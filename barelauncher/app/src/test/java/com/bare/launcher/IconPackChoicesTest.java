package com.bare.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class IconPackChoicesTest {

    @Test public void choicesAreScopedPerPack() {
        IconPackChoices c = new IconPackChoices();
        assertTrue(c.put("com.pack.a", "com.app", "app_alt"));

        assertEquals("app_alt", c.get("com.pack.a", "com.app"));
        assertNull(c.get("com.pack.b", "com.app"));
    }

    @Test public void serializeAndParse_roundTrip() {
        IconPackChoices c = new IconPackChoices();
        c.put("com.pack.b", "com.app", "b_icon");
        c.put("com.pack.a", "com.app", "a_icon");
        c.put("com.pack.a", "com.other", "other");

        String raw = c.serialize();
        IconPackChoices back = IconPackChoices.parse(raw);

        assertEquals("com.pack.a/com.app=a_icon,com.pack.a/com.other=other,"
                + "com.pack.b/com.app=b_icon", raw);
        assertEquals(3, back.size());
        assertEquals("b_icon", back.get("com.pack.b", "com.app"));
    }

    @Test public void parse_skipsMalformedEntries() {
        IconPackChoices c = IconPackChoices.parse(
                "com.pack/com.app=ok,garbage,=x,com.pack/=x,com.pack/com.b=bad name,"
                        + "com..pack/com.c=x,com.pack/com.d=");

        assertEquals(1, c.size());
        assertEquals("ok", c.get("com.pack", "com.app"));
    }

    @Test public void put_rejectsInvalidNames() {
        IconPackChoices c = new IconPackChoices();
        assertFalse(c.put("com.pack", "com.app", "../x"));
        assertFalse(c.put("com.pack", "tvinput://oem/HW5", "x"));
        assertFalse(c.put(null, "com.app", "x"));
        assertEquals(0, c.size());
    }

    @Test public void removeAppAndRemovePack() {
        IconPackChoices c = new IconPackChoices();
        c.put("com.pack.a", "com.app", "x");
        c.put("com.pack.b", "com.app", "y");
        c.put("com.pack.a", "com.other", "z");

        assertTrue(c.removeApp("com.app"));
        assertEquals(1, c.size());
        assertTrue(c.removePack("com.pack.a"));
        assertEquals(0, c.size());
        assertFalse(c.remove("com.pack.a", "com.other"));
    }

    @Test public void removeApp_doesNotMatchPackageSuffix() {
        IconPackChoices c = new IconPackChoices();
        c.put("com.pack", "org.myapp", "x");

        assertFalse(c.removeApp("app"));
        assertEquals(1, c.size());
    }

    @Test public void catalog_rankingPrefersMoreWordsThenExactThenShorter() {
        IconPackCatalog cat = new IconPackCatalog(Arrays.asList(
                "youtube_music", "youtube", "youtube_kids", "music", "google_play_movies",
                "movies_anywhere", "play_store"));

        assertEquals("youtube", cat.search("YouTube", 10).get(0));
        List<String> movies = cat.search("Play Movies & TV", 10);
        assertEquals("google_play_movies", movies.get(0));
        assertTrue(movies.contains("movies_anywhere"));
        assertTrue(movies.contains("play_store"));
        assertFalse(movies.contains("music"));
    }

    @Test public void catalog_blankQueryListsPackOrderAndRespectsLimit() {
        IconPackCatalog cat = new IconPackCatalog(Arrays.asList("c", "a", "b"));

        assertEquals(Arrays.asList("c", "a"), cat.search("  ", 2));
        assertEquals(0, cat.search("zzz", 10).size());
    }

    @Test public void catalog_dropsInvalidAndDuplicateNames() {
        IconPackCatalog cat = new IconPackCatalog(Arrays.asList("ok", "ok", "bad name", "", null));

        assertEquals(1, cat.size());
    }

    @Test public void catalog_parsesDrawableXmlItems() throws Exception {
        List<String> names = IconPackCatalog.parseDrawableXml(new IconPackMap.TagSource() {
            private final String[][] tags = {
                    {"resources", null}, {"category", null}, {"item", "netflix"},
                    {"item", "netflix_alt"}, {"version", null}};
            private int i = -1;
            @Override public int next() { return ++i < tags.length ? START_TAG : END_DOCUMENT; }
            @Override public String name() { return tags[i][0]; }
            @Override public String attribute(String name) {
                return "drawable".equals(name) ? tags[i][1] : null;
            }
        });

        assertEquals(Arrays.asList("netflix", "netflix_alt"), names);
    }

    @Test public void words_dropsSingleLettersUnlessAlone() {
        assertEquals(Arrays.asList("play", "movies", "tv"),
                IconPackCatalog.words("Play Movies & TV"));
        assertEquals(Arrays.asList("x"), IconPackCatalog.words("X"));
        assertEquals(Arrays.asList("plex"), IconPackCatalog.words("Plex A"));
    }
}
