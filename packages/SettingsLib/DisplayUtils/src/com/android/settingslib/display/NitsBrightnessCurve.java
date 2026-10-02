/*
 * Panel calibration data:
 * Copyright (c) 2021, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * Changes from Qualcomm Innovation Center are provided under:
 * Copyright (c) 2023-2024 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.android.settingslib.display;

import java.util.Arrays;

/** Bidirectional, piecewise-linear calibration for the verified NX809J panel. */
final class NitsBrightnessCurve {
    static final String DISPLAY_UNIQUE_ID = "local:4630947168392018835";

    // Generated from the reviewed factory XML. Regenerate with apply_brightness_curve.py.
    // XML SHA256: 6fbb9628da623f0445c631a2e7ac8be19b77bd393db8aeb52b099540c540c4a9
    private static final float[] BACKLIGHT = {
        0.0000f, 0.0196f, 0.0392f, 0.0588f, 0.0784f, 0.098f,
        0.1176f, 0.1373f, 0.1569f, 0.1765f, 0.1961f, 0.2157f,
        0.2353f, 0.2549f, 0.2745f, 0.2941f, 0.3137f, 0.3333f,
        0.3529f, 0.3725f, 0.3922f, 0.4118f, 0.4314f, 0.451f,
        0.4706f, 0.4902f, 0.5098f, 0.5294f, 0.549f, 0.5686f,
        0.5882f, 0.6078f, 0.6275f, 0.6471f, 0.6667f, 0.6863f,
        0.7059f, 0.7255f, 0.7451f, 0.7647f, 0.7843f, 0.8039f,
        0.8235f, 0.8431f, 0.8627f, 0.8824f, 0.902f, 0.9216f,
        0.9412f, 0.9608f, 0.9804f, 1.0f,
    };
    private static final float[] NITS = {
        3.5656f, 4.0839f, 4.9475f, 5.9254f, 7.0171f, 8.503f,
        11.6029f, 15.0319f, 18.7615f, 22.9899f, 27.4477f, 33.7276f,
        40.1355f, 46.8394f, 55.0091f, 63.032f, 71.626f, 80.9005f,
        90.7174f, 101.4439f, 112.7651f, 125.3084f, 138.4505f, 153.3287f,
        169.4664f, 185.9423f, 203.0642f, 219.4539f, 236.8212f, 254.955f,
        274.7769f, 295.7729f, 316.0061f, 338.7526f, 361.509f, 385.7114f,
        410.4631f, 435.5487f, 462.3148f, 489.2858f, 518.7537f, 547.7319f,
        577.6963f, 609.674f, 655.3798f, 769.237f, 894.6417f, 1028.4795f,
        1165.0377f, 1304.6511f, 1447.4595f, 1599.524f,
    };

    private NitsBrightnessCurve() {}

    static float toNits(float backlight) {
        return interpolate(backlight, BACKLIGHT, NITS);
    }

    static float toBacklight(float nits) {
        return interpolate(nits, NITS, BACKLIGHT);
    }

    private static float interpolate(float value, float[] input, float[] output) {
        if (Float.isNaN(value)) {
            return Float.NaN;
        }
        if (value <= input[0]) {
            return output[0];
        }
        if (value >= input[input.length - 1]) {
            return output[output.length - 1];
        }
        final int index = Arrays.binarySearch(input, value);
        if (index >= 0) {
            return output[index];
        }
        final int upper = -index - 1;
        final int lower = upper - 1;
        final float fraction = (value - input[lower]) / (input[upper] - input[lower]);
        return output[lower] + fraction * (output[upper] - output[lower]);
    }
}
