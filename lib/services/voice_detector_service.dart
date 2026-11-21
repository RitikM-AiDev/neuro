import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:record/record.dart';

import '../models/assistant_state.dart';

class VoiceDetectorService {
  final void Function(AssistantState state, double amplitude)
      onVoiceStateChanged;

  final void Function(String message)? onMessageSent;
  final void Function(String language, int? confidence)? onLanguageChanged;

  static const MethodChannel _speechChannel =
      MethodChannel('com.example.neuro_apps/speech');

  // Used only for runtime microphone permission check.
  // Official Android SpeechRecognizer handles all audio input.
  final AudioRecorder _permissionRecorder = AudioRecorder();

  bool _isListening = false;
  bool _isHardwareRecording = false;

  // Real-time voice activity detection state
  bool _isVoiceActive = false;
  double _smoothAmp = 0.0;

  // Timers
  Timer? _idleDebounceTimer;
  Timer? _silenceTimer;
  Timer? _finalResultWaitTimer;

  // Speech text buffering (complete vs partial)
  String _currentPartialText = '';
  String _accumulatedFinalText = '';
  String? _lastDispatchedText;
  String? _detectedLanguage;
  bool _hasSilenceElapsed = false;

  static const Duration _idleDebounceDuration = Duration(milliseconds: 500);
  static const Duration _silenceDispatchDuration = Duration(milliseconds: 1500);
  static const Duration _finalResultGracePeriod = Duration(milliseconds: 800);

  VoiceDetectorService({
    required this.onVoiceStateChanged,
    this.onMessageSent,
    this.onLanguageChanged,
  }) {
    _initChannelHandler();
  }

  bool get isListening => _isListening;
  bool get isHardwareRecording => _isHardwareRecording;
  String? get detectedLanguage => _detectedLanguage;
  String get currentSpeechText => _accumulatedFinalText.isNotEmpty
      ? _accumulatedFinalText
      : _currentPartialText;

  // ── MethodChannel Listener ──────────────────────────────────────────────────

  void _initChannelHandler() {
    _speechChannel.setMethodCallHandler((call) async {
      switch (call.method) {

        // ── Real-time RMS from Official Android SpeechRecognizer ────────────
        case 'onSpeechState':
          final args = call.arguments;
          if (args is! Map) return;

          final event = args['event'] as String? ?? '';
          final rmsDb = (args['rmsDb'] ?? args['rms'] as num?)?.toDouble() ?? 0.0;
          final isVoice = args['isVoiceActive'] as bool? ?? (rmsDb > -1.2);

          if (event == 'beginning') {
            _onSpeechActivityStarted();
          } else if (event == 'rms') {
            _handleRms(rmsDb, isVoice);
          } else if (event == 'processing') {
            // End of speech utterance detected by speech engine
            onVoiceStateChanged(AssistantState.thinking, 0.0);
          }
          break;

        // ── Hardware Session Recording State ────────────────────────────────
        case 'onRecordingState':
          final args = call.arguments;
          if (args is Map) {
            _isHardwareRecording = args['isRecording'] as bool? ?? false;
            debugPrint('[SpeechRecognizer] Recording state: $_isHardwareRecording');
          }
          break;

        // ── Partial Speech Result ───────────────────────────────────────────
        case 'onPartialResult':
          final args = call.arguments;
          if (args is! Map) return;

          final text = args['text'] as String? ?? '';
          final lang = args['language'] as String?;
          final sessionId = (args['sessionId'] as num?)?.toInt();

          _handleSpeechResult(text, lang, false, sessionId);
          break;

        // ── Final or Interim Speech Result ──────────────────────────────────
        case 'onSpeechResult':
          final args = call.arguments;
          if (args is! Map) return;

          final text = args['text'] as String? ?? '';
          final lang = args['language'] as String?;
          final isFinal = args['isFinal'] as bool? ?? false;
          final sessionId = (args['sessionId'] as num?)?.toInt();

          _handleSpeechResult(text, lang, isFinal, sessionId);
          break;

        // ── Automatic Language Detection Event (API 34+) ────────────────────
        case 'onLanguageDetected':
          final args = call.arguments;
          if (args is! Map) return;

          final lang = args['language'] as String? ?? '';
          final confidence = (args['confidence'] as num?)?.toInt();

          if (lang.isNotEmpty) {
            _detectedLanguage = lang;
            debugPrint('[Multilingual] Language detected: $lang (confidence=$confidence)');
            onLanguageChanged?.call(lang, confidence);
          }
          break;

        // ── Recognition Error ───────────────────────────────────────────────
        case 'onSpeechError':
          debugPrint('[SpeechRecognizer] Error event: ${call.arguments}');
          break;
      }
    });
  }

