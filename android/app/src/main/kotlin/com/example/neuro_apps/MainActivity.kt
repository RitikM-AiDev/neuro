package com.example.neuro_apps

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.android.FlutterActivityLaunchConfigs.BackgroundMode
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.ArrayList
import java.util.concurrent.Executor

class MainActivity : FlutterActivity(), RecognitionListener {

    companion object {
        private const val TAG = "NeuroSpeech"

        private const val SPEECH_CHANNEL = "com.example.neuro_apps/speech"
        private const val CONTROL_CHANNEL = "com.example.neuro_apps/control"

        // Multilingual speech recognition target languages
        private val SUPPORTED_LANGUAGES = arrayListOf(
            "en-US", // English (US)
            "en-IN", // English (India)
            "ta-IN", // Tamil
            "hi-IN", // Hindi
            "te-IN", // Telugu
            "ml-IN", // Malayalam
            "kn-IN", // Kannada
            "bn-IN", // Bengali
            "mr-IN", // Marathi
            "gu-IN", // Gujarati
            "pa-IN"  // Punjabi
        )

        private const val DEFAULT_FALLBACK_LANGUAGE = "en-IN"
        private const val REQUEST_RECORD_AUDIO = 200
    }

    // ── Flutter Channels ──────────────────────────────────────────────────────

    private var controlChannel: MethodChannel? = null
    private var speechChannel: MethodChannel? = null

