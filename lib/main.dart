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

    // Listen to real-time voice amplitude and state stream from the detector
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
            size: 190,
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

class _HeadlessLauncherState extends State<HeadlessLauncher> {
  static const MethodChannel _controlChannel =
      MethodChannel('com.example.neuro_apps/control');

  late final VoiceDetectorService _voiceService;
  bool _needsPermissions = false;
  bool _isRequesting = false;

  @override
  void initState() {
    super.initState();
    _voiceService = VoiceDetectorService(
      onVoiceStateChanged: (state, amp) {
        if (state == AssistantState.listening) {
          FlutterOverlayWindow.shareData('listening:${amp.toStringAsFixed(3)}');
        } else {
          FlutterOverlayWindow.shareData('idle:0.0');
        }
      },
    );
    _checkAndExecute();
  }

  Future<void> _checkAndExecute() async {
    final overlayGranted = await FlutterOverlayWindow.isPermissionGranted();
    final micGranted = await _voiceService.hasPermission(request: false);

    if (overlayGranted && micGranted) {
      await _toggleOrStartOverlay();
    } else {
      if (mounted) {
        setState(() => _needsPermissions = true);
      }
    }
  }

  Future<void> _toggleOrStartOverlay() async {
    final isActive = await FlutterOverlayWindow.isActive();

    if (isActive) {
      // If already active when launched, toggle it off (clean exit)
      await _voiceService.stopListening();
      await FlutterOverlayWindow.closeOverlay();
      await _minimizeApp();
      return;
    }

    // 1. Show the floating overlay with touch pass-through enabled
    await FlutterOverlayWindow.showOverlay(
      enableDrag: true,
      overlayTitle: 'Neuro Assistant',
      overlayContent: 'Neural Voice Assistant Active',
      flag: OverlayFlag.clickThrough, // All touches pass through to backscreen apps
      visibility: NotificationVisibility.visibilityPublic,
      positionGravity: PositionGravity.auto,
      height: 200,
      width: 200,
    );

    // 2. Start continuous voice detection
    await _voiceService.startListening();

    // 3. Immediately minimize app so no full screen or app view is shown
    await _minimizeApp();
  }

  Future<void> _minimizeApp() async {
    try {
      await _controlChannel.invokeMethod('minimizeApp');
    } catch (_) {}
  }

  Future<void> _requestPermissionsAndStart() async {
    if (_isRequesting) return;
    setState(() => _isRequesting = true);

    final overlayGranted = await FlutterOverlayWindow.isPermissionGranted();
    if (!overlayGranted) {
      await FlutterOverlayWindow.requestPermission();
    }

    final micGranted = await _voiceService.hasPermission(request: true);

    final hasOverlay = await FlutterOverlayWindow.isPermissionGranted();

    if (hasOverlay && micGranted) {
      if (mounted) setState(() => _needsPermissions = false);
      await _toggleOrStartOverlay();
    } else {
      if (mounted) setState(() => _isRequesting = false);
    }
  }

  @override
  void dispose() {
    _voiceService.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    // If permissions are already granted, keep UI completely transparent while it minimizes
    if (!_needsPermissions) {
      return const Scaffold(
        backgroundColor: Colors.transparent,
        body: SizedBox.shrink(),
      );
    }

    // If first-time permissions are needed, show a minimal floating translucent prompt
    return Scaffold(
      backgroundColor: Colors.transparent,
      body: Center(
        child: Container(
          margin: const EdgeInsets.symmetric(horizontal: 32),
          padding: const EdgeInsets.all(24),
          decoration: BoxDecoration(
            color: const Color(0xFF050810).withValues(alpha: 0.92),
            borderRadius: BorderRadius.circular(20),
            border: Border.all(
              color: const Color(0xFF00E5FF).withValues(alpha: 0.4),
              width: 1.5,
            ),
            boxShadow: [
              BoxShadow(
                color: const Color(0xFF00E5FF).withValues(alpha: 0.2),
                blurRadius: 24,
                spreadRadius: 2,
              ),
            ],
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text(
                'NEURAL ASSISTANT',
                style: TextStyle(
                  color: Color(0xFF00E5FF),
                  fontSize: 13,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 3,
                ),
              ),
              const SizedBox(height: 14),
              const Text(
                'Enable Overlay & Microphone access to let the neural circle float above other apps and react to voice.',
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: Colors.white70,
                  fontSize: 13,
                  height: 1.4,
                ),
              ),
              const SizedBox(height: 22),
              GestureDetector(
                onTap: _requestPermissionsAndStart,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(30),
                    gradient: const LinearGradient(
                      colors: [Color(0xFF00E5FF), Color(0xFF7B2FBE)],
                    ),
                    boxShadow: [
                      BoxShadow(
                        color: const Color(0xFF00E5FF).withValues(alpha: 0.4),
                        blurRadius: 14,
                      ),
                    ],
                  ),
                  child: Text(
                    _isRequesting ? 'Authorizing...' : 'Grant & Activate',
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 13,
                      fontWeight: FontWeight.bold,
                      letterSpacing: 1,
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
