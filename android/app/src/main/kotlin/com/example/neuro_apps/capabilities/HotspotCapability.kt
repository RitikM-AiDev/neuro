package com.example.neuro_apps.capabilities

import android.content.Context
import android.os.Build
import android.provider.Settings

class HotspotCapability : DeviceCapability {
    override val name = "hotspot"
    override val supportedActions = setOf("set", "toggle", "open_settings")
    override val executionMode = ExecutionMode.USER_SETTINGS_UI
    override val requirement = CapabilityRequirement(minSdk = Build.VERSION_CODES.M)

    override fun execute(
        context: Context,
        action: String,
        parameters: Map<String, Any?>,
        onComplete: (CapabilityResult) -> Unit,
    ) {
        val desired = when (action) {
            "open_settings" -> null
            "toggle" -> true
            else -> CapabilityHelpers.parseEnabled(parameters)
        }

        CapabilityHelpers.executePanelOrSettings(
            context = context,
            capability = name,
            action = action,
            accessibilityTarget = "hotspot",
            desiredOn = desired,
            panelAction = "android.settings.TETHER_SETTINGS",
            fallbackAction = Settings.ACTION_WIRELESS_SETTINGS,
            onComplete = onComplete,
        )
    }
}
