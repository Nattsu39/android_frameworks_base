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

import static com.android.server.display.brightness.BrightnessReason.MODIFIER_HDR;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.view.Display;

import com.android.internal.R;
import com.android.internal.display.DcDimmingSettings;
import com.android.server.LocalServices;
import com.android.server.display.color.DisplayTransformManager;

import java.io.PrintWriter;

/**
 * Software DC dimming for a calibrated internal display.
 *
 * <p>DisplayBlanker serializes brightness calculation and matrix submission. This class's lock
 * protects settings and policy snapshots; SurfaceFlinger and settings writes run outside DMS's
 * sync root. The global color matrix also affects external and virtual outputs.
 */
final class DcDimmingController {
    private static final String TAG = "DcDimmingController";

    private final Object mLock = new Object();
    private final Context mContext;
    private final Handler mHandler;
    private final Runnable mReapplyRequest;
    private final DcDimmingCurve mCurve;

    // All mutable fields are guarded by mLock.
    private DisplayTransformManager mTransformManager;
    private boolean mStarted;
    private int mUserId = UserHandle.USER_SYSTEM;
    private boolean mRequested;
    private boolean mApplied;
    private int mPolicy = DisplayPowerRequest.POLICY_OFF;
    private float mMaximumBrightness = 1f;
    private Request mLastRequest;
    private DcDimmingCurve.Result mLastResult;
    private long mSubmissionCount;

    DcDimmingController(Context context, Handler handler, Runnable reapplyRequest) {
        this(context, handler, reapplyRequest, loadCurve(context.getResources()));
    }

    DcDimmingController(Context context, Handler handler, Runnable reapplyRequest,
            DcDimmingCurve curve) {
        mContext = context;
        mHandler = handler;
        mReapplyRequest = reapplyRequest;
        mCurve = curve;
    }

    private static DcDimmingCurve loadCurve(Resources resources) {
        DcDimmingCurve curve = null;
        if (resources.getBoolean(R.bool.config_supportSoftwareDcDimming)) {
            try {
                curve = new DcDimmingCurve(
                        readFloatArray(resources, R.array.config_softwareDcDimmingBrightness),
                        readFloatArray(resources, R.array.config_softwareDcDimmingScale));
            } catch (IllegalArgumentException | Resources.NotFoundException e) {
                Slog.e(TAG, "Disabling DC dimming: invalid panel calibration", e);
            }
        }
        return curve;
    }

    private static float[] readFloatArray(Resources resources, int resourceId) {
        final TypedArray values = resources.obtainTypedArray(resourceId);
        try {
            final float[] result = new float[values.length()];
            for (int i = 0; i < result.length; i++) {
                result[i] = values.getFloat(i, Float.NaN);
            }
            return result;
        } finally {
            values.recycle();
        }
    }

    boolean isSupported() {
        return mCurve != null;
    }

    /** Stock skips the ramp on entry to or exit from the exact HDR-only modifier value. */
    static boolean shouldSkipHdrRamp(int currentModifier, int previousModifier) {
        return (currentModifier == MODIFIER_HDR) != (previousModifier == MODIFIER_HDR);
    }

