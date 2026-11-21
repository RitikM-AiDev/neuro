import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_overlay_window/flutter_overlay_window.dart';

import 'models/assistant_state.dart';
import 'services/assistant_service.dart';
import 'widgets/neural_circle.dart';

// ============================================================
// 1. OVERLAY DISPLAY ENTRY POINT
// ============================================================

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
  StreamSubscription<dynamic>? _overlaySubscription;

  @override
  void initState() {
    super.initState();

    _overlaySubscription = FlutterOverlayWindow.overlayListener.listen((data) {
      if (!mounted || data == null) return;
      final msg = data.toString();

      if (msg.startsWith('listening:')) {
        final amp = double.tryParse(msg.substring(10)) ?? 0.4;
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
        final amp = double.tryParse(msg.substring(9)) ?? 0.8;
        setState(() {
          _state = AssistantState.speaking;
          _amplitude = amp;
        });
      }
    });
  }

  void _onTapOrb() {
    // Tapping the orb directly triggers active listening
    FlutterOverlayWindow.shareData('trigger_listening');
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
          child: GestureDetector(
            onTap: _onTapOrb,
            child: NeuralCircle(
              state: _state,
              amplitude: _amplitude,
              size: 100,
            ),
          ),
        ),
      ),
    );
  }
}

// ============================================================
// 2. MAIN CONTROLLER & ASSISTANT INITIALIZATION
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

class HeadlessLauncher extends StatefulWidget {
  const HeadlessLauncher({super.key});

  @override
  State<HeadlessLauncher> createState() => _HeadlessLauncherState();
}

