import 'dart:async';
import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:flutter/foundation.dart';
import 'package:record/record.dart';

import '../models/assistant_state.dart';
import 'backend_service.dart';
import 'gemini_service.dart';
import 'tools.dart';

/// Tap-to-toggle microphone recorder: records PCM16 audio while active and
/// sends the recorded utterance to Gemini + backend when toggled off.
class VoiceDetectorService {
  final void Function(AssistantState state, double amplitude)
      onVoiceStateChanged;
  final void Function(String responseText)? onResponseReceived;
  final GeminiService _geminiService;
  final BackendService _backendService;

  final AudioRecorder _recorder = AudioRecorder();
  StreamSubscription<Uint8List>? _streamSub;
  final BytesBuilder _recordedPcmBytes = BytesBuilder(copy: false);

  bool _isListening = false;
  bool _isSending = false;
  double _smoothAmp = 0.0;

  static const int _sampleRate = 16000;
  static const int _numChannels = 1;
  static const int _bitsPerSample = 16;

  // Minimum 120ms (3840 bytes) to avoid sending accidental double-taps.
  static const int _minSpeechBytes =
      ((_sampleRate * 120) ~/ 1000) * (_bitsPerSample ~/ 8);

  VoiceDetectorService({
    required this.onVoiceStateChanged,
    this.onResponseReceived,
    GeminiService? geminiService,
    BackendService? backendService,
  })  : _geminiService = geminiService ?? GeminiService(),
        _backendService = backendService ?? BackendService();

  bool get isListening => _isListening;
  bool get isSending => _isSending;

  Future<bool> hasPermission({bool request = false}) async {
    return _recorder.hasPermission(request: request);
  }

  /// Toggles recording: starts mic when off, stops & sends to Gemini when on.
  Future<void> toggleRecording() async {
    if (_isSending) return;
    if (_isListening) {
      await stopAndSend();
    } else {
      await startListening();
    }
  }

  Future<void> startListening() async {
    if (_isListening || _isSending) return;

    final permission = await _recorder.hasPermission();
    if (!permission) {
      throw StateError('Microphone permission not granted.');
    }

    _recordedPcmBytes.clear();
    _smoothAmp = 0.0;

    final stream = await _recorder.startStream(
      const RecordConfig(
        encoder: AudioEncoder.pcm16bits,
        sampleRate: _sampleRate,
        numChannels: _numChannels,
      ),
    );

    _isListening = true;
    onVoiceStateChanged(AssistantState.listening, 0.2);
    debugPrint('[Mic] Mic ON — recording started (16kHz PCM16).');

    _streamSub = stream.listen(
      _onAudioChunk,
      onError: (error) {
        debugPrint('[Mic] Stream error: $error');
        stopListening();
      },
      onDone: () {
        _isListening = false;
      },
    );
  }

  void _onAudioChunk(Uint8List bytes) {
    if (!_isListening || bytes.length < 2) return;
    _recordedPcmBytes.add(bytes);

    final sampleCount = bytes.length ~/ 2;
    if (sampleCount > 0) {
      final rms = _calculatePcm16Rms(bytes, sampleCount);
      final rawAmp = ((rms / 32768.0) * 4.0).clamp(0.0, 1.0);
      _smoothAmp = _smoothAmp * 0.3 + rawAmp * 0.7;
      onVoiceStateChanged(AssistantState.listening, _smoothAmp);
    }
  }

  Future<void> stopAndSend() async {
    if (!_isListening) return;

    await _streamSub?.cancel();
    _streamSub = null;

    try {
      await _recorder.stop();
    } catch (_) {}
    _isListening = false;
    _smoothAmp = 0.0;

    final pcmBytes = _recordedPcmBytes.takeBytes();
    debugPrint('[Mic] Mic OFF — captured ${pcmBytes.length} PCM bytes.');

    if (pcmBytes.length < _minSpeechBytes) {
      debugPrint(
        '[Mic] Recording too short (${pcmBytes.length} bytes < $_minSpeechBytes); ignoring.',
      );
      onVoiceStateChanged(AssistantState.idle, 0.0);
      return;
    }

    await _processRecordedAudio(pcmBytes);
  }

