package com.example.neuro_apps

import android.app.Activity
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.neuro_apps.dispatcher.ActionDispatcher
import com.example.neuro_apps.permissions.PermissionManager
import com.example.neuro_apps.registry.CapabilityRegistry
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.atomic.AtomicBoolean

object SystemChannelBridge {
    private const val TAG = "NeuroAccessibility"
    private const val CONTROL_CHANNEL = "com.example.neuro_apps/control"
    private const val SYSTEM_CHANNEL = "com.neuro/system"
    private const val OVERLAY_ENGINE_TAG = "myCachedEngine"
    private const val INSTAGRAM_PACKAGE = "com.instagram.android"

    private val capabilityRegistry: CapabilityRegistry by lazy {
        CapabilityRegistry.createDefault()
    }

    fun register(
        appContext: Context,
        messenger: BinaryMessenger,
        activityProvider: (() -> Activity?)? = null,
    ): MethodChannel {
        val controlChannel = MethodChannel(messenger, CONTROL_CHANNEL)
        controlChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "minimizeApp" -> {
                    activityProvider?.invoke()?.moveTaskToBack(true)
                    result.success(true)
                }
                "registerOverlayChannels" -> {
                    registerOverlayEngine(appContext)
                    result.success(true)
                }
                "searchProductOnFlipkart" -> {
                    val query = call.argument<String>("query")?.trim().orEmpty()
                    result.success(
                        if (query.isEmpty()) false else searchOnFlipkart(appContext, query),
                    )
                }
                "openInstagramReels" -> handleOpenInstagramReels(appContext, result)
                "openAccessibilitySettings" -> handleOpenAccessibilitySettings(appContext, result)
                "isAccessibilityEnabled" -> result.success(NeuroAccessibilityService.isServiceConnected())
                "search_web" -> {
                    val query = call.argument<String>("query")?.trim().orEmpty()
                    result.success(if (query.isEmpty()) false else searchWeb(appContext, query))
                }
                else -> result.notImplemented()
            }
        }

        val dispatcher = ActionDispatcher(
            registry = capabilityRegistry,
            permissionManager = PermissionManager(activityProvider),
        )

        MethodChannel(messenger, SYSTEM_CHANNEL).setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "executeDeviceAction" -> {
                        @Suppress("UNCHECKED_CAST")
                        val request = (call.arguments as? Map<String, Any?>) ?: emptyMap()
                        val replied = AtomicBoolean(false)
                        dispatcher.dispatch(appContext, request) { capResult ->
                            if (replied.compareAndSet(false, true)) {
                                result.success(capResult.toMap())
                            }
                        }
                    }
                    "listCapabilities" -> {
                        result.success(dispatcher.listCapabilities(appContext))
                    }
                    "internet" -> handlePanelToggle(
                        appContext = appContext,
                        target = "internet",
                        desiredOn = true,
                        panelAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                        } else {
                            Settings.ACTION_WIRELESS_SETTINGS
                        },
                        result = result,
                    )
                    "wifi" -> handlePanelToggle(
                        appContext = appContext,
                        target = "wifi",
                        desiredOn = true,
                        panelAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            Settings.Panel.ACTION_WIFI
                        } else {
                            Settings.ACTION_WIFI_SETTINGS
                        },
                        result = result,
                    )
                    "location" -> {
                        openSettingsAction(appContext, Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        result.success(true)
                    }
                    "bluetooth" -> {
                        startNewTask(appContext, Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        result.success(true)
                    }
                    "airplane" -> {
                        openSettingsAction(appContext, Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                        result.success(true)
                    }
                    "flashlight" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: false
                        result.success(setFlashlight(appContext, enabled))
                    }
                    "vibration" -> {
                        result.success(setRingerMode(appContext, AudioManager.RINGER_MODE_VIBRATE))
                    }
                    "silent" -> {
                        result.success(setRingerMode(appContext, AudioManager.RINGER_MODE_SILENT))
                    }
                    "normal" -> {
                        result.success(setRingerMode(appContext, AudioManager.RINGER_MODE_NORMAL))
                    }
                    "autoBrightness" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: true
                        result.success(setAutoBrightness(appContext, enabled))
                    }
                    "batterySaver" -> {
                        openSettingsAction(appContext, Settings.ACTION_BATTERY_SAVER_SETTINGS)
                        result.success(true)
                    }
                    "hotspot" -> {
                        openHotspotSettings(appContext)
                        result.success(true)
                    }
                    "screenshot" -> {
                        result.success(NeuroAccessibilityService.takeScreenshot())
                    }
                    "darkMode" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: true
                        handlePanelToggle(
                            appContext = appContext,
                            target = "dark_mode",
                            desiredOn = enabled,
                            panelAction = Settings.ACTION_DISPLAY_SETTINGS,
                            result = result,
                        )
                    }
                    else -> result.notImplemented()
                }
            } catch (e: Exception) {
                result.error("SYSTEM_ERROR", e.message, null)
            }
        }

        return controlChannel
    }

    fun registerOverlayEngine(appContext: Context) {
        val overlayEngine = FlutterEngineCache.getInstance().get(OVERLAY_ENGINE_TAG) ?: return
        register(appContext.applicationContext, overlayEngine.dartExecutor.binaryMessenger)
        Log.d(TAG, "Registered system & control channels on $OVERLAY_ENGINE_TAG.")
    }

    private fun handlePanelToggle(
        appContext: Context,
        target: String,
        desiredOn: Boolean,
        panelAction: String,
        result: MethodChannel.Result,
    ) {
        if (!NeuroAccessibilityService.isServiceConnected()) {
            result.success(openSettingsAction(appContext, panelAction))
            return
        }

        val replied = AtomicBoolean(false)
        NeuroAccessibilityService.requestPanelToggle(target, desiredOn) { success, message ->
            if (!replied.compareAndSet(false, true)) return@requestPanelToggle
            Log.d(TAG, "Panel toggle [$target -> $desiredOn]: success=$success ($message)")
            result.success(success)
        }

        if (!openSettingsAction(appContext, panelAction) && replied.compareAndSet(false, true)) {
            result.success(false)
        }
    }

    private fun setFlashlight(appContext: Context, enabled: Boolean): Boolean {
        return try {
            val cameraManager =
                appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull {
                cameraManager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            cameraManager.setTorchMode(cameraId, enabled)
            true
        } catch (e: Exception) {
            Log.e(TAG, "setFlashlight failed: ${e.message}", e)
            false
        }
    }

    private fun setRingerMode(appContext: Context, mode: Int): Boolean {
        val notificationManager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (!notificationManager.isNotificationPolicyAccessGranted) {
            startNewTask(
                appContext,
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
            )
            return false
        }

        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.ringerMode = mode
        return true
    }

    private fun setAutoBrightness(appContext: Context, enabled: Boolean): Boolean {
        if (!Settings.System.canWrite(appContext)) {
            startNewTask(
                appContext,
                Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:${appContext.packageName}"),
                ),
            )
            return false
        }

        val mode = if (enabled) {
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } else {
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        }

        return Settings.System.putInt(
            appContext.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            mode,
        )
    }

    private fun openHotspotSettings(appContext: Context) {
        try {
            startNewTask(appContext, Intent("android.settings.TETHER_SETTINGS"))
        } catch (_: Exception) {
            openSettingsAction(appContext, Settings.ACTION_WIRELESS_SETTINGS)
        }
    }

    private fun openSettingsAction(appContext: Context, action: String): Boolean {
        return try {
            startNewTask(appContext, Intent(action))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open $action: ${e.message}", e)
            false
        }
    }

    private fun startNewTask(appContext: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    private fun searchWeb(appContext: Context, query: String): Boolean {
        val uri = Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
        return try {
            startNewTask(appContext, Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (e: Exception) {
            Log.e(TAG, "search_web failed: ${e.message}", e)
            false
        }
    }

    private fun handleOpenInstagramReels(appContext: Context, result: MethodChannel.Result) {
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(INSTAGRAM_PACKAGE)
        if (launchIntent == null) {
            result.error("INSTAGRAM_NOT_INSTALLED", "Instagram is not installed on this device.", null)
            return
        }

        if (!NeuroAccessibilityService.isServiceConnected()) {
            result.error(
                "ACCESSIBILITY_NOT_ENABLED",
                "Please enable Neuro Assistant in Android Accessibility Settings.",
                null,
            )
            return
        }

        val replied = AtomicBoolean(false)
        NeuroAccessibilityService.requestReelsNavigation(timeoutMs = 12_000L) { success, message ->
            if (!replied.compareAndSet(false, true)) return@requestReelsNavigation
            if (success) {
                result.success(true)
            } else {
                result.error("REELS_NAVIGATION_FAILED", message, null)
            }
        }

        try {
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
            )
            appContext.startActivity(launchIntent)
        } catch (e: Exception) {
            if (replied.compareAndSet(false, true)) {
                result.error("INSTAGRAM_LAUNCH_FAILED", e.message ?: "Failed to launch Instagram.", null)
            }
        }
    }

    private fun handleOpenAccessibilitySettings(appContext: Context, result: MethodChannel.Result) {
        try {
            startNewTask(appContext, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            result.success(true)
        } catch (e: Exception) {
            result.error(
                "ACCESSIBILITY_SETTINGS_ERROR",
                e.message ?: "Could not open Accessibility Settings.",
                null,
            )
        }
    }

    private fun searchOnFlipkart(appContext: Context, query: String): Boolean {
        val searchUri = Uri.parse("https://www.flipkart.com/search?q=${Uri.encode(query)}")
        val appIntent = Intent(Intent.ACTION_VIEW, searchUri).apply {
            setPackage("com.flipkart.android")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(appIntent)
            true
        } catch (_: ActivityNotFoundException) {
            try {
                startNewTask(appContext, Intent(Intent.ACTION_VIEW, searchUri))
                true
            } catch (_: Exception) {
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}
