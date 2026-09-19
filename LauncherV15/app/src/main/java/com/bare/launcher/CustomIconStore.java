package com.bare.launcher;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persistent per-package custom launcher icons.
 *
 * <p>A selected image is decoded off the UI thread, bounded to
 * {@link #MAX_SOURCE_PX}, and normalized without stretching. Full-bleed
 * artwork is center-cropped to a square; PNG-style artwork has excess
 * transparent margins trimmed before it is fitted into the square. The
 * result is written atomically as WebP under the launcher's private files
 * directory. The source image can therefore move or disappear after
 * selection without breaking the override, and no broad storage permission
 * is required.
 *
 * <p>The in-memory source version prevents an icon decode that started before a
 * change from publishing stale artwork after the new file has been committed.
 * Versions are process-local because they only coordinate in-flight work; the
 * icon files themselves provide persistence across process restarts.
 */
final class CustomIconStore {

    static final int MAX_SOURCE_PX = 512;

    private static final int VISIBLE_ALPHA_THRESHOLD = 16;
    private static final float TRANSPARENT_ICON_PADDING_FRACTION = 0.04f;
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

            normalized = normalizeForDisplay(decoded, MAX_SOURCE_PX);
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
     * at or above {@code targetMax}. The final normalization step performs
     * the high-quality filtered crop/downscale; this first pass prevents a
     * 4K/8K image from becoming a large transient ARGB allocation.
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

    /**
     * Normalize user-selected artwork without stretching it.
     *
     * <p>Opaque/full-bleed images use center-crop so landscape and portrait
     * files fill the square instead of acquiring transparent letterbox bars.
     * Images with a transparent outer margin are treated as logo artwork:
     * the visible bounds are trimmed, then fitted with a small breathing room
     * so the logo is enlarged without cutting it off.
     *
     * <p>The input remains caller-owned. The method returns it unchanged only
     * when it is already a full-bleed square no larger than {@code maxSide}.
     */
    static Bitmap normalizeForDisplay(Bitmap source, int maxSide) {
        if (source == null || maxSide <= 0) return null;
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        if (sourceWidth <= 0 || sourceHeight <= 0) return null;

        Rect visible = findVisibleBounds(source);
        if (visible == null || visible.isEmpty()) return null;
        boolean hasTransparentMargin = visible.left > 0 || visible.top > 0
                || visible.right < sourceWidth || visible.bottom < sourceHeight;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG
                | Paint.DITHER_FLAG);

        if (!hasTransparentMargin) {
            int[] crop = centerCropBounds(sourceWidth, sourceHeight);
            if (crop == null) return null;
            int cropSide = crop[2] - crop[0];
            int outputSide = Math.min(maxSide, cropSide);
            if (sourceWidth == sourceHeight && sourceWidth == outputSide) return source;
            Bitmap output = Bitmap.createBitmap(
                    outputSide, outputSide, Bitmap.Config.ARGB_8888);
            new Canvas(output).drawBitmap(source,
                    new Rect(crop[0], crop[1], crop[2], crop[3]),
                    new RectF(0, 0, outputSide, outputSide), paint);
            return output;
        }

        int contentWidth = visible.width();
        int contentHeight = visible.height();
        int contentMax = Math.max(contentWidth, contentHeight);
        float usableFraction = 1f - 2f * TRANSPARENT_ICON_PADDING_FRACTION;
        int outputSide = Math.min(maxSide,
                Math.max(1, (int) Math.ceil(contentMax / usableFraction)));
        float padding = outputSide * TRANSPARENT_ICON_PADDING_FRACTION;
        float available = Math.max(1f, outputSide - 2f * padding);
        float scale = Math.min(available / contentWidth, available / contentHeight);
        float width = contentWidth * scale;
        float height = contentHeight * scale;
        float left = (outputSide - width) / 2f;
        float top = (outputSide - height) / 2f;

        Bitmap output = Bitmap.createBitmap(
                outputSide, outputSide, Bitmap.Config.ARGB_8888);
        new Canvas(output).drawBitmap(source, visible,
                new RectF(left, top, left + width, top + height), paint);
        return output;
    }

    /** Center square within a source image, returned as left/top/right/bottom. */
    static int[] centerCropBounds(int sourceWidth, int sourceHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0) return null;
        int side = Math.min(sourceWidth, sourceHeight);
        int left = (sourceWidth - side) / 2;
        int top = (sourceHeight - side) / 2;
        return new int[] {left, top, left + side, top + side};
    }

    /** Tight bounds of pixels with meaningful alpha, or null when fully transparent. */
    private static Rect findVisibleBounds(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        if (!source.hasAlpha()) return new Rect(0, 0, width, height);

        int left = width;
        int top = height;
        int right = -1;
        int bottom = -1;
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            source.getPixels(row, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                if ((row[x] >>> 24) < VISIBLE_ALPHA_THRESHOLD) continue;
                if (x < left) left = x;
                if (x > right) right = x;
                if (y < top) top = y;
                bottom = y;
            }
        }
        return right >= left ? new Rect(left, top, right + 1, bottom + 1) : null;
    }

    /**
     * Stable flat filename stem for an app package or synthetic TV-input identity.
     * Real package names retain their existing filenames. TV Input Framework IDs
     * can contain slashes and component names, so they use a fixed-size SHA-256
     * stem rather than weakening path validation or risking filename collisions.
     */
    static String fileStemForIdentity(String identity) {
        if (isSafePackageName(identity)) return identity;
        if (!AppInfo.isTvInputIdentity(identity)) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.substring(AppInfo.TV_INPUT_PREFIX.length())
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder stem = new StringBuilder("@tvinput_");
            for (byte value : digest) {
                int b = value & 0xff;
                if (b < 0x10) stem.append('0');
                stem.append(Integer.toHexString(b));
            }
            return stem.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return null; // SHA-256 is mandatory on every supported Android runtime.
        }
    }

    private File fileFor(String identity, String suffix) {
        String stem = fileStemForIdentity(identity);
        if (stem == null) return null;
        return new File(dir, stem + suffix);
    }

    private void bumpVersion(String packageName) {
        versions.put(packageName, nextVersion.incrementAndGet());
    }
}
