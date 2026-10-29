// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class HostSyncWakeTest {
    @Test public void onlyRootAndTheModuleCanRequestAHostWake() {
        var context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(context.getPackageManager()).setPackagesForUid(12001, "io.github.miam1ku.mibandoplusbridge");
        Shadows.shadowOf(context.getPackageManager()).setPackagesForUid(12002, "com.example.unrelated");
        assertTrue(HostSyncWake.authorized(context, 0));
        assertTrue(HostSyncWake.authorized(context, 12001));
        assertFalse(HostSyncWake.authorized(context, 12002));
        assertFalse(HostSyncWake.authorized(context, 2000));
    }
}
