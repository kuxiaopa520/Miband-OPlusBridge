// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.integration;

import android.content.Context;
import android.os.Bundle;
import android.os.Process;
import android.os.UserManager;
import android.database.ContentObserver;
import io.github.miam1ku.mibandoplusbridge.data.BandStateRepository;
import io.github.miam1ku.mibandoplusbridge.data.HealthRecordStore;
import io.github.miam1ku.mibandoplusbridge.protocol.MiFitnessHistoryParser;
import java.util.concurrent.atomic.AtomicInteger;
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
public final class HealthQueueBatchTest {
    private Context context;
    private HealthQueueProvider provider;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("health-outbox.db");
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(true);
        Shadows.shadowOf(context.getPackageManager()).setPackagesForUid(12001, "com.heytap.health");
        ShadowBinder.setCallingUid(12001);
        provider = Robolectric.buildContentProvider(HealthQueueProvider.class).create().get();
        try (HealthRecordStore store = new HealthRecordStore(context)) {
            store.confirmAccountHash(HealthRecordStore.hashAccount("oppo"));
        }
    }

    @Test public void aConfirmedPageFreesCapacityOnceWithoutTriggeringAFullHistoryReplay() throws Exception {
        String did = "batch-queue";
        var parser = new MiFitnessHistoryParser("1.0", BandStateRepository.deviceId(new JSONObject().put("did", did)), did);
        var rows = new java.util.ArrayList<io.github.miam1ku.mibandoplusbridge.protocol.BandHistoryParser.Measurement>();
        for (int i = 0; i < 200; i++) rows.addAll(parser.parse("hr_record", "heart_rate", 1700000000L + i * 60L,
                "{\"bpm\":72}", 0, "UTC", true));
        try (HealthRecordStore store = new HealthRecordStore(context)) {
            store.importMissing(HealthRecordStore.hashAccount("oppo"), rows);
        }
        AtomicInteger historySignals = new AtomicInteger(), capacitySignals = new AtomicInteger();
        ContentObserver history = new ContentObserver(null) {
            @Override public void onChange(boolean self) { historySignals.incrementAndGet(); }
        };
        ContentObserver capacity = new ContentObserver(null) {
            @Override public void onChange(boolean self) { capacitySignals.incrementAndGet(); }
        };
        context.getContentResolver().registerContentObserver(HealthQueueProvider.RECORDS_URI, true, history);
        context.getContentResolver().registerContentObserver(HealthQueueProvider.URI, false, history);
        context.getContentResolver().registerContentObserver(HealthQueueProvider.CAPACITY_URI, false, capacity);
        try {
            Bundle receipt = new Bundle();
            receipt.putString("account", "oppo");
            receipt.putStringArray("recordIds", rows.stream().map(row -> row.recordId).toArray(String[]::new));
            receipt.putIntArray("revisions", java.util.stream.IntStream.range(0, 200).map(i -> 1).toArray());
            assertEquals(200, provider.call("ackBatch", null, receipt).getInt("acknowledged"));
            assertEquals(1, capacitySignals.get());
            assertEquals(0, historySignals.get());
            ShadowBinder.setCallingUid(Process.myUid());
            Bundle status = provider.call("status", null, null);
            assertEquals(0, status.getInt("pendingPoints"));
            assertEquals(200, status.getLong("confirmedPoints"));
        } finally {
            context.getContentResolver().unregisterContentObserver(history);
            context.getContentResolver().unregisterContentObserver(capacity);
        }
    }

    @Test public void aProgressNotificationCannotRestartTheMiReader() {
        AtomicInteger restarts = new AtomicInteger();
        ContentObserver reader = new ContentObserver(null) {
            @Override public void onChange(boolean self) { restarts.incrementAndGet(); }
        };
        context.getContentResolver().registerContentObserver(MiFitnessMirrorProvider.HISTORY_REQUEST_URI, false, reader);
        try {
            context.getContentResolver().notifyChange(MiFitnessMirrorProvider.HISTORY_URI, null);
            assertEquals(0, restarts.get());
            context.getContentResolver().notifyChange(MiFitnessMirrorProvider.HISTORY_REQUEST_URI, null);
            assertEquals(1, restarts.get());
        } finally { context.getContentResolver().unregisterContentObserver(reader); }
    }
}
