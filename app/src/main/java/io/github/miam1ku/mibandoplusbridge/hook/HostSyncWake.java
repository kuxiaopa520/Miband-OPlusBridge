// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import io.github.miam1ku.mibandoplusbridge.HostIdentity;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XC_MethodHook;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedBridge;
import io.github.miam1ku.mibandoplusbridge.service.RootSyncWake;

final class HostSyncWake {
    private HostSyncWake() {}

    static void install(Context context, Class<?> providerType, Runnable request) throws NoSuchMethodException {
        // Xiaomi inherits ContentProvider.call; OPPO overrides it.
        XposedBridge.hookMethod(providerType.getMethod("call", String.class, String.class, Bundle.class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!providerType.isInstance(param.thisObject) || !RootSyncWake.METHOD.equals(param.args[0])) return;
                int uid = Binder.getCallingUid();
                if (!authorized(context, uid)) {
                    param.setThrowable(new SecurityException("SYNC_WAKE_CALLER_REQUIRED"));
                    return;
                }
                request.run();
                Bundle reply = new Bundle();
                reply.putBoolean("accepted", true);
                param.setResult(reply);
            }
        });
    }

    static boolean authorized(Context context, int uid) {
        return uid == 0 || HostIdentity.uidHas(context, uid, "io.github.miam1ku.mibandoplusbridge");
    }
}