  // ── Audio Processing & VAD ────────────────────────────────────────────────

  void _onSpeechActivityStarted() {
    if (!_isListening) return;

    _isVoiceActive = true;
    _hasSilenceElapsed = false;

    _idleDebounceTimer?.cancel();
    _silenceTimer?.cancel();
    _finalResultWaitTimer?.cancel();

    onVoiceStateChanged(
      AssistantState.listening,
      _smoothAmp > 0.1 ? _smoothAmp : 0.25,
    );
  }

  void _handleRms(double rmsDb, bool isVoice) {
    if (!_isListening) return;

    // rmsDb ranges from -2.0 dB (silence) to 10.0+ dB (loud speech)
    // Low speech / whisper is around -1.2 dB to 0.5 dB
    final normalized = ((rmsDb + 2.0) / 10.0).clamp(0.0, 1.0);
    _smoothAmp = _smoothAmp * 0.25 + normalized * 0.75;

    if (isVoice) {
      _onSpeechActivityStarted();
      onVoiceStateChanged(AssistantState.listening, _smoothAmp);
    } else {
      // User is quiet / audio level is below voice threshold
      if (_isVoiceActive) {
        _isVoiceActive = false;

        // 1. Debounce 500 ms before switching animation to idle
        _idleDebounceTimer?.cancel();
        _idleDebounceTimer = Timer(_idleDebounceDuration, () {
          if (!_isVoiceActive && _isListening) {
            onVoiceStateChanged(AssistantState.idle, 0.0);
          }
        });

        // 2. Start 1.5s continuous silence countdown to dispatch message
        _silenceTimer?.cancel();
        _silenceTimer = Timer(_silenceDispatchDuration, () {
          _onSilenceTimeout();
        });
      }
    }
  }

  // ── 1.5-Second Silence Detection ──────────────────────────────────────────

  void _onSilenceTimeout() {
    _hasSilenceElapsed = true;

    // If final transcript is available, dispatch immediately
    if (_accumulatedFinalText.isNotEmpty) {
      _dispatchMessage();
    } else if (_currentPartialText.isNotEmpty) {
      // If only partial text is present, grant a 400 ms grace period for
      // onResults to finalize so the message is not truncated
      _finalResultWaitTimer?.cancel();
      _finalResultWaitTimer = Timer(const Duration(milliseconds: 400), () {
        _dispatchMessage();
      });
    }

    // Microphone remains continuously active!
  }

  // ── Speech-to-Text Handling (Partial vs Final) ─────────────────────────────

  void _handleSpeechResult(
    String text,
    String? language,
    bool isFinal,
    int? sessionId,
  ) {
    final recognized = text.trim();
    if (recognized.isEmpty) return;

    if (language != null && language.isNotEmpty) {
      _detectedLanguage = language;
    }

    // Incoming speech result confirms voice activity
    _onSpeechActivityStarted();

    if (!isFinal) {
      _currentPartialText = recognized;
      debugPrint('[STT] PARTIAL [${_detectedLanguage ?? "auto"}]: $recognized');
      return;
    }

    // Complete final transcription
    debugPrint('[STT] FINAL [${_detectedLanguage ?? "auto"}]: $recognized');

    // Avoid duplicate replay if this exact utterance was already dispatched
    if (_lastDispatchedText != null && recognized == _lastDispatchedText) {
      _lastDispatchedText = null;
      _currentPartialText = '';
      return;
    }

    _currentPartialText = '';

    if (_accumulatedFinalText.isEmpty) {
      _accumulatedFinalText = recognized;
    } else {
      if (!_accumulatedFinalText.endsWith(recognized) &&
          !_accumulatedFinalText.contains(recognized)) {
        _accumulatedFinalText = '$_accumulatedFinalText $recognized';
      }
    }

    _finalResultWaitTimer?.cancel();

    // If 1.5s silence already elapsed while waiting for final result, dispatch now!
    if (!_isVoiceActive && _hasSilenceElapsed) {
      _dispatchMessage();
    }
  }

