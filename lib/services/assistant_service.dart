import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../models/assistant_state.dart';

/// Events dispatched by the native Android VoiceAssistant architecture
enum AssistantEventType {
  hotwordDetected,
  recognitionStarted,
  partialTranscript,
  finalTranscript,
  commandProcessing,
  responseReady,
  returnedToStandby,
  recognitionError,
  unknown,
}

class AssistantEvent {
  final AssistantEventType type;
  final String text;
  final String language;
  final double rms;
  final String? error;
  final Map<String, dynamic> raw;

  AssistantEvent({
    required this.type,
    this.text = '',
    this.language = 'auto',
    this.rms = 0.0,
    this.error,
    required this.raw,
  });

  factory AssistantEvent.fromMap(Map<dynamic, dynamic> map) {
    final typeStr = map['type'] as String? ?? '';
    final type = switch (typeStr) {
      'HOTWORD_DETECTED' => AssistantEventType.hotwordDetected,
      'RECOGNITION_STARTED' => AssistantEventType.recognitionStarted,
      'PARTIAL_TRANSCRIPT' => AssistantEventType.partialTranscript,
      'FINAL_TRANSCRIPT' => AssistantEventType.finalTranscript,
      'COMMAND_PROCESSING' => AssistantEventType.commandProcessing,
      'RESPONSE_READY' => AssistantEventType.responseReady,
      'RETURNED_TO_STANDBY' => AssistantEventType.returnedToStandby,
      'RECOGNITION_ERROR' => AssistantEventType.recognitionError,
      _ => AssistantEventType.unknown,
    };

    return AssistantEvent(
      type: type,
      text: map['text'] as String? ?? map['command'] as String? ?? map['response'] as String? ?? '',
      language: map['language'] as String? ?? 'auto',
      rms: (map['rms'] as num?)?.toDouble() ?? 0.0,
      error: map['message'] as String? ?? map['error'] as String?,
      raw: Map<String, dynamic>.from(map),
    );
  }
}

/// Flutter interface to the Android Google Assistant architecture
class AssistantService {
  static const MethodChannel _methodChannel =
      MethodChannel('com.example.neuro_apps/assistant');

  static const EventChannel _eventChannel =
      EventChannel('com.example.neuro_apps/assistant_events');

  final void Function(AssistantState state, double amplitude)? onStateChanged;
  final void Function(String partialText)? onPartialText;
  final void Function(String finalCommand, String language)? onCommandReceived;
  final void Function(String response)? onResponseReady;

  StreamSubscription<dynamic>? _subscription;
  AssistantState _currentState = AssistantState.idle;

  AssistantService({
    this.onStateChanged,
    this.onPartialText,
    this.onCommandReceived,
    this.onResponseReady,
  }) {
    _initEventStream();
  }

  AssistantState get currentState => _currentState;

  void _initEventStream() {
    _subscription?.cancel();
    _subscription = _eventChannel.receiveBroadcastStream().listen(
      _handleEvent,
      onError: (dynamic error) {
        debugPrint('[AssistantService] Event stream error: $error');
      },
    );
  }

  void _handleEvent(dynamic raw) {
    if (raw is! Map) return;
    final event = AssistantEvent.fromMap(raw);

    debugPrint('[AssistantService] Native Event: ${event.type} ${event.text}');

    switch (event.type) {
      case AssistantEventType.hotwordDetected:
        _updateState(AssistantState.listening, 0.7);
        break;

      case AssistantEventType.recognitionStarted:
        _updateState(AssistantState.listening, event.rms > 0.0 ? event.rms : 0.4);
        break;

      case AssistantEventType.partialTranscript:
        _updateState(AssistantState.listening, 0.6);
        onPartialText?.call(event.text);
        break;

      case AssistantEventType.finalTranscript:
        _updateState(AssistantState.thinking, 0.0);
        if (event.text.isNotEmpty) {
          debugPrint('========================================');
          debugPrint('ASSISTANT COMMAND [${event.language}]: ${event.text}');
          debugPrint('========================================');
          onCommandReceived?.call(event.text, event.language);
          _executeNeuroAi(event.text, event.language);
        }
        break;

      case AssistantEventType.commandProcessing:
        _updateState(AssistantState.thinking, 0.0);
        break;

      case AssistantEventType.responseReady:
        _updateState(AssistantState.speaking, 0.85);
        onResponseReady?.call(event.text);
        break;

      case AssistantEventType.returnedToStandby:
        _updateState(AssistantState.idle, 0.0);
        break;

      case AssistantEventType.recognitionError:
        debugPrint('[AssistantService] Recognition Error: ${event.error}');
        _updateState(AssistantState.idle, 0.0);
        break;

      case AssistantEventType.unknown:
        break;
    }
  }

