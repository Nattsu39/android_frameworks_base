/* Copyright (C) 2026 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.decor;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.hardware.display.BrightnessInfo;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import com.android.systemui.ScreenDecorations;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;
import com.nubia.udc.UdcColorProfiles;

import java.io.PrintWriter;

/** Colored UDC compensation must use an RGBA window, never the HWC coverage mask. */
public final class UdcColorCalibration {
    private static final ComponentName EDITOR = new ComponentName(
            "com.nubia.rmcontrol", "com.nubia.rmcontrol.UdcCalibrationActivity");
    private final Context mContext;
    private final Handler mHandler;
    private final UserTracker mUsers;
    private final WindowManager mWindows;
    private final DisplayManager mDisplays;
    private final int[] mGeometry;
    private final CircleView mView;
    private final ContentObserver mObserver;
    private final DisplayManager.DisplayListener mDisplayListener;
    private final TaskStackChangeListener mTaskListener;
    private final UserTracker.Callback mUserListener;
    private UdcColorProfiles mProfiles = new UdcColorProfiles();
    private boolean mStarted;
    private boolean mAttached;
    private boolean mCameraActive;
    private boolean mEditorVisible;
    private boolean mAccessibilityAdjusted;
    private int mProfile = -1;
    private float mBrightness = Float.NaN;
    private int mDisplayState;
    private WindowManager.LayoutParams mLayout;

