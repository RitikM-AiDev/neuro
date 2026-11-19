import 'dart:async';
import 'dart:math';
import 'package:record/record.dart';
import '../models/assistant_state.dart';

class VoiceDetectorService {
  final void Function(AssistantState state, double amplitude) onVoiceStateChanged;

  final AudioRecorder _recorder = AudioRecorder();
  StreamSubscription? _streamSub;
  StreamSubscription? _ampSub;
  Timer? _silenceTimer;

  bool _isVoiceActive = false;
  bool _isListening = false;
  double _smoothAmp = 0.0;

  VoiceDetectorService({required this.onVoiceStateChanged});

  bool get isListening => _isListening;

  Future<bool> hasPermission({bool request = false}) async {
    return await _recorder.hasPermission(request: request);
  }

  Future<void> startListening() async {
    if (_isListening) return;

    final hasPerm = await _recorder.hasPermission();
    if (!hasPerm) return;

    try {
      final stream = await _recorder.startStream(
        const RecordConfig(
          encoder: AudioEncoder.pcm16bits,
          sampleRate: 16000,
          numChannels: 1,
        ),
      );

      _isListening = true;

      // 1. Process real-time PCM bytes for instantaneous RMS amplitude calculation
      _streamSub = stream.listen((bytes) {
        if (bytes.length < 2) return;
        int sumSquares = 0;
        final sampleCount = bytes.length ~/ 2;
        for (int i = 0; i < bytes.length - 1; i += 2) {
          int sample = bytes[i] | (bytes[i + 1] << 8);
          if (sample >= 32768) sample -= 65536;
          sumSquares += sample * sample;
        }
        final double rms = sqrt(sumSquares / sampleCount);

        // Voice detection threshold: RMS > 550 (approx -36 dBFS)
        if (rms > 550) {
          final double rawNorm = ((rms - 500) / 4500).clamp(0.0, 1.0);
          _onVoiceSignal(rawNorm);
        } else {
          _onSilenceSignal();
        }
      }, onError: (_) {
        // Stream error handling
      });

      // 2. Also listen to hardware amplitude events for extra fidelity
      _ampSub = _recorder.onAmplitudeChanged(const Duration(milliseconds: 60)).listen((amp) {
        if (amp.current > -38.0 || amp.max > -32.0) {
          final double rawNorm = ((amp.current - (-40.0)) / 28.0).clamp(0.0, 1.0);
          _onVoiceSignal(rawNorm);
        } else {
          _onSilenceSignal();
        }
      });
    } catch (_) {
      _isListening = false;
    }
  }

  void _onVoiceSignal(double rawNorm) {
    _silenceTimer?.cancel();
    _silenceTimer = null;
    _isVoiceActive = true;

    // Smooth exponential moving average for fluid animation
    _smoothAmp = _smoothAmp * 0.3 + rawNorm * 0.7;
    onVoiceStateChanged(AssistantState.listening, _smoothAmp);
  }

  void _onSilenceSignal() {
    if (!_isVoiceActive) return;

    // Debounce silence by 500ms to allow natural speech pauses between words
    _silenceTimer ??= Timer(const Duration(milliseconds: 500), () {
      _isVoiceActive = false;
      _smoothAmp = 0.0;
      onVoiceStateChanged(AssistantState.idle, 0.0);
      _silenceTimer = null;
    });
  }

  Future<void> stopListening() async {
    _silenceTimer?.cancel();
    _silenceTimer = null;
    await _streamSub?.cancel();
    _streamSub = null;
    await _ampSub?.cancel();
    _ampSub = null;

    if (_isListening) {
      try {
        await _recorder.stop();
      } catch (_) {}
      _isListening = false;
    }
    _isVoiceActive = false;
    _smoothAmp = 0.0;
  }

  Future<void> dispose() async {
    await stopListening();
    await _recorder.dispose();
  }
}
