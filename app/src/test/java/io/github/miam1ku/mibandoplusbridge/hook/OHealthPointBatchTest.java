// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import io.github.miam1ku.mibandoplusbridge.data.HealthRecord;
import java.time.Instant;
import java.util.ArrayList;
import org.junit.Test;
import static org.junit.Assert.*;

public class OHealthPointBatchTest {
    @Test public void oldDaysShareWeeklyBatchesWithoutMixingDevicesOrReadingAnUnboundedHistoryWindow() {
        var points = new ArrayList<HealthRecord>();
        long start = Instant.parse("2026-10-01T12:00:00Z").toEpochMilli();
        for (int day = 0; day < 28; day++) points.add(new HealthRecord("point" + day, "band-a", "heart_rate",
                start + day * 86_400_000L, start + day * 86_400_000L + 60_000, 70, null, 1, "UTC", "continuous", false));
        var modern = OHealthHealthImportHook.pointBatches(points, true);
        assertEquals(5, modern.size());
        assertEquals(28, modern.values().stream().mapToInt(java.util.List::size).sum());
        assertTrue(modern.values().stream().allMatch(batch -> batch.size() <= 7));
        assertEquals(28, OHealthHealthImportHook.pointBatches(points, false).size());
        points.add(new HealthRecord("other-band", "band-b", "heart_rate", start, start + 60_000, 80, null, 1, "UTC", "continuous", false));
        points.add(new HealthRecord("oxygen", "band-a", "spo2", start, start + 60_000, 98, null, 1, "UTC", "continuous", false));
        assertEquals(7, OHealthHealthImportHook.pointBatches(points, true).size());
    }
}
