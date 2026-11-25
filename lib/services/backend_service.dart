import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';

class BackendService {
  BackendService({String? baseUrl, Dio? dio})
      : _explicitBaseUrl = baseUrl,
        _dio = dio ??
            Dio(
              BaseOptions(
                connectTimeout: const Duration(seconds: 10),
                sendTimeout: const Duration(seconds: 10),
                receiveTimeout: const Duration(seconds: 60),
              ),
            );

  final String? _explicitBaseUrl;
  final Dio _dio;
  bool _isRequestInFlight = false;

  bool get isRequestInFlight => _isRequestInFlight;

  String get _baseUrl {
    if (_explicitBaseUrl != null && _explicitBaseUrl.isNotEmpty) {
      return _explicitBaseUrl.replaceAll(RegExp(r'/+$'), '');
    }
    if (dotenv.isInitialized) {
      final envUrl =
          dotenv.env['BACKEND_URL'] ?? dotenv.env['backend_url'];
      if (envUrl != null && envUrl.trim().isNotEmpty) {
        return envUrl.trim().replaceAll(RegExp(r'/+$'), '');
      }
    }
    const defineUrl = String.fromEnvironment('BACKEND_URL');
    if (defineUrl.isNotEmpty) {
      return defineUrl.replaceAll(RegExp(r'/+$'), '');
    }
    // Default Android emulator host loopback; override with BACKEND_URL in .env for physical devices.
    return 'http://localhost:8000';
  }

  /// Sends transcribed user text to the FastAPI backend `/api/v1/ai/query` endpoint.
  /// Drops any new call while a previous request is still awaiting a response.
  // ignore: non_constant_identifier_names
  Future<Map<String, dynamic>?> send_query(String query) async {
    if (_isRequestInFlight) {
      debugPrint(
        '[Backend] Request still in flight; waiting for response before sending another.',
      );
      return null;
    }

    final trimmed = query.trim();
    if (trimmed.isEmpty) return null;

    _isRequestInFlight = true;
    final url = '$_baseUrl/api/v1/ai/query';
    try {
      debugPrint('[Backend] POST $url -> "$trimmed"');
      final response = await _dio.post<Map<String, dynamic>>(
        url,
        data: {'query': trimmed},
        options: Options(headers: const {'Content-Type': 'application/json'}),
      );
      debugPrint('[Backend] HTTP ${response.statusCode}: ${response.data}');
      return response.data;
    } on DioException catch (e) {
      debugPrint(
        '[Backend] API error (${e.response?.statusCode}): ${e.response?.data ?? e.message}',
      );
      return null;
    } catch (e) {
      debugPrint('[Backend] Unexpected error: $e');
      return null;
    } finally {
      _isRequestInFlight = false;
    }
  }
}
