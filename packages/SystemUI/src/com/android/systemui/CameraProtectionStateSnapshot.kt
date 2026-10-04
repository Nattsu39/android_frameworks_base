/*
 * Copyright (C) 2026 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui

import android.hardware.CameraStatus
import android.hardware.ICameraServiceListener
import android.hardware.camera2.CameraManager
import android.os.RemoteException
import android.util.Log

/** Reads existing camera clients when CameraManager connected before open/close registration. */
object CameraProtectionStateSnapshot {
    private const val TAG = "CameraProtectionState"

    fun read(manager: CameraManager, deviceId: Int): List<CameraStatus> {
        val service = manager.cameraService ?: return emptyList()
        // CameraManager handles live events. This listener only obtains the service snapshot.
        val listener = object : ICameraServiceListener.Stub() {
            override fun onStatusChanged(status: Int, cameraId: String, deviceId: Int) = Unit
            override fun onPhysicalCameraStatusChanged(
                status: Int, cameraId: String, physicalId: String, deviceId: Int
            ) = Unit
            override fun onTorchStatusChanged(status: Int, cameraId: String, deviceId: Int) = Unit
            override fun onTorchStrengthLevelChanged(
                cameraId: String, strength: Int, deviceId: Int
            ) = Unit
            override fun onCameraAccessPrioritiesChanged() = Unit
            override fun onCameraOpened(
                cameraId: String, packageId: String, deviceId: Int
            ) = Unit
            override fun onCameraOpenedInSharedMode(
                cameraId: String, packageId: String, deviceId: Int, primaryClient: Boolean
            ) = Unit
            override fun onCameraClosed(cameraId: String, deviceId: Int) = Unit
        }
        return try {
            service.addListener(listener).filter { it.deviceId == deviceId }
        } catch (error: RemoteException) {
            Log.w(TAG, "Cannot read existing camera clients", error)
            emptyList()
        } catch (error: SecurityException) {
            Log.w(TAG, "Camera client snapshot is not permitted", error)
            emptyList()
        } finally {
            try {
                service.removeListener(listener)
            } catch (error: RemoteException) {
                Log.w(TAG, "Cannot remove camera snapshot listener", error)
            }
        }
    }
}
