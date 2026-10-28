// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import io.github.miam1ku.mibandoplusbridge.protocol.BandHistoryParser.Measurement;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Mi Fitness 3.59.1 normalized Room rows. Times in this database are epoch seconds. */
public final class MiFitnessHistoryParser {
    public static final List<String> TABLES = List.of("sleep_segment", "step_record", "hr_record", "spo2_record", "stress_record", "sport_report");
    private final BandHistoryParser records;

    public MiFitnessHistoryParser(String firmware, String deviceId, String did) {
        records = new BandHistoryParser(firmware, deviceId, did);
    }

    public List<Measurement> parse(String table, String key, long time, String value,
            int offsetSeconds, String zoneName, boolean complete) throws JSONException {
        if (!TABLES.contains(table) || value == null || value.length() > 192 * 1024) {
            throw new IllegalArgumentException("MI_HISTORY_ROW_UNSUPPORTED");
        }
        String zone;
        try {
            zone = zoneName == null || zoneName.isBlank() ? ZoneOffset.ofTotalSeconds(offsetSeconds).getId()
                    : ZoneId.of(zoneName).getId();
        } catch (DateTimeException invalid) {
            zone = ZoneOffset.ofTotalSeconds(offsetSeconds).getId();
        }
        JSONObject json = new JSONObject(value);
        if ("sleep_segment".equals(table)) {
            if (!List.of("sleep", "watch_night_sleep", "watch_daytime_sleep").contains(key)) {
                throw new IllegalArgumentException("MI_HISTORY_KEY_UNSUPPORTED");
            }
            long start = seconds(json.getLong("bedtime"));
            long end = seconds(json.getLong("wake_up_time"));
            if (end <= start || end - start > 48 * 3_600_000L) {
                throw new IllegalArgumentException("MI_HISTORY_SLEEP_TIME_INVALID");
            }
            JSONArray items = json.optJSONArray("items");
            if (items != null && items.length() > 4096) throw new IllegalArgumentException("MI_HISTORY_SLEEP_TOO_LARGE");
            List<Measurement> stages = new ArrayList<>();
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                long a = Math.max(start, seconds(item.getLong(item.has("start_time") ? "start_time" : "start")));
                long b = Math.min(end, seconds(item.getLong(item.has("end_time") ? "end_time" : "end")));
                int stage = item.getInt("state");
                if (stage == 0) stage = 5; // NOT_SLEEP is excluded from sleep duration.
                if (stage < 2 || stage > 6) throw new IllegalArgumentException("MI_HISTORY_SLEEP_STAGE_UNSUPPORTED");
                if (b > a) stages.add(records.sleepStage(a, b, stage, zone));
            }
            stages.sort(Comparator.comparingLong(item -> item.startMs));
            List<Measurement> result = new ArrayList<>();
            for (Measurement stage : stages) {
                if (!result.isEmpty()) {
                    Measurement previous = result.get(result.size() - 1);
                    if (previous.endMs > stage.startMs) throw new IllegalArgumentException("MI_HISTORY_SLEEP_OVERLAP");
                    if (previous.endMs == stage.startMs && previous.stage.equals(stage.stage)) {
                        result.set(result.size() - 1, records.sleepStage(previous.startMs, stage.endMs, stage.stage, zone));
                        continue;
                    }
                }
                result.add(stage);
            }
            // A summary without stages must not turn its awake minutes into invented sleep.
            if (result.isEmpty() && (!json.has("duration")
                    || Math.abs(json.getLong("duration") * 60_000L - (end - start)) >= 60_000L)) {
                throw new IllegalArgumentException("MI_HISTORY_SLEEP_DETAILS_REQUIRED");
            }
            result.add(0, records.measurement("sleep_interval", start, end, null, zone, "sleep",
                    complete && !json.optBoolean("is_uncomplete", false), null, null, null));
            return result;
        }
        if ("sport_report".equals(table)) {
            int mode = switch (json.getInt("sport_type")) {
                case 1 -> 2;
                case 2, 207 -> 1;
                case 3 -> 10;
                case 4 -> 36;
                case 5 -> 37;
                case 6 -> 3;
                case 8 -> 35;
                case 9 -> 7;
                case 10 -> 809;
                case 14 -> 908;
                case 15 -> 506;
                case 333 -> 19;
                default -> throw new IllegalArgumentException("MI_HISTORY_SPORT_TYPE_UNSUPPORTED");
            };
            long start = seconds(json.getLong("start_time")), end = seconds(json.getLong("end_time"));
            if (end <= start || end - start > 48 * 3_600_000L) throw new IllegalArgumentException("MI_HISTORY_SPORT_TIME_INVALID");
            Integer calories = json.has("total_cal") && !json.isNull("total_cal") ? json.getInt("total_cal")
                    : json.has("calories") && !json.isNull("calories") ? json.getInt("calories") : null;
            Integer distance = json.has("distance") && !json.isNull("distance") ? json.getInt("distance") : null;
            Integer reps = mode != 908 && json.has("steps") && !json.isNull("steps") ? json.getInt("steps") : null;
            Integer heart = json.has("avg_hrm") && !json.isNull("avg_hrm") ? json.getInt("avg_hrm") : null;
            if (heart != null && (heart < 1 || heart > 250)) heart = null;
            Integer duration = json.has("duration") && !json.isNull("duration") ? json.getInt("duration") : null;
            if (duration != null && duration == 0) duration = null;
            Measurement summary = records.sportSummary(new SportReportParser.Session(mode, "", start, end,
                    calories, distance, reps, heart, duration, null), zone);
            summary.toRecord(1);
            return List.of(summary);
        }
        long start = seconds(time);
        String kind;
        String mode = "continuous";
        Object sourceValue;
        Integer calories = null;
        Integer distance = null;
        switch (table) {
            case "step_record" -> {
                if (!"steps".equals(key)) throw new IllegalArgumentException("MI_HISTORY_KEY_UNSUPPORTED");
                kind = "steps_interval";
                sourceValue = json.get("steps");
                if (json.has("calories")) {
                    double amount = json.getDouble("calories");
                    if (!Double.isFinite(amount) || amount < 0 || amount > 100_000) {
                        throw new IllegalArgumentException("MI_HISTORY_CALORIES_INVALID");
                    }
                    calories = (int) Math.round(amount);
                }
                if (json.has("distance")) distance = json.getInt("distance");
            }
            case "hr_record" -> {
                kind = "heart_rate";
                mode = switch (key) {
                    case "heart_rate" -> "continuous";
                    case "single_heart_rate" -> "manual";
                    case "resting_heart_rate" -> "resting";
                    default -> throw new IllegalArgumentException("MI_HISTORY_KEY_UNSUPPORTED");
                };
                sourceValue = json.get("bpm");
            }
            case "spo2_record", "stress_record" -> {
                kind = "spo2_record".equals(table) ? "spo2" : "stress";
                if (key.equals("single_" + kind)) mode = "manual";
                else if (!key.equals(kind)) throw new IllegalArgumentException("MI_HISTORY_KEY_UNSUPPORTED");
                sourceValue = json.get(kind);
            }
            default -> throw new IllegalArgumentException("MI_HISTORY_TABLE_UNSUPPORTED");
        }
        if (!(sourceValue instanceof Number measured)) throw new IllegalArgumentException("MI_HISTORY_VALUE_INVALID");
        Measurement record = records.measurement(kind, start, start + ("continuous".equals(mode) ? 60_000 : 1),
                measured, zone, mode, false, calories, distance, null);
        record.toRecord(1);
        return List.of(record);
    }

    private static long seconds(long time) {
        if (time < 946684800L || time > 4102444800L) throw new IllegalArgumentException("MI_HISTORY_TIMESTAMP_INVALID");
        return time * 1000L;
    }
}
