package com.example.neuro_apps.capabilities

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings

class DarkModeCapability : DeviceCapability {
    override val name = "dark_mode"
    override val supportedActions = setOf("set", "toggle", "open_settings", "get")
    override val executionMode = ExecutionMode.USER_SETTINGS_UI
    override val requirement = CapabilityRequirement(minSdk = Build.VERSION_CODES.Q)

    override fun getState(context: Context): Any? {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return uiMode == Configuration.UI_MODE_NIGHT_YES
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
            accessibilityTarget = "dark_mode",
            desiredOn = desired,
            panelAction = Settings.ACTION_DISPLAY_SETTINGS,
            currentStateProvider = { getState(context) },
            onComplete = onComplete,
        )
    }
}
