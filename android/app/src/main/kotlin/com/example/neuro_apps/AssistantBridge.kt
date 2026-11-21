package com.example.neuro_apps

import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.util.Log
import io.flutter.plugin.common.EventChannel

/**
 * Thread-safe native bridge connecting Android VoiceInteractionService,
 * VoiceInteractionSession, Google SpeechRecognizer, and Flutter.
 */
object AssistantBridge {
    private const val TAG = "NeuroAssistantBridge"

    const val METHOD_CHANNEL = "com.example.neuro_apps/assistant"
    const val EVENT_CHANNEL = "com.example.neuro_apps/assistant_events"

    // Events defined in specifications
    const val EVENT_HOTWORD_DETECTED = "HOTWORD_DETECTED"
    const val EVENT_RECOGNITION_STARTED = "RECOGNITION_STARTED"
    const val EVENT_PARTIAL_TRANSCRIPT = "PARTIAL_TRANSCRIPT"
    const val EVENT_FINAL_TRANSCRIPT = "FINAL_TRANSCRIPT"
    const val EVENT_COMMAND_PROCESSING = "COMMAND_PROCESSING"
    const val EVENT_RESPONSE_READY = "RESPONSE_READY"
    const val EVENT_RETURNED_TO_STANDBY = "RETURNED_TO_STANDBY"
    const val EVENT_RECOGNITION_ERROR = "RECOGNITION_ERROR"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var eventSink: EventChannel.EventSink? = null

    @Volatile
    var activeService: NeuroVoiceInteractionService? = null

    @Volatile
    var activeSession: NeuroVoiceInteractionSession? = null

    fun setEventSink(sink: EventChannel.EventSink?) {
        this.eventSink = sink
        Log.d(TAG, "AssistantBridge eventSink updated: ${sink != null}")
    }

    fun emitEvent(type: String, data: Map<String, Any?> = emptyMap()) {
        val payload = HashMap<String, Any?>(data).apply {
            put("type", type)
            put("timestamp", System.currentTimeMillis())
        }

        Log.d(TAG, "EMIT [$type]: $payload")

        mainHandler.post {
            try {
                eventSink?.success(payload)
            } catch (e: Exception) {
                Log.w(TAG, "Error emitting event $type to Flutter", e)
            }
        }
    }

    /**
     * Checks if Neuro is currently selected as the active/default assistant on Android.
     */
    fun isDefaultAssistant(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) ?: false
        } else {
            VoiceInteractionService.isActiveService(
                context,
                ComponentName(context, NeuroVoiceInteractionService::class.java)
            )
        }
    }

    /**
     * Triggers the official Android system assistant selection screen.
     */
    fun requestAssistantRole(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = activity.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                if (!roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
                    activity.startActivityForResult(intent, 4001)
                    return
                }
            }
        }

        // Fallback to voice input settings
        try {
            val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch assistant settings", e)
            }
        }
    }

    /**
     * Programmatically triggers a voice interaction session.
     */
    fun triggerVoiceInteraction(args: android.os.Bundle = android.os.Bundle()) {
        val service = activeService
        if (service != null) {
            Log.d(TAG, "triggerVoiceInteraction called on active VoiceInteractionService")
            service.startAssistantInteraction(args)
        } else {
            Log.w(TAG, "VoiceInteractionService is not active; attempting fallback session start")
            activeSession?.startListening()
        }
    }

    /**
     * Stops the active voice interaction session.
     */
    fun stopVoiceInteraction() {
        activeSession?.stopListening()
    }
}
