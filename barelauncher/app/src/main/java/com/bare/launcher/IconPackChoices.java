package com.bare.launcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Android-free store of icons the user picked by hand from an icon pack.
 *
 * <p>Each entry references a drawable by name inside one pack, keyed by
 * {@code packPackage/appPackage}. Choices are therefore per pack: switching
 * to another pack ignores them, and switching back restores them. Only the
 * reference is saved, so a pack update shows its newer artwork.
 *
 * <p>Serialized form: {@code pack/app=drawable,pack/app=drawable}. Package
 * and resource names cannot contain {@code / = ,}, so no escaping is needed.
 */
final class IconPackChoices {

    static final int MAX_ENTRIES = 2048;
    private static final int MAX_SERIALIZED_LENGTH = 256 * 1024;

    private final Map<String, String> choices = new LinkedHashMap<>();

    /** Drawable chosen for {@code appPackage} in {@code packPackage}, or {@code null}. */
    synchronized String get(String packPackage, String appPackage) {
        String key = key(packPackage, appPackage);
        return key != null ? choices.get(key) : null;
    }

    /** Record a choice. Returns {@code false} for invalid input or a full store. */
    synchronized boolean put(String packPackage, String appPackage, String drawable) {
        String key = key(packPackage, appPackage);
        if (key == null || !IconPackMap.isDrawableName(drawable)) return false;
        if (!choices.containsKey(key) && choices.size() >= MAX_ENTRIES) return false;
        choices.put(key, drawable);
        return true;
    }

    /** Remove the choice for one app in one pack. Returns {@code true} if one existed. */
    synchronized boolean remove(String packPackage, String appPackage) {
        String key = key(packPackage, appPackage);
        return key != null && choices.remove(key) != null;
    }

    /** Remove an uninstalled app's choices across every pack. */
    synchronized boolean removeApp(String appPackage) {
        if (appPackage == null) return false;
        String suffix = "/" + appPackage;
        return choices.keySet().removeIf(k -> k.endsWith(suffix));
    }

    /** Remove every choice made in an uninstalled pack. */
    synchronized boolean removePack(String packPackage) {
        if (packPackage == null) return false;
        String prefix = packPackage + "/";
        return choices.keySet().removeIf(k -> k.startsWith(prefix));
    }

    synchronized int size() { return choices.size(); }

    /** Canonical, sorted, single-line form for SharedPreferences and backups. */
    synchronized String serialize() {
        List<String> keys = new ArrayList<>(choices.keySet());
        Collections.sort(keys);
        StringBuilder out = new StringBuilder();
        for (String k : keys) {
            String entry = k + "=" + choices.get(k);
            if (out.length() + entry.length() + 1 > MAX_SERIALIZED_LENGTH) break;
            if (out.length() > 0) out.append(',');
            out.append(entry);
        }
        return out.toString();
    }

    /** Parse a serialized store; malformed entries are skipped individually. */
    static IconPackChoices parse(String raw) {
        IconPackChoices out = new IconPackChoices();
        if (raw == null || raw.isEmpty() || raw.length() > MAX_SERIALIZED_LENGTH) return out;
        for (String entry : raw.split(",", -1)) {
            int eq = entry.indexOf('=');
            int slash = entry.indexOf('/');
            if (eq <= 0 || slash <= 0 || slash > eq) continue;
            out.put(entry.substring(0, slash), entry.substring(slash + 1, eq),
                    entry.substring(eq + 1));
        }
        return out;
    }

    private static String key(String packPackage, String appPackage) {
        if (!IconPackMap.isPackageName(packPackage) || !IconPackMap.isPackageName(appPackage)) {
            return null;
        }
        return packPackage + "/" + appPackage;
    }
}
