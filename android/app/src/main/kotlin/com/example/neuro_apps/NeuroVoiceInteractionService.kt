package com.example.neuro_apps

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.util.Log

/**
 * Official Android VoiceInteractionService for Neuro Assistant.
 *
 * Registered as the system voice interaction service, managing low-power
 * wake-word detection standby and coordinating assistant sessions.
 */
class NeuroVoiceInteractionService : VoiceInteractionService() {

    companion object {
        private const val TAG = "NeuroVoiceService"
    }

    private var hotwordManager: NeuroHotwordManager? = null

    override fun onReady() {
        super.onReady()
        Log.i(TAG, "VOICE_INTERACTION_SERVICE_READY")

        AssistantBridge.activeService = this

        hotwordManager = NeuroHotwordManager(this, this).apply {
            init(object : NeuroHotwordManager.Callback {
                override fun onHotwordDetected(confidence: Float) {
                    Log.i(TAG, "WAKE_WORD_ACTIVATED: 'Hey Neuro' detected (confidence=$confidence)")

                    // Emit event to Flutter
                    AssistantBridge.emitEvent(
                        AssistantBridge.EVENT_HOTWORD_DETECTED,
                        mapOf("confidence" to confidence.toDouble())
                    )

                    // Start official VoiceInteractionSession
                    startAssistantInteraction(Bundle())
                }

                override fun onHotwordAvailabilityChanged(isDspSupported: Boolean, statusMessage: String) {
                    Log.d(TAG, "Hotword detector status: DSP supported=$isDspSupported ($statusMessage)")
                }
            })

            startStandbyListening()
        }
    }

    /**
     * Programmatically triggers a voice interaction session (e.g. from UI, button, or wake-word).
     */
    fun startAssistantInteraction(args: Bundle) {
        Log.d(TAG, "Starting assistant interaction session via showSession()")
        try {
            showSession(args, VoiceInteractionSession.SHOW_SOURCE_ASSIST_GESTURE)
        } catch (e: Exception) {
            Log.e(TAG, "showSession failed", e)
        }
    }

    fun pauseHotwordDetection() {
        hotwordManager?.pauseForSpeechRecognition()
    }

    fun resumeHotwordDetection() {
        hotwordManager?.resumeAfterSpeechRecognition()
    }

    override fun onShutdown() {
        Log.i(TAG, "VOICE_INTERACTION_SERVICE_SHUTDOWN")
        hotwordManager?.destroy()
        hotwordManager = null
        if (AssistantBridge.activeService == this) {
            AssistantBridge.activeService = null
        }
        super.onShutdown()
    }
}
