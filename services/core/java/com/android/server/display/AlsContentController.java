/*
 * Copyright (C) 2026 The Avium Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.display;

import static android.window.ScreenCapture.ScreenCaptureParams.PROTECTED_CONTENT_POLICY_THROW_EXCEPTION;
import static android.window.ScreenCapture.ScreenCaptureParams.SECURE_CONTENT_POLICY_THROW_EXCEPTION;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IThermalService;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Temperature;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayAddress;
import android.window.ScreenCaptureInternal;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reports panel content to the NX809J vendor ALS algorithm; does not modify ambient lux. */
final class AlsContentController {
    private static final String TAG = "AlsContentController";
    private static final String MONITOR_TYPE = "zte.sensor.zte_als_monitor";
    private static final String SERVICE = "nx809j.als_content";
    private static final String DESCRIPTOR = "org.avium.display.IAlsContentBridge";
    private static final String BACKLIGHT = "/sys/class/backlight/panel0-backlight/brightness";
    private static final String ACL = "/proc/driver/lcd_acl";
    private static final int SET_ACTIVE = IBinder.FIRST_CALL_TRANSACTION;
    private static final int SET_PARAMS = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final long REFRESH_MILLIS = 5000;

    private final Context mContext;
    private final SensorManager mSensorManager;
    private final Sensor mMonitor;
    private final Handler mOwnerHandler;
    private final HandlerThread mWorkerThread;
    private final Handler mWorker;
    private final AtomicBoolean mSampleQueued = new AtomicBoolean();
    private volatile boolean mEnabled;
    private volatile long mGeneration;
    private volatile long mCaptures;
    private volatile long mSkipped;
    private volatile long mSubmissions;

    // Used only on mWorker. No screenshot or sensor data is persisted.
    private IBinder mBridge;
    private boolean mBridgeActive;
    private int[] mSubmitted;
    private IBinder.DeathRecipient mBridgeDeath;

    private final SensorEventListener mListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            // The monitor value is a trigger, not environmental lux. Coalesce callbacks.
            requestSample();
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    AlsContentController(Context context, SensorManager sensors, Handler ownerHandler) {
        mContext = context;
        mSensorManager = sensors;
        mOwnerHandler = ownerHandler;
        Sensor monitor = null;
        for (Sensor sensor : sensors.getSensorList(Sensor.TYPE_ALL)) {
            if (MONITOR_TYPE.equals(sensor.getStringType())) {
                monitor = sensor;
                break;
            }
        }
        mMonitor = monitor;
        mWorkerThread = new HandlerThread("AlsContentCapture");
        mWorkerThread.start();
        mWorker = new Handler(mWorkerThread.getLooper());
        if (monitor == null) Slog.w(TAG, "Vendor ALS monitor unavailable; content reporting disabled");
    }

    // Called on the DPC handler. Registration and callbacks do no capture or vendor I/O.
    void setEnabled(boolean enabled) {
        enabled &= mMonitor != null;
        if (enabled == mEnabled) return;
        if (enabled && !mSensorManager.registerListener(mListener, mMonitor,
                SensorManager.SENSOR_DELAY_NORMAL, mOwnerHandler)) {
            Slog.w(TAG, "Cannot register vendor ALS monitor");
            return;
        }
        mEnabled = enabled;
        ++mGeneration;
        if (!enabled) mSensorManager.unregisterListener(mListener);
        final boolean active = enabled;
        mWorker.post(() -> {
            mSubmitted = null;
            if (mBridge != null) setBridgeActive(active);
            if (active && mEnabled) requestSample();
        });
    }

    void stop() {
        setEnabled(false);
        mWorker.post(() -> {
            if (mBridge != null) mBridge.unlinkToDeath(mBridgeDeath, 0);
            mBridge = null;
            mWorkerThread.quitSafely();
        });
    }

