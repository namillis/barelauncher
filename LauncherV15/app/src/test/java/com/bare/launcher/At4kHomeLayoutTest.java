package com.bare.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class At4kHomeLayoutTest {

    @Test
    public void cardsUseThreeByTwoAspectRatio() {
        At4kHomeLayout.Metrics metrics = At4kHomeLayout.calculate(1920, 1080, 1f);

        assertEquals(metrics.tileWidthPx * 2, metrics.tileHeightPx * 3, 2);
    }

    @Test
    public void labelsAppearOnlyBelowFavoritesBoundary() {
        assertFalse(At4kHomeLayout.shouldShowLabel(0, 6));
        assertFalse(At4kHomeLayout.shouldShowLabel(5, 6));
        assertTrue(At4kHomeLayout.shouldShowLabel(6, 6));
        assertTrue(At4kHomeLayout.shouldShowLabel(12, 6));
    }

    @Test
    public void wallpaperBlursOnlyBelowFavoritesBoundary() {
        assertFalse(At4kHomeLayout.shouldBlurBackground(5, 6));
        assertTrue(At4kHomeLayout.shouldBlurBackground(6, 6));
    }
}
