package com.example.neuro_apps

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.android.FlutterActivityLaunchConfigs.BackgroundMode
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.util.Locale

class MainActivity : FlutterActivity(), AssistantSpeechManager.Listener, TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "NeuroMainActivity"
        private const val CONTROL_CHANNEL = "com.example.neuro_apps/control"
        private const val REQUEST_RECORD_AUDIO = 2001
        private const val UTTERANCE_ID = "neuro_main_tts"
    }

    private var controlChannel: MethodChannel? = null
    private var assistantChannel: MethodChannel? = null
    private var assistantEventChannel: EventChannel? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var localSpeechManager: AssistantSpeechManager? = null
    private var localTts: TextToSpeech? = null
    private var isTtsReady = false

    override fun getBackgroundMode(): BackgroundMode = BackgroundMode.transparent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        localSpeechManager = AssistantSpeechManager(this)
        localTts = TextToSpeech(this, this)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // 1. Control Channel (Window & App Lifecycle)
        controlChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CONTROL_CHANNEL).apply {
            setMethodCallHandler { call, result ->
                when (call.method) {
                    "minimizeApp" -> {
                        moveTaskToBack(true)
                        result.success(true)
                    }
                    "closeApp" -> {
                        Log.d(TAG, "closeApp received — killing process")
                        result.success(true)
                        finishAffinity()
                        mainHandler.postDelayed({
                            android.os.Process.killProcess(android.os.Process.myPid())
                            System.exit(0)
                        }, 250L)
                    }
                    else -> result.notImplemented()
                }
            }
        }

        // 2. Assistant MethodChannel (Command & Status API)
        assistantChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, AssistantBridge.METHOD_CHANNEL).apply {
            setMethodCallHandler { call, result ->
                when (call.method) {
                    "hasPermission" -> {
                        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        val request = call.argument<Boolean>("request") ?: false
                        if (!granted && request) {
                            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
                        }
                        result.success(granted)
                    }
                    "initializeAssistant" -> {
                        val isDefault = AssistantBridge.isDefaultAssistant(this@MainActivity)
                        result.success(mapOf(
                            "isDefaultAssistant" to isDefault,
                            "serviceActive" to (AssistantBridge.activeService != null)
                        ))
                    }
                    "requestAssistantRole" -> {
                        AssistantBridge.requestAssistantRole(this@MainActivity)
                        result.success(true)
                    }
                    "startListening" -> {
                        startVoiceListeningWorkflow()
                        result.success(true)
                    }
                    "stopListening" -> {
                        stopVoiceListeningWorkflow()
                        result.success(true)
                    }
                    "getAssistantStatus" -> {
                        result.success(mapOf(
                            "isDefaultAssistant" to AssistantBridge.isDefaultAssistant(this@MainActivity),
                            "serviceActive" to (AssistantBridge.activeService != null),
                            "sessionActive" to (AssistantBridge.activeSession != null)
                        ))
                    }
                    "sendAiResponse" -> {
                        val text = call.argument<String>("response").orEmpty()
                        speakAiResponse(text)
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }
        }

        // 3. Assistant EventChannel (Live Event Streaming to Flutter)
        assistantEventChannel = EventChannel(flutterEngine.dartExecutor.binaryMessenger, AssistantBridge.EVENT_CHANNEL).apply {
            setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    Log.d(TAG, "Assistant EventChannel listener attached")
                    AssistantBridge.setEventSink(events)
                }

                override fun onCancel(arguments: Any?) {
                    Log.d(TAG, "Assistant EventChannel listener cancelled")
                    AssistantBridge.setEventSink(null)
                }
            })
        }
    }

    private fun startVoiceListeningWorkflow() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted; requesting at runtime")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }

        if (AssistantBridge.activeService != null) {
            Log.d(TAG, "Triggering voice interaction on active VoiceInteractionService")
            AssistantBridge.triggerVoiceInteraction()
        } else {
            Log.d(TAG, "Starting local SpeechRecognizer directly on MainActivity")
            AssistantBridge.emitEvent(AssistantBridge.EVENT_RECOGNITION_STARTED)
            localSpeechManager?.startListening("auto", this)
        }
    }

    private fun stopVoiceListeningWorkflow() {
        if (AssistantBridge.activeService != null) {
            AssistantBridge.stopVoiceInteraction()
        } else {
            localSpeechManager?.stopListening()
        }
    }

    private fun speakAiResponse(text: String) {
        if (AssistantBridge.activeSession != null) {
            AssistantBridge.activeSession?.speakResponse(text)
        } else {
            Log.d(TAG, "Speaking response via local TTS: '$text'")
            AssistantBridge.emitEvent(
                AssistantBridge.EVENT_RESPONSE_READY,
                mapOf("response" to text)
            )
            if (isTtsReady && localTts != null) {
                localTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
            } else {
                mainHandler.postDelayed({
                    AssistantBridge.emitEvent(AssistantBridge.EVENT_RETURNED_TO_STANDBY)
                }, 2000L)
            }
        }
    }

    // ── AssistantSpeechManager.Listener Callbacks ────────────────────────────

    override fun onRecognitionStarted() {
        Log.d(TAG, "Local recognition started; mic active")
    }

    override fun onRmsChanged(rmsLevel: Float) {
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
            AssistantBridge.emitEvent(AssistantBridge.EVENT_RETURNED_TO_STANDBY)
            return
        }

        Log.d(TAG, "Local FINAL_TRANSCRIPT: '$trimmed'")
        AssistantBridge.emitEvent(
            AssistantBridge.EVENT_FINAL_TRANSCRIPT,
            mapOf("text" to trimmed, "language" to (language ?: "auto"))
        )
        AssistantBridge.emitEvent(
            AssistantBridge.EVENT_COMMAND_PROCESSING,
            mapOf("command" to trimmed)
        )
    }

    override fun onLanguageDetected(language: String, confidence: Int) {}

    override fun onError(errorCode: Int, errorMessage: String, isRecoverable: Boolean) {
        Log.w(TAG, "Local SpeechRecognizer error: code=$errorCode ($errorMessage)")
        AssistantBridge.emitEvent(
            AssistantBridge.EVENT_RECOGNITION_ERROR,
            mapOf("code" to errorCode, "message" to errorMessage, "recoverable" to isRecoverable)
        )
        AssistantBridge.emitEvent(AssistantBridge.EVENT_RETURNED_TO_STANDBY)
    }

    // ── TextToSpeech.OnInitListener Callbacks ─────────────────────────────────

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            localTts?.language = Locale("en", "IN")
            localTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    mainHandler.post {
                        AssistantBridge.emitEvent(AssistantBridge.EVENT_RETURNED_TO_STANDBY)
                    }
                }
                override fun onError(utteranceId: String?) {
                    mainHandler.post {
                        AssistantBridge.emitEvent(AssistantBridge.EVENT_RETURNED_TO_STANDBY)
                    }
                }
            })
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "RECORD_AUDIO onRequestPermissionsResult: granted=$granted")
            if (granted) {
                AssistantBridge.activeService?.resumeHotwordDetection()
            }
        }
    }

    override fun onDestroy() {
        localSpeechManager?.cancelAndRelease()
        try { localTts?.stop(); localTts?.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }
}