    void dump(PrintWriter pw) {
        pw.println("  ALS content: monitor=" + (mMonitor != null) + " enabled=" + mEnabled
                + " captures=" + mCaptures + " skipped=" + mSkipped
                + " submissions=" + mSubmissions);
        pw.println("    Submission/local acceptance does not confirm DSP compensation.");
    }

    private void requestSample() {
        if (!mEnabled || !mSampleQueued.compareAndSet(false, true)) return;
        final long generation = mGeneration;
        mWorker.post(() -> {
            try {
                if (isCurrent(generation)) sample(generation);
            } catch (RuntimeException e) {
                ++mSkipped;
                suspendBridge();
                Slog.w(TAG, "Skipping failed ALS content sample", e);
            } finally {
                mSampleQueued.set(false);
                mWorker.removeCallbacks(mRefresh);
                if (mEnabled) mWorker.postDelayed(mRefresh, REFRESH_MILLIS);
            }
        });
    }

    private final Runnable mRefresh = this::requestSample;

    private void handleBridgeDeath(IBinder bridge) {
        mWorker.post(() -> {
            if (mBridge != bridge) return;
            mBridge = null;
            mBridgeActive = false;
            mSubmitted = null;
            requestSample();
        });
    }

    private boolean isCurrent(long generation) {
        return mEnabled && generation == mGeneration;
    }

