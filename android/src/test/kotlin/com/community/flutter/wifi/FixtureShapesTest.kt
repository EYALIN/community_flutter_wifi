package com.community.flutter.wifi

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The recorded plugin outputs in a test/fixtures/wifi/ folder (JSON files that Dart tests of a consuming package also
 * parse) must stay what this Kotlin code builds from the same inputs: same keys, same value types, and for the
 * emulator recordings the same values. If either side changes without the other, one of these tests fails.
 * The folder is found above the working directory, or passed with -Dwifi.fixtures=<dir>.
 */
class FixtureShapesTest {
    private fun fixtureDir(): File {
        System.getProperty("wifi.fixtures")?.let { return File(it) }
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            // A sibling folder of an ancestor that holds test/fixtures/wifi.
            d.listFiles { f -> f.isDirectory }?.map { File(it, "test/fixtures/wifi") }?.firstOrNull { it.isDirectory }?.let { return it }
            d = d.parentFile
        }
        fail("test/fixtures/wifi not found above ${System.getProperty("user.dir")}")
    }

    private fun fixture(name: String): Any? = norm(MiniJson(File(fixtureDir(), "$name.json").readText()).parse())

    /** JSON integers parse as Long, the plugin builds Int: compare them as one number type. */
    private fun norm(v: Any?): Any? = when (v) {
        is Map<*, *> -> v.entries.associate { (k, x) -> k.toString() to norm(x) }
        is List<*> -> v.map { norm(it) }
        is Int -> v.toLong()
        else -> v
    }

    @Suppress("UNCHECKED_CAST")
    private fun obj(name: String) = fixture(name) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun arr(name: String) = fixture(name) as List<Map<String, Any?>>

    /** Recursive "key set + value type" description; Int and Long are one `int`. */
    private fun shape(v: Any?): Any? = when (v) {
        null -> "null"
        is Map<*, *> -> v.entries.associate { (k, x) -> k.toString() to shape(x) }.toSortedMap()
        is List<*> -> if (v.isEmpty()) "list<empty>" else "list<${shape(v.first())}>"
        is Boolean -> "bool"
        is Int, is Long -> "int"
        is Double -> "double"
        is String -> "string"
        else -> v::class.simpleName
    }

    private fun assertSameShape(expected: Map<String, Any?>, actual: Map<String, Any?>, what: String) {
        assertEquals(expected.keys.sorted(), actual.keys.sorted(), "$what: keys")
        for (k in expected.keys) {
            val e = expected[k]
            // A list element of an empty list says nothing about the type: compare keys only for a non-empty one.
            val a = actual[k]
            if ((e is List<*> && e.isEmpty()) || (a is List<*> && a.isEmpty())) continue
            assertEquals(shape(e), shape(a), "$what: type of '$k'")
        }
    }

    @Test fun getAllWifiDetails_matchesTheEmulatorRecording() {
        val rec = obj("emulator_all_wifi_details")
        val built = WifiMath.allWifiDetails(
            true, "\"AndroidWifi\"", "00:13:10:85:fe:01", "10.0.2.16", "02:00:00:00:00:00", 0, 1, 4, "10.0.2.2", -50, 2447,
            "10.0.2.3", "0.0.0.0",
        )
        assertEquals(rec, norm(built)) // the recording was made from exactly these framework values
    }

    @Test fun getAllWifiDetails_otherRecordingsHaveTheSameKeysAndTypes() {
        val built = WifiMath.allWifiDetails(true, "\"HomeNet\"", "aa:bb:cc:dd:ee:ff", "192.168.1.23", "02:00:00:00:00:00", 3, 866, 3, "192.168.1.1", -58, 5180, "192.168.1.1", "0.0.0.0")
        for (n in listOf("home_all_wifi_details", "office_6ghz_all_wifi_details")) assertSameShape(obj(n), built, n)
        // The recordings with nothing connected keep every key; the values that were unreadable are null / -1.
        val none = obj("no_location_all_wifi_details")
        assertEquals(built.keys, none.keys, "no_location_all_wifi_details: keys")
        val sixGhz = obj("office_6ghz_all_wifi_details")
        assertEquals(WifiMath.channel(Regex("\\d+").find(sixGhz["frequency"] as String)!!.value.toInt()).toLong(), sixGhz["channel"])
    }

    @Test fun getIpInfo_matchesTheEmulatorRecording() {
        val rec = arr("emulator_ip_info")
        val built = WifiMath.ipInfoConnection(
            "WIFI", -50, 1, "\"AndroidWifi\"", "10.0.2.16", "02:00:00:00:00:00", 0, 2447, "00:13:10:85:fe:01",
            "Asia/Jerusalem", "10.0.2.3", "0.0.0.0",
        )
        built.putAll(WifiMath.unknownLocation())
        assertEquals(listOf(norm(built)), rec)
        assertSameShape(arr("home_ip_info").single(), built, "home_ip_info")
    }

    @Test fun scan_matchesTheEmulatorRecording() {
        // The recorded entries, rebuilt from their raw framework fields (centre frequency, capabilities, level, width).
        val raw = listOf(
            listOf("AndroidWifi", "00:13:10:85:fe:01", "[ESS]", 2447, -50, WifiMath.WIDTH_20),
            listOf("HomeNet-5G", "a4:2b:b0:1c:5e:90", "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]", 5180, -58, WifiMath.WIDTH_80),
            listOf("", "3c:84:6a:21:9d:02", "[WPA2-PSK-CCMP][ESS]", 2412, -79, WifiMath.WIDTH_20),
            listOf("Office WiFi 6E", "f0:9f:c2:44:10:07", "[RSN-SAE-CCMP][ESS]", 6195, -67, WifiMath.WIDTH_160),
        )
        val built = raw.map { WifiMath.scanEntry(it[0] as String, it[1] as String, it[2] as String, it[3] as Int, it[4] as Int, it[5] as Int) }
        // `distance` is a free-space guess: pinned exactly for the one recorded emulator entry (3.08), and as a
        // two-decimal string for the entries whose recorded distance was written by hand.
        val rec = arr("emulator_scan")
        // `hasPassword` is compared for every entry, the SAE-only 6E one included.
        fun cmp(e: Map<String, Any?>) = e - "distance"
        assertEquals(rec.map { cmp(it) }, built.map { cmp(norm(it) as Map<String, Any?>) })
        assertTrue(built.all { it["hasPassword"] is Boolean })
        assertEquals(rec[0]["distance"], built[0]["distance"])
        for (e in rec) assertTrue((e["distance"] as String).matches(Regex("""\d+\.\d\d""")))
        // scanFresh wraps the same entries.
        val fresh = WifiMath.scanFresh(built, true, true, false, false, 900L, 5_000L)
        assertEquals(built, fresh["results"])
        assertEquals(built.size, fresh["count"])
    }

    @Test fun getConnectedDevices_matchesTheEmulatorRecording() {
        val rec = arr("emulator_connected_devices")
        val built = rec.map {
            WifiMath.deviceEntry(
                it["ipAddress"] as String, it["deviceName"] as String, it["localHost"] as Boolean, it["loopbackAddress"] as Boolean,
                it["hostAddress"] as String, it["canonicalHostName"] as String, it["multicastAddress"] as Boolean,
                it["siteLocalAddress"] as Boolean,
            )
        }
        assertEquals(rec, built)
        assertTrue(rec.isNotEmpty())
    }

    @Test fun getNetworkInfo_matchesTheFourRecordings() {
        val home = WifiMath.linkInfo("wlan0", "192.168.1.23", 24, "192.168.1.1", listOf("192.168.1.1"), 1500)
        assertEquals(obj("home_network_info"), norm(WifiMath.networkInfo(true, "wifi", home, home)))
        val cell = WifiMath.linkInfo("rmnet_data1", "100.78.14.201", 30, "100.78.14.202", listOf("8.8.8.8", "8.8.4.4"), 1400)
        assertEquals(obj("cellular_network_info"), norm(WifiMath.networkInfo(true, "cellular", cell, null)))
        val tun = WifiMath.linkInfo("tun0", "10.8.0.2", 24, null, listOf("10.8.0.1"), 1400)
        assertEquals(obj("vpn_network_info"), norm(WifiMath.networkInfo(true, "vpn", tun, home)))
        assertEquals(obj("none_network_info"), norm(WifiMath.networkInfo(false, "none", null, null)))
    }

    @Test fun everyRecordedFixtureIsCoveredByATest() {
        val covered = setOf(
            "emulator_all_wifi_details", "home_all_wifi_details", "office_6ghz_all_wifi_details", "no_location_all_wifi_details",
            "emulator_ip_info", "home_ip_info", "emulator_scan", "emulator_connected_devices", "home_network_info",
            "cellular_network_info", "vpn_network_info", "none_network_info",
            "ios_all_wifi_details", // iOS: produced by the Swift plugin, shape-checked on the Dart side only
            "error_messages", // the plugin's own error texts: ErrorMessagesTest
            "ios_network_info", "ios_connected_devices_done", // iOS: the Swift plugin's shapes, Dart side only
        )
        val files = fixtureDir().listFiles { f -> f.extension == "json" }!!.map { it.nameWithoutExtension }.toSet()
        assertEquals(emptySet(), files - covered, "a new fixture needs a Kotlin shape test (or an entry in this list)")
    }
}

