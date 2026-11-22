import 'dart:async';

import 'package:flutter/services.dart';

/// Wi-Fi manager: scan, connection / IP / router details, connected devices and diagnostics.
/// Results are plain maps with stable keys and units, the same on Android and iOS where both apply.
/// Failures throw a [PlatformException] whose message is the plugin's error text ("Wi-Fi is
/// disabled", "This feature is not supported on iOS.").
class CommunityFlutterWifi {
  CommunityFlutterWifi._();

  static const MethodChannel _channel = MethodChannel('community_flutter_wifi');

  /// Android only; throws "not supported" on iOS.
  static Future<bool> isWifiEnabled() async => (await _channel.invokeMethod<bool>('isWifiEnabled')) ?? false;

  static Future<bool> hasLocationPermission() async =>
      (await _channel.invokeMethod<bool>('hasLocationPermission')) ?? false;

  /// Shows the system prompt when it can; resolves to whether location is granted afterwards.
  ///
  /// On Android 13+ a host app that declares NEARBY_WIFI_DEVICES gets that permission asked in the same
  /// call - a second system dialog. [includeNearbyDevices] false asks for location alone (the argument is
  /// only sent when false, so the default call is byte-identical to before).
  static Future<bool> requestLocationPermission({bool includeNearbyDevices = true}) async =>
      (await _channel.invokeMethod<bool>(
        'requestLocationPermission',
        includeNearbyDevices ? null : <String, Object?>{'includeNearbyDevices': false},
      )) ??
      false;

  /// Whether the system Location toggle is on (Android; iOS: location services). With it off, SSID
  /// reads and scans come back empty without an error.
  static Future<bool> isLocationEnabled() async => (await _channel.invokeMethod<bool>('isLocationEnabled')) ?? false;

  /// What the user granted: 'fine' (precise location), 'approximate' (Android 12+ "Approximate": the system hides SSIDs
  /// and scan results from it) or 'denied'. [hasLocationPermission] is unchanged: true only for 'fine'. iOS: 'fine',
  /// 'approximate' (Precise Location off - iOS then returns no network name), 'denied', or 'not_determined' (never asked:
  /// the system dialog can still be shown; a caller that does not know it reads it as 'denied').
  static Future<String> locationPermissionState() async =>
      (await _channel.invokeMethod<String>('locationPermissionState')) ?? 'denied';

  /// A hotspot / USB / Bluetooth tether is up on this phone (an `ap0` / `swlan0` / `rndis0` / `bt-pan` interface is up).
  /// While it is, scans and the connected-devices sweep may be empty or partial. No permission needed. iOS: always false
  /// (no API tells it).
  static Future<bool> isTetheringActive() async => (await _channel.invokeMethod<bool>('isTetheringActive')) ?? false;

  /// The system Location settings (Android: the Location toggle screen; iOS: the app's settings), for "turn on Location".
  static Future<bool> openLocationSettings() async => (await _channel.invokeMethod<bool>('openLocationSettings')) ?? false;

  static Future<bool> openWifiSettings() async => (await _channel.invokeMethod<bool>('openWifiSettings')) ?? false;

  /// The app's own page in system settings, for a permission the user denied.
  static Future<bool> openAppSettings() async => (await _channel.invokeMethod<bool>('openAppSettings')) ?? false;

  static Future<bool> isConnectedToInternet() async =>
      (await _channel.invokeMethod<bool>('isConnectedToInternet')) ?? false;

  /// Nearby networks, strongest first: SSID, BSSID, capabilities, frequency ("2.412 GHz"),
  /// security, level (dBm), channelWidth, distance (metres, "%.2f"), hasPassword. Android only.
  static Future<List<Map<String, Object?>>> getWifiList() => _list('getWifiList');

  /// The system's LAST scan results in the [getWifiList] shape - the call asks for NO new scan (Android). Entries
  /// may be older than a few minutes; [getWifiList] is unchanged and still requests a scan. Throws like it when Wi-Fi
  /// is off or location is not granted.
  static Future<List<Map<String, Object?>>> getCachedWifiList() => _list('getCachedWifiList');

  /// A scan that waits for its own result (Android): requests a scan, waits up to [timeout] for the
  /// system's SCAN_RESULTS_AVAILABLE and resolves to `results` (the [getWifiList] entries), `count`,
  /// `requested` (the system accepted the scan), `fresh` (this scan completed and updated the results),
  /// `throttled` (the system declined the scan: `startScan()` returned false - throttled, 4 foreground scans per 2 minutes, OR failed - or finished it without updating, so the results are the cache), `timedOut`,
  /// `ageMs` (age of the newest result, null with none) and `scanTimestampMs` (that moment, epoch ms).
  /// [getWifiList] is unchanged. Throws like it when Wi-Fi is off or location is not granted.
  static Future<Map<String, Object?>> scanFresh({Duration timeout = const Duration(seconds: 10)}) async {
    final raw = await _channel.invokeMethod<Map<Object?, Object?>>('scanFresh', <String, Object?>{
      'timeoutMs': timeout.inMilliseconds,
    });
    final m = _deep(raw ?? const {});
    m['results'] = ((m['results'] as List<Object?>?) ?? const <Object?>[])
        .map((e) => Map<String, Object?>.from(e as Map))
        .toList();
    return m;
  }

