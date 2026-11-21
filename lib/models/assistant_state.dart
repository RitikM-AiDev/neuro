/// Visual UI state for NeuralCircle and overlay animation
enum AssistantState {
  idle,
  listening,
  thinking,
  speaking,
}

/// Controlled 9-State State Machine for AudioRecord + STT lifecycle
enum VoiceState {
  idle,
  initializing,
  audioListening,
  speechDetected,
  sttProcessing,
  resultAvailable,
  resumingListening,
  error,
  stopped,
}

/// Structured Voice Event streamed from Kotlin AudioListeningService
class VoiceEvent {
  final VoiceState state;
  final String rawState;
  final String text;
  final bool partial;
  final String? language;
  final double rms;
  final String? error;

  const VoiceEvent({
    required this.state,
    required this.rawState,
    required this.text,
    required this.partial,
    this.language,
    required this.rms,
    this.error,
  });

  factory VoiceEvent.fromMap(Map<dynamic, dynamic> map) {
    final raw = (map['state'] as String? ?? 'IDLE').toUpperCase();
    final state = _parseState(raw);

    return VoiceEvent(
      state: state,
      rawState: raw,
      text: (map['text'] as String? ?? '').trim(),
      partial: map['partial'] as bool? ?? false,
      language: map['language'] as String?,
      rms: (map['rms'] as num?)?.toDouble() ?? 0.0,
      error: map['error'] as String?,
    );
  }

  static VoiceState _parseState(String raw) {
    switch (raw) {
      case 'INITIALIZING':
        return VoiceState.initializing;
      case 'AUDIO_LISTENING':
        return VoiceState.audioListening;
      case 'SPEECH_DETECTED':
        return VoiceState.speechDetected;
      case 'STT_PROCESSING':
        return VoiceState.sttProcessing;
      case 'RESULT_AVAILABLE':
        return VoiceState.resultAvailable;
      case 'RESUMING_LISTENING':
        return VoiceState.resumingListening;
      case 'ERROR':
        return VoiceState.error;
      case 'STOPPED':
        return VoiceState.stopped;
      case 'IDLE':
      default:
        return VoiceState.idle;
    }
  }

  /// Maps the low-level lifecycle state to the high-level visual animation state
  AssistantState get visualState {
    switch (state) {
      case VoiceState.audioListening:
        return rms > 0.08 ? AssistantState.listening : AssistantState.idle;
      case VoiceState.speechDetected:
      case VoiceState.sttProcessing:
        return AssistantState.listening;
      case VoiceState.resultAvailable:
        return AssistantState.speaking;
      case VoiceState.resumingListening:
      case VoiceState.initializing:
      case VoiceState.idle:
      case VoiceState.stopped:
      case VoiceState.error:
        return AssistantState.idle;
    }
  }

  @override
  String toString() {
    return 'VoiceEvent(state: $rawState, text: "$text", partial: $partial, lang: $language, rms: $rms, error: $error)';
  }
}
