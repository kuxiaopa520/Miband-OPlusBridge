// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.UserManager;
import io.github.miam1ku.mibandoplusbridge.data.BandStateRepository;
import io.github.miam1ku.mibandoplusbridge.data.BindingStore;
import io.github.miam1ku.mibandoplusbridge.data.LocalPrefs;
import io.github.miam1ku.mibandoplusbridge.integration.MiFitnessMirrorProvider;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class MiFitnessBatteryHookTest {
    private Context context;
    private MiFitnessMirrorProvider provider;

    public static final class Battery {
        int level = 80, charge = 2;
        public int getBattery() { return level; }
        public int getChargeStatus() { return charge; }
    }
    public static final class Device {
        String did = "band-selected", mac = "01:02:03:04:05:06";
        final Battery battery = new Battery();
        public String getDid() { return did; }
        public String getMac() { return mac; }
        boolean failBattery;
        public Battery getBatteryInfo() { if (failBattery) throw new IllegalStateException("NOT_READY"); return battery; }
    }
    public static final class Model {
        final Device device = new Device();
        boolean failConnection;
        public String getDid() { return device.did; }
        public Device getDeviceInfo() { return device; }
        public int getDeviceStatus() { if (failConnection) throw new IllegalStateException("NOT_READY"); return 3; }
        public boolean isDeviceConnected() { return true; }
    }

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(true);
        PackageInfo mi = new PackageInfo(); mi.packageName = "com.mi.health"; mi.applicationInfo = new ApplicationInfo();
        mi.applicationInfo.packageName = mi.packageName; mi.applicationInfo.uid = 12001;
        Shadows.shadowOf(context.getPackageManager()).installPackage(mi);
        Shadows.shadowOf(context.getPackageManager()).setPackagesForUid(12001, mi.packageName);
        JSONObject binding = new JSONObject().put("did", "band-selected").put("userId", "123").put("address", "01:02:03:04:05:06");
        new BindingStore(context).save(binding);
        LocalPrefs.open(context, "band-state").edit().putBoolean("registered", true).putInt("battery", 85)
                .putString("mac", "01:02:03:04:05:06").putBoolean("connected", false)
                .putString("deviceId", BandStateRepository.deviceId(binding)).commit();
        LocalPrefs.open(context, "ownership").edit().putString("mode", "COEXIST").putBoolean("ownsDisable", false).commit();
        provider = Robolectric.buildContentProvider(MiFitnessMirrorProvider.class).create("io.github.miam1ku.mibandoplusbridge.mi-mirror").get();
        ShadowContentResolver.registerProviderInternal("io.github.miam1ku.mibandoplusbridge.mi-mirror", provider);
        ShadowBinder.setCallingUid(12001);
    }

    @Test public void officialBatteryCacheReplaces85With80WithoutInventingAConnection() {
        Device device = new Device();
        MiFitnessBatteryHook.publish(context, device);
        var state = LocalPrefs.open(context, "band-state");
        assertEquals(80, state.getInt("battery", -1));
        assertFalse(state.getBoolean("connected", true));
        assertFalse(state.getBoolean("charging", true));
        device.battery.level = 81; device.battery.charge = 1;
        MiFitnessBatteryHook.publish(context, device);
        state = LocalPrefs.open(context, "band-state");
        assertEquals(81, state.getInt("battery", -1)); assertTrue(state.getBoolean("charging", false));
    }

    @Test public void otherDevicesAndUnknownLevelsCannotOverwriteTheSelectedBand() {
        Device device = new Device(); device.did = "another-band";
        MiFitnessBatteryHook.publish(context, device);
        assertEquals(85, LocalPrefs.open(context, "band-state").getInt("battery", -1));
        device.did = "band-selected"; device.battery.level = -1;
        MiFitnessBatteryHook.publish(context, device);
        assertEquals(85, LocalPrefs.open(context, "band-state").getInt("battery", -1));
        device.battery.level = 80; device.mac = "11:12:13:14:15:16";
        MiFitnessBatteryHook.publish(context, device);
        assertEquals(85, LocalPrefs.open(context, "band-state").getInt("battery", -1));
    }

    @Test public void repeatedModelSnapshotsRecoverConnectionAndBatteryWithoutANewSetterEvent() {
        Model model = new Model();
        MiFitnessBatteryHook.publishSnapshot(context, model);
        assertTrue(LocalPrefs.open(context, "band-state").getBoolean("connected", false));
        assertEquals(80, LocalPrefs.open(context, "band-state").getInt("battery", -1));
        model.device.battery.level = 79;
        MiFitnessBatteryHook.publishSnapshot(context, model);
        assertEquals(79, LocalPrefs.open(context, "band-state").getInt("battery", -1));
    }

    @Test public void connectionAndBatteryReadFailuresDoNotSuppressEachOther() {
        Model model = new Model(); model.failConnection = true;
        MiFitnessBatteryHook.publishSnapshot(context, model);
        assertFalse(LocalPrefs.open(context, "band-state").getBoolean("connected", true));
        assertEquals(80, LocalPrefs.open(context, "band-state").getInt("battery", -1));
        model.failConnection = false; model.device.failBattery = true;
        MiFitnessBatteryHook.publishSnapshot(context, model);
        assertTrue(LocalPrefs.open(context, "band-state").getBoolean("connected", false));
        assertEquals(80, LocalPrefs.open(context, "band-state").getInt("battery", -1));
    }
}