  /// The active connection from LinkProperties - NO location permission, GPS or Geocoder (Android):
  /// `connected`, `transport` ('wifi' | 'cellular' | 'ethernet' | 'vpn' | 'other' | 'none'), `isWifi`
  /// (the active network is Wi-Fi - a cellular address is never labelled Wi-Fi), the active network's
  /// `interfaceName`, `ipv4`, `prefixLength`, `subnetMask`, `gateway`, `dnsServers`, `dns1`, `dns2`, `mtu`,
  /// and `wifi` - the Wi-Fi network's own link map with the same keys (also while a VPN is active), or null.
  ///
  /// iOS: the same keys from NWPathMonitor, getifaddrs, the routing table (gateway) and libresolv (DNS) - no
  /// permission, no location. Each link map also carries `ipv6` (a list of the interface's IPv6 addresses; Android sends
  /// no such key). iOS has no per-network DNS, so `dnsServers` is the system resolver's list on both maps.
  static Future<Map<String, Object?>> getNetworkInfo() async =>
      _deep(await _channel.invokeMethod<Map<Object?, Object?>>('getNetworkInfo') ?? const {});

  /// One entry for the active connection: type, signal, speed, ssid, internalip, macaddress,
  /// networkid, frequency, bssid, timezone, dns1, dns2 and the last-known location fields
  /// (latitude, longitude, city, street, country, region, zipcode, state).
  static Future<List<Map<String, Object?>>> getIpInfo() => _list('getIpInfo');

  /// Router facts: iswifienabled, issupportwifi, ssid, bssid, ip, mac, networkid, linkspeed,
  /// signalstrength, gateway, rssi, speed, frequency, channel, dns1, dns2.
  static Future<Map<String, Object?>> getAllWifiDetails() async =>
      Map<String, Object?>.from(await _channel.invokeMethod<Map<Object?, Object?>>('getAllWifiDetails') ?? const {});

  /// Hosts that answered a ping sweep of the local /24: ipAddress, deviceName, localHost,
  /// loopbackAddress, hostAddress, canonicalHostName, multicastAddress, siteLocalAddress. Takes a few seconds.
  /// iOS: the hosts of the /24 around the phone that accept or refuse a TCP connection on a few common ports,
  /// named by Bonjour (the fixed types in NSBonjourServices) or reverse DNS; the Local Network prompt applies and a denial
  /// throws a [PlatformException] with code `local_network_denied`.
  static Future<List<Map<String, Object?>>> getConnectedDevices() => _list('getConnectedDevices');

  static const EventChannel _sweepChannel = EventChannel('community_flutter_wifi/connected_devices');

  /// [getConnectedDevices] with REAL progress (Android): listening starts ONE sweep of the device's own private
  /// subnet (prefix length from the connection, at most [maxHosts] addresses - a /22 by default; a public or
  /// non-Wi-Fi network is refused) and the stream emits `{type: progress, done, total, host?}` as probes finish
  /// (`done` only grows, `host` is set only when that probe answered) and a last `{type: done, done, total,
  /// devices}` (the [getConnectedDevices] maps), then closes. One sweep at a time and a short pause between two:
  /// a second listen inside that window gets an error. Cancelling the subscription stops the probes.
  /// [getConnectedDevices] is unchanged. Nothing is probed until somebody listens.
  ///
  /// iOS: the same events (TCP answers + Bonjour names, see [getConnectedDevices]); the window is at most the /24
  /// around the phone (a larger network says `partial: true`). A Local Network denial is a stream error with code
  /// `local_network_denied`; not on a private Wi-Fi network, code `wifi`.
  static Stream<Map<String, Object?>> connectedDevicesProgress({int maxHosts = 1024}) => _sweepChannel
      .receiveBroadcastStream(<String, Object?>{'maxHosts': maxHosts})
      .map((e) {
        final m = _deep(Map<Object?, Object?>.from(e as Map));
        final devices = m['devices'];
        if (devices is List) m['devices'] = [for (final d in devices) Map<String, Object?>.from(d as Map)];
        return m;
      });

  /// connectionStatus, wifiDetails (null off Wi-Fi) and performance {healthScore, status}.
  /// Android only.
  static Future<Map<String, Object?>> getNetworkDiagnostics() async =>
      _deep(await _channel.invokeMethod<Map<Object?, Object?>>('getNetworkDiagnostics') ?? const {});

  /// Joins the network [ssid] through the system's own join flow: Android 11+ shows the "save this
  /// network?" dialog (`ACTION_WIFI_ADD_NETWORKS`), Android 10 posts a network suggestion, older
  /// Android adds and enables the configuration; iOS applies an `NEHotspotConfiguration` (the app
  /// needs the Hotspot Configuration entitlement). [security] is the Wi-Fi QR `T` field: 'WPA'
  /// (also WPA2 / WPA3 / SAE), 'WEP' or 'NONE' / 'nopass' (an open network; [password] ignored).
  ///
  /// Resolves to 'connected' (joined), 'saved' (approved; the system joins it), 'suggested' (Android 10:
  /// waits for the user's approval in the notification), 'already' (on it already), 'cancelled' (the
  /// user dismissed the dialog) or 'failed'. An Android app declares ACCESS_WIFI_STATE and
  /// CHANGE_WIFI_STATE.
  static Future<String> connect(String ssid, {String password = '', String security = 'WPA', bool hidden = false}) async =>
      (await _channel.invokeMethod<String>('connect', <String, Object?>{
        'ssid': ssid,
        'password': password,
        'security': security,
        'hidden': hidden,
      })) ??
      'failed';

  static Future<List<Map<String, Object?>>> _list(String method) async {
    final list = await _channel.invokeMethod<List<Object?>>(method) ?? const [];
    return list.map((e) => Map<String, Object?>.from(e as Map)).toList();
  }

  static Map<String, Object?> _deep(Map<Object?, Object?> m) =>
      m.map((k, v) => MapEntry(k as String, v is Map ? _deep(v) : v));
}
