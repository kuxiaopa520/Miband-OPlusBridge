// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** System-scheduled recovery after process eviction; event hooks handle normal updates. */
public final class CoexistSyncJob extends JobService {
    private volatile Thread work;

    @Override public boolean onStartJob(JobParameters params) {
        work = new Thread(() -> {
            boolean retry = false;
            try {
                if (CoexistSync.enabled(this)) retry = !CoexistSync.wake(this);
                else CoexistSync.schedule(this);
            } catch (RuntimeException unavailable) { retry = true; }
            finally { jobFinished(params, retry); }
        }, "band-sync-recovery");
        work.start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        Thread active = work;
        if (active != null) active.interrupt();
        return true;
    }
}
