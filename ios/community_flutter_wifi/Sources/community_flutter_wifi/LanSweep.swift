import Foundation
import Network
import UIKit

/// The connected-devices sweep on iOS, with the Android event shape (`WifiMath.progressEvent` / `doneEvent` /
/// `deviceEntry`). iOS has no ICMP for apps and no ARP table, so a host "answers" when a TCP connection to one of a few
/// common ports is accepted OR refused (a refusal is the host itself replying), within a short timeout. Names come
/// from reverse DNS (the router's DHCP names) and from Bonjour, browsing ONLY the fixed service types the app declares
/// in NSBonjourServices - no browse-all, no SSDP, so no multicast entitlement is needed.
///
/// Everything here is local-network traffic: iOS shows the Local Network prompt on the first sweep. A denial is reported
/// as the error code `local_network_denied` (no API reports the permission state, so it is read from the connections).
final class LanSweep {
  /// The window of one sweep on iOS: the /24 around the phone (a larger network is swept in part, `partial` true).
  static let maxHosts = 256
  /// Ports a typical home device listens on or refuses quickly: web UI, HTTPS, iPhone/iPad sync, SMB, SSH.
  static let ports: [UInt16] = [80, 443, 62078, 445, 22]
  static let probeTimeout: TimeInterval = 1.5
  static let concurrentHosts = 24
  static let nameTimeout: TimeInterval = 1.5
  static let bonjourWindow: TimeInterval = 4
  /// The Bonjour types the app may browse. Each must also be listed in the app's NSBonjourServices.
  static let bonjourTypes = [
    "_airplay._tcp", "_raop._tcp", "_googlecast._tcp", "_companion-link._tcp", "_hap._tcp", "_homekit._tcp",
    "_ipp._tcp", "_ipps._tcp", "_printer._tcp", "_pdl-datastream._tcp", "_http._tcp", "_smb._tcp",
    "_afpovertcp._tcp", "_ssh._tcp", "_sftp-ssh._tcp", "_rfb._tcp", "_spotify-connect._tcp", "_sonos._tcp",
    "_amzn-wplay._tcp", "_device-info._tcp", "_workstation._tcp", "_matter._tcp",
  ]

  enum Failure: Error {
    case busy, notOnWifi, localNetworkDenied
    var code: String {
      switch self {
      case .busy: return "wifi"
      case .notOnWifi: return "wifi"
      case .localNetworkDenied: return "local_network_denied"
      }
    }
    var message: String {
      switch self {
      case .busy: return "A scan is running or was just done; try again in a few seconds"
      case .notOnWifi: return "Not connected to a private Wi-Fi network"
      case .localNetworkDenied: return "Local Network access is off for this app"
      }
    }
  }

  /// One sweep at a time, and a short pause after one (as the Android plugin).
  private static var running = false
  private static var lastEnd: Date?
  private static let cooldown: TimeInterval = 2

  private let queue = DispatchQueue(label: "community_flutter_wifi.sweep")
  private var cancelled = false
  private var connections: [NWConnection] = []
  private var browsers: [NWBrowser] = []

  let onEvent: ([String: Any?]) -> Void
  let onError: (Failure) -> Void

  init(onEvent: @escaping ([String: Any?]) -> Void, onError: @escaping (Failure) -> Void) {
    self.onEvent = onEvent
    self.onError = onError
  }

  /// Starts the sweep (main thread). Events and the error are delivered on the main thread; nothing is delivered after
  /// [cancel].
  func start(maxHosts: Int) {
    if Self.running || (Self.lastEnd.map { Date().timeIntervalSince($0) < Self.cooldown } ?? false) {
      onError(.busy)
      return
    }
    Self.running = true
    let monitor = NWPathMonitor()
    monitor.pathUpdateHandler = { [weak self] path in
      monitor.cancel()
      let info = WifiNetworkInfo.read(path)
      DispatchQueue.main.async { self?.begin(info: info, maxHosts: maxHosts) }
    }
    monitor.start(queue: queue)
  }

  func cancel() {
    queue.async {
      self.cancelled = true
      self.connections.forEach { $0.cancel() }
      self.browsers.forEach { $0.cancel() }
      self.connections = []
      self.browsers = []
    }
    finish()
  }

