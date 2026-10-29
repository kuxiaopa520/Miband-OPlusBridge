// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Xml;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

/**
 * Ownership state lives in a json file under no_backup.
 *
 * <p>libxposed API 102 leaves {@link Context#getSharedPreferences} in the app data directory, not at
 * {@code /data/misc/apexdata}. Modules through 1.6.8 did, and the old OFFICIAL copy is still there.
 * An empty json file is imported once from {@code shared_prefs} in the app data directory.
 * {@code RawFitnessFileStore} still opens {@code oplusband-history} directly, because that step-metric
 * flag is already in the user's SharedPreferences.</p>
 */
public final class LocalPrefs {
    private final File file;
    private JSONObject json;

    private LocalPrefs(File file, JSONObject json) {
        this.file = file;
        this.json = json;
    }

    public static LocalPrefs open(Context context, String name) {
        Context app = context.getApplicationContext();
        if (app != null) context = app;
        File file = new File(context.getNoBackupFilesDir(), name + ".json");
        Stored stored = readStored(file);
        JSONObject json = stored.json;
        if (!stored.present) {
            json = importShared(context, name);
            JSONObject disk = importXml(new File(context.getDataDir(), "shared_prefs/" + name + ".xml"));
            if (prefer(disk, json, name)) json = disk;
            if (json.length() > 0) writeJson(file, json);
        }
        return new LocalPrefs(file, json);
    }

    public boolean getBoolean(String key, boolean fallback) { return json.optBoolean(key, fallback); }
    public String getString(String key, String fallback) {
        return json.has(key) ? json.optString(key, fallback) : fallback;
    }
    public int getInt(String key, int fallback) { return json.optInt(key, fallback); }
    public long getLong(String key, long fallback) { return json.optLong(key, fallback); }

    public Editor edit() { return new Editor(); }

    public final class Editor {
        private final JSONObject next;
        private final java.util.Set<String> touched = new java.util.HashSet<>();
        private long baseRevision;
        Editor() {
            baseRevision = json.optLong("revision", 0);
            try { next = new JSONObject(json.toString()); }
            catch (Exception failed) { throw new IllegalStateException("STATE_COPY_FAILED", failed); }
        }
        public Editor putBoolean(String key, boolean value) { return put(key, value); }
        public Editor putString(String key, String value) { return put(key, value); }
        public Editor putInt(String key, int value) { return put(key, value); }
        public Editor putLong(String key, long value) { return put(key, value); }
        public Editor remove(String key) { touched.add(key); next.remove(key); return this; }
        private Editor put(String key, Object value) {
            touched.add(key);
            try { next.put(key, value); return this; }
            catch (Exception failed) { throw new IllegalStateException("STATE_WRITE_FAILED", failed); }
        }
        public boolean commit() {
            // The UI and :bridge may commit different keys from older snapshots.
            // Lock a separate file because writeJson atomically replaces the JSON inode.
            synchronized (LocalPrefs.class) {
                File parent = file.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
                try (var channel = java.nio.channels.FileChannel.open(new File(file.getPath() + ".lock").toPath(),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                     var lock = channel.lock()) {
                    JSONObject latest = readStored(file).json;
                    long currentRevision = latest.optLong("revision", 0);
                    for (String key : touched) {
                        if (next.has(key)) latest.put(key, next.get(key));
                        else latest.remove(key);
                    }
                    if ("band-state.json".equals(file.getName()) && touched.contains("revision")
                            && next.optLong("revision", 0) > baseRevision) {
                        latest.put("revision", Math.max(currentRevision, baseRevision) + next.getLong("revision") - baseRevision);
                    }
                    if (!writeJson(file, latest)) return false;
                    json = latest;
                    baseRevision = latest.optLong("revision", 0);
                    touched.clear();
                    return true;
                } catch (Exception failed) {
                    note("LOCAL_PREFS_WRITE_FAILED " + file.getName(), failed);
                    return false;
                }
            }
        }
    }

    private static boolean prefer(JSONObject disk, JSONObject current, String name) {
        if (disk.length() == 0) return false;
        if ("ownership".equals(name) && "NATIVE".equals(disk.optString("mode"))
                && !"NATIVE".equals(current.optString("mode"))) return true;
        if ("band-state".equals(name) && disk.optBoolean("registered")
                && disk.optLong("revision", 0) >= current.optLong("revision", 0)) return true;
        return current.length() == 0;
    }

    /** Present means a non-empty file is already on disk. A parse failure leaves that file in place, so open keeps it instead of the old SharedPreferences. */
    static final class Stored {
        final JSONObject json;
        final boolean present;
        Stored(JSONObject json, boolean present) {
            this.json = json;
            this.present = present;
        }
    }

    static Stored readStored(File file) {
        if (!file.isFile() || file.length() == 0) return new Stored(new JSONObject(), false);
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            if (bytes.length == 0) return new Stored(new JSONObject(), false);
            return new Stored(new JSONObject(new String(bytes, StandardCharsets.UTF_8)), true);
        } catch (Exception failed) {
            note("LOCAL_PREFS_READ_FAILED " + file.getName(), failed);
            return new Stored(new JSONObject(), true);
        }
    }

    static boolean writeJson(File file, JSONObject json) {
        File dir = file.getParentFile();
        if (dir != null) dir.mkdirs();
        File tmp = new File(file.getPath() + ".tmp");
        try {
            Files.write(tmp.toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception failed) {
            tmp.delete();
            note("LOCAL_PREFS_WRITE_FAILED " + file.getName(), failed);
            return false;
        }
    }

    private static void note(String line, Exception failed) {
        try {
            android.util.Log.w("OplusBandBridge", line, failed);
        } catch (RuntimeException unavailable) {
            // Unit tests run without a working android.util.Log. 
        }
    }

    private static JSONObject importShared(Context context, String name) {
        JSONObject json = new JSONObject();
        try {
            SharedPreferences prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            for (var entry : prefs.getAll().entrySet()) {
                if (entry.getValue() != null) json.put(entry.getKey(), entry.getValue());
            }
        } catch (Exception ignored) { }
        return json;
    }

    private static JSONObject importXml(File file) {
        JSONObject json = new JSONObject();
        if (!file.isFile()) return json;
        try (FileInputStream in = new FileInputStream(file)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, StandardCharsets.UTF_8.name());
            String type = null;
            String key = null;
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event == XmlPullParser.START_TAG) {
                    type = parser.getName();
                    key = parser.getAttributeValue(null, "name");
                    if ("boolean".equals(type) && key != null) {
                        json.put(key, Boolean.parseBoolean(parser.getAttributeValue(null, "value")));
                    } else if ("int".equals(type) && key != null) {
                        json.put(key, Integer.parseInt(parser.getAttributeValue(null, "value")));
                    } else if ("long".equals(type) && key != null) {
                        json.put(key, Long.parseLong(parser.getAttributeValue(null, "value")));
                    }
                } else if (event == XmlPullParser.TEXT && "string".equals(type) && key != null) {
                    json.put(key, parser.getText());
                } else if (event == XmlPullParser.END_TAG) {
                    type = null;
                    key = null;
                }
            }
        } catch (Exception ignored) { }
        return json;
    }
}