  Future<void> _processRecordedAudio(Uint8List pcmBytes) async {
    if (_isSending) return;

    _isSending = true;
    onVoiceStateChanged(AssistantState.thinking, 0.0);

    try {
      final wavBytes = _wrapPcm16AsWav(pcmBytes);
      debugPrint(
        '[Gemini] Sending ${pcmBytes.length} PCM bytes (${wavBytes.length} WAV bytes)...',
      );

      final text = await _geminiService.generateFromSpeechAudio(wavBytes);
      if (text != null && text.isNotEmpty) {
        final transcript = _extractTranscript(text);
        if (_isNonSpeechTranscript(text, transcript)) {
          debugPrint('[Neuro] Non-speech/silence detected ($transcript); ignoring.');
          onVoiceStateChanged(AssistantState.idle, 0.0);
          return;
        }

        debugPrint('[Neuro] Transcribed text: $transcript');

        final deviceAction = _extractDeviceAction(text);
        final toolCall = _extractToolCall(text);
        final backendFuture = _backendService.send_query(transcript);

        if (deviceAction != null) {
          debugPrint('[Tools] Executing dynamic DeviceAction: $deviceAction');
          final actionResult = await executeDeviceAction(deviceAction);
          debugPrint('[Tools] DeviceAction result -> $actionResult');
        } else if (toolCall != null) {
          debugPrint(
            '[Tools] Executing LLM tool: ${toolCall.name} with ${toolCall.params}',
          );
          final executed = await ToolsService.executeTool(
            toolCall.name,
            toolCall.params,
          );
          debugPrint('[Tools] ${toolCall.name} executed -> $executed');
        }

        final backendResult = await backendFuture;
        final backendReply = backendResult?['reply']?.toString().trim();
        final finalOutput = (backendReply != null && backendReply.isNotEmpty)
            ? backendReply
            : text;

        onResponseReceived?.call(finalOutput);
        onVoiceStateChanged(AssistantState.speaking, 0.85);

        await Future.delayed(const Duration(milliseconds: 1200));
        if (!_isListening) {
          onVoiceStateChanged(AssistantState.idle, 0.0);
        }
        return;
      }
      debugPrint('[Gemini] No text returned for recording.');
    } finally {
      _recordedPcmBytes.clear();
      _isSending = false;
    }

    if (!_isListening) {
      onVoiceStateChanged(AssistantState.idle, 0.0);
    }
  }

  double _calculatePcm16Rms(Uint8List bytes, int sampleCount) {
    final data = ByteData.sublistView(bytes);
    double sumSquares = 0.0;

    for (int i = 0; i < sampleCount; i++) {
      final sample = data.getInt16(i * 2, Endian.little).toDouble();
      sumSquares += sample * sample;
    }

    return sqrt(sumSquares / sampleCount);
  }

  bool _isNonSpeechTranscript(String rawOutput, String transcript) {
    final upperRaw = rawOutput.trim().toUpperCase();
    if (upperRaw == 'SILENCE') return true;

    final cleaned = transcript.trim().toLowerCase();
    if (cleaned.isEmpty) return true;

    final timestampOnly = RegExp(r'^[\[\(]?\d{1,2}:\d{2}(:\d{2})?[\]\)]?$');
    if (timestampOnly.hasMatch(cleaned)) return true;

    const placeholders = {
      'silence',
      '[silence]',
      '(silence)',
      '[no speech]',
      '(no speech)',
      '[inaudible]',
      '(inaudible)',
      'none',
      'n/a',
    };
    return placeholders.contains(cleaned);
  }

