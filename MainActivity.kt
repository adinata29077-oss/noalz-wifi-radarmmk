package com.noalz.wifiradar

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.webkit.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private val pool = Executors.newFixedThreadPool(48)
    @Volatile private var scanning = false
    private val wm by lazy { applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        push("perm", JSONObject().put("ok", hasPerm()))
    }
    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = sendWifi(false)
    }

    private fun perms() = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun hasPerm() = perms().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    private fun push(type: String, d: JSONObject) = runOnUiThread {
        web.evaluateJavascript("onNative('$type',${JSONObject.quote(d.toString())})", null)
    }
    private fun ipStr(v: Int) = "${(v ushr 24) and 255}.${(v ushr 16) and 255}.${(v ushr 8) and 255}.${v and 255}"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        web = WebView(this).also { setContentView(it) }
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
        }
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest) = loader.shouldInterceptRequest(r.url)
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = r.url.host != "appassets.androidplatform.net"
        }
        web.addJavascriptInterface(Bridge(), "Android")
        ContextCompat.registerReceiver(this, scanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    override fun onDestroy() { unregisterReceiver(scanReceiver); pool.shutdownNow(); super.onDestroy() }

    data class Net(val name: String, val ip: Int, val prefix: Int)
    private fun findNet(): Net? {
        val all = ArrayList<Net>()
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
            if (!ni.isUp || ni.isLoopback || !Regex("^(wlan|ap|swlan|softap|rndis|eth).*").matches(ni.name)) return@forEach
            ni.interfaceAddresses.forEach { ia ->
                val a = ia.address
                if (a is Inet4Address && a.isSiteLocalAddress) {
                    val by = a.address
                    all.add(Net(ni.name, ((by[0].toInt() and 255) shl 24) or ((by[1].toInt() and 255) shl 16) or ((by[2].toInt() and 255) shl 8) or (by[3].toInt() and 255), ia.networkPrefixLength.toInt()))
                }
            }
        }
        return all.firstOrNull { isHotspot(it.name) } ?: all.firstOrNull()
    }
    private fun isHotspot(n: String) = Regex("^(ap|swlan|softap|rndis).*").matches(n)

    private fun sendWifi(throttled: Boolean) {
        val arr = JSONArray()
        try {
            wm.scanResults.forEach { r ->
                val c = r.capabilities
                val type = when { c.contains("SAE") -> "WPA3"; c.contains("WPA2") -> "WPA2"; c.contains("WPA") -> "WPA"; c.contains("WEP") -> "WEP"; else -> "OPEN" }
                arr.put(JSONObject().put("ssid", r.SSID).put("bssid", r.BSSID).put("rssi", r.level).put("freq", r.frequency).put("type", type))
            }
        } catch (e: SecurityException) { push("wifi", JSONObject().put("error", "perm")); return }
        push("wifi", JSONObject().put("list", arr).put("throttled", throttled))
    }

    inner class Bridge {
        @JavascriptInterface fun hasPerms() = hasPerm()
        @JavascriptInterface fun requestPerms() { runOnUiThread { if (hasPerm()) push("perm", JSONObject().put("ok", true)) else permLauncher.launch(perms()) } }
        @JavascriptInterface fun wifiInfo() {
            val o = JSONObject().put("enabled", wm.isWifiEnabled)
            try {
                val ci = wm.connectionInfo
                val s = ci?.ssid?.trim('"')
                if (s != null && s != "<unknown ssid>") o.put("ssid", s)
                if (ci != null && ci.networkId != -1) o.put("rssi", ci.rssi).put("freq", ci.frequency)
                val gw = wm.dhcpInfo?.gateway ?: 0
                if (gw != 0) o.put("gw", ipStr(Integer.reverseBytes(gw)))
            } catch (_: Exception) {}
            findNet()?.let { o.put("iface", it.name).put("ip", ipStr(it.ip)).put("mode", if (isHotspot(it.name)) "hotspot" else "wifi") }
            push("info", o)
        }
        @JavascriptInterface fun scanWifi() {
            if (!hasPerm()) { push("wifi", JSONObject().put("error", "perm")); return }
            if (!wm.isWifiEnabled) { push("wifi", JSONObject().put("error", "wifi_off")); return }
            val ok = try { wm.startScan() } catch (e: SecurityException) { false }
            if (!ok) sendWifi(true)
        }
        @JavascriptInterface fun scanDevices() {
            if (scanning) return
            val n = try { findNet() } catch (e: Exception) { null }
            if (n == null) { push("devices", JSONObject().put("error", "no_net")); return }
            scanning = true
            Thread {
                try {
                    val p = n.prefix.coerceIn(22, 30)
                    val mask = -1 shl (32 - p)
                    val net = n.ip and mask
                    val bc = net or mask.inv()
                    val found = java.util.Collections.synchronizedList(ArrayList<JSONObject>())
                    val latch = CountDownLatch(bc - net - 1)
                    for (h in net + 1 until bc) {
                        if (h == n.ip) { latch.countDown(); continue }
                        pool.execute {
                            try {
                                val ip = ipStr(h)
                                val a = InetAddress.getByName(ip)
                                if (a.isReachable(600)) {
                                    val hn = a.canonicalHostName
                                    val o = JSONObject().put("ip", ip)
                                    if (hn != ip) o.put("host", hn)
                                    found.add(o)
                                }
                            } catch (_: Exception) {} finally { latch.countDown() }
                        }
                    }
                    latch.await(45, TimeUnit.SECONDS)
                    val macs = HashMap<String, String>()
                    try {
                        File("/proc/net/arp").readLines().drop(1).forEach { l ->
                            val t = l.split(Regex("\\s+"))
                            if (t.size >= 4 && t[3] != "00:00:00:00:00:00") macs[t[0]] = t[3]
                        }
                    } catch (_: Exception) {}
                    val arr = JSONArray()
                    synchronized(found) { found.forEach { o -> macs[o.getString("ip")]?.let { o.put("mac", it) }; arr.put(o) } }
                    push("devices", JSONObject().put("list", arr).put("self", ipStr(n.ip)).put("iface", n.name)
                        .put("mode", if (isHotspot(n.name)) "hotspot" else "wifi").put("macAvailable", macs.isNotEmpty()))
                } catch (e: Exception) {
                    push("devices", JSONObject().put("error", "fail"))
                } finally { scanning = false }
            }.start()
        }
    }
}