    // ── Official Android SpeechRecognizer ─────────────────────────────────────

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> mainHandler.post(command) }

    @Volatile
    private var shouldListen = false

    @Volatile
    private var isSessionActive = false

    private var restartRunnable: Runnable? = null
    private var sessionCounter = 0L
    private var activeSessionId = 0L

    // Multilingual configuration
    private var activeLanguage = "auto"
    private var lastDetectedLanguage: String? = null

    // ── App Lifecycle ─────────────────────────────────────────────────────────

    override fun getBackgroundMode(): BackgroundMode = BackgroundMode.transparent

    // =========================================================================
    // Flutter Engine Setup
    // =========================================================================

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // 1. Control Channel
        controlChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CONTROL_CHANNEL
        )

        controlChannel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "minimizeApp" -> {
                    moveTaskToBack(true)
                    result.success(true)
                }
                "closeApp" -> {
                    stopContinuousListening()
                    result.success(true)
                    finishAffinity()
                }
                else -> result.notImplemented()
            }
        }

        // 2. Speech Channel
        speechChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            SPEECH_CHANNEL
        )

        speechChannel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "startListening" -> {
                    val langArg = call.argument<String>("language")
                    if (!langArg.isNullOrEmpty()) {
                        activeLanguage = langArg
                    }
                    startContinuousListening()
                    result.success(true)
                }

                "stopListening" -> {
                    stopContinuousListening()
                    result.success(true)
                }

                "setLanguage" -> {
                    val langArg = call.argument<String>("language") ?: "auto"
                    activeLanguage = langArg
                    Log.d(TAG, "Active language updated: $activeLanguage")

                    // If currently listening, refresh the current session with new language settings
                    if (shouldListen && isSessionActive) {
                        scheduleRestart(0L)
                    }
                    result.success(true)
                }

                "checkRecognitionSupport" -> {
                    checkRecognitionSupport(result)
                }

                else -> result.notImplemented()
            }
        }
    }

    // =========================================================================
    // Check Recognition Support (API 33+)
    // =========================================================================

    private fun checkRecognitionSupport(result: MethodChannel.Result) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            result.success(
                mapOf(
                    "supported" to false,
                    "isRecognitionAvailable" to false,
                    "error" to "SpeechRecognizer unavailable on device"
                )
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                if (speechRecognizer == null) {
                    createRecognizerIfNeeded()
                }

                val testIntent = buildRecognizerIntent()

                speechRecognizer?.checkRecognitionSupport(
                    testIntent,
                    mainExecutor,
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                            val onDeviceLangs = recognitionSupport.supportedOnDeviceLanguages
                            val installedLangs = recognitionSupport.installedOnDeviceLanguages
                            val onlineLangs = recognitionSupport.onlineLanguages

                            Log.d(
                                TAG,
                                "Recognition Support: onDevice=$onDeviceLangs, installed=$installedLangs, online=$onlineLangs"
                            )

                            result.success(
                                mapOf(
                                    "supported" to true,
                                    "isRecognitionAvailable" to true,
                                    "supportedOnDeviceLanguages" to onDeviceLangs,
                                    "installedOnDeviceLanguages" to installedLangs,
                                    "onlineLanguages" to onlineLangs,
                                    "sdkVersion" to Build.VERSION.SDK_INT
                                )
                            )
                        }

                        override fun onError(errorCode: Int) {
                            Log.w(TAG, "checkRecognitionSupport error: $errorCode")
                            result.success(
                                mapOf(
                                    "supported" to true,
                                    "isRecognitionAvailable" to true,
                                    "checkSupportErrorCode" to errorCode,
                                    "sdkVersion" to Build.VERSION.SDK_INT
                                )
                            )
                        }
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "checkRecognitionSupport exception", e)
                result.success(
                    mapOf(
                        "supported" to true,
                        "isRecognitionAvailable" to true,
                        "error" to e.message,
                        "sdkVersion" to Build.VERSION.SDK_INT
                    )
                )
            }
        } else {
            // Pre-API 33 fallback
            val available = SpeechRecognizer.isRecognitionAvailable(this)
            result.success(
                mapOf(
                    "supported" to available,
                    "isRecognitionAvailable" to available,
                    "sdkVersion" to Build.VERSION.SDK_INT
                )
            )
        }
    }

    // =========================================================================
    // Continuous Listening Lifecycle
    // =========================================================================

    private fun startContinuousListening() {
        mainHandler.post {
            if (shouldListen && isSessionActive) {
                Log.d(TAG, "Continuous listening is already actively running")
                return@post
            }

            if (!hasMicrophonePermission()) {
                Log.w(TAG, "Microphone permission is not granted, requesting at runtime...")
                requestPermissions(
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    REQUEST_RECORD_AUDIO
                )
                return@post
            }

            shouldListen = true
            Log.d(TAG, "Continuous listening requested (language=$activeLanguage)")

            createRecognizerIfNeeded()
            if (shouldListen) {
                startRecognitionSession()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Microphone permission granted by user via runtime dialog")
                startContinuousListening()
            } else {
                Log.e(TAG, "Microphone permission denied by user")
                sendSpeechError("Microphone permission denied", SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            }
        }
    }

    private fun stopContinuousListening() {
        mainHandler.post {
            shouldListen = false
            isSessionActive = false

            restartRunnable?.let { mainHandler.removeCallbacks(it) }
            restartRunnable = null

            try {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying speech recognizer", e)
            }

            speechRecognizer = null
            lastDetectedLanguage = null

            notifyRecordingState(false)
            Log.d(TAG, "Continuous listening stopped and recognizer destroyed")
        }
    }

    private fun createRecognizerIfNeeded() {
        if (speechRecognizer != null) return

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "SpeechRecognizer is unavailable on this device")
            shouldListen = false
            sendSpeechError("Speech recognition service unavailable on device", SpeechRecognizer.ERROR_CLIENT)
            return
        }

        try {
            // First prefer Google's official Speech Recognition Service
            val googleComponent = ComponentName(
                "com.google.android.googlequicksearchbox",
                "com.google.android.voicesearch.service.RecognitionService"
            )

            speechRecognizer = try {
                Log.d(TAG, "Attempting to create SpeechRecognizer with Google RecognitionService")
                SpeechRecognizer.createSpeechRecognizer(this, googleComponent)
            } catch (e: Exception) {
                Log.w(TAG, "Google RecognitionService not available, using default recognizer", e)
                SpeechRecognizer.createSpeechRecognizer(this)
            }

            speechRecognizer?.setRecognitionListener(this)
            Log.d(TAG, "SpeechRecognizer created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "SpeechRecognizer creation failed", e)
            shouldListen = false
            sendSpeechError("Recognizer creation failed: ${e.message}", SpeechRecognizer.ERROR_CLIENT)
        }
    }

    private fun startRecognitionSession() {
        if (!shouldListen || isSessionActive) return

        if (speechRecognizer == null) {
            createRecognizerIfNeeded()
        }
        if (!shouldListen || speechRecognizer == null) return

        try {
            activeSessionId = ++sessionCounter
            isSessionActive = true

            // Clean any residual session state before starting
            try {
                speechRecognizer?.cancel()
            } catch (_: Exception) {}

            val intent = buildRecognizerIntent()
            speechRecognizer?.startListening(intent)

            notifyRecordingState(true)
            Log.d(TAG, "Started SpeechRecognizer session #$activeSessionId (lang=$activeLanguage)")

        } catch (e: Exception) {
            isSessionActive = false
            Log.e(TAG, "Failed to start recognition session", e)
            recreateRecognizer()
            scheduleRestart(1000L)
        }
    }

    // =========================================================================
    // Multilingual Intent Configuration
    // =========================================================================

    private fun buildRecognizerIntent(): Intent {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)

            // Extended silence limits for continuous assistant behavior (using official RecognizerIntent extras)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 60_000L)
        }

        val isAutoLanguage = activeLanguage.isEmpty() || activeLanguage.equals("auto", ignoreCase = true)

        if (!isAutoLanguage) {
            // 1. Explicit language specified by user (e.g. "ta-IN", "hi-IN", "en-US")
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, activeLanguage)
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, activeLanguage)
            Log.d(TAG, "Intent configured with explicit language: $activeLanguage")
        } else {
            // 2. Automatic Multilingual Recognition
            Log.d(TAG, "Intent configured for automatic multilingual detection")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ (API 34+) Official Multilingual APIs
                intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, true)
                intent.putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                    SUPPORTED_LANGUAGES
                )
                intent.putExtra(
                    "android.speech.extra.LANGUAGE_SWITCH_ALLOWED_LANGUAGES",
                    SUPPORTED_LANGUAGES
                )
                intent.putExtra(
                    "android.speech.extra.LANGUAGE_SWITCH_BALANCED",
                    "balanced"
                )
            } else {
                // Android < 34 Multilingual Fallback Strategy:
                // Set fallback language (en-IN) and request Google App language detection extras
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, DEFAULT_FALLBACK_LANGUAGE)
                intent.putExtra("android.speech.extra.ENABLE_LANGUAGE_DETECTION", true)
                intent.putExtra(
                    "android.speech.extra.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES",
                    SUPPORTED_LANGUAGES
                )
            }
        }

        return intent
    }

    private fun scheduleRestart(delayMs: Long = 0L) {
        if (!shouldListen) return

        restartRunnable?.let { mainHandler.removeCallbacks(it) }

        restartRunnable = Runnable {
            if (shouldListen && !isSessionActive) {
                Log.d(TAG, "Cycling recognition session (seamless)...")
                startRecognitionSession()
            }
        }

        mainHandler.postDelayed(restartRunnable!!, delayMs)
    }

    private fun recreateRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        if (shouldListen) {
            createRecognizerIfNeeded()
        }
    }

    // =========================================================================
    // RecognitionListener Callbacks
    // =========================================================================

    override fun onReadyForSpeech(params: Bundle?) {
        Log.d(TAG, "Ready for speech — microphone active")
        notifyRecordingState(true)
    }

    override fun onBeginningOfSpeech() {
        Log.d(TAG, "User started speaking")
        notifySpeechState("beginning", 2.0, true)
    }

    override fun onRmsChanged(rmsdB: Float) {
        if (!shouldListen) return

        // rmsdB typically ranges from -2.0 dB (silence) to 10.0+ dB (speech)
        // Whispering / quiet speech is around -1.2 to 0.5 dB
        // -1.2f threshold ensures low/whispered speech is detected as voice activity
        val isVoiceActive = rmsdB > -1.2f

        notifySpeechState("rms", rmsdB.toDouble(), isVoiceActive)
    }

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        Log.d(TAG, "User finished speech utterance — processing recognition")
        notifySpeechState("processing", 0.0, false)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        if (!shouldListen || !isSessionActive) return

        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull()?.trim() ?: return

        // Extract detected language if returned by the recognizer
        val detectedLang = extractDetectedLanguage(partialResults) ?: lastDetectedLanguage

        if (text.isNotEmpty()) {
            Log.d(TAG, "PARTIAL [#$activeSessionId] [${detectedLang ?: "unknown"}]: $text")

            val payload = mapOf(
                "text" to text,
                "language" to detectedLang,
                "sessionId" to activeSessionId
            )

            // Send onPartialResult
            speechChannel?.invokeMethod("onPartialResult", payload)

            // Send onSpeechResult with isFinal = false
            speechChannel?.invokeMethod(
                "onSpeechResult",
                mapOf(
                    "text" to text,
                    "language" to detectedLang,
                    "isFinal" to false,
                    "sessionId" to activeSessionId
                )
            )
        }
    }

    override fun onResults(results: Bundle?) {
        if (!shouldListen || !isSessionActive) return

        val completedId = activeSessionId
        isSessionActive = false

        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull()?.trim()

        val detectedLang = extractDetectedLanguage(results) ?: lastDetectedLanguage

        if (!text.isNullOrEmpty()) {
            Log.d(TAG, "FINAL [#$completedId] [${detectedLang ?: "unknown"}]: $text")

            val payload = mapOf(
                "text" to text,
                "language" to detectedLang,
                "isFinal" to true,
                "sessionId" to completedId
            )

            speechChannel?.invokeMethod("onSpeechResult", payload)
        }

        // Seamless session cycle: 50ms pause gives Android audio stack time to release mic
        scheduleRestart(50L)
    }

    override fun onError(error: Int) {
        isSessionActive = false

        val msg = getErrorMessage(error)
        Log.w(TAG, "SpeechRecognizer onError: $error ($msg)")

        if (!shouldListen) return

        // Insufficient permissions — user must re-grant
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            shouldListen = false
            notifyRecordingState(false)
            sendSpeechError("Microphone permission denied", error)
            return
        }

        // Unrecoverable errors that leave the recognizer in a broken state
        if (
            error == SpeechRecognizer.ERROR_CLIENT ||
            error == SpeechRecognizer.ERROR_AUDIO ||
            error == SpeechRecognizer.ERROR_SERVER
        ) {
            recreateRecognizer()
        }

        // Recoverable silence / timeout / busy errors
        val delay = when (error) {
            // Normal silence timeouts: cycle seamlessly with 50ms
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 50L

            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 200L

            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_SERVER -> 500L

            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 1000L

            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> 2000L

            else -> 200L
        }

        scheduleRestart(delay)
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    // =========================================================================
    // Android 14+ (API 34+) Language Detection Callbacks
    // =========================================================================

    override fun onLanguageDetection(results: Bundle) {
        val detected = extractDetectedLanguage(results)
        val confidence = results.getInt("language_detection_confidence_level", -1)

        Log.d(TAG, "onLanguageDetection: language=$detected, confidence=$confidence")

        if (!detected.isNullOrEmpty()) {
            lastDetectedLanguage = detected
            speechChannel?.invokeMethod(
                "onLanguageDetected",
                mapOf(
                    "language" to detected,
                    "confidence" to confidence
                )
            )
        }
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private fun extractDetectedLanguage(bundle: Bundle?): String? {
        if (bundle == null) return null
        return bundle.getString("detected_language")
            ?: bundle.getString("android.speech.extra.DETECTED_LANGUAGE")
            ?: bundle.getString(SpeechRecognizer.DETECTED_LANGUAGE)
    }

    private fun notifyRecordingState(active: Boolean) {
        mainHandler.post {
            speechChannel?.invokeMethod(
                "onRecordingState",
                mapOf("isRecording" to active)
            )
        }
    }

    private fun notifySpeechState(event: String, rmsDb: Double, isVoiceActive: Boolean) {
        mainHandler.post {
            speechChannel?.invokeMethod(
                "onSpeechState",
                mapOf(
                    "event" to event,
                    "rmsDb" to rmsDb,
                    "isVoiceActive" to isVoiceActive
                )
            )
        }
    }

    private fun sendSpeechError(message: String, code: Int) {
        mainHandler.post {
            speechChannel?.invokeMethod(
                "onSpeechError",
                mapOf(
                    "error" to message,
                    "code" to code
                )
            )
        }
    }

    private fun hasMicrophonePermission(): Boolean {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun getErrorMessage(error: Int) = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission denied"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language not supported"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language unavailable"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many requests"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "Cannot check support"
        else -> "Recognition error: $error"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        controlChannel?.invokeMethod("onAppReopened", null)

        if (shouldListen && !isSessionActive) {
            scheduleRestart(0L)
        }
    }

    override fun onDestroy() {
        stopContinuousListening()
        Log.d(TAG, "MainActivity destroyed — SpeechRecognizer released")
        super.onDestroy()
    }
}
