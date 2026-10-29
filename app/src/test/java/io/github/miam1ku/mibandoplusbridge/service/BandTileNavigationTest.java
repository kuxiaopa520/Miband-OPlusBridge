// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserManager;
import io.github.miam1ku.mibandoplusbridge.MainActivity;
import io.github.miam1ku.mibandoplusbridge.data.LocalPrefs;
import io.github.miam1ku.mibandoplusbridge.integration.NativePanel;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class BandTileNavigationTest {
    public static class WakeProvider extends ContentProvider {
        int wakes;
        public boolean onCreate() { return true; }
        public Bundle call(String method, String arg, Bundle extras) {
            if ("wake".equals(method)) wakes++;
            return Bundle.EMPTY;
        }
        public Cursor query(Uri uri, String[] columns, String selection, String[] args, String sort) { return null; }
        public String getType(Uri uri) { return null; }
        public Uri insert(Uri uri, ContentValues values) { return null; }
        public int delete(Uri uri, String selection, String[] args) { return 0; }
        public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }

    @Test public void coexistTileOpensOppoNativePanelAndRequestsSilentRecovery() {
        Context app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app.getSystemService(UserManager.class)).setUserUnlocked(true);
        PackageInfo mi = new PackageInfo(); mi.packageName = "com.mi.health"; mi.applicationInfo = new ApplicationInfo();
        mi.applicationInfo.packageName = mi.packageName;
        Shadows.shadowOf(app.getPackageManager()).installPackage(mi);
        LocalPrefs.open(app, "ownership").edit().putString("mode", "COEXIST").commit();
        String id = "miband11_" + "a".repeat(64);
        LocalPrefs.open(app, "band-state").edit().putBoolean("registered", true).putString("deviceId", id)
                .putString("mac", "01:02:03:04:05:06").putString("name", "Xiaomi Band 10 NFC").commit();
        ResolveInfo panel = new ResolveInfo(); panel.activityInfo = new ActivityInfo();
        panel.activityInfo.packageName = NativePanel.HEALTH;
        panel.activityInfo.name = "com.heytap.health.linkage.ui.DeviceDetailsPanelActivity";
        Shadows.shadowOf(app.getPackageManager()).addResolveInfoForIntent(new Intent(NativePanel.PANEL)
                .setPackage(NativePanel.HEALTH).addCategory(Intent.CATEGORY_DEFAULT), panel);
        WakeProvider wake = Robolectric.buildContentProvider(WakeProvider.class).create().get();
        ShadowContentResolver.registerProviderInternal("io.github.miam1ku.mibandoplusbridge.mi-mirror", wake);

        try (var controller = Robolectric.buildActivity(BandTileActivity.class).create()) {
            var activity = controller.get();
            Intent opened = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull(opened);
            assertEquals(panel.activityInfo.name, opened.getComponent().getClassName());
            assertEquals(NativePanel.HEALTH, opened.getComponent().getPackageName());
            assertEquals(id, opened.getStringExtra("device_id"));
            assertEquals("01:02:03:04:05:06", opened.getStringExtra("device_mac_info"));
            assertEquals(NativePanel.BAND_MODEL, opened.getStringExtra("model_id"));
            assertEquals(1, wake.wakes);
            assertTrue(activity.isFinishing());
            assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
            assertNull(Shadows.shadowOf((android.app.Application) app).getNextStartedService());
        }
    }

    @Test public void unconfiguredTileStillOpensModuleSetup() {
        try (var controller = Robolectric.buildActivity(BandTileActivity.class).create()) {
            Intent opened = Shadows.shadowOf(controller.get()).getNextStartedActivity();
            assertEquals(MainActivity.class.getName(), opened.getComponent().getClassName());
        }
    }
}
