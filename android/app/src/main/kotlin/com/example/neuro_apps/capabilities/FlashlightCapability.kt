package com.example.neuro_apps.capabilities

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build

class FlashlightCapability : DeviceCapability {
    override val name = "flashlight"
    override val supportedActions = setOf("set", "toggle", "get")
    override val executionMode = ExecutionMode.DIRECT
    override val requirement = CapabilityRequirement(minSdk = Build.VERSION_CODES.M)

    @Volatile
    private var isTorchOn: Boolean = false

    override fun isAvailable(context: Context): Boolean =
        super.isAvailable(context) &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)

    override fun getState(context: Context): Any? = isTorchOn

    override fun execute(
        context: Context,
        action: String,
        parameters: Map<String, Any?>,
        onComplete: (CapabilityResult) -> Unit,
    ) {
        val target = when (action) {
            "toggle" -> !isTorchOn
            else -> CapabilityHelpers.parseEnabled(parameters, isTorchOn) ?: run {
                onComplete(
                    CapabilityResult(
                        success = false,
                        capability = name,
                        action = action,
                        state = isTorchOn,
                        message = "Missing or invalid 'enabled' boolean parameter.",
                        executionMode = executionMode,
                        errorCode = "INVALID_PARAMETERS",
                    ),
                )
                return
            }
        }

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }

        if (cameraId == null) {
            onComplete(
                CapabilityResult(
                    success = false,
                    capability = name,
                    action = action,
                    message = "No flash unit found on this device.",
                    executionMode = executionMode,
                    errorCode = "NO_FLASH_HARDWARE",
                ),
            )
            return
        }

        cameraManager.setTorchMode(cameraId, target)
        isTorchOn = target
        onComplete(
            CapabilityResult(
                success = true,
                capability = name,
                action = action,
                state = target,
                message = if (target) "Flashlight enabled" else "Flashlight disabled",
                executionMode = executionMode,
            ),
        )
    }
}