    void onBootCompleted(int userId) {
        if (!isSupported()) {
            return;
        }
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(DcDimmingSettings.ENABLED), false,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        synchronized (mLock) {
                            mRequested = readRequestedLocked();
                        }
                    }
                }, UserHandle.USER_ALL);
        synchronized (mLock) {
            mUserId = userId;
            mTransformManager = LocalServices.getService(DisplayTransformManager.class);
            mRequested = readRequestedLocked();
            mApplied = mRequested;
            mStarted = true;
            writeAppliedLocked();
            scheduleReapplyLocked();
        }
    }

    void onUserSwitching(int userId) {
        synchronized (mLock) {
            mUserId = userId;
            if (mStarted) {
                mRequested = readRequestedLocked();
            }
        }
    }

    /** Called only at an actual transition to OFF, outside DMS's sync root. */
    void onScreenOff() {
        synchronized (mLock) {
            if (mStarted) {
                // Read again so a settings observer delayed behind this transition cannot lose it.
                mRequested = readRequestedLocked();
                mApplied = mRequested;
                writeAppliedLocked();
            }
        }
    }

    void setBrightnessConstraints(int policy, float maximumBrightness) {
        if (!isSupported()) {
            return;
        }
        synchronized (mLock) {
            if (mPolicy != policy || Float.compare(mMaximumBrightness, maximumBrightness) != 0) {
                mPolicy = policy;
                mMaximumBrightness = maximumBrightness;
                scheduleReapplyLocked();
            }
        }
    }

    private void scheduleReapplyLocked() {
        // A cap or policy change matters even when PhotonicModulator's brightness is unchanged.
        mHandler.removeCallbacks(mReapplyRequest);
        mHandler.post(mReapplyRequest);
    }

    Request getLastRequest() {
        synchronized (mLock) {
            return mLastRequest;
        }
    }

    /** Pure calculation; safe to call with DMS's sync root held. */
    DcDimmingCurve.Result calculate(int state, float brightness, float sdrBrightness) {
        synchronized (mLock) {
            mLastRequest = new Request(state, brightness, sdrBrightness);
            final boolean allowed = mStarted && mApplied && mTransformManager != null
                    && state == Display.STATE_ON
                    && (mPolicy == DisplayPowerRequest.POLICY_BRIGHT
                    || mPolicy == DisplayPowerRequest.POLICY_DIM);
            mLastResult = mCurve.transform(allowed, mMaximumBrightness, brightness, sdrBrightness);
            return mLastResult;
        }
    }

    /** Submits only our matrix level, outside DMS's sync root, on the serialized blanker path. */
    void apply(DcDimmingCurve.Result result) {
        final DisplayTransformManager transformManager;
        synchronized (mLock) {
            transformManager = mTransformManager;
        }
        if (transformManager == null) {
            return;
        }
        final float scale = result.scale;
        final float[] matrix = scale == 1f ? null : new float[] {
                scale, 0f, 0f, 0f,
                0f, scale, 0f, 0f,
                0f, 0f, scale, 0f,
                0f, 0f, 0f, 1f
        };
        transformManager.setColorMatrix(DisplayTransformManager.LEVEL_COLOR_MATRIX_DC_DIMMING,
                matrix);
        synchronized (mLock) {
            mSubmissionCount++;
        }
    }

    private boolean readRequestedLocked() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                DcDimmingSettings.ENABLED, 0, mUserId) != 0;
    }

    private void writeAppliedLocked() {
        if (!Settings.System.putIntForUser(mContext.getContentResolver(),
                DcDimmingSettings.APPLIED, mApplied ? 1 : 0, mUserId)) {
            Slog.w(TAG, "Could not persist applied DC dimming state for user " + mUserId);
        }
    }

    void dump(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("  Software DC dimming:");
            pw.println("    supported=" + isSupported() + ", started=" + mStarted
                    + ", colorServiceAvailable=" + (mTransformManager != null));
            pw.println("    user=" + mUserId + ", requested=" + mRequested + ", applied=" + mApplied
                    + ", pending=" + (mRequested != mApplied));
            pw.println("    policy=" + mPolicy + ", maximumBrightness=" + mMaximumBrightness
                    + ", floor=" + (mCurve == null ? Float.NaN : mCurve.getBrightnessFloor()));
            if (mLastRequest != null && mLastResult != null) {
                pw.println("    state=" + Display.stateToString(mLastRequest.state)
                        + ", mainRequest=" + mLastRequest.brightness
                        + ", sdrRequest=" + mLastRequest.sdrBrightness);
                pw.println("    mainOutput=" + mLastResult.brightness
                        + ", sdrOutput=" + mLastResult.sdrBrightness
                        + ", scale=" + mLastResult.scale);
            }
            pw.println("    matrixSubmissions=" + mSubmissionCount
                    + " (submission count, not frame presentation confirmation)");
        }
    }

    static final class Request {
        final int state;
        final float brightness;
        final float sdrBrightness;

        Request(int state, float brightness, float sdrBrightness) {
            this.state = state;
            this.brightness = brightness;
            this.sdrBrightness = sdrBrightness;
        }
    }
}
