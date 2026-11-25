import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';

class GeminiService {
  GeminiService({String? apiKey, Dio? dio})
    : _explicitApiKey = apiKey,
      _dio =
          dio ??
          Dio(
            BaseOptions(
              connectTimeout: const Duration(seconds: 10),
              sendTimeout: const Duration(seconds: 10),
              receiveTimeout: const Duration(seconds: 20),
            ),
          );

  static const String _model = 'gemini-3.5-flash-lite';

  static Map<String, dynamic> _toggleTool(String name, String description) => {
    'name': name,
    'description': description,
    'parameters': {
      'type': 'OBJECT',
      'properties': {
        'status': {
          'type': 'STRING',
          'enum': ['on', 'off'],
          'description': 'Target state: "on" or "off".',
        },
      },
      'required': ['status'],
    },
  };

  /// Gemini function declarations for web search and device toggle tools.
  static final List<Map<String, dynamic>> toolDeclarations = [
    {
      'name': 'search_web',
      'description': 'Search the internet for information.',
      'parameters': {
        'type': 'OBJECT',
        'properties': {
          'query': {
            'type': 'STRING',
            'description': 'Search query',
          },
        },
        'required': ['query'],
      },
    },
    _toggleTool('ToggleInternet', 'Turn mobile data / internet on or off.'),
    _toggleTool('ToggleBluetooth', 'Turn Bluetooth on or off.'),
    _toggleTool('ToggleWifi', 'Turn Wi-Fi on or off.'),
    _toggleTool('ToggleDarkMode', 'Turn system dark mode on or off.'),
    _toggleTool('ToggleAirplaneMode', 'Turn airplane mode on or off.'),
    _toggleTool('ToggleFlash', 'Turn the device flashlight on or off.'),
    _toggleTool('ToggleVibration', 'Turn vibration mode on or off.'),
    _toggleTool('ToggleSilent', 'Turn silent / mute mode on or off.'),
    _toggleTool('ToggleLocation', 'Turn device location / GPS on or off.'),
    _toggleTool('ToggleAutoBrightness', 'Turn auto-brightness on or off.'),
    _toggleTool('ToggleScreenshot', 'Trigger or toggle screenshot capture.'),
    _toggleTool('ToggleLowPowerMode', 'Turn battery saver / low power mode on or off.'),
    _toggleTool('ToggleHotspot', 'Turn mobile hotspot on or off.'),
  ];

  /// Ready-to-send `tools` payload for Gemini `generateContent` requests.
  static final List<Map<String, dynamic>> geminiTools = [
    {'function_declarations': toolDeclarations},
  ];

  final String? _explicitApiKey;
  final Dio _dio;

  String get _apiKey {
    if (_explicitApiKey != null && _explicitApiKey.isNotEmpty) {
      return _explicitApiKey;
    }
    if (dotenv.isInitialized) {
      final envKey =
          dotenv.env['gemini_api_key'] ?? dotenv.env['GEMINI_API_KEY'];
      if (envKey != null && envKey.trim().isNotEmpty) {
        return envKey.trim();
      }
    }
    return const String.fromEnvironment('GEMINI_API_KEY');
  }

