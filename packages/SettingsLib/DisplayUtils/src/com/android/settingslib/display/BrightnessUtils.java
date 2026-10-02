/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settingslib.display;

import android.hardware.display.DisplayManagerGlobal;
import android.os.SystemProperties;
import android.util.MathUtils;
import android.view.Display;
import android.view.DisplayInfo;

/** Utility methods for calculating the display brightness. */
public class BrightnessUtils {

    private static final boolean sHasNitsCalibration =
            "NX809J".equals(SystemProperties.get("ro.product.device"));

    // Enable calibrated brightness by default; the existing switch can select linear mode.
    public static final boolean sDisableGammaConversion =
            SystemProperties.getBoolean("sys.brightness.disable_gamma_conversion", false);

    public static final int GAMMA_SPACE_MIN = 0;
    public static final int GAMMA_SPACE_MAX = 65535;

    // Hybrid Log Gamma constant values
    private static final float R = 0.5f;
    private static final float A = 0.17883277f;
    private static final float B = 0.28466892f;
    private static final float C = 0.55991073f;

    /**
     * A function for converting from the gamma space that the slider works in to the
     * linear space that the setting works in.
     *
     * The gamma space effectively provides us a way to make linear changes to the slider that
     * result in linear changes in perception. If we made changes to the slider in the linear space
     * then we'd see an approximately logarithmic change in perception (c.f. Fechner's Law).
     *
     * Internally, this implements the Hybrid Log Gamma electro-optical transfer function, which is
     * a slight improvement to the typical gamma transfer function for displays whose max
     * brightness exceeds the 120 nit reference point, but doesn't set a specific reference
     * brightness like the PQ function does.
     *
     * Note that this transfer function is only valid if the display's backlight value is a linear
     * control. If it's calibrated to be something non-linear, then a different transfer function
     * should be used.
     *
     * @param val The slider value.
     * @param min The minimum acceptable value for the setting.
     * @param max The maximum acceptable value for the setting.
     * @return The corresponding setting value.
     */
    public static final int convertGammaToLinear(int val, int min, int max) {
        final float normalizedVal = MathUtils.norm(GAMMA_SPACE_MIN, GAMMA_SPACE_MAX, val);

        if (sDisableGammaConversion) {
            return Math.round(MathUtils.lerp(min, max, normalizedVal));
        }

        final float ret;
        if (normalizedVal <= R) {
            ret = MathUtils.sq(normalizedVal / R);
        } else {
            ret = MathUtils.exp((normalizedVal - C) / A) + B;
        }

        // HLG is normalized to the range [0, 12], so we need to re-normalize to the range [0, 1]
        // in order to derive the correct setting value.
        return Math.round(MathUtils.lerp(min, max, ret / 12));
    }

    /**
     * Version of {@link #convertGammaToLinear} that takes and returns float values.
     * TODO(flc): refactor Android Auto to use float version
     *
     * @param val The slider value.
     * @param min The minimum acceptable value for the setting.
     * @param max The maximum acceptable value for the setting.
     * @return The corresponding setting value.
     */
    public static final float convertGammaToLinearFloat(int val, float min, float max) {
        return convertGammaToLinearFloat(val, min, max, Display.DEFAULT_DISPLAY);
    }

    /** Converts a slider value using the calibration of the specified display, when available. */
    public static float convertGammaToLinearFloat(int val, float min, float max, int displayId) {
        final float normalizedVal = MathUtils.norm(GAMMA_SPACE_MIN, GAMMA_SPACE_MAX, val);

        if (sDisableGammaConversion) {
            return MathUtils.lerp(min, max, normalizedVal);
        }

        final float ret;
        if (normalizedVal <= R) {
            ret = MathUtils.sq(normalizedVal / R);
        } else {
            ret = MathUtils.exp((normalizedVal - C) / A) + B;
        }

        // HLG is normalized to the range [0, 12], ensure that value is within that range,
        // it shouldn't be out of bounds.
        final float normalizedRet = MathUtils.constrain(ret, 0, 12);

        // The OEM backlight control is nonlinear. Apply HLG to calibrated nits instead.
        if (hasNitsCalibration(displayId, min, max)) {
            if (val <= GAMMA_SPACE_MIN) {
                return min;
            }
            if (val >= GAMMA_SPACE_MAX) {
                return max;
            }
            final float targetNits = MathUtils.lerp(NitsBrightnessCurve.toNits(min),
                    NitsBrightnessCurve.toNits(max), normalizedRet / 12);
            return MathUtils.constrain(NitsBrightnessCurve.toBacklight(targetNits), min, max);
        }

        // Re-normalize to the range [0, 1] for displays with a linear backlight control.
        return MathUtils.lerp(min, max, normalizedRet / 12);
    }

