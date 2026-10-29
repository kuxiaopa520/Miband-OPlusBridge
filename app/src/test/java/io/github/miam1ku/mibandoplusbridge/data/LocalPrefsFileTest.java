// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class LocalPrefsFileTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void readsAFileLargerThanTheOld32KibCap() throws Exception {
        JSONObject json = new JSONObject();
        json.put("mode", "NATIVE");
        json.put("pad", "x".repeat(40_000));
        java.io.File file = folder.newFile("ownership.json");
        assertTrue(LocalPrefs.writeJson(file, json));
        assertTrue(file.length() > 32_768);
        LocalPrefs.Stored stored = LocalPrefs.readStored(file);
        assertTrue(stored.present);
        assertEquals("NATIVE", stored.json.getString("mode"));
        assertEquals(40_000, stored.json.getString("pad").length());
    }

    @Test public void keepsABrokenFileFromLookingMissing() throws Exception {
        java.io.File file = folder.newFile("ownership.json");
        Files.write(file.toPath(), "{".getBytes(StandardCharsets.UTF_8));
        LocalPrefs.Stored stored = LocalPrefs.readStored(file);
        assertTrue(stored.present);
        assertEquals(0, stored.json.length());
        assertEquals("{", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void treatsAMissingFileAsAbsent() {
        LocalPrefs.Stored stored = LocalPrefs.readStored(new java.io.File(folder.getRoot(), "absent.json"));
        assertFalse(stored.present);
    }

    @Test public void uiAndBackendEditorsPreserveEachOthersKeysAndExplicitRemovals() throws Exception {
        java.io.File file = folder.newFile("band-state.json");
        assertTrue(LocalPrefs.writeJson(file, new JSONObject().put("battery", 85).put("obsolete", true)));
        var constructor = LocalPrefs.class.getDeclaredConstructor(java.io.File.class, JSONObject.class);
        constructor.setAccessible(true);
        LocalPrefs ui = constructor.newInstance(file, LocalPrefs.readStored(file).json);
        LocalPrefs backend = constructor.newInstance(file, LocalPrefs.readStored(file).json);
        var uiEdit = ui.edit().putLong("revision", 8).remove("obsolete");
        assertTrue(backend.edit().putInt("battery", 80).commit());
        assertTrue(uiEdit.commit());
        JSONObject merged = LocalPrefs.readStored(file).json;
        assertEquals(80, merged.getInt("battery"));
        assertEquals(8, merged.getLong("revision"));
        assertFalse(merged.has("obsolete"));
        assertTrue(backend.edit().putBoolean("charging", true).commit());
        assertEquals(8, LocalPrefs.readStored(file).json.getLong("revision"));
        assertTrue(uiEdit.commit());
        assertTrue(LocalPrefs.readStored(file).json.getBoolean("charging"));
    }

    @Test public void simultaneousBatteryAndImageChangesEachAdvanceTheDeviceRevision() throws Exception {
        java.io.File file = folder.newFile("band-state.json");
        assertTrue(LocalPrefs.writeJson(file, new JSONObject().put("revision", 7).put("battery", 85)));
        var constructor = LocalPrefs.class.getDeclaredConstructor(java.io.File.class, JSONObject.class);
        constructor.setAccessible(true);
        LocalPrefs image = constructor.newInstance(file, LocalPrefs.readStored(file).json);
        LocalPrefs battery = constructor.newInstance(file, LocalPrefs.readStored(file).json);
        var imageUpdate = image.edit().putLong("revision", 8).putString("image", "real-band");
        assertTrue(battery.edit().putLong("revision", 8).putInt("battery", 80).commit());
        assertTrue(imageUpdate.commit());
        JSONObject merged = LocalPrefs.readStored(file).json;
        assertEquals(80, merged.getInt("battery"));
        assertEquals("real-band", merged.getString("image"));
        assertEquals(9, merged.getLong("revision"));
    }
}
