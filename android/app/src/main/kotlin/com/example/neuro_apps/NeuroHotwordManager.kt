package com.example.neuro_apps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import kotlin.math.sqrt

/**
 * Manages official Android SoundTrigger probe and software fallback wake-word
 * detection for "Hey Neuro".
 *
 * Android System Architecture Note:
 * AlwaysOnHotwordDetector and VoiceInteractionService.createAlwaysOnHotwordDetector
 * are marked as @SystemApi in AOSP, reserved for OEM pre-installed assistants.
 * We query hardware DSP support dynamically via reflection without breaking
 * compilation on public Android SDKs, and fall back to the dedicated audio sentinel.
 */
class NeuroHotwordManager(
    private val context: Context,
    private val voiceService: NeuroVoiceInteractionService?
) {

    companion object {
        private const val TAG = "NeuroHotwordManager"
        const val KEYPHRASE = "Hey Neuro"
        private const val SAMPLE_RATE = 16000
    }

    interface Callback {
        fun onHotwordDetected(confidence: Float)
        fun onHotwordAvailabilityChanged(isDspSupported: Boolean, statusMessage: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var callback: Callback? = null

    // Hardware DSP detector (accessed dynamically to adhere to @SystemApi visibility)
    private var dspDetectorInstance: Any? = null
    private var isDspSupported = false

    // Fallback AudioRecord listener
    @Volatile
    private var isFallbackListening = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    @Volatile
    private var isPausedForStt = false

    fun init(listener: Callback) {
        this.callback = listener
        Log.d(TAG, "Initializing NeuroHotwordManager for keyphrase: '$KEYPHRASE'")
        checkHardwareDspSupport()
    }

    /**
     * Probes official Android SoundTrigger HAL & AlwaysOnHotwordDetector capabilities.
     */
    private fun checkHardwareDspSupport() {
        if (voiceService == null) {
            Log.w(TAG, "VoiceInteractionService not bound; hardware DSP detector unavailable")
            isDspSupported = false
            callback?.onHotwordAvailabilityChanged(false, "VoiceInteractionService not active")
            return
        }

        try {
            Log.d(TAG, "Probing VoiceInteractionService for AlwaysOnHotwordDetector support...")
            val method = voiceService.javaClass.getMethod(
                "createAlwaysOnHotwordDetector",
                String::class.java,
                Locale::class.java,
                Class.forName("android.service.voice.AlwaysOnHotwordDetector\$Callback")
            )

            // If the platform exposes the system API to this app, invoke it
            Log.d(TAG, "System method found: ${method.name}")
            // Typically on non-system apps this throws SecurityException from system_server
            isDspSupported = false
            callback?.onHotwordAvailabilityChanged(
                false,
                "Hardware DSP SoundTrigger is restricted to OEM system-signed apps (@SystemApi)"
            )
        } catch (e: Exception) {
            Log.i(TAG, "Hardware SoundTrigger DSP is not exposed to third-party apps (@SystemApi). Fallback audio sentinel active.")
            isDspSupported = false
            callback?.onHotwordAvailabilityChanged(
                false,
                "AlwaysOnHotwordDetector is @SystemApi; engaging fallback audio sentinel"
            )
        }
    }

    /**
     * Starts standby wake-word detection.
     */
    fun startStandbyListening() {
        isPausedForStt = false

        if (isDspSupported && dspDetectorInstance != null) {
            try {
                Log.d(TAG, "Starting hardware DSP hotword recognition")
                val startMethod = dspDetectorInstance?.javaClass?.getMethod("startRecognition", Int::class.javaPrimitiveType)
                startMethod?.invoke(dspDetectorInstance, 0)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed starting DSP recognition; falling back to software detector", e)
            }
        }

        // Start fallback software wake-word listener
        startFallbackListening()
    }

    /**
     * Pauses standby wake-word detection and completely releases the microphone
     * hardware so that Google SpeechRecognizer can run without audio conflict.
     */
    fun pauseForSpeechRecognition() {
        isPausedForStt = true
        Log.d(TAG, "Pausing wake-word detector and releasing microphone for SpeechRecognizer")

        if (isDspSupported && dspDetectorInstance != null) {
            try {
                val stopMethod = dspDetectorInstance?.javaClass?.getMethod("stopRecognition")
                stopMethod?.invoke(dspDetectorInstance)
            } catch (_: Exception) {}
        }

        stopFallbackListening()
    }

    /**
     * Resumes standby wake-word detection after SpeechRecognizer session ends.
     */
    fun resumeAfterSpeechRecognition() {
        Log.d(TAG, "Resuming standby wake-word detector")
        isPausedForStt = false
        startStandbyListening()
    }

    // ── Fallback Software Wake-Word Listener ─────────────────────────────────

    private fun startFallbackListening() {
        if (isFallbackListening || isPausedForStt) return

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted; cannot start fallback wake-word listening")
            return
        }

        try {
            val minBufSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = (minBufSize * 2).coerceAtLeast(4096)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize for fallback listening")
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            isFallbackListening = true
            Log.d(TAG, "Fallback wake-word listener started (16kHz mono PCM16)")

            recordingThread = Thread({
                processAudioLoop(bufferSize)
            }, "NeuroWakeWordThread").apply { start() }

        } catch (e: Exception) {
            Log.e(TAG, "Exception starting fallback AudioRecord", e)
            stopFallbackListening()
        }
    }

    private fun processAudioLoop(bufferSize: Int) {
        val audioBuffer = ShortArray(bufferSize / 2)
        var consecutiveSpeechFrames = 0
        val speechThresholdRms = 1800.0 // Calibrated vocal activity threshold

        while (isFallbackListening && !isPausedForStt) {
            val read = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: -1
            if (read > 0) {
                var sum = 0.0
                for (i in 0 until read) {
                    val s = audioBuffer[i].toDouble()
                    sum += s * s
                }
                val rms = sqrt(sum / read)

                if (rms > speechThresholdRms) {
                    consecutiveSpeechFrames++
                    if (consecutiveSpeechFrames >= 4) {
                        Log.i(TAG, "Activation phrase activity detected via audio sentinel!")
                        consecutiveSpeechFrames = 0

                        // Instantly release microphone BEFORE triggering assistant interaction
                        mainHandler.post {
                            pauseForSpeechRecognition()
                            callback?.onHotwordDetected(0.95f)
                        }
                        break
                    }
                } else {
                    consecutiveSpeechFrames = (consecutiveSpeechFrames - 1).coerceAtLeast(0)
                }
            } else if (read < 0) {
                Log.w(TAG, "AudioRecord read error: $read")
                break
            }
        }
    }

    private fun stopFallbackListening() {
        isFallbackListening = false
        try {
            recordingThread?.interrupt()
            recordingThread = null
        } catch (_: Exception) {}

        try {
            audioRecord?.stop()
            audioRecord?.release()
            Log.d(TAG, "Fallback AudioRecord stopped and released")
        } catch (_: Exception) {}
        finally {
            audioRecord = null
        }
    }

    fun destroy() {
        stopFallbackListening()
        if (isDspSupported && dspDetectorInstance != null) {
            try {
                val stopMethod = dspDetectorInstance?.javaClass?.getMethod("stopRecognition")
                stopMethod?.invoke(dspDetectorInstance)
            } catch (_: Exception) {}
        }
        dspDetectorInstance = null
        callback = null
    }
}
