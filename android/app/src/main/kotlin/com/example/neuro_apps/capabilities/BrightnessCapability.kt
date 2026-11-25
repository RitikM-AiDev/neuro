package com.example.neuro_apps.capabilities

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.example.neuro_apps.permissions.SpecialAccessType

class BrightnessCapability : DeviceCapability {
    override val name = "brightness"
    override val supportedActions = setOf("set", "get")
    override val executionMode = ExecutionMode.SPECIAL_ACCESS
    override val requirement = CapabilityRequirement(
        minSdk = Build.VERSION_CODES.M,
        specialAccess = SpecialAccessType.WRITE_SETTINGS,
    )

    override fun getState(context: Context): Any? =
        try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) {
            null
        }

    override fun execute(
        context: Context,
        action: String,
        parameters: Map<String, Any?>,
        onComplete: (CapabilityResult) -> Unit,
    ) {
        val rawLevel = parameters["level"] ?: parameters["brightness"] ?: parameters["value"]
        val level = (rawLevel as? Number)?.toInt() ?: rawLevel?.toString()?.toIntOrNull()

        if (level == null || level < 0 || level > 255) {
            onComplete(
                CapabilityResult(
                    success = false,
                    capability = name,
                    action = action,
                    state = getState(context),
                    message = "Invalid 'level' parameter; expected integer 0..255.",
                    executionMode = executionMode,
                    errorCode = "INVALID_PARAMETERS",
                ),
            )
            return
        }

        // Switch to manual mode so explicit brightness level takes effect immediately.
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        val updated = Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            level,
        )

        onComplete(
            CapabilityResult(
                success = updated,
                capability = name,
                action = action,
                state = getState(context) ?: level,
                message = if (updated) "Screen brightness set to $level." else "Failed to set brightness.",
                executionMode = executionMode,
                errorCode = if (updated) null else "WRITE_FAILED",
            ),
        )
    }
}
