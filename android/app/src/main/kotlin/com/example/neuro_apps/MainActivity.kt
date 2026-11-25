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
        controlChannel = SystemChannelBridge.register(
            appContext = applicationContext,
            messenger = flutterEngine.dartExecutor.binaryMessenger,
            activityProvider = { this },
        )
        SystemChannelBridge.registerOverlayEngine(applicationContext)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        controlChannel?.invokeMethod("onAppReopened", null)
    }
}
