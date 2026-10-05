/* SPDX-License-Identifier: Apache-2.0
 * Copyright (C) 2026 The Android Open Source Project
 */
package com.android.server.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.os.Handler;
import android.os.test.TestLooper;
import android.provider.Settings;
import android.test.mock.MockContentResolver;
import android.view.Display;

import androidx.test.core.app.ApplicationProvider;

import com.android.internal.display.DcDimmingSettings;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;
import com.android.internal.util.test.LocalServiceKeeperRule;
import com.android.server.display.color.DisplayTransformManager;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class DcDimmingControllerTest {
    @Rule public final FakeSettingsProviderRule mSettingsRule = FakeSettingsProvider.rule();
    @Rule public final LocalServiceKeeperRule mServicesRule = new LocalServiceKeeperRule();

    private MockContentResolver mResolver;
    private DisplayTransformManager mTransformManager;
    private DcDimmingController mController;
    private final TestLooper mLooper = new TestLooper();
    private final Runnable mReapply = mock(Runnable.class);

    @Before
    public void setUp() {
        Context context = spy(new ContextWrapper(ApplicationProvider.getApplicationContext()));
        mResolver = spy(mSettingsRule.mockContentResolver(context));
        // The observer belongs to the fake provider. Never register it in the real system service.
        doNothing().when(mResolver).registerContentObserver(any(), anyBoolean(), any(), anyInt());
        when(context.getContentResolver()).thenReturn(mResolver);
        mTransformManager = mock(DisplayTransformManager.class);
        mServicesRule.overrideLocalService(DisplayTransformManager.class, mTransformManager);
        mController = new DcDimmingController(context, new Handler(mLooper.getLooper()), mReapply,
                new DcDimmingCurve(DcDimmingCurveTest.BRIGHTNESS, DcDimmingCurveTest.SCALE));
        mController.setBrightnessConstraints(DisplayPowerRequest.POLICY_BRIGHT, 1f);
    }

    @Test
    public void bootReadsSavedSettingAndReappliesCurrentRequest() {
        setRequested(true);
        mController.onBootCompleted(0);
        mLooper.dispatchAll();
        verify(mReapply).run();
        assertEquals(1, appliedSetting());
        assertEquals(0.33f, calculate(Display.STATE_ON).brightness, 0f);
    }

    @Test
    public void settingChangeWaitsForOffEvenIfObserverHasNotRun() {
        mController.onBootCompleted(0);
        setRequested(true);
        assertEquals(0.1f, calculate(Display.STATE_ON).brightness, 0f);
        mController.onScreenOff();
        assertEquals(1, appliedSetting());
        assertEquals(0.33f, calculate(Display.STATE_ON).brightness, 0f);
        setRequested(false);
        assertEquals(0.33f, calculate(Display.STATE_ON).brightness, 0f);
        mController.onScreenOff();
        assertEquals(0, appliedSetting());
        assertEquals(0.1f, calculate(Display.STATE_ON).brightness, 0f);
    }

    @Test
    public void dozeAndSuspendedStatesNeverLiftBrightness() {
        startEnabled();
        for (int state : new int[] {Display.STATE_OFF, Display.STATE_DOZE,
                Display.STATE_DOZE_SUSPEND, Display.STATE_ON_SUSPEND, Display.STATE_UNKNOWN}) {
            assertEquals(0.1f, calculate(state).brightness, 0f);
            assertEquals(1f, calculate(state).scale, 0f);
        }
    }

    @Test
    public void onPulseUnderDozePolicyBypassesDimming() {
        startEnabled();
        mController.setBrightnessConstraints(DisplayPowerRequest.POLICY_DOZE, 1f);
        assertEquals(0.1f, calculate(Display.STATE_ON).brightness, 0f);
        assertEquals(1, appliedSetting());
        mController.setBrightnessConstraints(DisplayPowerRequest.POLICY_BRIGHT, 1f);
        assertEquals(0.33f, calculate(Display.STATE_ON).brightness, 0f);
    }

    @Test
    public void capChangeBypassesAndRequestsRecalculation() {
        startEnabled();
        mLooper.dispatchAll();
        mController.setBrightnessConstraints(DisplayPowerRequest.POLICY_BRIGHT, 0.2f);
        assertEquals(0.1f, calculate(Display.STATE_ON).brightness, 0f);
        mLooper.dispatchAll();
        verify(mReapply, org.mockito.Mockito.times(2)).run();
    }

    @Test
    public void userSwitchKeepsAppliedStateUntilOff() {
        startEnabled();
        setRequested(false);
        mController.onUserSwitching(10);
        assertEquals(0.33f, calculate(Display.STATE_ON).brightness, 0f);
        mController.onScreenOff();
        assertEquals(0.1f, calculate(Display.STATE_ON).brightness, 0f);
    }

    @Test
    public void matrixUsesDedicatedLevelWithUnchangedAlpha() {
        startEnabled();
        mController.apply(calculate(Display.STATE_ON));
        ArgumentCaptor<float[]> captor = ArgumentCaptor.forClass(float[].class);
        verify(mTransformManager).setColorMatrix(
                org.mockito.ArgumentMatchers.eq(290), captor.capture());
        float[] matrix = captor.getValue();
        assertEquals(matrix[0], matrix[5], 0f);
        assertEquals(matrix[0], matrix[10], 0f);
        assertEquals(1f, matrix[15], 0f);
        mController.apply(calculate(Display.STATE_DOZE));
        verify(mTransformManager).setColorMatrix(290, null);
    }

    @Test
    public void replayRetainsOriginalMainAndSdrRequests() {
        startEnabled();
        mController.calculate(Display.STATE_ON, 0.2392f, 0.0824f);
        DcDimmingController.Request request = mController.getLastRequest();
        assertEquals(0.2392f, request.brightness, 0f);
        assertEquals(0.0824f, request.sdrBrightness, 0f);
    }

    @Test
    public void hdrRampTransitionUsesStockExactModifierComparison() {
        assertTrue(DcDimmingController.shouldSkipHdrRamp(4, 0));
        assertTrue(DcDimmingController.shouldSkipHdrRamp(0, 4));
        assertTrue(DcDimmingController.shouldSkipHdrRamp(4, 5));
        assertFalse(DcDimmingController.shouldSkipHdrRamp(4, 4));
        assertFalse(DcDimmingController.shouldSkipHdrRamp(5, 0));
    }

    private void startEnabled() {
        setRequested(true);
        mController.onBootCompleted(0);
    }

    private void setRequested(boolean enabled) {
        Settings.System.putIntForUser(mResolver, DcDimmingSettings.ENABLED, enabled ? 1 : 0, 0);
    }

    private int appliedSetting() {
        return Settings.System.getIntForUser(mResolver, DcDimmingSettings.APPLIED, 0, 0);
    }

    private DcDimmingCurve.Result calculate(int state) {
        return mController.calculate(state, 0.1f, 0.1f);
    }
}
