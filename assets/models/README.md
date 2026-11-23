# Sherpa-ONNX Offline Model Assets

Place your offline speech recognition ONNX model files in this directory:

- `model.int8.onnx` (SenseVoice or Whisper int8 quantized ONNX model)
- `tokens.txt` (Vocabulary token file)

### Recommended Model: SenseVoice (Fast & Offline)
Download pre-trained files from the official Sherpa-ONNX release:
1. `model.int8.onnx`: https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2
2. Extract and rename:
   - `model.int8.onnx` -> `assets/models/model.int8.onnx`
   - `tokens.txt` -> `assets/models/tokens.txt`
