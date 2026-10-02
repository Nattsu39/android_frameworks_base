// SPDX-License-Identifier: Apache-2.0

package com.android.settingslib.display;

/** Tests the production conversion interface, not a separate copy of the curve algorithm. */
public final class BrightnessCurveTest {
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void near(float actual, float expected, float tolerance, String message) {
        require(Math.abs(actual - expected) <= tolerance,
                message + ": expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) {
        String mode = args[0];
        float midpoint = BrightnessUtils.convertGammaToLinearFloat(32768, 0, 0.8584f, 0);
        float expected = switch (mode) {
            case "linear" -> 0.4292f;
            case "nits" -> 0.27948066f;
            case "hlg" -> 0.07153333f;
            default -> throw new IllegalArgumentException(mode);
        };
        near(midpoint, expected, 0.00003f, "50% landmark");
        near(BrightnessUtils.convertGammaToLinearFloat(32768, 0, 0.8584f), midpoint,
                0, "Default-display overload");

        float[][] ranges = {{0, 0.8584f}, {0, 1}, {0.05f, 0.8584f}, {0.2f, 0.8f}};
        for (float[] range : ranges) {
            float minimum = range[0];
            float maximum = range[1];
            float previous = minimum;
            int maximumGammaError = 0;
            for (int slider = 0; slider <= 65535; slider++) {
                float value = BrightnessUtils.convertGammaToLinearFloat(slider, minimum, maximum, 0);
                require(Float.isFinite(value), "Finite output at " + slider);
                require(value >= minimum - 1e-6f && value <= maximum + 1e-6f, "Dynamic bounds");
                require(value >= previous - 1e-7f, "Monotonicity at " + slider);
                int inverse = BrightnessUtils.convertLinearToGammaFloat(value, minimum, maximum, 0);
                require(inverse >= 0 && inverse <= 65535, "Inverse bounds");
                float restored = BrightnessUtils.convertGammaToLinearFloat(inverse, minimum, maximum, 0);
                near(restored, value, 0.00005f, "Brightness round trip at " + slider);
                maximumGammaError = Math.max(maximumGammaError, Math.abs(inverse - slider));
                previous = value;
            }
            near(BrightnessUtils.convertGammaToLinearFloat(0, minimum, maximum, 0), minimum,
                    1e-6f, "Minimum endpoint");
            near(previous, maximum, 1e-6f, "Maximum endpoint");
            if (minimum == 0) {
                require(maximumGammaError <= 4, "Gamma round-trip error: " + maximumGammaError);
            }
            System.out.printf("%s range %.4f..%.4f: 65536 samples, max gamma error %d%n",
                    mode, minimum, maximum, maximumGammaError);
        }

        float fallback = mode.equals("linear") ? 0.4292f : 0.07153333f;
        for (int displayId : new int[]{-1, 1, 999}) {
            near(BrightnessUtils.convertGammaToLinearFloat(32768, 0, 0.8584f, displayId),
                    fallback, 0.00003f, "Uncalibrated display fallback");
        }
        int legacy = BrightnessUtils.convertGammaToLinear(32768, 1, 255);
        require(legacy == (mode.equals("linear") ? 128 : 22), "Legacy integer conversion");
        require(BrightnessUtils.convertLinearToGamma(legacy, 1, 255)
                == BrightnessUtils.convertLinearToGammaFloat(legacy, 1, 255, -1),
                "Legacy integer inverse must bypass panel calibration");
        if (mode.equals("nits")) {
            require(BrightnessUtils.convertLinearToGammaFloat(-1, 0, 0.8584f, 0) == 0,
                    "Clamp below calibrated range");
            require(BrightnessUtils.convertLinearToGammaFloat(1, 0, 0.8584f, 0) == 65535,
                    "Clamp above calibrated range");
        }
        require(Float.isNaN(NitsBrightnessCurve.toNits(Float.NaN)), "NaN calibration input");
        System.out.println("PASS: " + mode);
    }
}
