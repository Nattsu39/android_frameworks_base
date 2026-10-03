/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.biometrics.sensors.fingerprint.aidl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.common.CommonProps;
import android.hardware.biometrics.fingerprint.FingerprintSensorType;
import android.hardware.biometrics.fingerprint.SensorLocation;
import android.hardware.biometrics.fingerprint.SensorProps;
import android.hardware.biometrics.fingerprint.IFingerprint;
import android.hardware.biometrics.fingerprint.ISession;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.internal.R;
import com.android.modules.utils.testing.ExtendedMockitoRule;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.AuthSessionCoordinator;
import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.BiometricScheduler;
import com.android.server.biometrics.sensors.LockoutCache;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.UserSwitchProvider;
import com.android.server.biometrics.sensors.fingerprint.FingerprintUtils;
import com.android.server.biometrics.sensors.fingerprint.GestureAvailabilityDispatcher;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.stubbing.Answer;

import java.util.List;

@Presubmit
@SmallTest
public class SensorTest {

    private static final String TAG = "SensorTest";
    private static final int USER_ID = 2;
    private static final int SENSOR_ID = 4;
    private static final byte[] HAT = new byte[69];

    @Rule
    public final ExtendedMockitoRule mExtendedMockitoRule = new ExtendedMockitoRule.Builder(this)
            .spyStatic(Utils.class)
            .build();

    @Mock
    private Context mContext;
    @Mock
    private Resources mResources;
    @Mock
    private IBiometricService mBiometricService;
    @Mock
    private ISession mSession;
    @Mock
    private UserSwitchProvider<IFingerprint, ISession> mUserSwitchProvider;
    @Mock
    private LockoutResetDispatcher mLockoutResetDispatcher;
    @Mock
    private BiometricLogger mLogger;
    @Mock
    private BiometricContext mBiometricContext;
    @Mock
    private AuthSessionCoordinator mAuthSessionCoordinator;
    @Mock
    FingerprintProvider mFingerprintProvider;
    @Mock
    GestureAvailabilityDispatcher mGestureAvailabilityDispatcher;
    @Mock
    private AidlSession mCurrentSession;
    @Mock
    private BaseClientMonitor mClientMonitor;
    @Mock
    private HandlerThread mThread;
    @Mock
    AidlResponseHandler.AidlResponseHandlerCallback mAidlResponseHandlerCallback;
    @Mock
    private FingerprintUtils mBiometricUtils;

    private final TestLooper mLooper = new TestLooper();
    private final LockoutCache mLockoutCache = new LockoutCache();

    private BiometricScheduler<IFingerprint, ISession> mScheduler;
    private AidlResponseHandler mHalCallback;

    @Before
    public void setUp() {
        when(mContext.getResources()).thenReturn(mResources);

        when(mContext.getSystemService(Context.BIOMETRIC_SERVICE)).thenReturn(mBiometricService);
        ExtendedMockito.doAnswer(invocation -> mBiometricService.getCurrentStrength(SENSOR_ID))
                .when(() -> Utils.getCurrentStrength(SENSOR_ID));
        when(mBiometricContext.getAuthSessionCoordinator()).thenReturn(mAuthSessionCoordinator);
        when(mThread.getLooper()).thenReturn(mLooper.getLooper());

        mScheduler = new BiometricScheduler<>(
                new Handler(mLooper.getLooper()),
                BiometricScheduler.SENSOR_TYPE_FP_OTHER,
                null /* gestureAvailabilityDispatcher */,
                mBiometricService,
                2 /* recentOperationsLimit */,
                () -> USER_ID,
                mUserSwitchProvider);
        mHalCallback = new AidlResponseHandler(mContext, mScheduler, SENSOR_ID, USER_ID,
                mLockoutCache, mLockoutResetDispatcher, mAuthSessionCoordinator,
                mAidlResponseHandlerCallback, mBiometricUtils);
    }

    @Test
    public void halSessionCallback_respondsToResetLockout() throws Exception {
        doAnswer((Answer<Void>) invocationOnMock -> {
            mHalCallback.onLockoutCleared();
            return null;
        }).when(mSession).resetLockout(any());
        mLockoutCache.setLockoutModeForUser(USER_ID, LockoutTracker.LOCKOUT_TIMED);

        mScheduler.scheduleClientMonitor(new FingerprintResetLockoutClient(mContext,
                () -> new AidlSession(1, mSession, USER_ID, mHalCallback),
                USER_ID, TAG, SENSOR_ID, mLogger, mBiometricContext, HAT, mLockoutCache,
                mLockoutResetDispatcher, 0 /* biometricStrength */));
        mLooper.dispatchAll();

        verifyNotLocked();
    }

    @Test
    public void halSessionCallback_respondsToUnprovokedResetLockout() {
        mLockoutCache.setLockoutModeForUser(USER_ID, LockoutTracker.LOCKOUT_TIMED);

        mHalCallback.onLockoutCleared();
        mLooper.dispatchAll();

        verifyNotLocked();
    }

