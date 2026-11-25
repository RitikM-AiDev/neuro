package com.example.neuro_apps.capabilities

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.example.neuro_apps.permissions.SpecialAccessType

class DoNotDisturbCapability : DeviceCapability {
    override val name = "do_not_disturb"
    override val supportedActions = setOf("set", "toggle", "get")
    override val executionMode = ExecutionMode.SPECIAL_ACCESS
    override val requirement = CapabilityRequirement(
        minSdk = Build.VERSION_CODES.M,
        specialAccess = SpecialAccessType.NOTIFICATION_POLICY,
    )

    private fun nm(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun getState(context: Context): Any? =
        nm(context).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL

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

        val filter = if (enabled) {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY
        } else {
            NotificationManager.INTERRUPTION_FILTER_ALL
        }
        nm(context).setInterruptionFilter(filter)

        onComplete(
            CapabilityResult(
                success = true,
                capability = name,
                action = action,
                state = enabled,
                message = if (enabled) "Do Not Disturb enabled" else "Do Not Disturb disabled",
                executionMode = executionMode,
            ),
        )
    }
}
