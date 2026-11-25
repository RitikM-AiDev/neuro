import 'dart:async';
import 'dart:math';
import 'dart:typed_data';

import 'package:flutter/foundation.dart';
import 'package:record/record.dart';

import '../models/assistant_state.dart';
import 'gemini_service.dart';

/// Always-on microphone capture that records ONLY speech samples (excluding
/// silence frames) and sends completed utterances as WAV audio to Gemini.
class VoiceDetectorService {
  final void Function(AssistantState state, double amplitude)
      onVoiceStateChanged;
  final void Function(String responseText)? onResponseReceived;
  final GeminiService _geminiService;

  final AudioRecorder _recorder = AudioRecorder();
  StreamSubscription<Uint8List>? _streamSub;

  bool _isListening = false;
  bool _isVoiceActive = false;
  bool _isSending = false;

  double _smoothAmp = 0.0;
  double _noiseFloorRms = 200.0;
  int _silenceSamplesCount = 0;
  int _hangoverFramesRemaining = 0;

  static const int _sampleRate = 16000;
  static const int _numChannels = 1;
  static const int _bitsPerSample = 16;

  // 20ms sub-frame slicing for fine-grained silence removal (320 samples = 640 bytes).
  static const int _subFrameSamples = (_sampleRate * 20) ~/ 1000;
  static const int _subFrameBytes = _subFrameSamples * (_bitsPerSample ~/ 8);

  // PCM16 RMS threshold: > 550 is speech, <= 550 is silence.
  static const double _speechRmsThreshold = 550.0;

  // Keep up to 2 hangover sub-frames (40ms) for natural consonant dips.
  static const int _maxHangoverFrames = 2;

  // ~700ms of continuous silence marks end of utterance.
  static const int _silenceEndSamples = (_sampleRate * 700) ~/ 1000;

  // Minimum 400ms of speech audio to avoid sending accidental clicks/noise.
  static const int _minSpeechBytes =
      ((_sampleRate * 400) ~/ 1000) * (_bitsPerSample ~/ 8);

  // Holds ONLY speech PCM16 bytes (silence frames are never appended).
  final BytesBuilder _speechPcmBytes = BytesBuilder(copy: false);
  final BytesBuilder _pendingBytes = BytesBuilder(copy: false);
  Uint8List? _preRollFrame;

  VoiceDetectorService({
    required this.onVoiceStateChanged,
    this.onResponseReceived,
    GeminiService? geminiService,
  }) : _geminiService = geminiService ?? GeminiService();

  bool get isListening => _isListening;

  Future<bool> hasPermission({bool request = false}) async {
    return _recorder.hasPermission(request: request);
  }

  Future<void> startListening() async {
    if (_isListening) return;

    final permission = await _recorder.hasPermission();
    if (!permission) {
      throw StateError('Microphone permission not granted.');
    }

    _speechPcmBytes.clear();
    _pendingBytes.clear();
    _preRollFrame = null;
    _silenceSamplesCount = 0;
    _hangoverFramesRemaining = 0;
    _noiseFloorRms = 200.0;
    _isVoiceActive = false;

    final stream = await _recorder.startStream(
      const RecordConfig(
        encoder: AudioEncoder.pcm16bits,
        sampleRate: _sampleRate,
        numChannels: _numChannels,
      ),
    );

    _isListening = true;
    debugPrint('[Mic] Always-on microphone started (16kHz PCM16).');

    _streamSub = stream.listen(
      _processAudio,
      onError: (error) {
        debugPrint('[Mic] Stream error: $error');
        stopListening();
      },
      onDone: () {
        _isListening = false;
      },
    );
  }

  double get _effectiveSpeechThreshold =>
      max(_speechRmsThreshold, _noiseFloorRms * 2.0);

