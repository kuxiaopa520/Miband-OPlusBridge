// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
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
public class MiFitnessConnectionHookTest {
    private Context context;

    public static final class Device {
        String did = "band-selected", mac = "01:02:03:04:05:06";
        public String getDid() { return did; }
        public String getMac() { return mac; }
        public String getAddress() { return mac; }
        public String getFirmwareVersion() { return "1.2.3"; }
    }
    public static final class Model {
        final Device info = new Device();
        int status = 3;
        boolean connected = true;
        public Device getDeviceInfo() { return info; }
        public String getDid() { return info.did; }
        public int getDeviceStatus() { return status; }
        public boolean isDeviceConnected() { return connected; }
    }
    public static final class Transport {
        public Device getDeviceInfo() { return new Device(); }
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
        registerProvider();
        ShadowBinder.setCallingUid(12001);
    }

    private void registerProvider() {
        MiFitnessMirrorProvider provider = Robolectric.buildContentProvider(MiFitnessMirrorProvider.class)
                .create("io.github.miam1ku.mibandoplusbridge.mi-mirror").get();
        ShadowContentResolver.registerProviderInternal("io.github.miam1ku.mibandoplusbridge.mi-mirror", provider);
    }

    @Test public void currentOfficialModelRecoversPersistedDisconnectWithoutANewConnectEvent() {
        assertFalse(LocalPrefs.open(context, "band-state").getBoolean("connected", true));
        MiFitnessConnectionHook.publishModel(context, new Model());
        var state = LocalPrefs.open(context, "band-state");
        assertTrue(state.getBoolean("connected", false));
        assertEquals("mi-model", state.getString("connectionSource", ""));
        assertTrue(state.getLong("connectionAtMs", 0) > 0);

        // A restarted backend reads the old false from disk, while Mi never disconnected.
        LocalPrefs.open(context, "band-state").edit().putBoolean("connected", false).commit();
        registerProvider();
        MiFitnessConnectionHook.publishModel(context, new Model());
        assertTrue(LocalPrefs.open(context, "band-state").getBoolean("connected", false));
    }

    @Test public void nativeSessionCleanupCannotClearTheCompanionConnection() {
        MiFitnessConnectionHook.publishModel(context, new Model());
        long revision = LocalPrefs.open(context, "band-state").getLong("revision", 0);
        new BandStateRepository(context).markSessionClosed();
        var state = LocalPrefs.open(context, "band-state");
        assertTrue(state.getBoolean("connected", false));
        assertEquals(revision, state.getLong("revision", 0));

        LocalPrefs.open(context, "ownership").edit().putString("mode", "NATIVE").putBoolean("ownsDisable", true).commit();
        new BandStateRepository(context).markSessionClosed();
        assertFalse(LocalPrefs.open(context, "band-state").getBoolean("connected", true));
    }

    @Test public void falseTransportProxyDoesNotDisconnectTheBandButItsBatteryStillUpdates() {
        MiFitnessConnectionHook.publishModel(context, new Model());
        assertFalse(MiFitnessMirrorHook.publishState(context, new Transport(), false, null));
        assertTrue(LocalPrefs.open(context, "band-state").getBoolean("connected", false));
        Bundle battery = new Bundle(); battery.putInt("battery", 80); battery.putInt("chargerState", 2);
        assertTrue(MiFitnessMirrorHook.publishState(context, new Transport(), false, battery));
        var state = LocalPrefs.open(context, "band-state");
        assertTrue(state.getBoolean("connected", false));
        assertEquals(80, state.getInt("battery", -1));
        assertEquals("mi-model", state.getString("connectionSource", ""));
    }

    @Test public void realDisconnectAndReconnectEventsStillChangeTheSelectedBand() {
        MiFitnessConnectionHook.publishEvent(context, "band-selected", true);
        assertTrue(LocalPrefs.open(context, "band-state").getBoolean("connected", false));
        MiFitnessConnectionHook.publishEvent(context, "band-selected", false);
        assertFalse(LocalPrefs.open(context, "band-state").getBoolean("connected", true));
        MiFitnessConnectionHook.publishEvent(context, "band-selected", true);
        assertTrue(LocalPrefs.open(context, "band-state").getBoolean("connected", false));
    }

    @Test public void officialModelAlsoConfirmsARealDisconnectAfterAMissedEvent() {
        Model model = new Model();
        MiFitnessConnectionHook.publishModel(context, model);
        model.status = 1; model.connected = false;
        MiFitnessConnectionHook.publishModel(context, model);
        assertFalse(LocalPrefs.open(context, "band-state").getBoolean("connected", true));
        for (int connectedStatus : new int[]{3, 5, 6, 7}) {
            model.status = connectedStatus; model.connected = true;
            MiFitnessConnectionHook.publishModel(context, model);
            assertTrue(LocalPrefs.open(context, "band-state").getBoolean("connected", false));
        }
    }

    @Test public void unknownModelsAndOtherDevicesCannotOverwriteTheConnection() {
        MiFitnessConnectionHook.publishModel(context, new Model());
        Model model = new Model(); model.connected = false;
        for (int unknown : new int[]{-1, 0, 10}) {
            model.status = unknown;
            MiFitnessConnectionHook.publishModel(context, model);
        }
        model.status = 1; model.info.did = "other-band";
        MiFitnessConnectionHook.publishModel(context, model);
        model.info.did = "band-selected"; model.info.mac = "11:12:13:14:15:16";
        MiFitnessConnectionHook.publishModel(context, model);
        MiFitnessConnectionHook.publishEvent(context, "other-band", false);
        MiFitnessConnectionHook.publishEvent(context, null, false);
        assertTrue(LocalPrefs.open(context, "band-state").getBoolean("connected", false));
    }

    @Test public void unchangedProbesRefreshFreshnessWithoutRepeatedDeviceRevisions() {
        MiFitnessConnectionHook.publishModel(context, new Model());
        var state = LocalPrefs.open(context, "band-state");
        long revision = state.getLong("revision", 0), lastUpdate = state.getLong("lastUpdateMs", 0);
        state.edit().putLong("connectionAtMs", 1).commit();
        MiFitnessConnectionHook.publishModel(context, new Model());
        state = LocalPrefs.open(context, "band-state");
        assertEquals(revision, state.getLong("revision", 0));
        assertEquals(lastUpdate, state.getLong("lastUpdateMs", 0));
        assertTrue(state.getLong("connectionAtMs", 0) > 1);
    }

    @Test public void otherUidsAndDisabledCoexistenceCannotPublishConnections() {
        ShadowBinder.setCallingUid(12002);
        assertThrows(SecurityException.class, () -> MiFitnessConnectionHook.publishModel(context, new Model()));
        ShadowBinder.setCallingUid(12001);
        LocalPrefs.open(context, "ownership").edit().putString("mode", "OFFICIAL").commit();
        MiFitnessConnectionHook.publishModel(context, new Model());
        assertFalse(LocalPrefs.open(context, "band-state").getBoolean("connected", true));
    }
}
