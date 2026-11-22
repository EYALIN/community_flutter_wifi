package com.community.flutter.wifi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One group of tests per channel method: every key, type and unit of the map each method hands to
 * Dart. The plugin only reads the Android framework and passes plain values to [WifiMath], so these tests
 * feed the same values the emulator returned (see FixtureShapesTest) and pin what comes out.
 */
class PluginMethodsTest {
    // ---- getAllWifiDetails -------------------------------------------------------------------------

    private fun details() = WifiMath.allWifiDetails(
        true, "\"AndroidWifi\"", "00:13:10:85:fe:01", "10.0.2.16", "02:00:00:00:00:00", 0, 1, 4, "10.0.2.2", -50, 2447,
        "10.0.2.3", "0.0.0.0",
    )

    @Test fun getAllWifiDetails_everyKeyTypeAndUnit() {
        val d = details()
        assertEquals(16, d.size)
        assertEquals(true, d["iswifienabled"])
        assertEquals(true, d["issupportwifi"])
        assertTrue(d["ssid"] is String && d["bssid"] is String && d["ip"] is String && d["mac"] is String)
        assertTrue(d["networkid"] is Int && d["channel"] is Int)
        assertEquals("1 Mbps", d["linkspeed"]) // String with the unit
        assertEquals("1 Mbps", d["speed"])
        assertEquals("2447 MHz", d["frequency"]) // String with the unit
        assertEquals(8, d["channel"])
        assertTrue(d["gateway"] is String && d["dns1"] is String && d["dns2"] is String)
    }

    @Test fun getAllWifiDetails_rssiIsNegativeDbmAndSignalstrengthIsTheLevel() {
        val d = details()
        assertTrue(d["rssi"] is Int && (d["rssi"] as Int) < 0)
        assertTrue(d["signalstrength"] is Int && (d["signalstrength"] as Int) in 0..4)
        // The C1 regression: the level must never be shown (or returned) as dBm.
        for (level in 0..4) {
            val m = WifiMath.allWifiDetails(true, null, null, null, null, -1, 54, level, null, -100 + level * 12, 5180, null, null)
            assertEquals(level, m["signalstrength"])
            assertTrue((m["rssi"] as Int) <= -52)
        }
    }

    @Test fun getAllWifiDetails_unreadableValuesKeepTheirKeys() {
        val d = WifiMath.allWifiDetails(false, null, null, null, null, -1, -1, 0, null, -1, -1, null, null)
        assertEquals(details().keys, d.keys)
        assertEquals("-1 Mbps", d["linkspeed"])
        assertEquals(-1, d["channel"])
    }

    // ---- scan: getWifiList / getCachedWifiList (entries) and scanFresh ------------------------------

    @Test fun getWifiList_entryKeysTypesAndUnits() {
        val e = WifiMath.scanEntry("HomeNet-5G", "a4:2b:b0:1c:5e:90", "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]", 5180, -58, WifiMath.WIDTH_80)
        assertEquals(9, e.size)
        assertEquals("5.18 GHz", e["frequency"]) // String in GHz here, unlike getAllWifiDetails' MHz
        assertTrue(e["level"] is Int && (e["level"] as Int) < 0) // dBm
        assertEquals("80 MHz", e["channelWidth"])
        assertEquals("WPA2", e["security"])
        assertEquals(true, e["hasPassword"])
        assertEquals("3.66", e["distance"]) // String, two decimals
    }

    @Test fun getCachedWifiList_usesTheSameEntryShape() {
        val open = WifiMath.scanEntry("", "3c:84:6a:21:9d:02", "[ESS]", 2412, -79, WifiMath.WIDTH_20)
        assertEquals(WifiMath.scanEntry("x", "y", "[ESS]", 2412, -1, 0).keys, open.keys)
        assertEquals("Open", open["security"])
        assertEquals(false, open["hasPassword"])
    }

    @Test fun scanFresh_everyKeyAndType() {
        val entries = listOf(WifiMath.scanEntry("a", "b", "[ESS]", 2412, -50, 0))
        val m = WifiMath.scanFresh(entries, requested = true, fresh = true, throttled = false, timedOut = false, ageMs = 1_200L, nowEpochMs = 1_000_000L)
        assertEquals(8, m.size)
        assertEquals(1, m["count"])
        assertEquals(entries, m["results"])
        assertTrue(m["requested"] is Boolean && m["fresh"] is Boolean && m["throttled"] is Boolean && m["timedOut"] is Boolean)
        assertEquals(1_200L, m["ageMs"]) // Long, milliseconds
        assertEquals(998_800L, m["scanTimestampMs"]) // epoch ms = now - age
    }