    @Test
    public void onBinderDied_cancelNonInterruptableClient() {
        mLooper.dispatchAll();

        when(mCurrentSession.getUserId()).thenReturn(USER_ID);
        when(mClientMonitor.getTargetUserId()).thenReturn(USER_ID);
        when(mClientMonitor.isInterruptable()).thenReturn(false);

        final Sensor sensor = getSensor();
        mScheduler = sensor.getScheduler();
        sensor.mCurrentSession = new AidlSession(0, mock(ISession.class),
                USER_ID, mHalCallback);

        mScheduler.scheduleClientMonitor(mClientMonitor);

        assertNotNull(mScheduler.getCurrentClient());

        sensor.onBinderDied();

        verify(mClientMonitor).cancel();
        assertNull(sensor.getSessionForUser(USER_ID));
        assertNull(mScheduler.getCurrentClient());
    }

    @Test
    public void sensorTypeOverride_defaultPreservesHalType() {
        final SensorProps props = createSensorProps(FingerprintSensorType.UNDER_DISPLAY_OPTICAL);

        final Sensor sensor = createSensorFromHalProps(props);

        assertEquals(FingerprintSensorType.UNDER_DISPLAY_OPTICAL,
                sensor.getSensorProperties().sensorType);
    }

    @Test
    public void sensorTypeOverride_correctsOpticalReportToUltrasonic() {
        when(mResources.getInteger(R.integer.config_udfpsSensorType))
                .thenReturn((int) FingerprintSensorType.UNDER_DISPLAY_ULTRASONIC);
        final SensorProps props = createSensorProps(FingerprintSensorType.UNDER_DISPLAY_OPTICAL);

        final FingerprintSensorPropertiesInternal actual =
                createSensorFromHalProps(props).getSensorProperties();

        assertEquals(FingerprintSensorType.UNDER_DISPLAY_ULTRASONIC, actual.sensorType);
        assertEquals(FingerprintSensorType.UNDER_DISPLAY_OPTICAL, props.sensorType);
        assertEquals(SENSOR_ID, actual.sensorId);
        assertEquals(5, actual.maxEnrollmentsPerUser);
        assertEquals(600, actual.getLocation().sensorLocationX);
        assertEquals(2000, actual.getLocation().sensorLocationY);
        assertEquals(140, actual.getLocation().sensorRadius);
    }

    @Test
    public void sensorTypeOverride_doesNotConvertNonUdfpsSensors() {
        when(mResources.getInteger(R.integer.config_udfpsSensorType))
                .thenReturn((int) FingerprintSensorType.UNDER_DISPLAY_ULTRASONIC);
        for (byte halType : new byte[] {
                FingerprintSensorType.REAR, FingerprintSensorType.POWER_BUTTON}) {
            final Sensor sensor = createSensorFromHalProps(createSensorProps(halType));

            assertEquals(halType, sensor.getSensorProperties().sensorType);
        }
    }

    @Test
    public void sensorTypeOverride_invalidValuePreservesHalType() {
        for (int invalidType : new int[] {-1, FingerprintSensorType.REAR,
                FingerprintSensorType.POWER_BUTTON, 99}) {
            when(mResources.getInteger(R.integer.config_udfpsSensorType)).thenReturn(invalidType);

            final Sensor sensor = createSensorFromHalProps(
                    createSensorProps(FingerprintSensorType.UNDER_DISPLAY_OPTICAL));

            assertEquals(FingerprintSensorType.UNDER_DISPLAY_OPTICAL,
                    sensor.getSensorProperties().sensorType);
        }
    }

    private Sensor createSensorFromHalProps(SensorProps props) {
        return new Sensor(mFingerprintProvider, mContext, new Handler(mLooper.getLooper()),
                props, mBiometricContext, List.of(),
                true /* resetLockoutRequiresHardwareAuthToken */);
    }

    private SensorProps createSensorProps(byte sensorType) {
        final SensorProps props = new SensorProps();
        props.commonProps = new CommonProps();
        props.commonProps.sensorId = SENSOR_ID;
        props.commonProps.maxEnrollmentsPerUser = 5;
        props.sensorType = sensorType;
        final SensorLocation location = new SensorLocation();
        location.display = "";
        location.sensorLocationX = 600;
        location.sensorLocationY = 2000;
        location.sensorRadius = 140;
        props.sensorLocations = new SensorLocation[] {location};
        return props;
    }

    private void verifyNotLocked() {
        assertEquals(LockoutTracker.LOCKOUT_NONE, mLockoutCache.getLockoutModeForUser(USER_ID));
        verify(mLockoutResetDispatcher).notifyLockoutResetCallbacks(eq(SENSOR_ID));
        verify(mAuthSessionCoordinator).resetLockoutFor(eq(USER_ID), anyInt(), anyLong());
    }

    private Sensor getSensor() {
        final SensorProps sensorProps = new SensorProps();
        sensorProps.commonProps = new CommonProps();
        sensorProps.commonProps.sensorId = 1;
        final FingerprintSensorPropertiesInternal internalProp = new
                FingerprintSensorPropertiesInternal(
                sensorProps.commonProps.sensorId, sensorProps.commonProps.sensorStrength,
                sensorProps.commonProps.maxEnrollmentsPerUser, null,
                sensorProps.sensorType, false /* resetLockoutRequiresHardwareAuthToken */);
        final Sensor sensor = new Sensor(mFingerprintProvider, mContext,
                null /* handler */, internalProp,
                mBiometricContext, mCurrentSession);
        sensor.init(mGestureAvailabilityDispatcher, mLockoutResetDispatcher);

        return sensor;
    }
}
