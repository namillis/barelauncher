package com.bare.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

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
