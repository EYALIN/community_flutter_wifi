package com.community.flutter.wifi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pins the plugin's units and result-map shapes (callers read these keys). */
class WifiMathTest {
    // ---- frequency -> channel / band -------------------------------------------------------------

    @Test fun channels24() {
        assertEquals(1, WifiMath.channel(2412))
        assertEquals(6, WifiMath.channel(2437))
        assertEquals(13, WifiMath.channel(2472))
        assertEquals(14, WifiMath.channel(2484))
    }

    @Test fun channels5() {
        assertEquals(34, WifiMath.channel(5170))
        assertEquals(36, WifiMath.channel(5180))
        assertEquals(100, WifiMath.channel(5500))
        assertEquals(165, WifiMath.channel(5825))
    }

    @Test fun channels59() {
        assertEquals(169, WifiMath.channel(5845))
        assertEquals(177, WifiMath.channel(5885))
    }

    @Test fun channels6() {
        assertEquals(2, WifiMath.channel(5935))
        assertEquals(1, WifiMath.channel(5955))
        assertEquals(5, WifiMath.channel(5975))
        assertEquals(93, WifiMath.channel(6415))
        assertEquals(233, WifiMath.channel(7115))
    }

    @Test fun noChannelOutsideTheBands() {
        for (f in listOf(-1, 0, 900, 2400, 4900, 5900, 5925, 7125, 7200)) assertEquals(-1, WifiMath.channel(f), "f=$f")
    }

    // ---- width -----------------------------------------------------------------------------------

    @Test fun widths20To320() {
        assertEquals(
            listOf("20 MHz", "40 MHz", "80 MHz", "160 MHz", "80+80 MHz", "320 MHz"),
            (0..5).map { WifiMath.channelWidth(it) },
        )
        assertEquals("Unknown", WifiMath.channelWidth(6))
        assertEquals("Unknown", WifiMath.channelWidth(-1))
    }

    @Test fun securityLabels() {
        assertEquals("WEP", WifiMath.security("[WEP]"))
        assertEquals("WPA3", WifiMath.security("[RSN-SAE-CCMP]"))
        assertEquals("WPA2", WifiMath.security("[WPA2-PSK-CCMP][ESS]"))
        assertEquals("WPA", WifiMath.security("[WPA-PSK-TKIP]"))
        assertEquals("EAP", WifiMath.security("[RSN-EAP-CCMP]"))
        assertEquals("Open", WifiMath.security("[ESS]"))
    }

    @Test fun suggestionAuth() {
        assertEquals("open", WifiMath.suggestionAuth("NONE"))
        assertEquals("open", WifiMath.suggestionAuth("nopass"))
        assertEquals("open", WifiMath.suggestionAuth(""))
        assertEquals("wpa2", WifiMath.suggestionAuth("WPA"))
        assertEquals("wpa2", WifiMath.suggestionAuth("WPA2"))
        assertEquals("wpa2", WifiMath.suggestionAuth("[WPA2-PSK-CCMP][RSN-SAE-CCMP]")) // transition mode
        assertEquals("wpa3", WifiMath.suggestionAuth("WPA3"))
        assertEquals("wpa3", WifiMath.suggestionAuth("SAE"))
        assertEquals("wpa3", WifiMath.suggestionAuth("[RSN-SAE-CCMP]"))
    }

    @Test fun locationEnabledDecision() {
        // API 28+: the platform flag decides.
        assertTrue(WifiMath.locationEnabled(34, { true }, { error("unused") }))
        assertFalse(WifiMath.locationEnabled(34, { false }, { error("unused") }))
        assertTrue(WifiMath.locationEnabled(28, { true }, { error("unused") }))
        // Older: LOCATION_MODE 0 = off.
        assertFalse(WifiMath.locationEnabled(27, { error("unused") }, { 0 }))
        assertTrue(WifiMath.locationEnabled(27, { error("unused") }, { 3 }))
        assertTrue(WifiMath.locationEnabled(24, { error("unused") }, { 1 }))
        // A failed read is "on".
        assertTrue(WifiMath.locationEnabled(34, { throw SecurityException("x") }, { 0 }))
        assertTrue(WifiMath.locationEnabled(24, { true }, { throw IllegalStateException("x") }))
    }

