import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class SystemController {
  static const MethodChannel _channel =
      MethodChannel('com.neuro/system');

  /// Sends a structured dynamic capability request:
  /// `{ "capability": "...", "action": "...", "parameters": {...} }`.
  static Future<Map<String, dynamic>> executeDeviceAction(
    Map<String, dynamic> request,
  ) async {
    final capability = request['capability']?.toString() ?? '';
    final action = request['action']?.toString() ?? '';
    if (capability.trim().isEmpty || action.trim().isEmpty) {
      return {
        'success': false,
        'capability': capability,
        'action': action,
        'message': 'Request must include non-empty "capability" and "action".',
        'requires_user_action': false,
        'error_code': 'INVALID_REQUEST',
      };
    }

    try {
      final response = await _channel.invokeMapMethod<String, dynamic>(
        'executeDeviceAction',
        request,
      );
      if (response != null) {
        debugPrint('[Tools] executeDeviceAction($capability.$action) -> $response');
        return response;
      }
      return {
        'success': false,
        'capability': capability,
        'action': action,
        'message': 'Empty response from native dispatcher.',
        'requires_user_action': false,
        'error_code': 'EMPTY_RESPONSE',
      };
    } on PlatformException catch (e) {
      debugPrint('[Tools] executeDeviceAction error: ${e.code} - ${e.message}');
      return {
        'success': false,
        'capability': capability,
        'action': action,
        'message': e.message ?? 'Platform exception during execution.',
        'requires_user_action': false,
        'error_code': e.code,
      };
    } on MissingPluginException catch (e) {
      debugPrint('[Tools] executeDeviceAction missing plugin: $e');
      return {
        'success': false,
        'capability': capability,
        'action': action,
        'message': 'Native device control channel is not registered.',
        'requires_user_action': false,
        'error_code': 'MISSING_PLUGIN',
      };
    } catch (e) {
      debugPrint('[Tools] executeDeviceAction unexpected error: $e');
      return {
        'success': false,
        'capability': capability,
        'action': action,
        'message': e.toString(),
        'requires_user_action': false,
        'error_code': 'UNEXPECTED_ERROR',
      };
    }
  }

  static Future<List<Map<String, dynamic>>> listCapabilities() async {
    try {
      final raw = await _channel.invokeListMethod<Map<dynamic, dynamic>>(
        'listCapabilities',
      );
      if (raw == null) return const [];
      return raw.map((e) => Map<String, dynamic>.from(e)).toList();
    } catch (e) {
      debugPrint('[Tools] listCapabilities error: $e');
      return const [];
    }
  }

  static Future<bool?> internet() async =>
      await _channel.invokeMethod('internet');

  static Future<bool?> wifi() async =>
      await _channel.invokeMethod('wifi');

  static Future<bool?> location() async =>
      await _channel.invokeMethod('location');

  static Future<bool?> bluetooth() async =>
      await _channel.invokeMethod('bluetooth');

  static Future<bool?> airplane() async =>
      await _channel.invokeMethod('airplane');

  static Future<bool?> flashlight(bool enabled) async =>
      await _channel.invokeMethod(
        'flashlight',
        {'enabled': enabled},
      );

  static Future<bool?> vibration() async =>
      await _channel.invokeMethod('vibration');

  static Future<bool?> silent() async =>
      await _channel.invokeMethod('silent');

  static Future<bool?> normal() async =>
      await _channel.invokeMethod('normal');

  static Future<bool?> autoBrightness(bool enabled) async =>
      await _channel.invokeMethod(
        'autoBrightness',
        {'enabled': enabled},
      );

  static Future<bool?> batterySaver() async =>
      await _channel.invokeMethod('batterySaver');

  static Future<bool?> hotspot() async =>
      await _channel.invokeMethod('hotspot');

  static Future<bool?> screenshot() async =>
      await _channel.invokeMethod('screenshot');

  static Future<bool?> darkMode(bool enabled) async =>
      await _channel.invokeMethod(
        'darkMode',
        {'enabled': enabled},
      );
}

class ToolsService {
  static const MethodChannel _controlChannel =
      MethodChannel('com.example.neuro_apps/control');

  static Future<bool> _invokeControl(
    String method, [
    Map<String, dynamic>? arguments,
  ]) async {
    try {
      final result = await _controlChannel.invokeMethod<bool>(
        method,
        arguments,
      );
      debugPrint('[Tools] $method($arguments) -> $result');
      return result ?? false;
    } on PlatformException catch (e) {
      debugPrint('[Tools] $method error: ${e.code} - ${e.message}');
      return false;
    } on MissingPluginException catch (e) {
      debugPrint('[Tools] $method not implemented: $e');
      return false;
    } catch (e) {
      debugPrint('[Tools] $method unexpected error: $e');
      return false;
    }
  }

  static Future<bool> _runSystemCall(
    String label,
    Future<bool?> Function() action,
  ) async {
    try {
      final result = await action();
      debugPrint('[Tools] $label -> $result');
      return result ?? false;
    } on PlatformException catch (e) {
      debugPrint('[Tools] $label error: ${e.code} - ${e.message}');
      return false;
    } on MissingPluginException catch (e) {
      debugPrint('[Tools] $label not implemented: $e');
      return false;
    } catch (e) {
      debugPrint('[Tools] $label unexpected error: $e');
      return false;
    }
  }

  static bool _isEnabled(String status) =>
      status.trim().toLowerCase() == 'on';

  /// Launch Instagram and trigger Neuro's Accessibility Service
  /// to navigate to the Reels tab.
  static Future<bool> openInstagramReels() =>
      _invokeControl('openInstagramReels');

