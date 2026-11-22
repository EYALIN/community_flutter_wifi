package com.community.flutter.wifi

import java.io.File
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * No error the plugin sends over its channels carries a network name or a host name. Its own failures are
 * constant texts (WifiMath.ownErrorMessages, pinned with the Dart side by fixtures/wifi/error_messages.json); any other
 * exception crosses as its class name only (WifiMath.platformErrorMessage), whatever its message held.
 */
class ErrorMessagesTest {
    private fun find(rel: String): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            listOf(File(d, rel), File(d, "plugins/community_flutter_wifi/$rel"), File(d, "FlutterApps/plugins/community_flutter_wifi/$rel"))
                .firstOrNull { it.exists() }?.let { return it }
            d.listFiles { f -> f.isDirectory }?.map { File(it, rel) }?.firstOrNull { it.exists() }?.let { return it }
            d = d.parentFile
        }
        fail("$rel not found above ${System.getProperty("user.dir")}")
    }

    private val pluginSource by lazy { find("android/src/main/kotlin/com/community/flutter/wifi/CommunityFlutterWifiPlugin.kt").readText() }

    // Real names, as a user's network and LAN would have them.
    private val names = listOf("MyHome", "HomeNet-Cedar", "Casa Pérez 5G", "קפה גסט", "printer.local", "192.168.1.23")

    @Test fun aFrameworkExceptionCrossesAsItsClassNameOnly() {
        val thrown = listOf(
            IllegalStateException("failed to join MyHome"),
            IllegalArgumentException("Invalid SSID HomeNet-Cedar"),
            SecurityException("uid 10123 cannot read Casa Pérez 5G"),
            UnknownHostException("Unable to resolve host \"printer.local\": No address associated with hostname"),
            RuntimeException("connect to 192.168.1.23 failed: קפה גסט"),
            object : Exception("anonymous MyHome") {},
        )
        for (e in thrown) {
            val m = WifiMath.platformErrorMessage(e)
            for (n in names) assertFalse(m.contains(n), "$m keeps $n")
            assertTrue(m.matches(Regex("[A-Za-z]+")), m)
        }
        assertEquals("UnknownHostException", WifiMath.platformErrorMessage(thrown[3]))
        assertEquals("Exception", WifiMath.platformErrorMessage(thrown[5]))
    }

    @Test fun everyErrorTheChannelsSendIsAConstantOrAClassName() {
        // Each result.error / sink.error message and each WifiError text in the plugin source.
        val sends = Regex("""\b(?:result|sink)\.error\(\s*"wifi"\s*,\s*([^\n]+?),\s*null\)""").findAll(pluginSource).map { it.groupValues[1].trim() }.toList()
        val raised = Regex("""WifiError\(("[^"\n]*")\)""").findAll(pluginSource).map { it.groupValues[1] }.toList()
        assertTrue(sends.size >= 6, "the scan should find the plugin's error sends: $sends")
        assertTrue(raised.size >= 7, "the scan should find the plugin's WifiError texts: $raised")
        val allowed = setOf(
            "e.message", // only in `catch (e: WifiError)`: one of ownErrorMessages
            "WifiMath.platformErrorMessage(e)",
            "if (e is WifiError) e.message else WifiMath.platformErrorMessage(e)",
        )
        for (s in sends + raised) {
            if (s in allowed) continue
            assertTrue(s.startsWith("\"") && s.endsWith("\"") && !s.contains("$"), "not a constant text: $s")
            assertTrue(s.removeSurrounding("\"") in WifiMath.ownErrorMessages, "$s is not in WifiMath.ownErrorMessages")
        }
        // A bare e.message is sent only by the WifiError catch.
        val bare = Regex("""catch \(e: (\w+)\) \{\s*\n[^\n]*\.error\("wifi", e\.message,""").findAll(pluginSource).map { it.groupValues[1] }.toList()
        assertEquals(listOf("WifiError"), bare)
        assertEquals(1, Regex("""\.error\("wifi", e\.message,""").findAll(pluginSource).count())
        assertFalse(pluginSource.contains("e.message ?: e.toString()"))
    }

    // The plugin's scanFresh takes its decision from WifiMath.scanOutcome (table-tested in WifiMathTest),
    // with no inline copy of the rule left to drift.
    @Test fun scanFreshUsesScanOutcome() {
        assertTrue(pluginSource.contains("WifiMath.scanOutcome(requested = requested, gotBroadcast = got, updatedExtra = updated.get())"))
        for (k in listOf("requested", "fresh", "throttled", "timedOut")) assertTrue(pluginSource.contains("$k = outcome.$k,"), k)
        assertFalse(Regex("""throttled\s*=\s*!requested""").containsMatchIn(pluginSource))
    }

    @Test fun theOwnTextsAreThoseTheDartSideKeeps() {
        // The test/fixtures/wifi folder (a sibling folder of an ancestor, as FixtureShapesTest finds it).
        val fixture = MiniJson(find("test/fixtures/wifi/error_messages.json").readText()).parse() as Map<*, *>
        assertEquals(WifiMath.ownErrorMessages, fixture["android"])
        for (m in WifiMath.ownErrorMessages) for (n in names) assertFalse(m.contains(n))
    }
}