  void _updateState(AssistantState state, double amplitude) {
    _currentState = state;
    onStateChanged?.call(state, amplitude);
  }

  /// Default Neuro AI Command Handler
  /// Formulates appropriate response in Tamil or English and sends to TTS
  Future<void> _executeNeuroAi(String command, String language) async {
    final lower = command.toLowerCase();
    String responseText;

    // Multilingual responses (Tamil / English)
    if (lower.contains('வணக்கம்') || lower.contains('eppadi irukinga') || lower.contains('epdi irukinga')) {
      responseText = 'வணக்கம்! நான் நல்லா இருக்கேன். உங்களுக்கு என்ன உதவி வேணும்?';
    } else if (lower.contains('hello') || lower.contains('hi') || lower.contains('hey')) {
      responseText = 'Hello! I am Neuro. How can I assist you today?';
    } else if (lower.contains('time') || lower.contains('நேரம்')) {
      final now = DateTime.now();
      final timeStr = '${now.hour}:${now.minute.toString().padLeft(2, '0')}';
      responseText = language == 'ta-IN'
          ? 'இப்போ மணி $timeStr'
          : 'The current time is $timeStr';
    } else if (lower.contains('who are you') || lower.contains('நீ யார்')) {
      responseText = language == 'ta-IN'
          ? 'நான் நியூரோ, உங்கள் தனிப்பட்ட குரல் உதவியாளர்.'
          : 'I am Neuro, your personal neural voice assistant.';
    } else {
      responseText = language == 'ta-IN'
          ? 'புரிந்தது: $command. செய்ய முயற்சிக்கிறேன்.'
          : 'Understood: $command. Processing your request.';
    }

    // Deliver response to Android TextToSpeech engine
    await sendAiResponse(responseText);
  }

  // ── Public API ─────────────────────────────────────────────────────────────

  Future<bool> hasPermission({bool request = false}) async {
    try {
      final res = await _methodChannel.invokeMethod<bool>(
        'hasPermission',
        {'request': request},
      );
      return res ?? false;
    } catch (e) {
      debugPrint('[AssistantService] hasPermission error: $e');
      return false;
    }
  }

  Future<Map<String, dynamic>?> initializeAssistant() async {
    try {
      final res = await _methodChannel.invokeMethod('initializeAssistant');
      if (res is Map) return Map<String, dynamic>.from(res);
    } catch (e) {
      debugPrint('[AssistantService] initializeAssistant error: $e');
    }
    return null;
  }

  Future<void> requestAssistantRole() async {
    try {
      await _methodChannel.invokeMethod('requestAssistantRole');
    } catch (e) {
      debugPrint('[AssistantService] requestAssistantRole error: $e');
    }
  }

  Future<void> startListening() async {
    try {
      await _methodChannel.invokeMethod('startListening');
    } catch (e) {
      debugPrint('[AssistantService] startListening error: $e');
    }
  }

  Future<void> stopListening() async {
    try {
      await _methodChannel.invokeMethod('stopListening');
    } catch (e) {
      debugPrint('[AssistantService] stopListening error: $e');
    }
  }

  Future<void> sendAiResponse(String response) async {
    try {
      await _methodChannel.invokeMethod('sendAiResponse', {'response': response});
    } catch (e) {
      debugPrint('[AssistantService] sendAiResponse error: $e');
    }
  }

  Future<Map<String, dynamic>?> getAssistantStatus() async {
    try {
      final res = await _methodChannel.invokeMethod('getAssistantStatus');
      if (res is Map) return Map<String, dynamic>.from(res);
    } catch (e) {
      debugPrint('[AssistantService] getAssistantStatus error: $e');
    }
    return null;
  }

  void dispose() {
    _subscription?.cancel();
    _subscription = null;
  }
}
