// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import io.github.miam1ku.mibandoplusbridge.data.BandStateRepository;
import io.github.miam1ku.mibandoplusbridge.hook.OHealthSleepPlan;
import java.time.ZoneId;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class MiFitnessHistoryParserTest {
    private static final long AT = 1700000000L;
    private static final String DID = "history-test-band";

    private static MiFitnessHistoryParser parser() throws Exception {
        return new MiFitnessHistoryParser("1.0", BandStateRepository.deviceId(new JSONObject().put("did", DID)), DID);
    }

    @Test public void oldSleepReachesTheNativeSleepPlanWithCorrectStagesAndDurations() throws Exception {
        long bed = 1577833200L; // 2019-12-31 23:00 UTC, well beyond a recent-history window.
        String value = new JSONObject().put("bedtime", bed).put("wake_up_time", bed + 4 * 3600)
                .put("duration", 180).put("items", new org.json.JSONArray()
                        .put(new JSONObject().put("start_time", bed).put("end_time", bed + 3600).put("state", 2))
                        .put(new JSONObject().put("start_time", bed + 3600).put("end_time", bed + 7200).put("state", 3))
                        .put(new JSONObject().put("start_time", bed + 7200).put("end_time", bed + 10800).put("state", 4))
                        .put(new JSONObject().put("start_time", bed + 10800).put("end_time", bed + 14400).put("state", 5)))
                .toString();
        var records = parser().parse("sleep_segment", "sleep", bed + 14400, value, 0, "UTC", true);
        assertEquals(5, records.size());
        assertTrue(records.get(0).complete);
        var nights = OHealthSleepPlan.nights(records.stream().map(record -> record.toRecord(1)).toList(), ZoneId.of("UTC"));
        assertEquals(1, nights.size());
        var night = nights.get(0);
        assertEquals(20200101, night.date());
        assertEquals(180, night.sleepMinutes());
        assertEquals(60, night.deepMinutes());
        assertEquals(60, night.lightMinutes());
        assertEquals(60, night.remMinutes());
        assertEquals(60, night.wakeMinutes());
    }

    @Test public void alternateSleepKeysAndAdjacentRunsKeepTheSameLiveIdentity() throws Exception {
        String value = "{\"bedtime\":1700000000,\"wake_up_time\":1700000120,\"items\":["
                + "{\"start\":1700000000,\"end\":1700000060,\"state\":3},"
                + "{\"start\":1700000060,\"end\":1700000120,\"state\":3}]}";
        var rows = parser().parse("sleep_segment", "watch_night_sleep", AT, value, 28800, null, true);
        assertEquals(2, rows.size());
        assertEquals("+08:00", rows.get(0).timezone);
        var live = new BandHistoryParser("1.0", rows.get(0).deviceId, DID).sleepStage(AT * 1000, (AT + 120) * 1000, 3, "+08:00");
        assertEquals(live.recordId, rows.get(1).recordId);
        assertEquals(live.sourceFingerprint, rows.get(1).sourceFingerprint);
    }

    @Test public void missingStagesNeverInventDeepLightOrRemOrCountAwakeAsSleep() throws Exception {
        String full = "{\"bedtime\":1700000000,\"wake_up_time\":1700003600,\"duration\":60,\"is_uncomplete\":true}";
        var rows = parser().parse("sleep_segment", "sleep", AT, full, 0, "UTC", true);
        assertEquals(1, rows.size());
        assertFalse(rows.get(0).complete);
        assertThrows(IllegalArgumentException.class, () -> parser().parse("sleep_segment", "sleep", AT,
                full.replace("\"duration\":60", "\"duration\":50"), 0, "UTC", true));
    }

    @Test public void stepRowsAreMinuteDeltasAndPreserveDistanceAndCalories() throws Exception {
        var row = parser().parse("step_record", "steps", AT, "{\"steps\":123,\"calories\":5.4,\"distance\":88}",
                0, "UTC", true).get(0);
        assertEquals("steps_interval", row.kind);
        assertEquals(123, row.value.intValue());
        assertEquals(60_000, row.endMs - row.startMs);
        assertEquals(Integer.valueOf(5), row.calories);
        assertEquals(Integer.valueOf(88), row.distance);
    }

    @Test public void savedSportSummariesUseTheLiveRecordIdentityAndPreserveTheirMeasuredValues() throws Exception {
        String json = "{\"sport_type\":1,\"start_time\":1700000000,\"end_time\":1700003600,"
                + "\"duration\":3000,\"distance\":5200,\"total_cal\":365,\"calories\":330,\"steps\":6000,\"avg_hrm\":122}";
        var row = parser().parse("sport_report", "1", AT, json, 28800, "Asia/Shanghai", true).get(0);
        var live = new BandHistoryParser("1.0", row.deviceId, DID).sportSummary(
                new SportReportParser.Session(2, "", AT * 1000, (AT + 3600) * 1000,
                        365, 5200, 6000, 122, 3000, null), "Asia/Shanghai");
        assertEquals("sport_session", row.kind);
        assertEquals(2, row.value.intValue());
        assertEquals(live.recordId, row.recordId);
        assertEquals(live.sourceFingerprint, row.sourceFingerprint);
        assertEquals(Integer.valueOf(365), row.calories);
        assertEquals(Integer.valueOf(5200), row.distance);
        assertEquals(Integer.valueOf(3000), row.duration);
        assertEquals(Integer.valueOf(122), row.heart);
        assertThrows(IllegalArgumentException.class, () -> parser().parse("sport_report", "99999", AT,
                json.replace("\"sport_type\":1", "\"sport_type\":99999"), 0, "UTC", true));
        assertThrows(IllegalArgumentException.class, () -> parser().parse("sport_report", "1", AT,
                json.replace("1700003600", "1699999999"), 0, "UTC", true));
    }

    @Test public void minuteCaloriesReachTheDailyActivityTotalWithoutReplacingAnAuthoritativeDailyReport() throws Exception {
        var one = parser().parse("step_record", "steps", AT, "{\"steps\":100,\"calories\":2}", 0, "UTC", true).get(0).toRecord(1);
        var two = parser().parse("step_record", "steps", AT + 60, "{\"steps\":200,\"calories\":3}", 0, "UTC", true).get(0).toRecord(1);
        var day = BandHistoryParser.stepDays(java.util.List.of(one, two)).get(0);
        assertEquals(300, day.steps);
        assertEquals(5, day.calories);
        assertEquals(-1, day.moveAbout);
        var report = new io.github.miam1ku.mibandoplusbridge.data.HealthRecord("daily", one.deviceId, "steps_day",
                AT * 1000, (AT + 86400) * 1000, 600, null, 1, "UTC", "continuous", false, 20, null, null);
        var authoritative = BandHistoryParser.stepDays(java.util.List.of(one, two, report)).get(0);
        assertEquals(600, authoritative.steps);
        assertEquals(20, authoritative.calories);
    }

    @Test public void manualAndRestingHeartValuesKeepTheirOwnModes() throws Exception {
        var continuous = parser().parse("hr_record", "heart_rate", AT, "{\"bpm\":72}", 0, "UTC", true).get(0);
        var manual = parser().parse("hr_record", "single_heart_rate", AT, "{\"bpm\":72}", 0, "UTC", true).get(0);
        var resting = parser().parse("hr_record", "resting_heart_rate", AT, "{\"bpm\":62}", 0, "UTC", true).get(0);
        assertEquals(AT * 1000, continuous.startMs);
        assertEquals(60_000, continuous.endMs - continuous.startMs);
        assertEquals(1, manual.endMs - manual.startMs);
        assertEquals("manual", manual.measurementMode);
        assertEquals("resting", resting.measurementMode);
        assertNotEquals(continuous.recordId, manual.recordId);
        assertNotEquals(manual.recordId, resting.recordId);
    }

    @Test public void oxygenAndStressUseTheStoredValueRatherThanSyntheticAverages() throws Exception {
        var oxygen = parser().parse("spo2_record", "single_spo2", AT, "{\"spo2\":97}", 0, "UTC", true).get(0);
        var stress = parser().parse("stress_record", "stress", AT, "{\"stress\":35}", 0, "UTC", true).get(0);
        assertEquals(97, oxygen.value.intValue());
        assertEquals("manual", oxygen.measurementMode);
        assertEquals(35, stress.value.intValue());
        assertEquals("continuous", stress.measurementMode);
    }

    @Test public void unknownKeysAndWrongTimestampUnitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> parser().parse("hr_record", "future_heart_kind", AT,
                "{\"bpm\":72}", 0, "UTC", true));
        assertThrows(IllegalArgumentException.class, () -> parser().parse("hr_record", "heart_rate", AT * 1000,
                "{\"bpm\":72}", 0, "UTC", true));
        assertThrows(IllegalArgumentException.class, () -> parser().parse("spo2_record", "spo2", AT,
                "{\"spo2\":200}", 0, "UTC", true));
    }
}
