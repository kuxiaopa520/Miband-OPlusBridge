// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = 35)
public class OHealthPanelImageTest {
    public static final class Panel extends android.widget.FrameLayout {
        public Object k, l;
        public android.widget.ImageView j;
        Panel(android.content.Context context) { super(context); }
    }

    public static final class Images extends android.content.ContentProvider {
        static java.io.File file;
        @Override public boolean onCreate() { return true; }
        @Override public android.os.ParcelFileDescriptor openFile(android.net.Uri uri, String mode) throws java.io.FileNotFoundException {
            return android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public String getType(android.net.Uri uri) { return "image/png"; }
        @Override public android.database.Cursor query(android.net.Uri u, String[] p, String s, String[] a, String o) { return null; }
        @Override public android.net.Uri insert(android.net.Uri u, android.content.ContentValues v) { return null; }
        @Override public int update(android.net.Uri u, android.content.ContentValues v, String s, String[] a) { return 0; }
        @Override public int delete(android.net.Uri u, String s, String[] a) { return 0; }
    }

    @Test public void theOpenPanelRepaintsFromTheActualProviderFileAfterItBecomesAvailable() throws Exception {
        var controller = org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().visible();
        var activity = controller.get();
        Panel panel = new Panel(activity);
        activity.setContentView(panel);
        OHealthPanelImage.show(panel, "");
        assertNull(panel.j.getDrawable());
        var provider = org.robolectric.Robolectric.buildContentProvider(Images.class).create("test.band.image").get();
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("test.band.image", provider);
        Images.file = new java.io.File(activity.getCacheDir(), "actual-device.png");
        Bitmap original = Bitmap.createBitmap(48, 96, Bitmap.Config.ARGB_8888);
        original.eraseColor(0xff345678);
        try (var output = new java.io.FileOutputStream(Images.file)) { original.compress(Bitmap.CompressFormat.PNG, 100, output); }
        try {
            OHealthPanelImage.show(panel, "content://test.band.image/actual-device.png");
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (panel.j.getDrawable() == null && System.nanoTime() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                Thread.sleep(10);
            }
            assertTrue(org.robolectric.shadows.ShadowLog.getLogs().toString(),
                    panel.j.getDrawable() instanceof android.graphics.drawable.BitmapDrawable);
            Bitmap shown = ((android.graphics.drawable.BitmapDrawable) panel.j.getDrawable()).getBitmap();
            assertEquals(0xff345678, shown.getPixel(24, 48));
            assertEquals(1, panel.getChildCount());
        } finally { controller.pause().stop().destroy(); Images.file.delete(); original.recycle(); }
    }

    @Test public void theSuppliedImagePixelsAreDecodedWithoutSubstitutingAWatch() {
        Bitmap original = Bitmap.createBitmap(360, 720, Bitmap.Config.ARGB_8888);
        original.eraseColor(0xff123456);
        var png = new ByteArrayOutputStream();
        assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, png));
        Bitmap decoded = OHealthPanelImage.decode(png.toByteArray());
        assertEquals(360, decoded.getWidth()); assertEquals(720, decoded.getHeight());
        assertEquals(0xff123456, decoded.getPixel(180, 360));
        assertThrows(IllegalArgumentException.class, () -> OHealthPanelImage.decode(new byte[]{1, 2, 3}));
        decoded.recycle(); original.recycle();
    }
}
