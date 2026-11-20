package com.example.neuro_apps

import android.content.Intent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.android.FlutterActivityLaunchConfigs.BackgroundMode
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private var controlChannel: MethodChannel? = null

    override fun getBackgroundMode(): BackgroundMode {
        return BackgroundMode.transparent
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        controlChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "com.example.neuro_apps/control")
        controlChannel?.setMethodCallHandler { call, result ->
            if (call.method == "minimizeApp") {
                moveTaskToBack(true)
                result.success(true)
            } else {
                result.notImplemented()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        controlChannel?.invokeMethod("onAppReopened", null)
    }
}