    @Test fun locationPermissionStates() {
        assertEquals("fine", WifiMath.locationPermissionState(fine = true, coarse = true))
        assertEquals("fine", WifiMath.locationPermissionState(fine = true, coarse = false))
        assertEquals("approximate", WifiMath.locationPermissionState(fine = false, coarse = true))
        assertEquals("denied", WifiMath.locationPermissionState(fine = false, coarse = false))
    }

    @Test fun tetherInterfaces() {
        assertTrue(WifiMath.hasTetherInterface(listOf("lo", "wlan0", "ap0")))
        assertTrue(WifiMath.hasTetherInterface(listOf("swlan0")))
        assertTrue(WifiMath.hasTetherInterface(listOf("rndis0")))
        assertFalse(WifiMath.hasTetherInterface(listOf("lo", "wlan0", "rmnet_data1", "dummy0")))
        assertFalse(WifiMath.hasTetherInterface(emptyList()))
    }

    @Test fun ipAndMask() {
        assertEquals("192.168.1.1", WifiMath.ip(0x0101A8C0))
        assertEquals("255.255.255.0", WifiMath.subnetMask(24))
        assertEquals("255.255.240.0", WifiMath.subnetMask(20))
        assertEquals("255.255.255.255", WifiMath.subnetMask(32))
        assertEquals("0.0.0.0", WifiMath.subnetMask(0))
        assertNull(WifiMath.subnetMask(33))
        assertNull(WifiMath.subnetMask(-1))
    }

    // ---- getWifiList entry -------------------------------------------------------------------------

    @Test fun scanEntryKeysAndUnits() {
        val e = WifiMath.scanEntry("home", "aa:bb", "[WPA2-PSK-CCMP][ESS]", 2412, -50, WifiMath.WIDTH_40)
        assertEquals(
            setOf("SSID", "BSSID", "capabilities", "frequency", "security", "level", "channelWidth", "distance", "hasPassword"),
            e.keys,
        )
        assertEquals("2.412 GHz", e["frequency"])
        assertEquals(-50, e["level"]) // dBm
        assertEquals("WPA2", e["security"])
        assertEquals("40 MHz", e["channelWidth"])
        assertEquals(true, e["hasPassword"])
        assertTrue((e["distance"] as String).matches(Regex("""\d+\.\d\d""")))
        assertEquals(false, WifiMath.scanEntry("o", "b", "[ESS]", 5180, -60, 0)["hasPassword"])
    }

    // Every combination of what the system did -> fresh / throttled / timed out. Inverting any branch of
    // WifiMath.scanOutcome turns a row red.
    @Test fun scanOutcomeTable() {
        data class Row(val requested: Boolean, val got: Boolean, val updated: Boolean, val fresh: Boolean, val throttled: Boolean, val timedOut: Boolean)
        val rows = listOf(
            // startScan() refused: throttled, never fresh, never a timeout (nothing was awaited).
            Row(false, false, false, fresh = false, throttled = true, timedOut = false),
            Row(false, false, true, fresh = false, throttled = true, timedOut = false),
            Row(false, true, false, fresh = false, throttled = true, timedOut = false),
            Row(false, true, true, fresh = false, throttled = true, timedOut = false),
            // accepted, the broadcast says the results were updated: fresh.
            Row(true, true, true, fresh = true, throttled = false, timedOut = false),
            // accepted, the broadcast says nothing new: the system throttled it after all.
            Row(true, true, false, fresh = false, throttled = true, timedOut = false),
            // accepted, no broadcast before the timeout: timed out, not throttled.
            Row(true, false, false, fresh = false, throttled = false, timedOut = true),
            Row(true, false, true, fresh = false, throttled = false, timedOut = true),
        )
        for (r in rows) {
            val o = WifiMath.scanOutcome(r.requested, r.got, r.updated)
            assertEquals(WifiMath.ScanOutcome(r.requested, r.fresh, r.throttled, r.timedOut), o, "$r")
            // exactly one of fresh / throttled / timedOut describes every call
            assertEquals(1, listOf(o.fresh, o.throttled, o.timedOut).count { it }, "$r")
        }
    }

