// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import android.content.Context;
import android.database.DatabaseUtils;
import io.github.miam1ku.mibandoplusbridge.protocol.MiFitnessHistoryParser;
import java.util.ArrayList;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class HealthHistoryImportTest {
    private Context context;
    private MiFitnessHistoryParser parser;
    private final String account = HealthRecordStore.hashAccount("history-account");

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("health-outbox.db");
        String did = "history-store-band";
        parser = new MiFitnessHistoryParser("1.0", BandStateRepository.deviceId(new JSONObject().put("did", did)), did);
    }

    @Test public void repeatedImportDoesNotDuplicateOrReplaceNewerLiveMeasurements() throws Exception {
        var old = parser.parse("hr_record", "heart_rate", 1700000000L, "{\"bpm\":62}", 0, "UTC", true);
        var live = parser.parse("hr_record", "heart_rate", 1700000000L, "{\"bpm\":72}", 0, "UTC", true);
        try (HealthRecordStore store = new HealthRecordStore(context)) {
            store.confirmAccountHash(account);
            store.enqueueMeasurement(live.get(0));
            assertEquals(0, store.importMissing(account, old));
            assertEquals(0, store.importMissing(account, old));
            assertEquals(1, DatabaseUtils.queryNumEntries(store.getReadableDatabase(), "measurements"));
            try (var row = store.getReadableDatabase().rawQuery("SELECT payload FROM measurements", null)) {
                assertTrue(row.moveToFirst());
                assertEquals(72, new JSONObject(row.getString(0)).getInt("value"));
            }
        }
    }

    @Test public void accountChangesRejectTheWholeImportedRow() throws Exception {
        var rows = parser.parse("hr_record", "heart_rate", 1700000000L, "{\"bpm\":62}", 0, "UTC", true);
        try (HealthRecordStore store = new HealthRecordStore(context)) {
            store.confirmAccountHash(HealthRecordStore.hashAccount("different-account"));
            assertThrows(SecurityException.class, () -> store.importMissing(account, rows));
            assertEquals(0, DatabaseUtils.queryNumEntries(store.getReadableDatabase(), "measurements"));
        }
    }

    @Test public void completeSleepHistoryExtendsAPreviouslyTruncatedNightWithoutDuplicatingItsStages() throws Exception {
        String partial = "{\"bedtime\":1700000000,\"wake_up_time\":1700007200,\"is_uncomplete\":true,"
                + "\"items\":[{\"start_time\":1700000000,\"end_time\":1700007200,\"state\":3}]}";
        String complete = partial.replace("1700007200", "1700014400").replace("\"is_uncomplete\":true", "\"is_uncomplete\":false");
        var first = parser.parse("sleep_segment", "sleep", 1700007200L, partial, 0, "UTC", false);
        var full = parser.parse("sleep_segment", "sleep", 1700014400L, complete, 0, "UTC", true);
        try (HealthRecordStore store = new HealthRecordStore(context)) {
            store.confirmAccountHash(account);
            assertEquals(2, store.importMissing(account, first));
            assertEquals(2, store.importMissing(account, full));
            assertEquals(0, store.importMissing(account, first));
            assertEquals(0, store.importMissing(account, full));
            assertEquals(2, DatabaseUtils.queryNumEntries(store.getReadableDatabase(), "measurements"));
            try (var rows = store.getReadableDatabase().rawQuery("SELECT payload FROM measurements", null)) {
                while (rows.moveToNext()) {
                    var row = new JSONObject(rows.getString(0));
                    assertEquals(1700014400000L, row.getLong("endMs"));
                    assertEquals(2, row.getInt("revision"));
                }
            }
        }
    }

    @Test public void queueFullRollsBackTheRowAndLeavesPreviouslyStoredHistoryIntact() throws Exception {
        var rows = new ArrayList<>(parser.parse("step_record", "steps", 1700000000L, "{\"steps\":12}", 0, "UTC", true));
        rows.addAll(parser.parse("hr_record", "heart_rate", 1700000000L, "{\"bpm\":62}", 0, "UTC", true));
        try (HealthRecordStore store = new HealthRecordStore(context)) {
            store.confirmAccountHash(account);
            store.getWritableDatabase().execSQL("INSERT INTO records VALUES ('full',1,'other',?,'{}',67108864)", new Object[]{account});
            assertEquals("HEALTH_OUTBOX_FULL", assertThrows(IllegalStateException.class, () -> store.importMissing(account, rows)).getMessage());
            assertEquals(0, DatabaseUtils.queryNumEntries(store.getReadableDatabase(), "measurements"));
            store.getWritableDatabase().delete("records", null, null);
            assertEquals(2, store.importMissing(account, rows));
            assertEquals(0, store.importMissing(account, rows));
            assertEquals(2, DatabaseUtils.queryNumEntries(store.getReadableDatabase(), "measurements"));
        }
    }

    @Test public void bulkHistoryPreservesEveryMeasurementNearAnAlreadyPopulatedQueue() throws Exception {
        try (HealthRecordStore store = new HealthRecordStore(context)) {
            store.confirmAccountHash(account);
            store.getWritableDatabase().execSQL("WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<8192) "
                    + "INSERT INTO records SELECT 'existing-'||x,1,'other',?,'{}',512 FROM n", new Object[]{account});
            long began = System.nanoTime();
            var page = new java.util.ArrayList<java.util.List<io.github.miam1ku.mibandoplusbridge.protocol.BandHistoryParser.Measurement>>();
            for (int i = 0; i < 1024; i++) {
                page.add(parser.parse("hr_record", "heart_rate", 1700000000L + i * 60L,
                        "{\"bpm\":62}", 0, "UTC", true));
                if (page.size() == 256) {
                    assertEquals(256, store.importHistoryBatch(account, page).consumed());
                    page.clear();
                }
            }
            System.out.println("HISTORY_IMPORT_1024 elapsedMs=" + (System.nanoTime() - began) / 1_000_000L);
            assertEquals(1024, DatabaseUtils.queryNumEntries(store.getReadableDatabase(), "measurements"));
            assertEquals(9216, DatabaseUtils.queryNumEntries(store.getReadableDatabase(), "records"));
        }
    }

    @Test public void batchStopsAtAWholeSourceRowAndResumesAfterReceiptsFreeCapacity() throws Exception {
        var first = parser.parse("hr_record", "heart_rate", 1700000000L, "{\"bpm\":62}", 0, "UTC", true);
        var second = parser.parse("hr_record", "heart_rate", 1700000060L, "{\"bpm\":63}", 0, "UTC", true);
        try (HealthRecordStore store = new HealthRecordStore(context)) {
            store.confirmAccountHash(account);
            store.getWritableDatabase().execSQL("WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<32767) "
                    + "INSERT INTO records SELECT 'existing-'||x,1,'other',?,'{}',10 FROM n", new Object[]{account});
            var result = store.importHistoryBatch(account, java.util.List.of(first, second));
            assertTrue(result.full());
            assertEquals(1, result.consumed());
            assertEquals(1, result.added());
            assertEquals(1, DatabaseUtils.queryNumEntries(store.getReadableDatabase(), "measurements"));
            store.getWritableDatabase().delete("records", "record_id LIKE 'existing-%'", null);
            assertEquals(1, store.importHistoryBatch(account, java.util.List.of(second)).consumed());
            assertEquals(2, DatabaseUtils.queryNumEntries(store.getReadableDatabase(), "measurements"));
        }
    }

    @Test public void bulkReceiptCannotDeleteAnUpdatedRevisionOrAnotherAccountsQueue() throws Exception {
        var first = parser.parse("hr_record", "heart_rate", 1700000000L, "{\"bpm\":62}", 0, "UTC", true).get(0);
        var updated = parser.parse("hr_record", "heart_rate", 1700000000L, "{\"bpm\":72}", 0, "UTC", true).get(0);
        try (HealthRecordStore store = new HealthRecordStore(context)) {
            store.confirmAccountHash(account);
            store.enqueueMeasurement(first);
            store.enqueueMeasurement(updated);
            assertEquals(0, store.acknowledgeBatch("history-account", new String[]{first.recordId}, new int[]{1}));
            assertThrows(SecurityException.class, () -> store.acknowledgeBatch("wrong-account", new String[]{first.recordId}, new int[]{2}));
            assertEquals(1, store.count());
            assertEquals(1, store.acknowledgeBatch("history-account", new String[]{first.recordId}, new int[]{2}));
            assertEquals(0, store.count());
            assertEquals(1, DatabaseUtils.queryNumEntries(store.getReadableDatabase(), "measurements"));
        }
    }

    @Test public void pendingPagePrioritizesNewDataAndReceiptsRetainTheOlderBacklog() throws Exception {
        var first = parser.parse("hr_record", "heart_rate", 1700000000L, "{\"bpm\":62}", 0, "UTC", true).get(0);
        var latest = parser.parse("hr_record", "heart_rate", 1700000060L, "{\"bpm\":72}", 0, "UTC", true).get(0);
        try (HealthRecordStore store = new HealthRecordStore(context)) {
            store.confirmAccountHash(account);
            store.enqueueMeasurement(first);
            store.enqueueMeasurement(latest);
            String version = store.historyVersion("history-account", first.deviceId, "steps");
            try (var page = store.pending("history-account")) {
                assertTrue(page.moveToFirst());
                assertEquals(latest.recordId, page.getString(0));
                assertTrue(page.moveToNext());
                assertEquals(first.recordId, page.getString(0));
            }
            store.acknowledgeBatch("history-account", new String[]{latest.recordId}, new int[]{1});
            assertEquals(1, store.count());
            assertEquals(version, store.historyVersion("history-account", first.deviceId, "steps"));
            assertThrows(SecurityException.class, () -> store.historyVersion("wrong", first.deviceId, "steps"));
            assertThrows(IllegalArgumentException.class, () -> store.historyVersion("history-account", first.deviceId, "other"));
        }
    }

    @Test public void statusReaderDoesNotWaitForAnotherProvidersHistoryTransaction() throws Exception {
        var row = parser.parse("hr_record", "heart_rate", 1700000000L, "{\"bpm\":62}", 0, "UTC", true).get(0);
        var reader = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (HealthRecordStore writer = new HealthRecordStore(context); HealthRecordStore status = new HealthRecordStore(context)) {
            writer.confirmAccountHash(account);
            status.getReadableDatabase();
            var db = writer.getWritableDatabase();
            db.beginTransaction();
            try {
                writer.enqueueMeasurement(row);
                assertEquals(Integer.valueOf(0), reader.submit(status::count).get(3, java.util.concurrent.TimeUnit.SECONDS));
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            assertEquals(1, status.count());
        } finally { reader.shutdownNow(); }
    }
}
