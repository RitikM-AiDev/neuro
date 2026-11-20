package com.example.neuro_apps

import android.content.Intent
import android.speech.RecognitionService

class NeuroRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {}
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
