// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.SystemClock;
import android.os.UserManager;
import io.github.miam1ku.mibandoplusbridge.data.BandStateRepository;
import io.github.miam1ku.mibandoplusbridge.data.SessionLog;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Short on-demand wakeups; no activity, foreground service, or permanent module connection. */
public final class CoexistSync {
    static final int JOB_ID = 71024;
    static final int RESUME_JOB_ID = 71025;
    private static final AtomicBoolean QUEUED = new AtomicBoolean();
    private static long lastWake;
    private static final java.util.concurrent.ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "band-silent-sync");
        thread.setDaemon(true);
        return thread;
    });

    private CoexistSync() {}

    public static boolean enabled(Context context) {
        UserManager users = context.getSystemService(UserManager.class);
        return users != null && users.isUserUnlocked() && new BandStateRepository(context).isRegistered()
                && new OwnershipController(context).coexistReady();
    }

    public static void schedule(Context context) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs == null) return;
        if (!enabled(context)) { jobs.cancel(JOB_ID); jobs.cancel(RESUME_JOB_ID); return; }
        if (jobs.getPendingJob(JOB_ID) == null) jobs.schedule(new JobInfo.Builder(JOB_ID,
                new ComponentName(context, CoexistSyncJob.class)).setPersisted(true)
                .setPeriodic(15 * 60_000L).build());
    }

    /** Register before the task can be removed; this callback does not perform Root work. */
    public static void resumeAfterUi(Context context) {
        schedule(context);
        if (!enabled(context)) return;
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs != null && jobs.getPendingJob(RESUME_JOB_ID) == null) jobs.schedule(new JobInfo.Builder(
                RESUME_JOB_ID, new ComponentName(context, CoexistSyncJob.class)).setPersisted(true)
                .setMinimumLatency(2_000).setOverrideDeadline(10_000).build());
    }

    public static void request(Context context) {
        if (!(context.getPackageName() + ":bridge").equals(android.app.Application.getProcessName())) {
            try {
                context.getContentResolver().call(io.github.miam1ku.mibandoplusbridge.integration.MiFitnessMirrorProvider.URI,
                        "wake", null, null);
            } catch (RuntimeException unavailable) { resumeAfterUi(context); }
            return;
        }
        requestInBackend(context);
    }

    public static void requestInBackend(Context context) {
        if (!QUEUED.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext();
        Context target = app == null ? context : app;
        WORKER.execute(() -> {
            try {
                schedule(target);
                long now = SystemClock.elapsedRealtime();
                if (!enabled(target) || lastWake != 0 && now - lastWake < 30_000) return;
                lastWake = Math.max(1, now);
                wake(target);
            } catch (RuntimeException unavailable) {
                SessionLog.line(target, "SILENT_SYNC_UNAVAILABLE " + unavailable.getClass().getSimpleName());
            } finally { QUEUED.set(false); }
        });
    }

    static boolean wake(Context context) {
        // A provider call starts each host's main process without presenting an activity.
        // OPPO protects its main-process provider with a signature permission; use the
        // already-authorized root helper instead of weakening that permission.
        OwnershipController owner = new OwnershipController(context);
        var runtime = io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(context, "coexist-runtime");
        if (runtime.getInt("startupVersion", 0) != io.github.miam1ku.mibandoplusbridge.BuildConfig.VERSION_CODE) {
            String grant = owner.ensureStartupAllowed();
            if (grant != null) runtime.edit().putInt("startupVersion", io.github.miam1ku.mibandoplusbridge.BuildConfig.VERSION_CODE).commit();
            else SessionLog.line(context, "SILENT_SYNC_STARTUP_GRANT_FAILED");
        }
        boolean ok = owner.wakeSyncHosts();
        if (!ok) SessionLog.line(context, "SILENT_SYNC_WAKE_FAILED");
        return ok;
    }
}
