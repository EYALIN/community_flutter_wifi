package com.community.flutter.wifi

import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * The plugin's pure rules: frequency -> channel / band, channel-width labels, the free-space distance,
 * and the result maps built from plain values. Nothing here touches the Android framework, so JVM unit
 * tests pin every key, type and unit (`rssi` is dBm; `signalstrength` is the 0-4 level; `linkspeed`
 * carries "Mbps"). The plugin only reads the framework and hands the numbers to these functions.
 */
internal object WifiMath {
    // android.net.wifi.ScanResult.CHANNEL_WIDTH_* (inlined constants; 320 MHz is API 33).
    const val WIDTH_20 = 0
    const val WIDTH_40 = 1
    const val WIDTH_80 = 2
    const val WIDTH_160 = 3
    const val WIDTH_80_PLUS_80 = 4
    const val WIDTH_320 = 5

    /** Channel number of a centre frequency in MHz (2.4, 5, 5.9 and 6 GHz), -1 when it is none. */
    fun channel(f: Int): Int = when (f) {
        2484 -> 14
        in 2412..2472 -> (f - 2412) / 5 + 1
        in 5170..5895 -> (f - 5000) / 5 // 5 GHz incl. the 5.9 GHz U-NII-4 channels 169..177
        5935 -> 2 // 6 GHz channel 2 is the odd one out (5935, not 5945)
        in 5955..7115 -> (f - 5950) / 5
        else -> -1
    }

    fun channelWidth(code: Int): String = when (code) {
        WIDTH_20 -> "20 MHz"
        WIDTH_40 -> "40 MHz"
        WIDTH_80 -> "80 MHz"
        WIDTH_160 -> "160 MHz"
        WIDTH_80_PLUS_80 -> "80+80 MHz"
        WIDTH_320 -> "320 MHz"
        else -> "Unknown"
    }

    fun security(caps: String): String = when {
        caps.contains("WEP") -> "WEP"
        caps.contains("WPA3") || caps.contains("SAE") -> "WPA3"
        caps.contains("WPA2") -> "WPA2"
        caps.contains("WPA") -> "WPA"
        caps.contains("EAP") -> "EAP"
        else -> "Open"
    }

    /** What one scanFresh call amounts to. */
    data class ScanOutcome(val requested: Boolean, val fresh: Boolean, val throttled: Boolean, val timedOut: Boolean)

    /**
     * The scanFresh decision, from what the system did. [requested] = startScan() accepted the request;
     * [gotBroadcast] = SCAN_RESULTS_AVAILABLE arrived before the timeout; [updatedExtra] = its EXTRA_RESULTS_UPDATED.
     * - not requested (Android's 4-scans-per-2-minutes limit, or Wi-Fi scanning off): throttled, the cached list;
     * - requested, broadcast with updated results: fresh;
     * - requested, broadcast without an update (the system refused the scan it had accepted): throttled;
     * - requested, no broadcast in time: timed out (not throttled - nobody said no), the cached list.
     */
    fun scanOutcome(requested: Boolean, gotBroadcast: Boolean, updatedExtra: Boolean): ScanOutcome {
        val timedOut = requested && !gotBroadcast
        val fresh = requested && gotBroadcast && updatedExtra
        return ScanOutcome(requested = requested, fresh = fresh, throttled = !requested || (!timedOut && !fresh), timedOut = timedOut)
    }

    /**
     * The message of a failure the plugin did not raise itself (a framework / JDK exception). Its text is free
     * text the plugin does not control - "failed to join MyHome", "Unable to resolve host \"printer.local\"" - and it
     * could reach the app's error dialog and crash reports, so only the exception's class name crosses the channel.
     */
    fun platformErrorMessage(e: Throwable): String = e.javaClass.simpleName.ifEmpty { "Exception" }

    /**
     * Every constant text the plugin puts in an error itself (WifiError, result.error / sink.error literals). The
     * test fixture fixtures/wifi/error_messages.json pins them, so a Dart caller can tell them apart from free text.
     */
    val ownErrorMessages = listOf(
        "No network name",
        "Wi-Fi is disabled",
        "Location permission is required",
        "Not connected to a network",
        "A scan is running or was just done; try again in a few seconds",
        "Not connected to a private Wi-Fi network",
    )

