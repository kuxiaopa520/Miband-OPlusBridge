// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.integration;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.database.DatabaseUtils;
import android.os.Bundle;
import android.os.Process;
import android.os.UserManager;
import io.github.miam1ku.mibandoplusbridge.data.BandStateRepository;
import io.github.miam1ku.mibandoplusbridge.data.BindingStore;
import io.github.miam1ku.mibandoplusbridge.data.HealthRecordStore;
import io.github.miam1ku.mibandoplusbridge.data.LocalPrefs;
import java.util.ArrayList;
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
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class MiFitnessHistoryImportTest {
    private Context context;
    private MiFitnessMirrorProvider provider;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("health-outbox.db");
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(true);
        PackageInfo mi = new PackageInfo();
        mi.packageName = "com.mi.health";
        mi.applicationInfo = new ApplicationInfo();
        mi.applicationInfo.packageName = mi.packageName;
        mi.applicationInfo.uid = 12001;
        Shadows.shadowOf(context.getPackageManager()).installPackage(mi);
        Shadows.shadowOf(context.getPackageManager()).setPackagesForUid(12001, "com.mi.health");
        ShadowBinder.setCallingUid(Process.myUid());
        JSONObject binding = new JSONObject().put("did", "selected-band").put("userId", "123")
                .put("address", "01:02:03:04:05:06");
        new BindingStore(context).save(binding);
        LocalPrefs.open(context, "band-state").edit().putBoolean("registered", true)
                .putString("deviceId", BandStateRepository.deviceId(binding)).commit();
        LocalPrefs.open(context, "ownership").edit().putString("mode", "COEXIST").putBoolean("ownsDisable", false).commit();
        try (HealthRecordStore store = new HealthRecordStore(context)) {
            store.confirmAccountHash(HealthRecordStore.hashAccount("oppo-account"));
        }
        provider = Robolectric.buildContentProvider(MiFitnessMirrorProvider.class).create().get();
    }

    private Bundle start() {
        ShadowBinder.setCallingUid(Process.myUid());
        assertTrue(provider.call("historyStart", null, null).getBoolean("active"));
        ShadowBinder.setCallingUid(12001);
        return provider.call("historyJob", null, null);
    }

    private static Bundle sleepPage(Bundle job) {
        Bundle page = new Bundle(job);
        Bundle row = new Bundle();
        row.putString("key", "sleep");
        row.putLong("time", 1700003600L);
        row.putString("value", "{\"bedtime\":1700000000,\"wake_up_time\":1700003600,\"duration\":60,"
                + "\"items\":[{\"start_time\":1700000000,\"end_time\":1700003600,\"state\":3}]}");
        row.putInt("zoneOffsetInSec", 28800);
        row.putBoolean("isComplete", true);
        ArrayList<Bundle> rows = new ArrayList<>();
        rows.add(row);
        page.putParcelableArrayList("data", rows);
        page.putBoolean("done", true);
        return page;
    }

    @Test public void sleepImportsBeforeOtherTablesAndTheProgressSurvivesProviderRestart() {
        Bundle job = start();
        assertEquals("sleep_segment", job.getString("table"));
        Bundle reply = provider.call("historyPage", null, sleepPage(job));
        assertEquals(1, reply.getLong("sleepRows"));
        assertEquals(2, reply.getLong("added"));
        provider.shutdown();
        provider = Robolectric.buildContentProvider(MiFitnessMirrorProvider.class).create().get();
        Bundle resumed = provider.call("historyJob", null, null);
        assertEquals(job.getString("token"), resumed.getString("token"));
        assertEquals("step_record", resumed.getString("table"));
        // Replayed IPC from the old page must not move or duplicate the new cursor.
        provider.call("historyPage", null, sleepPage(job));
        try (HealthRecordStore store = new HealthRecordStore(context)) {
            assertEquals(2, DatabaseUtils.queryNumEntries(store.getReadableDatabase(), "measurements"));
        }
        assertEquals("step_record", provider.call("historyJob", null, null).getString("table"));
    }

    @Test public void removalAndAccountChangesStopHistoricalIngestion() {
        Bundle job = start();
        try (HealthRecordStore store = new HealthRecordStore(context)) {
            store.confirmAccountHash(HealthRecordStore.hashAccount("other-oppo-account"));
        }
        assertEquals("MI_HISTORY_ACCOUNT_CHANGED", provider.call("historyPage", null, sleepPage(job)).getString("status"));
        LocalPrefs.open(context, "band-state").edit().putBoolean("registered", false).commit();
        assertEquals("MI_MIRROR_INACTIVE", provider.call("historyPage", null, sleepPage(job)).getString("status"));
        try (HealthRecordStore store = new HealthRecordStore(context)) {
            assertEquals(0, DatabaseUtils.queryNumEntries(store.getReadableDatabase(), "measurements"));
        }
    }

    @Test public void onlyTheSelectedMiCallerMaySendARequestedPage() {
        Bundle job = start();
        Bundle wrongDevice = sleepPage(job);
        wrongDevice.putString("did", "other-band");
        assertThrows(SecurityException.class, () -> provider.call("historyPage", null, wrongDevice));
        ShadowBinder.setCallingUid(12002);
        assertThrows(SecurityException.class, () -> provider.call("historyPage", null, sleepPage(job)));
    }

    @Test public void queueFullKeepsTheUnconsumedPointCursorForAResumedImport() {
        Bundle job = start();
        for (int i = 0; i < 2; i++) {
            Bundle empty = new Bundle(job);
            empty.putParcelableArrayList("data", new ArrayList<Bundle>());
            empty.putBoolean("done", true);
            provider.call("historyPage", null, empty);
            job = provider.call("historyJob", null, null);
        }
        assertEquals("hr_record", job.getString("table"));
        Bundle point = new Bundle();
        point.putString("key", "heart_rate");
        point.putLong("time", 1700000000L);
        point.putString("value", "{\"bpm\":62}");
        Bundle page = new Bundle(job);
        ArrayList<Bundle> data = new ArrayList<>();
        data.add(point);
        page.putParcelableArrayList("data", data);
        page.putBoolean("done", true);
        try (HealthRecordStore store = new HealthRecordStore(context)) {
            store.getWritableDatabase().execSQL("INSERT INTO records VALUES ('full',1,'other',?,'{}',67108864)",
                    new Object[]{store.confirmedAccountHash()});
            Bundle paused = provider.call("historyPage", null, page);
            assertEquals("MI_HISTORY_OPEN_OPPO_QUEUE_FULL", paused.getString("status"));
            assertEquals(0, paused.getLong("rows"));
            assertEquals(-1, provider.call("historyJob", null, null).getLong("time"));
            store.getWritableDatabase().delete("records", null, null);
            Bundle resumed = provider.call("historyPage", null, page);
            assertEquals(1, resumed.getLong("rows"));
            assertEquals(1, resumed.getLong("added"));
            assertEquals("spo2_record", provider.call("historyJob", null, null).getString("table"));
        }
    }

    @Test public void missingSleepDetailsAreCountedAsSkippedInsteadOfClaimedAsImported() {
        Bundle page = sleepPage(start());
        page.getParcelableArrayList("data", Bundle.class).get(0).putString("value",
                "{\"bedtime\":1700000000,\"wake_up_time\":1700003600,\"duration\":45}");
        Bundle reply = provider.call("historyPage", null, page);
        assertEquals(1, reply.getLong("skipped"));
        assertEquals(0, reply.getLong("sleepRows"));
        assertEquals(0, reply.getLong("added"));
        assertEquals("MI_HISTORY_SLEEP_DETAILS_REQUIRED", reply.getString("lastSkip"));
    }

    @Test public void anOppoAccountIsRequiredBeforeReadingAnyHistory() {
        ShadowBinder.setCallingUid(Process.myUid());
        try (HealthRecordStore store = new HealthRecordStore(context)) {
            store.getWritableDatabase().delete("binding", null, null);
        }
        assertEquals("MI_HISTORY_OPEN_OPPO", provider.call("historyStart", null, null).getString("status"));
    }
}