/** A small JSON reader (the JVM unit tests have no org.json): objects, arrays, strings, numbers, true/false/null. */
internal class MiniJson(private val s: String) {
    private var i = 0

    fun parse(): Any? = value().also { ws(); check(i == s.length) { "trailing JSON at $i" } }

    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

    private fun value(): Any? {
        ws()
        return when (val c = s[i]) {
            '{' -> {
                i++; val m = LinkedHashMap<String, Any?>(); ws()
                if (s[i] == '}') { i++; return m }
                while (true) {
                    ws(); val k = str(); ws(); check(s[i++] == ':'); m[k] = value(); ws()
                    if (s[i] == ',') { i++; continue }
                    check(s[i++] == '}'); return m
                }
                @Suppress("UNREACHABLE_CODE") m
            }
            '[' -> {
                i++; val l = ArrayList<Any?>(); ws()
                if (s[i] == ']') { i++; return l }
                while (true) {
                    l.add(value()); ws()
                    if (s[i] == ',') { i++; continue }
                    check(s[i++] == ']'); return l
                }
                @Suppress("UNREACHABLE_CODE") l
            }
            '"' -> str()
            't' -> { i += 4; true }
            'f' -> { i += 5; false }
            'n' -> { i += 4; null }
            else -> {
                check(c == '-' || c.isDigit()) { "unexpected '$c' at $i" }
                val st = i
                while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
                val t = s.substring(st, i)
                if (t.any { it in ".eE" }) t.toDouble() else t.toLong()
            }
        }
    }

    private fun str(): String {
        check(s[i++] == '"')
        val b = StringBuilder()
        while (s[i] != '"') {
            if (s[i] == '\\') {
                i++
                when (val e = s[i++]) {
                    'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b')
                    'f' -> b.append('\u000c'); 'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> b.append(e)
                }
            } else b.append(s[i++])
        }
        i++
        return b.toString()
    }
}
