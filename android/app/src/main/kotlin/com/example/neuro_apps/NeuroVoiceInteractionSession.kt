package com.example.neuro_apps

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Official Android VoiceInteractionSession for Neuro.
 *
 * Coordinates:
 * - Assistant window lifecycle.
 * - Google SpeechRecognizer command transcription.
 * - Delivery of transcripts to Flutter via AssistantBridge.
 * - Spoken response output via Android TextToSpeech.
 * - Clean return to low-power standby.
 */
class NeuroVoiceInteractionSession(context: Context) :
    VoiceInteractionSession(context),
    AssistantSpeechManager.Listener,
    TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "NeuroVoiceSession"
        private const val UTTERANCE_ID = "neuro_response_utterance"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var speechManager: AssistantSpeechManager
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingSpeechText: String? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "VOICE_INTERACTION_SESSION_CREATED")

        AssistantBridge.activeSession = this
        speechManager = AssistantSpeechManager(context)
        textToSpeech = TextToSpeech(context, this)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.d(TAG, "VOICE_INTERACTION_SESSION_SHOW (showFlags=$showFlags)")

        // Signal Flutter that recognition has started
        AssistantBridge.emitEvent(AssistantBridge.EVENT_RECOGNITION_STARTED)

        // Pause hotword detection and start Google SpeechRecognizer
        startListening()
    }

    fun startListening() {
        mainHandler.post {
            AssistantBridge.activeService?.pauseHotwordDetection()
            speechManager.startListening("auto", this)
        }
    }

    fun stopListening() {
        mainHandler.post {
            speechManager.stopListening()
        }
    }

    // ── SpeechRecognizer Callbacks ───────────────────────────────────────────

    override fun onRecognitionStarted() {
        Log.d(TAG, "SpeechRecognizer started in VoiceInteractionSession")
    }

    override fun onRmsChanged(rmsLevel: Float) {
        // Broadcast sound amplitude to Flutter UI
        AssistantBridge.emitEvent(
            AssistantBridge.EVENT_RECOGNITION_STARTED,
            mapOf("rms" to rmsLevel.toDouble())
        )
    }

    override fun onPartialResult(text: String, language: String?) {
        AssistantBridge.emitEvent(
            AssistantBridge.EVENT_PARTIAL_TRANSCRIPT,
            mapOf("text" to text, "language" to (language ?: "auto"))
        )
    }

    override fun onFinalResult(text: String, language: String?) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            Log.d(TAG, "Empty final result (silence); returning to standby")
            returnToStandby()
            return
        }

        Log.d(TAG, "Final transcript captured: '$trimmed' [$language]")

        // Emit final transcript to Flutter
        AssistantBridge.emitEvent(
            AssistantBridge.EVENT_FINAL_TRANSCRIPT,
            mapOf("text" to trimmed, "language" to (language ?: "auto"))
        )

        // Signal command processing by AI
        AssistantBridge.emitEvent(
            AssistantBridge.EVENT_COMMAND_PROCESSING,
            mapOf("command" to trimmed)
        )
    }

    override fun onLanguageDetected(language: String, confidence: Int) {
        Log.d(TAG, "Detected spoken language: $language (conf=$confidence)")
    }

    override fun onError(errorCode: Int, errorMessage: String, isRecoverable: Boolean) {
        Log.w(TAG, "Speech recognition error in session: code=$errorCode ($errorMessage)")

        AssistantBridge.emitEvent(
            AssistantBridge.EVENT_RECOGNITION_ERROR,
            mapOf("code" to errorCode, "message" to errorMessage, "recoverable" to isRecoverable)
        )

        // Return gracefully to standby on silence or terminal errors
        returnToStandby()
    }

    // ── Spoken Response Output (TTS) ─────────────────────────────────────────

    fun speakResponse(response: String) {
        mainHandler.post {
            Log.d(TAG, "Speaking response: '$response'")
            AssistantBridge.emitEvent(
                AssistantBridge.EVENT_RESPONSE_READY,
                mapOf("response" to response)
            )

            if (!isTtsReady || textToSpeech == null) {
                pendingSpeechText = response
                return@post
            }

            textToSpeech?.speak(
                response,
                TextToSpeech.QUEUE_FLUSH,
                null,
                UTTERANCE_ID
            )
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            textToSpeech?.language = Locale("en", "IN")
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS started speaking")
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS finished speaking; returning session to standby")
                    mainHandler.post { returnToStandby() }
                }

                override fun onError(utteranceId: String?) {
                    Log.w(TAG, "TTS playback error; returning session to standby")
                    mainHandler.post { returnToStandby() }
                }
            })

            pendingSpeechText?.let { text ->
                pendingSpeechText = null
                speakResponse(text)
            }
        } else {
            Log.w(TAG, "TextToSpeech initialization failed with status: $status")
            isTtsReady = false
        }
    }

    // ── Return to Standby Lifecycle ──────────────────────────────────────────

    fun returnToStandby() {
        mainHandler.post {
            Log.d(TAG, "RETURN_TO_STANDBY: Ending interaction session")
            speechManager.cancelAndRelease()

            AssistantBridge.emitEvent(AssistantBridge.EVENT_RETURNED_TO_STANDBY)

            // Resume wake-word detector on service
            AssistantBridge.activeService?.resumeHotwordDetection()

            hide()
        }
    }

    override fun onHide() {
        super.onHide()
        Log.d(TAG, "VOICE_INTERACTION_SESSION_HIDE")
        speechManager.cancelAndRelease()
        AssistantBridge.activeService?.resumeHotwordDetection()
    }

    override fun onDestroy() {
        Log.d(TAG, "VOICE_INTERACTION_SESSION_DESTROY")
        speechManager.cancelAndRelease()
        try { textToSpeech?.stop(); textToSpeech?.shutdown() } catch (_: Exception) {}
        textToSpeech = null
        if (AssistantBridge.activeSession == this) {
            AssistantBridge.activeSession = null
        }
        super.onDestroy()
    }
}