    // A WPA3-only (SAE) or Enterprise (EAP) network has a password even without a WPA / PSK token;
    // OWE (Enhanced Open) does not.
    @Test fun hasPasswordPerSecurityType() {
        val cases = mapOf(
            "[RSN-SAE-CCMP][ESS]" to true,
            "[RSN-SAE-CCMP]" to true,
            "[RSN-EAP-CCMP]" to true,
            "[RSN-EAP/SHA256-CCMP][ESS]" to true,
            "[RSN-OWE-CCMP]" to false,
            "[RSN-OWE-CCMP][ESS]" to false,
            "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]" to true,
            "[WPA-PSK-TKIP][ESS]" to true,
            "[RSN-PSK+SAE-CCMP][ESS]" to true,
            "[WEP][ESS]" to true,
            "[ESS]" to false,
            "" to false,
        )
        for ((caps, want) in cases) {
            assertEquals(want, WifiMath.hasPassword(caps), caps)
            assertEquals(want, WifiMath.scanEntry("n", "b", caps, 2412, -50, 0)["hasPassword"], caps)
        }
    }

    @Test fun distanceGrowsAsTheSignalFalls() {
        assertTrue(WifiMath.distance(2412, -80) > WifiMath.distance(2412, -40))
    }

    // ---- getAllWifiDetails: signalstrength is the 0-4 level, rssi is dBm ------------------------------

    private fun details(level: Int = 3, rssi: Int = -61, speed: Int = 433, freq: Int = 5180) = WifiMath.allWifiDetails(
        true, "home", "aa:bb", "192.168.1.20", "02:00", 7, speed, level, "192.168.1.1", rssi, freq, "8.8.8.8", "8.8.4.4",
    )

    @Test fun allWifiDetailsKeys() {
        assertEquals(
            setOf(
                "iswifienabled", "issupportwifi", "ssid", "bssid", "ip", "mac", "networkid", "linkspeed", "signalstrength",
                "gateway", "rssi", "speed", "frequency", "channel", "dns1", "dns2",
            ),
            details().keys,
        )
    }

    @Test fun signalstrengthIsTheLevelAndRssiIsDbm() {
        val d = details(level = 3, rssi = -61)
        assertEquals(3, d["signalstrength"])
        assertEquals(-61, d["rssi"])
        assertTrue((d["signalstrength"] as Int) in 0..4)
        assertTrue((d["rssi"] as Int) < 0)
    }

    @Test fun linkSpeedAndFrequencyCarryTheirUnits() {
        val d = details(speed = 433, freq = 5180)
        assertEquals("433 Mbps", d["linkspeed"])
        assertEquals("433 Mbps", d["speed"])
        assertEquals("5180 MHz", d["frequency"])
        assertEquals(36, d["channel"])
        assertEquals("-1 Mbps", details(speed = -1)["linkspeed"])
    }

    @Test fun sixGhzChannelIsNoLongerMinusOne() {
        assertEquals(37, details(freq = 6135)["channel"])
    }

    // ---- getIpInfo connection fields ---------------------------------------------------------------

    @Test fun ipInfoConnectionKeysAndDefaults() {
        val e = WifiMath.ipInfoConnection(null, -1, -1, null, null, null, -1, -1, null, "UTC", null, null)
        assertEquals(
            setOf("type", "signal", "speed", "ssid", "internalip", "macaddress", "networkid", "frequency", "bssid", "timezone", "dns1", "dns2"),
            e.keys,
        )
        assertEquals("Not Connected", e["type"])
        assertEquals("UNKNOWN", e["ssid"])
        assertEquals("UNKNOWN", e["dns1"])
        val on = WifiMath.ipInfoConnection("WIFI", -55, 300, "home", "10.0.0.5", "02:00", 3, 2412, "aa", "Asia/Jerusalem", "1.1.1.1", "1.0.0.1")
        assertEquals(-55, on["signal"]) // dBm
        assertEquals(300, on["speed"])
    }

    // ---- scanFresh ---------------------------------------------------------------------------------

    private val one = listOf(WifiMath.scanEntry("home", "aa", "[ESS]", 2412, -50, 0))

    @Test fun scanFreshKeys() {
        val m = WifiMath.scanFresh(one, true, true, false, false, 1200L, 100_000L)
        assertEquals(
            setOf("results", "count", "requested", "fresh", "throttled", "timedOut", "ageMs", "scanTimestampMs"),
            m.keys,
        )
    }

