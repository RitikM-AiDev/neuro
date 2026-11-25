import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:neuro_apps/services/tools.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('com.neuro/system');
  final calls = <MethodCall>[];

  setUp(() {
    calls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'executeDeviceAction') {
        final args = Map<String, dynamic>.from(call.arguments as Map);
        final cap = args['capability'];
        final action = args['action'];
        if (cap == 'unknown') {
          return {
            'success': false,
            'capability': cap,
            'action': action,
            'message': "Unsupported capability 'unknown'.",
            'requires_user_action': false,
            'error_code': 'UNSUPPORTED_CAPABILITY',
          };
        }
        return {
          'success': true,
          'capability': cap,
          'action': action,
          'state': args['parameters']?['enabled'] ?? args['parameters']?['level'],
          'message': '$cap $action succeeded',
          'requires_user_action': false,
          'execution_mode': 'direct',
          'error_code': null,
        };
      }
      if (call.method == 'listCapabilities') {
        return [
          {'capability': 'flashlight', 'execution_mode': 'direct'},
          {'capability': 'brightness', 'execution_mode': 'special_access'},
        ];
      }
      return null;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('executeDeviceAction rejects empty capability or action', () async {
    final res = await executeDeviceAction({'capability': '', 'action': 'set'});
    expect(res['success'], isFalse);
    expect(res['error_code'], 'INVALID_REQUEST');
    expect(calls, isEmpty);
  });

  test('executeDeviceAction forwards structured capability JSON', () async {
    final res = await executeDeviceAction({
      'capability': 'flashlight',
      'action': 'set',
      'parameters': {'enabled': true},
    });
    expect(res['success'], isTrue);
    expect(res['capability'], 'flashlight');
    expect(res['state'], isTrue);
    expect(calls.single.method, 'executeDeviceAction');
  });

  test('executeDeviceAction surfaces unsupported capability', () async {
    final res = await executeDeviceAction({
      'capability': 'unknown',
      'action': 'set',
      'parameters': {},
    });
    expect(res['success'], isFalse);
    expect(res['error_code'], 'UNSUPPORTED_CAPABILITY');
  });

  test('listDeviceCapabilities returns registered capabilities', () async {
    final list = await listDeviceCapabilities();
    expect(list.length, 2);
    expect(list.first['capability'], 'flashlight');
  });
}
