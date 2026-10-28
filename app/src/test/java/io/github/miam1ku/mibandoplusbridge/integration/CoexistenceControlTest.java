// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.integration;

import android.content.Context;
import android.os.Bundle;
import android.os.UserManager;
import io.github.miam1ku.mibandoplusbridge.data.LocalPrefs;
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
public final class CoexistenceControlTest {
    private Context context;
    private HostNotifyProvider provider;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(true);
        Shadows.shadowOf(context.getPackageManager()).setPackagesForUid(12001, "com.heytap.health");
        ShadowBinder.setCallingUid(12001);
        LocalPrefs.open(context, "ownership").edit().putString("mode", "COEXIST").putBoolean("ownsDisable", false).commit();
        provider = Robolectric.buildContentProvider(HostNotifyProvider.class).create().get();
    }

    @Test public void coexistenceDoesNotForwardControlOrAutomaticallyGrantAListener() {
        Bundle values = new Bundle();
        values.putBoolean("approved", false);
        values.putBoolean("secure", true);
        for (String method : new String[]{"forward", "music", "findWatch", "policy", "policySnapshot", "healthListener"}) {
            assertEquals("OFFICIAL_CONTROLS_OWNED_BY_MI", provider.call(method, null, values).getString("status"));
        }
        assertFalse(LocalPrefs.open(context, io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener.SETTINGS)
                .getBoolean("healthGrantFailed", false));
    }

    @Test public void changingAPreferenceDoesNotTransferActualControlOwnership() {
        LocalPrefs.open(context, "ownership").edit().putBoolean("preferCoexistence", false).commit();
        assertEquals("OFFICIAL_CONTROLS_OWNED_BY_MI", provider.call("music", null, new Bundle()).getString("status"));
    }

    @Test public void nativeOwnershipStillAcceptsItsListenerReports() {
        LocalPrefs.open(context, "ownership").edit().putString("mode", "NATIVE").putBoolean("ownsDisable", true)
                .putBoolean("officialRestored", false).commit();
        assertEquals("OK", provider.call("healthListener", null, null).getString("status"));
    }

    @Test public void coexistenceDoesNotRelaxTheCallingUidCheck() {
        ShadowBinder.setCallingUid(12002);
        assertThrows(SecurityException.class, () -> provider.call("music", null, new Bundle()));
    }
}
