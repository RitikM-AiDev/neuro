
import 'dart:async';
import 'dart:math';
import 'dart:typed_data';

import 'package:record/record.dart';
import 'package:sherpa_onnx/sherpa_onnx.dart' as sherpa;

import '../models/assistant_state.dart';

class VoiceDetectorService {
  final void Function(AssistantState state, double amplitude)
      onVoiceStateChanged;

  final AudioRecorder _recorder = AudioRecorder();

  StreamSubscription<Uint8List>? _streamSub;

  sherpa.VoiceActivityDetector? _vad;

  bool _isListening = false;
  bool _isVoiceActive = false;
  bool _isInitialized = false;

  double _smoothAmp = 0.0;

  static const int _sampleRate = 16000;
  static const int _frameSize = 512;

  final List<double> _audioBuffer = [];

  VoiceDetectorService({
    required this.onVoiceStateChanged,
  });

  bool get isListening => _isListening;
  bool get isInitialized => _isInitialized;

  Future<bool> hasPermission({bool request = false}) async {
    return _recorder.hasPermission(request: request);
  }

  Future<void> initialize({
    required String vadModelPath,
  }) async {
    if (_isInitialized) return;

    await sherpa.initBindingsAsync();

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

    _isInitialized = true;
  }

  Future<void> startListening() async {
    if (_isListening) return;

    final permission = await _recorder.hasPermission();

    if (!permission) {
      throw StateError('Microphone permission not granted.');
    }

    _audioBuffer.clear();
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
      // Immediate fallback to RMS voice reactivity until Sherpa VAD model is loaded
      if (rms > 0.02) {
        _isVoiceActive = true;
        onVoiceStateChanged(
          AssistantState.listening,
          _smoothAmp,
        );
      } else if (_isVoiceActive) {
        _isVoiceActive = false;
        _smoothAmp = 0.0;
        onVoiceStateChanged(
          AssistantState.idle,
          0.0,
        );
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

      onVoiceStateChanged(
        AssistantState.listening,
        _smoothAmp,
      );
    } else if (_isVoiceActive) {
      _isVoiceActive = false;
      _smoothAmp = 0.0;

      onVoiceStateChanged(
        AssistantState.idle,
        0.0,
      );
    }
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

    _isInitialized = false;

    await _recorder.dispose();
  }
}