    private void sample(long generation) {
        if (!connectBridge()) return;
        Display display = mContext.getSystemService(DisplayManager.class)
                .getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null || !(display.getAddress() instanceof DisplayAddress.Physical)
                || display.getState() != Display.STATE_ON) {
            ++mSkipped;
            suspendBridge();
            return;
        }
        final int rotation = display.getRotation();
        final Display.Mode mode = display.getMode();
        final int width = mode.getPhysicalWidth();
        final int height = mode.getPhysicalHeight();
        if (width < AlsContentSample.RIGHT || height < AlsContentSample.BOTTOM) {
            ++mSkipped;
            suspendBridge();
            return;
        }
        int[] crop = AlsContentSample.crop(width, height, rotation);
        IBinder token = DisplayControl.getPhysicalDisplayToken(
                ((DisplayAddress.Physical) display.getAddress()).getPhysicalDisplayId());
        if (token == null) {
            suspendBridge();
            return;
        }
        ScreenCaptureInternal.ScreenshotHardwareBuffer capture =
                ScreenCaptureInternal.captureDisplay(
                        new ScreenCaptureInternal.DisplayCaptureArgs.Builder(token)
                                .setSourceCrop(new Rect(crop[0], crop[1], crop[2], crop[3]))
                                .setSize(AlsContentSample.SIZE, AlsContentSample.SIZE)
                                // This is the native TP capture equivalent established by
                                // stock disassembly: display render intent, no screenshot
                                // local tonemapping/HDR headroom cap/gainmap pass.
                                .setPreserveDisplayColors(true)
                                .setSecureContentPolicy(SECURE_CONTENT_POLICY_THROW_EXCEPTION)
                                .setProtectedContentPolicy(PROTECTED_CONTENT_POLICY_THROW_EXCEPTION)
                                .build());
        if (capture == null) {
            ++mSkipped;
            suspendBridge();
            return;
        }
        Bitmap hardware = null;
        Bitmap pixels = null;
        boolean validFrame = false;
        try {
            // This SF revision treats ThrowException like redaction in the GPU
            // path. Check the returned secure-layer flag instead of assuming a
            // successful capture contains only ordinary visible pixels.
            if (capture.containsSecureLayers()) {
                ++mSkipped;
                return;
            }
            if (!isCurrent(generation) || rotation != display.getRotation()
                    || !mode.equals(display.getMode()) || display.getState() != Display.STATE_ON) {
                ++mSkipped;
                return;
            }
            hardware = capture.asBitmap();
            if (hardware == null) return;
            pixels = hardware.copy(Bitmap.Config.ARGB_8888, false);
            if (pixels == null || pixels.getWidth() != AlsContentSample.SIZE
                    || pixels.getHeight() != AlsContentSample.SIZE) {
                ++mSkipped;
                return;
            }
            int[] argb = new int[AlsContentSample.SIZE * AlsContentSample.SIZE];
            pixels.getPixels(argb, 0, AlsContentSample.SIZE, 0, 0,
                    AlsContentSample.SIZE, AlsContentSample.SIZE);
            int[] rgb = AlsContentSample.rgb(argb, rotation);
            int backlight = readInteger(BACKLIGHT);
            if (backlight < 0 || backlight > 65535) {
                ++mSkipped;
                return;
            }
            int colorGamut = Settings.System.getIntForUser(mContext.getContentResolver(),
                    "lcd_color_gamut", 2, UserHandle.USER_CURRENT);
            int[] params = {rgb[0], rgb[1], rgb[2], backlight, alpsTemperature(),
                    AlsContentSample.mode(colorGamut, readInteger(ACL))};
            ++mCaptures;
            validFrame = true;
            if (isCurrent(generation) && (mBridgeActive || setBridgeActive(true))
                    && shouldSubmit(params) && transact(SET_PARAMS, params)) {
                mSubmitted = params;
                ++mSubmissions;
            }
        } finally {
            // Stop heartbeat retransmission of the old tuple when the new frame
            // cannot be trusted. A later valid frame starts a fresh session.
            if (!validFrame && isCurrent(generation)) suspendBridge();
            if (pixels != null) pixels.recycle();
            if (hardware != null) hardware.recycle();
            capture.getHardwareBuffer().close();
        }
    }

    private boolean shouldSubmit(int[] params) {
        if (mSubmitted == null) return true;
        return params[0] != mSubmitted[0] || params[1] != mSubmitted[1]
                || params[2] != mSubmitted[2] || params[3] != mSubmitted[3]
                || params[5] != mSubmitted[5]
                || (params[4] >= 34 && params[4] != mSubmitted[4]);
    }

    private boolean connectBridge() {
        if (mBridge != null) return true;
        IBinder bridge = ServiceManager.checkService(SERVICE);
        if (bridge == null) return false;
        try {
            mBridgeDeath = () -> handleBridgeDeath(bridge);
            bridge.linkToDeath(mBridgeDeath, 0);
            mBridge = bridge;
            mSubmitted = null;
            return setBridgeActive(mEnabled);
        } catch (RemoteException e) {
            mBridge = null;
            return false;
        }
    }

    private boolean setBridgeActive(boolean active) {
        boolean accepted = transact(SET_ACTIVE, new int[] {active ? 1 : 0});
        if (accepted) mBridgeActive = active;
        return accepted;
    }

    private void suspendBridge() {
        mSubmitted = null;
        if (mBridgeActive) setBridgeActive(false);
    }

    private boolean transact(int code, int[] values) {
        if (mBridge == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            for (int value : values) data.writeInt(value);
            if (!mBridge.transact(code, data, reply, 0)) return false;
            reply.readException();
            return reply.readInt() == 1;
        } catch (RemoteException e) {
            mBridge.unlinkToDeath(mBridgeDeath, 0);
            mBridge = null;
            mBridgeActive = false;
            mSubmitted = null;
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private int alpsTemperature() {
        IThermalService thermal = IThermalService.Stub.asInterface(
                ServiceManager.checkService(Context.THERMAL_SERVICE));
        if (thermal == null) return -1;
        try {
            // On this vendor HAL, type 17 is named alps despite the standard TYPE_SPEAKER name.
            for (Temperature temperature : thermal.getCurrentTemperaturesWithType(17)) {
                if ("alps".equals(temperature.getName()) && Float.isFinite(temperature.getValue())) {
                    return (int) temperature.getValue();
                }
            }
        } catch (RemoteException e) {
            return -1;
        }
        return -1;
    }

    private static int readInteger(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line = reader.readLine();
            return line == null ? -1 : Integer.parseInt(line.trim());
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }
}
