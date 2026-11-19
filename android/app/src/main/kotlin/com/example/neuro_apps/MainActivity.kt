package com.example.neuro_apps

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.android.FlutterActivityLaunchConfigs.BackgroundMode
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    override fun getBackgroundMode(): BackgroundMode {
        return BackgroundMode.transparent
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "com.example.neuro_apps/control")
            .setMethodCallHandler { call, result ->
                if (call.method == "minimizeApp") {
                    moveTaskToBack(true)
                    result.success(true)
                } else {
                    result.notImplemented()
                }
            }
    }
}
