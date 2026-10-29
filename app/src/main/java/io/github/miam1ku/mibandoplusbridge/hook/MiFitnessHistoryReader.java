// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.app.Application;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Handler;
import android.os.FileObserver;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.HookRuntime;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedHelpers;
import io.github.miam1ku.mibandoplusbridge.integration.MiFitnessMirrorProvider;
import io.github.miam1ku.mibandoplusbridge.protocol.MiFitnessHistoryParser;
import java.io.File;
import java.util.ArrayList;

/** Runs in Mi Fitness, reading only its current account DB, including committed WAL rows. */
final class MiFitnessHistoryReader {
    private final Context context;
    private final ClassLoader loader;
    private final Handler worker;
    private final Runnable work = this::run;
    private final Runnable poll = this::requestAuto;
    private boolean waitingCapacity;
    private long capacityWakeAt;
    private boolean autoRequested = true;
    private FileObserver sourceChanges;
    private String watchedPath;

    private MiFitnessHistoryReader(Context context, ClassLoader loader) {
        this.context = context;
        this.loader = loader;
        worker = new Handler(HookRuntime.thread("OplusBandMiHistory").getLooper());
    }

    static void install(Context context, ClassLoader loader) {
        // The device, widget and transport processes must not race the main process's importer.
        if (!"com.mi.health".equals(Application.getProcessName())) return;
        MiFitnessHistoryReader reader = new MiFitnessHistoryReader(context, loader);
        HookRuntime.track(() -> { if (reader.sourceChanges != null) reader.sourceChanges.stopWatching(); });
        try {
            HostSyncWake.install(context, Class.forName("com.mi.health.search.SettingSearchProvider", false, loader), reader::requestAuto);
        } catch (ReflectiveOperationException unavailable) { HookRuntime.log("MI_SILENT_WAKE_UNAVAILABLE"); }
        HookRuntime.observe(context.getContentResolver(), MiFitnessMirrorProvider.HISTORY_REQUEST_URI, false,
                new ContentObserver(reader.worker) {
                    @Override public void onChange(boolean selfChange) { reader.schedule(500); }
                });
        HookRuntime.observe(context.getContentResolver(), io.github.miam1ku.mibandoplusbridge.integration.HealthQueueProvider.CAPACITY_URI, false,
                new ContentObserver(reader.worker) {
                    @Override public void onChange(boolean selfChange) {
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (reader.waitingCapacity && now - reader.capacityWakeAt >= 1500) {
                            reader.capacityWakeAt = now;
                            reader.schedule(250);
                        }
                    }
                });
        reader.schedule(5_000); // Also resume a request after either process was restarted.
    }

    private void requestAuto() {
        worker.post(() -> {
            if (autoRequested) return;
            autoRequested = true;
            worker.removeCallbacks(poll);
            schedule(2_000);
        });
    }

    private void schedule(long delay) {
        worker.removeCallbacks(work);
        worker.postDelayed(work, delay);
    }

