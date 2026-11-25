package com.example.neuro_apps.capabilities

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.example.neuro_apps.permissions.SpecialAccessType

class ScreenTimeoutCapability : DeviceCapability {
    override val name = "screen_timeout"
    override val supportedActions = setOf("set", "get")
    override val executionMode = ExecutionMode.SPECIAL_ACCESS
    override val requirement = CapabilityRequirement(
        minSdk = Build.VERSION_CODES.M,
        specialAccess = SpecialAccessType.WRITE_SETTINGS,
    )

    override fun getState(context: Context): Any? =
        try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
        } catch (_: Exception) {
            null
        }

    override fun execute(
        context: Context,
        action: String,
        parameters: Map<String, Any?>,
        onComplete: (CapabilityResult) -> Unit,
    ) {
        val msParam = parameters["milliseconds"] ?: parameters["timeout_ms"]
        val secParam = parameters["seconds"]
        val timeoutMs = when {
            msParam != null -> (msParam as? Number)?.toInt() ?: msParam.toString().toIntOrNull()
            secParam != null -> ((secParam as? Number)?.toInt() ?: secParam.toString().toIntOrNull())?.times(1000)
            else -> null
        }

        if (timeoutMs == null || timeoutMs < 5000 || timeoutMs > 1_800_000) {
            onComplete(
                CapabilityResult(
                    success = false,
                    capability = name,
                    action = action,
                    state = getState(context),
                    message = "Invalid timeout; provide 'milliseconds' (5000..1800000) or 'seconds' (5..1800).",
                    executionMode = executionMode,
                    errorCode = "INVALID_PARAMETERS",
                ),
            )
            return
        }

        val updated = Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_OFF_TIMEOUT,
            timeoutMs,
        )

        onComplete(
            CapabilityResult(
                success = updated,
                capability = name,
                action = action,
                state = getState(context) ?: timeoutMs,
                message = if (updated) {
                    "Screen timeout set to ${timeoutMs / 1000}s."
                } else {
                    "Failed to update screen timeout."
                },
                executionMode = executionMode,
                errorCode = if (updated) null else "WRITE_FAILED",
            ),
        )
    }
}