  /// Open Android Accessibility settings.
  static Future<bool> openAccessibilitySettings() =>
      _invokeControl('openAccessibilitySettings');

  /// Returns true when Neuro Assistant Accessibility Service is enabled.
  static Future<bool> isAccessibilityEnabled() =>
      _invokeControl('isAccessibilityEnabled');

  /// Search the web for [query].
  static Future<bool> searchWeb(String query) {
    final trimmed = query.trim();
    if (trimmed.isEmpty) return Future.value(false);
    return _invokeControl('search_web', {'query': trimmed});
  }

  static Future<bool> toggleInternet(String status) =>
      _runSystemCall('ToggleInternet', SystemController.internet);

  static Future<bool> toggleBluetooth(String status) =>
      _runSystemCall('ToggleBluetooth', SystemController.bluetooth);

  static Future<bool> toggleWifi(String status) =>
      _runSystemCall('ToggleWifi', SystemController.wifi);

  static Future<bool> toggleDarkMode(String status) => _runSystemCall(
        'ToggleDarkMode',
        () => SystemController.darkMode(_isEnabled(status)),
      );

  static Future<bool> toggleAirplaneMode(String status) =>
      _runSystemCall('ToggleAirplaneMode', SystemController.airplane);

  static Future<bool> toggleFlash(String status) => _runSystemCall(
        'ToggleFlash',
        () => SystemController.flashlight(_isEnabled(status)),
      );

  static Future<bool> toggleVibration(String status) => _runSystemCall(
        'ToggleVibration',
        _isEnabled(status)
            ? SystemController.vibration
            : SystemController.normal,
      );

  static Future<bool> toggleSilent(String status) => _runSystemCall(
        'ToggleSilent',
        _isEnabled(status) ? SystemController.silent : SystemController.normal,
      );

  static Future<bool> toggleLocation(String status) =>
      _runSystemCall('ToggleLocation', SystemController.location);

  static Future<bool> toggleAutoBrightness(String status) => _runSystemCall(
        'ToggleAutoBrightness',
        () => SystemController.autoBrightness(_isEnabled(status)),
      );

  static Future<bool> toggleScreenshot(String status) =>
      _runSystemCall('ToggleScreenshot', SystemController.screenshot);

  static Future<bool> toggleLowPowerMode(String status) =>
      _runSystemCall('ToggleLowPowerMode', SystemController.batterySaver);

  static Future<bool> toggleHotspot(String status) =>
      _runSystemCall('ToggleHotspot', SystemController.hotspot);

  /// Dispatches a tool call by [toolName] and [params] from Gemini output.
  static Future<bool> executeTool(
    String toolName,
    Map<String, dynamic> params,
  ) {
    final status = params['status']?.toString() ?? 'on';
    switch (toolName.trim()) {
      case 'search_web':
        return searchWeb(params['query']?.toString() ?? '');
      case 'ToggleInternet':
        return toggleInternet(status);
      case 'ToggleBluetooth':
        return toggleBluetooth(status);
      case 'ToggleWifi':
        return toggleWifi(status);
      case 'ToggleDarkMode':
        return toggleDarkMode(status);
      case 'ToggleAirplaneMode':
        return toggleAirplaneMode(status);
      case 'ToggleFlash':
        return toggleFlash(status);
      case 'ToggleVibration':
        return toggleVibration(status);
      case 'ToggleSilent':
        return toggleSilent(status);
      case 'ToggleLocation':
        return toggleLocation(status);
      case 'ToggleAutoBrightness':
        return toggleAutoBrightness(status);
      case 'ToggleScreenshot':
        return toggleScreenshot(status);
      case 'ToggleLowPowerMode':
        return toggleLowPowerMode(status);
      case 'ToggleHotspot':
        return toggleHotspot(status);
      case 'openInstagramReels':
        return openInstagramReels();
      default:
        debugPrint('[Tools] Unknown tool: $toolName');
        return Future.value(false);
    }
  }
}

Future<Map<String, dynamic>> executeDeviceAction(
  Map<String, dynamic> request,
) =>
    SystemController.executeDeviceAction(request);

Future<List<Map<String, dynamic>>> listDeviceCapabilities() =>
    SystemController.listCapabilities();

Future<bool> openInstagramReels() => ToolsService.openInstagramReels();

Future<bool> openAccessibilitySettings() =>
    ToolsService.openAccessibilitySettings();

Future<bool> isAccessibilityEnabled() =>
    ToolsService.isAccessibilityEnabled();

Future<bool> searchWeb(String query) => ToolsService.searchWeb(query);
Future<bool> toggleInternet(String status) => ToolsService.toggleInternet(status);
Future<bool> toggleBluetooth(String status) =>
    ToolsService.toggleBluetooth(status);
Future<bool> toggleWifi(String status) => ToolsService.toggleWifi(status);
Future<bool> toggleDarkMode(String status) => ToolsService.toggleDarkMode(status);
Future<bool> toggleAirplaneMode(String status) =>
    ToolsService.toggleAirplaneMode(status);
Future<bool> toggleFlash(String status) => ToolsService.toggleFlash(status);
Future<bool> toggleVibration(String status) =>
    ToolsService.toggleVibration(status);
Future<bool> toggleSilent(String status) => ToolsService.toggleSilent(status);
Future<bool> toggleLocation(String status) => ToolsService.toggleLocation(status);
Future<bool> toggleAutoBrightness(String status) =>
    ToolsService.toggleAutoBrightness(status);
Future<bool> toggleScreenshot(String status) =>
    ToolsService.toggleScreenshot(status);
Future<bool> toggleLowPowerMode(String status) =>
    ToolsService.toggleLowPowerMode(status);
Future<bool> toggleHotspot(String status) => ToolsService.toggleHotspot(status);
