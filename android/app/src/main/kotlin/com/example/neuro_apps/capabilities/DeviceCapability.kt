package com.example.neuro_apps.capabilities

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.neuro_apps.NeuroAccessibilityService
import com.example.neuro_apps.permissions.SpecialAccessType
import java.util.concurrent.atomic.AtomicBoolean

enum class ExecutionMode(val wireName: String) {
    DIRECT("direct"),
    RUNTIME_PERMISSION("runtime_permission"),
    SPECIAL_ACCESS("special_access"),
    USER_SETTINGS_UI("user_settings_ui"),
    PRIVILEGED_ONLY("privileged_only"),
}

data class CapabilityRequirement(
    val minSdk: Int = Build.VERSION_CODES.M,
    val runtimePermissions: List<String> = emptyList(),
    val specialAccess: SpecialAccessType? = null,
)

data class CapabilityResult(
    val success: Boolean,
    val capability: String,
    val action: String,
    val state: Any? = null,
    val message: String,
    val requiresUserAction: Boolean = false,
    val executionMode: ExecutionMode,
    val errorCode: String? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "success" to success,
        "capability" to capability,
        "action" to action,
        "state" to state,
        "message" to message,
        "requires_user_action" to requiresUserAction,
        "execution_mode" to executionMode.wireName,
        "error_code" to errorCode,
    )
}

interface DeviceCapability {
    val name: String
    val supportedActions: Set<String>
    val executionMode: ExecutionMode
    val requirement: CapabilityRequirement

    fun isAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= requirement.minSdk

    fun getState(context: Context): Any? = null

    fun execute(
        context: Context,
        action: String,
        parameters: Map<String, Any?>,
        onComplete: (CapabilityResult) -> Unit,
    )
}

internal object CapabilityHelpers {
    private const val TAG = "NeuroCapabilities"

    fun parseEnabled(parameters: Map<String, Any?>, currentState: Boolean? = null): Boolean? {
        val raw = parameters["enabled"] ?: parameters["state"] ?: parameters["status"]
        return when (raw) {
            is Boolean -> raw
            is String -> when (raw.trim().lowercase()) {
                "true", "on", "1", "enable", "enabled" -> true
                "false", "off", "0", "disable", "disabled" -> false
                "toggle" -> currentState?.let { !it }
                else -> null
            }
            is Number -> raw.toInt() != 0
            null -> null
            else -> null
        }
    }

    fun openSettingsIntent(context: Context, action: String): Boolean {
        return try {
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open settings [$action]: ${e.message}", e)
            false
        }
    }

    fun executePanelOrSettings(
        context: Context,
        capability: String,
        action: String,
        accessibilityTarget: String?,
        desiredOn: Boolean?,
        panelAction: String,
        fallbackAction: String? = null,
        currentStateProvider: () -> Any? = { null },
        onComplete: (CapabilityResult) -> Unit,
    ) {
        if (accessibilityTarget != null &&
            desiredOn != null &&
            NeuroAccessibilityService.isServiceConnected()
        ) {
            val replied = AtomicBoolean(false)
            NeuroAccessibilityService.requestPanelToggle(
                target = accessibilityTarget,
                desiredOn = desiredOn,
            ) { success, message ->
                if (!replied.compareAndSet(false, true)) return@requestPanelToggle
                onComplete(
                    CapabilityResult(
                        success = success,
                        capability = capability,
                        action = action,
                        state = currentStateProvider() ?: desiredOn,
                        message = message,
                        requiresUserAction = !success,
                        executionMode = ExecutionMode.USER_SETTINGS_UI,
                        errorCode = if (success) null else "ACCESSIBILITY_PANEL_FAILED",
                    ),
                )
            }

            val opened = openSettingsIntent(context, panelAction) ||
                (fallbackAction != null && openSettingsIntent(context, fallbackAction))
            if (!opened && replied.compareAndSet(false, true)) {
                onComplete(
                    CapabilityResult(
                        success = false,
                        capability = capability,
                        action = action,
                        state = currentStateProvider(),
                        message = "Unable to open Android Settings panel.",
                        requiresUserAction = true,
                        executionMode = ExecutionMode.USER_SETTINGS_UI,
                        errorCode = "SETTINGS_OPEN_FAILED",
                    ),
                )
            }
            return
        }

        val opened = openSettingsIntent(context, panelAction) ||
            (fallbackAction != null && openSettingsIntent(context, fallbackAction))

        onComplete(
            CapabilityResult(
                success = opened,
                capability = capability,
                action = action,
                state = currentStateProvider(),
                message = if (opened) {
                    "Opened system settings for $capability (manual confirmation required on Android)."
                } else {
                    "Unable to open system settings for $capability."
                },
                requiresUserAction = true,
                executionMode = ExecutionMode.USER_SETTINGS_UI,
                errorCode = if (opened) null else "SETTINGS_OPEN_FAILED",
            ),
        )
    }
}
