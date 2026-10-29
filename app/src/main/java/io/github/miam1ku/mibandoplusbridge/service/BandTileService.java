// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import io.github.miam1ku.mibandoplusbridge.R;
import io.github.miam1ku.mibandoplusbridge.data.BandCatalog;
import io.github.miam1ku.mibandoplusbridge.data.BandStateRepository;
import io.github.miam1ku.mibandoplusbridge.data.LocalPrefs;
import io.github.miam1ku.mibandoplusbridge.integration.DeviceCardProvider;

/** SystemUI binding this tile starts the bridge. A tap leaves the connection up. */
public final class BandTileService extends TileService {
    private static final String DEVICES = "com.heytap.mydevices";
    private ContentObserver observer;
    private Bitmap glyph;
    private boolean glyphWatch;

    @Override
    public void onStartListening() {
        super.onStartListening();
        if (CoexistSync.enabled(this)) CoexistSync.request(this);
        else if (BandLiveService.mayWake(this)) BandLiveService.ensureProcess(this);
        if (observer == null) {
            observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override public void onChange(boolean selfChange) { render(); }
            };
            try {
                getContentResolver().registerContentObserver(DeviceCardProvider.URI, false, observer);
            } catch (RuntimeException unavailable) {
                observer = null;
            }
        }
        render();
    }

    @Override
    public void onStopListening() {
        if (observer != null) {
            getContentResolver().unregisterContentObserver(observer);
            observer = null;
        }
        super.onStopListening();
    }

    @Override
    public void onClick() {
        Intent open = new Intent(this, BandTileActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        startActivityAndCollapse(pending);
    }

    private void render() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean registered = false;
        boolean connected = false;
        int battery = -1;
        String model = "";
        boolean coexist = false;
        try {
            coexist = new OwnershipController(this).coexistReady();
            registered = new BandStateRepository(this).isRegistered();
            if (registered) {
                LocalPrefs state = LocalPrefs.open(this, "band-state");
                connected = state.getBoolean("connected", false);
                battery = state.getInt("battery", -1);
                model = state.getString("modelId", "");
            }
        } catch (RuntimeException ignored) {
            registered = false;
        }
        BandTileState.View view = BandTileState.of(
                registered, BandLiveService.isRunning(), connected, battery, coexist);
        boolean watch = BandCatalog.watchForm(model);
        if (glyph == null || glyphWatch != watch) {
            glyph = tileGlyph(watch);
            glyphWatch = watch;
        }
        if (glyph != null) tile.setIcon(Icon.createWithBitmap(glyph));
        tile.setState(view.state());
        tile.setSubtitle(view.subtitle());
        tile.updateTile();
    }

    /** ColorOS draws its own band and watch pictures. The source canvas is mostly empty, so fill the tile. */
    private Bitmap tileGlyph(boolean watch) {
        Bitmap source = systemGlyph(watch);
        if (source == null) {
            return BitmapFactory.decodeResource(getResources(),
                    watch ? R.drawable.ic_qs_watch : R.drawable.ic_qs_band);
        }
        return enlarge(source);
    }

    private Bitmap systemGlyph(boolean watch) {
        try {
            Context devices = createPackageContext(DEVICES, 0);
            int id = devices.getResources().getIdentifier(
                    watch ? "default_watch" : "default_wristband", "drawable", DEVICES);
            if (id == 0) return null;
            return BitmapFactory.decodeResource(devices.getResources(), id);
        } catch (PackageManager.NameNotFoundException missing) {
            return null;
        }
    }

    /** Scale the opaque pixels until the height fills the tile. Strap tips past the edge are clipped. */
    private static Bitmap enlarge(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = new int[width * height];
        source.getPixels(pixels, 0, width, 0, 0, width, height);
        int left = width;
        int top = height;
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((pixels[y * width + x] >>> 24) <= 16) continue;
                if (x < left) left = x;
                if (y < top) top = y;
                if (x > right) right = x;
                if (y > bottom) bottom = y;
            }
        }
        if (right < left || bottom < top) return source;
        Bitmap crop = Bitmap.createBitmap(source, left, top, right - left + 1, bottom - top + 1);
        int size = 216;
        float scale = (size * 1.10f) / crop.getHeight();
        int scaledWidth = Math.max(1, Math.round(crop.getWidth() * scale));
        int scaledHeight = Math.max(1, Math.round(crop.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(crop, scaledWidth, scaledHeight, true);
        Bitmap canvas = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        new Canvas(canvas).drawBitmap(scaled, (size - scaledWidth) / 2f, (size - scaledHeight) / 2f, null);
        if (crop != source) crop.recycle();
        if (scaled != crop) scaled.recycle();
        return canvas;
    }
}
