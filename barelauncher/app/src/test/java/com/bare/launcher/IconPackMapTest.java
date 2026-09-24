package com.bare.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class IconPackMapTest {

    /** List-backed stand-in for XmlPullParser. */
    private static final class FakeTags implements IconPackMap.TagSource {
        private final List<String> names = new ArrayList<>();
        private final List<Map<String, String>> attrs = new ArrayList<>();
        private int index = -1;

        FakeTags tag(String name, String... kv) {
            Map<String, String> m = new HashMap<>();
            for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
            names.add(name);
            attrs.add(m);
            return this;
        }

        FakeTags item(String component, String drawable) {
            return tag("item", "component", component, "drawable", drawable);
        }

        @Override public int next() {
            index++;
            return index < names.size() ? START_TAG : END_DOCUMENT;
        }

        @Override public String name() { return names.get(index); }

        @Override public String attribute(String name) { return attrs.get(index).get(name); }
    }

    @Test public void componentKey_parsesComponentInfoWrapper() {
        assertEquals("com.app/com.app.Main",
                IconPackMap.componentKey("ComponentInfo{com.app/com.app.Main}"));
    }

    @Test public void componentKey_expandsRelativeClassAndTrims() {
        assertEquals("com.app/com.app.ui.Main",
                IconPackMap.componentKey("  ComponentInfo{ com.app/.ui.Main }  "));
    }

    @Test public void componentKey_rejectsMalformedValues() {
        assertNull(IconPackMap.componentKey(null));
        assertNull(IconPackMap.componentKey("ComponentInfo{com.app}"));
        assertNull(IconPackMap.componentKey("ComponentInfo{com.app/}"));
        assertNull(IconPackMap.componentKey("ComponentInfo{/com.app.Main}"));
        assertNull(IconPackMap.componentKey("ComponentInfo{com.app/com.app.Main"));
        assertNull(IconPackMap.componentKey("ComponentInfo{com.app/a/b}"));
        assertNull(IconPackMap.componentKey("ComponentInfo{com..app/com.app.Main}"));
        assertNull(IconPackMap.componentKey("ComponentInfo{com.app/com app.Main}"));
    }

    @Test public void drawableName_acceptsOnlyResourceEntryNames() {
        assertTrue(IconPackMap.isDrawableName("netflix_2"));
        assertFalse(IconPackMap.isDrawableName(""));
        assertFalse(IconPackMap.isDrawableName("../x"));
        assertFalse(IconPackMap.isDrawableName("a.b"));
        assertFalse(IconPackMap.isDrawableName(null));
    }

    @Test public void parse_readsItemsAndIgnoresOtherTagsAndBadItems() throws Exception {
        IconPackMap map = IconPackMap.parse(new FakeTags()
                .tag("resources")
                .tag("iconback", "img1", "back")
                .item("ComponentInfo{com.a/com.a.Main}", "a")
                .item("ComponentInfo{broken}", "b")
                .item("ComponentInfo{com.c/com.c.Main}", "bad name")
                .tag("calendar", "component", "ComponentInfo{com.d/com.d.Main}", "prefix", "cal_")
                .item("ComponentInfo{com.e/com.e.Main}", "e"));

        assertEquals(2, map.size());
        assertEquals("a", map.drawableFor("com.a", "com.a.Main", null));
        assertEquals("e", map.drawableFor("com.e", "com.e.Main", null));
        assertNull(map.drawableFor("com.d", "com.d.Main", null));
    }

    @Test public void drawableFor_exactTvComponentWins() {
        IconPackMap map = new IconPackMap();
        map.add("ComponentInfo{com.app/com.app.Phone}", "phone");
        map.add("ComponentInfo{com.app/com.app.Tv}", "tv");

        assertEquals("tv", map.drawableFor("com.app", "com.app.Tv", pkg -> "com.app.Phone"));
    }

    @Test public void drawableFor_unambiguousPackageFallsBackWithoutLookup() {
        IconPackMap map = new IconPackMap();
        map.add("ComponentInfo{com.app/com.app.Phone}", "app");
        map.add("ComponentInfo{com.app/com.app.Alias}", "app");
        AtomicInteger lookups = new AtomicInteger();

        String name = map.drawableFor("com.app", "com.app.tv.LeanbackActivity", pkg -> {
            lookups.incrementAndGet();
            return null;
        });

        assertEquals("app", name);
        assertEquals(0, lookups.get());
    }

    @Test public void drawableFor_ambiguousPackageUsesPhoneLauncherComponent() {
        IconPackMap map = new IconPackMap();
        map.add("ComponentInfo{com.app/com.app.Main}", "main");
        map.add("ComponentInfo{com.app/com.app.Settings}", "settings");

        assertEquals("main", map.drawableFor("com.app", "com.app.tv.Leanback", pkg -> ".Main"));
        assertNull(map.drawableFor("com.app", "com.app.tv.Leanback", pkg -> null));
        assertNull(map.drawableFor("com.app", "com.app.tv.Leanback", null));
    }

    @Test public void drawableFor_unmappedPackageReturnsNull() {
        IconPackMap map = new IconPackMap();
        map.add("ComponentInfo{com.app/com.app.Main}", "main");

        assertNull(map.drawableFor("com.other", "com.other.Main", pkg -> "com.other.Main"));
        assertNull(map.drawableFor(null, null, null));
    }

    @Test public void add_firstMappingForAComponentWins() {
        IconPackMap map = new IconPackMap();
        assertTrue(map.add("ComponentInfo{com.app/com.app.Main}", "first"));
        assertFalse(map.add("ComponentInfo{com.app/com.app.Main}", "second"));

        assertEquals("first", map.drawableFor("com.app", "com.app.Main", null));
    }

    @Test public void parse_stopsAtItemCap() throws Exception {
        FakeTags tags = new FakeTags();
        for (int i = 0; i < IconPackMap.MAX_ITEMS + 10; i++) {
            tags.item("ComponentInfo{com.p" + i + "/com.p" + i + ".Main}", "d" + i);
        }

        assertEquals(IconPackMap.MAX_ITEMS, IconPackMap.parse(tags).size());
    }

    @Test public void packageName_validation() {
        assertTrue(IconPackMap.isPackageName("com.example.pack"));
        assertFalse(IconPackMap.isPackageName("../evil"));
        assertFalse(IconPackMap.isPackageName("com.example-pack"));
        assertFalse(IconPackMap.isPackageName(""));
        assertFalse(IconPackMap.isPackageName(null));
    }

    @Test public void cacheVariant_isFilenameSafeAndVersionSensitive() {
        String v1 = IconPackMap.cacheVariant("com.pack", 10, 1000);
        String v2 = IconPackMap.cacheVariant("com.pack", 11, 1000);
        String v3 = IconPackMap.cacheVariant("com.pack", 10, 2000);

        assertTrue(v1.matches("[a-z0-9]+"));
        assertEquals(v1, IconPackMap.cacheVariant("com.pack", 10, 1000));
        assertNotEquals(v1, v2);
        assertNotEquals(v1, v3);
        assertTrue(IconDiskCache.isVariant(v1));
    }

    @Test public void diskCache_variantOfDistinguishesDefaultAndPackEntries() {
        assertNull(IconDiskCache.variantOf("com.app-192.icn"));
        assertEquals("pabc123", IconDiskCache.variantOf("com.app-192-pabc123.icn"));
        assertEquals("pabc123", IconDiskCache.variantOf("com.app-192-pabc123.icn.tmp"));
        assertNull(IconDiskCache.variantOf("com.app-192-BAD.icn"));
        assertNull(IconDiskCache.variantOf("com.app.cache"));
    }
}