  private func finish() {
    DispatchQueue.main.async {
      if Self.running { Self.lastEnd = Date() }
      Self.running = false
    }
  }

  private func deliver(_ event: [String: Any?]) {
    DispatchQueue.main.async { [weak self] in
      guard let self = self else { return }
      var stop = false
      self.queue.sync { stop = self.cancelled }
      if !stop { self.onEvent(event) }
    }
  }

  private func fail(_ failure: Failure) {
    DispatchQueue.main.async { [weak self] in
      guard let self = self else { return }
      var stop = false
      self.queue.sync { stop = self.cancelled }
      self.finish()
      if !stop { self.onError(failure) }
    }
  }

  private func begin(info: [String: Any?], maxHosts: Int) {
    var stop = false
    queue.sync { stop = cancelled }
    if stop { return }  // left the page before the path was read: nothing is started
    let wifi = info["wifi"] as? [String: Any?]
    guard let ipv4 = wifi?["ipv4"] as? String, let prefix = wifi?["prefixLength"] as? Int,
          let targets = Self.targets(ipv4: ipv4, prefixLength: prefix, maxHosts: min(maxHosts, Self.maxHosts)) else {
      fail(.notOnWifi)
      return
    }
    let gateway = wifi?["gateway"] as? String
    let partial = Self.isPartial(prefixLength: prefix, maxHosts: min(maxHosts, Self.maxHosts))
    let effective = Self.effectivePrefix(prefixLength: prefix, maxHosts: min(maxHosts, Self.maxHosts))
    let preflightHost = gateway ?? targets.first
    preflight(host: preflightHost) { [weak self] denied in
      guard let self = self else { return }
      if denied { self.fail(.localNetworkDenied); return }
      self.queue.async {
        // Cancelled while the Local Network prompt was up: start nothing (no probe, no Bonjour browser).
        guard !self.cancelled else { return }
        self.sweep(targets: targets, partial: partial, prefix: effective)
      }
    }
  }

  // MARK: - Local Network permission

  /// Waits for the Local Network decision before the sweep counts anything: one connection to the router. While the
  /// system prompt is up the app is inactive and the connection waits with `localNetworkDenied`; once the user decides
  /// it either goes through (or is refused by the router - also an answer) or stays denied with the app active again.
  /// No answer at all within a few seconds (a router that drops the port) is not a denial: the sweep goes on.
  private func preflight(host: String?, done: @escaping (Bool) -> Void) {
    guard let host = host, let port = NWEndpoint.Port(rawValue: 80) else { done(false); return }
    let connection = NWConnection(host: NWEndpoint.Host(host), port: port, using: Self.parameters())
    var finished = false
    var deniedSince: Date?
    let started = Date()
    func settle(_ denied: Bool) {
      if finished { return }
      finished = true
      connection.cancel()
      DispatchQueue.main.async { done(denied) }
    }
    connection.stateUpdateHandler = { [weak connection] state in
      switch state {
      case .ready:
        settle(false)
      case .waiting(let error), .failed(let error):
        if Self.isDenied(error, connection?.currentPath) {
          if deniedSince == nil { deniedSince = Date() }
        } else {
          settle(false)  // refused / unreachable: the network answered, so access is allowed
        }
      default:
        break
      }
    }
    queue.async { self.connections.append(connection) }
    connection.start(queue: queue)
    func tick() {
      queue.asyncAfter(deadline: .now() + 0.5) { [weak self] in
        guard let self = self, !finished else { return }
        if self.cancelled { settle(false); return }
        DispatchQueue.main.async {
          let active = UIApplication.shared.applicationState == .active
          self.queue.async {
            if finished { return }
            if let since = deniedSince, active, Date().timeIntervalSince(since) > 1.5 {
              settle(true)
            } else if deniedSince == nil, Date().timeIntervalSince(started) > 4 {
              settle(false)
            } else if Date().timeIntervalSince(started) > 120 {
              settle(deniedSince != nil)
            } else {
              tick()
            }
          }
        }
      }
    }
    tick()
  }

  static func isDenied(_ error: NWError, _ path: NWPath?) -> Bool {
    if path?.unsatisfiedReason == .localNetworkDenied { return true }
    if case .dns(let code) = error, code == -65570 { return true }  // kDNSServiceErr_PolicyDenied
    return false
  }

