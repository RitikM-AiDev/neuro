
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_overlay_window/flutter_overlay_window.dart';

import 'models/assistant_state.dart';
import 'services/voice_detector_service.dart';
import 'services/neuro_model_manager.dart';
import 'widgets/neural_circle.dart';

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

  StreamSubscription? _overlaySubscription;

  @override
  void initState() {
    super.initState();

    _overlaySubscription =
        FlutterOverlayWindow.overlayListener.listen((data) {
      if (!mounted || data == null) return;

      final msg = data.toString();

      if (msg.startsWith('listening:')) {
        final amp = double.tryParse(msg.substring(10)) ?? 0.0;

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
        final amp = double.tryParse(msg.substring(9)) ?? 0.0;

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
  State<HeadlessLauncher> createState() =>
      _HeadlessLauncherState();
}

class _HeadlessLauncherState extends State<HeadlessLauncher>
    with WidgetsBindingObserver {
  static const MethodChannel _controlChannel =
      MethodChannel('com.example.neuro_apps/control');

  late final VoiceDetectorService _voiceService;

  final NeuroModelManager _modelManager = NeuroModelManager();

  StreamSubscription? _overlaySubscription;

  Timer? _permissionPollTimer;

  bool _isStartingOverlay = false;
  bool _isDisposed = false;

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
    );

    _overlaySubscription =
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

      FlutterOverlayWindow.requestPermission().then((granted) async {
        if (granted == true) {
          final mic = await _voiceService.hasPermission(
            request: false,
          );

          if (mic) {
            _stopPermissionPolling();
            await _toggleOrStartOverlay(forceOpen: true);
          }
        }
      }).catchError((_) {});
    } else {
      final mic = await _voiceService.hasPermission(
        request: false,
      );

      if (mic) {
        await _toggleOrStartOverlay(forceOpen: true);
      }
    }
  }

  void _startPermissionPolling() {
    _permissionPollTimer?.cancel();

    _permissionPollTimer =
        Timer.periodic(const Duration(milliseconds: 500), (timer) async {
      final overlayGranted =
          await FlutterOverlayWindow.isPermissionGranted();

      if (overlayGranted) {
        final micGranted =
            await _voiceService.hasPermission(request: false);

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

  Future<void> _toggleOrStartOverlay({
    bool forceOpen = false,
  }) async {
    if (_isStartingOverlay || _isDisposed) return;

    _isStartingOverlay = true;

    try {
      final isActive = await FlutterOverlayWindow.isActive();

      if (isActive && !forceOpen) {
        await _voiceService.stopListening();
        await FlutterOverlayWindow.closeOverlay();
        await _minimizeApp();
        return;
      }

      // Download or reuse the local Silero VAD model.
      if (!_voiceService.isInitialized) {
        final modelPath = await _modelManager.ensureVadModel(
          onProgress: (progress) {
            debugPrint(
              'VAD model download: ${(progress * 100).toStringAsFixed(1)}%',
            );
          },
        );

        // Initialize Sherpa before starting the microphone.
        await _voiceService.initialize(
          vadModelPath: modelPath,
        );
      }

      if (!isActive) {
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
      }

      if (!_voiceService.isListening) {
        await _voiceService.startListening();
      }

      await _minimizeApp();
    } catch (e, stackTrace) {
      debugPrint('Neuro startup error: $e');
      debugPrintStack(stackTrace: stackTrace);
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
    _isDisposed = true;

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
