import CoreLocation
import Flutter
import Network
import NetworkExtension
import UIKit

/// iOS side of the Wi-Fi manager. iOS exposes no scan, no RSSI, no channel and no link speed to apps — those calls
/// return a "not supported" error (getWifiList, getCachedWifiList, scanFresh, getNetworkDiagnostics, isWifiEnabled),
/// so an app should hide those features on iOS. SSID/BSSID come from NEHotspotNetwork
/// (needs precise location + the Access Wi-Fi Information entitlement; without them they read as empty).
/// What iOS does allow, with the Android shapes: getNetworkInfo (addresses, mask, gateway, DNS - no location) and the
/// connected-devices sweep (getConnectedDevices and the `community_flutter_wifi/connected_devices` EventChannel: TCP
/// answers + Bonjour names, behind the Local Network prompt; see LanSweep).
public class CommunityFlutterWifiPlugin: NSObject, FlutterPlugin, FlutterStreamHandler, CLLocationManagerDelegate {
  private let locationManager = CLLocationManager()
  /// Every caller waiting on the location prompt; each one is answered once the user decides.
  private var pendingPermission: [FlutterResult] = []
  private var pendingLocation: [(CLLocation?) -> Void] = []
  private var locationRequest = 0
  /// The running EventChannel sweep (one at a time; cancelled with the Dart subscription).
  private var sweep: LanSweep?

  public static func register(with registrar: FlutterPluginRegistrar) {
    let channel = FlutterMethodChannel(name: "community_flutter_wifi", binaryMessenger: registrar.messenger())
    let instance = CommunityFlutterWifiPlugin()
    instance.locationManager.delegate = instance
    registrar.addMethodCallDelegate(instance, channel: channel)
    let sweepChannel = FlutterEventChannel(name: "community_flutter_wifi/connected_devices", binaryMessenger: registrar.messenger())
    sweepChannel.setStreamHandler(instance)
  }

  // MARK: - Connected devices (EventChannel)