    @Test fun freshResult() {
        val m = WifiMath.scanFresh(one, true, true, false, false, 1200L, 100_000L)
        assertEquals(true, m["fresh"]); assertEquals(false, m["throttled"]); assertEquals(false, m["timedOut"])
        assertEquals(1, m["count"])
        assertEquals(98_800L, m["scanTimestampMs"])
    }

    @Test fun throttledResultReportsItsAge() {
        val m = WifiMath.scanFresh(one, false, false, true, false, 95_000L, 100_000L)
        assertEquals(false, m["fresh"]); assertEquals(true, m["throttled"])
        assertEquals(95_000L, m["ageMs"])
    }

    @Test fun timeoutIsNeitherFreshNorThrottled() {
        val m = WifiMath.scanFresh(one, true, false, false, true, 30_000L, 100_000L)
        assertEquals(true, m["timedOut"]); assertEquals(false, m["fresh"]); assertEquals(false, m["throttled"])
    }

    @Test fun noResultsHasNoAge() {
        val m = WifiMath.scanFresh(emptyList(), true, true, false, false, null, 100_000L)
        assertNull(m["ageMs"]); assertNull(m["scanTimestampMs"]); assertEquals(0, m["count"])
        assertNull(WifiMath.ageMs(5_000L, null))
        assertEquals(1_000L, WifiMath.ageMs(5_000L, 4_000L))
        assertEquals(0L, WifiMath.ageMs(5_000L, 6_000L))
    }

    // ---- getNetworkInfo: a cellular address is never Wi-Fi -----------------------------------------

    @Test fun transportLabels() {
        assertEquals("vpn", WifiMath.transport(vpn = true, wifi = true, cellular = false, ethernet = false))
        assertEquals("wifi", WifiMath.transport(false, true, false, false))
        assertEquals("cellular", WifiMath.transport(false, false, true, false))
        assertEquals("ethernet", WifiMath.transport(false, false, false, true))
        assertEquals("other", WifiMath.transport(false, false, false, false))
    }

    @Test fun linkInfoShape() {
        val l = WifiMath.linkInfo("wlan0", "192.168.1.20", 24, "192.168.1.1", listOf("8.8.8.8", "8.8.4.4"), 1500)
        assertEquals(
            setOf("interfaceName", "ipv4", "prefixLength", "subnetMask", "gateway", "dnsServers", "dns1", "dns2", "mtu"),
            l.keys,
        )
        assertEquals(24, l["prefixLength"]); assertEquals("255.255.255.0", l["subnetMask"])
        assertEquals("8.8.4.4", l["dns2"])
        val b = WifiMath.linkInfo("wlan0", "10.1.2.3", 20, null, emptyList(), null) // not an assumed /24
        assertEquals("255.255.240.0", b["subnetMask"]); assertNull(b["dns1"])
    }

    @Test fun cellularIsNotWifi() {
        val cell = WifiMath.linkInfo("rmnet0", "10.20.30.40", 30, null, listOf("1.1.1.1"), null)
        val m = WifiMath.networkInfo(true, "cellular", cell, null)
        assertEquals(false, m["isWifi"]); assertEquals("cellular", m["transport"]); assertNull(m["wifi"])
        assertEquals("10.20.30.40", m["ipv4"]) // the active network's address, labelled by its transport
    }

    @Test fun wifiIsWifi() {
        val w = WifiMath.linkInfo("wlan0", "192.168.1.20", 24, "192.168.1.1", listOf("8.8.8.8"), 1500)
        val m = WifiMath.networkInfo(true, "wifi", w, w)
        assertEquals(true, m["isWifi"]); assertNotNull(m["wifi"])
        assertEquals("192.168.1.1", m["gateway"])
        assertTrue(m.keys.containsAll(listOf("connected", "transport", "isWifi", "wifi", "ipv4", "prefixLength", "gateway", "dnsServers")))
    }

