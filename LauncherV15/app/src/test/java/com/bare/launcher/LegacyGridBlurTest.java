package com.bare.launcher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LegacyGridBlurTest {

    @Test
    public void blurArgbSpreadsBrightPixelAcrossNeighbours() {
        int[] pixels = {0xff000000, 0xffffffff, 0xff000000};

        LegacyGridBlur.blurArgb(pixels, 3, 1, 1);

        assertEquals(0xff7f7f7f, pixels[0]);
        assertEquals(0xff555555, pixels[1]);
        assertEquals(0xff7f7f7f, pixels[2]);
    }

    @Test
    public void blurArgbLeavesInputUnchangedForZeroRadius() {
        int[] pixels = {0xff123456};

        LegacyGridBlur.blurArgb(pixels, 1, 1, 0);

        assertEquals(0xff123456, pixels[0]);
    }
}
