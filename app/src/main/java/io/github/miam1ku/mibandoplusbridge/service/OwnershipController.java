// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import android.content.Context;
import io.github.miam1ku.mibandoplusbridge.data.LocalPrefs;
import io.github.miam1ku.mibandoplusbridge.data.SessionLog;
import android.content.pm.PackageManager;
import android.os.UserManager;
import io.github.miam1ku.mibandoplusbridge.HostIdentity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Connection ownership touches only fixed package names, and it can hand the band back to the official app.
 * Shell commands are assembled in this class.
 *
 * <p>{@link #beginNativeSession()} holds the {@code GATE} read lock for the whole SPP session.
 * {@link #takeOver()} waits for the write lock, and the write lock waits for that read lock, so calling
 * takeover while the session lock is held deadlocks. End the session with {@link #endNativeSession()} first.</p>
 */
public final class OwnershipController {
    private static final String BRIDGE = "io.github.miam1ku.mibandoplusbridge";
    private static final String OFFICIAL = HostIdentity.MI_PACKAGE;
    /** id, pm, and the startup-grant helper all return short text. */
    static final int MAX_COMMAND_OUTPUT = 4096;
    /**
     * {@code cmd deviceidle whitelist} prints system packages first, and a device can have hundreds of lines.
     * Bytes still waiting at this limit mean the list is incomplete.
     */
    static final int MAX_WHITELIST_OUTPUT = 256 * 1024;
    private static final ReentrantReadWriteLock GATE = new ReentrantReadWriteLock(true);
    private final Context context;
    private final LocalPrefs state;

    public static void beginNativeSession() { GATE.readLock().lock(); }
    public static void endNativeSession() { GATE.readLock().unlock(); }

    public static final class Failure extends Exception {
        public final String code;
        public Failure(String code) { super(code); this.code = code; }
    }

    public OwnershipController(Context context) {
        this.context = context.getApplicationContext();
        state = LocalPrefs.open(this.context, "ownership");
    }

    public String mode() { return state.getString("mode", "OFFICIAL"); }

    public boolean prefersCoexistence() { return state.getBoolean("preferCoexistence", true); }

    public void preferCoexistence(boolean enabled) {
        if (!state.edit().putBoolean("preferCoexistence", enabled).commit()) {
            throw new IllegalStateException("OWNERSHIP_STORAGE_FAILED");
        }
    }

    public synchronized boolean coexistReady() {
        if (!"COEXIST".equals(mode()) || state.getBoolean("ownsDisable", false)
                || state.getBoolean("transitionPending", false)
                || !context.getSystemService(UserManager.class).isUserUnlocked()) return false;
        try {
            int enabled = officialState();
            return enabled == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    || enabled == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        } catch (Failure missing) {
            return false;
        }
    }

    /** The caller closes direct sessions first, exactly as when returning to the official app. */
    public synchronized void useCoexistence() throws Failure {
        GATE.writeLock().lock();
        try {
            requireUnlocked();
            restoreOfficial();
            int enabled = officialState();
            if (enabled != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    && enabled != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                throw new Failure("OFFICIAL_ALREADY_DISABLED");
            }
            if (!state.edit().putString("mode", "COEXIST").commit()) {
                throw new Failure("OWNERSHIP_STORAGE_FAILED");
            }
            ensureStartupAllowed();
        } finally {
            GATE.writeLock().unlock();
        }
    }

    public synchronized boolean nativeReady() {
        return "NATIVE".equals(mode()) && state.getBoolean("ownsDisable", false)
                && !state.getBoolean("officialRestored", false)
                && context.getSystemService(UserManager.class).isUserUnlocked();
    }

    /** {@code id -u} is 0 after the user has allowed this app in KernelSU. KernelSU has no grant dialog, so this only probes. */
    public boolean probeRoot() {
        try {
            return "0".equals(execute("id -u", 8));
        } catch (Failure ignored) {
            return false;
        }
    }

    public synchronized void takeOver() throws Failure {
        GATE.writeLock().lock();
        try {
        requireUnlocked();
        if (!HostIdentity.installed(context, HostIdentity.MI_PACKAGE)) throw new Failure("HOST_VERSION_UNSUPPORTED");
        requireRoot();
        if (state.getBoolean("ownsDisable", false)) {
            if (nativeReady()) return;
            throw new Failure("OWNERSHIP_RECOVERY_REQUIRED");
        }
        int original = officialState();
        if (original != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                && original != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            throw new Failure("OFFICIAL_ALREADY_DISABLED");
        }
        if (!state.edit().putInt("originalEnabled", original).putBoolean("transitionPending", true)
                .putBoolean("officialRestored", false).putString("mode", "OFFICIAL").commit()) {
            throw new Failure("OWNERSHIP_STORAGE_FAILED");
        }
        try {
            run("am force-stop com.mi.health", "OFFICIAL_STOP_FAILED");
            run("pm disable-user --user 0 com.mi.health", "OFFICIAL_DISABLE_FAILED");
            run("am force-stop com.mi.health", "OFFICIAL_STOP_FAILED");
            if (officialState() != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                throw new Failure("OFFICIAL_DISABLE_UNCONFIRMED");
            }
            ensureDoze();
            ensureStartupAllowed();
            if (!state.edit().putBoolean("ownsDisable", true).putBoolean("transitionPending", false)
                    .putString("mode", "NATIVE").commit()) throw new Failure("OWNERSHIP_STORAGE_FAILED");
            if (officialState() != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                throw new Failure("OFFICIAL_DISABLE_UNCONFIRMED");
            }
            run("am force-stop com.mi.health", "OFFICIAL_STOP_FAILED");
        } catch (Failure error) {
            if (!rollbackPending(original)) throw new Failure("OWNERSHIP_ROLLBACK_FAILED");
            throw error;
        }
        } finally {
            GATE.writeLock().unlock();
        }
    }

    public synchronized void restoreOfficial() throws Failure {
        GATE.writeLock().lock();
        try {
        if (!state.getBoolean("ownsDisable", false) && !state.getBoolean("transitionPending", false)) {
            if ("COEXIST".equals(mode()) && !state.edit().putString("mode", "OFFICIAL").commit()) {
                throw new Failure("OWNERSHIP_STORAGE_FAILED");
            }
            return;
        }
        requireRoot();
        int original = state.getInt("originalEnabled", -1);
        if (original != 0 && original != 1) throw new Failure("ORIGINAL_STATE_UNKNOWN");
        if (!state.getBoolean("officialRestored", false)) {
            restorePackage(original);
            if (officialState() != original) throw new Failure("OFFICIAL_RESTORE_UNCONFIRMED");
            if (!state.edit().putBoolean("officialRestored", true).commit()) {
                throw new Failure("OWNERSHIP_STORAGE_FAILED");
            }
        }
        dropDoze();
        if (!state.edit().putString("mode", "OFFICIAL").putBoolean("ownsDisable", false)
                .putBoolean("transitionPending", false).remove("originalEnabled")
                .remove("officialRestored").remove("dozeAdded").commit()) {
            throw new Failure("OWNERSHIP_STORAGE_FAILED");
        }
        } finally {
            GATE.writeLock().unlock();
        }
    }

    public synchronized boolean restoreIfModuleInvalid() throws Failure {
        GATE.writeLock().lock();
        try {
        if (!state.getBoolean("ownsDisable", false) && !state.getBoolean("transitionPending", false)) return false;
        if (nativeReady()) return false;
        restoreOfficial();
        return true;
        } finally {
            GATE.writeLock().unlock();
        }
    }

    /** Rollback returns the official package to the enabled state from before takeover. On failure Xiaomi Health stays disabled, and the caller shows that result. */
    private boolean rollbackPending(int original) {
        try {
            restorePackage(original);
            if (officialState() != original) {
                SessionLog.line(context, "ownership rollback unconfirmed state=" + officialState());
                return false;
            }
            dropDoze();
            if (!state.edit().putString("mode", "OFFICIAL").putBoolean("ownsDisable", false)
                    .putBoolean("transitionPending", false).remove("originalEnabled")
                    .remove("officialRestored").remove("dozeAdded").commit()) {
                SessionLog.line(context, "ownership rollback failed OWNERSHIP_STORAGE_FAILED");
                return false;
            }
            return true;
        } catch (Failure failed) {
            SessionLog.line(context, "ownership rollback failed " + failed.code);
            return false;
        }
    }

    private void restorePackage(int original) throws Failure {
        if (original == 0) run("pm default-state --user 0 com.mi.health", "OFFICIAL_RESTORE_FAILED");
        else if (original == 1) run("pm enable --user 0 com.mi.health", "OFFICIAL_RESTORE_FAILED");
        else throw new Failure("ORIGINAL_STATE_UNKNOWN");
    }

    private int officialState() throws Failure {
        try { return context.getPackageManager().getApplicationEnabledSetting(OFFICIAL); }
        catch (IllegalArgumentException absent) { throw new Failure("OFFICIAL_APP_MISSING"); }
    }

    private void requireUnlocked() throws Failure {
        if (!context.getSystemService(UserManager.class).isUserUnlocked()) throw new Failure("USER_LOCKED");
    }
    private void requireRoot() throws Failure {
        if (!"0".equals(execute("id -u"))) throw new Failure("ROOT_REQUIRED");
    }

    /** Doze whitelist plus background execution, so a swiped task leaves the bridge running. */
    public void keepAwake() {
        if (!probeRoot()) return;
        try {
            ensureDoze();
        } catch (Failure failed) { }
        String startup = ensureStartupAllowed();
        android.util.Log.i("OplusBandBridge", "STARTUP_GRANT "
                + (startup == null ? "failed" : startup.replace('\n', ' ')));
        try {
            execute("cmd appops set " + BRIDGE + " RUN_ANY_IN_BACKGROUND allow");
            execute("cmd appops set " + BRIDGE + " RUN_IN_BACKGROUND allow");
        } catch (Failure ignored) { }
    }

    /**
     * ColorOS 「自启动」(autostart) and 「关联启动」(associate start) for the bridge and
     * 「OPPO 健康」(OPPO Health). Without them the system refuses to start the bridge for Health's
     * provider calls and for MY_PACKAGE_REPLACED, so after an update Health sees
     * {@code Unknown authority} until the bridge is opened by hand.
     * Returns the helper output, or null when it could not run.
     */
    public String ensureStartupAllowed() {
        String command = startupGrantCommand(context.getApplicationInfo().sourceDir);
        if (command == null) return null;
        try {
            return execute(command, 20);
        } catch (Failure failed) {
            return null;
        }
    }

    /** Fixed class and packages. The APK path comes from PackageManager and is checked. */
    static String startupGrantCommand(String apkPath) {
        if (apkPath == null || !apkPath.matches("/data/app/[A-Za-z0-9._~=+/-]+/base\\.apk")
                || apkPath.contains("..")) return null;
        return "CLASSPATH=" + apkPath + " /system/bin/app_process /system/bin "
                + RootStartupGrant.class.getName() + " " + BRIDGE + " com.heytap.health";
    }

    /**
     * The doze whitelist matches a whole comma-separated package field. A line is
     * {@code user,package,uid} or {@code system-excidle,package,uid}, and system packages come first.
     * A 256-byte read used to hide the bridge package, so {@code contains} treated it as absent,
     * every pass ran {@code whitelist +}, and {@code dozeAdded} became true.
     * Restore then ran {@code whitelist -} and removed a row the user had added.
     * Bytes still waiting at the cap fail the command and leave the list as it is.
     */
    private void ensureDoze() throws Failure {
        String list = execute("cmd deviceidle whitelist", 30, MAX_WHITELIST_OUTPUT);
        if (whitelistContains(list, BRIDGE)) return;
        execute("cmd deviceidle whitelist +" + BRIDGE);
        if (state.edit().putBoolean("dozeAdded", true).commit()) return;
        try {
            execute("cmd deviceidle whitelist -" + BRIDGE);
        } catch (Failure removeFailed) {
            // The prefs write missed, so dozeAdded stays false and a later restore skips whitelist -.
            // This removal is the chance to take back the row just added.
        }
        throw new Failure("OWNERSHIP_STORAGE_FAILED");
    }

    /** The package name has to be a whole comma- or whitespace-separated field, so a longer package name is not a hit. */
    static boolean whitelistContains(String list, String packageName) {
        if (list == null || packageName == null || packageName.isEmpty()) return false;
        for (String line : list.split("\\R")) {
            for (String token : line.split("[,\\s]+")) {
                if (packageName.equals(token)) return true;
            }
        }
        return false;
    }

    private void dropDoze() {
        if (!state.getBoolean("dozeAdded", false)) return;
        try {
            execute("cmd deviceidle whitelist -" + BRIDGE);
        } catch (Failure ignored) { }
    }

    private void run(String fixedCommand, String failureCode) throws Failure {
        try { execute(fixedCommand); }
        catch (Failure failure) { throw new Failure(failureCode); }
    }
    static String shellQuote(String command) {
        return "'" + command.replace("'", "'\\''") + "'";
    }
    private static String execute(String fixedCommand) throws Failure {
        return execute(fixedCommand, 30, MAX_COMMAND_OUTPUT);
    }
    private static String execute(String fixedCommand, int timeoutSeconds) throws Failure {
        return execute(fixedCommand, timeoutSeconds, MAX_COMMAND_OUTPUT);
    }
    private static String execute(String fixedCommand, int timeoutSeconds, int maxOutput) throws Failure {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", "/system/bin/sh -c " + shellQuote(fixedCommand))
                    .redirectErrorStream(true).start();
            if (!process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new Failure("ROOT_ACTION_TIMEOUT");
            }
            String text = readLimited(process.getInputStream(), maxOutput).trim();
            if (process.exitValue() != 0) throw new Failure("ROOT_ACTION_FAILED");
            return text;
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new Failure("ROOT_ACTION_FAILED");
        } finally {
            if (process != null) process.destroy();
        }
    }

    /** Reads until the stream ends. Bytes still waiting after maxOutput make the output incomplete for the caller. */
    static String readLimited(InputStream in, int maxOutput) throws IOException {
        byte[] buf = new byte[Math.min(8192, Math.max(1, maxOutput))];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int total = 0;
        while (total < maxOutput) {
            int n = in.read(buf, 0, Math.min(buf.length, maxOutput - total));
            if (n < 0) return out.toString(StandardCharsets.UTF_8);
            out.write(buf, 0, n);
            total += n;
        }
        if (in.read() >= 0) throw new IOException("OUTPUT_LIMIT");
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Fixed components only. Callers cannot pass a shell string. */
    static String rootStartCommand(String packageName, String className, boolean foreground) {
        if (foreground) {
            if (!"io.github.miam1ku.mibandoplusbridge".equals(packageName)
                    || !"io.github.miam1ku.mibandoplusbridge.service.BandLiveService".equals(className)) return null;
            return "am start-foreground-service --user 0 -n " + packageName + "/" + className;
        }
        if (!"com.heytap.health".equals(packageName)
                || !"com.heytap.health.rpc.host.HealthRpcMsgService".equals(className)) return null;
        return "am start-service --user 0 -n " + packageName + "/" + className;
    }


    /** Shell uid is allowed to call this. settings put global zen_mode is reverted by ColorOS. */
    static String zenCommand(boolean on) {
        return "cmd notification set_dnd " + (on ? "priority" : "off");
    }

    static String allowDndCommand() {
        return "cmd notification allow_dnd io.github.miam1ku.mibandoplusbridge";
    }

    static String allowHealthListenerCommand() {
        return "cmd notification allow_listener com.heytap.health/"
                + "com.heytap.health.watch.commonnotification.HeytapNotificationListenerService";
    }

    /** One fixed component. Shell uid may run this; the app cannot grant its own listener. */
    public static boolean allowHealthListener() {
        try {
            execute(allowHealthListenerCommand(), 8);
            return true;
        } catch (Failure failure) {
            return false;
        }
    }

    static boolean setZenMode(boolean on) {
        try {
            execute(allowDndCommand(), 8);
        } catch (Failure ignored) { }
        try {
            execute(zenCommand(on), 8);
            return true;
        } catch (Failure failure) {
            return false;
        }
    }
    static boolean rootStart(String packageName, String className, boolean foreground) {
        String command = rootStartCommand(packageName, className, foreground);
        if (command == null) return false;
        try {
            execute(command, 8);
            return true;
        } catch (Failure failure) {
            return false;
        }
    }
}
