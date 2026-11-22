package com.community.flutter.wifi

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address

/**
 * `getNetworkInfo`: the connection's IPv4 / prefix length / gateway / DNS from LinkProperties, and which
 * transport it is. It is constructed from a ConnectivityManager only - no LocationManager, no Geocoder,
 * no Wi-Fi SSID (which needs location) - so it works with no permission beyond ACCESS_NETWORK_STATE and
 * never labels a cellular address as Wi-Fi ([WifiMath.networkInfo] carries the transport).
 */
internal class NetworkInfoReader(private val cm: ConnectivityManager) {
    @Suppress("DEPRECATION")
    fun read(): Map<String, Any?> {
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val transport = if (caps == null) "none" else WifiMath.transport(
            vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            bluetooth = caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH),
        )
        val wifiNet = if (transport == "wifi") active else wifiNetwork()
        return WifiMath.networkInfo(
            connected = caps != null,
            transport = transport,
            active = active?.let { link(it) },
            wifi = wifiNet?.let { link(it) },
        )
    }

    /** The device's Wi-Fi network even while a VPN is the active one. */
    @Suppress("DEPRECATION")
    private fun wifiNetwork(): Network? = cm.allNetworks.firstOrNull {
        val c = cm.getNetworkCapabilities(it)
        c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun link(n: Network): Map<String, Any?>? {
        val lp: LinkProperties = cm.getLinkProperties(n) ?: return null
        val v4 = lp.linkAddresses.firstOrNull { it.address is Inet4Address }
        val gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        val dns = lp.dnsServers.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
        return WifiMath.linkInfo(
            interfaceName = lp.interfaceName,
            ipv4 = v4?.address?.hostAddress,
            prefixLength = v4?.prefixLength,
            gateway = gateway,
            dns = dns,
            mtu = if (Build.VERSION.SDK_INT >= 29) lp.mtu.takeIf { it > 0 } else null,
        )
    }
}
