package com.example.neuro_apps.capabilities

import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.provider.Settings

class LocationCapability : DeviceCapability {
    override val name = "location"
    override val supportedActions = setOf("set", "toggle", "open_settings", "get")
    override val executionMode = ExecutionMode.USER_SETTINGS_UI
    override val requirement = CapabilityRequirement(minSdk = Build.VERSION_CODES.M)

    override fun getState(context: Context): Any? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
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
            accessibilityTarget = "location",
            desiredOn = desired,
            panelAction = Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            currentStateProvider = { getState(context) },
            onComplete = onComplete,
        )
    }
}
