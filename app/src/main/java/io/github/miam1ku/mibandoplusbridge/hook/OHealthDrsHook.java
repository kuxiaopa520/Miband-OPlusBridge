// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.content.Context;
import android.content.ContextWrapper;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.HookRuntime;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XC_MethodHook;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedBridge;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedHelpers;

/** Repair the null application context seen in Health 6.7.17's DRS capacity initializer. */
final class OHealthDrsHook {
    private OHealthDrsHook() {}

    static void install(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> core = Class.forName("com.oplus.drs.core.DrsCore", false, loader);
        // Repair before DrsRuntime caches the application context for capacity and later services.
        XposedBridge.hookMethod(core.getDeclaredMethod("init", Context.class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!(param.args[0] instanceof Context storage) || storage.getApplicationContext() != null) return;
                Class<?> holder = Class.forName("com.heytap.health.base.base.GlobalApplicationHolder", false, loader);
                Object known = XposedHelpers.callStaticMethod(holder, "getAppContext");
                if (!(known instanceof android.app.Application application)) return;
                Context repaired = initializationContext(storage, application);
                param.args[0] = repaired;
                HookRuntime.log("OHEALTH_DRS_CONTEXT_REPAIRED");
                OHealthDeviceHook.traceLine(application, "OHEALTH_DRS_CONTEXT_REPAIRED");
            }
        });
    }

    static Context initializationContext(Context storage, Context application) {
        if (storage == null || application == null || storage.getApplicationContext() != null) return storage;
        // Keep the storage context's files, database paths and protection domain. Only repair
        // the application lookup that CapacityGovernor dereferences without a null check.
        return new ContextWrapper(storage) {
            @Override public Context getApplicationContext() { return application; }
        };
    }
}