    private void run() {
        waitingCapacity = false;
        Bundle job = null;
        try {
            if (autoRequested) {
                MiFitnessDeviceImage.request(context, loader);
                context.getContentResolver().call(MiFitnessMirrorProvider.URI, "historyAuto", null, null);
                autoRequested = false;
            }
            for (int page = 0; page < 8; page++) {
                if (Thread.currentThread().isInterrupted()) return;
                job = context.getContentResolver().call(MiFitnessMirrorProvider.URI, "historyJob", null, null);
                if (job == null || !job.containsKey("token")) { idle(); return; }
                Class<?> type = Class.forName("com.xiaomi.fit.fitness.persist.db.FitnessDatabase", false, loader);
                Object companion = XposedHelpers.getStaticObjectField(type, "Companion");
                Object room = XposedHelpers.callMethod(companion, "getInstance");
                Object helper = XposedHelpers.callMethod(room, "getOpenHelper");
                Object hostDb = XposedHelpers.callMethod(helper, "getReadableDatabase");
                String path = (String) XposedHelpers.callMethod(hostDb, "getPath");
                // Never close the host's Room database. Open and close our own read-only handle.
                if (!sourceAccountMatches(path, job.getString("sourceAccount", ""))) {
                    report(job, "MI_HISTORY_SOURCE_ACCOUNT_CHANGED");
                    return;
                }
                watchSource(path);
                Bundle batch;
                try (SQLiteDatabase db = SQLiteDatabase.openDatabase(path, null,
                        SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {
                    batch = readPage(db, job);
                }
                Bundle reply = context.getContentResolver().call(MiFitnessMirrorProvider.URI, "historyPage", null, batch);
                if (reply == null || !reply.getBoolean("active")) { idle(); return; }
                String status = reply.getString("status", "");
                if ("MI_HISTORY_OPEN_OPPO_QUEUE_FULL".equals(status) || "MI_HISTORY_STORAGE_RETRY".equals(status)) {
                    waitingCapacity = true;
                    schedule(10_000);
                    return;
                }
            }
            schedule(25);
        } catch (Throwable failure) {
            String code = failure instanceof android.database.sqlite.SQLiteException
                    ? "MI_HISTORY_SCHEMA_UNSUPPORTED" : "MI_HISTORY_DATABASE_UNAVAILABLE";
            HookRuntime.log(code + " " + failure.getClass().getSimpleName());
            if (job != null && job.containsKey("token")) report(job, code);
            schedule(30_000);
        }
    }

    private void idle() {
        worker.removeCallbacks(poll);
        worker.postDelayed(poll, 60_000);
    }

    private void watchSource(String path) {
        if (path.equals(watchedPath)) return;
        if (sourceChanges != null) sourceChanges.stopWatching();
        File db = new File(path);
        sourceChanges = new FileObserver(db.getParentFile(), FileObserver.MODIFY | FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
            @Override public void onEvent(int event, String name) {
                if (db.getName().equals(name) || (db.getName() + "-wal").equals(name)) requestAuto();
            }
        };
        sourceChanges.startWatching();
        watchedPath = path;
    }

    static boolean sourceAccountMatches(String path, String account) throws java.io.IOException {
        if (path == null || account == null || account.isBlank()) return false;
        File db = new File(path).getCanonicalFile();
        File region = db.getParentFile();
        File owner = region == null ? null : region.getParentFile();
        return "fitness_data".equals(db.getName()) && owner != null && account.equals(owner.getName());
    }

    static Bundle readPage(SQLiteDatabase db, Bundle job) {
        String table = job.getString("table", "");
        if (!MiFitnessHistoryParser.TABLES.contains(table)) throw new IllegalArgumentException("MI_HISTORY_TABLE_UNSUPPORTED");
        String complete = "sleep_segment".equals(table) ? "isComplete" : "1 AS isComplete";
        Bundle result = new Bundle(job);
        ArrayList<Bundle> data = new ArrayList<>();
        int chars = 0;
        try (Cursor rows = db.rawQuery("SELECT `key`, time, CASE WHEN length(value)<="
                + MiFitnessMirrorProvider.HISTORY_PAGE_CHARS + " THEN value ELSE NULL END, zoneOffsetInSec, zoneName, " + complete
                + " FROM " + table + " WHERE sid=? AND isDeleted=0 AND time<=?"
                + " AND (time>? OR (time=? AND `key` COLLATE BINARY>?)) ORDER BY time, `key` COLLATE BINARY LIMIT "
                + MiFitnessMirrorProvider.HISTORY_PAGE_SIZE, new String[]{job.getString("did"),
                Long.toString(job.getLong("cutoff")), Long.toString(job.getLong("time")),
                Long.toString(job.getLong("time")), job.getString("key", "")})) {
            while (rows.moveToNext()) {
                String value = rows.getString(2);
                if (value == null) value = "";
                boolean tooLarge = value.length() > MiFitnessMirrorProvider.HISTORY_PAGE_CHARS;
                if (!tooLarge && chars + value.length() > MiFitnessMirrorProvider.HISTORY_PAGE_CHARS) break;
                Bundle row = new Bundle();
                row.putString("key", rows.getString(0));
                row.putLong("time", rows.getLong(1));
                if (!tooLarge) { row.putString("value", value); chars += value.length(); }
                row.putInt("zoneOffsetInSec", rows.getInt(3));
                row.putString("zoneName", rows.getString(4));
                row.putBoolean("isComplete", rows.getInt(5) != 0);
                data.add(row);
            }
            result.putBoolean("done", data.size() == rows.getCount() && rows.getCount() < MiFitnessMirrorProvider.HISTORY_PAGE_SIZE);
        }
        result.putParcelableArrayList("data", data);
        return result;
    }

    private void report(Bundle job, String error) {
        try {
            Bundle input = new Bundle(job);
            input.putString("error", error);
            context.getContentResolver().call(MiFitnessMirrorProvider.URI, "historyError", null, input);
        } catch (RuntimeException ignored) { }
    }
}
