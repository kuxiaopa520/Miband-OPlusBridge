// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.integration;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserManager;
import io.github.miam1ku.mibandoplusbridge.HostIdentity;
import io.github.miam1ku.mibandoplusbridge.data.BandStateRepository;
import io.github.miam1ku.mibandoplusbridge.data.BindingStore;
import io.github.miam1ku.mibandoplusbridge.data.HealthRecordStore;
import io.github.miam1ku.mibandoplusbridge.data.LocalPrefs;
import io.github.miam1ku.mibandoplusbridge.data.MacIds;
import io.github.miam1ku.mibandoplusbridge.data.RawFitnessFileStore;
import io.github.miam1ku.mibandoplusbridge.data.SessionLog;
import io.github.miam1ku.mibandoplusbridge.protocol.BandHistoryParser;
import io.github.miam1ku.mibandoplusbridge.protocol.MiFitnessHistoryParser;
import io.github.miam1ku.mibandoplusbridge.service.HealthReplay;
import io.github.miam1ku.mibandoplusbridge.service.OwnershipController;

/** Selected-device copies only. The official app retains its transport, acknowledgements and data. */
public final class MiFitnessMirrorProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://io.github.miam1ku.mibandoplusbridge.mi-mirror");
    public static final int MAX_FRAGMENT_BYTES = 128 * 1024;
    public static final Uri HISTORY_URI = URI.buildUpon().appendPath("history").build();
    public static final Uri HISTORY_REQUEST_URI = HISTORY_URI.buildUpon().appendPath("request").build();
    public static final String HISTORY_PREFS = "mi-history-import";
    public static final int HISTORY_PAGE_SIZE = 64;
    public static final int HISTORY_PAGE_CHARS = 192 * 1024;
    private BandHistoryParser parser;
    private String parserDevice = "";
    private String parserFirmware = "";
    private HealthReplay replay;
    private HealthRecordStore historyStore;

    @Override public boolean onCreate() {
        historyStore = new HealthRecordStore(getContext());
        return true;
    }

    @Override public synchronized Bundle call(String method, String arg, Bundle input) {
        boolean self = HostIdentity.isSelf();
        if (!self) HostIdentity.requireMiCaller(getContext());
        if ("historyStatus".equals(method)) {
            if (!self) throw new SecurityException("HISTORY_SELF_REQUIRED");
            return historyStatus();
        }
        if (!getContext().getSystemService(UserManager.class).isUserUnlocked()
                || !new OwnershipController(getContext()).coexistReady()
                || !new BandStateRepository(getContext()).isRegistered()) {
            parser = null;
            if (replay != null) { replay.close(); replay = null; }
            return status("MI_MIRROR_INACTIVE");
        }
        try {
            BindingStore.Identity binding = new BindingStore(getContext()).readIdentity();
            if (binding == null || binding.did().isBlank()) return status("MI_MIRROR_DID_REQUIRED");
            if ("historyStart".equals(method)) {
                if (!self) throw new SecurityException("HISTORY_SELF_REQUIRED");
                return startHistory(binding);
            }
            if ("historyJob".equals(method)) return historyJob(binding);
            if (replay == null && !"historyPage".equals(method) && !"historyError".equals(method)) {
                replay = new HealthReplay(getContext(), code -> {
                    getContext().getSharedPreferences("live-service", 0).edit()
                            .putString("healthCollectionStatus", code).apply();
                    SessionLog.line(getContext(), "mirror " + code);
                }, () -> {});
                replay.request();
            }
            if ("target".equals(method)) {
                Bundle target = status("MI_MIRROR_ACTIVE");
                target.putString("did", binding.did());
                target.putString("address", binding.address());
                return target;
            }
            if (self) throw new SecurityException("MI_CALLER_REQUIRED");
            if (input == null || !binding.did().equals(input.getString("did"))) {
                throw new SecurityException("MI_MIRROR_DEVICE_MISMATCH");
            }
            if ("historyPage".equals(method)) return importHistoryPage(binding, input);
            if ("historyError".equals(method)) {
                LocalPrefs prefs = LocalPrefs.open(getContext(), HISTORY_PREFS);
                if (prefs.getString("token", "").equals(input.getString("token"))) {
                    String error = input.getString("error", "MI_HISTORY_READ_FAILED");
                    if (!java.util.Set.of("MI_HISTORY_SOURCE_ACCOUNT_CHANGED", "MI_HISTORY_DATABASE_UNAVAILABLE",
                            "MI_HISTORY_SCHEMA_UNSUPPORTED", "MI_HISTORY_READ_FAILED").contains(error)) {
                        error = "MI_HISTORY_READ_FAILED";
                    }
                    if (!error.equals(prefs.getString("status", ""))) SessionLog.line(getContext(), error);
                    saveHistoryStatus(error);
                    getContext().getContentResolver().notifyChange(HISTORY_URI, null);
                }
                return historyStatus();
            }
            BandStateRepository repository = new BandStateRepository(getContext());
            if ("state".equals(method)) {
                if (!MacIds.same(binding.address(), input.getString("address", ""))) {
                    throw new SecurityException("MI_MIRROR_DEVICE_MISMATCH");
                }
                boolean connected = input.getBoolean("connected", false);
                repository.recordCompanionState(binding.address(), connected, input.getString("firmware"),
                        input.getInt("battery", -1), input.getInt("chargerState", 0));
                if (!connected && parser != null) parser.reset();
                return status("MI_MIRROR_STATE_SAVED");
            }
            if (!"fragment".equals(method)) throw new IllegalArgumentException("UNSUPPORTED_MIRROR_OPERATION");
            byte[] data = input.getByteArray("data");
            if (data == null || data.length < 5 || data.length > MAX_FRAGMENT_BYTES) {
                throw new IllegalArgumentException("INVALID_HISTORY_FRAGMENT");
            }
            String firmware = LocalPrefs.open(getContext(), "band-state").getString("verifiedFirmware", "");
            if (firmware.isBlank()) firmware = new BindingStore(getContext()).read().optString("firmware", "");
            if (firmware.isBlank()) return status("MI_MIRROR_FIRMWARE_REQUIRED");
            if (parser == null || !parserDevice.equals(binding.deviceId()) || !parserFirmware.equals(firmware)) {
                parser = new BandHistoryParser(firmware, binding.deviceId(), binding.did());
                parserDevice = binding.deviceId();
                parserFirmware = firmware;
            }
            // A new file also recovers from a provider restart or a failed previous IPC.
            if ((data[2] & 0xff) == 1 && data[3] == 0) parser.reset();
            BandHistoryParser.FileResult file = parser.acceptFragment(data);
            if (file == null) return status("MI_MIRROR_FRAGMENT_ACCEPTED");
            new RawFitnessFileStore(getContext()).persist(file, binding.deviceId(), firmware,
                    System.currentTimeMillis());
            repository.recordCompanionState(binding.address(), true, firmware, -1, 0);
            repository.recordSyncCompleted();
            replay.request();
            SessionLog.line(getContext(), "mirror file " + file.parseStatus + " records=" + file.measurements.size());
            return status("PARSED".equals(file.parseStatus) ? "MI_MIRROR_FILE_SAVED" : "MI_MIRROR_FORMAT_UNSUPPORTED");
        } catch (SecurityException denied) {
            throw denied;
        } catch (Exception failure) {
            if (method != null && method.startsWith("history")) {
                saveHistoryStatus("MI_HISTORY_STORAGE_RETRY");
                SessionLog.line(getContext(), "MI_HISTORY_STORAGE_RETRY " + failure.getClass().getSimpleName());
                return historyStatus();
            }
            if (parser != null) parser.reset();
            SessionLog.line(getContext(), "mirror paused " + failure.getClass().getSimpleName());
            return status("MI_MIRROR_RETRY_NEXT_FILE");
        }
    }

    private Bundle startHistory(BindingStore.Identity binding) throws Exception {
        String account = historyStore.confirmedAccountHash();
        if (account == null) return status("MI_HISTORY_OPEN_OPPO");
        String source = new BindingStore(getContext()).read().optString("userId", "");
        if (source.isBlank()) return status("MI_HISTORY_REIMPORT_BINDING");
        LocalPrefs prefs = LocalPrefs.open(getContext(), HISTORY_PREFS);
        if (!prefs.getBoolean("active", false) || !binding.deviceId().equals(prefs.getString("deviceId", ""))
                || !account.equals(prefs.getString("accountHash", ""))
                || !source.equals(prefs.getString("sourceAccount", ""))) {
            if (!prefs.edit().putBoolean("active", true).putString("token", java.util.UUID.randomUUID().toString())
                    .putString("deviceId", binding.deviceId()).putString("accountHash", account)
                    .putString("sourceAccount", source).putInt("table", 0).putLong("time", -1).putString("key", "")
                    .putLong("cutoff", System.currentTimeMillis() / 1000).putLong("rows", 0)
                    .putLong("added", 0).putLong("skipped", 0).putLong("sleepRows", 0)
                    .putString("lastSkip", "").putString("status", "MI_HISTORY_WAITING_MI").commit()) {
                throw new IllegalStateException("MI_HISTORY_PROGRESS_WRITE_FAILED");
            }
            SessionLog.line(getContext(), "MI_HISTORY_REQUESTED");
        }
        getContext().getContentResolver().notifyChange(HISTORY_REQUEST_URI, null);
        return historyStatus();
    }

    private Bundle historyStatus() {
        LocalPrefs prefs = LocalPrefs.open(getContext(), HISTORY_PREFS);
        Bundle result = status(prefs.getString("status", "MI_HISTORY_NOT_STARTED"));
        result.putBoolean("active", prefs.getBoolean("active", false));
        for (String key : new String[]{"rows", "added", "skipped", "sleepRows"}) {
            result.putLong(key, prefs.getLong(key, 0));
        }
        result.putString("lastSkip", prefs.getString("lastSkip", ""));
        return result;
    }

    private Bundle historyJob(BindingStore.Identity binding) {
        LocalPrefs prefs = LocalPrefs.open(getContext(), HISTORY_PREFS);
        if (!prefs.getBoolean("active", false)) return historyStatus();
        if (!binding.deviceId().equals(prefs.getString("deviceId", ""))) return status("MI_HISTORY_DEVICE_CHANGED");
        if (!prefs.getString("accountHash", "").equals(historyStore.confirmedAccountHash())) {
            saveHistoryStatus("MI_HISTORY_ACCOUNT_CHANGED");
            return status("MI_HISTORY_ACCOUNT_CHANGED");
        }
        Bundle result = historyStatus();
        result.putString("did", binding.did());
        result.putString("token", prefs.getString("token", ""));
        result.putString("sourceAccount", prefs.getString("sourceAccount", ""));
        result.putString("table", MiFitnessHistoryParser.TABLES.get(prefs.getInt("table", 0)));
        result.putLong("time", prefs.getLong("time", -1));
        result.putString("key", prefs.getString("key", ""));
        result.putLong("cutoff", prefs.getLong("cutoff", 0));
        return result;
    }

    private Bundle importHistoryPage(BindingStore.Identity binding, Bundle input) throws Exception {
        Bundle job = historyJob(binding);
        if (!job.containsKey("token")) return job;
        for (String key : new String[]{"token", "sourceAccount", "table", "key"}) {
            if (!job.getString(key, "").equals(input.getString(key, ""))) return job;
        }
        if (job.getLong("time") != input.getLong("time", -2)) return job;
        var rows = input.getParcelableArrayList("data", Bundle.class);
        if (rows == null || rows.size() > HISTORY_PAGE_SIZE) throw new IllegalArgumentException("MI_HISTORY_PAGE_INVALID");
        int chars = 0;
        for (Bundle row : rows) chars = Math.addExact(chars, row.getString("value", "").length());
        if (chars > HISTORY_PAGE_CHARS) throw new IllegalArgumentException("MI_HISTORY_PAGE_TOO_LARGE");
        LocalPrefs prefs = LocalPrefs.open(getContext(), HISTORY_PREFS);
        MiFitnessHistoryParser converter = new MiFitnessHistoryParser("mi-fitness-local-3.59.1", binding.deviceId(), binding.did());
        long after = job.getLong("time");
        String afterKey = job.getString("key", "");
        long count = prefs.getLong("rows", 0), added = prefs.getLong("added", 0);
        long skipped = prefs.getLong("skipped", 0), sleep = prefs.getLong("sleepRows", 0);
        String lastSkip = prefs.getString("lastSkip", "");
        String code = "MI_HISTORY_RUNNING";
        for (Bundle row : rows) {
            long time = row.getLong("time", -1);
            String key = row.getString("key", "");
            if (time < after || time == after && key.compareTo(afterKey) <= 0 || time > job.getLong("cutoff")) {
                throw new SecurityException("MI_HISTORY_CURSOR_INVALID");
            }
            try {
                var measurements = converter.parse(job.getString("table"), key, time, row.getString("value"),
                        row.getInt("zoneOffsetInSec"), row.getString("zoneName"), row.getBoolean("isComplete", true));
                added += historyStore.importMissing(prefs.getString("accountHash", ""), measurements);
                if ("sleep_segment".equals(job.getString("table"))) sleep++;
            } catch (org.json.JSONException | IllegalArgumentException invalid) {
                skipped++;
                lastSkip = invalid.getMessage() != null && invalid.getMessage().startsWith("MI_HISTORY_")
                        ? SessionLog.redact(invalid.getMessage()) : "MI_HISTORY_ROW_INVALID";
            } catch (IllegalStateException full) {
                if (!"HEALTH_OUTBOX_FULL".equals(full.getMessage())) throw full;
                code = "MI_HISTORY_OPEN_OPPO_QUEUE_FULL";
                break; // Keep this row's cursor so it resumes after OPPO drains the queue.
            }
            count++;
            after = time;
            afterKey = key;
        }
        int table = prefs.getInt("table", 0);
        boolean tableDone = input.getBoolean("done") && !"MI_HISTORY_OPEN_OPPO_QUEUE_FULL".equals(code);
        if (tableDone) { table++; after = -1; afterKey = ""; }
        boolean active = table < MiFitnessHistoryParser.TABLES.size();
        if (!active) code = "MI_HISTORY_LOCAL_COMPLETE";
        if (!prefs.edit().putBoolean("active", active).putInt("table", table).putLong("time", after)
                .putString("key", afterKey).putLong("rows", count).putLong("added", added)
                .putLong("skipped", skipped).putLong("sleepRows", sleep).putString("lastSkip", lastSkip)
                .putString("status", code).commit()) throw new IllegalStateException("MI_HISTORY_PROGRESS_WRITE_FAILED");
        if (tableDone || !"MI_HISTORY_RUNNING".equals(code)) {
            SessionLog.line(getContext(), code + " table=" + job.getString("table")
                    + " rows=" + count + " added=" + added + " skipped=" + skipped + " sleep=" + sleep);
        }
        getContext().getContentResolver().notifyChange(HealthQueueProvider.URI, null);
        getContext().getContentResolver().notifyChange(HealthQueueProvider.RECORDS_URI, null);
        getContext().getContentResolver().notifyChange(HISTORY_URI, null);
        return historyStatus();
    }

    private void saveHistoryStatus(String code) {
        LocalPrefs.open(getContext(), HISTORY_PREFS).edit().putString("status", code).commit();
    }

    private static Bundle status(String code) {
        Bundle result = new Bundle();
        result.putString("status", code);
        return result;
    }

    @Override public synchronized void shutdown() {
        if (replay != null) { replay.close(); replay = null; }
        if (historyStore != null) historyStore.close();
        super.shutdown();
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
