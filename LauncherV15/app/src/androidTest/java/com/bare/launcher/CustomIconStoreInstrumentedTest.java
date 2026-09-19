package com.bare.launcher;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;

/** Device-level regression coverage for custom-icon bitmap normalization. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class CustomIconStoreInstrumentedTest {

    @Test
    public void normalizeForDisplay_landscapeFullBleed_fillsEveryCorner() {
        Bitmap source = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.RED);
        Bitmap normalized = null;
        try {
            normalized = CustomIconStore.normalizeForDisplay(source, 100);

            assertNotNull(normalized);
            assertEquals(90, normalized.getWidth());
            assertEquals(90, normalized.getHeight());
            assertEquals(Color.RED, normalized.getPixel(0, 0));
            assertEquals(Color.RED,
                    normalized.getPixel(normalized.getWidth() - 1,
                            normalized.getHeight() - 1));
        } finally {
            recycleIfDistinct(normalized, source);
            source.recycle();
        }
    }

    @Test
    public void normalizeForDisplay_transparentPadding_expandsVisibleArtwork() {
        Bitmap source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(source);
        Paint paint = new Paint();
        paint.setColor(Color.BLUE);
        canvas.drawRect(40, 40, 60, 60, paint);
        Bitmap normalized = null;
        try {
            normalized = CustomIconStore.normalizeForDisplay(source, 100);

            assertNotNull(normalized);
            int[] bounds = visibleBounds(normalized);
            int visibleWidth = bounds[2] - bounds[0];
            int visibleHeight = bounds[3] - bounds[1];
            assertTrue(visibleWidth >= normalized.getWidth() * 0.8f);
            assertTrue(visibleHeight >= normalized.getHeight() * 0.8f);
        } finally {
            recycleIfDistinct(normalized, source);
            source.recycle();
        }
    }

    @Test
    public void normalizeForDisplay_fullyTransparent_rejectsImage() {
        Bitmap source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        try {
            assertNull(CustomIconStore.normalizeForDisplay(source, 100));
        } finally {
            source.recycle();
        }
    }

    @Test
    public void tvInputIdentity_saveReadDelete_roundTrips() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        String identity = "tvinput://test.oem/.HdmiInputService/HW99";
        CustomIconStore store = new CustomIconStore(context);
        File sourceFile = new File(context.getCacheDir(), "tv-input-icon-source.png");
        Bitmap source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.MAGENTA);
        try {
            try (FileOutputStream out = new FileOutputStream(sourceFile)) {
                assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, out));
            }
            store.delete(identity);

            assertTrue(store.save(context.getContentResolver(),
                    Uri.fromFile(sourceFile), identity));
            assertTrue(store.has(identity));
            Bitmap stored = store.read(identity);
            try {
                assertNotNull(stored);
                int pixel = stored.getPixel(0, 0);
                assertTrue(Math.abs(Color.red(pixel) - Color.red(Color.MAGENTA)) <= 2);
                assertTrue(Math.abs(Color.green(pixel) - Color.green(Color.MAGENTA)) <= 2);
                assertTrue(Math.abs(Color.blue(pixel) - Color.blue(Color.MAGENTA)) <= 2);
            } finally {
                if (stored != null && !stored.isRecycled()) stored.recycle();
            }
            assertTrue(store.delete(identity));
            assertFalse(store.has(identity));
        } finally {
            store.delete(identity);
            source.recycle();
            //noinspection ResultOfMethodCallIgnored
            sourceFile.delete();
        }
    }

    private static int[] visibleBounds(Bitmap bitmap) {
        int left = bitmap.getWidth();
        int top = bitmap.getHeight();
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (Color.alpha(bitmap.getPixel(x, y)) == 0) continue;
                left = Math.min(left, x);
                top = Math.min(top, y);
                right = Math.max(right, x);
                bottom = Math.max(bottom, y);
            }
        }
        return new int[] {left, top, right + 1, bottom + 1};
    }

    private static void recycleIfDistinct(Bitmap candidate, Bitmap source) {
        if (candidate != null && candidate != source && !candidate.isRecycled()) {
            candidate.recycle();
        }
    }
}