    /**
     * The network asks for a credential: WEP, WPA / WPA2 / PSK, WPA3 Personal (SAE, also SAE-only
     * "[RSN-SAE-CCMP]" with no WPA token) and Enterprise (EAP, "[RSN-EAP-CCMP]"). OWE (Enhanced Open, "[RSN-OWE-CCMP]")
     * encrypts without a password, so it stays false like an open network.
     */
    fun hasPassword(caps: String): Boolean =
        listOf("WEP", "WPA", "PSK", "SAE", "EAP").any { caps.contains(it) }

    /** Free-space path loss estimate of the distance to the access point, in metres. */
    fun distance(freqMhz: Int, level: Int): Double =
        10.0.pow((27.55 - 20 * log10(freqMhz.toDouble()) + abs(level)) / 20.0)

    /** DhcpInfo's little-endian int -> dotted quad. */
    fun ip(v: Int): String = "${v and 0xff}.${v shr 8 and 0xff}.${v shr 16 and 0xff}.${v shr 24 and 0xff}"

    /** "255.255.255.0" for /24; null for a prefix outside 0..32. */
    fun subnetMask(prefix: Int): String? {
        if (prefix !in 0..32) return null
        val mask = if (prefix == 0) 0L else (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        return "${mask shr 24 and 0xff}.${mask shr 16 and 0xff}.${mask shr 8 and 0xff}.${mask and 0xff}"
    }

    fun signalQuality(level: Int): String = when {
        level >= 4 -> "excellent"
        level >= 3 -> "good"
        level >= 2 -> "fair"
        else -> "poor"
    }

    /** Which passphrase API a join uses: "open", "wpa3" (SAE) or "wpa2" (PSK, also WPA2/WPA3 transition mode). */
    fun suggestionAuth(security: String): String {
        val u = security.uppercase(Locale.ROOT)
        return when {
            u.isEmpty() || u == "NONE" || u == "NOPASS" -> "open"
            (u.contains("SAE") || u == "WPA3") && !u.contains("PSK") && !u.contains("WPA2") -> "wpa3"
            else -> "wpa2"
        }
    }

    fun locationPermissionState(fine: Boolean, coarse: Boolean): String = when {
        fine -> "fine"
        coarse -> "approximate"
        else -> "denied"
    }

    /** Interfaces a phone creates while it shares its connection: hotspot (ap0, swlan0), USB (rndis0), Bluetooth (bt-pan). */
    fun hasTetherInterface(upInterfaceNames: List<String>): Boolean =
        upInterfaceNames.any { Regex("^(ap\\d+|swlan\\d+|softap\\d*|rndis\\d+|bt-pan|ncm\\d+)$").matches(it) }

    /**
     * Whether the system Location toggle is on. [platformFlag] = LocationManager.isLocationEnabled (API 28+),
     * [legacyMode] = Settings.Secure LOCATION_MODE (older; 0 = off). A failed read is "on": it must not send the
     * user to turn on a setting that may already be on.
     */
    fun locationEnabled(sdkInt: Int, platformFlag: () -> Boolean, legacyMode: () -> Int): Boolean = try {
        if (sdkInt >= 28) platformFlag() else legacyMode() != 0
    } catch (e: Exception) {
        true
    }

    // ---- result maps -------------------------------------------------------------------------

    fun scanEntry(ssid: String?, bssid: String?, caps: String, freqMhz: Int, level: Int, widthCode: Int): Map<String, Any?> =
        mapOf(
            "SSID" to ssid,
            "BSSID" to bssid,
            "capabilities" to caps,
            "frequency" to "${freqMhz / 1000.0} GHz",
            "security" to security(caps),
            "level" to level, // dBm
            "channelWidth" to channelWidth(widthCode),
            "distance" to String.format(Locale.US, "%.2f", distance(freqMhz, level)),
            "hasPassword" to hasPassword(caps),
        )

    /** [level] is the 0-4 bar level; [rssi] is dBm. */
    fun allWifiDetails(
        enabled: Boolean, ssid: String?, bssid: String?, ip: String?, mac: String?, networkId: Int,
        linkSpeed: Int, level: Int, gateway: String?, rssi: Int, freqMhz: Int, dns1: String?, dns2: String?,
    ): Map<String, Any?> = mapOf(
        "iswifienabled" to enabled,
        "issupportwifi" to true,
        "ssid" to ssid,
        "bssid" to bssid,
        "ip" to ip,
        "mac" to mac,
        "networkid" to networkId,
        "linkspeed" to "$linkSpeed Mbps",
        "signalstrength" to level,
        "gateway" to gateway,
        "rssi" to rssi,
        "speed" to "$linkSpeed Mbps",
        "frequency" to "$freqMhz MHz",
        "channel" to channel(freqMhz),
        "dns1" to dns1,
        "dns2" to dns2,
    )

    /**
     * One answering host of `getConnectedDevices` / the `connected_devices` EventChannel. All eight keys, in this order;
     * the plugin reads java.net.InetAddress and hands the values over.
     */
    fun deviceEntry(
        ipAddress: String?, deviceName: String, localHost: Boolean, loopback: Boolean, hostAddress: String?,
        canonicalHostName: String, multicast: Boolean, siteLocal: Boolean,
    ): Map<String, Any?> = mapOf(
        "ipAddress" to ipAddress,
        "deviceName" to deviceName,
        "localHost" to localHost,
        "loopbackAddress" to loopback,
        "hostAddress" to hostAddress,
        "canonicalHostName" to canonicalHostName,
        "multicastAddress" to multicast,
        "siteLocalAddress" to siteLocal,
    )

    /** The `getIpInfo` location fields before (or without) a fix: -1.0 / "UNKNOWN", never absent. */
    fun unknownLocation(): MutableMap<String, Any?> = mutableMapOf(
        "latitude" to -1.0, "longitude" to -1.0, "city" to "UNKNOWN", "street" to "UNKNOWN",
        "country" to "UNKNOWN", "region" to "UNKNOWN", "zipcode" to "UNKNOWN", "state" to "UNKNOWN",
    )

    /** The `getIpInfo` connection fields (the location fields are added by the old method only). */
    fun ipInfoConnection(
        type: String?, rssi: Int, linkSpeed: Int, ssid: String?, ip: String?, mac: String?, networkId: Int,
        freqMhz: Int, bssid: String?, timezone: String, dns1: String?, dns2: String?,
    ): MutableMap<String, Any?> = mutableMapOf(
        "type" to (type ?: "Not Connected"),
        "signal" to rssi,
        "speed" to linkSpeed,
        "ssid" to (ssid ?: "UNKNOWN"),
        "internalip" to (ip ?: "UNKNOWN"),
        "macaddress" to (mac ?: "UNKNOWN"),
        "networkid" to networkId,
        "frequency" to freqMhz,
        "bssid" to (bssid ?: "UNKNOWN"),
        "timezone" to timezone,
        "dns1" to (dns1 ?: "UNKNOWN"),
        "dns2" to (dns2 ?: "UNKNOWN"),
    )

    /**
     * `scanFresh`. [fresh] = this call's scan completed and updated the results; [throttled] = Android
     * refused the scan (startScan false) or completed it without updating (EXTRA_RESULTS_UPDATED false),
     * so [results] are cached; [timedOut] = no broadcast within the timeout; [ageMs] = age of the newest
     * result (null with no results); [scanTimestampMs] = that moment as epoch milliseconds.
     */
    fun scanFresh(
        results: List<Map<String, Any?>>, requested: Boolean, fresh: Boolean, throttled: Boolean,
        timedOut: Boolean, ageMs: Long?, nowEpochMs: Long,
    ): Map<String, Any?> = mapOf(
        "results" to results,
        "count" to results.size,
        "requested" to requested,
        "fresh" to fresh,
        "throttled" to throttled,
        "timedOut" to timedOut,
        "ageMs" to ageMs,
        "scanTimestampMs" to ageMs?.let { nowEpochMs - it },
    )

    /** Newest result's age: [nowMs] and the results' timestamps are on the same (boot) clock, in ms. */
    fun ageMs(nowMs: Long, newestTimestampMs: Long?): Long? = newestTimestampMs?.let { maxOf(0L, nowMs - it) }

    /** The transport label of a network; a VPN wins (its capabilities also list the transport below it). */
    fun transport(vpn: Boolean, wifi: Boolean, cellular: Boolean, ethernet: Boolean, bluetooth: Boolean = false): String = when {
        vpn -> "vpn"
        wifi -> "wifi"
        cellular -> "cellular"
        ethernet -> "ethernet"
        bluetooth -> "bluetooth"
        else -> "other"
    }

    /** One network's link facts from LinkProperties (no location involved). */
    fun linkInfo(
        interfaceName: String?, ipv4: String?, prefixLength: Int?, gateway: String?, dns: List<String>, mtu: Int?,
    ): Map<String, Any?> = mapOf(
        "interfaceName" to interfaceName,
        "ipv4" to ipv4,
        "prefixLength" to prefixLength,
        "subnetMask" to prefixLength?.let { subnetMask(it) },
        "gateway" to gateway,
        "dnsServers" to dns,
        "dns1" to dns.getOrNull(0),
        "dns2" to dns.getOrNull(1),
        "mtu" to mtu,
    )

    /**
     * `getNetworkInfo`. The top-level link fields describe the ACTIVE network and [transport] says what it
     * is, so a cellular address is never presented as Wi-Fi; [wifi] is the Wi-Fi network's own link facts
     * (also while a VPN is the active network) or null when the device has no Wi-Fi network.
     */
    fun networkInfo(connected: Boolean, transport: String, active: Map<String, Any?>?, wifi: Map<String, Any?>?): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>(
            "connected" to connected,
            "transport" to transport,
            "isWifi" to (transport == "wifi"),
            "wifi" to wifi,
        )
        out.putAll(active ?: linkInfo(null, null, null, null, emptyList(), null))
        return out
    }

