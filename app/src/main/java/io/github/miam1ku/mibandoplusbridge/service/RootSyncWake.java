// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import android.content.AttributionSource;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import java.lang.reflect.Method;

/** Fixed, no-data provider calls. Never launches UI, reads health data, or changes host permissions. */
public final class RootSyncWake {
    public static final String METHOD = "io.github.miam1ku.mibandoplusbridge.SYNC_WAKE";
    private static final String[] AUTHORITIES = {"com.heytap.health.assignment_provider", "com.mi.health.provider.search"};

    private RootSyncWake() {}

    public static void main(String[] args) {
        int result = 0;
        try {
            Object am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
            for (String authority : AUTHORITIES) {
                IBinder token = new Binder();
                try {
                    Object holder = null;
                    for (Method method : am.getClass().getMethods()) {
                        if (!method.getName().equals("getContentProviderExternal")) continue;
                        if (method.getParameterCount() == 4) holder = method.invoke(am, authority, 0, token, "band-sync");
                        else if (method.getParameterCount() == 3) holder = method.invoke(am, authority, 0, token);
                        if (holder != null) break;
                    }
                    if (holder == null) throw new IllegalStateException("SYNC_PROVIDER_MISSING");
                    Object provider = holder.getClass().getField("provider").get(holder);
                    boolean handled = false;
                    AttributionSource source = new AttributionSource.Builder(Process.myUid()).setPackageName("root").build();
                    for (Method method : provider.getClass().getMethods()) {
                        if (!method.getName().equals("call") || method.getParameterCount() != 5
                                || method.getParameterTypes()[0] != AttributionSource.class) continue;
                        // Providers are published just before Application.onCreate. Let its
                        // hook finish installing on a cold start without showing an activity.
                        for (int attempt = 0; attempt < 20 && !handled; attempt++) {
                            Bundle reply = (Bundle) method.invoke(provider, source, authority, METHOD, null, new Bundle());
                            handled = reply != null && reply.getBoolean("accepted", false);
                            if (!handled) Thread.sleep(500);
                        }
                        break;
                    }
                    if (!handled) throw new IllegalStateException("SYNC_HOOK_NOT_READY");
                } catch (Exception unavailable) {
                    result = 1;
                    System.out.println("SYNC_WAKE_FAILED " + authority);
                } finally {
                    for (Method method : am.getClass().getMethods()) {
                        if (!method.getName().equals("removeContentProviderExternalAsUser") || method.getParameterCount() != 3) continue;
                        try { method.invoke(am, authority, token, 0); } catch (Exception ignored) { }
                        break;
                    }
                }
            }
        } catch (Exception unavailable) { result = 1; }
        System.out.flush();
        System.exit(result);
    }
}
