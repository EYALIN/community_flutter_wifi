## 1.0.0

* First release on pub.dev.

* Nearby networks (`getWifiList`, `getCachedWifiList`, `scanFresh` with fresh / throttled / timed-out reporting),
  current network and router details (`getAllWifiDetails`, `getIpInfo`), the active connection from link
  properties without location (`getNetworkInfo`), network diagnostics and an internet check.
* Connected devices on the local network (`getConnectedDevices`, and `connectedDevicesProgress` with real progress
  events); iOS sweeps with TCP probes and Bonjour names behind the Local Network prompt.
* `connect` joins a network through the system's own flow (Android add-network dialog / suggestion, iOS
  `NEHotspotConfiguration`).
* Permission and settings helpers: `requestLocationPermission`, `locationPermissionState`, `isLocationEnabled`,
  `isTetheringActive`, `openLocationSettings`, `openWifiSettings`, `openAppSettings`.
* Error messages are fixed texts and never carry a network or host name.
