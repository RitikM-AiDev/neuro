package com.example.neuro_apps.capabilities

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings

class WifiCapability : DeviceCapability {
    override val name = "wifi"
    override val supportedActions = setOf("set", "toggle", "open_settings", "get")
    override val executionMode = ExecutionMode.USER_SETTINGS_UI
    override val requirement = CapabilityRequirement(minSdk = Build.VERSION_CODES.M)

    private fun wifiManager(context: Context): WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    override fun isAvailable(context: Context): Boolean =
        super.isAvailable(context) && wifiManager(context) != null

    override fun getState(context: Context): Any? =
        try {
            wifiManager(context)?.isWifiEnabled
        } catch (_: SecurityException) {
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

        val panelAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Settings.Panel.ACTION_WIFI
        } else {
            Settings.ACTION_WIFI_SETTINGS
        }

        CapabilityHelpers.executePanelOrSettings(
            context = context,
            capability = name,
            action = action,
            accessibilityTarget = "wifi",
            desiredOn = desired,
            panelAction = panelAction,
            fallbackAction = Settings.ACTION_WIFI_SETTINGS,
            currentStateProvider = { getState(context) },
            onComplete = onComplete,
        )
    }
}