    // ---- getConnectedDevices and the connected_devices EventChannel ---------------------------------

    @Test fun getConnectedDevices_deviceEntryKeysAndTypes() {
        val d = WifiMath.deviceEntry("10.0.2.3", "dns.lan", false, false, "10.0.2.3", "dns.lan", false, true)
        assertEquals(
            listOf("ipAddress", "deviceName", "localHost", "loopbackAddress", "hostAddress", "canonicalHostName", "multicastAddress", "siteLocalAddress"),
            d.keys.toList(),
        )
        assertTrue(d["ipAddress"] is String && d["deviceName"] is String && d["canonicalHostName"] is String)
        assertTrue(d["localHost"] is Boolean && d["siteLocalAddress"] is Boolean)
    }

    @Test fun connectedDevicesChannel_progressAndDoneEvents() {
        val p = WifiMath.progressEvent(10, 253, 2, "10.0.2.3")
        assertEquals(setOf("type", "done", "total", "answered", "host"), p.keys)
        assertEquals("progress", p["type"])
        val dev = WifiMath.deviceEntry("10.0.2.3", "dns.lan", false, false, "10.0.2.3", "dns.lan", false, true)
        val done = WifiMath.doneEvent(253, 253, listOf(dev), partial = false, prefix = 24)
        assertEquals(setOf("type", "done", "total", "answered", "devices", "partial", "prefix"), done.keys)
        assertEquals("done", done["type"])
        assertEquals(1, done["answered"])
        assertEquals(listOf(dev), done["devices"])
        assertEquals(24, done["prefix"])
    }

    // ---- getIpInfo and getNetworkInfo ---------------------------------------------------------------

    @Test fun getIpInfo_connectionPlusLocationKeys() {
        val e = WifiMath.ipInfoConnection("WIFI", -50, 1, "\"AndroidWifi\"", "10.0.2.16", "02:00:00:00:00:00", 0, 2447, "00:13:10:85:fe:01", "Asia/Jerusalem", "10.0.2.3", "0.0.0.0")
        e.putAll(WifiMath.unknownLocation())
        assertEquals(20, e.size)
        assertTrue(e["signal"] is Int && (e["signal"] as Int) < 0) // dBm
        assertTrue(e["speed"] is Int) // Mbps as a bare number here (getAllWifiDetails has the suffix)
        assertTrue(e["frequency"] is Int) // MHz, bare
        assertEquals("WIFI", e["type"])
        assertEquals(-1.0, e["latitude"])
        assertEquals(-1.0, e["longitude"])
        for (k in listOf("city", "street", "country", "region", "zipcode", "state")) assertEquals("UNKNOWN", e[k])
    }

    @Test fun getIpInfo_notConnectedAnswersUnknownNotNull() {
        val e = WifiMath.ipInfoConnection(null, -1, -1, null, null, null, -1, -1, null, "UTC", null, null)
        for (k in listOf("ssid", "internalip", "macaddress", "bssid", "dns1", "dns2")) assertEquals("UNKNOWN", e[k])
        assertEquals("Not Connected", e["type"])
    }

    @Test fun getNetworkInfo_wifiLinkKeysAndTypes() {
        val link = WifiMath.linkInfo("wlan0", "192.168.1.23", 24, "192.168.1.1", listOf("192.168.1.1"), 1500)
        val m = WifiMath.networkInfo(true, "wifi", link, link)
        assertEquals(
            setOf("connected", "transport", "isWifi", "wifi", "interfaceName", "ipv4", "prefixLength", "subnetMask", "gateway", "dnsServers", "dns1", "dns2", "mtu"),
            m.keys,
        )
        assertEquals(true, m["isWifi"])
        assertEquals("255.255.255.0", m["subnetMask"])
        assertTrue(m["prefixLength"] is Int && m["mtu"] is Int && m["dnsServers"] is List<*>)
        assertEquals(null, m["dns2"])
    }

    @Test fun getNetworkInfo_cellularIsNeverWifi() {
        val link = WifiMath.linkInfo("rmnet_data1", "100.78.14.201", 30, "100.78.14.202", listOf("8.8.8.8", "8.8.4.4"), 1400)
        val m = WifiMath.networkInfo(true, "cellular", link, null)
        assertEquals(false, m["isWifi"])
        assertEquals(null, m["wifi"])
    }
}