class _HeadlessLauncherState extends State<HeadlessLauncher>
    with WidgetsBindingObserver {
  static const MethodChannel _controlChannel =
      MethodChannel('com.example.neuro_apps/control');

  late final AssistantService _assistantService;
  Timer? _permissionPollTimer;
  StreamSubscription<dynamic>? _overlaySubscription;

  bool _isStartingOverlay = false;
  bool _isClosing = false;
  bool _micGranted = false;
  bool _overlayGranted = false;
  bool _isDefaultAssistant = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);

    _assistantService = AssistantService(
      onStateChanged: (state, amp) {
        // Broadcast assistant state to the floating overlay bubble
        switch (state) {
          case AssistantState.listening:
            FlutterOverlayWindow.shareData('listening:${amp.toStringAsFixed(3)}');
            break;
          case AssistantState.thinking:
            FlutterOverlayWindow.shareData('thinking:0.0');
            break;
          case AssistantState.speaking:
            FlutterOverlayWindow.shareData('speaking:${amp.toStringAsFixed(3)}');
            break;
          case AssistantState.idle:
            FlutterOverlayWindow.shareData('idle:0.0');
            break;
        }
      },
      onCommandReceived: (command, language) {
        debugPrint('[Neuro] Executing voice command: "$command" ($language)');
      },
      onResponseReady: (response) {
        debugPrint('[Neuro] Assistant Speaking: "$response"');
      },
    );

    _controlChannel.setMethodCallHandler((call) async {
      if (call.method == 'onAppReopened') {
        await _checkAndLaunchOverlay();
      }
    });

    _overlaySubscription = FlutterOverlayWindow.overlayListener.listen((data) async {
      final msg = data.toString();
      if (msg == 'close') {
        await _closeNeuro();
      } else if (msg == 'trigger_listening') {
        await _assistantService.startListening();
      }
    });

    _initAssistantAndOverlay();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refreshStatus();
      _checkAndLaunchOverlay();
    }
  }

  Future<void> _refreshStatus() async {
    final mic = await _assistantService.hasPermission(request: false);
    final overlay = await FlutterOverlayWindow.isPermissionGranted();
    final status = await _assistantService.initializeAssistant();
    final isDefault = status?['isDefaultAssistant'] == true;

    if (mounted) {
      setState(() {
        _micGranted = mic;
        _overlayGranted = overlay;
        _isDefaultAssistant = isDefault;
      });
    }
  }

  Future<void> _initAssistantAndOverlay() async {
    // 1. Request microphone permission explicitly so OS dialog displays on launch
    final mic = await _assistantService.hasPermission(request: true);
    final overlay = await FlutterOverlayWindow.isPermissionGranted();
    final status = await _assistantService.initializeAssistant();
    final isDefault = status?['isDefaultAssistant'] == true;

    if (mounted) {
      setState(() {
        _micGranted = mic;
        _overlayGranted = overlay;
        _isDefaultAssistant = isDefault;
      });
    }

    // 2. Launch Floating Overlay Window if permissions are ready
    if (overlay && mic) {
      await _startOverlay();
      return;
    }

    if (!overlay) {
      _startPermissionPolling();
      FlutterOverlayWindow.requestPermission().catchError((error) {
        debugPrint('[Neuro] Overlay permission error: $error');
        return false;
      });
    }
  }

  void _startPermissionPolling() {
    _permissionPollTimer?.cancel();
    _permissionPollTimer = Timer.periodic(
      const Duration(milliseconds: 500),
      (timer) async {
        final overlayGranted = await FlutterOverlayWindow.isPermissionGranted();
        if (overlayGranted) {
          _stopPermissionPolling();
          await _refreshStatus();
          await _startOverlay();
        }
      },
    );
  }

  void _stopPermissionPolling() {
    _permissionPollTimer?.cancel();
    _permissionPollTimer = null;
  }

  Future<void> _checkAndLaunchOverlay() async {
    final overlayGranted = await FlutterOverlayWindow.isPermissionGranted();
    if (overlayGranted) {
      _stopPermissionPolling();
      await _refreshStatus();
      await _startOverlay();
    }
  }

  Future<void> _startOverlay() async {
    if (_isStartingOverlay || _isClosing) return;
    _isStartingOverlay = true;

    try {
      final isActive = await FlutterOverlayWindow.isActive();
      if (!isActive) {
        await FlutterOverlayWindow.showOverlay(
          enableDrag: true,
          overlayTitle: 'Neuro Assistant',
          overlayContent: 'Hey Neuro Assistant Active',
          flag: OverlayFlag.defaultFlag,
          alignment: OverlayAlignment.bottomCenter,
          startPosition: const OverlayPosition(0, 30),
          visibility: NotificationVisibility.visibilityPublic,
          positionGravity: PositionGravity.none,
          height: 110,
          width: 110,
        );
        await Future.delayed(const Duration(milliseconds: 300));
      }

      final mic = await _assistantService.hasPermission(request: false);
      if (mic) {
        await _minimizeApp();
      }
    } catch (e, stack) {
      debugPrint('[Neuro] Overlay startup error: $e\n$stack');
    } finally {
      _isStartingOverlay = false;
    }
  }

  Future<void> _minimizeApp() async {
    try {
      await _controlChannel.invokeMethod('minimizeApp');
    } catch (e) {
      debugPrint('[Neuro] Minimize error: $e');
    }
  }

  Future<void> _closeNeuro() async {
    if (_isClosing) return;
    _isClosing = true;

    try {
      await _assistantService.stopListening();
      try { await FlutterOverlayWindow.closeOverlay(); } catch (_) {}
      try { await _controlChannel.invokeMethod('closeApp'); } catch (_) {}
      SystemNavigator.pop();
    } finally {
      _isClosing = false;
    }
  }

  @override
  void dispose() {
    _stopPermissionPolling();
    _overlaySubscription?.cancel();
    _assistantService.dispose();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0D0D14),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const SizedBox(height: 20),
              Center(
                child: Column(
                  children: [
                    Container(
                      width: 80,
                      height: 80,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        boxShadow: [
                          BoxShadow(
                            color: const Color(0xFF00E5FF).withValues(alpha: 0.3),
                            blurRadius: 20,
                            spreadRadius: 2,
                          ),
                        ],
                        gradient: const LinearGradient(
                          colors: [Color(0xFF00E5FF), Color(0xFF7B2FBE)],
                          begin: Alignment.topLeft,
                          end: Alignment.bottomRight,
                        ),
                      ),
                      child: const Icon(Icons.mic, size: 40, color: Colors.white),
                    ),
                    const SizedBox(height: 16),
                    const Text(
                      'Neuro Voice Assistant',
                      style: TextStyle(
                        fontSize: 24,
                        fontWeight: FontWeight.bold,
                        color: Colors.white,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      'Google SpeechRecognizer Voice Engine',
                      style: TextStyle(
                        fontSize: 14,
                        color: Colors.white.withValues(alpha: 0.6),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 32),
              _buildStatusTile(
                icon: Icons.mic_none,
                title: 'Microphone Permission',
                subtitle: _micGranted ? 'Granted' : 'Required for voice recognition',
                isReady: _micGranted,
                actionLabel: 'Grant',
                onAction: () async {
                  await _assistantService.hasPermission(request: true);
                  await _refreshStatus();
                },
              ),
              const SizedBox(height: 12),
              _buildStatusTile(
                icon: Icons.layers_outlined,
                title: 'Floating Overlay Window',
                subtitle: _overlayGranted ? 'Granted' : 'Required for floating orb',
                isReady: _overlayGranted,
                actionLabel: 'Grant',
                onAction: () async {
                  await FlutterOverlayWindow.requestPermission();
                  await _refreshStatus();
                },
              ),
              const SizedBox(height: 12),
              _buildStatusTile(
                icon: Icons.assistant,
                title: 'Default Assistant App',
                subtitle: _isDefaultAssistant ? 'Neuro Active' : 'Set as default digital assistant',
                isReady: _isDefaultAssistant,
                actionLabel: 'Set',
                onAction: () async {
                  await _assistantService.requestAssistantRole();
                  await _refreshStatus();
                },
              ),
              const Spacer(),
              ElevatedButton.icon(
                onPressed: () async {
                  await _assistantService.startListening();
                },
                icon: const Icon(Icons.record_voice_over),
                label: const Text('Test Voice Recognition'),
                style: ElevatedButton.styleFrom(
                  backgroundColor: const Color(0xFF00E5FF),
                  foregroundColor: Colors.black,
                  padding: const EdgeInsets.symmetric(vertical: 16),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                  ),
                ),
              ),
              const SizedBox(height: 12),
              OutlinedButton.icon(
                onPressed: () async {
                  await _startOverlay();
                },
                icon: const Icon(Icons.open_in_new),
                label: const Text('Open Floating Orb & Minimize'),
                style: OutlinedButton.styleFrom(
                  foregroundColor: Colors.white,
                  side: BorderSide(color: Colors.white.withValues(alpha: 0.2)),
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                  ),
                ),
              ),
              const SizedBox(height: 16),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildStatusTile({
    required IconData icon,
    required String title,
    required String subtitle,
    required bool isReady,
    required String actionLabel,
    required VoidCallback onAction,
  }) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xFF161622),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: isReady
              ? const Color(0xFF00E5FF).withValues(alpha: 0.4)
              : Colors.white.withValues(alpha: 0.08),
        ),
      ),
      child: Row(
        children: [
          Icon(
            icon,
            color: isReady ? const Color(0xFF00E5FF) : Colors.white54,
            size: 28,
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: const TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.w600,
                    fontSize: 15,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  subtitle,
                  style: TextStyle(
                    color: isReady ? const Color(0xFF00E5FF) : Colors.white38,
                    fontSize: 12,
                  ),
                ),
              ],
            ),
          ),
          if (!isReady)
            ElevatedButton(
              onPressed: onAction,
              style: ElevatedButton.styleFrom(
                backgroundColor: const Color(0xFF00E5FF),
                foregroundColor: Colors.black,
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
              ),
              child: Text(actionLabel),
            )
          else
            const Icon(Icons.check_circle, color: Color(0xFF00E5FF), size: 24),
        ],
      ),
    );
  }
}
