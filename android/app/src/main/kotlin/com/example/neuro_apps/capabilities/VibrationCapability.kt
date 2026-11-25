package com.example.neuro_apps.capabilities

import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.example.neuro_apps.permissions.SpecialAccessType

class VibrationCapability : DeviceCapability {
    override val name = "vibration"
    override val supportedActions = setOf("set", "toggle", "get")
    override val executionMode = ExecutionMode.SPECIAL_ACCESS
    override val requirement = CapabilityRequirement(
        minSdk = Build.VERSION_CODES.M,
        specialAccess = SpecialAccessType.NOTIFICATION_POLICY,
    )

    private fun audioManager(context: Context): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun getState(context: Context): Any? =
        audioManager(context).ringerMode == AudioManager.RINGER_MODE_VIBRATE

    override fun execute(
        context: Context,
        action: String,
        parameters: Map<String, Any?>,
        onComplete: (CapabilityResult) -> Unit,
    ) {
        val current = getState(context) as Boolean
        val enabled = when (action) {
            "toggle" -> !current
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

        val am = audioManager(context)
        am.ringerMode = if (enabled) {
            AudioManager.RINGER_MODE_VIBRATE
        } else {
            AudioManager.RINGER_MODE_NORMAL
        }

        onComplete(
            CapabilityResult(
                success = true,
                capability = name,
                action = action,
                state = enabled,
                message = if (enabled) "Vibration mode enabled" else "Normal ringer mode restored",
                executionMode = executionMode,
            ),
        )
    }
}
