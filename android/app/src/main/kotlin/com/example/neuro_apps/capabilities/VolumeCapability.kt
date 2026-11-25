package com.example.neuro_apps.capabilities

import android.content.Context
import android.media.AudioManager
import android.os.Build

class VolumeCapability : DeviceCapability {
    override val name = "volume"
    override val supportedActions = setOf("set", "adjust", "get")
    override val executionMode = ExecutionMode.DIRECT
    override val requirement = CapabilityRequirement(minSdk = Build.VERSION_CODES.M)

    private fun audioManager(context: Context): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun resolveStream(rawStream: Any?): Int =
        when (rawStream?.toString()?.trim()?.lowercase()) {
            "ring", "ringer" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification", "notifications" -> AudioManager.STREAM_NOTIFICATION
            "voice_call", "call", "voice" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }

    override fun getState(context: Context): Any? {
        val am = audioManager(context)
        return mapOf(
            "music" to am.getStreamVolume(AudioManager.STREAM_MUSIC),
            "music_max" to am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            "ring" to am.getStreamVolume(AudioManager.STREAM_RING),
            "alarm" to am.getStreamVolume(AudioManager.STREAM_ALARM),
        )
    }

    override fun execute(
        context: Context,
        action: String,
        parameters: Map<String, Any?>,
        onComplete: (CapabilityResult) -> Unit,
    ) {
        val am = audioManager(context)
        val stream = resolveStream(parameters["stream"])
        val maxVol = am.getStreamMaxVolume(stream)

        if (action == "adjust") {
            val dirStr = parameters["direction"]?.toString()?.trim()?.lowercase()
            val direction = when (dirStr) {
                "up", "raise", "increase" -> AudioManager.ADJUST_RAISE
                "down", "lower", "decrease" -> AudioManager.ADJUST_LOWER
                "mute" -> AudioManager.ADJUST_MUTE
                "unmute" -> AudioManager.ADJUST_UNMUTE
                else -> {
                    onComplete(
                        CapabilityResult(
                            success = false,
                            capability = name,
                            action = action,
                            message = "Invalid 'direction'; expected up, down, mute, or unmute.",
                            executionMode = executionMode,
                            errorCode = "INVALID_PARAMETERS",
                        ),
                    )
                    return
                }
            }
            am.adjustStreamVolume(stream, direction, AudioManager.FLAG_SHOW_UI)
            val updated = am.getStreamVolume(stream)
            onComplete(
                CapabilityResult(
                    success = true,
                    capability = name,
                    action = action,
                    state = updated,
                    message = "Volume adjusted to $updated/$maxVol.",
                    executionMode = executionMode,
                ),
            )
            return
        }

        val rawLevel = parameters["level"] ?: parameters["volume"]
        val level = (rawLevel as? Number)?.toInt() ?: rawLevel?.toString()?.toIntOrNull()
        if (level == null || level < 0 || level > maxVol) {
            onComplete(
                CapabilityResult(
                    success = false,
                    capability = name,
                    action = action,
                    state = am.getStreamVolume(stream),
                    message = "Invalid 'level'; expected 0..$maxVol for selected stream.",
                    executionMode = executionMode,
                    errorCode = "INVALID_PARAMETERS",
                ),
            )
            return
        }

        am.setStreamVolume(stream, level, AudioManager.FLAG_SHOW_UI)
        onComplete(
            CapabilityResult(
                success = true,
                capability = name,
                action = action,
                state = am.getStreamVolume(stream),
                message = "Volume set to $level/$maxVol.",
                executionMode = executionMode,
            ),
        )
    }
}