  /// TCP over IPv4. The destinations are the Wi-Fi subnet's own addresses, so the route is the Wi-Fi interface.
  static func parameters() -> NWParameters {
    let params = NWParameters.tcp
    if let ip = params.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options { ip.version = .v4 }
    return params
  }

  // MARK: - Sweep

  /// Runs on [queue], never after [cancel].
  private func sweep(targets: [String], partial: Bool, prefix: Int) {
    let total = targets.count
    let window = Set(targets)
    var next = 0
    var doneCount = 0
    var answered: [Int: String] = [:]
    var deniedProbes = 0
    deliver(Self.progressEvent(done: 0, total: total, answered: 0, host: nil))
    let bonjour = BonjourNames(queue: queue, types: Self.bonjourTypes) { [weak self] b in
      // A browser registered after a cancel is stopped at once (cancel() already cleared the list).
      guard let self = self, !self.cancelled else { b.cancel(); return }
      self.browsers.append(b)
    }
    bonjour.start()

    func launch() {
      while next < targets.count, (next - doneCount) < Self.concurrentHosts, !cancelled {
        let index = next
        let host = targets[index]
        next += 1
        probe(host) { [weak self] alive, denied in
          guard let self = self, !self.cancelled else { return }
          doneCount += 1
          if denied { deniedProbes += 1 }
          if alive { answered[index] = host }
          self.deliver(Self.progressEvent(done: doneCount, total: total, answered: answered.count, host: alive ? host : nil))
          if doneCount == total {
            self.complete(answered: answered, deniedProbes: deniedProbes, total: total, window: window, bonjour: bonjour,
                          partial: partial, prefix: prefix)
          } else {
            launch()
          }
        }
      }
    }
    if total == 0 {
      complete(answered: [:], deniedProbes: 0, total: 0, window: window, bonjour: bonjour, partial: partial, prefix: prefix)
    } else {
      launch()
    }
  }

  /// One host: TCP to every port at once; the first accept or refusal is an answer. Called back on [queue].
  private func probe(_ host: String, done: @escaping (_ alive: Bool, _ denied: Bool) -> Void) {
    var settled = false
    var open: [NWConnection] = []
    var denied = false
    func settle(_ alive: Bool) {
      if settled { return }
      settled = true
      open.forEach { $0.cancel() }
      // A settled probe's connections are done: the list holds only the ones still in flight.
      connections.removeAll { c in open.contains { $0 === c } }
      done(alive, denied && !alive)
    }
    for p in Self.ports {
      guard let port = NWEndpoint.Port(rawValue: p) else { continue }
      let c = NWConnection(host: NWEndpoint.Host(host), port: port, using: Self.parameters())
      open.append(c)
      c.stateUpdateHandler = { [weak c] state in
        switch state {
        case .ready:
          settle(true)
        case .waiting(let error), .failed(let error):
          if case .posix(let code) = error, code == .ECONNREFUSED || code == .ECONNRESET {
            settle(true)
          } else if Self.isDenied(error, c?.currentPath) {
            denied = true
          }
        default:
          break
        }
      }
      c.start(queue: queue)
    }
    connections.append(contentsOf: open)
    queue.asyncAfter(deadline: .now() + Self.probeTimeout) { settle(false) }
  }

  /// [window]: the swept addresses (the subnet window without its network, broadcast and the phone's own address).
  private func complete(answered: [Int: String], deniedProbes: Int, total: Int, window: Set<String>, bonjour: BonjourNames,
                        partial: Bool, prefix: Int) {
    connections.forEach { $0.cancel() }
    connections = []
    // Every probe was blocked by the Local Network setting (it was turned off while the sweep ran).
    if total > 0, deniedProbes == total {
      bonjour.stop()
      fail(.localNetworkDenied)
      return
    }
    let hosts = answered.sorted { $0.key < $1.key }.map { $0.value }
    // Bonjour started with the sweep; give it the rest of its window, then resolve reverse DNS names in parallel.
    let wait = max(0, Self.bonjourWindow - bonjour.elapsed)
    queue.asyncAfter(deadline: .now() + wait) { [weak self] in
      guard let self = self, !self.cancelled else { return }
      bonjour.resolve(timeout: 2) { bonjourNames in
        Self.reverseNames(hosts, timeout: Self.nameTimeout, queue: self.queue) { reverse in
          guard !self.cancelled else { return }
          var devices: [[String: Any?]] = []
          var listed = Set<String>()
          for host in hosts {
            devices.append(Self.device(ip: host, bonjour: bonjourNames[host], reverse: reverse[host]))
            listed.insert(host)
          }
          // A device that announced itself over Bonjour but refused none of our ports also answered - when it is an
          // address of the swept window (never another subnet, never the phone itself).
          for (ip, name) in bonjourNames.sorted(by: { $0.key < $1.key }) where !listed.contains(ip) && window.contains(ip) {
            devices.append(Self.device(ip: ip, bonjour: name, reverse: reverse[ip]))
          }
          self.deliver(Self.doneEvent(done: total, total: total, devices: devices, partial: partial, prefix: prefix))
          self.finish()
          self.queue.async { self.browsers.forEach { $0.cancel() }; self.browsers = [] }
        }
      }
    }
  }

