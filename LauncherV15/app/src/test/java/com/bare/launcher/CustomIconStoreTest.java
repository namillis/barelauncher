package com.bare.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
