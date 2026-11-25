package com.example.neuro_apps.capabilities

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.example.neuro_apps.permissions.SpecialAccessType

class AutoBrightnessCapability : DeviceCapability {
    override val name = "auto_brightness"
    override val supportedActions = setOf("set", "toggle", "get")
    override val executionMode = ExecutionMode.SPECIAL_ACCESS
    override val requirement = CapabilityRequirement(
        minSdk = Build.VERSION_CODES.M,
        specialAccess = SpecialAccessType.WRITE_SETTINGS,
    )

    override fun getState(context: Context): Any? =
        try {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
            ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } catch (_: Exception) {
            null
        }

    override fun execute(
        context: Context,
        action: String,
        parameters: Map<String, Any?>,
        onComplete: (CapabilityResult) -> Unit,
    ) {
        val current = getState(context) as? Boolean
        val enabled = when (action) {
            "toggle" -> current?.let { !it } ?: true
            else -> CapabilityHelpers.parseEnabled(parameters, current)
        }

        if (enabled == null) {
            onComplete(
                CapabilityResult(
                    success = false,
                    capability = name,
                    action = action,
                    state = current,
                    message = "Missing or invalid 'enabled' parameter.",
                    executionMode = executionMode,
                    errorCode = "INVALID_PARAMETERS",
                ),
            )
            return
        }

        val mode = if (enabled) {
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } else {
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        }

        val updated = Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            mode,
        )

        onComplete(
            CapabilityResult(
                success = updated,
                capability = name,
                action = action,
                state = enabled,
                message = if (updated) {
                    if (enabled) "Auto-brightness enabled" else "Auto-brightness disabled"
                } else {
                    "Failed to update auto-brightness."
                },
                executionMode = executionMode,
                errorCode = if (updated) null else "WRITE_FAILED",
            ),
        )
    }
}
