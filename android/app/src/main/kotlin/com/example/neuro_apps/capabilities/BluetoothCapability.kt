package com.example.neuro_apps.capabilities

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

class BluetoothCapability : DeviceCapability {
    override val name = "bluetooth"
    override val supportedActions = setOf("set", "toggle", "open_settings", "get")
    override val executionMode = ExecutionMode.RUNTIME_PERMISSION
    override val requirement = CapabilityRequirement(
        minSdk = Build.VERSION_CODES.M,
        runtimePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyList()
        },
    )

    private fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    override fun isAvailable(context: Context): Boolean =
        super.isAvailable(context) && adapter(context) != null

    override fun getState(context: Context): Any? =
        try {
            adapter(context)?.isEnabled
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

        if (desired == true) {
            val launched = try {
                context.startActivity(
                    Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
                true
            } catch (_: Exception) {
                CapabilityHelpers.openSettingsIntent(context, Settings.ACTION_BLUETOOTH_SETTINGS)
            }
            onComplete(
                CapabilityResult(
                    success = launched,
                    capability = name,
                    action = action,
                    state = current,
                    message = if (launched) {
                        "Prompted user to enable Bluetooth."
                    } else {
                        "Failed to open Bluetooth enable dialog."
                    },
                    requiresUserAction = true,
                    executionMode = ExecutionMode.USER_SETTINGS_UI,
                    errorCode = if (launched) null else "BLUETOOTH_INTENT_FAILED",
                ),
            )
            return
        }

        // Android 13+ blocks BluetoothAdapter.disable(); route to Bluetooth settings.
        val opened = CapabilityHelpers.openSettingsIntent(context, Settings.ACTION_BLUETOOTH_SETTINGS)
        onComplete(
            CapabilityResult(
                success = opened,
                capability = name,
                action = action,
                state = current,
                message = if (opened) {
                    "Opened Bluetooth Settings (Android requires user confirmation to turn Bluetooth off)."
                } else {
                    "Failed to open Bluetooth Settings."
                },
                requiresUserAction = true,
                executionMode = ExecutionMode.USER_SETTINGS_UI,
                errorCode = if (opened) null else "SETTINGS_OPEN_FAILED",
            ),
        )
    }
}
