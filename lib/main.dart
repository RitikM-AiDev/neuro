
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_overlay_window/flutter_overlay_window.dart';

import 'models/assistant_state.dart';
import 'services/tools.dart';
import 'services/voice_detector_service.dart';
import 'widgets/neural_circle.dart';

@pragma('vm:entry-point')
Future<void> overlayMain() async {
  WidgetsFlutterBinding.ensureInitialized();
  try {
    await dotenv.load(fileName: '.env');
  } catch (e) {
    debugPrint('[Neuro Overlay] Failed to load .env: $e');
  }
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

  late final VoiceDetectorService _voiceService;
  StreamSubscription? _overlaySubscription;

  @override
  void initState() {
    super.initState();

    _voiceService = VoiceDetectorService(
      onVoiceStateChanged: (state, amp) {
        if (!mounted) return;
        setState(() {
          _state = state;
          _amplitude = amp;
        });
      },
      onResponseReceived: (text) {
        debugPrint('[Neuro] Gemini Response: $text');
        if (!mounted) return;
        setState(() {
          _state = AssistantState.speaking;
          _amplitude = 0.8;
        });
      },
    );

    _overlaySubscription =
        FlutterOverlayWindow.overlayListener.listen((data) async {
      if (data == 'close') {
        await _voiceService.stopListening();
        await FlutterOverlayWindow.closeOverlay();
      }
    });
  }

  Future<void> _onOverlayTap() async {
    try {
      await _voiceService.toggleRecording();
    } catch (e, stackTrace) {
      debugPrint('[Neuro Overlay] Mic toggle error: $e');
      debugPrintStack(stackTrace: stackTrace);
    }
  }

  String get _statusLabel {
    switch (_state) {
      case AssistantState.listening:
        return 'Mic On';
      case AssistantState.thinking:
        return 'Thinking...';
      case AssistantState.speaking:
        return 'Done';
      case AssistantState.idle:
        return 'Mic Off';
    }
  }

  Color get _statusColor {
    switch (_state) {
      case AssistantState.listening:
        return const Color(0xFF00E676);
      case AssistantState.thinking:
        return const Color(0xFFFFD54F);
      case AssistantState.speaking:
        return const Color(0xFF00E5FF);
      case AssistantState.idle:
        return Colors.white70;
    }
  }

  @override
  void dispose() {
    _overlaySubscription?.cancel();
    _voiceService.dispose();
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
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: _onOverlayTap,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                NeuralCircle(
                  state: _state,
                  amplitude: _amplitude,
                  size: 78,
                ),
                const SizedBox(height: 4),
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 8,
                    vertical: 2,
                  ),
                  decoration: BoxDecoration(
                    color: Colors.black.withValues(alpha: 0.72),
                    borderRadius: BorderRadius.circular(10),
                    border: Border.all(
                      color: _statusColor.withValues(alpha: 0.8),
                      width: 0.8,
                    ),
                  ),
                  child: Text(
                    _statusLabel,
                    style: TextStyle(
                      color: _statusColor,
                      fontSize: 10,
                      fontWeight: FontWeight.w600,
                      letterSpacing: 0.3,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  try {
    await dotenv.load(fileName: '.env');
  } catch (e) {
    debugPrint('[Neuro] Failed to load .env: $e');
  }
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

  StreamSubscription? _overlaySubscription;

  Timer? _permissionPollTimer;

  bool _isStartingOverlay = false;
  bool _isDisposed = false;
  bool _accessibilityPrompted = false;

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
      onVoiceStateChanged: (_, __) {},
    );

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

    final accessibilityGranted =
        await ToolsService.isAccessibilityEnabled();

    if (overlayGranted && micGranted && accessibilityGranted) {
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
          await _ensureAccessibilityOrStartOverlay();
        }
      }).catchError((_) {});
    } else {
      await _ensureAccessibilityOrStartOverlay();
    }
  }

  Future<void> _ensureAccessibilityOrStartOverlay() async {
    final overlayGranted =
        await FlutterOverlayWindow.isPermissionGranted();
    final micGranted =
        await _voiceService.hasPermission(request: false);
    if (!overlayGranted || !micGranted) return;

    final accessibilityGranted =
        await ToolsService.isAccessibilityEnabled();
    if (accessibilityGranted) {
      _stopPermissionPolling();
      await _toggleOrStartOverlay(forceOpen: true);
      return;
    }

    _startPermissionPolling();
    if (!_accessibilityPrompted) {
      _accessibilityPrompted = true;
      await ToolsService.openAccessibilitySettings();
    }
  }

  void _startPermissionPolling() {
    _permissionPollTimer?.cancel();

    _permissionPollTimer =
        Timer.periodic(const Duration(milliseconds: 500), (timer) async {
      final overlayGranted =
          await FlutterOverlayWindow.isPermissionGranted();
      if (!overlayGranted) return;

      final micGranted =
          await _voiceService.hasPermission(request: false);
      if (!micGranted) return;

      final accessibilityGranted =
          await ToolsService.isAccessibilityEnabled();
      if (accessibilityGranted) {
        _stopPermissionPolling();
        await _toggleOrStartOverlay(forceOpen: true);
      } else if (!_accessibilityPrompted) {
        _accessibilityPrompted = true;
        await ToolsService.openAccessibilitySettings();
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

    final accessibilityGranted =
        await ToolsService.isAccessibilityEnabled();

    if (overlayGranted && micGranted && accessibilityGranted) {
      _stopPermissionPolling();

      await _toggleOrStartOverlay(
        forceOpen: forceOpen,
      );
    } else if (overlayGranted && micGranted && !accessibilityGranted) {
      _startPermissionPolling();
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

      try {
        await _controlChannel.invokeMethod('registerOverlayChannels');
      } catch (_) {}

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
