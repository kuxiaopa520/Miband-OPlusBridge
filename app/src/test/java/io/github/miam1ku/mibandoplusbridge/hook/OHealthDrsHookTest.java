// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class OHealthDrsHookTest {
    @Test public void capacityInitializationSurvivesAnUnpublishedApplicationContext() {
        Application application = RuntimeEnvironment.getApplication();
        Context storage = new ContextWrapper(application.createDeviceProtectedStorageContext()) {
            @Override public Context getApplicationContext() { return null; }
        };
        // The two calls at CapacityGovernor.init:204 from the supplied APK and crash stack.
        assertThrows(NullPointerException.class, () -> capacityPreferences(storage));
        SharedPreferences expected = application.getSharedPreferences("drs_capacity_governor", 0);
        Context repaired = OHealthDrsHook.initializationContext(storage, application);
        assertSame(expected, capacityPreferences(repaired));
        assertTrue(repaired.isDeviceProtectedStorage());
        assertSame(storage.getSharedPreferences("drs-storage-probe", 0),
                repaired.getSharedPreferences("drs-storage-probe", 0));
        assertSame(repaired, OHealthDrsHook.initializationContext(repaired, application));
    }

    @Test public void aHealthyStorageContextKeepsItsOriginalIdentity() {
        Application application = RuntimeEnvironment.getApplication();
        Context healthy = new ContextWrapper(application);
        assertSame(healthy, OHealthDrsHook.initializationContext(healthy, application));
        assertSame(application, OHealthDrsHook.initializationContext(application, application));
    }

    @Test public void absentInputsKeepTheHostsExistingBehavior() {
        Application application = RuntimeEnvironment.getApplication();
        assertNull(OHealthDrsHook.initializationContext(null, application));
        Context storage = new ContextWrapper(application) {
            @Override public Context getApplicationContext() { return null; }
        };
        assertSame(storage, OHealthDrsHook.initializationContext(storage, null));
    }

    private static SharedPreferences capacityPreferences(Context storage) {
        Context application = storage.getApplicationContext();
        return application.getSharedPreferences("drs_capacity_governor", 0);
    }
}
