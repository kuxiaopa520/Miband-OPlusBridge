// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.content.Context;
import android.os.Bundle;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedHelpers;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.HookRuntime;
import io.github.miam1ku.mibandoplusbridge.integration.MiFitnessMirrorProvider;
import org.json.JSONObject;

/** Read the same current product object Mi Fitness uses, including its updated image URLs. */
final class MiFitnessDeviceImage {
    private static final java.util.concurrent.ExecutorService WORKER = HookRuntime.worker("band-product-image", 0);
    private static final java.util.concurrent.atomic.AtomicBoolean PENDING = new java.util.concurrent.atomic.AtomicBoolean();
    private MiFitnessDeviceImage() {}

    static void request(Context context, ClassLoader loader) {
        if (!PENDING.compareAndSet(false, true)) return;
        WORKER.execute(() -> {
            try { publish(context, loader); }
            catch (Throwable unavailable) { HookRuntime.log("MI_PRODUCT_IMAGE_UNAVAILABLE " + unavailable.getClass().getSimpleName()); }
            finally { PENDING.set(false); }
        });
    }

    private static void publish(Context context, ClassLoader loader) throws Exception {
        Bundle target = context.getContentResolver().call(MiFitnessMirrorProvider.URI, "target", null, null);
        if (target == null || !"MI_MIRROR_ACTIVE".equals(target.getString("status"))
                || !target.getBoolean("imageNeeded", false)) return;
        String model = target.getString("model", "");
        Class<?> managerType = Class.forName("com.xiaomi.fitness.device.manager.export.WearableProductManager", false, loader);
        Object companion = XposedHelpers.getStaticObjectField(managerType, "Companion");
        Object manager = XposedHelpers.callStaticMethod(Class.forName(
                "com.xiaomi.fitness.device.manager.export.DeviceManagerExtKt", false, loader), "getInstance", companion);
        Object product = XposedHelpers.callMethod(manager, "getProductByModel", model);
        if (product == null || !model.equals(XposedHelpers.callMethod(product, "getModel"))) return;
        JSONObject image = new JSONObject().put("model", model);
        String[][] fields = {{"sku_big_preview", "getSkuBigPreview"}, {"big_preview", "getBigPreview"},
                {"sku_big_icon", "getSkuBigIcon"}, {"big_icon_3", "getBigIcon3"}, {"big_icon", "getBigIcon"}};
        for (String[] field : fields) image.put(field[0], XposedHelpers.callMethod(product, field[1]));
        Bundle value = new Bundle();
        value.putString("did", target.getString("did"));
        value.putString("product", image.toString());
        context.getContentResolver().call(MiFitnessMirrorProvider.URI, "productImage", null, value);
    }
}
