// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.content.Context;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import io.github.miam1ku.mibandoplusbridge.integration.DeviceCardProvider;
import java.lang.reflect.Field;
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
public final class OHealthNavigationTest {
    @Test public void healthMenusOpenDataPagesInTheSameHealthApp() {
        String[] menus = {"h0", "r0", "s0", "c0", "s", "p0"};
        String[] pages = {"HeartRateHistoryActivity", "SleepHistoryActivity", "SportRecordListActivity",
                "DailyActivityDetailActivity", "BloodOxygenHistoryActivity", "StressHistoryActivity"};
        for (int i = 0; i < menus.length; i++) {
            var intent = OHealthDeviceHook.historyIntent610(menus[i], "01:02:03:04:05:06");
            assertEquals("com.heytap.health", intent.getComponent().getPackageName());
            assertTrue(intent.getComponent().getClassName().endsWith(pages[i]));
            assertEquals("01:02:03:04:05:06", intent.getStringExtra("settingsDeviceMac"));
        }
    }

    public static final class BandProvider extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public Bundle call(String method, String arg, Bundle data) {
            Bundle state = new Bundle();
            state.putBoolean("registered", true); state.putBoolean("connected", true);
            state.putString("deviceId", "miband11_test"); state.putString("mac", "01:02:03:04:05:06");
            state.putString("name", "Test band");
            return state;
        }
        @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues v) { return null; }
        @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
        @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    }

    @Test public void importerGetsDeviceStateEvenBeforeTheUiSnapshotCallbackRuns() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        var provider = Robolectric.buildContentProvider(BandProvider.class).create().get();
        ShadowContentResolver.registerProviderInternal(DeviceCardProvider.AUTHORITY, provider);
        Field host = OHealthDeviceHook.class.getDeclaredField("hostContext"); host.setAccessible(true);
        Field state = OHealthDeviceHook.class.getDeclaredField("snapshot"); state.setAccessible(true);
        Object oldHost = host.get(null), oldState = state.get(null);
        host.set(null, context); state.set(null, null);
        try {
            assertNull(OHealthDeviceHook.registeredSnapshot());
            assertEquals("miband11_test", OHealthDeviceHook.refreshForImport().getString("deviceId"));
            assertTrue(OHealthDeviceHook.registeredSnapshot().getBoolean("registered"));
        } finally { host.set(null, oldHost); state.set(null, oldState); }
    }

    public static final class PanelState {
        public String l, m;
        public int t = 103, y;
        public boolean z;
        public final androidx.lifecycle.MutableLiveData<Boolean> q = new androidx.lifecycle.MutableLiveData<>();
    }

    public static final class Panel {
        public String t = "01:02:03:04:05:06", l = "miband11_selected";
    }
    public static final class PanelRow {
        public int a, f;
        public String i = "https://example.test/oppo-watch.png", j = "oppo-watch-3d";
        public boolean k = true;
        PanelRow(int type, int action) { a = type; f = action; }
    }

    @Test public void controlCenterKeepsTheNativePanelButUsesTheBandImageAndOnlyWorkingActions() throws Exception {
        Field state = OHealthDeviceHook.class.getDeclaredField("snapshot"); state.setAccessible(true);
        Object previous = state.get(null);
        Bundle band = new Bundle();
        band.putBoolean("registered", true); band.putString("mac", "01:02:03:04:05:06");
        band.putString("modelId", "miwear.watch.o66nfc");
        band.putString("imageUri", "content://io.github.miam1ku.mibandoplusbridge.devices/device_image/miband-body-1.png");
        state.set(null, band);
        try {
            PanelRow picture = new PanelRow(3, 0), battery = new PanelRow(1, 0), open = new PanelRow(2, 4);
            var original = java.util.List.of(picture, battery, new PanelRow(2, 2), new PanelRow(2, 11), open);
            var projected = OHealthDeviceHook.panelRows610(new Panel(), original);
            assertEquals(java.util.List.of(picture, battery, open), projected);
            assertEquals(band.getString("imageUri"), picture.i);
            assertFalse(picture.k);
            assertEquals("", picture.j);
            Panel other = new Panel(); other.t = "11:12:13:14:15:16";
            assertSame(original, OHealthDeviceHook.panelRows610(other, original));
            band.putString("imageUri", "");
            OHealthDeviceHook.panelRows610(new Panel(), original);
            assertEquals("", picture.i);
        } finally { state.set(null, previous); }
    }

    @Test public void nativePanelShowsTheCurrentBandWithoutChangingAnotherDevice() throws Exception {
        Field state = OHealthDeviceHook.class.getDeclaredField("snapshot"); state.setAccessible(true);
        Object previous = state.get(null);
        Bundle band = new Bundle();
        band.putBoolean("registered", true); band.putBoolean("connected", true);
        band.putString("deviceId", "miband11_selected"); band.putString("mac", "01:02:03:04:05:06");
        band.putInt("battery", 73); band.putBoolean("charging", true);
        state.set(null, band);
        try {
            PanelState selected = new PanelState();
            selected.l = "miband11_selected"; selected.m = "01:02:03:04:05:06";
            assertTrue(OHealthDeviceHook.applyPanelState610(selected));
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(102, selected.t);
            assertEquals(73, selected.y);
            assertTrue(selected.z);
            assertEquals(Boolean.TRUE, selected.q.getValue());
            PanelState other = new PanelState();
            other.l = "miband11_other"; other.m = "11:12:13:14:15:16";
            assertFalse(OHealthDeviceHook.applyPanelState610(other));
            assertEquals(103, other.t);
            assertEquals(0, other.y);
        } finally { state.set(null, previous); }
    }
}
