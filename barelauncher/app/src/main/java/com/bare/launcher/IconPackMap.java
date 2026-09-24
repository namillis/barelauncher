package com.bare.launcher;

import java.util.HashMap;
import java.util.Map;

/**
 * Android-free model of an ADW/Nova-style icon pack's {@code appfilter.xml}.
 *
 * <p>Packs map launcher components to drawable names:
 * <pre>
 *   &lt;item component="ComponentInfo{com.app/com.app.MainActivity}" drawable="app" /&gt;
 * </pre>
 * Most packs are authored against phone {@code LAUNCHER} activities, while
 * BareLauncher starts TV apps through their {@code LEANBACK_LAUNCHER}
 * activity. {@link #drawableFor} therefore tries the exact TV component,
 * then an unambiguous per-package mapping, and only then the phone launcher
 * component. Unsupported tags (masks, calendars, scale) are ignored.
 */
final class IconPackMap {

    /** Minimal pull-parser surface so parsing is unit-testable on the JVM. */
    interface TagSource {
        int START_TAG = 2;
        int END_DOCUMENT = 1;

        /** Advance to the next event and return its type. */
        int next() throws Exception;

        /** Current tag name. */
        String name();

        /** Attribute value on the current start tag, or {@code null}. */
        String attribute(String name);
    }

    /** Resolves the phone launcher activity class, called only when needed. */
    interface PhoneActivityLookup {
        String activityClass(String packageName);
    }

    /** Guards against hostile or corrupt packs holding unbounded memory. */
    static final int MAX_ITEMS = 50_000;
    private static final int MAX_EVENTS = 500_000;
    private static final int MAX_VALUE_LENGTH = 512;
    private static final String AMBIGUOUS = "\u0000";

    private final Map<String, String> byComponent = new HashMap<>();
    private final Map<String, String> byPackage = new HashMap<>();

    /** Number of distinct component mappings. */
    int size() { return byComponent.size(); }

    /** Parse the pack's appfilter events; malformed items are skipped. */
    static IconPackMap parse(TagSource source) throws Exception {
        IconPackMap map = new IconPackMap();
        int events = 0;
        for (int type = source.next(); type != TagSource.END_DOCUMENT; type = source.next()) {
            if (++events > MAX_EVENTS || map.size() >= MAX_ITEMS) break;
            if (type != TagSource.START_TAG || !"item".equals(source.name())) continue;
            map.add(source.attribute("component"), source.attribute("drawable"));
        }
        return map;
    }

    /** Add one appfilter item. Returns {@code false} when it is invalid. */
    boolean add(String rawComponent, String drawable) {
        String key = componentKey(rawComponent);
        if (key == null || !isDrawableName(drawable)) return false;
        if (byComponent.containsKey(key)) return false;   // first mapping wins
        byComponent.put(key, drawable);
        String pkg = key.substring(0, key.indexOf('/'));
        String previous = byPackage.get(pkg);
        if (previous == null) byPackage.put(pkg, drawable);
        else if (!previous.equals(drawable)) byPackage.put(pkg, AMBIGUOUS);
        return true;
    }

    /**
     * Drawable name for an app, or {@code null} when the pack has no safe
     * match. {@code activityClass} is the component BareLauncher launches.
     */
    String drawableFor(String packageName, String activityClass, PhoneActivityLookup phone) {
        if (packageName == null || packageName.isEmpty()) return null;
        if (activityClass != null) {
            String exact = byComponent.get(packageName + "/" + expandClass(packageName, activityClass));
            if (exact != null) return exact;
        }
        String packageDrawable = byPackage.get(packageName);
        if (packageDrawable == null) return null;
        if (!AMBIGUOUS.equals(packageDrawable)) return packageDrawable;
        // Several different icons for one package: only an exact phone
        // component match is trustworthy.
        String phoneClass = phone != null ? phone.activityClass(packageName) : null;
        if (phoneClass == null) return null;
        return byComponent.get(packageName + "/" + expandClass(packageName, phoneClass));
    }

    /**
     * Normalize {@code ComponentInfo{pkg/cls}} (or a bare {@code pkg/cls}) to
     * {@code pkg/fully.qualified.Class}. Returns {@code null} when malformed.
     */
    static String componentKey(String raw) {
        if (raw == null || raw.length() > MAX_VALUE_LENGTH) return null;
        String s = raw.trim();
        if (s.startsWith("ComponentInfo{")) {
            if (!s.endsWith("}")) return null;
            s = s.substring("ComponentInfo{".length(), s.length() - 1).trim();
        }
        int slash = s.indexOf('/');
        if (slash <= 0 || slash != s.lastIndexOf('/') || slash == s.length() - 1) return null;
        String pkg = s.substring(0, slash).trim();
        String cls = s.substring(slash + 1).trim();
        if (!isJavaName(pkg)) return null;
        String full = expandClass(pkg, cls);
        if (!isJavaName(full)) return null;
        return pkg + "/" + full;
    }

    private static String expandClass(String pkg, String cls) {
        return cls.startsWith(".") ? pkg + cls : cls;
    }

    /** Plausible Android package name (dot-separated Java identifiers). */
    static boolean isPackageName(String name) {
        return name != null && name.length() <= MAX_VALUE_LENGTH
                && name.indexOf('$') < 0 && isJavaName(name);
    }

    /**
     * Filename-safe disk-cache namespace for one installed pack version.
     * Contains only {@code [a-z0-9]} so it can be embedded in cache file
     * names that use {@code -} as a field separator.
     */
    static String cacheVariant(String packageName, long versionCode, long lastUpdateTime) {
        String identity = packageName + "@" + versionCode + "@" + lastUpdateTime;
        long hash = 0xcbf29ce484222325L;          // 64-bit FNV-1a
        for (int i = 0; i < identity.length(); i++) {
            hash ^= identity.charAt(i);
            hash *= 0x100000001b3L;
        }
        return "p" + Long.toHexString(hash);
    }

    /** Android resource entry names: letters, digits and underscores. */
    static boolean isDrawableName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_VALUE_LENGTH) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    /** Dot-separated Java identifiers, allowing {@code $} for nested classes. */
    private static boolean isJavaName(String name) {
        if (name == null || name.isEmpty()) return false;
        boolean segmentStart = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '.') {
                if (segmentStart) return false;
                segmentStart = true;
                continue;
            }
            boolean ok = segmentStart
                    ? Character.isJavaIdentifierStart(c)
                    : Character.isJavaIdentifierPart(c);
            if (!ok) return false;
            segmentStart = false;
        }
        return !segmentStart;
    }
}
