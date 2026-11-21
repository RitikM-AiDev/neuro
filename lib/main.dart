
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_overlay_window/flutter_overlay_window.dart';

import 'models/assistant_state.dart';
import 'services/voice_detector_service.dart';
import 'widgets/neural_circle.dart';

// ============================================================
// OVERLAY ENTRY POINT
// ============================================================

@pragma('vm:entry-point')
void overlayMain() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const NeuralOverlayApp());
}

class NeuralOverlayApp extends StatefulWidget {
  const NeuralOverlayApp({super.key});

  @override
  State<NeuralOverlayApp> createState() =>
      _NeuralOverlayAppState();
}

class _NeuralOverlayAppState extends State<NeuralOverlayApp> {
  AssistantState _state = AssistantState.idle;
  double _amplitude = 0.0;

  StreamSubscription<dynamic>? _overlaySubscription;

  @override
  void initState() {
    super.initState();

    _overlaySubscription =
        FlutterOverlayWindow.overlayListener.listen((data) {
      if (!mounted || data == null) return;

      final msg = data.toString();

      if (msg.startsWith('listening:')) {
        final amp =
            double.tryParse(msg.substring(10)) ?? 0.0;

        setState(() {
          _state = AssistantState.listening;
          _amplitude = amp;
        });
      } else if (msg.startsWith('idle')) {
        setState(() {
          _state = AssistantState.idle;
          _amplitude = 0.0;
        });
      } else if (msg.startsWith('thinking')) {
        setState(() {
          _state = AssistantState.thinking;
          _amplitude = 0.0;
        });
      } else if (msg.startsWith('speaking:')) {
        final amp =
            double.tryParse(msg.substring(9)) ?? 0.0;

        setState(() {
          _state = AssistantState.speaking;
          _amplitude = amp;
        });
      }
    });
  }

  @override
  void dispose() {
    _overlaySubscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      color: Colors.transparent,
      home: Scaffold(
        backgroundColor: Colors.transparent,
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

// ============================================================
// MAIN CONTROLLER
// ============================================================

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

// ============================================================
// HEADLESS LAUNCHER
// ============================================================

class HeadlessLauncher extends StatefulWidget {
  const HeadlessLauncher({super.key});

  @override
  State<HeadlessLauncher> createState() =>
      _HeadlessLauncherState();
}

class _HeadlessLauncherState extends State<HeadlessLauncher>
    with WidgetsBindingObserver {

  static const MethodChannel _controlChannel =
      MethodChannel('com.example.neuro_apps/control');

  late final VoiceDetectorService _voiceService;

  Timer? _permissionPollTimer;

  StreamSubscription<dynamic>? _overlaySubscription;

  bool _isStartingOverlay = false;
  bool _isClosing = false;

  // Automatic multilingual speech recognition by default (supports English, Tamil, Hindi, etc.)
  static const String _defaultLanguage = 'auto';

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
          FlutterOverlayWindow.shareData(
            'listening:${amp.toStringAsFixed(3)}',
          );
        } else {
          FlutterOverlayWindow.shareData('idle:0.0');
        }
      },
      onMessageSent: (message) {
        debugPrint('[Neuro] Transcript: $message');

        // Connect your assistant/backend message handler here.
      },
      onLanguageChanged: (language, confidence) {
        debugPrint('[Neuro] Spoken language detected: $language ($confidence)');
      },
    );

    _overlaySubscription =
        FlutterOverlayWindow.overlayListener.listen((data) async {
      if (data == 'close') {
        await _closeNeuro();
      }
    });

