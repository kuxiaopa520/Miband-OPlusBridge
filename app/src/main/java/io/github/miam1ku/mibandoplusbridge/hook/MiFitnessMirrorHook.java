// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.SystemClock;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.HookRuntime;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XC_MethodHook;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedBridge;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedHelpers;
import io.github.miam1ku.mibandoplusbridge.integration.MiFitnessMirrorProvider;
import io.github.miam1ku.mibandoplusbridge.integration.DeviceCardProvider;
import java.lang.reflect.Method;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;

/** Observe the official fitness receiver; never replace its return value or send band commands. */
public final class MiFitnessMirrorHook {
    private static volatile String selectedDid = "";
    private static volatile long targetExpires;
    private static volatile long rawStateAt;
    private static long lastFailureLog;

    private MiFitnessMirrorHook() {}

    public static void install(Context context, ClassLoader loader) throws Exception {
        HookRuntime.observe(context.getContentResolver(), DeviceCardProvider.URI, false, new ContentObserver(null) {
            @Override public void onChange(boolean selfChange) { targetExpires = 0; }
        });
        int receivers = 0;
        for (String[] receiverSpec : new String[][]{
                {"com.xiaomi.fit.fitness.device.mi.receive.FitnessReceiverHandler", "handleDataInternal"},
                {"com.xiaomi.fit.fitness.eco.device.mi.receive.FitnessReceiverHandler", "handleData"}}) {
            try {
                Class<?> receiver = Class.forName(receiverSpec[0], false, loader);
                Method receive = receiver.getDeclaredMethod(receiverSpec[1], String.class, int.class, byte[].class);
                if (receive.getReturnType() != boolean.class) continue;
                XposedBridge.hookMethod(receive, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!Integer.valueOf(102).equals(param.args[1]) || !(param.args[2] instanceof byte[] data)
                                || data.length < 5 || data.length > MiFitnessMirrorProvider.MAX_FRAGMENT_BYTES) return;
                        try {
                            if (!selected(context, (String) param.args[0])) return;
                            Bundle fragment = new Bundle();
                            fragment.putString("did", (String) param.args[0]);
                            fragment.putByteArray("data", data);
                            // Store the last fragment before the official receiver confirms the file.
                            // A failed mirror still leaves the official receiver's normal execution intact.
                            Bundle reply = context.getContentResolver().call(MiFitnessMirrorProvider.URI,
                                    "fragment", null, fragment);
                            String status = reply == null ? "MI_MIRROR_PROVIDER_UNAVAILABLE" : reply.getString("status", "");
                            if (!status.equals("MI_MIRROR_FRAGMENT_ACCEPTED") && !status.equals("MI_MIRROR_FILE_SAVED")) {
                                report(status);
                            }
                        } catch (Throwable failure) {
                            report("MI_MIRROR_FRAGMENT_SKIPPED " + failure.getClass().getSimpleName());
                        }
                    }
                });
                receivers++;
            } catch (ClassNotFoundException | NoSuchMethodException missing) {
                // The normal and eco device stacks are optional independently.
            }
        }
        if (receivers == 0) throw new NoSuchMethodException("MI_MIRROR_RECEIVER_UNAVAILABLE");

        Class<?> api = Class.forName("com.xiaomi.wearable.wear.api.WearApiCall", false, loader);
        XposedBridge.hookMethod(api.getDeclaredMethod("handleData", int.class, byte[].class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    long now = SystemClock.elapsedRealtime();
                    if (Integer.valueOf(102).equals(param.args[0]) && (rawStateAt == 0 || now - rawStateAt >= 10_000)
                            && publishState(context, param.thisObject,
                            Boolean.TRUE.equals(XposedHelpers.callMethod(param.thisObject, "isConnected")), null)) {
                        rawStateAt = now;
                    }
                } catch (Throwable failure) { report("MI_MIRROR_STATE_SKIPPED"); }
            }
        });
        for (String event : new String[]{"onConnected", "onDisconnected"}) {
            XposedBridge.hookMethod(api.getDeclaredMethod(event), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable()) publishState(context, param.thisObject,
                            "onConnected".equals(event), null);
                }
            });
        }
        Class<?> info = Class.forName("com.xiaomi.wearable.core.DeviceInfo", false, loader);
        XposedBridge.hookMethod(api.getDeclaredMethod("onUpdate", info), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!param.hasThrowable()) publishState(context, param.thisObject,
                            Boolean.TRUE.equals(XposedHelpers.callMethod(param.thisObject, "isConnected")), null);
                } catch (Throwable failure) { report("MI_MIRROR_STATE_SKIPPED"); }
            }
        });
        Class<?> callback = Class.forName("com.xiaomi.wearable.core.ICallback", false, loader);
        Class<?> result = Class.forName("com.xiaomi.wearable.core.WearApiResult", false, loader);
        XposedBridge.hookMethod(api.getDeclaredMethod("callbackClient", callback, result), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object device = XposedHelpers.callMethod(param.thisObject, "getDeviceInfo");
                    if (!selected(context, (String) XposedHelpers.callMethod(device, "getDid"))
                            || !Integer.valueOf(0).equals(XposedHelpers.callMethod(param.args[1], "a"))) return;
                    byte[] bytes = (byte[]) XposedHelpers.callMethod(param.args[1], "c");
                    if (bytes == null || bytes.length > 4096) return;
                    XiaomiProto.Command command = XiaomiProto.Command.parseFrom(bytes);
                    if (command.getType() == 2 && command.getSubtype() == 1 && command.hasSystem()
                            && command.getSystem().hasPower() && command.getSystem().getPower().hasBattery()) {
                        var battery = command.getSystem().getPower().getBattery();
                        Bundle values = new Bundle();
                        if (battery.hasLevel()) values.putInt("battery", battery.getLevel());
                        if (battery.hasState()) values.putInt("chargerState", battery.getState());
                        publishState(context, param.thisObject,
                                Boolean.TRUE.equals(XposedHelpers.callMethod(param.thisObject, "isConnected")), values);
                    }
                } catch (Throwable ignored) {
                    // Many API results are unrelated to the Xiaomi system protobuf.
                }
            }
        });
        HookRuntime.log("MI_MIRROR_HOOK_INSTALLED receivers=" + receivers);
    }

    private static boolean selected(Context context, String did) {
        long now = SystemClock.elapsedRealtime();
        if (selectedDid.isBlank() || now >= targetExpires) {
            Bundle target = context.getContentResolver().call(MiFitnessMirrorProvider.URI, "target", null, null);
            selectedDid = target != null && "MI_MIRROR_ACTIVE".equals(target.getString("status"))
                    ? target.getString("did", "") : "";
            targetExpires = now + 5_000;
        }
        return did != null && !did.isBlank() && did.equals(selectedDid);
    }

    static boolean publishState(Context context, Object api, boolean connected, Bundle values) {
        try {
            // A freshly created API/proxy can report false while Mi's actual device model
            // is still connected. Confirm disconnections through the official manager.
            if (!connected && values == null) return false;
            Object info = XposedHelpers.callMethod(api, "getDeviceInfo");
            String did = (String) XposedHelpers.callMethod(info, "getDid");
            if (!selected(context, did)) return false;
            Bundle state = values == null ? new Bundle() : values;
            state.putString("did", did);
            state.putString("address", (String) XposedHelpers.callMethod(info, "getAddress"));
            state.putString("firmware", (String) XposedHelpers.callMethod(info, "getFirmwareVersion"));
            state.putBoolean("connected", connected);
            state.putString("source", "mi-transport");
            Bundle reply = context.getContentResolver().call(MiFitnessMirrorProvider.URI, connected ? "state" : "battery", null, state);
            return reply != null && ("MI_MIRROR_STATE_SAVED".equals(reply.getString("status"))
                    || "MI_MIRROR_BATTERY_SAVED".equals(reply.getString("status")));
        } catch (Throwable failure) {
            report("MI_MIRROR_STATE_SKIPPED " + failure.getClass().getSimpleName());
            return false;
        }
    }

    private static synchronized void report(String status) {
        long now = SystemClock.elapsedRealtime();
        if (lastFailureLog != 0 && now - lastFailureLog < 60_000) return;
        lastFailureLog = now;
        HookRuntime.log(status);
    }
}
