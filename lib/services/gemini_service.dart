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
              connectTimeout: const Duration(seconds: 15),
              receiveTimeout: const Duration(seconds: 45),
            ),
          );

  static const String _model = 'gemini-3.5-flash-lite';

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
                  'Transcribe the user audio verbatim, then respond clearly.\n'
                  'Format your output as:\n'
                  'Transcript: <exact transcription>\n'
                  'Response: <your response>',
            },
          ],
        },
      ],
    };

    try {
      final response = await _dio.post<Map<String, dynamic>>(
        url,
        data: body,
        options: Options(headers: const {'Content-Type': 'application/json'}),
      );

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
        if (part is Map<String, dynamic> && part['text'] is String) {
          buffer.write(part['text']);
        }
      }

      final text = buffer.toString().trim();
      if (text.isNotEmpty) {
        debugPrint('[Gemini $_model]:\n$text');
        return text;
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
}
