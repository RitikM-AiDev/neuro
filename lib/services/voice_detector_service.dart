import 'dart:async';
import 'dart:math';
import 'dart:typed_data';

import 'package:flutter/foundation.dart';
import 'package:record/record.dart';
import 'package:sherpa_onnx/sherpa_onnx.dart' as sherpa;

import '../models/assistant_state.dart';

class VoiceDetectorService {
  final void Function(AssistantState state, double amplitude)
      onVoiceStateChanged;
  final void Function(String recognizedText)? onSpeechRecognized;

  final AudioRecorder _recorder = AudioRecorder();

  StreamSubscription<Uint8List>? _streamSub;

  sherpa.VoiceActivityDetector? _vad;
  sherpa.OfflineRecognizer? _recognizer;

  bool _isListening = false;
  bool _isVoiceActive = false;
  bool _isInitialized = false;

  double _smoothAmp = 0.0;

  static const int _sampleRate = 16000;
  static const int _frameSize = 512;

  final List<double> _audioBuffer = [];
  final List<double> _speechSamples = [];

  VoiceDetectorService({
    required this.onVoiceStateChanged,
    this.onSpeechRecognized,
  });

  bool get isListening => _isListening;
  bool get isInitialized => _isInitialized;
  bool get hasRecognizer => _recognizer != null;

  Future<bool> hasPermission({bool request = false}) async {
    return _recorder.hasPermission(request: request);
  }

  Future<void> initialize({
    required String vadModelPath,
    String? sttModelPath,
    String? sttTokensPath,
  }) async {
    if (_isInitialized) return;

    await sherpa.initBindingsAsync();

    // 1. Initialize Silero VAD
    final config = sherpa.VadModelConfig(
      sileroVad: sherpa.SileroVadModelConfig(
        model: vadModelPath,
        threshold: 0.5,
        minSilenceDuration: 0.5,
        minSpeechDuration: 0.25,
        maxSpeechDuration: 20.0,
        windowSize: _frameSize,
      ),
      sampleRate: _sampleRate,
      numThreads: 2,
      provider: 'cpu',
      debug: false,
    );

    _vad = sherpa.VoiceActivityDetector(
      config: config,
      bufferSizeInSeconds: 30,
    );

    // 2. Initialize Offline Speech Recognizer (SenseVoice) if models provided
    if (sttModelPath != null &&
        sttTokensPath != null &&
        sttModelPath.isNotEmpty &&
        sttTokensPath.isNotEmpty) {
      try {
        final sttConfig = sherpa.OfflineRecognizerConfig(
          model: sherpa.OfflineModelConfig(
            senseVoice: sherpa.OfflineSenseVoiceModelConfig(
              model: sttModelPath,
              language: 'auto',
              useInverseTextNormalization: true,
            ),
            tokens: sttTokensPath,
            numThreads: 2,
            debug: false,
          ),
        );
        _recognizer = sherpa.OfflineRecognizer(sttConfig);
        debugPrint('Sherpa-ONNX STT SenseVoice recognizer initialized successfully.');
      } catch (e) {
        debugPrint('Failed to initialize STT recognizer: $e');
      }
    }

    _isInitialized = true;
  }

  Future<void> startListening() async {
    if (_isListening) return;

    final permission = await _recorder.hasPermission();

    if (!permission) {
      throw StateError('Microphone permission not granted.');
    }

    _audioBuffer.clear();
    _speechSamples.clear();
    _vad?.reset();

    final stream = await _recorder.startStream(
      const RecordConfig(
        encoder: AudioEncoder.pcm16bits,
        sampleRate: _sampleRate,
        numChannels: 1,
      ),
    );

    _isListening = true;

    _streamSub = stream.listen(
      _processAudio,
      onError: (error) {
        stopListening();
      },
      onDone: () {
        _isListening = false;
      },
    );
  }