    _initPermissionsAndStart();
  }

  // ==========================================================
  // APP LIFECYCLE
  // ==========================================================

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _checkAndExecute(forceOpen: true);
    }
  }

  // ==========================================================
  // PERMISSIONS
  // ==========================================================

  Future<void> _initPermissionsAndStart() async {
    final overlayGranted =
        await FlutterOverlayWindow.isPermissionGranted();

    final micGranted =
        await _voiceService.hasPermission(request: false);

    if (overlayGranted && micGranted) {
      await _toggleOrStartOverlay(forceOpen: true);
      return;
    }

    if (!micGranted) {
      await _voiceService.hasPermission(request: true);
    }

    final hasOverlay =
        await FlutterOverlayWindow.isPermissionGranted();

    if (!hasOverlay) {
      _startPermissionPolling();

      FlutterOverlayWindow.requestPermission()
          .then((granted) async {
        if (granted == true) {
          final mic =
              await _voiceService.hasPermission(request: false);

          if (mic) {
            _stopPermissionPolling();

            await _toggleOrStartOverlay(
              forceOpen: true,
            );
          }
        }
      }).catchError((error) {
        debugPrint('[Neuro] Overlay permission error: $error');
      });
    } else {
      final mic =
          await _voiceService.hasPermission(request: false);

      if (mic) {
        await _toggleOrStartOverlay(forceOpen: true);
      }
    }
  }

  void _startPermissionPolling() {
    _permissionPollTimer?.cancel();

    _permissionPollTimer = Timer.periodic(
      const Duration(milliseconds: 500),
      (timer) async {
        final overlayGranted =
            await FlutterOverlayWindow.isPermissionGranted();

        if (!overlayGranted) return;

        final mic =
            await _voiceService.hasPermission(request: false);

        if (mic) {
          _stopPermissionPolling();

          await _toggleOrStartOverlay(
            forceOpen: true,
          );
        }
      },
    );
  }

  void _stopPermissionPolling() {
    _permissionPollTimer?.cancel();
    _permissionPollTimer = null;
  }

  Future<void> _checkAndExecute({
    bool forceOpen = false,
  }) async {
    final overlayGranted =
        await FlutterOverlayWindow.isPermissionGranted();

    final micGranted =
        await _voiceService.hasPermission(request: false);

    if (overlayGranted && micGranted) {
      _stopPermissionPolling();

      await _toggleOrStartOverlay(
        forceOpen: forceOpen,
      );
    }
  }

  // ==========================================================
  // OVERLAY + CONTINUOUS LISTENING
  // ==========================================================

  Future<void> _toggleOrStartOverlay({
    bool forceOpen = false,
  }) async {
    if (_isStartingOverlay || _isClosing) return;

    _isStartingOverlay = true;

    try {
      final isActive =
          await FlutterOverlayWindow.isActive();

      if (isActive) {
        if (!_voiceService.isListening) {
          await _startVoiceRecognition();
          await Future.delayed(const Duration(milliseconds: 300));
        }

        await _minimizeApp();
        return;
      }

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

      // Give OverlayService foreground service 350ms to establish foreground notification
      await Future.delayed(const Duration(milliseconds: 350));

      await _startVoiceRecognition();

      // Ensure SpeechRecognizer has acquired audio focus before minimizing
      await Future.delayed(const Duration(milliseconds: 350));

      await _minimizeApp();

    } catch (e, stack) {
      debugPrint('[Neuro] Overlay startup failed: $e');
      debugPrint('$stack');
    } finally {
      _isStartingOverlay = false;
    }
  }

  Future<void> _startVoiceRecognition() async {
    if (_voiceService.isListening) return;

    await _voiceService.startListening(
      language: _defaultLanguage,
    );

    debugPrint('[Neuro] Voice recognition started');
  }

  Future<void> _minimizeApp() async {
    try {
      await _controlChannel.invokeMethod('minimizeApp');
    } catch (e) {
      debugPrint('[Neuro] Minimize error: $e');
    }
  }

  // ==========================================================
  // CLOSE
  // ==========================================================

  Future<void> _closeNeuro() async {
    if (_isClosing) return;

    _isClosing = true;

    try {
      await _voiceService.stopListening();

      try {
        await FlutterOverlayWindow.closeOverlay();
      } catch (_) {}

      try {
        await _controlChannel.invokeMethod('closeApp');
      } catch (_) {}

      SystemNavigator.pop();

    } finally {
      _isClosing = false;
    }
  }

  // ==========================================================
  // DISPOSE
  // ==========================================================

  @override
  void dispose() {
    _stopPermissionPolling();

    _overlaySubscription?.cancel();

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
