package com.bare.launcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Android-free list of every icon in a pack, with remote-friendly search.
 *
 * <p>Packs list their artwork in {@code drawable.xml} as
 * {@code <item drawable="name"/>}, often 10,000+ entries. Names are
 * snake_case ({@code google_play_movies}), so search splits the query into
 * words and ranks names containing more of them first, then exact and
 * prefix matches, then shorter names. That makes an app label such as
 * "Play Movies &amp; TV" a useful starting query without any typing.
 */
final class IconPackCatalog {

    static final int MAX_NAMES = 50_000;
    private static final int MAX_EVENTS = 600_000;

    private final List<String> names;
    private final String[] normalized;

    IconPackCatalog(List<String> rawNames) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (rawNames != null) {
            for (String n : rawNames) {
                if (unique.size() >= MAX_NAMES) break;
                if (IconPackMap.isDrawableName(n)) unique.add(n);
            }
        }
        names = Collections.unmodifiableList(new ArrayList<>(unique));
        normalized = new String[names.size()];
        for (int i = 0; i < normalized.length; i++) normalized[i] = compact(names.get(i));
    }

    /** Every icon name in the pack, in the pack's own order. */
    List<String> names() { return names; }

    int size() { return names.size(); }

    /** Collect {@code <item drawable="...">} names from a drawable.xml event stream. */
    static List<String> parseDrawableXml(IconPackMap.TagSource source) throws Exception {
        List<String> out = new ArrayList<>();
        int events = 0;
        for (int type = source.next(); type != IconPackMap.TagSource.END_DOCUMENT;
                type = source.next()) {
            if (++events > MAX_EVENTS || out.size() >= MAX_NAMES) break;
            if (type != IconPackMap.TagSource.START_TAG || !"item".equals(source.name())) continue;
            String name = source.attribute("drawable");
            if (name != null) out.add(name.trim());
        }
        return out;
    }

    /**
     * Up to {@code limit} names matching {@code query}, best first. A blank
     * query returns the first {@code limit} icons in pack order.
     */
    List<String> search(String query, int limit) {
        List<String> words = words(query);
        List<String> out = new ArrayList<>(Math.min(limit, 64));
        if (limit <= 0) return out;
        if (words.isEmpty()) {
            for (int i = 0; i < names.size() && out.size() < limit; i++) out.add(names.get(i));
            return out;
        }
        String joined = String.join("", words);
        List<long[]> hits = new ArrayList<>();
        for (int i = 0; i < normalized.length; i++) {
            String n = normalized[i];
            int matched = 0;
            for (String w : words) if (n.contains(w)) matched++;
            if (matched == 0) continue;
            int kind = n.equals(joined) ? 0 : n.startsWith(joined) ? 1
                    : n.startsWith(words.get(0)) ? 2 : 3;
            // Sort key: more words matched, then match kind, then length, then pack order.
            long key = ((long) (words.size() - matched) << 48)
                    | ((long) kind << 40)
                    | ((long) Math.min(n.length(), 0xFFFF) << 24)
                    | i;
            hits.add(new long[] {key, i});
        }
        hits.sort((a, b) -> Long.compare(a[0], b[0]));
        for (int i = 0; i < hits.size() && out.size() < limit; i++) {
            out.add(names.get((int) hits.get(i)[1]));
        }
        return out;
    }

    /** Lowercase alphanumeric words; one-letter words are dropped unless alone. */
    static List<String> words(String query) {
        List<String> out = new ArrayList<>();
        if (query == null) return out;
        for (String part : query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!part.isEmpty()) out.add(part);
        }
        if (out.size() > 1) out.removeIf(w -> w.length() < 2);
        return out;
    }

    private static String compact(String name) {
        return name.toLowerCase(Locale.ROOT).replace("_", "");
    }
}