  void _processAudio(Uint8List bytes) {
    if (!_isListening || bytes.length < 2) return;

    final samples = _convertPcm16ToFloat(bytes);

    if (samples.isEmpty) return;

    final rms = _calculateRms(samples);

    final rawAmplitude = (rms * 4.0).clamp(0.0, 1.0);

    _smoothAmp = _smoothAmp * 0.3 + rawAmplitude * 0.7;

    final vad = _vad;
    if (vad != null) {
      _audioBuffer.addAll(samples);

      while (_audioBuffer.length >= _frameSize) {
        final frame = Float32List.fromList(
          _audioBuffer.sublist(0, _frameSize),
        );

        _audioBuffer.removeRange(0, _frameSize);

        _processVadFrame(frame);
      }
    } else {
      // Immediate fallback to RMS reactivity until Sherpa VAD is loaded
      if (rms > 0.02) {
        _isVoiceActive = true;
        _speechSamples.addAll(samples);
        onVoiceStateChanged(
          AssistantState.listening,
          _smoothAmp,
        );
      } else if (_isVoiceActive) {
        _isVoiceActive = false;
        _smoothAmp = 0.0;
        _processSpeechSegment();
      }
    }
  }

  Float32List _convertPcm16ToFloat(Uint8List bytes) {
    final sampleCount = bytes.length ~/ 2;
    final data = ByteData.sublistView(bytes);

    final result = Float32List(sampleCount);

    for (int i = 0; i < sampleCount; i++) {
      result[i] =
          data.getInt16(i * 2, Endian.little) / 32768.0;
    }

    return result;
  }

  double _calculateRms(Float32List samples) {
    double sumSquares = 0.0;

    for (final sample in samples) {
      sumSquares += sample * sample;
    }

    return sqrt(sumSquares / samples.length);
  }

  void _processVadFrame(Float32List frame) {
    final vad = _vad;
    if (vad == null) return;

    vad.acceptWaveform(frame);

    final detected = vad.isDetected();

    if (detected) {
      _isVoiceActive = true;
      _speechSamples.addAll(frame);

      onVoiceStateChanged(
        AssistantState.listening,
        _smoothAmp,
      );
    } else if (_isVoiceActive) {
      _isVoiceActive = false;
      _smoothAmp = 0.0;
      _processSpeechSegment();
    }
  }

  Future<void> _processSpeechSegment() async {
    final recognizer = _recognizer;

    // Minimum 400ms audio required for speech transcription
    if (recognizer == null || _speechSamples.length < (_sampleRate * 0.4)) {
      _speechSamples.clear();
      onVoiceStateChanged(AssistantState.idle, 0.0);
      return;
    }

    final samples = Float32List.fromList(_speechSamples);
    _speechSamples.clear();

    onVoiceStateChanged(AssistantState.thinking, 0.0);

    try {
      final stream = recognizer.createStream();
      stream.acceptWaveform(samples: samples, sampleRate: _sampleRate);
      recognizer.decode(stream);
      final result = recognizer.getResult(stream);
      final text = result.text.trim();
      stream.free();

      if (text.isNotEmpty) {
        debugPrint('[Sherpa STT Result]: $text');
        onSpeechRecognized?.call(text);
        onVoiceStateChanged(AssistantState.speaking, 0.85);

        Future.delayed(const Duration(milliseconds: 1500), () {
          if (!_isVoiceActive && _isListening) {
            onVoiceStateChanged(AssistantState.idle, 0.0);
          }
        });
        return;
      }
    } catch (e) {
      debugPrint('Error during Sherpa STT decode: $e');
    }

    onVoiceStateChanged(AssistantState.idle, 0.0);
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

    _audioBuffer.clear();
    _speechSamples.clear();

    _isVoiceActive = false;
    _smoothAmp = 0.0;

    _vad?.reset();

    onVoiceStateChanged(
      AssistantState.idle,
      0.0,
    );
  }

  Future<void> dispose() async {
    await stopListening();

    _vad?.free();
    _vad = null;

    _recognizer?.free();
    _recognizer = null;

    _isInitialized = false;

    await _recorder.dispose();
  }
}
