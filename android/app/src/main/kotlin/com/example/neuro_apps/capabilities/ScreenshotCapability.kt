package com.example.neuro_apps.capabilities

import android.content.Context
import android.os.Build
import com.example.neuro_apps.NeuroAccessibilityService
import com.example.neuro_apps.permissions.SpecialAccessType

class ScreenshotCapability : DeviceCapability {
    override val name = "screenshot"
    override val supportedActions = setOf("capture", "trigger")
    override val executionMode = ExecutionMode.SPECIAL_ACCESS
    override val requirement = CapabilityRequirement(
        minSdk = Build.VERSION_CODES.P,
        specialAccess = SpecialAccessType.ACCESSIBILITY_SERVICE,
    )

    override fun execute(
        context: Context,
        action: String,
        parameters: Map<String, Any?>,
        onComplete: (CapabilityResult) -> Unit,
    ) {
        val captured = NeuroAccessibilityService.takeScreenshot()
        onComplete(
            CapabilityResult(
                success = captured,
                capability = name,
                action = action,
                state = captured,
                message = if (captured) {
                    "Screenshot captured via Neuro Accessibility Service."
                } else {
                    "Screenshot action failed."
                },
                executionMode = executionMode,
                errorCode = if (captured) null else "SCREENSHOT_FAILED",
            ),
        )
    }
}