    /**
     * A function for converting from the linear space that the setting works in to the
     * gamma space that the slider works in.
     *
     * The gamma space effectively provides us a way to make linear changes to the slider that
     * result in linear changes in perception. If we made changes to the slider in the linear space
     * then we'd see an approximately logarithmic change in perception (c.f. Fechner's Law).
     *
     * Internally, this implements the Hybrid Log Gamma opto-electronic transfer function, which is
     * a slight improvement to the typical gamma transfer function for displays whose max
     * brightness exceeds the 120 nit reference point, but doesn't set a specific reference
     * brightness like the PQ function does.
     *
     * Note that this transfer function is only valid if the display's backlight value is a linear
     * control. If it's calibrated to be something non-linear, then a different transfer function
     * should be used.
     *
     * @param val The brightness setting value.
     * @param min The minimum acceptable value for the setting.
     * @param max The maximum acceptable value for the setting.
     * @return The corresponding slider value
     */
    public static final int convertLinearToGamma(int val, int min, int max) {
        // Legacy integer brightness is not in the normalized backlight domain.
        return convertLinearToGammaFloat((float) val, (float) min, (float) max,
                Display.INVALID_DISPLAY);
    }

    /**
     * Version of {@link #convertLinearToGamma} that takes float values.
     * TODO: brightnessfloat merge with above method(?)
     * @param val The brightness setting value.
     * @param min The minimum acceptable value for the setting.
     * @param max The maximum acceptable value for the setting.
     * @return The corresponding slider value
     */
    public static final int convertLinearToGammaFloat(float val, float min, float max) {
        return convertLinearToGammaFloat(val, min, max, Display.DEFAULT_DISPLAY);
    }

    /** Inverse of the display-aware float conversion, using the same dynamic bounds. */
    public static int convertLinearToGammaFloat(float val, float min, float max, int displayId) {
        if (sDisableGammaConversion) {
            final float normalizedVal = MathUtils.norm(min, max, val);
            return Math.round(MathUtils.lerp(GAMMA_SPACE_MIN, GAMMA_SPACE_MAX, normalizedVal));
        }

        final float normalizedVal;
        if (hasNitsCalibration(displayId, min, max)) {
            if (val <= min) {
                return GAMMA_SPACE_MIN;
            }
            if (val >= max) {
                return GAMMA_SPACE_MAX;
            }
            final float clamped = MathUtils.constrain(val, min, max);
            normalizedVal = MathUtils.norm(NitsBrightnessCurve.toNits(min),
                    NitsBrightnessCurve.toNits(max), NitsBrightnessCurve.toNits(clamped)) * 12;
        } else {
            normalizedVal = MathUtils.norm(min, max, val) * 12;
        }
        final float ret;
        if (normalizedVal <= 1f) {
            ret = MathUtils.sqrt(normalizedVal) * R;
        } else {
            ret = A * MathUtils.log(normalizedVal - B) + C;
        }

        return Math.round(MathUtils.lerp(GAMMA_SPACE_MIN, GAMMA_SPACE_MAX, ret));
    }

    private static boolean hasNitsCalibration(int displayId, float min, float max) {
        if (!sHasNitsCalibration || displayId == Display.INVALID_DISPLAY
                || !Float.isFinite(min) || !Float.isFinite(max)
                || min < 0 || max > 1 || min >= max) {
            return false;
        }
        final DisplayInfo info = DisplayManagerGlobal.getInstance().getDisplayInfo(displayId);
        return info != null && NitsBrightnessCurve.DISPLAY_UNIQUE_ID.equals(info.uniqueId);
    }
}