    // ---- LAN sweep with real progress (connectedDevicesProgress) ------------------------------------

    /** Largest sweep window: a /22 = 1024 addresses. A bigger network is swept in the /22 around the device. */
    const val SWEEP_MAX_PREFIX = 22

    private fun ipv4ToLong(ip: String): Long? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        var out = 0L
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            if (n !in 0..255 || p.length > 3) return null
            out = (out shl 8) or n.toLong()
        }
        return out
    }

    private fun longToIpv4(v: Long): String = "${v shr 24 and 0xff}.${v shr 16 and 0xff}.${v shr 8 and 0xff}.${v and 0xff}"

    /** RFC 1918 only: 10/8, 172.16/12, 192.168/16. A public, CGNAT or link-local address is never swept. */
    fun isPrivateIpv4(ip: String): Boolean {
        val v = ipv4ToLong(ip) ?: return false
        return (v shr 24) == 10L || (v shr 20) == 0xAC1L || (v shr 16) == 0xC0A8L
    }

    /** The prefix that bounds a sweep of at most [maxHosts] addresses, between /22 (1024) and /30. */
    fun sweepPrefixFor(maxHosts: Int): Int {
        var bits = 0
        var n = maxOf(maxHosts, 4)
        while (n > 1) { n = n shr 1; bits++ }
        return (32 - bits).coerceIn(SWEEP_MAX_PREFIX, 30)
    }

    /**
     * The addresses to probe: every host of the device's own subnet ([prefixLength] from LinkProperties), bounded
     * to a window of at most [maxHosts] addresses around the device, without the network and broadcast addresses
     * and without the device itself. Null when the address is not a private IPv4 or the prefix is not 0..32 -
     * nothing is probed then. A /31 or /32 has no other host: empty.
     */
    fun sweepTargets(ipv4: String, prefixLength: Int, maxHosts: Int = 1024): List<String>? {
        if (prefixLength !in 0..32 || !isPrivateIpv4(ipv4)) return null
        val own = ipv4ToLong(ipv4) ?: return null
        val prefix = maxOf(prefixLength, sweepPrefixFor(maxHosts))
        if (prefix >= 31) return emptyList()
        val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        val network = own and mask
        val size = 1L shl (32 - prefix)
        return (1 until size - 1).map { network + it }.filter { it != own }.map { longToIpv4(it) }
    }

    /** The prefix of the swept window: the connection's own prefix, or the /22 (at most [maxHosts]) around the device. */
    fun sweepEffectivePrefix(prefixLength: Int, maxHosts: Int = 1024): Int = maxOf(prefixLength, sweepPrefixFor(maxHosts))

    /** The network is larger than the window: only a part of it is swept. */
    fun sweepIsPartial(prefixLength: Int, maxHosts: Int = 1024): Boolean = prefixLength < sweepPrefixFor(maxHosts)

    /** `answered` = probes that got an answer so far; carried on EVERY event, so a throttled event never loses one. */
    fun progressEvent(done: Int, total: Int, answered: Int, host: String?): Map<String, Any?> =
        mapOf("type" to "progress", "done" to done, "total" to total, "answered" to answered, "host" to host)

    fun doneEvent(
        done: Int, total: Int, devices: List<Map<String, Any?>>, partial: Boolean = false, prefix: Int? = null,
    ): Map<String, Any?> = mapOf(
        "type" to "done", "done" to done, "total" to total, "answered" to devices.size, "devices" to devices,
        "partial" to partial, "prefix" to prefix,
    )

    /** What one finished probe reports. */
    data class Update(val done: Int, val answered: Int, val host: String?)

    /** Counts finished probes (name lookups included: the caller counts a probe only when it is completely done). */
    class SweepProgress(val total: Int) {
        private var done = 0
        private var answered = 0
        private var lastEmitted = 0
        private val step = maxOf(1, total / 100)

        /**
         * One probe finished. Returns what to report - in the order the probes finished, so `done` only grows -
         * or null to stay quiet: throttled to about 100 events, but ALWAYS reported when the probe got an answer
         * ([host] set) and for the last probe. Thread-safe: the pool's workers call it concurrently.
         */
        @Synchronized
        fun complete(host: String? = null): Update? {
            if (done >= total) return null
            done++
            if (host != null) answered++
            if (host != null || done == total || done - lastEmitted >= step) {
                lastEmitted = done
                return Update(done, answered, host)
            }
            return null
        }

        @Synchronized
        fun doneCount(): Int = done

        @Synchronized
        fun answeredCount(): Int = answered
    }

    /**
     * One sweep at a time, each with its OWN cancel token: a listen the gate refuses changes nothing, and cancelling
     * a sweep can never be undone by a later listen.
     */
    class SweepControl(private val gate: SweepGate = SweepGate()) {
        private var active: java.util.concurrent.atomic.AtomicBoolean? = null

        /** A fresh token when the gate accepts, null (state untouched) when it refuses. */
        @Synchronized
        fun listen(nowMs: Long): java.util.concurrent.atomic.AtomicBoolean? {
            if (!gate.tryStart(nowMs)) return null
            return java.util.concurrent.atomic.AtomicBoolean(false).also { active = it }
        }

        /** The listener went away: stops the accepted sweep (if any). */
        @Synchronized
        fun cancel() {
            active?.set(true)
        }

        @Synchronized
        fun finish(nowMs: Long) {
            gate.finish(nowMs)
        }
    }

    /** One sweep at a time and a pause between two: a tap storm cannot flood the network. */
    class SweepGate(private val minIntervalMs: Long = 3_000L) {
        private var running = false
        private var lastEndMs = Long.MIN_VALUE / 2

        @Synchronized
        fun tryStart(nowMs: Long): Boolean {
            if (running || nowMs - lastEndMs < minIntervalMs) return false
            running = true
            return true
        }

        @Synchronized
        fun finish(nowMs: Long) {
            running = false
            lastEndMs = nowMs
        }
    }
}
