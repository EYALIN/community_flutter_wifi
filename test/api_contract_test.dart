// API contract: the plugin's public API only grows. Every existing static method keeps its
// exact signature (a typed tear-off fails to COMPILE if it changes), its channel method name and its
// arguments; the newer calls are pinned beside them.
import 'package:community_flutter_wifi/community_flutter_wifi.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('community_flutter_wifi');
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final calls = <MethodCall>[];
  Object? Function(MethodCall) reply = (_) => null;

  setUp(() {
    calls.clear();
    reply = (_) => null;
    messenger.setMockMethodCallHandler(channel, (c) async {
      calls.add(c);
      return reply(c);
    });
  });
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test('existing signatures are unchanged (compile-time pin)', () {
    final Future<bool> Function() a = CommunityFlutterWifi.isWifiEnabled;
    final Future<bool> Function() b = CommunityFlutterWifi.hasLocationPermission;
    final Future<bool> Function() c = CommunityFlutterWifi.openWifiSettings;
    final Future<bool> Function() d = CommunityFlutterWifi.openAppSettings;
    final Future<bool> Function() e = CommunityFlutterWifi.isConnectedToInternet;
    final Future<List<Map<String, Object?>>> Function() f = CommunityFlutterWifi.getWifiList;
    final Future<List<Map<String, Object?>>> Function() g = CommunityFlutterWifi.getIpInfo;
    final Future<Map<String, Object?>> Function() h = CommunityFlutterWifi.getAllWifiDetails;
    final Future<List<Map<String, Object?>>> Function() i = CommunityFlutterWifi.getConnectedDevices;
    final Future<Map<String, Object?>> Function() j = CommunityFlutterWifi.getNetworkDiagnostics;
    final Future<String> Function(String, {String password, String security, bool hidden}) k =
        CommunityFlutterWifi.connect;
    final Future<bool> Function() l = CommunityFlutterWifi.requestLocationPermission;
    expect([a, b, c, d, e, f, g, h, i, j, k, l], everyElement(isNotNull));
  });

  test('every existing call keeps its channel method name', () async {
    reply = (c) => switch (c.method) {
      'getWifiList' || 'getIpInfo' || 'getConnectedDevices' => <Object?>[],
      'getAllWifiDetails' || 'getNetworkDiagnostics' => <Object?, Object?>{},
      'connect' => 'connected',
      _ => true,
    };
    await CommunityFlutterWifi.isWifiEnabled();
    await CommunityFlutterWifi.hasLocationPermission();
    await CommunityFlutterWifi.requestLocationPermission();
    await CommunityFlutterWifi.openWifiSettings();
    await CommunityFlutterWifi.openAppSettings();
    await CommunityFlutterWifi.isConnectedToInternet();
    await CommunityFlutterWifi.getWifiList();
    await CommunityFlutterWifi.getIpInfo();
    await CommunityFlutterWifi.getAllWifiDetails();
    await CommunityFlutterWifi.getConnectedDevices();
    await CommunityFlutterWifi.getNetworkDiagnostics();
    await CommunityFlutterWifi.connect('home');
    expect(calls.map((c) => c.method), [
      'isWifiEnabled', 'hasLocationPermission', 'requestLocationPermission', 'openWifiSettings', 'openAppSettings',
      'isConnectedToInternet', 'getWifiList', 'getIpInfo', 'getAllWifiDetails', 'getConnectedDevices',
      'getNetworkDiagnostics', 'connect',
    ]);
  });

  test('requestLocationPermission() sends no arguments (as before); includeNearbyDevices:false is opt-in', () async {
    reply = (_) => true;
    await CommunityFlutterWifi.requestLocationPermission();
    expect(calls.last.arguments, isNull);
    await CommunityFlutterWifi.requestLocationPermission(includeNearbyDevices: false);
    expect(calls.last.arguments, {'includeNearbyDevices': false});
  });

  test('getAllWifiDetails passes every router key through untouched', () async {
    const keys = [
      'iswifienabled', 'issupportwifi', 'ssid', 'bssid', 'ip', 'mac', 'networkid', 'linkspeed', 'signalstrength',
      'gateway', 'rssi', 'speed', 'frequency', 'channel', 'dns1', 'dns2',
    ];
    reply = (_) => {
      for (final k in keys) k: k == 'signalstrength' ? 3 : (k == 'rssi' ? -61 : 'v'),
    };
    final d = await CommunityFlutterWifi.getAllWifiDetails();
    expect(d.keys, keys);
    expect(d['signalstrength'], 3); // 0-4 level
    expect(d['rssi'], -61); // dBm
  });

  group('isLocationEnabled', () {
    test('sends isLocationEnabled without arguments', () async {
      reply = (_) => true;
      expect(await CommunityFlutterWifi.isLocationEnabled(), isTrue);
      expect(calls.single.method, 'isLocationEnabled');
      expect(calls.single.arguments, isNull);
    });
    test('null becomes false', () async => expect(await CommunityFlutterWifi.isLocationEnabled(), isFalse));
  });

  group('locationPermissionState / isTetheringActive', () {
    test('locationPermissionState sends its own method without arguments; hasLocationPermission is untouched', () async {
      reply = (_) => 'approximate';
      expect(await CommunityFlutterWifi.locationPermissionState(), 'approximate');
      expect(calls.single.method, 'locationPermissionState');
      expect(calls.single.arguments, isNull);
    });
    test('a null answer is denied; tethering null is false', () async {
      expect(await CommunityFlutterWifi.locationPermissionState(), 'denied');
      expect(await CommunityFlutterWifi.isTetheringActive(), isFalse);
      expect(calls.map((c) => c.method), ['locationPermissionState', 'isTetheringActive']);
    });
    test('openLocationSettings sends its own method', () async {
      reply = (_) => true;
      expect(await CommunityFlutterWifi.openLocationSettings(), isTrue);
      expect(calls.single.method, 'openLocationSettings');
    });
    test('tethering true passes through', () async {
      reply = (_) => true;
      expect(await CommunityFlutterWifi.isTetheringActive(), isTrue);
    });
  });

  group('scanFresh', () {
    final shape = <String, Object?>{
      'results': <Object?>[
        <Object?, Object?>{'SSID': 'home', 'level': -50},
      ],
      'count': 1,
      'requested': true,
      'fresh': true,
      'throttled': false,
      'timedOut': false,
      'ageMs': 800,
      'scanTimestampMs': 1700000000000,
    };

    test('sends the timeout in ms and returns the pinned map shape with typed results', () async {
      reply = (_) => shape;
      final m = await CommunityFlutterWifi.scanFresh(timeout: const Duration(seconds: 5));
      expect(calls.single.method, 'scanFresh');
      expect(calls.single.arguments, {'timeoutMs': 5000});
      expect(
        m.keys,
        containsAll(['results', 'count', 'requested', 'fresh', 'throttled', 'timedOut', 'ageMs', 'scanTimestampMs']),
      );
      expect(m['results'], [
        {'SSID': 'home', 'level': -50},
      ]);
      expect(m['results'], isA<List<Map<String, Object?>>>());
      expect(m['fresh'], isTrue);
      expect(m['ageMs'], 800);
    });

    test('a throttled answer keeps the age', () async {
      reply = (_) => {...shape, 'requested': false, 'fresh': false, 'throttled': true, 'ageMs': 95000};
      final m = await CommunityFlutterWifi.scanFresh();
      expect(calls.single.arguments, {'timeoutMs': 10000});
      expect(m['throttled'], isTrue);
      expect(m['fresh'], isFalse);
      expect(m['ageMs'], 95000);
    });

    test('null result becomes an empty result list', () async {
      expect((await CommunityFlutterWifi.scanFresh())['results'], isEmpty);
    });

    test('PlatformException propagates with the plugin message', () async {
      reply = (_) => throw PlatformException(code: 'wifi', message: 'Wi-Fi is disabled');
      await expectLater(
        CommunityFlutterWifi.scanFresh(),
        throwsA(isA<PlatformException>().having((e) => e.message, 'message', 'Wi-Fi is disabled')),
      );
    });
  });

  group('getNetworkInfo', () {
    test('converts the nested wifi map and the DNS list', () async {
      reply = (_) => <Object?, Object?>{
        'connected': true,
        'transport': 'wifi',
        'isWifi': true,
        'ipv4': '192.168.1.20',
        'prefixLength': 24,
        'subnetMask': '255.255.255.0',
        'gateway': '192.168.1.1',
        'dnsServers': <Object?>['8.8.8.8'],
        'wifi': <Object?, Object?>{'ipv4': '192.168.1.20', 'prefixLength': 24},
      };
      final m = await CommunityFlutterWifi.getNetworkInfo();
      expect(calls.single.method, 'getNetworkInfo');
      expect(calls.single.arguments, isNull);
      expect(m['isWifi'], isTrue);
      expect(m['prefixLength'], 24);
      expect((m['wifi'] as Map<String, Object?>)['ipv4'], '192.168.1.20');
      expect(m['dnsServers'], ['8.8.8.8']);
    });

    test('cellular is not Wi-Fi and wifi stays null', () async {
      reply = (_) => <Object?, Object?>{
        'connected': true,
        'transport': 'cellular',
        'isWifi': false,
        'ipv4': '10.1.2.3',
        'wifi': null,
      };
      final m = await CommunityFlutterWifi.getNetworkInfo();
      expect(m['isWifi'], isFalse);
      expect(m['wifi'], isNull);
    });

    test('null result becomes an empty map', () async => expect(await CommunityFlutterWifi.getNetworkInfo(), isEmpty));

    test('iOS: the Android keys plus an ipv6 list on each link map pass through', () async {
      reply = (_) => <Object?, Object?>{
        'connected': true,
        'transport': 'wifi',
        'isWifi': true,
        'interfaceName': 'en0',
        'ipv4': '192.168.1.23',
        'prefixLength': 24,
        'subnetMask': '255.255.255.0',
        'gateway': '192.168.1.1',
        'dnsServers': <Object?>['192.168.1.1'],
        'dns1': '192.168.1.1',
        'dns2': null,
        'mtu': 1500,
        'ipv6': <Object?>['fd00::23'],
        'wifi': <Object?, Object?>{'ipv4': '192.168.1.23', 'ipv6': <Object?>['fd00::23']},
      };
      final m = await CommunityFlutterWifi.getNetworkInfo();
      expect(m['ipv6'], ['fd00::23']);
      expect((m['wifi'] as Map<String, Object?>)['ipv6'], ['fd00::23']);
      expect(m['gateway'], '192.168.1.1');
    });
  });

  group('connectedDevicesProgress', () {
    const sweep = MethodChannel('community_flutter_wifi/connected_devices');
    final sweepCalls = <MethodCall>[];

    Future<void> emit(Object? event) => messenger.handlePlatformMessage(
        sweep.name, const StandardMethodCodec().encodeSuccessEnvelope(event), (_) {});

    setUp(() {
      sweepCalls.clear();
      messenger.setMockMethodCallHandler(sweep, (c) async {
        sweepCalls.add(c);
        return null;
      });
    });
    tearDown(() => messenger.setMockMethodCallHandler(sweep, null));

    test('nothing is probed until somebody listens, and the old getConnectedDevices does not use the stream', () async {
      reply = (_) => <Object?>[];
      await CommunityFlutterWifi.getConnectedDevices();
      final stream = CommunityFlutterWifi.connectedDevicesProgress();
      expect(sweepCalls, isEmpty, reason: 'creating the stream starts nothing');
      expect(calls.single.method, 'getConnectedDevices');
      final sub = stream.listen((_) {});
      await Future<void>.delayed(Duration.zero);
      expect(sweepCalls.map((c) => c.method), ['listen']);
      expect(sweepCalls.single.arguments, {'maxHosts': 1024});
      await sub.cancel();
      await Future<void>.delayed(Duration.zero);
      expect(sweepCalls.map((c) => c.method), ['listen', 'cancel']);
    });

    test('progress events and the final device list arrive typed, in order', () async {
      final got = <Map<String, Object?>>[];
      final sub = CommunityFlutterWifi.connectedDevicesProgress(maxHosts: 254).listen(got.add);
      await Future<void>.delayed(Duration.zero);
      expect(sweepCalls.single.arguments, {'maxHosts': 254});
      await emit(<Object?, Object?>{'type': 'progress', 'done': 0, 'total': 254, 'host': null});
      await emit(<Object?, Object?>{'type': 'progress', 'done': 100, 'total': 254, 'host': '192.168.1.5'});
      await emit(<Object?, Object?>{
        'type': 'done',
        'done': 254,
        'total': 254,
        'devices': <Object?>[
          <Object?, Object?>{'ipAddress': '192.168.1.5', 'deviceName': 'printer'},
        ],
      });
      await Future<void>.delayed(Duration.zero);
      expect(got.map((e) => e['done']), [0, 100, 254]);
      expect(got.last['type'], 'done');
      expect((got.last['devices'] as List).single, isA<Map<String, Object?>>());
      await sub.cancel();
    });

    test('iOS: a Local Network denial is a stream error with code local_network_denied', () async {
      Object? error;
      final sub = CommunityFlutterWifi.connectedDevicesProgress().listen((_) {}, onError: (Object e) => error = e);
      await Future<void>.delayed(Duration.zero);
      await messenger.handlePlatformMessage(
          sweep.name,
          const StandardMethodCodec().encodeErrorEnvelope(code: 'local_network_denied', message: 'Local Network access is off for this app'),
          (_) {});
      await Future<void>.delayed(Duration.zero);
      expect(error, isA<PlatformException>().having((e) => e.code, 'code', 'local_network_denied'));
      await sub.cancel();
    });
  });
}
