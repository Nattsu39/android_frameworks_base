/*
 * Copyright (C) 2026 The Avium Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.display;

import java.util.Arrays;

/** Pixel geometry and the manufacturer's 25-point, independently sorted RGB estimator. */
final class AlsContentSample {
    static final int SIZE = 40;
    static final int LEFT = 555;
    static final int TOP = 172;
    static final int RIGHT = 595;
    static final int BOTTOM = 212;

    private AlsContentSample() {}

    /** Natural panel coordinates mapped into SurfaceFlinger's logical layer-stack coordinates. */
    static int[] crop(int naturalWidth, int naturalHeight, int rotation) {
        switch (rotation) {
            case 0:
                return new int[] {LEFT, TOP, RIGHT, BOTTOM};
            case 1:
                return new int[] {TOP, naturalWidth - RIGHT, BOTTOM, naturalWidth - LEFT};
            case 2:
                return new int[] {naturalWidth - RIGHT, naturalHeight - BOTTOM,
                        naturalWidth - LEFT, naturalHeight - TOP};
            case 3:
                return new int[] {naturalHeight - BOTTOM, LEFT, naturalHeight - TOP, RIGHT};
            default:
                throw new IllegalArgumentException("Invalid display rotation: " + rotation);
        }
    }

    static int mode(int colorGamut, int acl) {
        int base;
        switch (colorGamut) {
            case 0: base = 70; break;
            case 1: base = 10; break;
            case 3: base = 30; break;
            default: base = 20; break;
        }
        return base + (acl >= 1 && acl <= 3 ? acl : 0);
    }

    static int[] rgb(int[] pixels, int rotation) {
        if (pixels.length != SIZE * SIZE || rotation < 0 || rotation > 3) {
            throw new IllegalArgumentException("Expected a 40x40 capture and valid rotation");
        }
        float[][] channels = new float[3][25];
        int sample = 0;
        for (int x = 0; x < SIZE; x += 13) {
            for (int y = 0; y < SIZE; y += 13) {
                addPixel(channels, sample++, pixel(pixels, x, y, rotation));
            }
        }
        for (int x = 6; x < SIZE - 7; x += 13) {
            for (int y = 6; y < SIZE - 7; y += 13) {
                addPixel(channels, sample++, pixel(pixels, x, y, rotation));
            }
        }
        int[] result = new int[3];
        for (int channel = 0; channel < 3; channel++) {
            Arrays.sort(channels[channel]);
            float sum = 0.0f;
            for (int i = 0; i < 25; i++) {
                float weight = i < 1 ? 0.15f : i < 5 ? 0.0f : i < 9 ? 0.1f
                        : i < 13 ? 0.05f : i < 17 ? 0.35f : i < 21 ? 0.15f : 0.2f;
                // Preserve the stock float operation order; do not collapse to an average.
                float value = channels[channel][i] * weight * 25.0f;
                if (i != 0) value /= 4.0f;
                sum += value;
            }
            result[channel] = Math.round(sum / 25.0f);
        }
        return result;
    }

    private static int pixel(int[] pixels, int x, int y, int rotation) {
        int rx = x;
        int ry = y;
        switch (rotation) {
            case 1: rx = y; ry = SIZE - 1 - x; break;
            case 2: rx = SIZE - 1 - x; ry = SIZE - 1 - y; break;
            case 3: rx = SIZE - 1 - y; ry = x; break;
        }
        return pixels[ry * SIZE + rx];
    }

    private static void addPixel(float[][] channels, int sample, int pixel) {
        channels[0][sample] = (pixel >> 16) & 255;
        channels[1][sample] = (pixel >> 8) & 255;
        channels[2][sample] = pixel & 255;
    }
}
