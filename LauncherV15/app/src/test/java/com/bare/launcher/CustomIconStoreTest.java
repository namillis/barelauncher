package com.bare.launcher;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** JVM tests for the Android-free decisions inside {@link CustomIconStore}. */
public class CustomIconStoreTest {

    @Test public void computeSampleSize_imageWithinBound_returnsOne() {
        assertEquals(1, CustomIconStore.computeSampleSize(512, 512, 512));
        assertEquals(1, CustomIconStore.computeSampleSize(320, 480, 512));
    }

    @Test public void computeSampleSize_largeSquare_stopsAtTargetResolution() {
        assertEquals(8, CustomIconStore.computeSampleSize(4096, 4096, 512));
    }

    @Test public void computeSampleSize_panorama_boundsLongestAxis() {
        assertEquals(8, CustomIconStore.computeSampleSize(8000, 500, 512));
        assertEquals(8, CustomIconStore.computeSampleSize(500, 8000, 512));
    }

    @Test public void computeSampleSize_invalidDimensions_returnsOne() {
        assertEquals(1, CustomIconStore.computeSampleSize(0, 500, 512));
        assertEquals(1, CustomIconStore.computeSampleSize(500, 0, 512));
        assertEquals(1, CustomIconStore.computeSampleSize(500, 500, 0));
    }

    @Test public void computeSampleSize_pathologicalInput_terminatesAtCap() {
        assertEquals(0x4000,
                CustomIconStore.computeSampleSize(Integer.MAX_VALUE, Integer.MAX_VALUE, 1));
    }

    @Test public void centerCropBounds_landscape_cropsSides() {
        assertArrayEquals(new int[] {350, 0, 1250, 900},
                CustomIconStore.centerCropBounds(1600, 900));
    }

    @Test public void centerCropBounds_portrait_cropsTopAndBottom() {
        assertArrayEquals(new int[] {0, 350, 900, 1250},
                CustomIconStore.centerCropBounds(900, 1600));
    }

    @Test public void centerCropBounds_square_preservesWholeImage() {
        assertArrayEquals(new int[] {0, 0, 512, 512},
                CustomIconStore.centerCropBounds(512, 512));
    }

    @Test public void centerCropBounds_invalidDimensions_returnsNull() {
        assertNull(CustomIconStore.centerCropBounds(0, 512));
        assertNull(CustomIconStore.centerCropBounds(512, 0));
    }

    @Test public void isSafePackageName_acceptsAndroidIdentifiers() {
        assertTrue(CustomIconStore.isSafePackageName("com.example.tv_launcher2"));
        assertTrue(CustomIconStore.isSafePackageName("org.videolan.vlc"));
    }

    @Test public void isSafePackageName_rejectsMissingAndSyntheticKeys() {
        assertFalse(CustomIconStore.isSafePackageName(null));
        assertFalse(CustomIconStore.isSafePackageName(""));
        assertFalse(CustomIconStore.isSafePackageName("tvinput://hdmi1"));
    }

    @Test public void isSafePackageName_rejectsPathTraversal() {
        assertFalse(CustomIconStore.isSafePackageName("../outside"));
        assertFalse(CustomIconStore.isSafePackageName("com.example/app"));
        assertFalse(CustomIconStore.isSafePackageName("com.example\\app"));
    }
}
