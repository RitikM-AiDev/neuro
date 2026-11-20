import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_overlay_window/flutter_overlay_window.dart';
import 'models/assistant_state.dart';
import 'services/voice_detector_service.dart';
import 'widgets/neural_circle.dart';

// ─── Floating Overlay Entry Point (Separate Dart Isolate) ────────────────────
@pragma('vm:entry-point')
void overlayMain() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const NeuralOverlayApp());
}

class NeuralOverlayApp extends StatefulWidget {
  const NeuralOverlayApp({super.key});

  @override
  State<NeuralOverlayApp> createState() => _NeuralOverlayAppState();
}

class _NeuralOverlayAppState extends State<NeuralOverlayApp> {
  AssistantState _state = AssistantState.idle;
  double _amplitude = 0.0;

  @override
  void initState() {
    super.initState();

    // Listen to real-time events (mic amplitude, state, etc.)
    FlutterOverlayWindow.overlayListener.listen((data) {
      if (data == null) return;
      final msg = data.toString();

      if (msg.startsWith('listening:')) {
        final amp = double.tryParse(msg.substring(10)) ?? 0.0;
        if (mounted) {
          setState(() {
            _state = AssistantState.listening;
            _amplitude = amp;
          });
        }
      } else if (msg.startsWith('idle')) {
        if (mounted) {
          setState(() {
            _state = AssistantState.idle;
            _amplitude = 0.0;
          });
        }
      } else if (msg.startsWith('thinking')) {
        if (mounted) {
          setState(() {
            _state = AssistantState.thinking;
            _amplitude = 0.0;
          });
        }
      } else if (msg.startsWith('speaking:')) {
        final amp = double.tryParse(msg.substring(9)) ?? 0.0;
        if (mounted) {
          setState(() {
            _state = AssistantState.speaking;
            _amplitude = amp;
          });
        }
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      color: Colors.transparent,
      home: Scaffold(
        backgroundColor: Colors.transparent, // Fully transparent window
        body: Center(
          child: NeuralCircle(
            state: _state,
            amplitude: _amplitude,
            size: 100,
          ),
        ),
      ),
    );
  }
}

// ─── Main Controller Entry Point (Zero Full-Screen UI) ───────────────────────
void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const NeuroControllerApp());
}

class NeuroControllerApp extends StatelessWidget {
  const NeuroControllerApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Neuro',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        scaffoldBackgroundColor: Colors.transparent,
        colorScheme: const ColorScheme.dark(
          primary: Color(0xFF00E5FF),
          secondary: Color(0xFF7B2FBE),
        ),
      ),
      home: const HeadlessLauncher(),
    );
  }
}

class HeadlessLauncher extends StatefulWidget {
  const HeadlessLauncher({super.key});

  @override
  State<HeadlessLauncher> createState() => _HeadlessLauncherState();
}