    public UdcColorCalibration(Context context, Handler handler, UserTracker users) {
        mContext = context;
        mHandler = handler;
        mUsers = users;
        mWindows = context.getSystemService(WindowManager.class);
        mDisplays = context.getSystemService(DisplayManager.class);
        mGeometry = context.getResources().getIntArray(R.array.config_udcColorCalibrationGeometry);
        mView = new CircleView(context);
        mObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                readSettings();
            }
        };
        mDisplayListener = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int id) { }
            @Override public void onDisplayRemoved(int id) {
                if (id == Display.DEFAULT_DISPLAY) removeWindow();
            }
            @Override public void onDisplayChanged(int id) {
                if (id == Display.DEFAULT_DISPLAY) update();
            }
        };
        mTaskListener = new TaskStackChangeListener() {
            @Override public void onTaskStackChanged() {
                mHandler.post(UdcColorCalibration.this::readTopActivity);
            }
        };
        mUserListener = new UserTracker.Callback() {
            @Override public void onUserChanged(int user, Context context) {
                removeWindow();
                readSettings();
                readTopActivity();
            }
        };
    }

    public void start() {
        if (mStarted || mContext.getDisplayId() != Display.DEFAULT_DISPLAY
                || mGeometry.length != 5) return;
        for (int value : mGeometry) {
            if (value <= 0) return;
        }
        mStarted = true;
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(UdcColorProfiles.SETTING), false, mObserver,
                UserHandle.USER_ALL);
        for (String key : new String[]{Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED,
                Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED}) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(key), false, mObserver, UserHandle.USER_ALL);
        }
        mDisplays.registerDisplayListener(mDisplayListener, mHandler,
                DisplayManager.EVENT_TYPE_DISPLAY_CHANGED | DisplayManager.EVENT_TYPE_DISPLAY_STATE
                        | DisplayManager.EVENT_TYPE_DISPLAY_BRIGHTNESS);
        mUsers.addCallback(mUserListener, new HandlerExecutor(mHandler));
        TaskStackChangeListeners.getInstance().registerTaskStackListener(mTaskListener);
        readTopActivity();
        readSettings();
    }

    public void setCameraActive(boolean active) {
        mCameraActive = active;
        update();
    }

    private void readTopActivity() {
        ActivityManager.RunningTaskInfo task =
                ActivityManagerWrapper.getInstance().getRunningTask();
        mEditorVisible = task != null && task.displayId == Display.DEFAULT_DISPLAY
                && EDITOR.equals(task.topActivity);
        update();
    }

    private void readSettings() {
        int user = mUsers.getUserId();
        mProfiles = UdcColorProfiles.parse(Settings.System.getStringForUser(
                mContext.getContentResolver(), UdcColorProfiles.SETTING, user));
        mAccessibilityAdjusted = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, 0, user) != 0
                || Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED, 0, user) != 0;
        update();
    }

    private void update() {
        if (!mStarted) return;
        Display display = mDisplays.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) {
            removeWindow();
            return;
        }
        mDisplayState = display.getState();
        BrightnessInfo brightness = display.getBrightnessInfo();
        mBrightness = brightness == null ? Float.NaN : brightness.adjustedBrightness;
        mProfile = UdcColorProfiles.profileForBrightness(mBrightness);
        int color = mProfile < 0 ? 0 : mProfiles.color(mProfile);
        if (!shouldShow(color, mDisplayState, mCameraActive, mEditorVisible,
                mAccessibilityAdjusted)) {
            removeWindow();
            return;
        }
        DisplayInfo info = new DisplayInfo();
        if (!display.getDisplayInfo(info)) {
            removeWindow();
            return;
        }
        float[] circle = circleBounds(mGeometry, info.getNaturalWidth(),
                info.getNaturalHeight(), info.rotation);
        WindowManager.LayoutParams layout = ScreenDecorations.getWindowLayoutBaseParams();
        // This is a colored overlay, so retain the display's normal color transformations.
        layout.privateFlags &= ~WindowManager.LayoutParams.PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC;
        layout.gravity = Gravity.TOP | Gravity.LEFT;
        layout.width = layout.height = (int) Math.ceil(circle[2] * 2);
        layout.x = Math.round(circle[0] - layout.width / 2f);
        layout.y = Math.round(circle[1] - layout.height / 2f);
        layout.setTitle("UdcColorCalibration");
        mView.setColor(color);
        if (!mAttached) {
            mWindows.addView(mView, layout);
            mAttached = true;
        } else if (mLayout.x != layout.x || mLayout.y != layout.y
                || mLayout.width != layout.width) {
            mWindows.updateViewLayout(mView, layout);
        }
        mLayout = layout;
    }

    public static boolean shouldShow(int color, int displayState, boolean cameraActive,
            boolean editorVisible, boolean accessibilityAdjusted) {
        return (color >>> 24) != 0 && displayState == Display.STATE_ON
                && !cameraActive && !editorVisible && !accessibilityAdjusted;
    }

    /** Device geometry is in natural panel pixels; return current center and radius. */
    public static float[] circleBounds(int[] geometry, int naturalWidth, int naturalHeight,
            int rotation) {
        float sx = (float) naturalWidth / geometry[0];
        float sy = (float) naturalHeight / geometry[1];
        float x = geometry[2] * sx;
        float y = geometry[3] * sy;
        float radius = geometry[4] * Math.min(sx, sy);
        switch (rotation) {
            case 1: return new float[]{y, naturalWidth - x, radius};
            case 2: return new float[]{naturalWidth - x, naturalHeight - y, radius};
            case 3: return new float[]{naturalHeight - y, x, radius};
            default: return new float[]{x, y, radius};
        }
    }

    private void removeWindow() {
        if (mAttached) {
            mWindows.removeViewImmediate(mView);
            mAttached = false;
        }
    }

    public void dump(PrintWriter writer) {
        writer.println("UdcColorCalibration: started=" + mStarted + " attached=" + mAttached
                + " user=" + mUsers.getUserId() + " profile=" + mProfile
                + " brightness=" + mBrightness + " state=" + mDisplayState
                + " camera=" + mCameraActive + " editor=" + mEditorVisible
                + " accessibility=" + mAccessibilityAdjusted);
        writer.println("  profiles=" + mProfiles.encode());
        if (mLayout != null) writer.println("  bounds=" + mLayout.x + "," + mLayout.y
                + " " + mLayout.width + "x" + mLayout.height);
    }

    private static final class CircleView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        CircleView(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void setColor(int color) {
            if (mPaint.getColor() != color) {
                mPaint.setColor(color);
                invalidate();
            }
        }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f,
                    Math.min(getWidth(), getHeight()) / 2f, mPaint);
        }
    }
}
