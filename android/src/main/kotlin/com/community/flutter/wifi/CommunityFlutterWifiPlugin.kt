package com.community.flutter.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Android side of the Wi-Fi manager: scan, connection / IP / router details, the LAN sweep and
 * network diagnostics, returned as plain maps over the method and event channels.
 */
class CommunityFlutterWifiPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware,
    PluginRegistry.RequestPermissionsResultListener, PluginRegistry.ActivityResultListener {
    private lateinit var channel: MethodChannel
    private lateinit var sweepChannel: EventChannel
    private val sweepControl = WifiMath.SweepControl()
    private lateinit var context: Context
    private var activity: Activity? = null
    private var binding: ActivityPluginBinding? = null
    private var pendingPermission: MethodChannel.Result? = null
    private var pendingConnect: MethodChannel.Result? = null
    private val io = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "community_flutter_wifi")
        channel.setMethodCallHandler(this)
        sweepChannel = EventChannel(binding.binaryMessenger, "community_flutter_wifi/connected_devices")
        sweepChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                startSweep((arguments as? Map<*, *>)?.get("maxHosts").let { (it as? Number)?.toInt() } ?: 1024, events)
            }

            override fun onCancel(arguments: Any?) {
                sweepControl.cancel()
            }
        })
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        sweepChannel.setStreamHandler(null)
        sweepControl.cancel()
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) = attach(binding)
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) = attach(binding)
    override fun onDetachedFromActivityForConfigChanges() = detach()
    override fun onDetachedFromActivity() = detach()

    private fun attach(b: ActivityPluginBinding) {
        binding = b
        activity = b.activity
        b.addRequestPermissionsResultListener(this)
        b.addActivityResultListener(this)
    }

    private fun detach() {
        binding?.removeRequestPermissionsResultListener(this)
        binding?.removeActivityResultListener(this)
        binding = null
        activity = null
    }

    private val wifi get() = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivity get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "isWifiEnabled" -> result.success(wifi.isWifiEnabled)
            "hasLocationPermission" -> result.success(hasLocation())
            "requestLocationPermission" -> requestLocation(result, call.argument<Boolean>("includeNearbyDevices") ?: true)
            "isLocationEnabled" -> result.success(isLocationEnabled())
            "locationPermissionState" -> result.success(locationPermissionState())
            "isTetheringActive" -> background(result) { isTetheringActive() }
            "openLocationSettings" -> result.success(openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            "openWifiSettings" -> result.success(openSettings(Settings.ACTION_WIFI_SETTINGS))
            "openAppSettings" -> result.success(openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true))
            "isConnectedToInternet" -> result.success(isConnectedToInternet())
            "connect" -> connect(
                call.argument<String>("ssid") ?: "",
                call.argument<String>("password") ?: "",
                call.argument<String>("security") ?: "WPA",
                call.argument<Boolean>("hidden") ?: false,
                result,
            )
            // The rest touch the network stack (scan, geocoder, ping sweep): off the main thread.
            "getWifiList" -> background(result) { wifiList() }
            "getCachedWifiList" -> background(result) { cachedWifiList() }
            "scanFresh" -> background(result) { scanFresh(call.argument<Number>("timeoutMs")?.toLong() ?: SCAN_TIMEOUT_MS) }
            "getNetworkInfo" -> background(result) { networkInfo() }
            "getIpInfo" -> background(result) { ipInfo() }
            "getAllWifiDetails" -> background(result) { allWifiDetails() }
            "getConnectedDevices" -> background(result) { connectedDevices() }
            "getNetworkDiagnostics" -> background(result) { networkDiagnostics() }
            else -> result.notImplemented()
        }
    }

    private fun background(result: MethodChannel.Result, work: () -> Any?) {
        io.execute {
            try {
                val value = work()
                main.post { result.success(value) }
            } catch (e: WifiError) {
                main.post { result.error("wifi", e.message, null) }
            } catch (e: Exception) {
                main.post { result.error("wifi", WifiMath.platformErrorMessage(e), null) }
            }
        }
    }

    /** The plugin's own failures: [message] is one of the constant texts of WifiMath.ownErrorMessages, never a name. */
    private class WifiError(message: String) : Exception(message)

    // ---- Permissions -------------------------------------------------------------------------

    private fun hasLocation() =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * What the user granted: `fine` (precise), `approximate` (Android 12+ "Approximate" - COARSE only, the system hides
     * SSIDs and scan results from it) or `denied`. [hasLocation] stays "FINE granted" for the callers that exist.
     */
    private fun locationPermissionState(): String = WifiMath.locationPermissionState(
        fine = hasLocation(),
        coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED,
    )

    /** A hotspot / USB / Bluetooth tether is up: such an interface exists and is up (no permission needed). */
    private fun isTetheringActive(): Boolean = try {
        WifiMath.hasTetherInterface(
            java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp }.map { it.name },
        )
    } catch (e: Exception) {
        false
    }

    /**
     * Whether the system Location toggle is on. With it off, SSID reads and scans come back empty
     * without any error, so a caller checks this before blaming the permission.
     */
    private fun isLocationEnabled(): Boolean = WifiMath.locationEnabled(
        Build.VERSION.SDK_INT,
        { (context.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled },
        {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, 0)
        },
    )

    private fun requestLocation(result: MethodChannel.Result, includeNearby: Boolean = true) {
        val a = activity
        if (hasLocation()) return result.success(true)
        if (a == null || pendingPermission != null) return result.success(false)
        pendingPermission = result
        a.requestPermissions(locationRequest(includeNearby), REQUEST_LOCATION)
    }

    /**
     * Location, plus NEARBY_WIFI_DEVICES on Android 13+ when the host app declares it (scan results) -
     * a second system dialog. A caller that does not need it passes `includeNearbyDevices: false`.
     */
    private fun locationRequest(includeNearby: Boolean = true): Array<String> {
        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (includeNearby && Build.VERSION.SDK_INT >= 33 && declares(Manifest.permission.NEARBY_WIFI_DEVICES)) {
            wanted.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return wanted.toTypedArray()
    }

    private fun declares(permission: String): Boolean = try {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions?.contains(permission) == true
    } catch (e: Exception) {
        false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        if (requestCode != REQUEST_LOCATION) return false
        pendingPermission?.success(hasLocation())
        pendingPermission = null
        return true
    }

    private fun openSettings(action: String, forApp: Boolean = false): Boolean = try {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (forApp) intent.data = android.net.Uri.fromParts("package", context.packageName, null)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    // ---- Join a network -------------------------------------------------------------------------

    private fun isOpen(security: String) = security.uppercase(Locale.ROOT).let { it.isEmpty() || it == "NONE" || it == "NOPASS" }
    private fun isWep(security: String) = security.uppercase(Locale.ROOT) == "WEP"

    /**
     * Android 11+: the system "save this network?" dialog (ACTION_WIFI_ADD_NETWORKS), answered in
     * [onActivityResult]. Android 10: a network suggestion (the user approves it in a notification;
     * WEP cannot be suggested and falls back to the Wi-Fi settings). Older: the legacy configuration
     * API adds and enables the network.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun connect(ssid: String, password: String, security: String, hidden: Boolean, result: MethodChannel.Result) {
        if (ssid.isEmpty()) return result.error("wifi", "No network name", null)
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val a = activity
                if (a == null || pendingConnect != null || isWep(security)) {
                    return result.success(if (openSettings(Settings.ACTION_WIFI_SETTINGS)) "saved" else "failed")
                }
                val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
                intent.putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion(ssid, password, security, hidden)))
                pendingConnect = result
                a.startActivityForResult(intent, REQUEST_ADD_NETWORK)
            } else if (Build.VERSION.SDK_INT == 29) {
                if (isWep(security)) return result.success(if (openSettings(Settings.ACTION_WIFI_SETTINGS)) "saved" else "failed")
                val status = wifi.addNetworkSuggestions(listOf(suggestion(ssid, password, security, hidden)))
                result.success(
                    when (status) {
                        WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS,
                        WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE -> "suggested"
                        else -> "failed"
                    },
                )
            } else {
                val config = WifiConfiguration().apply {
                    SSID = "\"$ssid\""
                    hiddenSSID = hidden
                    when {
                        isOpen(security) -> allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                        isWep(security) -> {
                            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                            wepKeys[0] = "\"$password\""
                            wepTxKeyIndex = 0
                        }
                        else -> preSharedKey = "\"$password\""
                    }
                }
                val id = wifi.addNetwork(config)
                result.success(if (id != -1 && wifi.enableNetwork(id, true)) "connected" else "failed")
            }
        } catch (e: Exception) {
            pendingConnect = null
            result.error("wifi", WifiMath.platformErrorMessage(e), null)
        }
    }

    @android.annotation.TargetApi(29)
    private fun suggestion(ssid: String, password: String, security: String, hidden: Boolean): WifiNetworkSuggestion {
        val b = WifiNetworkSuggestion.Builder().setSsid(ssid).setIsHiddenSsid(hidden)
        when (WifiMath.suggestionAuth(security)) {
            "open" -> {}
            // A transition-mode AP is labelled "WPA3" by the scan; a device without SAE can still join it as WPA2.
            "wpa3" -> if (wifi.isWpa3SaeSupported) b.setWpa3Passphrase(password) else b.setWpa2Passphrase(password)
            else -> b.setWpa2Passphrase(password)
        }
        return b.build()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_ADD_NETWORK) return false
        val result = pendingConnect ?: return true
        pendingConnect = null
        if (resultCode != Activity.RESULT_OK) {
            result.success("cancelled")
            return true
        }
        val codes = if (Build.VERSION.SDK_INT >= 30) data?.getIntegerArrayListExtra(Settings.EXTRA_WIFI_NETWORK_RESULT_LIST) else null
        val code = codes?.firstOrNull()
        result.success(
            when (code) {
                // ALREADY_EXISTS: the network was saved before; the system joins it like a new one.
                null, Settings.ADD_WIFI_RESULT_SUCCESS, Settings.ADD_WIFI_RESULT_ALREADY_EXISTS -> "saved"
                else -> "failed"
            },
        )
        return true
    }

    // ---- Scan (WifiUtils.getWifiList) --------------------------------------------------------

    /** The system's last scan results; asks for NO scan (unlike [wifiList]) - the same entries, possibly older. */
    @SuppressLint("MissingPermission")
    private fun cachedWifiList(): List<Map<String, Any?>> {
        if (!wifi.isWifiEnabled) throw WifiError("Wi-Fi is disabled")
        if (!hasLocation()) throw WifiError("Location permission is required")
        return wifi.scanResults.sortedByDescending { it.level }.map { scanEntry(it) }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun wifiList(): List<Map<String, Any?>> {
        if (!wifi.isWifiEnabled) throw WifiError("Wi-Fi is disabled")
        if (!hasLocation()) throw WifiError("Location permission is required")
        wifi.startScan()
        return wifi.scanResults.sortedByDescending { it.level }.map { scanEntry(it) }
    }

    @SuppressLint("MissingPermission")
    private fun scanEntry(r: ScanResult): Map<String, Any?> =
        WifiMath.scanEntry(r.SSID, r.BSSID, r.capabilities ?: "", r.frequency, r.level, r.channelWidth)

    /**
     * A scan that waits for its own result. Requests a scan, waits (up to [timeoutMs]) for
     * SCAN_RESULTS_AVAILABLE and returns the results with their age and a `throttled` flag: Android
     * allows 4 foreground scans per 2 minutes and answers `startScan() == false` (or a broadcast with
     * EXTRA_RESULTS_UPDATED false) when it served the cache. [wifiList] is unchanged.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun scanFresh(timeoutMs: Long): Map<String, Any?> {
        if (!wifi.isWifiEnabled) throw WifiError("Wi-Fi is disabled")
        if (!hasLocation()) throw WifiError("Location permission is required")
        val latch = java.util.concurrent.CountDownLatch(1)
        val updated = java.util.concurrent.atomic.AtomicBoolean(false)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                updated.set(intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true) ?: true)
                latch.countDown()
            }
        }
        val filter = android.content.IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        var requested = false
        var got = false
        try {
            requested = wifi.startScan()
            if (requested) got = latch.await(timeoutMs.coerceIn(1_000L, 30_000L), TimeUnit.MILLISECONDS)
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
        val outcome = WifiMath.scanOutcome(requested = requested, gotBroadcast = got, updatedExtra = updated.get())
        val results = wifi.scanResults
        val newest = results.maxOfOrNull { it.timestamp / 1000L } // microseconds since boot -> ms
        return WifiMath.scanFresh(
            results = results.sortedByDescending { it.level }.map { scanEntry(it) },
            requested = outcome.requested,
            fresh = outcome.fresh,
            throttled = outcome.throttled,
            timedOut = outcome.timedOut,
            ageMs = WifiMath.ageMs(android.os.SystemClock.elapsedRealtime(), newest),
            nowEpochMs = System.currentTimeMillis(),
        )
    }

    // ---- Connection (IpInfoUtils.getIpInfo) --------------------------------------------------

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun ipInfo(): List<Map<String, Any?>> {
        val active = connectivity.activeNetworkInfo
        val info = wifi.connectionInfo
        val dhcp = wifi.dhcpInfo
        val location = location()
        val entry = WifiMath.ipInfoConnection(
            type = active?.typeName,
            rssi = info?.rssi ?: -1,
            linkSpeed = info?.linkSpeed ?: -1,
            ssid = info?.ssid,
            ip = localIp(),
            mac = info?.macAddress,
            networkId = info?.networkId ?: -1,
            freqMhz = info?.frequency ?: -1,
            bssid = info?.bssid,
            timezone = TimeZone.getDefault().id,
            dns1 = dhcp?.let { ip(it.dns1) },
            dns2 = dhcp?.let { ip(it.dns2) },
        )
        entry.putAll(location)
        return listOf(entry)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun location(): Map<String, Any?> {
        val out = WifiMath.unknownLocation()
        if (!hasLocation()) return out
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time } ?: return out
        out["latitude"] = loc.latitude
        out["longitude"] = loc.longitude
        if (!Geocoder.isPresent()) return out
        runCatching { Geocoder(context, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1) }
            .getOrNull()?.firstOrNull()?.let { a ->
                a.locality?.let { out["city"] = it }
                a.thoroughfare?.let { out["street"] = it }
                a.countryName?.let { out["country"] = it }
                a.subAdminArea?.let { out["region"] = it }
                a.postalCode?.let { out["zipcode"] = it }
                a.adminArea?.let { out["state"] = it }
            }
        return out
    }

    // ---- Network info (no location, no Geocoder) -------------------------------------------

    /** [NetworkInfoReader] only gets a ConnectivityManager: it cannot reach LocationManager or Geocoder. */
    private fun networkInfo(): Map<String, Any?> = NetworkInfoReader(connectivity).read()

    // ---- Router (WifiDetailsUtils.getAllWifiDetails) -----------------------------------------

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun allWifiDetails(): Map<String, Any?> {
        val info = wifi.connectionInfo
        val dhcp = wifi.dhcpInfo
        val freq = info?.frequency ?: -1
        return WifiMath.allWifiDetails(
            enabled = wifi.isWifiEnabled,
            ssid = info?.ssid,
            bssid = info?.bssid,
            ip = localIp() ?: info?.let { ip(it.ipAddress) },
            mac = info?.macAddress,
            networkId = info?.networkId ?: -1,
            linkSpeed = info?.linkSpeed ?: -1,
            level = WifiManager.calculateSignalLevel(info?.rssi ?: -100, 5),
            gateway = dhcp?.let { ip(it.gateway) },
            rssi = info?.rssi ?: -1,
            freqMhz = freq,
            dns1 = dhcp?.let { ip(it.dns1) },
            dns2 = dhcp?.let { ip(it.dns2) },
        )
    }

    private fun channel(f: Int) = WifiMath.channel(f)

    // ---- LAN sweep (getConnectedDevices) -----------------------------------------------------

    private fun connectedDevices(): List<Map<String, Any?>> {
        val own = localIp() ?: throw WifiError("Not connected to a network")
        val prefix = own.substringBeforeLast('.') + "."
        val pool = Executors.newFixedThreadPool(20)
        val found = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, InetAddress>>())
        for (i in 1..255) {
            pool.execute {
                runCatching {
                    val addr = InetAddress.getByName(prefix + i)
                    if (addr.isReachable(100)) found.add(i to addr)
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        return found.sortedBy { it.first }.map { (_, a) ->
            WifiMath.deviceEntry(
                a.hostAddress, a.hostName, a.isAnyLocalAddress, a.isLoopbackAddress, a.hostAddress,
                a.canonicalHostName, a.isMulticastAddress, a.isSiteLocalAddress,
            )
        }
    }

    // ---- LAN sweep with real progress (EventChannel community_flutter_wifi/connected_devices) ----------
    // Started ONLY by a Dart listen(), which the app does from a user's scan tap. Probes the device's own
    // private subnet (prefix length from LinkProperties, at most a /22), never a public range; one sweep at a time.

    private fun startSweep(maxHosts: Int, sink: EventChannel.EventSink) {
        val cancelled = sweepControl.listen(System.currentTimeMillis())
        if (cancelled == null) {
            sink.error("wifi", "A scan is running or was just done; try again in a few seconds", null)
            sink.endOfStream()
            return
        }
        io.execute {
            val post: (() -> Unit) -> Unit = { f -> main.post { if (!cancelled.get()) f() } }
            val dns = Executors.newCachedThreadPool()
            try {
                val link = (networkInfo()["wifi"] as? Map<*, *>)
                val ipv4 = link?.get("ipv4") as? String
                val prefix = (link?.get("prefixLength") as? Number)?.toInt()
                val targets = if (ipv4 != null && prefix != null) WifiMath.sweepTargets(ipv4, prefix, maxHosts) else null
                if (targets == null || prefix == null) {
                    post { sink.error("wifi", "Not connected to a private Wi-Fi network", null); sink.endOfStream() }
                    return@execute
                }
                val total = targets.size
                val progress = WifiMath.SweepProgress(total)
                val found = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, Map<String, Any?>>>())
                post { sink.success(WifiMath.progressEvent(0, total, 0, null)) }
                val pool = Executors.newFixedThreadPool(20)
                targets.forEachIndexed { index, host ->
                    pool.execute {
                        if (cancelled.get()) return@execute
                        var hit: String? = null
                        runCatching {
                            val addr = InetAddress.getByName(host)
                            if (addr.isReachable(100)) {
                                // The names are looked up HERE, with a timeout, before the probe counts as finished:
                                // 100% means the device list is complete.
                                found.add(index to deviceEntry(addr, dns))
                                hit = host
                            }
                        }
                        val answeredHost = hit
                        // Counted and posted under one lock: the main handler keeps the post order, so `done` only grows.
                        synchronized(progress) {
                            progress.complete(answeredHost)?.let { u ->
                                post { sink.success(WifiMath.progressEvent(u.done, total, u.answered, u.host)) }
                            }
                        }
                    }
                }
                pool.shutdown()
                if (!pool.awaitTermination(60, TimeUnit.SECONDS)) pool.shutdownNow()
                val devices = found.sortedBy { it.first }.map { it.second }
                val doneCount = progress.doneCount()
                val partial = WifiMath.sweepIsPartial(prefix, maxHosts)
                val effective = WifiMath.sweepEffectivePrefix(prefix, maxHosts)
                post { sink.success(WifiMath.doneEvent(doneCount, total, devices, partial, effective)); sink.endOfStream() }
            } catch (e: Exception) {
                post { sink.error("wifi", if (e is WifiError) e.message else WifiMath.platformErrorMessage(e), null); sink.endOfStream() }
            } finally {
                dns.shutdownNow()
                sweepControl.finish(System.currentTimeMillis())
            }
        }
    }

    /** One answering host as a result map; the reverse-DNS names have [NAME_TIMEOUT_MS] each, else the address is the name. */
    private fun deviceEntry(a: InetAddress, dns: java.util.concurrent.ExecutorService): Map<String, Any?> {
        fun name(read: () -> String): String = try {
            dns.submit<String> { read() }.get(NAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            a.hostAddress ?: ""
        }
        return WifiMath.deviceEntry(
            a.hostAddress, name { a.hostName }, a.isAnyLocalAddress, a.isLoopbackAddress, a.hostAddress,
            name { a.canonicalHostName }, a.isMulticastAddress, a.isSiteLocalAddress,
        )
    }

    // ---- Diagnostics (getNetworkDiagnostics) -------------------------------------------------

    private fun isConnectedToInternet(): Boolean {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun networkDiagnostics(): Map<String, Any?> {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val internet = isConnectedToInternet()
        val type = when {
            caps == null -> "None"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            onWifi -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "None"
        }
        val status = mapOf(
            "isConnectedToInternet" to internet,
            "isConnectedToWifi" to onWifi,
            "connectionType" to type,
            "isWifiEnabled" to wifi.isWifiEnabled,
        )
        if (!onWifi) {
            return mapOf(
                "connectionStatus" to status,
                "wifiDetails" to null,
                "performance" to mapOf("healthScore" to 0, "status" to "disconnected"),
            )
        }
        val info = wifi.connectionInfo
        val dhcp = wifi.dhcpInfo
        val rssi = info?.rssi ?: -100
        val link = info?.linkSpeed ?: 0
        val level = WifiManager.calculateSignalLevel(rssi, 5)
        val details = mapOf(
            "ssid" to info?.ssid?.removeSurrounding("\""),
            "bssid" to info?.bssid,
            "ipAddress" to (localIp() ?: info?.let { ip(it.ipAddress) }),
            "macAddress" to info?.macAddress,
            "linkSpeed" to link,
            "linkSpeedUnit" to "Mbps",
            "rssi" to rssi,
            "frequency" to (info?.frequency ?: -1),
            "channel" to channel(info?.frequency ?: -1),
            "gateway" to dhcp?.let { ip(it.gateway) },
            "dns1" to dhcp?.let { ip(it.dns1) },
            "dns2" to dhcp?.let { ip(it.dns2) },
            "networkId" to (info?.networkId ?: -1),
            "signalLevel" to level,
            "signalQuality" to WifiMath.signalQuality(level),
        )
        var score = when {
            rssi >= -50 -> 40
            rssi >= -60 -> 30
            rssi >= -70 -> 20
            else -> 10
        }
        score += when {
            link >= 100 -> 40
            link >= 50 -> 30
            link >= 20 -> 20
            else -> 10
        }
        if (internet) score += 20
        return mapOf(
            "connectionStatus" to status,
            "wifiDetails" to details,
            "performance" to mapOf(
                "healthScore" to score,
                "status" to when {
                    score >= 80 -> "excellent"
                    score >= 60 -> "good"
                    score >= 40 -> "fair"
                    else -> "poor"
                },
            ),
        )
    }

    // ---- Helpers -----------------------------------------------------------------------------

    private fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }.getOrNull()

    private fun ip(v: Int): String = WifiMath.ip(v)

    companion object {
        private const val REQUEST_LOCATION = 0x5717
        private const val REQUEST_ADD_NETWORK = 0x5718
        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val NAME_TIMEOUT_MS = 1_500L
    }
}