  // ── Dispatch Complete Message ─────────────────────────────────────────────

  void _dispatchMessage() {
    final textToSend = _accumulatedFinalText.isNotEmpty
        ? _accumulatedFinalText.trim()
        : _currentPartialText.trim();

    // Reset speech text state immediately for the next utterance
    _accumulatedFinalText = '';
    _currentPartialText = '';
    _hasSilenceElapsed = false;
    _finalResultWaitTimer?.cancel();
    _silenceTimer?.cancel();

    if (textToSend.isNotEmpty) {
      _lastDispatchedText = textToSend;
      send_message(textToSend);
    }
  }

  void send_message(String voiceToTextData) {
    final message = voiceToTextData.trim();
    if (message.isEmpty) return;

    final langTag = _detectedLanguage != null ? ' [$_detectedLanguage]' : '';

    print('========================================');
    print('send_message$langTag: $message');
    print('========================================');

    onMessageSent?.call(message);
  }

  // ── Public API Methods ────────────────────────────────────────────────────

  Future<bool> hasPermission({bool request = false}) async {
    return _permissionRecorder.hasPermission(request: request);
  }

  Future<void> startListening({String language = 'auto'}) async {
    if (_isListening) return;

    final granted = await hasPermission(request: true);
    if (!granted) {
      debugPrint('[VoiceDetectorService] Microphone permission denied');
      return;
    }

    try {
      _isListening = true;
      _accumulatedFinalText = '';
      _currentPartialText = '';
      _smoothAmp = 0.0;
      _isVoiceActive = false;
      _hasSilenceElapsed = false;

      await _speechChannel.invokeMethod('startListening', {
        'language': language,
      });

      debugPrint('[VoiceDetectorService] Continuous SpeechRecognizer started (lang=$language)');
    } catch (e) {
      _isListening = false;
      debugPrint('[VoiceDetectorService] Failed to start listening: $e');
    }
  }

  Future<void> stopListening() async {
    if (!_isListening) return;

    _isListening = false;
    _idleDebounceTimer?.cancel();
    _silenceTimer?.cancel();
    _finalResultWaitTimer?.cancel();

    _accumulatedFinalText = '';
    _currentPartialText = '';
    _smoothAmp = 0.0;
    _isVoiceActive = false;
    _hasSilenceElapsed = false;

    try {
      await _speechChannel.invokeMethod('stopListening');
    } catch (e) {
      debugPrint('[VoiceDetectorService] Failed to stop listening: $e');
    }

    onVoiceStateChanged(AssistantState.idle, 0.0);
    debugPrint('[VoiceDetectorService] Continuous SpeechRecognizer stopped');
  }

  Future<void> setLanguage(String language) async {
    try {
      await _speechChannel.invokeMethod('setLanguage', {
        'language': language,
      });
      debugPrint('[VoiceDetectorService] Language updated to $language');
    } catch (e) {
      debugPrint('[VoiceDetectorService] Failed to set language: $e');
    }
  }

  Future<Map<String, dynamic>?> checkRecognitionSupport() async {
    try {
      final res = await _speechChannel.invokeMethod('checkRecognitionSupport');
      if (res is Map) {
        return Map<String, dynamic>.from(res);
      }
    } catch (e) {
      debugPrint('[VoiceDetectorService] Failed to check recognition support: $e');
    }
    return null;
  }

  Future<void> dispose() async {
    await stopListening();
    _speechChannel.setMethodCallHandler(null);
    await _permissionRecorder.dispose();
  }
}
