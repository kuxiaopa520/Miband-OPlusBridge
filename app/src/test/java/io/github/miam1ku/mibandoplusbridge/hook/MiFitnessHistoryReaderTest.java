// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import io.github.miam1ku.mibandoplusbridge.integration.MiFitnessMirrorProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class MiFitnessHistoryReaderTest {
    private static SQLiteDatabase database() {
        SQLiteDatabase db = SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE hr_record (`key` TEXT, sid TEXT, time INTEGER, value TEXT, zoneOffsetInSec INTEGER, zoneName TEXT, isDeleted INTEGER)");
        return db;
    }

    private static Bundle job() {
        Bundle job = new Bundle();
        job.putString("did", "selected");
        job.putString("table", "hr_record");
        job.putString("key", "");
        job.putLong("time", -1);
        job.putLong("cutoff", 1800000000L);
        return job;
    }

    private static void insert(SQLiteDatabase db, String key, String did, long time, int deleted, String value) {
        ContentValues row = new ContentValues();
        row.put("key", key); row.put("sid", did); row.put("time", time); row.put("value", value);
        row.put("zoneOffsetInSec", 28800); row.put("zoneName", "Asia/Shanghai"); row.put("isDeleted", deleted);
        db.insertOrThrow("hr_record", null, row);
    }

    @Test public void deviceDeletionAndCutoffFiltersKeepOldLocalHistory() {
        try (SQLiteDatabase db = database()) {
            insert(db, "heart_rate", "selected", 1577833200L, 0, "{\"bpm\":62}");
            insert(db, "heart_rate", "other-band", 1700000000L, 0, "{\"bpm\":63}");
            insert(db, "heart_rate", "selected", 1700000060L, 1, "{\"bpm\":64}");
            insert(db, "heart_rate", "selected", 1900000000L, 0, "{\"bpm\":65}");
            Bundle page = MiFitnessHistoryReader.readPage(db, job());
            var rows = page.getParcelableArrayList("data", Bundle.class);
            assertEquals(1, rows.size());
            assertEquals(1577833200L, rows.get(0).getLong("time"));
            assertTrue(page.getBoolean("done"));
            assertEquals(4, android.database.DatabaseUtils.queryNumEntries(db, "hr_record"));
        }
    }

    @Test public void paginationDoesNotLoseDifferentKeysAtTheSameSecond() {
        try (SQLiteDatabase db = database()) {
            int size = MiFitnessMirrorProvider.HISTORY_PAGE_SIZE;
            for (int i = 0; i < size - 1; i++) insert(db, "heart_rate", "selected", 1700000000L + i, 0, "{\"bpm\":72}");
            insert(db, "heart_rate", "selected", 1700000000L + size - 1, 0, "{\"bpm\":73}");
            insert(db, "single_heart_rate", "selected", 1700000000L + size - 1, 0, "{\"bpm\":74}");
            Bundle request = job();
            Bundle first = MiFitnessHistoryReader.readPage(db, request);
            var firstRows = first.getParcelableArrayList("data", Bundle.class);
            assertEquals(size, firstRows.size());
            assertFalse(first.getBoolean("done"));
            Bundle last = firstRows.get(size - 1);
            request.putLong("time", last.getLong("time"));
            request.putString("key", last.getString("key"));
            Bundle second = MiFitnessHistoryReader.readPage(db, request);
            var remaining = second.getParcelableArrayList("data", Bundle.class);
            assertEquals(1, remaining.size());
            assertEquals("single_heart_rate", remaining.get(0).getString("key"));
            assertTrue(second.getBoolean("done"));
        }
    }

    @Test public void ipcByteBudgetLeavesTheNextRowForTheNextPage() {
        try (SQLiteDatabase db = database()) {
            String large = "x".repeat(MiFitnessMirrorProvider.HISTORY_PAGE_CHARS / 2 + 1);
            insert(db, "heart_rate", "selected", 1700000000L, 0, large);
            insert(db, "heart_rate", "selected", 1700000060L, 0, large);
            Bundle page = MiFitnessHistoryReader.readPage(db, job());
            assertEquals(1, page.getParcelableArrayList("data", Bundle.class).size());
            assertFalse(page.getBoolean("done"));
        }
    }

    @Test public void sourceAccountMustMatchTheCurrentRoomDatabase() throws Exception {
        assertTrue(MiFitnessHistoryReader.sourceAccountMatches("/data/user/0/com.mi.health/db/123/cn/fitness_data", "123"));
        assertFalse(MiFitnessHistoryReader.sourceAccountMatches("/data/user/0/com.mi.health/db/456/cn/fitness_data", "123"));
        assertFalse(MiFitnessHistoryReader.sourceAccountMatches("/data/user/0/com.mi.health/db/123/cn/other_db", "123"));
        assertFalse(MiFitnessHistoryReader.sourceAccountMatches(null, "123"));
    }

    @Test public void readOnlyHandleSeesCommittedWalHistoryAndLeavesPendingWritesAlone() throws Exception {
        java.io.File path = java.io.File.createTempFile("mi-history-wal", ".db",
                org.robolectric.RuntimeEnvironment.getApplication().getCacheDir());
        try (SQLiteDatabase host = SQLiteDatabase.openOrCreateDatabase(path, null)) {
            host.enableWriteAheadLogging();
            host.execSQL("CREATE TABLE hr_record (`key` TEXT, sid TEXT, time INTEGER, value TEXT, zoneOffsetInSec INTEGER, zoneName TEXT, isDeleted INTEGER)");
            insert(host, "heart_rate", "selected", 1700000000L, 0, "{\"bpm\":62}");
            try (SQLiteDatabase reader = SQLiteDatabase.openDatabase(path.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                host.beginTransaction();
                try {
                    insert(host, "heart_rate", "selected", 1700000060L, 0, "{\"bpm\":72}");
                    Bundle page = MiFitnessHistoryReader.readPage(reader, job());
                    assertTrue(reader.isReadOnly());
                    assertEquals(1, page.getParcelableArrayList("data", Bundle.class).size());
                } finally { host.endTransaction(); }
            }
            assertTrue(host.isOpen());
            assertEquals(1, android.database.DatabaseUtils.queryNumEntries(host, "hr_record"));
        }
    }
}
