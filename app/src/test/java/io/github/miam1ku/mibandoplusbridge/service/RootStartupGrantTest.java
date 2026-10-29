// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;
import org.junit.Test;

public class RootStartupGrantTest {
    @Test public void commandUsesInstalledApkAndFixedPackages() {
        assertEquals("CLASSPATH=/data/app/~~a1B2==/io.github.miam1ku.mibandoplusbridge-x_Y==/base.apk"
                        + " /system/bin/app_process /system/bin"
                        + " io.github.miam1ku.mibandoplusbridge.service.RootStartupGrant"
                        + " io.github.miam1ku.mibandoplusbridge com.heytap.health com.mi.health",
                OwnershipController.startupGrantCommand(
                        "/data/app/~~a1B2==/io.github.miam1ku.mibandoplusbridge-x_Y==/base.apk"));
    }

    @Test public void rejectsPathsThatCouldCarryShellText() {
        assertNull(OwnershipController.startupGrantCommand(null));
        assertNull(OwnershipController.startupGrantCommand("/data/app/x/base.apk; reboot"));
        assertNull(OwnershipController.startupGrantCommand("/data/app/x y/base.apk"));
        assertNull(OwnershipController.startupGrantCommand("/data/app/../../system/base.apk"));
        assertNull(OwnershipController.startupGrantCommand("/sdcard/base.apk"));
    }

    @Test public void onlyMissingPackagesAreChanged() {
        assertEquals(List.of("b"), RootStartupGrant.missing(List.of("a", "b"), List.of("a", "c")));
    }
}
