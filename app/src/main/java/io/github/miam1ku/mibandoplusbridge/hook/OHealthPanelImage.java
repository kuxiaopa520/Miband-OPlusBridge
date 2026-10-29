// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.ViewGroup;
import android.widget.ImageView;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.HookRuntime;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedHelpers;
import java.io.InputStream;
import java.util.WeakHashMap;

/** Load the actual Xiaomi file, bypassing the host's watch resource and Glide error drawable. */
final class OHealthPanelImage {
    private static final java.util.concurrent.ExecutorService IO = HookRuntime.worker("band-panel-image", 0);
    private static final WeakHashMap<ViewGroup, String> REQUESTS = new WeakHashMap<>();
    private static Bitmap cached;
    private static String cachedUri;

    private OHealthPanelImage() {}

    static void show(ViewGroup view, String uri) {
        if (uri.equals(REQUESTS.get(view))) return;
        REQUESTS.put(view, uri);
        ImageView image = new ImageView(view.getContext());
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription("小米手环");
        Object model = XposedHelpers.getObjectField(view, "k");
        if (model != null) {
            XposedHelpers.callMethod(model, "destroy");
        }
        try {
            XposedHelpers.findField(view.getClass(), "k").set(view, null);
            XposedHelpers.findField(view.getClass(), "l").set(view, null);
            XposedHelpers.findField(view.getClass(), "j").set(view, image);
        } catch (IllegalAccessException unavailable) { throw new IllegalStateException(unavailable); }
        view.removeAllViews();
        view.addView(image, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (uri.isBlank()) return; // The product fetch will notify and repaint this open panel.
        if (uri.equals(cachedUri) && cached != null) { image.setImageBitmap(cached); return; }
        IO.execute(() -> {
            try {
                Bitmap bitmap;
                try (InputStream input = view.getContext().getContentResolver().openInputStream(Uri.parse(uri))) {
                    if (input == null) throw new IllegalStateException("DEVICE_IMAGE_MISSING");
                    byte[] bytes = input.readNBytes(4 * 1024 * 1024 + 1);
                    bitmap = decode(bytes);
                }
                HookRuntime.main().post(() -> {
                    if (!uri.equals(REQUESTS.get(view))) return;
                    cached = bitmap;
                    cachedUri = uri;
                    image.setImageBitmap(bitmap);
                    HookRuntime.log("OHEALTH_PANEL_IMAGE_LOADED");
                });
            } catch (Exception failure) {
                HookRuntime.log("OHEALTH_PANEL_IMAGE_FAILED " + failure.getClass().getSimpleName());
                HookRuntime.main().post(() -> REQUESTS.remove(view));
            }
        });
    }

    static Bitmap decode(byte[] bytes) {
        if (bytes.length > 4 * 1024 * 1024) throw new IllegalArgumentException("DEVICE_IMAGE_SIZE");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > 4096 || options.outHeight > 4096) {
            throw new IllegalArgumentException("DEVICE_IMAGE_BOUNDS");
        }
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 1024) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) throw new IllegalArgumentException("DEVICE_IMAGE_DECODE");
        return bitmap;
    }
}
