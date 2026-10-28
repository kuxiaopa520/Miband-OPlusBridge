// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import android.content.Context;
import io.github.miam1ku.mibandoplusbridge.BuildConfig;
import io.github.miam1ku.mibandoplusbridge.service.OwnershipController;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

/** Local session trace. A line carries the event name. Tokens, MAC addresses, nonces, and ciphertext stay in the caller. */
public final class SessionLog {
    static final String START_MARK = "--- module start ";
    static final int KEPT_STARTS = 3;
    private static final long MAX_BYTES = 256 * 1024;
    private static final Object LOCK = new Object();
    private static boolean started;

    private SessionLog() {}

    /** One marker per process. Older module starts beyond the newest three are deleted. */
    public static void start(Context context) {
        if (context == null || started) return;
        started = true;
        synchronized (LOCK) {
            try {
                File file = logFile(context);
                String existing = file.isFile()
                        ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "";
                if (!existing.isBlank() && !existing.contains(START_MARK)) {
                    existing = START_MARK + "legacy ---\n" + existing;
                }
                String next = retain(existing + START_MARK + Instant.now() + " ---\n");
                Files.write(file.toPath(), next.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) { }
        }
    }

    /** Drops MAC-shaped addresses and control characters before a line is stored. */
    public static String redact(String value) {
        if (value == null || value.isBlank()) return "";
        String clean = value.replaceAll("(?i)[0-9a-f]{2}([:-][0-9a-f]{2}){5}", "mac")
                .replaceAll("[\\p{Cntrl}]+", " ").trim();
        return clean.length() > 160 ? clean.substring(0, 160) : clean;
    }

    public static void line(Context context, String message) {
        if (context == null || message == null || message.isBlank()) return;
        String row = Instant.now() + " " + message.replace('\n', ' ').replace('\r', ' ') + "\n";
        byte[] bytes = row.getBytes(StandardCharsets.UTF_8);
        synchronized (LOCK) {
            try {
                File file = logFile(context);
                if (file.length() + bytes.length > MAX_BYTES && file.isFile()) {
                    String kept = retain(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) + row);
                    try (FileOutputStream out = new FileOutputStream(file, false)) {
                        out.write(kept.getBytes(StandardCharsets.UTF_8));
                    }
                    return;
                }
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    out.write(bytes);
                }
            } catch (IOException ignored) { }
        }
    }

    public static File export(Context context) throws IOException {
        File dir = new File(context.getCacheDir(), "debug");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("DEBUG_LOG_UNAVAILABLE");
        File out = new File(dir, "band-debug.txt");
        StringBuilder text = new StringBuilder();
        text.append("miband-oplusbridge debug\n");
        text.append("exported-at ").append(Instant.now()).append('\n');
        text.append("module ").append(BuildConfig.VERSION_NAME).append('/').append(BuildConfig.VERSION_CODE).append('\n');
        OwnershipController owner = new OwnershipController(context);
        text.append("current-state mode=").append(owner.mode())
                .append(" registered=").append(new BandStateRepository(context).isRegistered())
                .append(" coexistReady=").append(owner.coexistReady()).append('\n');
        File live = new File(context.getNoBackupFilesDir(), "live-status.txt");
        if (live.isFile() && live.length() < 512) {
            text.append("last-direct-status ").append(new String(Files.readAllBytes(live.toPath()), StandardCharsets.UTF_8).trim()).append('\n');
        }
        File log = logFile(context);
        if (log.isFile()) text.append(retain(new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8)));
        text.append("\n--- OPPO Health Android crash buffer ---\n");
        try {
            text.append(owner.healthCrashLog());
        } catch (OwnershipController.Failure unavailable) {
            text.append(unavailable.code);
        }
        text.append('\n');
        Files.write(out.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
        return out;
    }

    /** Keeps the newest {@code keep} module starts. A single start that is still too long is cut from the tail. */
    static String retain(String text) {
        String kept = keepLastStarts(text, KEPT_STARTS);
        byte[] raw = kept.getBytes(StandardCharsets.UTF_8);
        while (raw.length > MAX_BYTES && countStarts(kept) > 1) {
            kept = keepLastStarts(kept, countStarts(kept) - 1);
            raw = kept.getBytes(StandardCharsets.UTF_8);
        }
        if (raw.length <= MAX_BYTES) return kept;
        int from = raw.length - (int) MAX_BYTES;
        String tail = new String(raw, from, raw.length - from, StandardCharsets.UTF_8);
        int newline = tail.indexOf('\n');
        if (newline >= 0 && newline + 1 < tail.length()) tail = tail.substring(newline + 1);
        int marker = kept.indexOf(START_MARK);
        if (marker >= 0 && !tail.startsWith(START_MARK)) {
            int end = kept.indexOf('\n', marker);
            String head = end < 0 ? kept.substring(marker) : kept.substring(marker, end + 1);
            return head + "… earlier lines trimmed\n" + tail;
        }
        return "… earlier lines trimmed\n" + tail;
    }

    static String keepLastStarts(String text, int keep) {
        if (text == null || text.isEmpty() || keep < 1) return "";
        int count = countStarts(text);
        if (count <= keep) return text;
        int at = 0;
        for (int i = 0; i <= count - keep; i++) {
            at = text.indexOf(START_MARK, at);
            if (i < count - keep) at += START_MARK.length();
        }
        return at < 0 ? text : text.substring(at);
    }

    private static int countStarts(String text) {
        int count = 0;
        for (int from = 0; (from = text.indexOf(START_MARK, from)) >= 0; from += START_MARK.length()) count++;
        return count;
    }

    private static File logFile(Context context) {
        return new File(context.getNoBackupFilesDir(), "session-log.txt");
    }
}