    @Test fun vpnOverWifiKeepsTheWifiLinkSeparate() {
        val vpn = WifiMath.linkInfo("tun0", "10.8.0.2", 24, null, emptyList(), null)
        val w = WifiMath.linkInfo("wlan0", "192.168.1.20", 24, "192.168.1.1", emptyList(), null)
        val m = WifiMath.networkInfo(true, "vpn", vpn, w)
        assertFalse(m["isWifi"] as Boolean)
        assertEquals("10.8.0.2", m["ipv4"])
        assertEquals("192.168.1.20", (m["wifi"] as Map<*, *>)["ipv4"])
    }

    @Test fun disconnectedHasNullLinkFields() {
        val m = WifiMath.networkInfo(false, "none", null, null)
        assertEquals(false, m["connected"]); assertNull(m["ipv4"]); assertEquals(emptyList<String>(), m["dnsServers"])
    }

    // ---- LAN sweep: subnet math, monotonic progress, rate limit -----------------------------------------

    @Test fun sweepTargetsOfA24AreTheOther253HostsOfTheSubnet() {
        val t = WifiMath.sweepTargets("192.168.1.23", 24)!!
        assertEquals(253, t.size) // 254 hosts minus the device itself
        assertEquals("192.168.1.1", t.first())
        assertEquals("192.168.1.254", t.last())
        assertFalse(t.contains("192.168.1.23"))
        assertFalse(t.contains("192.168.1.0"))
        assertFalse(t.contains("192.168.1.255"))
    }

    @Test fun sweepTargetsFollowThePrefixLength() {
        assertEquals(125, WifiMath.sweepTargets("10.0.0.5", 25)!!.size) // 126 hosts - self
        assertEquals(29, WifiMath.sweepTargets("10.0.0.5", 27)!!.size) // 30 hosts - self
        assertEquals(1, WifiMath.sweepTargets("10.0.0.5", 30)!!.size) // 2 hosts - self
        assertEquals(emptyList(), WifiMath.sweepTargets("10.0.0.5", 31))
        assertEquals(emptyList(), WifiMath.sweepTargets("10.0.0.5", 32))
    }

    @Test fun sweepIsCappedAtA22AroundTheDevice() {
        val t = WifiMath.sweepTargets("10.1.2.77", 16)!!
        assertEquals(1021, t.size) // the /22 10.1.0.0 - 10.1.3.255: 1022 hosts - self
        assertEquals("10.1.0.1", t.first())
        assertEquals("10.1.3.254", t.last())
        assertEquals(1021, WifiMath.sweepTargets("10.1.2.77", 22)!!.size)
        assertEquals(253, WifiMath.sweepTargets("10.1.2.77", 16, maxHosts = 256)!!.size) // the caller can ask for less
    }

    @Test fun sweepPrefixForBoundsTheWindow() {
        assertEquals(22, WifiMath.sweepPrefixFor(1024))
        assertEquals(22, WifiMath.sweepPrefixFor(100000))
        assertEquals(24, WifiMath.sweepPrefixFor(256))
        assertEquals(30, WifiMath.sweepPrefixFor(1))
    }

    @Test fun onlyPrivateRangesAreSwept() {
        assertNull(WifiMath.sweepTargets("8.8.8.8", 24))
        assertNull(WifiMath.sweepTargets("100.64.1.2", 24)) // carrier-grade NAT
        assertNull(WifiMath.sweepTargets("169.254.1.2", 24)) // link-local
        assertNull(WifiMath.sweepTargets("172.32.0.1", 24)) // just outside 172.16/12
        assertNull(WifiMath.sweepTargets("not an ip", 24))
        assertNull(WifiMath.sweepTargets("192.168.1.1", 33))
        assertTrue(WifiMath.isPrivateIpv4("172.16.0.1"))
        assertTrue(WifiMath.isPrivateIpv4("172.31.255.1"))
        assertTrue(WifiMath.isPrivateIpv4("10.255.0.1"))
        assertTrue(WifiMath.isPrivateIpv4("192.168.0.1"))
        assertFalse(WifiMath.isPrivateIpv4("192.169.0.1"))
    }

    @Test fun progressOnlyGrowsReachesTotalExactlyOnceAndIsThrottled() {
        val total = 253
        val p = WifiMath.SweepProgress(total)
        val seen = mutableListOf<Int>()
        repeat(total) { p.complete()?.let { seen.add(it.done) } }
        assertEquals(total, seen.last())
        assertEquals(1, seen.count { it == total })
        assertEquals(seen.sorted(), seen)
        assertEquals(seen.distinct(), seen)
        assertTrue(seen.size <= 130, "about 100 events, not one per probe: ${seen.size}")
        assertNull(p.complete(null), "a probe past the total reports nothing")
        assertEquals(total, p.doneCount())
    }

