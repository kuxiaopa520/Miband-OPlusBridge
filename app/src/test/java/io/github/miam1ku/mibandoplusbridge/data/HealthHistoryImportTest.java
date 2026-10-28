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
}
