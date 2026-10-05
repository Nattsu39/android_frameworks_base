/* SPDX-License-Identifier: Apache-2.0
 * Copyright (C) 2026 The Android Open Source Project
 */
package com.android.server.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class DcDimmingCurveTest {
    static final float[] BRIGHTNESS = {0f, 0.0824f, 0.1608f, 0.2392f, 0.33f};
    static final float[] SCALE = {0.2478f, 0.35f, 0.5255f, 0.7426f, 1f};
    private final DcDimmingCurve mCurve = new DcDimmingCurve(BRIGHTNESS, SCALE);

    @Test
    public void calibrationPointsMatchStock() {
        for (int i = 0; i < BRIGHTNESS.length; i++) {
            DcDimmingCurve.Result result = mCurve.transform(true, 1f,
                    BRIGHTNESS[i], BRIGHTNESS[i]);
            assertEquals(0.33f, result.brightness, 0f);
            assertEquals(SCALE[i], result.scale, 0f);
        }
    }

    @Test
    public void changingRequestAtSameFloorChangesMatrix() {
        DcDimmingCurve.Result low = mCurve.transform(true, 1f, 0.1f, 0.1f);
        DcDimmingCurve.Result high = mCurve.transform(true, 1f, 0.2f, 0.2f);
        assertEquals(low.brightness, high.brightness, 0f);
        assertTrue(high.scale > low.scale);
    }

    @Test
    public void hdrAboveFloorStillRaisesSdrWithIdentityMatrix() {
        DcDimmingCurve.Result result = mCurve.transform(true, 1f, 0.4f, 0.1f);
        assertEquals(0.4f, result.brightness, 0f);
        assertEquals(0.33f, result.sdrBrightness, 0f);
        assertEquals(1f, result.scale, 0f);
    }

    @Test
    public void hdrBelowFloorUsesMainForScale() {
        DcDimmingCurve.Result result = mCurve.transform(true, 1f, 0.2392f, 0.0824f);
        assertEquals(0.33f, result.brightness, 0f);
        assertEquals(0.33f, result.sdrBrightness, 0f);
        assertEquals(0.7426f, result.scale, 0f);
    }

    @Test
    public void brightnessProtectionBelowFloorBypassesDimming() {
        assertUnchanged(mCurve.transform(true, 0.2f, 0.1f, 0.1f), 0.1f, 0.1f);
        assertEquals(0.33f, mCurve.transform(true, 0.33f, 0.1f, 0.1f).brightness, 0f);
    }

    @Test
    public void invalidAndOffValuesArePreserved() {
        for (float value : new float[] {-1f, -0.1f, Float.NaN, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, 1.1f}) {
            assertUnchanged(mCurve.transform(true, 1f, value, 0.1f), value, 0.1f);
            assertUnchanged(mCurve.transform(true, 1f, 0.1f, value), 0.1f, value);
        }
        assertUnchanged(mCurve.transform(true, Float.NaN, 0.1f, 0.1f), 0.1f, 0.1f);
    }

    @Test
    public void disabledAndAboveFloorRequestsArePreserved() {
        assertUnchanged(mCurve.transform(false, 1f, 0.1f, 0.1f), 0.1f, 0.1f);
        assertUnchanged(mCurve.transform(true, 1f, 0.5f, 0.4f), 0.5f, 0.4f);
    }

    @Test
    public void invalidPanelCalibrationIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new DcDimmingCurve(new float[] {0f}, new float[] {1f}));
        assertThrows(IllegalArgumentException.class,
                () -> new DcDimmingCurve(new float[] {0f, 0.1f, 0.1f},
                        new float[] {0.2f, 0.5f, 1f}));
        assertThrows(IllegalArgumentException.class,
                () -> new DcDimmingCurve(new float[] {0f, 0.33f}, new float[] {1f, 0.5f}));
        assertThrows(IllegalArgumentException.class,
                () -> new DcDimmingCurve(new float[] {0f, Float.NaN}, new float[] {0.2f, 1f}));
    }

    private static void assertUnchanged(DcDimmingCurve.Result result, float main, float sdr) {
        assertEquals(Float.floatToIntBits(main), Float.floatToIntBits(result.brightness));
        assertEquals(Float.floatToIntBits(sdr), Float.floatToIntBits(result.sdrBrightness));
        assertEquals(1f, result.scale, 0f);
    }
}
