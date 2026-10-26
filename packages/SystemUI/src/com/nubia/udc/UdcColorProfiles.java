/* Copyright (C) 2026 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nubia.udc;

import java.util.Arrays;

/** Versioned, per-user calibration shared by RedMagic Control and SystemUI. */
public final class UdcColorProfiles {
    public static final String SETTING = "nx809_udc_color_profiles";
    public static final int PROFILE_COUNT = 3;
    public static final int CHANNEL_COUNT = 4;
    private final int[] mValues = {100, 0, 0, 0, 100, 0, 0, 0, 100, 0, 0, 0};

    public static UdcColorProfiles parse(String encoded) {
        UdcColorProfiles profiles = new UdcColorProfiles();
        if (encoded == null) return profiles;
        String[] fields = encoded.split(";", -1);
        if (fields.length != 13 || !"1".equals(fields[0])) return profiles;
        try {
            for (int i = 0; i < 12; i++) {
                int value = Integer.parseInt(fields[i + 1]);
                if (value < 0 || value > 100) return new UdcColorProfiles();
                profiles.mValues[i] = value;
            }
        } catch (NumberFormatException e) {
            return new UdcColorProfiles();
        }
        return profiles;
    }

    public int get(int profile, int channel) {
        checkIndex(profile, channel);
        return mValues[profile * CHANNEL_COUNT + channel];
    }

    public void set(int profile, int channel, int value) {
        checkIndex(profile, channel);
        if (value < 0 || value > 100) throw new IllegalArgumentException("Invalid percentage");
        mValues[profile * CHANNEL_COUNT + channel] = value;
    }

    public void reset(int profile) {
        for (int channel = 0; channel < CHANNEL_COUNT; channel++) {
            set(profile, channel, channel == 0 ? 100 : 0);
        }
    }

    /** Stock alpha is 38 minus the light slider; RGB channels each span 0..128. */
    public int color(int profile) {
        int alpha = 38 - Math.round(get(profile, 0) * 38 / 100f);
        if (alpha == 0) return 0;
        return (alpha << 24) | (Math.round(get(profile, 1) * 128 / 100f) << 16)
                | (Math.round(get(profile, 2) * 128 / 100f) << 8)
                | Math.round(get(profile, 3) * 128 / 100f);
    }

    /** Use the applied display brightness, including automatic and window overrides. */
    public static int profileForBrightness(float brightness) {
        if (!Float.isFinite(brightness) || brightness < 0) return -1;
        return brightness < 0.47f ? 0 : brightness < 0.83f ? 1 : 2;
    }

    public static float previewBrightness(int profile) {
        checkIndex(profile, 0);
        return new float[]{68 / 255f, 170 / 255f, 1f}[profile];
    }

    public String encode() {
        StringBuilder encoded = new StringBuilder("1");
        for (int value : mValues) encoded.append(';').append(value);
        return encoded.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof UdcColorProfiles
                && Arrays.equals(mValues, ((UdcColorProfiles) other).mValues);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(mValues);
    }

    private static void checkIndex(int profile, int channel) {
        if (profile < 0 || profile >= PROFILE_COUNT || channel < 0 || channel >= CHANNEL_COUNT) {
            throw new IllegalArgumentException("Invalid calibration index");
        }
    }
}
