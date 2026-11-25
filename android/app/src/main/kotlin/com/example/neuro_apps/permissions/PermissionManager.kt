package com.example.neuro_apps.permissions

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.neuro_apps.NeuroAccessibilityService
import com.example.neuro_apps.capabilities.CapabilityResult
import com.example.neuro_apps.capabilities.DeviceCapability

enum class SpecialAccessType {
    WRITE_SETTINGS,
    NOTIFICATION_POLICY,
    ACCESSIBILITY_SERVICE,
}

data class PermissionCheckResult(
    val authorized: Boolean,
    val blockedResult: CapabilityResult? = null,
)

class PermissionManager(
    private val activityProvider: (() -> Activity?)? = null,
) {
    fun ensureAuthorized(
        context: Context,
        capability: DeviceCapability,
        action: String,
    ): PermissionCheckResult {
        val req = capability.requirement

        // 1. Check Android version.
        if (Build.VERSION.SDK_INT < req.minSdk) {
            return PermissionCheckResult(
                authorized = false,
                blockedResult = CapabilityResult(
                    success = false,
                    capability = capability.name,
                    action = action,
                    message = "${capability.name} requires Android SDK ${req.minSdk} (current: ${Build.VERSION.SDK_INT}).",
                    requiresUserAction = false,
                    executionMode = capability.executionMode,
                    errorCode = "UNSUPPORTED_ANDROID_VERSION",
                ),
            )
        }

        // 2. Check hardware/feature availability.
        if (!capability.isAvailable(context)) {
            return PermissionCheckResult(
                authorized = false,
                blockedResult = CapabilityResult(
                    success = false,
                    capability = capability.name,
                    action = action,
                    message = "Capability '${capability.name}' is not supported on this device.",
                    requiresUserAction = false,
                    executionMode = capability.executionMode,
                    errorCode = "CAPABILITY_UNAVAILABLE",
                ),
            )
        }

        // 3. Check runtime permissions (never re-request already granted permissions).
        val missingPermissions = req.runtimePermissions.filter { perm ->
            ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            val activity = activityProvider?.invoke()
            if (activity != null) {
                ActivityCompat.requestPermissions(
                    activity,
                    missingPermissions.toTypedArray(),
                    RUNTIME_PERMISSION_REQUEST_CODE,
                )
            }
            Log.w(TAG, "Missing runtime permissions for ${capability.name}: $missingPermissions")
            return PermissionCheckResult(
                authorized = false,
                blockedResult = CapabilityResult(
                    success = false,
                    capability = capability.name,
                    action = action,
                    message = "Runtime permission required: ${missingPermissions.joinToString(", ")}.",
                    requiresUserAction = true,
                    executionMode = capability.executionMode,
                    errorCode = "PERMISSION_REQUIRED",
                ),
            )
        }

        // 4. Check special app access.
        when (req.specialAccess) {
            SpecialAccessType.WRITE_SETTINGS -> {
                if (!Settings.System.canWrite(context)) {
                    openSpecialAccessSettings(
                        context,
                        Intent(
                            Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                    return PermissionCheckResult(
                        authorized = false,
                        blockedResult = CapabilityResult(
                            success = false,
                            capability = capability.name,
                            action = action,
                            message = "Modify System Settings access required. Opened permission screen.",
                            requiresUserAction = true,
                            executionMode = capability.executionMode,
                            errorCode = "WRITE_SETTINGS_REQUIRED",
                        ),
                    )
                }
            }
            SpecialAccessType.NOTIFICATION_POLICY -> {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (!nm.isNotificationPolicyAccessGranted) {
                    openSpecialAccessSettings(
                        context,
                        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
                    )
                    return PermissionCheckResult(
                        authorized = false,
                        blockedResult = CapabilityResult(
                            success = false,
                            capability = capability.name,
                            action = action,
                            message = "Do Not Disturb policy access required. Opened permission screen.",
                            requiresUserAction = true,
                            executionMode = capability.executionMode,
                            errorCode = "DND_POLICY_REQUIRED",
                        ),
                    )
                }
            }
            SpecialAccessType.ACCESSIBILITY_SERVICE -> {
                if (!NeuroAccessibilityService.isServiceConnected()) {
                    openSpecialAccessSettings(
                        context,
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                    )
                    return PermissionCheckResult(
                        authorized = false,
                        blockedResult = CapabilityResult(
                            success = false,
                            capability = capability.name,
                            action = action,
                            message = "Neuro Assistant Accessibility Service must be enabled.",
                            requiresUserAction = true,
                            executionMode = capability.executionMode,
                            errorCode = "ACCESSIBILITY_REQUIRED",
                        ),
                    )
                }
            }
            null -> Unit
        }

        return PermissionCheckResult(authorized = true)
    }

    private fun openSpecialAccessSettings(context: Context, intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open special access settings: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "NeuroPermissions"
        private const val RUNTIME_PERMISSION_REQUEST_CODE = 2001
    }
}
