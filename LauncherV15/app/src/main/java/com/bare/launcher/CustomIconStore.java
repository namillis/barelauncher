package com.bare.launcher;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persistent per-package custom launcher icons.
 *
 * <p>A selected image is decoded off the UI thread, bounded to
 * {@link #MAX_SOURCE_PX}, fitted into a transparent square without stretching,
 * and written atomically as WebP under the launcher's private files directory.
 * The source image can therefore move or disappear after selection without
 * breaking the override, and no broad storage permission is required.
 *
 * <p>The in-memory source version prevents an icon decode that started before a
 * change from publishing stale artwork after the new file has been committed.
 * Versions are process-local because they only coordinate in-flight work; the
 * icon files themselves provide persistence across process restarts.
 */
final class CustomIconStore {

    static final int MAX_SOURCE_PX = 512;

    private static final String DIR_NAME = "custom-icons";
    private static final String EXT = ".webp";
    private static final String TMP_EXT = ".webp.tmp";
    private static final int QUALITY = 95;
    private static final int MAX_SAMPLE = 0x4000;

    private final File dir;
    private final ConcurrentHashMap<String, Long> versions = new ConcurrentHashMap<>();
    private final AtomicLong nextVersion = new AtomicLong();

    CustomIconStore(Context appContext) {
        dir = new File(appContext.getFilesDir(), DIR_NAME);
        if (!dir.exists()) {
            // Best effort. A failed mkdir simply makes save() return false.
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
    }

    /** Whether a committed custom icon exists for this package. */
    boolean has(String packageName) {
        File file = fileFor(packageName, EXT);
        return file != null && file.isFile() && file.length() > 0;
    }

    /** Process-local version used to reject stale asynchronous decode results. */
    long sourceVersion(String packageName) {
        if (packageName == null) return 0L;
        Long version = versions.get(packageName);
        return version != null ? version : 0L;
    }

    /** Decode the stored normalized source bitmap, or {@code null} on failure. */
    Bitmap read(String packageName) {
        File file = fileFor(packageName, EXT);
        if (file == null || !file.isFile() || file.length() == 0) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeStream(in, null, options);
        } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
            return null;
        }
    }

    /**
     * Copy and normalize a user-selected image into private storage.
     *
     * <p>Synchronized because two picker results for the same package share one
     * temporary filename. Selection is rare, so serializing writes has no
     * measurable runtime cost and keeps the atomic-publish contract simple.
     */
    @SuppressWarnings("deprecation")
    synchronized boolean save(ContentResolver resolver, Uri uri, String packageName) {
        File dest = fileFor(packageName, EXT);
        File tmp = fileFor(packageName, TMP_EXT);
        if (resolver == null || uri == null || dest == null || tmp == null) return false;

        Bitmap decoded = null;
        Bitmap normalized = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = resolver.openInputStream(uri)) {
                if (in == null) return false;
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = computeSampleSize(
                    bounds.outWidth, bounds.outHeight, MAX_SOURCE_PX);
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream in = resolver.openInputStream(uri)) {
                if (in == null) return false;
                decoded = BitmapFactory.decodeStream(in, null, options);
            }
            if (decoded == null || decoded.getWidth() <= 0 || decoded.getHeight() <= 0) {
                return false;
            }

            normalized = fitIntoSquare(decoded, MAX_SOURCE_PX);
            if (normalized == null) return false;

            if (!dir.exists() && !dir.mkdirs()) return false;
            // Remove a temp file left by process death before starting a new write.
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            boolean wrote;
            try (BufferedOutputStream out =
                         new BufferedOutputStream(new FileOutputStream(tmp))) {
                wrote = normalized.compress(Bitmap.CompressFormat.WEBP, QUALITY, out);
            }
            if (!wrote) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return false;
            }
            if (!tmp.renameTo(dest)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return false;
            }
            bumpVersion(packageName);
            return true;
        } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
            // Keep the previous committed icon intact on every failure path.
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        } finally {
            if (decoded != null && !decoded.isRecycled()) decoded.recycle();
            if (normalized != null && !normalized.isRecycled()) normalized.recycle();
        }
    }

    /** Delete the override and return whether the default icon can be used. */
    synchronized boolean delete(String packageName) {
        File dest = fileFor(packageName, EXT);
        File tmp = fileFor(packageName, TMP_EXT);
        if (dest == null || tmp == null) return false;
        boolean existed = dest.exists();
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
        if (existed && !dest.delete()) return false;
        if (existed) bumpVersion(packageName);
        return true;
    }

    /**
     * Largest power-of-two decode sample that leaves at least one source axis
     * at or above {@code targetMax}. The final square-fit step performs the
     * high-quality filtered downscale; this first pass prevents a 4K/8K image
     * from becoming a large transient ARGB allocation.
     */
    static int computeSampleSize(int sourceWidth, int sourceHeight, int targetMax) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetMax <= 0) return 1;
        int sample = 1;
        while (sample < MAX_SAMPLE) {
            int next = sample << 1;
            if (sourceWidth / next < targetMax && sourceHeight / next < targetMax) break;
            sample = next;
        }
        return sample;
    }

    /** File-safe Android package identifier accepted as a custom-icon key. */
    static boolean isSafePackageName(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        for (int i = 0, n = packageName.length(); i < n; i++) {
            char c = packageName.charAt(i);
            boolean safe = c == '.' || c == '_'
                    || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9');
            if (!safe) return false;
        }
        return true;
    }

    private static Bitmap fitIntoSquare(Bitmap source, int maxSide) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        if (sourceWidth <= 0 || sourceHeight <= 0 || maxSide <= 0) return null;
        int side = Math.min(maxSide, Math.max(sourceWidth, sourceHeight));
        Bitmap output = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        float scale = Math.min((float) side / sourceWidth, (float) side / sourceHeight);
        float width = sourceWidth * scale;
        float height = sourceHeight * scale;
        float left = (side - width) / 2f;
        float top = (side - height) / 2f;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        new Canvas(output).drawBitmap(
                source, null, new RectF(left, top, left + width, top + height), paint);
        return output;
    }

    private File fileFor(String packageName, String suffix) {
        if (!isSafePackageName(packageName)) return null;
        return new File(dir, packageName + suffix);
    }

    private void bumpVersion(String packageName) {
        versions.put(packageName, nextVersion.incrementAndGet());
    }
}
