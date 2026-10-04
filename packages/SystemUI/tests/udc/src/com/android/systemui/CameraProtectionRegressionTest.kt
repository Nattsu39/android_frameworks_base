/* Copyright (C) 2026 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.CameraStatus
import android.hardware.ICameraService
import android.hardware.ICameraServiceListener
import android.hardware.camera2.CameraManager
import android.hardware.graphics.common.AlphaInterpretation
import android.hardware.graphics.common.DisplayDecorationSupport
import android.testing.TestableContext
import android.os.RemoteException
import android.view.Display
import android.view.DisplayCutout
import android.view.DisplayInfo
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.systemui.decor.CutoutDecorProviderFactory
import com.android.systemui.res.R
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever

/** Exercises the real drawing, provider and camera callback code without the full SystemUI suite. */
@RunWith(AndroidJUnit4::class)
class CameraProtectionRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val display = mock(Display::class.java)
    private val info = DisplayInfo()
    private lateinit var context: TestableContext
    private val bounds = Rect(566, 28, 650, 112)
    private val path = Path().apply { addCircle(608f, 70f, 42f, Path.Direction.CW) }

    @Before
    fun setUp() {
        info.logicalWidth = 1216
        info.logicalHeight = 2688
        info.uniqueId = "test:udc"
        whenever(display.uniqueId).thenReturn(info.uniqueId)
        whenever(display.getDisplayInfo(any(DisplayInfo::class.java))).thenAnswer {
            (it.arguments[0] as DisplayInfo).copyFrom(info)
            true
        }
        context = object : TestableContext(instrumentation.targetContext) {
            override fun getDisplay(): Display = this@CameraProtectionRegressionTest.display
        }
        val resources = context.orCreateTestableResources
        resources.addOverride(
            com.android.internal.R.array.config_displayUniqueIdArray, emptyArray<String>()
        )
        resources.addOverride(
            com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout, false
        )
        resources.addOverride(R.bool.config_enableDisplayCutoutProtection, true)
        resources.addOverride(R.bool.config_enableCameraProtectionWithoutCutout, true)
        resources.addOverride(R.integer.config_cameraProtectionAnimationDuration, 0)
    }

    @Test
    fun protectionDrawsWithoutPhysicalCutoutAndHidesImmediately() = onMain {
        val view = DisplayCutoutBaseView(context)
        view.updateRotation(0)
        view.setProtection(path, bounds)
        view.enableShowProtection(true)
        val bitmap = Bitmap.createBitmap(1216, 2688, Bitmap.Config.ARGB_8888)
        view.onDraw(Canvas(bitmap))
        assertThat(bitmap.getPixel(608, 70)).isEqualTo(Color.BLACK)
        assertThat(bitmap.getPixel(608, 120)).isEqualTo(Color.TRANSPARENT)
        assertThat(view.cameraProtectionProgress).isEqualTo(1f)
        assertThat(view.shouldInterceptTouch()).isFalse()
        view.enableShowProtection(false)
        bitmap.eraseColor(Color.TRANSPARENT)
        view.onDraw(Canvas(bitmap))
        assertThat(bitmap.getPixel(608, 70)).isEqualTo(Color.TRANSPARENT)
        assertThat(view.cameraProtectionProgress).isEqualTo(0f)
        bitmap.recycle()
    }

    @Test
    fun protectionRotatesFromNaturalPanelCoordinates() = onMain {
        val view = DisplayCutoutBaseView(context)
        view.setProtection(path, bounds)
        view.enableShowProtection(true)
        val expected = listOf(
            RectF(566f, 28f, 650f, 112f),
            RectF(28f, 566f, 112f, 650f),
            RectF(566f, 2576f, 650f, 2660f),
            RectF(2576f, 566f, 2660f, 650f),
        )
        for (rotation in 0..3) {
            info.rotation = rotation
            info.logicalWidth = if (rotation % 2 == 0) 1216 else 2688
            info.logicalHeight = if (rotation % 2 == 0) 2688 else 1216
            view.updateRotation(rotation)
            assertThat(view.protectionRect).isEqualTo(expected[rotation])
        }
    }

    @Test
    fun repeatedActiveProtectionRetainsItsDrawingPath() = onMain {
        val view = DisplayCutoutBaseView(context)
        view.updateRotation(0)
        repeat(2) {
            view.setProtection(path, bounds)
            view.enableShowProtection(true)
        }
        assertThat(view.protectionRect).isEqualTo(RectF(bounds))
        assertThat(view.protectionPath.isEmpty).isFalse()
        assertThat(view.cameraProtectionProgress).isEqualTo(1f)
    }

    @Test
    fun hardwareDecorationProtectsCameraFromTransparentRegionOptimization() = onMain {
        val support = DisplayDecorationSupport().apply {
            format = PixelFormat.R_8
            alphaInterpretation = AlphaInterpretation.COVERAGE
        }
        val view = ScreenDecorHwcLayer(context, support, false)
        FrameLayout(context).addView(view)
        view.layout(0, 0, 1216, 2688)
        view.updateRotation(0)
        view.setProtection(path, bounds)
        view.enableShowProtection(true)
        view.calculateTransparentRect()
        assertThat(view.transparentRect).isEqualTo(Rect(0, 112, 1216, 2688))
        view.enableShowProtection(false)
        view.calculateTransparentRect()
        assertThat(view.transparentRect).isEqualTo(Rect(0, 0, 1216, 2688))
    }

    @Test
    fun softwareFallbackProvidesTopViewWithoutChangingDisplayCutout() = onMain {
        val factory = CutoutDecorProviderFactory(context.resources, display)
        assertThat(factory.hasProviders).isTrue()
        assertThat(factory.providers).hasSize(1)
        assertThat(factory.providers.single().alignedBounds)
            .containsExactly(DisplayCutout.BOUNDS_POSITION_TOP)
        assertThat(factory.displayInfo.displayCutout).isNull()
        context.orCreateTestableResources.addOverride(
            R.bool.config_enableCameraProtectionWithoutCutout, false
        )
        assertThat(factory.hasProviders).isFalse()
    }

    @Test
    fun softwareFallbackMeasuresEdgeGapAndUsesInversionCompensation() = onMain {
        val view = spy(
            ScreenDecorations.DisplayCutoutView(context, DisplayCutout.BOUNDS_POSITION_TOP)
        )
        doReturn(true).`when`(view).isAttachedToWindow
        doReturn(display).`when`(view).display
        view.layoutParams = FrameLayout.LayoutParams(0, 0)
        view.updateCutout()
        view.setColor(Color.WHITE)
        view.setProtection(path, bounds)
        view.enableShowProtection(true)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1216, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(2688, View.MeasureSpec.AT_MOST),
        )
        assertThat(view.measuredWidth).isEqualTo(1216)
        assertThat(view.measuredHeight).isEqualTo(112)
        assertThat(view.visibility).isEqualTo(View.VISIBLE)
        val bitmap = Bitmap.createBitmap(1216, 112, Bitmap.Config.ARGB_8888)
        view.onDraw(Canvas(bitmap))
        // ScreenDecorations supplies white under inversion; the display transform makes it black.
        assertThat(bitmap.getPixel(608, 70)).isEqualTo(Color.WHITE)
        view.enableShowProtection(false)
        assertThat(view.visibility).isEqualTo(View.GONE)
        bitmap.recycle()
    }

    @Test
    fun softwareFallbackMeasuresAndDrawsAllFourEdges() = onMain {
        val gravities = listOf(Gravity.TOP, Gravity.LEFT, Gravity.BOTTOM, Gravity.RIGHT)
        for (rotation in 0..3) {
            info.rotation = rotation
            info.logicalWidth = if (rotation % 2 == 0) 1216 else 2688
            info.logicalHeight = if (rotation % 2 == 0) 2688 else 1216
            val view = spy(
                ScreenDecorations.DisplayCutoutView(context, DisplayCutout.BOUNDS_POSITION_TOP)
            )
            doReturn(true).`when`(view).isAttachedToWindow
            doReturn(display).`when`(view).display
            val offsetX = if (rotation == 3) 2576 else 0
            val offsetY = if (rotation == 2) 2576 else 0
            doAnswer {
                (it.arguments[0] as IntArray).also { location ->
                    location[0] = offsetX
                    location[1] = offsetY
                }
                null
            }.`when`(view).getLocationOnScreen(any(IntArray::class.java))
            view.layoutParams = FrameLayout.LayoutParams(0, 0)
            view.updateRotation(rotation)
            view.updateCutout()
            view.setProtection(path, bounds)
            view.enableShowProtection(true)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(info.logicalWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(info.logicalHeight, View.MeasureSpec.AT_MOST),
            )
            assertThat(view.measuredWidth).isEqualTo(if (rotation % 2 == 0) 1216 else 112)
            assertThat(view.measuredHeight).isEqualTo(if (rotation % 2 == 0) 112 else 1216)
            assertThat((view.layoutParams as FrameLayout.LayoutParams).gravity)
                .isEqualTo(gravities[rotation])
            val bitmap = Bitmap.createBitmap(
                view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888
            )
            view.onDraw(Canvas(bitmap))
            val centerX = view.protectionRect.centerX().toInt() - offsetX
            val centerY = view.protectionRect.centerY().toInt() - offsetY
            assertThat(bitmap.getPixel(centerX, centerY)).isEqualTo(Color.BLACK)
            bitmap.recycle()
        }
    }

    @Test
    fun unrelatedCameraCloseThenFrontCloseRemovesProtection() {
        val fixture = listenerFixture()
        fixture.events.onCameraOpened("3", "third.party")
        fixture.events.onCameraOpened("1", "rear.app")
        fixture.events.onCameraClosed("1")
        assertThat(fixture.shown).isTrue()
        fixture.events.onCameraClosed("3")
        assertThat(fixture.shown).isFalse()
        assertThat(fixture.hides).isEqualTo(1)
    }

    @Test
    fun unrelatedPhysicalCallbackDoesNotClearProtectedLogicalCamera() {
        val fixture = listenerFixture("front")
        fixture.events.onCameraOpened("3", "third.party")
        fixture.events.onCameraOpened("1", "rear.app")
        fixture.events.onPhysicalCameraUnavailable("3", "front")
        assertThat(fixture.shown).isFalse()
        fixture.events.onPhysicalCameraAvailable("3", "front")
        assertThat(fixture.shown).isTrue()
        fixture.events.onCameraClosed("3")
        assertThat(fixture.shown).isFalse()
    }

    @Test
    fun stopHidesProtectionAndAllowsFreshCameraEvents() {
        val fixture = listenerFixture()
        fixture.events.onCameraOpened("3", "third.party")
        fixture.listener.stop()
        assertThat(fixture.shown).isFalse()
        fixture.listener.startListening()
        fixture.events.onCameraOpened("3", "third.party")
        assertThat(fixture.shown).isTrue()
        fixture.events.onCameraClosed("3")
        assertThat(fixture.shown).isFalse()
    }

    @Test
    fun excludedPackageDoesNotApplyProtection() {
        val fixture = listenerFixture()
        fixture.events.onCameraOpened("3", "excluded.app")
        assertThat(fixture.shown).isFalse()
    }

    @Test
    fun existingCameraSnapshotRestoresProtectionUntilClose() {
        val fixture = listenerFixture(initialStates = listOf(cameraState()))
        assertThat(fixture.shown).isTrue()
        fixture.events.onCameraClosed("3")
        assertThat(fixture.shown).isFalse()
    }

    @Test
    fun existingExcludedClientDoesNotApplyProtection() {
        val fixture = listenerFixture(initialStates = listOf(cameraState("excluded.app")))
        assertThat(fixture.shown).isFalse()
    }

    @Test
    fun snapshotRespectsUnavailablePhysicalCamera() {
        val state = cameraState().apply { unavailablePhysicalCameras = arrayOf("front") }
        val fixture = listenerFixture("front", listOf(state))
        assertThat(fixture.shown).isFalse()
        fixture.events.onPhysicalCameraAvailable("3", "front")
        assertThat(fixture.shown).isTrue()
    }

    @Test
    fun snapshotFiltersVirtualDevicesAndRemovesTemporaryListener() {
        val manager = mock(CameraManager::class.java)
        val service = mock(ICameraService::class.java)
        val builtIn = cameraState()
        val virtual = cameraState().apply { deviceId = 1 }
        whenever(manager.cameraService).thenReturn(service)
        whenever(service.addListener(any(ICameraServiceListener::class.java)))
            .thenReturn(arrayOf(builtIn, virtual))
        assertThat(CameraProtectionStateSnapshot.read(manager, 0)).containsExactly(builtIn)
        verify(service).removeListener(any(ICameraServiceListener::class.java))
    }

    @Test
    fun unavailableSnapshotStillRemovesTemporaryListener() {
        val manager = mock(CameraManager::class.java)
        val service = mock(ICameraService::class.java)
        whenever(manager.cameraService).thenReturn(service)
        doThrow(RemoteException("service disconnected")).`when`(service)
            .addListener(any(ICameraServiceListener::class.java))
        assertThat(CameraProtectionStateSnapshot.read(manager, 0)).isEmpty()
        verify(service).removeListener(any(ICameraServiceListener::class.java))
    }

    private fun cameraState(packageId: String = "third.party") = CameraStatus().apply {
        cameraId = "3"
        status = ICameraServiceListener.STATUS_NOT_AVAILABLE
        clientPackage = packageId
        deviceId = 0
        unavailablePhysicalCameras = emptyArray()
    }

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                block()
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
    }

    private fun listenerFixture(
        physicalId: String? = null,
        initialStates: List<CameraStatus> = emptyList(),
    ): ListenerFixture {
        val fixture = ListenerFixture()
        val manager = mock(CameraManager::class.java)
        doAnswer {
            fixture.events = it.arguments[1] as CameraManager.AvailabilityCallback
            null
        }.`when`(manager).registerAvailabilityCallback(
            any(Executor::class.java), any(CameraManager.AvailabilityCallback::class.java)
        )
        fixture.listener = CameraAvailabilityListener(
            manager, listOf(CameraProtectionInfo("3", physicalId, path, bounds, null)),
            "excluded.app", Executor { it.run() }, { initialStates },
        )
        fixture.listener.addTransitionCallback(
            object : CameraAvailabilityListener.CameraTransitionCallback {
                override fun onApplyCameraProtection(protectionPath: Path, bounds: Rect) {
                    fixture.shown = true
                }

                override fun onHideCameraProtection() {
                    fixture.shown = false
                    fixture.hides++
                }
            }
        )
        fixture.listener.startListening()
        return fixture
    }

    private class ListenerFixture {
        lateinit var listener: CameraAvailabilityListener
        lateinit var events: CameraManager.AvailabilityCallback
        var shown = false
        var hides = 0
    }
}