  public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
    let maxHosts = ((arguments as? [String: Any])?["maxHosts"] as? NSNumber)?.intValue ?? 1024
    let run = LanSweep(
      onEvent: { event in
        events(event)
        if event["type"] as? String == "done" { events(FlutterEndOfEventStream) }
      },
      onError: { failure in
        events(FlutterError(code: failure.code, message: failure.message, details: nil))
        events(FlutterEndOfEventStream)
      })
    sweep = run
    run.start(maxHosts: maxHosts)
    return nil
  }

  public func onCancel(withArguments arguments: Any?) -> FlutterError? {
    sweep?.cancel()
    sweep = nil
    return nil
  }

  /// getConnectedDevices: the same sweep, answered with its device list.
  private func connectedDevices(_ result: @escaping FlutterResult) {
    var holder: LanSweep?
    holder = LanSweep(
      onEvent: { event in
        guard event["type"] as? String == "done" else { return }
        result(event["devices"] ?? [])
        holder = nil
      },
      onError: { failure in
        result(FlutterError(code: failure.code, message: failure.message, details: nil))
        holder = nil
      })
    holder?.start(maxHosts: LanSweep.maxHosts)
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "isWifiEnabled":
      result(FlutterError(code: "wifi", message: "This feature is not supported on iOS.", details: nil))
    case "hasLocationPermission":
      result(authorized)
    case "requestLocationPermission":
      if authorized || status != .notDetermined { result(authorized); return }
      // Without a purpose string iOS ignores the request and no answer would ever come.
      guard Self.hasWhenInUsePurpose else { result(false); return }
      pendingPermission.append(result)
      if pendingPermission.count == 1 { locationManager.requestWhenInUseAuthorization() }
    case "locationPermissionState":
      // Precise Location off: iOS returns no network name (NEHotspotNetwork needs full accuracy).
      // 'not_determined' (additive, iOS only): never asked, so the system dialog can still be shown. Every reader that
      // does not know it treats it as 'denied' (WifiAccessState.resolve), as before.
      if status == .notDetermined { result("not_determined") }
      else if !authorized { result("denied") }
      else { result(locationManager.accuracyAuthorization == .reducedAccuracy ? "approximate" : "fine") }
    case "isTetheringActive":
      result(false)
    case "isLocationEnabled":
      // locationServicesEnabled() can block: ask off the main thread.
      DispatchQueue.global(qos: .userInitiated).async {
        let on = CLLocationManager.locationServicesEnabled()
        DispatchQueue.main.async { result(on) }
      }
    case "openWifiSettings", "openAppSettings", "openLocationSettings":
      guard let url = URL(string: UIApplication.openSettingsURLString) else { result(false); return }
      UIApplication.shared.open(url) { ok in result(ok) }
    case "isConnectedToInternet":
      reachability { result($0.status == .satisfied) }
    case "getAllWifiDetails":
      network { ssid, bssid in
        self.reachability { path in
          let details: [String: Any] = [
            "iswifienabled": path.usesInterfaceType(.wifi),
            "issupportwifi": true,
            "ssid": ssid ?? "Unavailable",
            "bssid": bssid ?? "Unavailable",
            "ip": Self.ipAddress() ?? "Unavailable",
            "mac": "Unavailable",
            "networkid": -1,
            "linkspeed": -1,
            "signalstrength": -1,
            "gateway": "Unavailable",
            "rssi": -1,
            "speed": -1,
            "frequency": -1,
            "channel": -1,
            "dns1": "Unavailable",
            "dns2": "Unavailable",
          ]
          result(details)
        }
      }
    case "getIpInfo":
      ipInfo(result)
    case "getNetworkInfo":
      reachability { path in
        DispatchQueue.global(qos: .userInitiated).async {
          let info = WifiNetworkInfo.read(path)
          DispatchQueue.main.async { result(info) }
        }
      }
    case "getConnectedDevices":
      connectedDevices(result)
    case "connect":
      let args = call.arguments as? [String: Any] ?? [:]
      connect(
        ssid: args["ssid"] as? String ?? "",
        password: args["password"] as? String ?? "",
        security: (args["security"] as? String ?? "WPA").uppercased(),
        result: result)
    default:
      // getWifiList, getCachedWifiList, scanFresh, getNetworkDiagnostics.
      result(FlutterError(code: "wifi", message: "This feature is not supported on iOS.", details: nil))
    }
  }

  // MARK: - Location

  private var status: CLAuthorizationStatus { locationManager.authorizationStatus }
  private var authorized: Bool { status == .authorizedWhenInUse || status == .authorizedAlways }

  public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
    guard status != .notDetermined, !pendingPermission.isEmpty else { return }
    let waiting = pendingPermission
    pendingPermission = []
    waiting.forEach { $0(authorized) }
  }

  private static var hasWhenInUsePurpose: Bool {
    let text = Bundle.main.object(forInfoDictionaryKey: "NSLocationWhenInUseUsageDescription") as? String
    return !(text ?? "").isEmpty
  }

  /// How long a location fix or a reverse geocode may take before the read goes on without it.
  private static let locationTimeout: TimeInterval = 10

  public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
    flushLocation(locations.last)
  }

  public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
    flushLocation(nil)
  }

  private func flushLocation(_ location: CLLocation?) {
    let waiting = pendingLocation
    pendingLocation = []
    waiting.forEach { $0(location) }
  }

  private func currentLocation(_ done: @escaping (CLLocation?) -> Void) {
    guard authorized else { done(nil); return }
    pendingLocation.append(done)
    guard pendingLocation.count == 1 else { return }
    locationRequest += 1
    let request = locationRequest
    locationManager.requestLocation()
    // A fix that never comes (no simulated location, no GPS) must not hold the read forever.
    DispatchQueue.main.asyncAfter(deadline: .now() + Self.locationTimeout) { [weak self] in
      guard let self = self, self.locationRequest == request, !self.pendingLocation.isEmpty else { return }
      self.locationManager.stopUpdatingLocation()
      self.flushLocation(nil)
    }
  }

  private func ipInfo(_ result: @escaping FlutterResult) {
    network { ssid, bssid in
      self.reachability { path in
        self.currentLocation { location in
          var answered = false
          let finish: (CLPlacemark?) -> Void = { placemark in
            if answered { return }
            answered = true
            var type = "none"
            if path.usesInterfaceType(.wifi) { type = "wifi" }
            else if path.usesInterfaceType(.cellular) { type = "cellular" }
            else if path.status == .satisfied { type = "unknown" }
            let latitude: Double = location?.coordinate.latitude ?? 0
            let longitude: Double = location?.coordinate.longitude ?? 0
            let info: [String: Any] = [
              "type": type,
              "signal": -1,
              "speed": -1,
              "ssid": ssid ?? "",
              "internalip": Self.ipAddress() ?? "",
              "macaddress": "Unavailable",
              "networkid": -1,
              "frequency": -1,
              "bssid": bssid ?? "",
              "dns1": "Unavailable",
              "dns2": "Unavailable",
              "timezone": TimeZone.current.identifier,
              "latitude": latitude,
              "longitude": longitude,
              "city": placemark?.locality ?? "",
              "street": placemark?.thoroughfare ?? "",
              "country": placemark?.country ?? "",
              "region": placemark?.administrativeArea ?? "",
              "zipcode": placemark?.postalCode ?? "",
              "state": placemark?.administrativeArea ?? "",
            ]
            result([info])
          }
          guard let location = location else { finish(nil); return }
          let geocoder = CLGeocoder()
          geocoder.reverseGeocodeLocation(location) { marks, _ in
            DispatchQueue.main.async { finish(marks?.first) }
          }
          // Offline or throttled geocoding: answer with the coordinates only.
          DispatchQueue.main.asyncAfter(deadline: .now() + Self.locationTimeout) {
            geocoder.cancelGeocode()
            finish(nil)
          }
        }
      }
    }
  }

  // MARK: - Join a network

  /// NEHotspotConfiguration (the app needs the Hotspot Configuration entitlement): 'connected',
  /// 'already' (on that network), 'cancelled' (the user declined the system prompt) or 'failed'.
  private func connect(ssid: String, password: String, security: String, result: @escaping FlutterResult) {
    if ssid.isEmpty {
      result(FlutterError(code: "wifi", message: "No network name", details: nil))
      return
    }
    let open = security.isEmpty || security == "NONE" || security == "NOPASS"
    let config = open
      ? NEHotspotConfiguration(ssid: ssid)
      : NEHotspotConfiguration(ssid: ssid, passphrase: password, isWEP: security == "WEP")
    config.joinOnce = false
    NEHotspotConfigurationManager.shared.apply(config) { error in
      DispatchQueue.main.async {
        guard let error = error as NSError? else { result("connected"); return }
        if error.domain == NEHotspotConfigurationErrorDomain {
          switch error.code {
          case NEHotspotConfigurationError.alreadyAssociated.rawValue: result("already"); return
          case NEHotspotConfigurationError.userDenied.rawValue: result("cancelled"); return
          default: break
          }
        }
        result("failed")
      }
    }
  }

  // MARK: - Network

  private func network(_ done: @escaping (String?, String?) -> Void) {
    NEHotspotNetwork.fetchCurrent { net in
      DispatchQueue.main.async { done(net?.ssid, net?.bssid) }
    }
  }

  private func reachability(_ done: @escaping (Network.NWPath) -> Void) {
    let monitor = Network.NWPathMonitor()
    monitor.pathUpdateHandler = { path in
      monitor.cancel()
      DispatchQueue.main.async { done(path) }
    }
    monitor.start(queue: DispatchQueue.global(qos: .utility))
  }

  private static func ipAddress() -> String? {
    var address: String?
    var ifaddr: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&ifaddr) == 0 else { return nil }
    defer { freeifaddrs(ifaddr) }
    var ptr = ifaddr
    while let cur = ptr {
      let iface = cur.pointee
      if iface.ifa_addr.pointee.sa_family == UInt8(AF_INET), String(cString: iface.ifa_name) == "en0" {
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        getnameinfo(iface.ifa_addr, socklen_t(iface.ifa_addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
        address = String(cString: host)
      }
      ptr = iface.ifa_next
    }
    return address
  }
}
