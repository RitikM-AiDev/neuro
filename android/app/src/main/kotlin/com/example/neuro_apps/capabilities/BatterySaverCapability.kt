package com.example.neuro_apps.capabilities

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

class BatterySaverCapability : DeviceCapability {
    override val name = "battery_saver"
    override val supportedActions = setOf("set", "toggle", "open_settings", "get")
    override val executionMode = ExecutionMode.USER_SETTINGS_UI
    override val requirement = CapabilityRequirement(minSdk = Build.VERSION_CODES.M)

    override fun getState(context: Context): Any? =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode

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
            accessibilityTarget = "battery_saver",
            desiredOn = desired,
            panelAction = Settings.ACTION_BATTERY_SAVER_SETTINGS,
            fallbackAction = Intent.ACTION_POWER_USAGE_SUMMARY,
            currentStateProvider = { getState(context) },
            onComplete = onComplete,
        )
    }
}
