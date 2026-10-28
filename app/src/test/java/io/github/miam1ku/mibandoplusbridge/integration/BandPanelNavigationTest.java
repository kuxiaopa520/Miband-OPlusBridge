// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.integration;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.os.UserManager;
import io.github.miam1ku.mibandoplusbridge.data.LocalPrefs;
import io.github.miam1ku.mibandoplusbridge.service.OwnershipController;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class BandPanelNavigationTest {
    @Test public void controlCenterOpensTheOppoBandPanelAndPreservesItsDevice() throws Exception {
        var app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app.getSystemService(UserManager.class)).setUserUnlocked(true);
        PackageInfo mi = new PackageInfo();
        mi.packageName = "com.mi.health";
        mi.applicationInfo = new ApplicationInfo();
        mi.applicationInfo.packageName = mi.packageName;
        Shadows.shadowOf(app.getPackageManager()).installPackage(mi);
        LocalPrefs.open(app, "ownership").edit().putString("mode", "COEXIST").putBoolean("ownsDisable", false).commit();
        assertTrue(new OwnershipController(app).coexistReady());
        ResolveInfo healthPanel = new ResolveInfo();
        healthPanel.activityInfo = new ActivityInfo();
        healthPanel.activityInfo.packageName = NativePanel.HEALTH;
        healthPanel.activityInfo.name = "com.heytap.health.linkage.ui.DeviceDetailsPanelActivity";
        Intent probe = new Intent(NativePanel.PANEL).setPackage(NativePanel.HEALTH)
                .addCategory(Intent.CATEGORY_DEFAULT);
        Shadows.shadowOf(app.getPackageManager()).addResolveInfoForIntent(probe, healthPanel);
        ResolveInfo miApp = new ResolveInfo();
        miApp.activityInfo = new ActivityInfo();
        miApp.activityInfo.packageName = "com.mi.health";
        miApp.activityInfo.name = "com.mi.health.MainActivity";
        Shadows.shadowOf(app.getPackageManager()).addResolveInfoForIntent(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage("com.mi.health"), miApp);
        assertNotNull(app.getPackageManager().getLaunchIntentForPackage("com.mi.health"));
        String id = "miband11_" + "a".repeat(64);
        for (String action : new String[]{NativePanel.PANEL, NativePanel.PAGE}) {
            Intent tap = new Intent(action).setClass(app, BandDetailsActivity.class)
                    .putExtra("device_id", id).putExtra("device_title", "Xiaomi Band 10 NFC")
                    .putExtra("device_mac_info", "01:02:03:04:05:06");
            // Exercise the actual onCreate routing before AppCompat loads unrelated view resources.
            var activity = Robolectric.buildActivity(BandDetailsActivity.class, tap).get();
            var route = BandDetailsActivity.class.getDeclaredMethod("openNativePanelIfRequested");
            route.setAccessible(true);
            try {
                assertEquals(Boolean.TRUE, route.invoke(activity));
                Intent opened = Shadows.shadowOf(activity).getNextStartedActivity();
                assertNotNull(opened);
                assertEquals(healthPanel.activityInfo.name, opened.getComponent().getClassName());
                assertEquals(NativePanel.HEALTH, opened.getComponent().getPackageName());
                assertEquals(id, opened.getStringExtra("device_id"));
                assertEquals("01:02:03:04:05:06", opened.getStringExtra("device_mac_info"));
                assertEquals(NativePanel.BAND_MODEL, opened.getStringExtra("model_id"));
                assertTrue(activity.isFinishing());
                assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
            } finally { activity.finish(); }
        }
    }
}