  // MARK: - Maps (the Android shapes)

  static func device(ip: String, bonjour: String?, reverse: String?) -> [String: Any?] {
    let name = (bonjour?.isEmpty == false ? bonjour : nil) ?? (reverse?.isEmpty == false ? reverse : nil) ?? ip
    return [
      "ipAddress": ip,
      "deviceName": name,
      "localHost": false,
      "loopbackAddress": false,
      "hostAddress": ip,
      "canonicalHostName": reverse ?? ip,
      "multicastAddress": false,
      "siteLocalAddress": isPrivate(ip),
    ]
  }

  static func progressEvent(done: Int, total: Int, answered: Int, host: String?) -> [String: Any?] {
    ["type": "progress", "done": done, "total": total, "answered": answered, "host": host]
  }

  static func doneEvent(done: Int, total: Int, devices: [[String: Any?]], partial: Bool, prefix: Int?) -> [String: Any?] {
    ["type": "done", "done": done, "total": total, "answered": devices.count, "devices": devices, "partial": partial,
     "prefix": prefix]
  }

  // MARK: - Targets (WifiMath.sweepTargets on Android)

  static func toInt(_ ip: String) -> UInt32? {
    let parts = ip.split(separator: ".", omittingEmptySubsequences: false)
    guard parts.count == 4 else { return nil }
    var v: UInt32 = 0
    for p in parts {
      guard p.count <= 3, let n = UInt32(p), n <= 255 else { return nil }
      v = (v << 8) | n
    }
    return v
  }

  static func toIp(_ v: UInt32) -> String { "\(v >> 24 & 0xff).\(v >> 16 & 0xff).\(v >> 8 & 0xff).\(v & 0xff)" }

  /// RFC 1918 only: 10/8, 172.16/12, 192.168/16.
  static func isPrivate(_ ip: String) -> Bool {
    guard let v = toInt(ip) else { return false }
    return (v >> 24) == 10 || (v >> 20) == 0xAC1 || (v >> 16) == 0xC0A8
  }

  static func prefixFor(maxHosts: Int) -> Int {
    var bits = 0
    var n = max(maxHosts, 4)
    while n > 1 { n >>= 1; bits += 1 }
    return min(max(32 - bits, 22), 30)
  }

  static func effectivePrefix(prefixLength: Int, maxHosts: Int) -> Int { max(prefixLength, prefixFor(maxHosts: maxHosts)) }

  static func isPartial(prefixLength: Int, maxHosts: Int) -> Bool { prefixLength < prefixFor(maxHosts: maxHosts) }

  /// Every host of the window around [ipv4] except the network, broadcast and own addresses; nil for a public address.
  static func targets(ipv4: String, prefixLength: Int, maxHosts: Int) -> [String]? {
    guard (0...32).contains(prefixLength), isPrivate(ipv4), let own = toInt(ipv4) else { return nil }
    let prefix = effectivePrefix(prefixLength: prefixLength, maxHosts: maxHosts)
    if prefix >= 31 { return [] }
    let mask: UInt32 = ~UInt32(0) << UInt32(32 - prefix)
    let network = own & mask
    let size = UInt32(1) << UInt32(32 - prefix)
    return (1..<(size - 1)).map { network + $0 }.filter { $0 != own }.map(toIp)
  }

