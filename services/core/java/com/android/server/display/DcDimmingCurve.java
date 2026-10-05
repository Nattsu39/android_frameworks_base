/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.server.display;

import android.util.Spline;

/** Validated panel calibration and the stock main/SDR brightness transformation. */
final class DcDimmingCurve {
    private final Spline mSpline;
    private final float mBrightnessFloor;

    DcDimmingCurve(float[] brightness, float[] scale) {
        if (brightness.length < 2 || brightness.length != scale.length
                || brightness[0] != 0f || scale[scale.length - 1] != 1f) {
            throw new IllegalArgumentException("Invalid DC dimming calibration endpoints");
        }
        for (int i = 0; i < brightness.length; i++) {
            if (!isValidBrightness(brightness[i]) || !Float.isFinite(scale[i])
                    || scale[i] <= 0f || scale[i] > 1f
                    || (i > 0 && (brightness[i] <= brightness[i - 1]
                    || scale[i] < scale[i - 1]))) {
                throw new IllegalArgumentException("Invalid DC dimming calibration at " + i);
            }
        }
        mBrightnessFloor = brightness[brightness.length - 1];
        mSpline = Spline.createSpline(brightness.clone(), scale.clone());
    }

    float getBrightnessFloor() {
        return mBrightnessFloor;
    }

    Result transform(boolean allowed, float maximumBrightness, float brightness,
            float sdrBrightness) {
        if (!allowed || !Float.isFinite(maximumBrightness)
                || maximumBrightness < mBrightnessFloor
                || !isValidBrightness(brightness) || !isValidBrightness(sdrBrightness)) {
            return new Result(brightness, sdrBrightness, 1f);
        }
        // HDR has already been calculated. Stock uses the main request for the shared matrix,
        // while flooring each brightness independently, even when the matrix becomes identity.
        return new Result(Math.max(brightness, mBrightnessFloor),
                Math.max(sdrBrightness, mBrightnessFloor), mSpline.interpolate(brightness));
    }

    private static boolean isValidBrightness(float value) {
        return Float.isFinite(value) && value >= 0f && value <= 1f;
    }

    static final class Result {
        final float brightness;
        final float sdrBrightness;
        final float scale;

        Result(float brightness, float sdrBrightness, float scale) {
            this.brightness = brightness;
            this.sdrBrightness = sdrBrightness;
            this.scale = scale;
        }
    }
}
