/* Copyright (C) 2026 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui

import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.systemui.decor.UdcColorCalibration
import com.google.common.truth.Truth.assertThat
import com.nubia.udc.UdcColorProfiles
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UdcColorCalibrationTest {
    private val geometry = intArrayOf(1216, 2688, 608, 70, 24)

    @Test fun defaultsAreTransparent() {
        for (profile in 0..2) assertThat(UdcColorProfiles().color(profile)).isEqualTo(0)
    }

    @Test fun stockMaximumChannels() {
        val profiles = UdcColorProfiles()
        profiles.set(0, 0, 0)
        for (channel in 1..3) profiles.set(0, channel, 100)
        assertThat(profiles.color(0)).isEqualTo(0x26808080)
    }

    @Test fun stockRounding() {
        val profiles = UdcColorProfiles()
        profiles.set(1, 0, 50)
        profiles.set(1, 1, 50)
        profiles.set(1, 2, 25)
        profiles.set(1, 3, 75)
        assertThat(profiles.color(1)).isEqualTo(0x13402060)
    }

    @Test fun brightnessBoundaries() {
        assertThat(UdcColorProfiles.profileForBrightness(0f)).isEqualTo(0)
        assertThat(UdcColorProfiles.profileForBrightness(0.4699f)).isEqualTo(0)
        assertThat(UdcColorProfiles.profileForBrightness(0.47f)).isEqualTo(1)
        assertThat(UdcColorProfiles.profileForBrightness(0.8299f)).isEqualTo(1)
        assertThat(UdcColorProfiles.profileForBrightness(0.83f)).isEqualTo(2)
        assertThat(UdcColorProfiles.profileForBrightness(1f)).isEqualTo(2)
    }

    @Test fun unknownBrightnessHidesCalibration() {
        for (value in floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            assertThat(UdcColorProfiles.profileForBrightness(value)).isEqualTo(-1)
        }
    }

    @Test fun previewBrightnessSelectsMatchingProfile() {
        for (profile in 0..2) {
            assertThat(UdcColorProfiles.profileForBrightness(
                UdcColorProfiles.previewBrightness(profile))).isEqualTo(profile)
        }
    }

    @Test fun serializationPreservesIndependentProfiles() {
        val profiles = UdcColorProfiles()
        profiles.set(0, 0, 20)
        profiles.set(1, 1, 40)
        profiles.set(2, 2, 60)
        val copy = UdcColorProfiles.parse(profiles.encode())
        assertThat(copy).isEqualTo(profiles)
        copy.reset(0)
        assertThat(copy.get(1, 1)).isEqualTo(40)
        assertThat(copy.get(2, 2)).isEqualTo(60)
        assertThat(profiles.get(0, 0)).isEqualTo(20)
    }

    @Test fun corruptedSettingsCannotCreateOpaqueOverlay() {
        val valid = UdcColorProfiles().encode()
        for (value in listOf(null, "", "2" + valid.substring(1), valid + ";0",
                valid.replaceFirst("100", "101"), valid.replaceFirst("100", "-1"),
                valid.replaceFirst("100", "oops"))) {
            assertThat(UdcColorProfiles.parse(value)).isEqualTo(UdcColorProfiles())
        }
    }

    @Test fun portraitMatchesStock48PixelCircle() {
        assertThat(UdcColorCalibration.circleBounds(geometry, 1216, 2688, 0))
            .usingTolerance(0.0).containsExactly(608f, 70f, 24f).inOrder()
    }

    @Test fun followsAllRotations() {
        val expected = arrayOf(floatArrayOf(608f, 70f, 24f), floatArrayOf(70f, 608f, 24f),
                floatArrayOf(608f, 2618f, 24f), floatArrayOf(2618f, 608f, 24f))
        for (rotation in 0..3) {
            assertThat(UdcColorCalibration.circleBounds(geometry, 1216, 2688, rotation))
                .usingTolerance(0.0).containsExactly(expected[rotation]).inOrder()
        }
    }

    @Test fun resolutionOverrideScalesPhysicalGeometry() {
        assertThat(UdcColorCalibration.circleBounds(geometry, 608, 1344, 0))
            .usingTolerance(0.0).containsExactly(304f, 35f, 12f).inOrder()
    }

    @Test fun visibleOnlyForNontransparentNormalDisplay() {
        assertThat(UdcColorCalibration.shouldShow(0x26000000, Display.STATE_ON,
                false, false, false)).isTrue()
        assertThat(UdcColorCalibration.shouldShow(0, Display.STATE_ON,
                false, false, false)).isFalse()
    }

    @Test fun cameraProtectionHasPriority() {
        assertThat(UdcColorCalibration.shouldShow(0x26808080, Display.STATE_ON,
                true, false, false)).isFalse()
    }

    @Test fun previewDoesNotStackWithPersistentColor() {
        assertThat(UdcColorCalibration.shouldShow(0x26808080, Display.STATE_ON,
                false, true, false)).isFalse()
    }

    @Test fun accessibilityColorChangesSuppressCalibration() {
        assertThat(UdcColorCalibration.shouldShow(0x26808080, Display.STATE_ON,
                false, false, true)).isFalse()
    }

    @Test fun screenOffAndDozeSuppressCalibration() {
        for (state in intArrayOf(Display.STATE_OFF, Display.STATE_DOZE,
                Display.STATE_DOZE_SUSPEND, Display.STATE_UNKNOWN)) {
            assertThat(UdcColorCalibration.shouldShow(0x26808080, state,
                    false, false, false)).isFalse()
        }
    }
}
