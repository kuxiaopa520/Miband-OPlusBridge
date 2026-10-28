// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OHealthImportBatchTest {
    @Test public void fullPointQueueGetsReceiptsWhileHundredsOfSleepDaysStillRemain() throws Exception {
        AtomicInteger points = new AtomicInteger(32768), activity = new AtomicInteger(), sport = new AtomicInteger();
        AtomicInteger sleep = new AtomicInteger(446);
        var order = new ArrayList<String>();
        var tasks = List.of(
                new OHealthHealthImportHook.ImportTask("POINTS", () -> { points.addAndGet(-4000); return 250L; }),
                new OHealthHealthImportHook.ImportTask("STEPS", () -> { activity.incrementAndGet(); return 250L; }),
                new OHealthHealthImportHook.ImportTask("SPORT", () -> { sport.incrementAndGet(); return 0L; }),
                new OHealthHealthImportHook.ImportTask("SLEEP", () -> { sleep.addAndGet(-3); return 250L; }));
        var retry = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 4; i++) {
            var result = OHealthHealthImportHook.runImportBatch(tasks, retry, i * 250L, order::add,
                    failure -> { throw new AssertionError(failure); });
            assertEquals(250, result.nextDelay());
        }
        assertEquals(16768, points.get());
        assertEquals(434, sleep.get());
        assertEquals(4, activity.get());
        assertEquals(4, sport.get());
        assertEquals(List.of("POINTS", "STEPS", "SPORT", "SLEEP"), order.subList(0, 4));
    }

    @Test public void aFailingSleepDayHasItsOwnCooldownWithoutPausingNewHeartAndActivityData() throws Exception {
        AtomicInteger points = new AtomicInteger(), activity = new AtomicInteger(), sleep = new AtomicInteger();
        var tasks = List.of(
                new OHealthHealthImportHook.ImportTask("POINTS", () -> { points.incrementAndGet(); return 250L; }),
                new OHealthHealthImportHook.ImportTask("SLEEP", () -> { sleep.incrementAndGet(); throw new IllegalStateException("SLEEP_IMPORT_INCOMPLETE"); }),
                new OHealthHealthImportHook.ImportTask("STEPS", () -> { activity.incrementAndGet(); return 250L; }));
        var retries = new LinkedHashMap<String, Long>();
        var errors = new ArrayList<Throwable>();
        var first = OHealthHealthImportHook.runImportBatch(tasks, retries, 0, phase -> {}, errors::add);
        assertEquals(250, first.nextDelay());
        assertNotNull(first.failure());
        OHealthHealthImportHook.runImportBatch(tasks, retries, 250, phase -> {}, errors::add);
        assertEquals(2, points.get());
        assertEquals(2, activity.get());
        assertEquals(1, sleep.get());
        assertEquals(1, errors.size());
        OHealthHealthImportHook.runImportBatch(tasks, retries, 60_000, phase -> {}, errors::add);
        assertEquals(2, sleep.get());
    }

    @Test public void accountChangesStopAllFollowingWrites() {
        AtomicInteger later = new AtomicInteger();
        var tasks = List.of(
                new OHealthHealthImportHook.ImportTask("POINTS", () -> { throw new SecurityException("IMPORT_ACCOUNT_CHANGED"); }),
                new OHealthHealthImportHook.ImportTask("STEPS", () -> { later.incrementAndGet(); return 0L; }));
        assertThrows(SecurityException.class, () -> OHealthHealthImportHook.runImportBatch(
                tasks, new LinkedHashMap<>(), 0, phase -> {}, failure -> {}));
        assertEquals(0, later.get());
    }
}
