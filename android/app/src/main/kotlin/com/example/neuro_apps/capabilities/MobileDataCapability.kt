package com.example.neuro_apps.capabilities

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager

class MobileDataCapability : DeviceCapability {
    override val name = "mobile_data"
    override val supportedActions = setOf("set", "toggle", "open_settings", "get")
    override val executionMode = ExecutionMode.USER_SETTINGS_UI
    override val requirement = CapabilityRequirement(minSdk = Build.VERSION_CODES.M)

    override fun getState(context: Context): Any? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                if (tm != null) {
                    return tm.isDataEnabled
                }
            }
            Settings.Global.getInt(context.contentResolver, "mobile_data", 0) == 1
        } catch (_: SecurityException) {
            try {
                Settings.Global.getInt(context.contentResolver, "mobile_data", 0) == 1
            } catch (_: Exception) {
                null
            }
        } catch (_: Exception) {
            null
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

        // On Android 10+, ACTION_INTERNET_CONNECTIVITY shows Mobile Data + Wi-Fi on Pixel/AOSP;
        // on Xiaomi/MIUI/HyperOS, ACTION_DATA_ROAMING_SETTINGS opens Mobile networks directly if needed.
        val panelAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Settings.Panel.ACTION_INTERNET_CONNECTIVITY
        } else {
            Settings.ACTION_DATA_ROAMING_SETTINGS
        }

        CapabilityHelpers.executePanelOrSettings(
            context = context,
            capability = name,
            action = action,
            accessibilityTarget = "internet",
            desiredOn = desired,
            panelAction = panelAction,
            fallbackAction = Settings.ACTION_DATA_ROAMING_SETTINGS,
            currentStateProvider = { getState(context) },
            onComplete = onComplete,
        )
    }
}
