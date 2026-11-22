import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:community_flutter_wifi/community_flutter_wifi.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('community_flutter_wifi');
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  final calls = <MethodCall>[];
  Object? Function(MethodCall call) reply = (_) => null;

  setUp(() {
    calls.clear();
    reply = (_) => null;
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return reply(call);
    });
  });

  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  final boolCalls = <String, Future<bool> Function()>{
    'isWifiEnabled': CommunityFlutterWifi.isWifiEnabled,
    'hasLocationPermission': CommunityFlutterWifi.hasLocationPermission,
    'requestLocationPermission': CommunityFlutterWifi.requestLocationPermission,
    'openWifiSettings': CommunityFlutterWifi.openWifiSettings,
    'openAppSettings': CommunityFlutterWifi.openAppSettings,
    'isConnectedToInternet': CommunityFlutterWifi.isConnectedToInternet,
  };

  for (final entry in boolCalls.entries) {
    group(entry.key, () {
      test('sends ${entry.key} on community_flutter_wifi without arguments and returns true', () async {
        reply = (_) => true;
        expect(await entry.value(), isTrue);
        expect(calls.single.method, entry.key);
        expect(calls.single.arguments, isNull);
      });

      test('returns false', () async {
        reply = (_) => false;
        expect(await entry.value(), isFalse);
      });

      test('null result becomes false', () async {
        expect(await entry.value(), isFalse);
      });

      test('wrong type throws', () async {
        reply = (_) => 'true';
        expect(entry.value(), throwsA(isA<TypeError>()));
      });

      test('PlatformException propagates with the plugin message', () async {
        reply = (_) => throw PlatformException(code: 'ERR', message: 'This feature is not supported on iOS.');
        await expectLater(
          entry.value(),
          throwsA(isA<PlatformException>().having((e) => e.message, 'message', 'This feature is not supported on iOS.')),
        );
      });

      test('MissingPluginException propagates', () async {
        messenger.setMockMethodCallHandler(channel, null);
        await expectLater(entry.value(), throwsA(isA<MissingPluginException>()));
      });
    });
  }

  final listCalls = <String, Future<List<Map<String, Object?>>> Function()>{
    'getWifiList': CommunityFlutterWifi.getWifiList,
    'getIpInfo': CommunityFlutterWifi.getIpInfo,
    'getConnectedDevices': CommunityFlutterWifi.getConnectedDevices,
  };

  for (final entry in listCalls.entries) {
    group(entry.key, () {
      test('sends ${entry.key} and converts to typed maps', () async {
        reply = (_) => [
              {'ssid': 'Home', 'level': -50, 'hasPassword': true, 'x': null},
              {'ssid': 'Guest', 'level': -80},
            ];
        final r = await entry.value();
        expect(calls.single.method, entry.key);
        expect(calls.single.arguments, isNull);
        expect(r, hasLength(2));
        expect(r.first, {'ssid': 'Home', 'level': -50, 'hasPassword': true, 'x': null});
        expect(r.last['ssid'], 'Guest');
        expect(r.first, isA<Map<String, Object?>>());
      });

      test('empty list', () async {
        reply = (_) => <Object?>[];
        expect(await entry.value(), isEmpty);
      });

      test('null result becomes an empty list', () async {
        expect(await entry.value(), isEmpty);
      });

      test('non-map element throws', () async {
        reply = (_) => ['nope'];
        expect(entry.value(), throwsA(isA<TypeError>()));
      });

      test('non-list result throws', () async {
        reply = (_) => {'a': 1};
        expect(entry.value(), throwsA(isA<TypeError>()));
      });

      test('PlatformException propagates', () async {
        reply = (_) => throw PlatformException(code: 'ERR', message: "Wi-Fi is disabled");
        await expectLater(entry.value(), throwsA(isA<PlatformException>()));
      });
    });
  }

  group('getAllWifiDetails', () {
    test('sends getAllWifiDetails and returns a typed map', () async {
      reply = (_) => {'iswifienabled': true, 'ssid': 'Home', 'rssi': -40, 'channel': 6};
      final r = await CommunityFlutterWifi.getAllWifiDetails();
      expect(calls.single.method, 'getAllWifiDetails');
      expect(calls.single.arguments, isNull);
      expect(r, {'iswifienabled': true, 'ssid': 'Home', 'rssi': -40, 'channel': 6});
    });

    test('null result becomes an empty map', () async {
      expect(await CommunityFlutterWifi.getAllWifiDetails(), isEmpty);
    });

    test('wrong type throws', () async {
      reply = (_) => [1];
      expect(CommunityFlutterWifi.getAllWifiDetails(), throwsA(isA<TypeError>()));
    });

    test('PlatformException propagates', () async {
      reply = (_) => throw PlatformException(code: 'ERR');
      await expectLater(CommunityFlutterWifi.getAllWifiDetails(), throwsA(isA<PlatformException>()));
    });
  });

  group('getNetworkDiagnostics', () {
    test('sends getNetworkDiagnostics and converts nested maps', () async {
      reply = (_) => {
            'connectionStatus': 'connected',
            'wifiDetails': {'ssid': 'Home', 'deep': {'a': 1}},
            'performance': {'healthScore': 90, 'status': 'good'},
          };
      final r = await CommunityFlutterWifi.getNetworkDiagnostics();
      expect(calls.single.method, 'getNetworkDiagnostics');
      expect(calls.single.arguments, isNull);
      expect(r['connectionStatus'], 'connected');
      expect(r['wifiDetails'], isA<Map<String, Object?>>());
      expect((r['wifiDetails']! as Map)['deep'], isA<Map<String, Object?>>());
      expect((r['performance']! as Map)['healthScore'], 90);
    });

    test('null wifiDetails stays null (off Wi-Fi)', () async {
      reply = (_) => {'connectionStatus': 'none', 'wifiDetails': null, 'performance': {'healthScore': 0, 'status': 'poor'}};
      final r = await CommunityFlutterWifi.getNetworkDiagnostics();
      expect(r.containsKey('wifiDetails'), isTrue);
      expect(r['wifiDetails'], isNull);
    });

    test('null result becomes an empty map', () async {
      expect(await CommunityFlutterWifi.getNetworkDiagnostics(), isEmpty);
    });

    test('non-string key throws', () async {
      reply = (_) => {1: 'x'};
      expect(CommunityFlutterWifi.getNetworkDiagnostics(), throwsA(isA<TypeError>()));
    });

    test('wrong type throws', () async {
      reply = (_) => 'x';
      expect(CommunityFlutterWifi.getNetworkDiagnostics(), throwsA(isA<TypeError>()));
    });

    test('PlatformException propagates', () async {
      reply = (_) => throw PlatformException(code: 'ERR', message: 'Wi-Fi is disabled');
      await expectLater(CommunityFlutterWifi.getNetworkDiagnostics(), throwsA(isA<PlatformException>()));
    });
  });

  group('connect', () {
    test('sends ssid, password, security and hidden; returns the status', () async {
      reply = (_) => 'saved';
      expect(await CommunityFlutterWifi.connect('home', password: 'secret', security: 'WEP', hidden: true), 'saved');
      expect(calls.single.method, 'connect');
      expect(calls.single.arguments, <String, Object?>{'ssid': 'home', 'password': 'secret', 'security': 'WEP', 'hidden': true});
    });

    test('defaults to WPA, no password, visible; null becomes failed', () async {
      expect(await CommunityFlutterWifi.connect('cafe'), 'failed');
      expect(calls.single.arguments, <String, Object?>{'ssid': 'cafe', 'password': '', 'security': 'WPA', 'hidden': false});
    });

    test('PlatformException propagates', () async {
      reply = (_) => throw PlatformException(code: 'wifi', message: 'No network name');
      await expectLater(CommunityFlutterWifi.connect(''), throwsA(isA<PlatformException>()));
    });
  });
}