    @Test fun progressIsMonotonicWhenTwentyThreadsFinishProbesAtOnce() {
        val total = 1021
        val p = WifiMath.SweepProgress(total)
        val seen = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val lock = Any()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(20)
        repeat(total) {
            pool.execute { synchronized(lock) { p.complete()?.let { seen.add(it.done) } } }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(seen.sorted(), seen)
        assertEquals(total, seen.last())
        assertEquals(total, p.doneCount())
    }

    @Test fun smallSweepReportsEveryProbe() {
        val p = WifiMath.SweepProgress(5)
        assertEquals(listOf(1, 2, 3, 4, 5), (1..5).map { p.complete()?.done })
    }

    @Test fun sweepGateAllowsOneAtATimeWithAPauseBetween() {
        val g = WifiMath.SweepGate(minIntervalMs = 3_000)
        assertTrue(g.tryStart(1_000))
        assertFalse(g.tryStart(1_500), "already running")
        g.finish(5_000)
        assertFalse(g.tryStart(6_000), "inside the pause")
        assertTrue(g.tryStart(8_000))
    }

    @Test fun sweepEventShapes() {
        assertEquals(
            mapOf("type" to "progress", "done" to 3, "total" to 10, "answered" to 1, "host" to "10.0.0.2"),
            WifiMath.progressEvent(3, 10, 1, "10.0.0.2"),
        )
        val done = WifiMath.doneEvent(10, 10, listOf(mapOf("ipAddress" to "10.0.0.2")), partial = true, prefix = 22)
        assertEquals("done", done["type"])
        assertEquals(10, done["done"])
        assertEquals(1, (done["devices"] as List<*>).size)
        assertEquals(1, done["answered"])
        assertEquals(true, done["partial"])
        assertEquals(22, done["prefix"])
    }

    @Test fun everyAnswerIsReportedEvenOnAThrottledCount() {
        // 1000 probes: about 100 throttled events, but 7 hits on counts the throttle would skip.
        val hitsAt = setOf(3, 11, 12, 13, 505, 777, 998)
        val q = WifiMath.SweepProgress(1000)
        val events = (1..1000).mapNotNull { i -> q.complete(if (i in hitsAt) "10.0.0.${i % 250}" else null) }
        assertEquals(hitsAt, events.filter { it.host != null }.map { it.done }.toSet())
        assertEquals(7, events.last().answered, "the running answered count is on the last event")
        assertEquals(events.map { it.answered }.sorted(), events.map { it.answered })
        assertEquals(7, q.answeredCount())
        assertEquals(1000, events.last().done)
    }

    @Test fun partialWindowIsReported() {
        assertTrue(WifiMath.sweepIsPartial(16))
        assertTrue(WifiMath.sweepIsPartial(21))
        assertFalse(WifiMath.sweepIsPartial(22))
        assertFalse(WifiMath.sweepIsPartial(24))
        assertEquals(22, WifiMath.sweepEffectivePrefix(16))
        assertEquals(24, WifiMath.sweepEffectivePrefix(24))
        assertTrue(WifiMath.sweepIsPartial(24, maxHosts = 1024).not())
    }

    @Test fun cancelThenRefusedListenNeverRestartsTheCancelledSweep() {
        val c = WifiMath.SweepControl(WifiMath.SweepGate(minIntervalMs = 3_000))
        val first = c.listen(1_000)!!
        assertFalse(first.get())
        c.cancel() // the Dart side cancelled
        assertTrue(first.get())
        assertNull(c.listen(1_100), "the cancelled sweep still runs down: the gate refuses a new one")
        assertTrue(first.get(), "a refused listen does not reset the cancelled token")
        c.finish(1_500)
        assertNull(c.listen(2_000), "inside the pause")
        val second = c.listen(5_000)!!
        assertFalse(second.get(), "a new accepted sweep has its own token")
        assertTrue(first.get())
        c.cancel()
        assertTrue(second.get())
    }
}