  /// Sends speech-only WAV audio bytes to `gemini-3.8-flash` with Google Search
  /// tool enabled and returns the generated text response.
  Future<String?> generateFromSpeechAudio(Uint8List wavBytes) async {
    final apiKey = _apiKey;
    if (apiKey.isEmpty || apiKey == 'YOUR_GEMINI_API_KEY') {
      debugPrint(
        '[Gemini] API key is missing. Add gemini_api_key=... in .env.',
      );
      return null;
    }

    final url =
        'https://generativelanguage.googleapis.com/v1beta/models/$_model:generateContent?key=$apiKey';

    final body = {
      'contents': [
        {
          'parts': [
            {
              'inline_data': {
                'mime_type': 'audio/wav',
                'data': base64Encode(wavBytes),
              },
            },
            {
              'text':
                  'Return valid JSON only. '
                  'If no speech (silence/noise/breath), return {"silence":true}. '
                  'Otherwise return {"silence":false,"transcript":"<verbatim speech>",'
                  '"device_action":<null_or_object>,"tool":"<none_or_search_web_or_openInstagramReels>",'
                  '"params":<object>,"response":"<short reply>"}.\n'
                  'For device controls, set "device_action":{"capability":"<name>","action":"<action>","parameters":{...}}.\n'
                  'Capabilities: flashlight(set/toggle,enabled), bluetooth(set/toggle,enabled), '
                  'wifi(set/toggle,enabled), brightness(set,level:0-255), '
                  'auto_brightness(set/toggle,enabled), volume(set/adjust,stream:music|ring|alarm|notification|voice_call,level,direction:up|down|mute|unmute), '
                  'vibration(set/toggle,enabled), silent_mode(set/toggle,enabled), '
                  'do_not_disturb(set/toggle,enabled), dark_mode(set/toggle,enabled), '
                  'location(set/open_settings), airplane_mode(set/open_settings), '
                  'mobile_data(set/toggle,enabled), hotspot(set/open_settings), '
                  'screenshot(capture), battery_saver(set/open_settings), '
                  'screen_timeout(set,seconds|milliseconds).',
            },
          ],
        },
      ],
      'generationConfig': {
        'temperature': 0,
        'responseMimeType': 'application/json',
      },
    };

    try {
      debugPrint('[Gemini] Calling $_model endpoint...');
      final response = await _dio.post<Map<String, dynamic>>(
        url,
        data: body,
        options: Options(headers: const {'Content-Type': 'application/json'}),
      );
      debugPrint('[Gemini] HTTP ${response.statusCode}');

      final data = response.data;
      if (data == null) {
        debugPrint('[Gemini] Empty response body.');
        return null;
      }

      final candidates = data['candidates'] as List<dynamic>?;
      if (candidates == null || candidates.isEmpty) {
        debugPrint('[Gemini] No candidates in response: $data');
        return null;
      }

      final content = candidates.first['content'] as Map<String, dynamic>?;
      final parts = content?['parts'] as List<dynamic>?;
      if (parts == null || parts.isEmpty) {
        debugPrint('[Gemini] No parts in candidate: ${candidates.first}');
        return null;
      }

      final buffer = StringBuffer();
      for (final part in parts) {
        if (part is! Map<String, dynamic>) continue;
        if (part['text'] is String) {
          if (buffer.isNotEmpty) buffer.writeln();
          buffer.write(part['text']);
        }
        final functionCall = part['functionCall'];
        if (functionCall is Map<String, dynamic>) {
          final toolName = functionCall['name']?.toString() ?? 'none';
          final args = functionCall['args'] ?? const <String, dynamic>{};
          if (buffer.isNotEmpty) buffer.writeln();
          buffer.writeln('Tool: $toolName');
          buffer.write('Params: ${jsonEncode(args)}');
        }
      }

      final text = buffer.toString().trim();
      if (text.isNotEmpty) {
        final formatted = _formatStructuredOutput(text);
        debugPrint('[Gemini $_model]:\n$formatted');
        return formatted;
      }
      debugPrint('[Gemini] Empty text in response parts.');
      return null;
    } on DioException catch (e) {
      debugPrint(
        '[Gemini] API error (${e.response?.statusCode}): ${e.response?.data ?? e.message}',
      );
      return null;
    } catch (e) {
      debugPrint('[Gemini] Unexpected error: $e');
      return null;
    }
  }

  String _formatStructuredOutput(String rawText) {
    try {
      final decoded = jsonDecode(rawText);
      if (decoded is Map<String, dynamic>) {
        if (decoded['silence'] == true) {
          return 'SILENCE';
        }
        final transcript = decoded['transcript']?.toString().trim() ?? '';
        final deviceAction = decoded['device_action'];
        final tool = decoded['tool']?.toString().trim() ?? 'none';
        final params = decoded['params'] is Map
            ? decoded['params']
            : const <String, dynamic>{};
        final response = decoded['response']?.toString().trim() ?? '';
        final buffer = StringBuffer()
          ..writeln('Transcript: $transcript');
        if (deviceAction is Map<String, dynamic>) {
          buffer.writeln('DeviceAction: ${jsonEncode(deviceAction)}');
        }
        buffer
          ..writeln('Tool: $tool')
          ..writeln('Params: ${jsonEncode(params)}')
          ..write('Response: $response');
        return buffer.toString();
      }
    } catch (_) {
      // Fall back to raw text if not JSON.
    }
    return rawText;
  }
}
