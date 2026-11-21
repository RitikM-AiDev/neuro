package com.example.neuro_apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Dedicated Google SpeechRecognizer session manager for Neuro Assistant.
 *
 * Guarantees:
 * - Single recognizer owner.
 * - Single active session at any given time.
 * - Zero microphone overlap with wake-word detection.
 * - Multilingual support for Tamil (ta-IN), English (en-IN), and Tanglish.
 */
class AssistantSpeechManager(private val context: Context) : RecognitionListener {

    companion object {
        private const val TAG = "NeuroSpeechManager"

        private val SUPPORTED_LANGUAGES = arrayListOf(
            "en-IN", // English (India)
            "ta-IN", // Tamil
            "en-US", // English (US)
            "hi-IN", // Hindi
            "te-IN", // Telugu
            "ml-IN", // Malayalam
            "kn-IN"  // Kannada
        )

        private const val DEFAULT_LANGUAGE = "en-IN"

        private val RECOVERABLE_ERRORS = setOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY
        )
    }

    interface Listener {
        fun onRecognitionStarted()
        fun onRmsChanged(rmsLevel: Float)
        fun onPartialResult(text: String, language: String?)
        fun onFinalResult(text: String, language: String?)
        fun onLanguageDetected(language: String, confidence: Int)
        fun onError(errorCode: Int, errorMessage: String, isRecoverable: Boolean)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var listener: Listener? = null

    @Volatile
    private var isSessionActive = false
    private var activeLanguage = "auto"
    private var lastDetectedLanguage: String? = null
    private var providerIndex = 0

    val isListening: Boolean
        get() = isSessionActive

    /**
     * Starts a speech recognition session safely on the main thread.
     */
    fun startListening(language: String = "auto", sessionListener: Listener) {
        mainHandler.post {
            if (isSessionActive) {
                Log.w(TAG, "Speech recognition session already active; ignoring duplicate startListening()")
                return@post
            }

            this.listener = sessionListener
            this.activeLanguage = language
            this.lastDetectedLanguage = null

            if (!ensureRecognizer()) {
                sessionListener.onError(
                    SpeechRecognizer.ERROR_CLIENT,
                    "Speech recognition service unavailable on device",
                    false
                )
                return@post
            }

            try {
                isSessionActive = true
                val intent = buildRecognizerIntent()
                Log.d(TAG, "SPEECH_RECOGNITION_SESSION_START: Requesting recognition (lang=$activeLanguage)")
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                isSessionActive = false
                Log.e(TAG, "Failed to start SpeechRecognizer", e)
                sessionListener.onError(
                    SpeechRecognizer.ERROR_CLIENT,
                    "Failed to start recognition: ${e.message}",
                    true
                )
            }
        }
    }

    /**
     * Gracefully stops accepting speech input and starts finalizing results.
     */
    fun stopListening() {
        mainHandler.post {
            if (!isSessionActive) return@post
            try {
                speechRecognizer?.stopListening()
                Log.d(TAG, "SpeechRecognizer.stopListening called")
            } catch (e: Exception) {
                Log.w(TAG, "Error in stopListening", e)
            }
        }
    }

    /**
     * Cancels the active session and fully releases the microphone hardware.
     */
    fun cancelAndRelease() {
        mainHandler.post {
            isSessionActive = false
            listener = null
            try {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                Log.d(TAG, "SpeechRecognizer destroyed and microphone hardware released")
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing SpeechRecognizer", e)
            } finally {
                speechRecognizer = null
            }
        }
    }

    // ── Internal Recognizer Creation & Provider Rotation ─────────────────────

    private fun ensureRecognizer(): Boolean {
        if (speechRecognizer != null) return true

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "SpeechRecognizer.isRecognitionAvailable returned false")
            return false
        }

        // Query available RecognitionServices on device
        val pm = context.packageManager
        val recognitionIntent = Intent(RecognitionService.SERVICE_INTERFACE)
        val availableServices = try {
            pm.queryIntentServices(recognitionIntent, 0)
        } catch (e: Exception) {
            emptyList()
        }

        Log.d(TAG, "Found ${availableServices.size} available RecognitionService(s)")
        for (ri in availableServices) {
            Log.d(TAG, "  -> ${ri.serviceInfo.packageName} / ${ri.serviceInfo.name}")
        }

        // 1. Try on-device recognition if supported (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            try {
                Log.d(TAG, "Creating On-Device SpeechRecognizer")
                val onDevice = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                if (onDevice != null) {
                    onDevice.setRecognitionListener(this)
                    speechRecognizer = onDevice
                    Log.d(TAG, "Bound to On-Device SpeechRecognizer")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "createOnDeviceSpeechRecognizer failed", e)
            }
        }

        // 2. Try Google App (QuickSearchBox) recognition service if installed
        val googleService = availableServices.firstOrNull {
            it.serviceInfo.packageName == "com.google.android.googlequicksearchbox"
        }
        if (googleService != null) {
            val comp = ComponentName(googleService.serviceInfo.packageName, googleService.serviceInfo.name)
            try {
                Log.d(TAG, "Creating SpeechRecognizer with Google App component: ${comp.flattenToShortString()}")
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context, comp)
                if (recognizer != null) {
                    recognizer.setRecognitionListener(this)
                    speechRecognizer = recognizer
                    Log.d(TAG, "Bound SpeechRecognizer to Google App")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed binding to Google App", e)
            }
        }

        // 3. Fallback to default system SpeechRecognizer (routes to OS preferred voice recognition provider)
        return try {
            Log.d(TAG, "Creating default system SpeechRecognizer")
            val defaultRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            defaultRecognizer.setRecognitionListener(this)
            speechRecognizer = defaultRecognizer
            Log.d(TAG, "Bound to default system SpeechRecognizer")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to create default system SpeechRecognizer", e)
            false
        }
    }

    private fun buildRecognizerIntent(): Intent {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

            // Natural conversational silence windows
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
        }

        val isAuto = activeLanguage.isEmpty() || activeLanguage.equals("auto", ignoreCase = true)

        if (!isAuto) {
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, activeLanguage)
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, activeLanguage)
            Log.d(TAG, "SpeechRecognizer configured for explicit language: $activeLanguage")
        } else {
            Log.d(TAG, "SpeechRecognizer configured for multilingual Tanglish (English + Tamil auto)")
            // ALWAYS set primary language so the recognizer engine knows its base acoustic model
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, DEFAULT_LANGUAGE)
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, DEFAULT_LANGUAGE)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Official Android 14+ (API 34+) automatic language detection & switching
                intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, true)
                intent.putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                    SUPPORTED_LANGUAGES
                )
            } else {
                // Fallback for older Android versions
                intent.putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("ta-IN", "hi-IN"))
            }
        }

        return intent
    }

    // ── RecognitionListener Callbacks ────────────────────────────────────────

    override fun onReadyForSpeech(params: Bundle?) {
        if (!isSessionActive) return
        Log.d(TAG, "RECOGNITION_READY: Microphone open, ready for speech input")
        listener?.onRecognitionStarted()
    }

    override fun onBeginningOfSpeech() {
        Log.d(TAG, "RECOGNITION_SPEECH_BEGINNING")
    }

    override fun onRmsChanged(rmsdB: Float) {
        if (!isSessionActive) return
        val normalized = ((rmsdB + 2.0f) / 10.0f).coerceIn(0.0f, 1.0f)
        listener?.onRmsChanged(normalized)
    }

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        Log.d(TAG, "RECOGNITION_END_OF_SPEECH: User finished speaking")
    }

    override fun onPartialResults(partialResults: Bundle?) {
        if (!isSessionActive) return
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull()?.trim() ?: return

        val lang = extractDetectedLanguage(partialResults) ?: lastDetectedLanguage

        if (text.isNotEmpty()) {
            Log.d(TAG, "PARTIAL_TRANSCRIPT [$lang]: '$text'")
            listener?.onPartialResult(text, lang)
        }
    }

    override fun onResults(results: Bundle?) {
        isSessionActive = false
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull()?.trim().orEmpty()
        val lang = extractDetectedLanguage(results) ?: lastDetectedLanguage

        Log.d(TAG, "FINAL_TRANSCRIPT [$lang]: '$text'")
        listener?.onFinalResult(text, lang)
    }

    override fun onError(error: Int) {
        isSessionActive = false
        val message = getErrorMessage(error)
        val isRecoverable = error in RECOVERABLE_ERRORS

        Log.w(TAG, "RECOGNITION_ERROR code=$error ($message), recoverable=$isRecoverable")

        // Clean up recognizer instance on error so subsequent requests create a fresh session
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        speechRecognizer = null

        listener?.onError(error, message, isRecoverable)
    }

    override fun onEvent(eventType: Int, params: Bundle?) {
        if (params != null) {
            extractDetectedLanguage(params)?.let { lang ->
                lastDetectedLanguage = lang
                listener?.onLanguageDetected(lang, -1)
            }
        }
    }

    override fun onLanguageDetection(results: Bundle) {
        extractDetectedLanguage(results)?.let { lang ->
            lastDetectedLanguage = lang
            val confidence = results.getInt("language_detection_confidence_level", -1)
            Log.d(TAG, "LANGUAGE_DETECTED: $lang (confidence=$confidence)")
            listener?.onLanguageDetected(lang, confidence)
        }
    }

    private fun extractDetectedLanguage(bundle: Bundle?): String? {
        if (bundle == null) return null
        return bundle.getString("detected_language")
            ?: bundle.getString("android.speech.extra.DETECTED_LANGUAGE")
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                bundle.getString(SpeechRecognizer.DETECTED_LANGUAGE)
            } else null
    }

    private fun getErrorMessage(error: Int) = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission denied"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech match (silence timeout)"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech input timeout"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language not supported"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language unavailable"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many requests"
        else -> "Recognition error $error"
    }
}
