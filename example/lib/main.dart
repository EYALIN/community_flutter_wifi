import 'dart:async';

import 'package:community_flutter_wifi/community_flutter_wifi.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const MaterialApp(home: WifiPage()));

class WifiPage extends StatefulWidget {
  const WifiPage({super.key});

  @override
  State<WifiPage> createState() => _WifiPageState();
}

class _WifiPageState extends State<WifiPage> {
  Map<String, Object?> _network = const {};
  String _ssid = '';
  List<Map<String, Object?>> _nearby = const [];
  List<Map<String, Object?>> _devices = const [];
  String _sweep = '';
  String _error = '';
  StreamSubscription<Map<String, Object?>>? _sub;

  @override
  void initState() {
    super.initState();
    _loadNetwork();
  }

  Future<void> _loadNetwork() async {
    final info = await CommunityFlutterWifi.getNetworkInfo();
    if (mounted) setState(() => _network = info);
  }

  Future<void> _scan() async {
    setState(() => _error = '');
    try {
      if (!await CommunityFlutterWifi.requestLocationPermission()) {
        final state = await CommunityFlutterWifi.locationPermissionState();
        if (mounted) setState(() => _error = 'Location permission: $state');
        return;
      }
      final details = await CommunityFlutterWifi.getAllWifiDetails();
      final scan = await CommunityFlutterWifi.scanFresh();
      if (!mounted) return;
      setState(() {
        _ssid = '${details['ssid'] ?? ''}';
        _nearby = scan['results'] as List<Map<String, Object?>>;
      });
    } on PlatformException catch (e) {
      setState(() => _error = e.message ?? e.code);
    }
  }

  void _sweepDevices() {
    _sub?.cancel();
    setState(() {
      _devices = const [];
      _sweep = 'starting';
    });
    _sub = CommunityFlutterWifi.connectedDevicesProgress().listen(
      (e) => setState(() {
        _sweep = '${e['done']} / ${e['total']}';
        if (e['type'] == 'done') {
          _devices = (e['devices'] as List).cast<Map<String, Object?>>();
          _sweep = 'done: ${_devices.length} devices';
        }
      }),
      onError: (Object e) => setState(() => _sweep = e is PlatformException ? '${e.code}: ${e.message}' : '$e'),
    );
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('community_flutter_wifi')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Text('Transport: ${_network['transport'] ?? '-'}'),
          Text('IPv4: ${_network['ipv4'] ?? '-'}/${_network['prefixLength'] ?? '-'}'),
          Text('Gateway: ${_network['gateway'] ?? '-'}'),
          Text('DNS: ${_network['dnsServers'] ?? '-'}'),
          const SizedBox(height: 16),
          FilledButton(onPressed: _scan, child: const Text('Network name + nearby networks')),
          if (_error.isNotEmpty) Text(_error, style: const TextStyle(color: Colors.red)),
          if (_ssid.isNotEmpty) Text('Connected to: $_ssid'),
          for (final n in _nearby) ListTile(title: Text('${n['SSID']}'), subtitle: Text('${n['level']} dBm, ${n['frequency']}')),
          const SizedBox(height: 16),
          FilledButton(onPressed: _sweepDevices, child: const Text('Devices on this network')),
          if (_sweep.isNotEmpty) Text(_sweep),
          for (final d in _devices) ListTile(title: Text('${d['deviceName']}'), subtitle: Text('${d['ipAddress']}')),
        ],
      ),
    );
  }
}
