package com.example.neuro_apps

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class PowerButtonAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var powerKeyDownTime = 0L
    private var isTriggered = false

    private val launchRunnable = Runnable {
        if (!isTriggered) {
            isTriggered = true
            launchNeuro()
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_POWER) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    powerKeyDownTime = SystemClock.elapsedRealtime()
                    isTriggered = false
                    // Schedule trigger after holding power button for 3 seconds (3000ms)
                    handler.postDelayed(launchRunnable, 3000)
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(launchRunnable)
                isTriggered = false
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString() ?: ""
            val packageName = event.packageName?.toString() ?: ""
            // Detect system power menu / global actions dialog triggered by holding power button
            if (className.contains("GlobalActions", ignoreCase = true) ||
                className.contains("PowerDialog", ignoreCase = true) ||
                (packageName == "com.android.systemui" && className.contains("Dialog", ignoreCase = true))
            ) {
                // Dismiss the power dialog and open the app
                performGlobalAction(GLOBAL_ACTION_BACK)
                launchNeuro()
            }
        }
    }

    private fun launchNeuro() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_ASSIST
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    override fun onInterrupt() {
        handler.removeCallbacks(launchRunnable)
    }
}
