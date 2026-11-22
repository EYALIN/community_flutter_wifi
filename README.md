# community_flutter_wifi

A Wi-Fi manager for Flutter apps: nearby networks, connection, IP and router details, the devices on the local network, diagnostics and joining a network.

<!-- support:top:start -->
## ❤️ Support this plugin

I build and maintain this plugin, and [a whole family of free Flutter plugins](https://github.com/EYALIN?tab=repositories&q=community_flutter), in my free time. Every Android and iOS release changes something underneath them (new SDK levels, Swift Package Manager, privacy manifests, OEM quirks), and keeping them working takes hours every month.

**If this plugin saved you from writing the platform code yourself, or it ships in an app that earns money, please sponsor its development.** Even $5 a month makes a real difference. It decides how fast issues get fixed, how quickly the plugin supports each new Android and iOS release, and which features come next. Issues and feature requests from sponsors are looked at first.

[![Sponsor Me](https://img.shields.io/static/v1?label=Sponsor%20Me&style=for-the-badge&message=%E2%9D%A4&logo=GitHub&color=%23fe8e86)](https://github.com/sponsors/EYALIN)

Not able to sponsor? A ⭐ on [GitHub](https://github.com/EYALIN) and a 👍 on pub.dev help other developers find it. Thank you!
<!-- support:top:end -->

- `getWifiList()`, `getCachedWifiList()`, `scanFresh()`: nearby networks with SSID, BSSID, band, channel width, signal, security and an estimated distance (Android).
- `getNetworkInfo()`: the active connection (transport, IPv4, prefix, gateway, DNS, MTU) from the system's link properties, no location needed.
- `getAllWifiDetails()`, `getIpInfo()`: the current Wi-Fi network and router.
- `getConnectedDevices()` and `connectedDevicesProgress()`: a sweep of the local subnet with real progress events.
- `getNetworkDiagnostics()`, `isConnectedToInternet()`, `isTetheringActive()`.
- `connect(ssid, password:, security:)`: joins a network through the system's own flow.
- Permission helpers: `requestLocationPermission()`, `locationPermissionState()`, `isLocationEnabled()`, `openLocationSettings()`, `openWifiSettings()`, `openAppSettings()`.

Results are plain `Map<String, Object?>` values with stable keys and units. Failures throw a `PlatformException`
whose message is a fixed text ("Wi-Fi is disabled", "Location permission is required", ...), never a network or
host name.

| | Android | iOS |
|---|---|---|
| Minimum | API 24 | iOS 15 |
| Nearby networks, scan, diagnostics, `isWifiEnabled` | yes | — (throws "not supported") |
| Network info (addresses, gateway, DNS) | yes | yes |
| SSID / BSSID of the current network | yes (precise location) | yes (precise location + entitlement) |
| Connected devices sweep | ping / reachability, up to a /22 | TCP + Bonjour, the /24 |
| Join a network (`connect`) | yes | yes (`NEHotspotConfiguration`) |
| Tethering state | yes | always `false` |

## Install

```sh
flutter pub add community_flutter_wifi
```

## Usage

```dart
import 'package:community_flutter_wifi/community_flutter_wifi.dart';

// Works without any permission.
final info = await CommunityFlutterWifi.getNetworkInfo();
print('${info['transport']} ${info['ipv4']}/${info['prefixLength']} via ${info['gateway']}');

// The network name and nearby networks need precise location.
if (await CommunityFlutterWifi.requestLocationPermission()) {
  final details = await CommunityFlutterWifi.getAllWifiDetails();
  print('SSID: ${details['ssid']}');

  final scan = await CommunityFlutterWifi.scanFresh();
  for (final n in scan['results'] as List<Map<String, Object?>>) {
    print('${n['SSID']} ${n['level']} dBm ${n['frequency']}');
  }
}

// Devices on the local network, with progress.
final sub = CommunityFlutterWifi.connectedDevicesProgress().listen((e) {
  if (e['type'] == 'progress') print('${e['done']} / ${e['total']}');
  if (e['type'] == 'done') print('${(e['devices'] as List).length} devices');
});
// ... sub.cancel() stops the probes.
```

See [example/lib/main.dart](example/lib/main.dart) for a complete app.

## Android setup

The plugin's manifest declares **no permission**, so an app that links it without showing Wi-Fi details does not
inherit location access. Declare what you use in your app's `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.INTERNET" />
<!-- Optional, Android 13+: requestLocationPermission() then asks for it together with location. -->
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />
```

- With "Approximate" location (Android 12+) or with the Location toggle off, the system hides SSIDs and scan
  results; `locationPermissionState()` and `isLocationEnabled()` tell the two apart.
- Android allows 4 foreground scans per 2 minutes. `scanFresh()` reports `throttled` / `timedOut` and returns the
  cached results in that case; `getCachedWifiList()` never requests a scan.

## iOS setup

iOS gives apps no scan, RSSI, channel or link speed; those calls throw a "not supported" `PlatformException`.
Add to `Info.plist`:

- `NSLocationWhenInUseUsageDescription`: reading the SSID / BSSID needs precise location.
- `NSLocalNetworkUsageDescription`: the connected-devices sweep triggers the Local Network prompt; a denial is a
  `PlatformException` / stream error with code `local_network_denied`.
- `NSBonjourServices`: the service types the sweep browses for device names (`_airplay._tcp`, `_raop._tcp`,
  `_googlecast._tcp`, `_companion-link._tcp`, `_hap._tcp`, `_homekit._tcp`, `_ipp._tcp`, `_ipps._tcp`,
  `_printer._tcp`, `_pdl-datastream._tcp`, `_http._tcp`, `_smb._tcp`, `_afpovertcp._tcp`, `_ssh._tcp`,
  `_sftp-ssh._tcp`, `_rfb._tcp`, `_spotify-connect._tcp`, `_sonos._tcp`, `_amzn-wplay._tcp`, `_device-info._tcp`,
  `_workstation._tcp`, `_matter._tcp`). Types you leave out are simply not found.

Capabilities (entitlements): **Access Wi-Fi Information** for the SSID / BSSID, and **Hotspot Configuration** for
`connect`. Without them the SSID reads as empty and `connect` fails.

## License

MIT — see [LICENSE](LICENSE). Issues and pull requests are welcome at
[github.com/EYALIN/community_flutter_wifi](https://github.com/EYALIN/community_flutter_wifi/issues).

<!-- support:bottom:start -->
## Support

If this plugin is useful to you, please [sponsor its development](https://github.com/sponsors/EYALIN). It keeps the plugin maintained for every new Android and iOS release.
<!-- support:bottom:end -->