  Map<String, dynamic>? _extractDeviceAction(String geminiOutput) {
    for (final line in geminiOutput.split('\n')) {
      final trimmed = line.trim();
      if (trimmed.toLowerCase().startsWith('deviceaction:')) {
        final rawJson = trimmed.substring('deviceaction:'.length).trim();
        if (rawJson.isNotEmpty) {
          try {
            final decoded = jsonDecode(rawJson);
            if (decoded is Map<String, dynamic>) {
              return decoded;
            }
          } catch (_) {}
        }
      }
    }
    return null;
  }

  ({String name, Map<String, dynamic> params})? _extractToolCall(
    String geminiOutput,
  ) {
    String? toolName;
    Map<String, dynamic> params = const {};

    for (final line in geminiOutput.split('\n')) {
      final trimmed = line.trim();
      final lower = trimmed.toLowerCase();
      if (lower.startsWith('tool:')) {
        final candidate = trimmed.substring('tool:'.length).trim();
        if (candidate.isNotEmpty && candidate.toLowerCase() != 'none') {
          toolName = candidate;
        }
      } else if (lower.startsWith('params:')) {
        final rawJson = trimmed.substring('params:'.length).trim();
        if (rawJson.isNotEmpty) {
          try {
            final decoded = jsonDecode(rawJson);
            if (decoded is Map<String, dynamic>) {
              params = decoded;
            }
          } catch (_) {}
        }
      }
    }

    if (toolName == null) return null;
    return (name: toolName, params: params);
  }

  String _extractTranscript(String geminiOutput) {
    for (final line in geminiOutput.split('\n')) {
      final trimmed = line.trim();
      if (trimmed.toLowerCase().startsWith('transcript:')) {
        final extracted = trimmed.substring('transcript:'.length).trim();
        if (extracted.isNotEmpty) return extracted;
      }
    }
    return geminiOutput.trim();
  }

  Uint8List _wrapPcm16AsWav(Uint8List pcmBytes) {
    final dataSize = pcmBytes.length;
    final byteRate = _sampleRate * _numChannels * (_bitsPerSample ~/ 8);
    final blockAlign = _numChannels * (_bitsPerSample ~/ 8);

    final header = ByteData(44);
    header.setUint8(0, 0x52); // R
    header.setUint8(1, 0x49); // I
    header.setUint8(2, 0x46); // F
    header.setUint8(3, 0x46); // F
    header.setUint32(4, 36 + dataSize, Endian.little);
    header.setUint8(8, 0x57); // W
    header.setUint8(9, 0x41); // A
    header.setUint8(10, 0x56); // V
    header.setUint8(11, 0x45); // E

    header.setUint8(12, 0x66); // f
    header.setUint8(13, 0x6D); // m
    header.setUint8(14, 0x74); // t
    header.setUint8(15, 0x20); // space
    header.setUint32(16, 16, Endian.little); // PCM
    header.setUint16(20, 1, Endian.little); // AudioFormat = 1 (PCM)
    header.setUint16(22, _numChannels, Endian.little);
    header.setUint32(24, _sampleRate, Endian.little);
    header.setUint32(28, byteRate, Endian.little);
    header.setUint16(32, blockAlign, Endian.little);
    header.setUint16(34, _bitsPerSample, Endian.little);

    header.setUint8(36, 0x64); // d
    header.setUint8(37, 0x61); // a
    header.setUint8(38, 0x74); // t
    header.setUint8(39, 0x61); // a
    header.setUint32(40, dataSize, Endian.little);

    final wav = Uint8List(44 + dataSize);
    wav.setRange(0, 44, header.buffer.asUint8List());
    wav.setRange(44, 44 + dataSize, pcmBytes);
    return wav;
  }

  Future<void> stopListening() async {
    await _streamSub?.cancel();
    _streamSub = null;

    if (_isListening) {
      try {
        await _recorder.stop();
      } catch (_) {}
      _isListening = false;
    }

    _recordedPcmBytes.clear();
    _smoothAmp = 0.0;
    onVoiceStateChanged(AssistantState.idle, 0.0);
  }

  Future<void> dispose() async {
    await stopListening();
    await _recorder.dispose();
  }
}