  // MARK: - Reverse DNS

  /// getnameinfo(NI_NAMEREQD) per host, all at once, each bounded by [timeout] (a lookup that takes longer is dropped).
  static func reverseNames(_ hosts: [String], timeout: TimeInterval, queue: DispatchQueue,
                           done: @escaping ([String: String]) -> Void) {
    if hosts.isEmpty { done([:]); return }
    var names: [String: String] = [:]
    let lock = NSLock()
    let group = DispatchGroup()
    for host in hosts {
      group.enter()
      DispatchQueue.global(qos: .utility).async {
        var sin = sockaddr_in()
        sin.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        sin.sin_family = sa_family_t(AF_INET)
        inet_pton(AF_INET, host, &sin.sin_addr)
        var buf = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        let rc = withUnsafePointer(to: &sin) {
          $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
            getnameinfo($0, socklen_t(MemoryLayout<sockaddr_in>.size), &buf, socklen_t(buf.count), nil, 0, NI_NAMEREQD)
          }
        }
        if rc == 0 {
          let name = String(cString: buf)
          if !name.isEmpty, name != host {
            lock.lock(); names[host] = name; lock.unlock()
          }
        }
        group.leave()
      }
    }
    var answered = false
    let finish = {
      if answered { return }
      answered = true
      lock.lock(); let copy = names; lock.unlock()
      done(copy)
    }
    group.notify(queue: queue) { finish() }
    queue.asyncAfter(deadline: .now() + timeout) { finish() }
  }
}

/// Bonjour browsing of the fixed service types, and the address of each service found (a short TCP connection to the
/// service's own advertised port, IPv4, cancelled as soon as it is ready).
final class BonjourNames {
  private let queue: DispatchQueue
  private let types: [String]
  private let register: (NWBrowser) -> Void
  private var found: [String: NWEndpoint] = [:]  // service name -> endpoint (first type wins)
  private var browsers: [NWBrowser] = []
  private var startedAt = Date()

  init(queue: DispatchQueue, types: [String], register: @escaping (NWBrowser) -> Void) {
    self.queue = queue
    self.types = types
    self.register = register
  }

  var elapsed: TimeInterval { Date().timeIntervalSince(startedAt) }

  func start() {
    startedAt = Date()
    for type in types {
      let browser = NWBrowser(for: .bonjour(type: type, domain: "local."), using: NWParameters())
      browser.browseResultsChangedHandler = { [weak self] results, _ in
        guard let self = self else { return }
        for r in results {
          if case .service(let name, _, _, _) = r.endpoint, self.found[name] == nil { self.found[name] = r.endpoint }
        }
      }
      browser.start(queue: queue)
      browsers.append(browser)
      register(browser)
    }
  }

  func stop() {
    browsers.forEach { $0.cancel() }
    browsers = []
  }

  /// IPv4 address -> service name, for the services found so far. Called back on the queue.
  func resolve(timeout: TimeInterval, done: @escaping ([String: String]) -> Void) {
    stop()
    let services = Array(found.prefix(32))
    if services.isEmpty { done([:]); return }
    var out: [String: String] = [:]
    var pending = services.count
    var answered = false
    var connections: [NWConnection] = []
    func finish() {
      if answered { return }
      answered = true
      connections.forEach { $0.cancel() }
      done(out)
    }
    for (name, endpoint) in services {
      let params = NWParameters.tcp
      if let ip = params.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options { ip.version = .v4 }
      let c = NWConnection(to: endpoint, using: params)
      connections.append(c)
      var settled = false
      c.stateUpdateHandler = { [weak c] state in
        if settled { return }
        switch state {
        case .ready:
          settled = true
          if case .hostPort(let host, _)? = c?.currentPath?.remoteEndpoint, case .ipv4(let v4) = host {
            let ip = "\(v4)".split(separator: "%").first.map(String.init) ?? "\(v4)"
            if out[ip] == nil { out[ip] = name }
          }
          c?.cancel()
          pending -= 1
          if pending == 0 { finish() }
        case .failed, .cancelled:
          settled = true
          pending -= 1
          if pending == 0 { finish() }
        default:
          break
        }
      }
      c.start(queue: queue)
    }
    queue.asyncAfter(deadline: .now() + timeout) { finish() }
  }
}
