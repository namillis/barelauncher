package com.bare.launcher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NavigationMotionTest {

    @Test
    public void surfaceOffsetAlignsTargetWithSource() {
        assertEquals(320f, NavigationMotion.surfaceOffsetY(900f, 580f, 1080), 0.001f);
        assertEquals(-240f, NavigationMotion.surfaceOffsetY(340f, 580f, 1080), 0.001f);
    }

    @Test
    public void surfaceOffsetIsBoundedAndRejectsInvalidGeometry() {
        assertEquals(550f, NavigationMotion.surfaceOffsetY(900f, 0f, 1000), 0.001f);
        assertEquals(-550f, NavigationMotion.surfaceOffsetY(0f, 900f, 1000), 0.001f);
        assertEquals(0f, NavigationMotion.surfaceOffsetY(Float.NaN, 400f, 1000), 0.001f);
        assertEquals(0f, NavigationMotion.surfaceOffsetY(700f, 400f, 0), 0.001f);
    }

    @Test
    public void horizontalFocusStartsTowardPreviousCell() {
        assertEquals(-10f, NavigationMotion.focusOffsetX(6, 6, 7, 6, 10f), 0.001f);
        assertEquals(10f, NavigationMotion.focusOffsetX(6, 7, 6, 6, 10f), 0.001f);
        assertEquals(0f, NavigationMotion.focusOffsetY(6, 6, 7, 6, 10f), 0.001f);
    }

    @Test
    public void verticalFocusStartsTowardPreviousRow() {
        assertEquals(-10f, NavigationMotion.focusOffsetY(6, 6, 12, 6, 10f), 0.001f);
        assertEquals(10f, NavigationMotion.focusOffsetY(6, 12, 6, 6, 10f), 0.001f);
        assertEquals(0f, NavigationMotion.focusOffsetX(6, 6, 12, 6, 10f), 0.001f);
    }

    @Test
    public void sameCellAndHeldNavigationHaveNoLeadIn() {
        assertEquals(0f, NavigationMotion.focusOffsetX(5, 7, 7, 5, 10f), 0.001f);
        assertEquals(0f, NavigationMotion.focusOffsetY(5, 7, 7, 5, 10f), 0.001f);
        assertEquals(0f, NavigationMotion.focusOffsetX(5, 7, 8, 5, 0f), 0.001f);
        assertEquals(0f, NavigationMotion.focusOffsetY(5, 7, 12, 5, 0f), 0.001f);
    }

    @Test
    public void motionDurationsStayBelowPerceivedLagThreshold() {
        assertEquals(220, NavigationMotion.SURFACE_DURATION_MS);
        assertEquals(110, NavigationMotion.GRID_FOCUS_DURATION_MS);
        assertEquals(10, NavigationMotion.GRID_FOCUS_GLIDE_DP);
    }
}
