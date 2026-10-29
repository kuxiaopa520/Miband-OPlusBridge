// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import io.github.miam1ku.mibandoplusbridge.data.MacIds;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.HookRuntime;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XC_MethodHook;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedBridge;
import io.github.miam1ku.mibandoplusbridge.integration.MiFitnessMirrorProvider;
import static io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedHelpers.*;

/** Reconcile the selected official connection from Mi Fitness, including its live :device process. */
final class MiFitnessConnectionHook {
    private MiFitnessConnectionHook() {}

    static void install(Context context, ClassLoader loader) throws ReflectiveOperationException {
        String process = Application.getProcessName();
        if (!"com.mi.health".equals(process) && !"com.mi.health:device".equals(process)) return;
        Handler worker = new Handler(HookRuntime.thread("band-mi-connection").getLooper());
        Runnable probe = new Runnable() {
            @Override public void run() {
                try {
                    Bundle target = context.getContentResolver().call(MiFitnessMirrorProvider.URI, "target", null, null);
                    if (target != null && "MI_MIRROR_ACTIVE".equals(target.getString("status"))) {
                        Class<?> type = Class.forName("com.xiaomi.fitness.device.manager.export.WearableDeviceManager", false, loader);
                        Object manager = callStaticMethod(Class.forName("com.xiaomi.fitness.device.manager.export.DeviceManagerExtKt", false, loader),
                                "getInstance", getStaticObjectField(type, "Companion"));
                        Object model = callMethod(manager, "getDeviceModel", target.getString("did"));
                        if (model != null) publishModel(context, model);
                    }
                } catch (Throwable failure) {
                    HookRuntime.log("MI_CONNECTION_RECONCILE_UNAVAILABLE " + failure.getClass().getSimpleName());
                } finally {
                    worker.removeCallbacks(this);
                    worker.postDelayed(this, 30_000);
                }
            }
        };
        HookRuntime.track(() -> worker.removeCallbacks(probe));
        Class<?> listener = Class.forName("com.xiaomi.fitness.device.manager.OnDeviceStatusListenerAdapter", false, loader);
        for (String event : new String[]{"onConnectSuccess", "onDisconnect"}) {
            XposedBridge.hookMethod(listener.getMethod(event, String.class), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    String did = (String) param.args[0];
                    worker.post(() -> {
                        try { publishEvent(context, did, "onConnectSuccess".equals(event)); }
                        catch (Throwable failure) { HookRuntime.log("MI_CONNECTION_EVENT_UNAVAILABLE " + failure.getClass().getSimpleName()); }
                        worker.removeCallbacks(probe);
                        worker.postDelayed(probe, 2_000);
                    });
                }
            });
        }
        worker.postDelayed(probe, 3_000);
        HookRuntime.log("MI_CONNECTION_HOOK_INSTALLED process=" + process);
    }

    static void publishModel(Context context, Object model) {
        int status = ((Number) callMethod(model, "getDeviceStatus")).intValue();
        // -1 and 0 are missing/uninitialized models. Neither proves that the physical link was lost.
        if (status < 1 || status > 9) return;
        Object info = callMethod(model, "getDeviceInfo");
        if (info == null) return;
        Bundle target = context.getContentResolver().call(MiFitnessMirrorProvider.URI, "target", null, null);
        if (target == null || !"MI_MIRROR_ACTIVE".equals(target.getString("status"))) return;
        String did = String.valueOf(callMethod(model, "getDid"));
        String mac = String.valueOf(callMethod(info, "getMac"));
        if (!did.equals(target.getString("did")) || !MacIds.same(mac, target.getString("address", ""))) return;
        Bundle values = new Bundle();
        values.putString("did", did); values.putString("address", mac);
        values.putBoolean("connected", Boolean.TRUE.equals(callMethod(model, "isDeviceConnected")));
        values.putString("source", "mi-model"); values.putInt("deviceStatus", status);
        context.getContentResolver().call(MiFitnessMirrorProvider.URI, "state", null, values);
    }

    static void publishEvent(Context context, String did, boolean connected) {
        if (did == null || did.isBlank()) return;
        Bundle target = context.getContentResolver().call(MiFitnessMirrorProvider.URI, "target", null, null);
        if (target == null || !"MI_MIRROR_ACTIVE".equals(target.getString("status")) || !did.equals(target.getString("did"))) return;
        Bundle values = new Bundle();
        values.putString("did", did); values.putString("address", target.getString("address"));
        values.putBoolean("connected", connected); values.putString("source", "mi-event");
        context.getContentResolver().call(MiFitnessMirrorProvider.URI, "state", null, values);
    }
}
