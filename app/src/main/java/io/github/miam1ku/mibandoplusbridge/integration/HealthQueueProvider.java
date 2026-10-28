// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.integration;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Process;
import android.os.UserManager;
import io.github.miam1ku.mibandoplusbridge.HostIdentity;
import io.github.miam1ku.mibandoplusbridge.protocol.BandHistoryParser.Measurement;
import io.github.miam1ku.mibandoplusbridge.data.HealthRecordStore;
import org.json.JSONObject;

/** Pending writes in credential-encrypted storage. Callers are checked by package name and read only the confirmed account. */
public final class HealthQueueProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.miam1ku.mibandoplusbridge.health";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/pending");
    public static final Uri RECORDS_URI = Uri.parse("content://" + AUTHORITY + "/records");
    public static final Uri CAPACITY_URI = Uri.parse("content://" + AUTHORITY + "/capacity");
    public static final Uri PROGRESS_URI = Uri.parse("content://" + AUTHORITY + "/import-progress");
    private static final String HOST = "com.heytap.health";
    private static final String[] COLUMNS = {"recordId", "revision", "record"};
    private HealthRecordStore store;
    private String proposedFingerprint;
    private long proposedAtMs;

    @Override public boolean onCreate() {
        store = new HealthRecordStore(getContext());
        return true;
    }

    private void requireUnlocked() {
        if (!getContext().getSystemService(UserManager.class).isUserUnlocked()) {
            throw new SecurityException("HEALTH_USER_LOCKED");
        }
    }

    private boolean requireCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return true;
        if (!io.github.miam1ku.mibandoplusbridge.HostIdentity.uidHas(getContext(), uid, HOST)) {
            throw new SecurityException("HEALTH_CALLER_NOT_AUTHORIZED");
        }
        return false;
    }

    private static void requireSelf(boolean self) {
        if (!self) throw new SecurityException("HEALTH_SELF_REQUIRED");
    }

    private static boolean recordsUri(Uri uri) {
        if (URI.equals(uri)) return false;
        if (uri == null || !RECORDS_URI.equals(uri.buildUpon().clearQuery().build())
                || !java.util.Set.of("after").containsAll(uri.getQueryParameterNames())
                || uri.getQueryParameters("after").size() > 1) {
            throw new IllegalArgumentException("UNSUPPORTED_HEALTH_URI");
        }
        return true;
    }

    private void requireCollectionReady(String deviceId) {
        if (!store.ownsDeviceArchive(deviceId)) {
            throw new SecurityException("HEALTH_ARCHIVE_OWNERSHIP_REQUIRED");
        }
    }

    @Override public synchronized Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        requireCaller();
        requireUnlocked();
        boolean history = recordsUri(uri);
        if (sortOrder != null || projection != null && !java.util.Arrays.equals(projection, COLUMNS)) {
            throw new IllegalArgumentException("UNSUPPORTED_HEALTH_PROJECTION");
        }
        Cursor cursor;
        if (history) {
            if (!"account=? AND deviceId=? AND kind=? AND startMs<? AND endMs>?".equals(selection)
                    || selectionArgs == null || selectionArgs.length != 5) {
                throw new IllegalArgumentException("HEALTH_RECORD_SELECTION_REQUIRED");
            }
            if ("steps_day".equals(selectionArgs[2]) || "steps_interval".equals(selectionArgs[2])) {
                new io.github.miam1ku.mibandoplusbridge.data.RawFitnessFileStore(getContext()).refreshStepMetrics();
            }
            if ("sleep_interval".equals(selectionArgs[2]) || "sleep_stage".equals(selectionArgs[2])
                    || "sleep_breath".equals(selectionArgs[2])) {
                new io.github.miam1ku.mibandoplusbridge.data.RawFitnessFileStore(getContext()).refreshSleepBreathMetrics();
            }
            cursor = store.records(selectionArgs[0], selectionArgs[1], selectionArgs[2],
                    Long.parseLong(selectionArgs[4]), Long.parseLong(selectionArgs[3]), uri.getQueryParameter("after"));
        } else {
            if (!"account=?".equals(selection) || selectionArgs == null || selectionArgs.length != 1) {
                throw new IllegalArgumentException("HEALTH_ACCOUNT_SELECTION_REQUIRED");
            }
            cursor = store.pending(selectionArgs[0]);
        }
        cursor.setNotificationUri(getContext().getContentResolver(), history ? RECORDS_URI : URI);
        return cursor;
    }

    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        boolean self = requireCaller();
        requireUnlocked();
        if (method == null) throw new IllegalArgumentException("UNSUPPORTED_HEALTH_OPERATION");
        switch (method) {
            case "adoptAccount": {
                if (self || extras == null) throw new SecurityException("OHEALTH_CALLER_REQUIRED");
                String account = extras.getString("account");
                if (account == null || account.isBlank() || account.length() > 512
                        || "com.heytap.health".equals(account)) {
                    return status(store.accountState());
                }
                store.confirmAccountHash(HealthRecordStore.hashAccount(account));
                notifyRecordsChanged();
                return status("HEALTH_ACCOUNT_CONFIRMED");
            }
            case "proposeAccount": {
                if (self || extras == null) throw new SecurityException("OHEALTH_CALLER_REQUIRED");
                String account = extras.getString("account");
                proposedFingerprint = HealthRecordStore.hashAccount(account);
                proposedAtMs = SystemClock.elapsedRealtime();
                store.authorizedAccount(account); // The account Health presented becomes the write target. 
                notifyRecordsChanged();
                return status("ACCOUNT_PROPOSED");
            }
            case "confirmProposedAccount": {
                requireSelf(self);
                if (extras == null || !extras.getBoolean("userConfirmed", false)) {
                    throw new SecurityException("HEALTH_ACCOUNT_USER_CONFIRMATION_REQUIRED");
                }
                if (proposedFingerprint == null || SystemClock.elapsedRealtime() - proposedAtMs > 300_000) {
                    throw new IllegalStateException("HEALTH_ACCOUNT_PROPOSAL_EXPIRED");
                }
                if (!proposedFingerprint.equals(extras.getString("expectedFingerprint"))) {
                    throw new SecurityException("HEALTH_ACCOUNT_PROPOSAL_CHANGED");
                }
                store.confirmAccountHash(proposedFingerprint);
                proposedFingerprint = null;
                notifyRecordsChanged();
                return status("HEALTH_ACCOUNT_CONFIRMED");
            }
            case "enqueueMeasurement": {
                requireSelf(self);
                if (extras == null || extras.getString("measurement") == null) {
                    throw new IllegalArgumentException("HEALTH_MEASUREMENT_REQUIRED");
                }
                try {
                    Measurement measurement = Measurement.fromJson(
                            new JSONObject(extras.getString("measurement")));
                    requireCollectionReady(measurement.deviceId);
                    HealthRecordStore.EnqueueResult queued = store.enqueueMeasurement(measurement);
                    if (queued.added()) notifyRecordsChanged();
                    Bundle result = status(queued.added() ? "HEALTH_QUEUED" : "HEALTH_ALREADY_QUEUED");
                    result.putInt("revision", queued.revision());
                    return result;
                } catch (org.json.JSONException malformed) {
                    throw new IllegalArgumentException("INVALID_HEALTH_MEASUREMENT", malformed);
                }
            }
            case "status": {
                requireSelf(self);
                Bundle result = status(store.accountState());
                result.putInt("pendingPoints", store.count());
                var progress = io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(getContext(), "health-import-progress");
                if (java.util.Objects.equals(store.confirmedAccountHash(), progress.getString("accountHash", ""))) {
                    result.putString("phase", progress.getString("phase", ""));
                    result.putString("error", progress.getString("error", ""));
                    result.putLong("confirmedPoints", progress.getLong("confirmedPoints", 0));
                    result.putInt("sleepDone", progress.getInt("sleepDone", 0));
                    result.putInt("sleepTotal", progress.getInt("sleepTotal", 0));
                    result.putLong("updatedAtMs", progress.getLong("updatedAtMs", 0));
                }
                if (proposedFingerprint != null
                        && !proposedFingerprint.equals(store.confirmedAccountHash())
                        && SystemClock.elapsedRealtime() - proposedAtMs <= 300_000) {
                    result.putString("proposedFingerprintFull", proposedFingerprint);
                    result.putString("proposedFingerprint", proposedFingerprint.substring(0, 12));
                    result.putLong("proposedRemainingMs", 300_000 - (SystemClock.elapsedRealtime() - proposedAtMs));
                }
                return result;
            }
            case "importProgress": {
                if (self || extras == null) throw new SecurityException("OHEALTH_CALLER_REQUIRED");
                String hash = HealthRecordStore.hashAccount(extras.getString("account"));
                if (!hash.equals(store.confirmedAccountHash())) throw new SecurityException("HEALTH_ACCOUNT_NOT_CONFIRMED");
                String phase = extras.getString("phase", "");
                if (!java.util.Set.of("SLEEP", "POINTS", "STEPS", "SPORT", "CLEANUP", "RETRY", "DONE", "WAIT_DEVICE", "ERROR").contains(phase)) {
                    throw new IllegalArgumentException("INVALID_HEALTH_PROGRESS");
                }
                var progress = io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(getContext(), "health-import-progress");
                var edit = progress.edit();
                if (!hash.equals(progress.getString("accountHash", ""))) edit.putLong("confirmedPoints", 0);
                edit.putString("accountHash", hash).putString("phase", phase)
                        .putString("error", io.github.miam1ku.mibandoplusbridge.data.SessionLog.redact(extras.getString("error", "")))
                        .putLong("updatedAtMs", System.currentTimeMillis());
                if (extras.containsKey("sleepDone")) edit.putInt("sleepDone", Math.max(0, extras.getInt("sleepDone")))
                        .putInt("sleepTotal", Math.max(0, extras.getInt("sleepTotal")));
                edit.commit();
                getContext().getContentResolver().notifyChange(PROGRESS_URI, null);
                return status("HEALTH_PROGRESS_SAVED");
            }
            case "historyVersion":
            case "sleepVersion": {
                if (self || extras == null) throw new SecurityException("OHEALTH_CALLER_REQUIRED");
                Bundle result = status("HEALTH_HISTORY_VERSION");
                result.putString("version", store.historyVersion(extras.getString("account"), extras.getString("deviceId"),
                        "sleepVersion".equals(method) ? "sleep" : extras.getString("family", "")));
                return result;
            }
            case "ackBatch": {
                if (self || extras == null || !extras.keySet().equals(java.util.Set.of("account", "recordIds", "revisions"))) {
                    throw new SecurityException("HEALTH_RECEIPT_REQUIRED");
                }
                int removed = store.acknowledgeBatch(extras.getString("account"), extras.getStringArray("recordIds"),
                        extras.getIntArray("revisions"));
                if (removed > 0) {
                    var progress = io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(getContext(), "health-import-progress");
                    String hash = HealthRecordStore.hashAccount(extras.getString("account"));
                    long previous = hash.equals(progress.getString("accountHash", "")) ? progress.getLong("confirmedPoints", 0) : 0;
                    progress.edit().putString("accountHash", hash).putString("phase", "POINTS")
                            .putLong("confirmedPoints", previous + removed).putLong("updatedAtMs", System.currentTimeMillis()).commit();
                    getContext().getContentResolver().notifyChange(CAPACITY_URI, null);
                    getContext().getContentResolver().notifyChange(PROGRESS_URI, null);
                }
                Bundle result = status("HEALTH_ACKNOWLEDGED");
                result.putInt("acknowledged", removed);
                return result;
            }
            case "ack": {
                if (self) throw new SecurityException("OHEALTH_CALLER_REQUIRED");
                if (extras == null || !extras.keySet().equals(java.util.Set.of("account", "recordId", "revision"))) {
                    throw new IllegalArgumentException("HEALTH_RECEIPT_REQUIRED");
                }
                int removed = store.acknowledge(extras.getString("account"), extras.getString("recordId"),
                        extras.getInt("revision", 0));
                if (removed != 0) getContext().getContentResolver().notifyChange(CAPACITY_URI, null);
                Bundle result = status("HEALTH_ACKNOWLEDGED");
                result.putInt("acknowledged", removed);
                return result;
            }
            case "releaseUnsupported": {
                if (self) throw new SecurityException("OHEALTH_CALLER_REQUIRED");
                if (extras == null || !extras.keySet().equals(java.util.Set.of("account", "recordId", "revision"))) {
                    throw new IllegalArgumentException("HEALTH_RECEIPT_REQUIRED");
                }
                int removed;
                try {
                    removed = store.releaseUnsupported(extras.getString("account"), extras.getString("recordId"),
                            extras.getInt("revision", 0));
                } catch (org.json.JSONException malformed) {
                    throw new IllegalArgumentException("INVALID_HEALTH_RECORD", malformed);
                }
                if (removed != 0) getContext().getContentResolver().notifyChange(CAPACITY_URI, null);
                Bundle result = status("HEALTH_HOST_UNSUPPORTED");
                result.putInt("released", removed);
                return result;
            }
            case "unmeasuredStress": {
                if (self) throw new SecurityException("OHEALTH_CALLER_REQUIRED");
                String account = account(extras);
                var gaps = store.unmeasuredStress(account);
                long[] starts = new long[gaps.size()];
                long[] ends = new long[gaps.size()];
                String[] devices = new String[gaps.size()];
                for (int i = 0; i < gaps.size(); i++) {
                    starts[i] = gaps.get(i).startMs();
                    ends[i] = gaps.get(i).endMs();
                    devices[i] = gaps.get(i).deviceId();
                }
                Bundle result = status("STRESS_GAPS");
                result.putLongArray("starts", starts);
                result.putLongArray("ends", ends);
                result.putStringArray("devices", devices);
                return result;
            }
            case "forgetUnmeasuredStress": {
                if (self) throw new SecurityException("OHEALTH_CALLER_REQUIRED");
                int removed = store.forgetUnmeasuredStress(account(extras));
                if (removed != 0) notifyRecordsChanged();
                Bundle result = status("STRESS_ZEROS_FORGOTTEN");
                result.putInt("removed", removed);
                return result;
            }
            default:
                throw new IllegalArgumentException("UNSUPPORTED_HEALTH_OPERATION");
        }
    }

    private void notifyRecordsChanged() {
        getContext().getContentResolver().notifyChange(URI, null);
        getContext().getContentResolver().notifyChange(RECORDS_URI, null);
    }

    private Bundle status(String code) {
        Bundle result = new Bundle();
        result.putString("status", code);
        result.putInt("pendingCount", store.count());
        return result;
    }

    private static String account(Bundle extras) {
        if (extras == null || !extras.keySet().equals(java.util.Set.of("account"))) {
            throw new IllegalArgumentException("HEALTH_ACCOUNT_REQUIRED");
        }
        return extras.getString("account");
    }

    @Override public String getType(Uri uri) {
        requireCaller();
        return "vnd.android.cursor.dir/vnd." + AUTHORITY + (recordsUri(uri) ? ".records" : ".pending");
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        requireCaller();
        throw new SecurityException("HEALTH_CALL_ONLY");
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        requireCaller();
        throw new SecurityException("HEALTH_CALL_ONLY");
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        requireCaller();
        throw new SecurityException("HEALTH_READBACK_REQUIRED");
    }
}
