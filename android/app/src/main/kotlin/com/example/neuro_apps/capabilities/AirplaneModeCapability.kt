package com.example.neuro_apps.capabilities

import android.content.Context
import android.os.Build
import android.provider.Settings

class AirplaneModeCapability : DeviceCapability {
    override val name = "airplane_mode"
    override val supportedActions = setOf("set", "toggle", "open_settings", "get")
    override val executionMode = ExecutionMode.USER_SETTINGS_UI
    override val requirement = CapabilityRequirement(minSdk = Build.VERSION_CODES.M)

    override fun getState(context: Context): Any? =
        try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                0,
            ) == 1
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
        val desired = when (action) {
            "open_settings" -> null
            "toggle" -> current?.let { !it } ?: true
            else -> CapabilityHelpers.parseEnabled(parameters, current)
        }

        CapabilityHelpers.executePanelOrSettings(
            context = context,
            capability = name,
            action = action,
            accessibilityTarget = "airplane_mode",
            desiredOn = desired,
            panelAction = Settings.ACTION_AIRPLANE_MODE_SETTINGS,
            fallbackAction = Settings.ACTION_WIRELESS_SETTINGS,
            currentStateProvider = { getState(context) },
            onComplete = onComplete,
        )
    }
}