class _HeadlessLauncherState extends State<HeadlessLauncher>
    with WidgetsBindingObserver {
  static const MethodChannel _controlChannel =
      MethodChannel('com.example.neuro_apps/control');

  late final VoiceDetectorService _voiceService;
  Timer? _permissionPollTimer;
  bool _isStartingOverlay = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);

    _controlChannel.setMethodCallHandler((call) async {
      if (call.method == 'onAppReopened') {
        await _checkAndExecute(forceOpen: true);
      }
    });

    _voiceService = VoiceDetectorService(
      onVoiceStateChanged: (state, amp) {
        if (state == AssistantState.listening) {
          FlutterOverlayWindow.shareData('listening:${amp.toStringAsFixed(3)}');
        } else {
          FlutterOverlayWindow.shareData('idle:0.0');
        }
      },
    );

    // Listen for close signal from overlay or native service
    FlutterOverlayWindow.overlayListener.listen((data) async {
      if (data == 'close') {
        await _voiceService.stopListening();
        await FlutterOverlayWindow.closeOverlay();
      }
    });

    _initPermissionsAndStart();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _checkAndExecute(forceOpen: true);
    }
  }

  Future<void> _initPermissionsAndStart() async {
    final overlayGranted = await FlutterOverlayWindow.isPermissionGranted();
    final micGranted = await _voiceService.hasPermission(request: false);

    if (overlayGranted && micGranted) {
      await _toggleOrStartOverlay(forceOpen: true);
      return;
    }

    // 1. Request microphone permission directly from Android OS dialog
    if (!micGranted) {
      await _voiceService.hasPermission(request: true);
    }

    // 2. Request overlay permission directly from Android system settings
    final hasOverlay = await FlutterOverlayWindow.isPermissionGranted();
    if (!hasOverlay) {
      _startPermissionPolling();
      FlutterOverlayWindow.requestPermission().then((granted) async {
        if (granted == true) {
          final mic = await _voiceService.hasPermission(request: false);
          if (mic) {
            _stopPermissionPolling();
            await _toggleOrStartOverlay(forceOpen: true);
          }
        }
      }).catchError((_) {});
    } else {
      final mic = await _voiceService.hasPermission(request: false);
      if (mic) {
        await _toggleOrStartOverlay(forceOpen: true);
      }
    }
  }

  void _startPermissionPolling() {
    _permissionPollTimer?.cancel();
    _permissionPollTimer =
        Timer.periodic(const Duration(milliseconds: 500), (timer) async {
      final overlayGranted = await FlutterOverlayWindow.isPermissionGranted();
      if (overlayGranted) {
        final micGranted = await _voiceService.hasPermission(request: false);
        if (micGranted) {
          _stopPermissionPolling();
          await _toggleOrStartOverlay(forceOpen: true);
        }
      }
    });
  }

  void _stopPermissionPolling() {
    _permissionPollTimer?.cancel();
    _permissionPollTimer = null;
  }

  Future<void> _checkAndExecute({bool forceOpen = false}) async {
    final overlayGranted = await FlutterOverlayWindow.isPermissionGranted();
    final micGranted = await _voiceService.hasPermission(request: false);

    if (overlayGranted && micGranted) {
      _stopPermissionPolling();
      await _toggleOrStartOverlay(forceOpen: forceOpen);
    }
  }

  Future<void> _toggleOrStartOverlay({bool forceOpen = false}) async {
    if (_isStartingOverlay) return;
    _isStartingOverlay = true;

    try {
      final isActive = await FlutterOverlayWindow.isActive();

      if (isActive && !forceOpen) {
        // If already active when launched normally, toggle it off (clean exit)
        await _voiceService.stopListening();
        await FlutterOverlayWindow.closeOverlay();
        await _minimizeApp();
        return;
      }

      if (!isActive) {
        // 1. Show floating overlay window
        // enableDrag: true enables native touch drag in Android WindowManager.
        // Starts at bottom center of screen whenever called.
        await FlutterOverlayWindow.showOverlay(
          enableDrag: true,
          overlayTitle: 'Neuro Assistant',
          overlayContent: 'Neural Voice Assistant Active',
          flag: OverlayFlag.defaultFlag,
          alignment: OverlayAlignment.bottomCenter,
          startPosition: const OverlayPosition(0, 30),
          visibility: NotificationVisibility.visibilityPublic,
          positionGravity: PositionGravity.none,
          height: 110,
          width: 110,
        );

        // 2. Start continuous voice detection
        await _voiceService.startListening();
      }

      // 3. Immediately minimize app so no full-screen window is ever visible
      await _minimizeApp();
    } finally {
      _isStartingOverlay = false;
    }
  }

  Future<void> _minimizeApp() async {
    try {
      await _controlChannel.invokeMethod('minimizeApp');
    } catch (_) {}
  }

  @override
  void dispose() {
    _stopPermissionPolling();
    WidgetsBinding.instance.removeObserver(this);
    _voiceService.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return const Scaffold(
      backgroundColor: Colors.transparent,
      body: SizedBox.shrink(),
    );
  }
}
