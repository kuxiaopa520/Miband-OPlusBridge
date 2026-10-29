// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import android.app.job.JobScheduler;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.UserManager;
import io.github.miam1ku.mibandoplusbridge.data.LocalPrefs;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class CoexistSyncTest {
    @Test public void recoverySurvivesProcessDeathAndStopsWhenCoexistenceIsDisabledOrUserLocked() {
        Context context = RuntimeEnvironment.getApplication();
        PackageInfo mi = new PackageInfo(); mi.packageName = "com.mi.health";
        mi.applicationInfo = new ApplicationInfo(); mi.applicationInfo.packageName = mi.packageName;
        Shadows.shadowOf(context.getPackageManager()).installPackage(mi);
        var users = context.getSystemService(UserManager.class);
        Shadows.shadowOf(users).setUserUnlocked(true);
        LocalPrefs.open(context, "band-state").edit().putBoolean("registered", true).commit();
        LocalPrefs.open(context, "ownership").edit().putString("mode", "COEXIST").commit();
        CoexistSync.schedule(context);
        var jobs = context.getSystemService(JobScheduler.class);
        var job = jobs.getPendingJob(CoexistSync.JOB_ID);
        assertNotNull(job); assertTrue(job.isPersisted()); assertTrue(job.isPeriodic());
        CoexistSync.schedule(context);
        assertEquals(1, jobs.getAllPendingJobs().size());
        CoexistSync.resumeAfterUi(context);
        var resume = jobs.getPendingJob(CoexistSync.RESUME_JOB_ID);
        assertNotNull(resume); assertTrue(resume.isPersisted()); assertFalse(resume.isPeriodic());
        CoexistSync.resumeAfterUi(context);
        assertEquals(2, jobs.getAllPendingJobs().size());
        Shadows.shadowOf(users).setUserUnlocked(false);
        CoexistSync.schedule(context);
        assertNull(jobs.getPendingJob(CoexistSync.JOB_ID));
        assertNull(jobs.getPendingJob(CoexistSync.RESUME_JOB_ID));
        assertNull(OwnershipController.syncWakeCommand("/data/app/x/base.apk; reboot"));
        assertTrue(OwnershipController.syncWakeCommand("/data/app/module/base.apk").endsWith(RootSyncWake.class.getName()));
    }
}
