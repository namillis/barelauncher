package com.bare.launcher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LayoutOptionsTest {

    @Test public void defaultsPreserveCurrentLayout() {
        assertEquals(6, LayoutOptions.DEFAULT_COLUMNS);
        assertEquals(20, LayoutOptions.DEFAULT_CORNER_PERCENT);
    }

    @Test public void columnsClampAndStepAcrossFourToSeven() {
        assertEquals(4, LayoutOptions.sanitizeColumns(2));
        assertEquals(7, LayoutOptions.sanitizeColumns(9));
        assertEquals(5, LayoutOptions.stepColumns(4, 1));
        assertEquals(6, LayoutOptions.stepColumns(7, -1));
        assertEquals(4, LayoutOptions.stepColumns(4, -1));
        assertEquals(7, LayoutOptions.stepColumns(7, 1));
    }

    @Test public void cornerPercentClampsToEvenStepsAcrossZeroToThirty() {
        assertEquals(0, LayoutOptions.sanitizeCornerPercent(-1));
        assertEquals(16, LayoutOptions.sanitizeCornerPercent(15));
        assertEquals(30, LayoutOptions.sanitizeCornerPercent(31));
        assertEquals(18, LayoutOptions.stepCornerPercent(16, 1));
        assertEquals(14, LayoutOptions.stepCornerPercent(16, -1));
        assertEquals(0, LayoutOptions.stepCornerPercent(0, -1));
        assertEquals(30, LayoutOptions.stepCornerPercent(30, 1));
    }

    @Test public void focusBorderDefaultsOnWithWhiteHighContrastColor() {
        assertEquals(true, LayoutOptions.DEFAULT_FOCUS_BORDER_ENABLED);
        assertEquals(0xFFFFFFFF, LayoutOptions.DEFAULT_FOCUS_COLOR);
    }

    @Test public void focusColorIsOpaqueAndCyclesInBothDirections() {
        assertEquals(0xFF123456, LayoutOptions.sanitizeFocusColor(0x00123456));
        assertEquals(0xFF00E5FF,
                LayoutOptions.stepFocusColor(LayoutOptions.DEFAULT_FOCUS_COLOR, 1));
        assertEquals(0xFFE040FB,
                LayoutOptions.stepFocusColor(LayoutOptions.DEFAULT_FOCUS_COLOR, -1));
    }
}
