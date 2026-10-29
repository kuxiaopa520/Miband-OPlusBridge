// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.content.Context;
import android.os.Bundle;
import io.github.miam1ku.mibandoplusbridge.data.MacIds;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.HookRuntime;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XC_MethodHook;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedBridge;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedHelpers;
import io.github.miam1ku.mibandoplusbridge.integration.MiFitnessMirrorProvider;

/** Use Mi Fitness's battery cache and read request on its existing connection. */
final class MiFitnessBatteryHook {
    private static final java.util.concurrent.ExecutorService WORKER = HookRuntime.worker("band-mi-battery", 0);
    private static final java.util.concurrent.atomic.AtomicBoolean POLLING = new java.util.concurrent.atomic.AtomicBoolean();
    private static long lastRefreshAt;

    private MiFitnessBatteryHook() {}

    static void install(Context context, ClassLoader loader) throws ClassNotFoundException {
        Class<?> info = Class.forName("com.xiaomi.fitness.device.manager.bean.DeviceInfo", false, loader);
        XC_MethodHook changed = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) return;
                Object device = param.thisObject;
                WORKER.execute(() -> {
                    try { publish(context, device); }
                    catch (Throwable failure) { HookRuntime.log("MI_BATTERY_MIRROR_UNAVAILABLE " + failure.getClass().getSimpleName()); }
                });
            }
        };
        XposedBridge.hookAllMethods(info, "setBatteryInfo", changed);
        XposedBridge.hookAllMethods(info, "setBattery", changed);
        requestSnapshot(context, loader);
    }

    static void requestSnapshot(Context context, ClassLoader loader) {
        if (!POLLING.compareAndSet(false, true)) return;
        WORKER.execute(() -> {
            try {
                Bundle target = context.getContentResolver().call(MiFitnessMirrorProvider.URI, "target", null, null);
                if (target == null || !"MI_MIRROR_ACTIVE".equals(target.getString("status"))) return;
                Class<?> type = Class.forName("com.xiaomi.fitness.device.manager.export.WearableDeviceManager", false, loader);
                Object companion = XposedHelpers.getStaticObjectField(type, "Companion");
                Object manager = XposedHelpers.callStaticMethod(Class.forName(
                        "com.xiaomi.fitness.device.manager.export.DeviceManagerExtKt", false, loader), "getInstance", companion);
                Object model = XposedHelpers.callMethod(manager, "getDeviceModel", target.getString("did"));
                if (model != null) {
                    MiFitnessConnectionHook.publishModel(context, model);
                    publish(context, XposedHelpers.callMethod(model, "getDeviceInfo"));
                }
                long now = android.os.SystemClock.elapsedRealtime();
                if (model != null && "com.mi.health".equals(android.app.Application.getProcessName())
                        && (lastRefreshAt == 0 || now - lastRefreshAt >= 60_000)) {
                    lastRefreshAt = Math.max(1, now);
                    Class<?> status = Class.forName("com.xiaomi.fitness.device.manager.DeviceBasicStatusDataHandler", false, loader);
                    Object handler = XposedHelpers.getStaticObjectField(status, "INSTANCE");
                    XposedHelpers.callMethod(handler, "syncBattery", target.getString("did"), null);
                    HookRuntime.log("MI_BATTERY_REFRESH_REQUESTED");
                }
            } catch (Throwable failure) { HookRuntime.log("MI_BATTERY_CACHE_UNAVAILABLE " + failure.getClass().getSimpleName()); }
            finally { POLLING.set(false); }
        });
    }

    static void publish(Context context, Object info) {
        if (info == null) return;
        Bundle target = context.getContentResolver().call(MiFitnessMirrorProvider.URI, "target", null, null);
        if (target == null || !"MI_MIRROR_ACTIVE".equals(target.getString("status"))) return;
        String did = String.valueOf(XposedHelpers.callMethod(info, "getDid"));
        String mac = String.valueOf(XposedHelpers.callMethod(info, "getMac"));
        if (!did.equals(target.getString("did")) || !MacIds.same(mac, target.getString("address", ""))) return;
        Object battery = XposedHelpers.callMethod(info, "getBatteryInfo");
        if (battery == null) return;
        int level = ((Number) XposedHelpers.callMethod(battery, "getBattery")).intValue();
        if (level < 0 || level > 100) return;
        Bundle values = new Bundle();
        values.putString("did", did);
        values.putString("address", mac);
        values.putInt("battery", level);
        values.putInt("chargerState", ((Number) XposedHelpers.callMethod(battery, "getChargeStatus")).intValue());
        context.getContentResolver().call(MiFitnessMirrorProvider.URI, "battery", null, values);
    }
}
