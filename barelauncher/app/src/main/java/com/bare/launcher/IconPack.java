package com.bare.launcher;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One installed ADW/Nova-compatible icon pack.
 *
 * <p>Artwork stays inside the pack APK. BareLauncher reads the pack's
 * {@code appfilter.xml} lazily on an icon worker the first time a disk-cache
 * miss needs it, then renders mapped drawables through the same path as a
 * user-selected custom icon. Every failure means "no pack icon"; callers fall
 * back to the app's own artwork.
 */
final class IconPack {

    /** Theme actions icon packs declare so launchers can discover them. */
    static final String[] DISCOVERY_ACTIONS = {
            "org.adw.launcher.THEMES",
            "com.novalauncher.THEME",
            "com.anddoes.launcher.THEME",
            "com.gau.go.launcherex.theme",
    };

    /** Longest rasterized side for pack artwork before per-surface rendering. */
    private static final int MAX_ARTWORK_PX = 512;
    private static final int DEFAULT_ARTWORK_PX = 256;

    /** A pack shown in Settings. */
    static final class Choice {
        final String packageName;
        final String label;

        Choice(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    final String packageName;
    /** Disk-cache namespace; changes when the pack is updated. */
    final String cacheVariant;

    private final PackageManager pm;
    private final Object lock = new Object();
    /** Separate from {@link #lock} so named loads never wait on an appfilter parse. */
    private final Object resourcesLock = new Object();
    private boolean loadAttempted;
    private boolean resourcesAttempted;
    private IconPackMap map;
    private Resources resources;

    private IconPack(PackageManager pm, String packageName, String cacheVariant) {
        this.pm = pm;
        this.packageName = packageName;
        this.cacheVariant = cacheVariant;
    }

    /** Installed icon packs, sorted by label. Excludes BareLauncher itself. */
    static List<Choice> discover(PackageManager pm, String selfPackage) {
        Map<String, Choice> found = new LinkedHashMap<>();
        if (pm == null) return new ArrayList<>();
        for (String action : DISCOVERY_ACTIONS) {
            List<ResolveInfo> matches;
            try {
                matches = pm.queryIntentActivities(new Intent(action), 0);
            } catch (RuntimeException e) {
                continue;
            }
            if (matches == null) continue;
            for (ResolveInfo ri : matches) {
                ActivityInfo ai = ri != null ? ri.activityInfo : null;
                if (ai == null || ai.packageName == null) continue;
                String pkg = ai.packageName;
                if (pkg.equals(selfPackage) || found.containsKey(pkg)) continue;
                String label = null;
                try {
                    CharSequence cs = ai.applicationInfo != null
                            ? ai.applicationInfo.loadLabel(pm) : null;
                    if (cs != null) label = cs.toString().trim();
                } catch (RuntimeException ignored) { /* use package name */ }
                if (label == null || label.isEmpty()) label = pkg;
                found.put(pkg, new Choice(pkg, label));
            }
        }
        List<Choice> out = new ArrayList<>(found.values());
        Collections.sort(out, (a, b) -> {
            int c = a.label.compareToIgnoreCase(b.label);
            return c != 0 ? c : a.packageName.compareTo(b.packageName);
        });
        return out;
    }

    /**
     * Open the selected pack without parsing it. Costs one PackageManager
     * lookup, so cold starts with warm disk caches never read appfilter.xml.
     * Returns {@code null} when the pack is not installed.
     */
    static IconPack open(PackageManager pm, String packageName) {
        if (pm == null || !IconPackMap.isPackageName(packageName)) return null;
        try {
            PackageInfo info = pm.getPackageInfo(packageName, 0);
            long version = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode;
            return new IconPack(pm, packageName,
                    IconPackMap.cacheVariant(packageName, version, info.lastUpdateTime));
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Rasterized pack artwork for an app, or {@code null} when the pack has
     * no safe mapping or its resource cannot be loaded. Worker thread only.
     */
    Bitmap loadArtwork(String appPackage, ComponentName launchComponent) {
        IconPackMap m = mapping();
        if (m == null) return null;
        String drawableName = m.drawableFor(appPackage,
                launchComponent != null ? launchComponent.getClassName() : null,
                this::phoneLauncherClass);
        return drawableName != null ? loadNamedArtwork(drawableName, MAX_ARTWORK_PX) : null;
    }

    /**
     * Rasterize one of the pack's drawables by resource name, longest side
     * capped at {@code maxPx}. Returns {@code null} when it does not exist or
     * fails to load. Worker thread only.
     */
    Bitmap loadNamedArtwork(String drawableName, int maxPx) {
        if (!IconPackMap.isDrawableName(drawableName)) return null;
        Resources res = resources();
        if (res == null) return null;
        try {
            int id = res.getIdentifier(drawableName, "drawable", packageName);
            if (id == 0) id = res.getIdentifier(drawableName, "mipmap", packageName);
            if (id == 0) return null;
            Drawable d = res.getDrawable(id, null);
            return d != null ? rasterize(d, Math.max(1, Math.min(maxPx, MAX_ARTWORK_PX))) : null;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * Every icon the pack offers, read from {@code drawable.xml} (XML or raw
     * resource) or, for packs without one, from the appfilter mappings.
     * Parses on every call; the picker keeps the result only while open.
     * Worker thread only.
     */
    IconPackCatalog loadCatalog() {
        Resources res = resources();
        if (res == null) return new IconPackCatalog(null);
        List<String> names = null;
        try {
            int xmlId = res.getIdentifier("drawable", "xml", packageName);
            if (xmlId != 0) {
                XmlResourceParser parser = res.getXml(xmlId);
                try {
                    names = IconPackCatalog.parseDrawableXml(tags(parser));
                } finally {
                    parser.close();
                }
            }
            if (names == null || names.isEmpty()) {
                int rawId = res.getIdentifier("drawable", "raw", packageName);
                if (rawId != 0) {
                    try (InputStream in = res.openRawResource(rawId)) {
                        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
                        parser.setInput(in, null);
                        names = IconPackCatalog.parseDrawableXml(tags(parser));
                    }
                }
            }
        } catch (Exception | OutOfMemoryError e) {
            names = null;
        }
        if (names == null || names.isEmpty()) {
            IconPackMap m = mapping();
            names = m != null ? m.drawableNames() : null;
        }
        return new IconPackCatalog(names);
    }

    /** The pack's resources, opened once per process. */
    private Resources resources() {
        synchronized (resourcesLock) {
            if (!resourcesAttempted) {
                resourcesAttempted = true;
                try {
                    resources = pm.getResourcesForApplication(packageName);
                } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                    resources = null;
                }
            }
            return resources;
        }
    }

    /** Parse appfilter once per process; later calls reuse the result. */
    private IconPackMap mapping() {
        Resources res = resources();
        synchronized (lock) {
            if (loadAttempted) return map;
            loadAttempted = true;
            if (res == null) return null;
            try {
                IconPackMap parsed = null;
                int xmlId = res.getIdentifier("appfilter", "xml", packageName);
                if (xmlId != 0) {
                    XmlResourceParser parser = res.getXml(xmlId);
                    try {
                        parsed = IconPackMap.parse(tags(parser));
                    } finally {
                        parser.close();
                    }
                }
                if (parsed == null || parsed.size() == 0) {
                    int rawId = res.getIdentifier("appfilter", "raw", packageName);
                    if (rawId != 0) {
                        try (InputStream in = res.openRawResource(rawId)) {
                            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
                            parser.setInput(in, null);
                            parsed = IconPackMap.parse(tags(parser));
                        }
                    }
                }
                if (parsed != null && parsed.size() > 0) map = parsed;
            } catch (Exception | OutOfMemoryError e) {
                map = null;
            }
            return map;
        }
    }

    /** Phone LAUNCHER activity, used only for packages with several icons. */
    private String phoneLauncherClass(String appPackage) {
        try {
            Intent launch = pm.getLaunchIntentForPackage(appPackage);
            ComponentName c = launch != null ? launch.getComponent() : null;
            return c != null ? c.getClassName() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Bitmap rasterize(Drawable d, int maxPx) {
        int w = d.getIntrinsicWidth();
        int h = d.getIntrinsicHeight();
        if (w <= 0 || h <= 0) {
            w = DEFAULT_ARTWORK_PX;
            h = DEFAULT_ARTWORK_PX;
        }
        float scale = Math.min(1f, maxPx / (float) Math.max(w, h));
        w = Math.max(1, Math.round(w * scale));
        h = Math.max(1, Math.round(h * scale));
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(out));
        return out;
    }

    private static IconPackMap.TagSource tags(XmlPullParser parser) {
        return new IconPackMap.TagSource() {
            @Override public int next() throws Exception { return parser.next(); }
            @Override public String name() { return parser.getName(); }
            @Override public String attribute(String name) {
                return parser.getAttributeValue(null, name);
            }
        };
    }
}