  void _processAudio(Uint8List bytes) {
    if (!_isListening || bytes.isEmpty) return;

    _pendingBytes.add(bytes);
    final buffered = _pendingBytes.takeBytes();

    var offset = 0;
    while (offset + _subFrameBytes <= buffered.length) {
      final subFrame = Uint8List.sublistView(
        buffered,
        offset,
        offset + _subFrameBytes,
      );
      _processSubFrame(subFrame);
      offset += _subFrameBytes;
    }

    if (offset < buffered.length) {
      _pendingBytes.add(Uint8List.sublistView(buffered, offset));
    }
  }

  void _processSubFrame(Uint8List frameBytes) {
    final rms = _calculatePcm16Rms(frameBytes, _subFrameSamples);
    final rawAmplitude = ((rms / 32768.0) * 4.0).clamp(0.0, 1.0);
    _smoothAmp = _smoothAmp * 0.3 + rawAmplitude * 0.7;

    if (rms > _effectiveSpeechThreshold) {
      if (!_isVoiceActive) {
        _isVoiceActive = true;
        debugPrint(
          '[Mic] Speech started (RMS: ${rms.toStringAsFixed(1)}, floor: ${_noiseFloorRms.toStringAsFixed(1)})',
        );
        // Include 20ms pre-roll only if it contains onset energy above noise floor.
        final preRoll = _preRollFrame;
        if (preRoll != null &&
            _calculatePcm16Rms(preRoll, _subFrameSamples) > _noiseFloorRms * 1.4) {
          _speechPcmBytes.add(preRoll);
        }
        _preRollFrame = null;
      }

      _silenceSamplesCount = 0;
      _hangoverFramesRemaining = _maxHangoverFrames;

      // Append ONLY speech sub-frames; silence sub-frames are excluded.
      _speechPcmBytes.add(frameBytes);

      onVoiceStateChanged(AssistantState.listening, _smoothAmp);
    } else if (_isVoiceActive) {
      _silenceSamplesCount += _subFrameSamples;

      // Short hangover preserves natural consonant dips without keeping long pauses.
      if (_hangoverFramesRemaining > 0) {
        _hangoverFramesRemaining--;
        _speechPcmBytes.add(frameBytes);
      }

      if (_silenceSamplesCount >= _silenceEndSamples) {
        _isVoiceActive = false;
        _silenceSamplesCount = 0;
        _hangoverFramesRemaining = 0;
        _smoothAmp = 0.0;
        debugPrint('[Mic] Silence detected — speech segment complete.');
        _sendSpeechToGemini();
      }
    } else {
      // Track ambient noise floor during idle periods and keep a 20ms pre-roll.
      _noiseFloorRms = (_noiseFloorRms * 0.95 + rms * 0.05).clamp(50.0, 450.0);
      _preRollFrame = Uint8List.fromList(frameBytes);
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

  Future<void> _sendSpeechToGemini() async {
    final rawPcmBytes = _speechPcmBytes.takeBytes();
    final pcmBytes = _stripSilenceSubFrames(rawPcmBytes);

    if (rawPcmBytes.length > pcmBytes.length) {
      final savedBytes = rawPcmBytes.length - pcmBytes.length;
      final savedPct = (savedBytes * 100 / rawPcmBytes.length).toStringAsFixed(1);
      debugPrint(
        '[Opt] Stripped $savedBytes silence bytes ($savedPct% saved).',
      );
    }

    if (pcmBytes.length < _minSpeechBytes) {
      debugPrint(
        '[Mic] Segment too short (${pcmBytes.length} bytes < $_minSpeechBytes); ignoring.',
      );
      onVoiceStateChanged(AssistantState.idle, 0.0);
      return;
    }

    final fullFrameSamples = pcmBytes.length ~/ 2;
    final fullFrameRms = fullFrameSamples > 0
        ? _calculatePcm16Rms(pcmBytes, fullFrameSamples)
        : 0.0;
    if (fullFrameRms < _speechRmsThreshold) {
      debugPrint(
        '[Mic] Full frame RMS too low (${fullFrameRms.toStringAsFixed(1)} < $_speechRmsThreshold); ignoring.',
      );
      onVoiceStateChanged(AssistantState.idle, 0.0);
      return;
    }

    if (_isSending) {
      debugPrint('[Gemini] Previous request still in flight; dropping segment.');
      return;
    }

    _isSending = true;
    onVoiceStateChanged(AssistantState.thinking, 0.0);

    try {
      final wavBytes = _wrapPcm16AsWav(pcmBytes);
      debugPrint(
        '[Gemini] Sending ${pcmBytes.length} speech-only PCM bytes (${wavBytes.length} WAV bytes)...',
      );

      final text = await _geminiService.generateFromSpeechAudio(wavBytes);
      if (text != null && text.isNotEmpty) {
        onResponseReceived?.call(text);
        onVoiceStateChanged(AssistantState.speaking, 0.85);

        Future.delayed(const Duration(milliseconds: 1500), () {
          if (!_isVoiceActive && _isListening) {
            onVoiceStateChanged(AssistantState.idle, 0.0);
          }
        });
        return;
      }
      debugPrint('[Gemini] No text returned for speech segment.');
    } finally {
      _isSending = false;
    }

    if (!_isVoiceActive) {
      onVoiceStateChanged(AssistantState.idle, 0.0);
    }
  }

  /// Strips leading, trailing, and extended internal silence sub-frames
  /// (below [_speechRmsThreshold]) while allowing at most one 20ms bridge
  /// frame between active speech frames for natural cadence.
  Uint8List _stripSilenceSubFrames(Uint8List pcmBytes) {
    if (pcmBytes.length < _subFrameBytes) return pcmBytes;

    final totalFrames = pcmBytes.length ~/ _subFrameBytes;
    final activeFrames = <Uint8List>[];
    var seenSpeech = false;
    Uint8List? pendingBridgeFrame;

    for (var i = 0; i < totalFrames; i++) {
      final start = i * _subFrameBytes;
      final frame = Uint8List.sublistView(pcmBytes, start, start + _subFrameBytes);
      final rms = _calculatePcm16Rms(frame, _subFrameSamples);

      if (rms >= _speechRmsThreshold) {
        if (pendingBridgeFrame != null) {
          activeFrames.add(pendingBridgeFrame);
          pendingBridgeFrame = null;
        }
        activeFrames.add(frame);
        seenSpeech = true;
      } else if (seenSpeech && pendingBridgeFrame == null) {
        // Hold at most one 20ms sub-frame; only keep it if speech resumes next.
        pendingBridgeFrame = frame;
      }
    }

    final compacted = BytesBuilder(copy: false);
    for (final frame in activeFrames) {
      compacted.add(frame);
    }
    return compacted.takeBytes();
  }

  Uint8List _wrapPcm16AsWav(Uint8List pcmBytes) {
    final dataSize = pcmBytes.length;
    final byteRate = _sampleRate * _numChannels * (_bitsPerSample ~/ 8);
    final blockAlign = _numChannels * (_bitsPerSample ~/ 8);

    final header = ByteData(44);
    // RIFF chunk descriptor
    header.setUint8(0, 0x52); // R
    header.setUint8(1, 0x49); // I
    header.setUint8(2, 0x46); // F
    header.setUint8(3, 0x46); // F
    header.setUint32(4, 36 + dataSize, Endian.little);
    header.setUint8(8, 0x57); // W
    header.setUint8(9, 0x41); // A
    header.setUint8(10, 0x56); // V
    header.setUint8(11, 0x45); // E

    // fmt sub-chunk
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

    // data sub-chunk
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

    _speechPcmBytes.clear();
    _pendingBytes.clear();
    _preRollFrame = null;
    _silenceSamplesCount = 0;
    _hangoverFramesRemaining = 0;
    _noiseFloorRms = 200.0;
    _isVoiceActive = false;
    _smoothAmp = 0.0;

    onVoiceStateChanged(AssistantState.idle, 0.0);
  }

  Future<void> dispose() async {
    await stopListening();
    await _recorder.dispose();
  }
}
