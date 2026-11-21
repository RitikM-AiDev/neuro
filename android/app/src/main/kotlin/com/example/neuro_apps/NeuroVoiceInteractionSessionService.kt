package com.example.neuro_apps

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

class NeuroVoiceInteractionSessionService : VoiceInteractionSessionService() {

    companion object {
        private const val TAG = "NeuroSessionService"
    }

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        Log.d(TAG, "Creating new NeuroVoiceInteractionSession")
        return NeuroVoiceInteractionSession(this)
    }
}
