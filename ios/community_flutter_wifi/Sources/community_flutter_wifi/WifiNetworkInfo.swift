import Foundation
import Network

#if canImport(community_flutter_wifi_c)
import community_flutter_wifi_c
#endif

/// `getNetworkInfo` on iOS, in the Android map shape (`WifiMath.networkInfo` / `linkInfo`): `connected`, `transport`,
/// `isWifi`, the active network's `interfaceName`, `ipv4`, `prefixLength`, `subnetMask`, `gateway`, `dnsServers`, `dns1`,
/// `dns2`, `mtu`, and `wifi` (the Wi-Fi interface's own link map, also under a VPN, or null). iOS adds `ipv6` (the
/// interface's IPv6 addresses, a list of strings) to each link map - Android has no such key.
///
/// The sources are public APIs only and none needs a permission or location: NWPathMonitor (transport), getifaddrs
/// (addresses, netmask, MTU), the routing table (default gateway, see cfw_net.c) and libresolv (DNS servers).
enum WifiNetworkInfo {
  /// One interface's addresses from getifaddrs.
  struct Interface {
    var name: String
    var index: UInt32
    var ipv4: String?
    var netmask: String?
    var prefixLength: Int?
    var ipv6: [String] = []
    var mtu: Int?
  }

  static func read(_ path: NWPath) -> [String: Any?] {
    let interfaces = Self.interfaces()
    let wifiName = path.availableInterfaces.first { $0.type == .wifi }?.name
    let primary = path.availableInterfaces.first
    let transport = Self.transport(path)
    let dns = Self.dnsServers()
    let wifi: [String: Any?]? = wifiName.flatMap { name in
      guard let iface = interfaces[name], iface.ipv4 != nil else { return nil }
      return link(iface, dns: dns)
    }
    var active: [String: Any?]
    if let name = primary?.name, let iface = interfaces[name], path.status == .satisfied {
      active = link(iface, dns: dns)
    } else {
      active = link(nil, dns: [])
    }
    // A VPN's tunnel has no gateway of its own on the routing table we read; its link facts are still its own.
    var out: [String: Any?] = [
      "connected": path.status == .satisfied,
      "transport": transport,
      "isWifi": transport == "wifi",
      "wifi": wifi,
    ]
    for (k, v) in active { out[k] = v }
    return out
  }

  /// 'vpn' | 'wifi' | 'cellular' | 'ethernet' | 'other' | 'none' (the Android labels; a VPN wins).
  static func transport(_ path: NWPath) -> String {
    guard path.status == .satisfied, let first = path.availableInterfaces.first else { return "none" }
    if first.type == .other, Self.isTunnel(first.name) { return "vpn" }
    switch first.type {
    case .wifi: return "wifi"
    case .cellular: return "cellular"
    case .wiredEthernet: return "ethernet"
    default: return "other"
    }
  }

  static func isTunnel(_ name: String) -> Bool {
    ["utun", "ipsec", "ppp", "tun", "tap"].contains { name.hasPrefix($0) }
  }

  static func link(_ iface: Interface?, dns: [String]) -> [String: Any?] {
    var gateway: String?
    if let iface = iface, iface.ipv4 != nil { gateway = Self.gateway(ifindex: iface.index) }
    return [
      "interfaceName": iface?.name,
      "ipv4": iface?.ipv4,
      "prefixLength": iface?.prefixLength,
      "subnetMask": iface?.netmask,
      "gateway": gateway,
      "dnsServers": dns,
      "dns1": dns.first,
      "dns2": dns.count > 1 ? dns[1] : nil,
      "mtu": iface?.mtu,
      "ipv6": iface?.ipv6 ?? [],
    ]
  }

  /// Every interface that is up, by name.
  static func interfaces() -> [String: Interface] {
    var out: [String: Interface] = [:]
    var ifaddr: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&ifaddr) == 0 else { return out }
    defer { freeifaddrs(ifaddr) }
    var ptr = ifaddr
    while let cur = ptr {
      defer { ptr = cur.pointee.ifa_next }
      let entry = cur.pointee
      guard (entry.ifa_flags & UInt32(IFF_UP)) != 0, let addr = entry.ifa_addr else { continue }
      let name = String(cString: entry.ifa_name)
      var iface = out[name] ?? Interface(name: name, index: if_nametoindex(entry.ifa_name))
      switch Int32(addr.pointee.sa_family) {
      case AF_INET:
        if iface.ipv4 == nil, let ip = numeric(addr) {
          iface.ipv4 = ip
          if let mask = entry.ifa_netmask, let m = numeric(mask) {
            iface.netmask = m
            iface.prefixLength = prefix(ofMask: m)
          }
        }
      case AF_INET6:
        if let ip = numeric(addr) { iface.ipv6.append(ip) }
      case AF_LINK:
        if let data = entry.ifa_data {
          let mtu = data.assumingMemoryBound(to: if_data.self).pointee.ifi_mtu
          if mtu > 0 { iface.mtu = Int(mtu) }
        }
      default:
        break
      }
      out[name] = iface
    }
    return out
  }

  static func numeric(_ sa: UnsafeMutablePointer<sockaddr>) -> String? {
    var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
    guard getnameinfo(sa, socklen_t(sa.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else {
      return nil
    }
    let s = String(cString: host)
    // A link-local address carries its scope ("fe80::1%en0"): the address alone.
    return s.split(separator: "%").first.map(String.init)
  }

  /// "255.255.255.0" -> 24; nil for a mask that is not contiguous.
  static func prefix(ofMask mask: String) -> Int? {
    let parts = mask.split(separator: ".").compactMap { UInt32($0) }
    guard parts.count == 4, parts.allSatisfy({ $0 <= 255 }) else { return nil }
    let v = parts.reduce(UInt32(0)) { ($0 << 8) | $1 }
    let bits = v.nonzeroBitCount
    let expected: UInt32 = bits == 0 ? 0 : ~UInt32(0) << UInt32(32 - bits)
    return v == expected ? bits : nil
  }

  static func gateway(ifindex: UInt32) -> String? {
    #if canImport(community_flutter_wifi_c)
    var buf = [CChar](repeating: 0, count: 64)
    guard cfw_default_gateway(ifindex, &buf, buf.count) == 1 else { return nil }
    return String(cString: buf)
    #else
    return nil
    #endif
  }

  static func dnsServers() -> [String] {
    #if canImport(community_flutter_wifi_c)
    var buf = [CChar](repeating: 0, count: 1024)
    guard cfw_dns_servers(&buf, buf.count) > 0 else { return [] }
    return String(cString: buf).split(separator: "\n").map(String.init)
    #else
    return []
    #endif
  }
}
