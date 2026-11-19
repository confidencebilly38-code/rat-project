package com.rat.client.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.rat.client.crypto.EncryptionUtils

class PreferencesManager private constructor() {

    companion object {
        @Suppress("UNUSED_PARAMETER")
        private const val TAG = "PreferencesManager"
        private const val PREFS_NAME = "rat_preferences"
        private const val SECURE_PREFS_NAME = "rat_secure_preferences"
        private var standardPrefs: SharedPreferences? = null
        private var securePrefs: SharedPreferences? = null
        private var initialized = false

        @JvmStatic
        fun init(context: Context) {
            if (initialized) return
            standardPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                securePrefs = EncryptedSharedPreferences.create(
                    context,
                    SECURE_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                // Fallback to standard prefs
                securePrefs = standardPrefs
            }
            initialized = true
        }

        private fun getStandard(): SharedPreferences = standardPrefs ?: throw IllegalStateException("Not initialized")
        private fun getSecure(): SharedPreferences = securePrefs ?: throw IllegalStateException("Not initialized")

        // ===== String =====

        @JvmStatic
        fun putString(key: String, value: String, secure: Boolean = false) {
            val prefs = if (secure) getSecure() else getStandard()
            prefs.edit().putString(key, value).apply()
        }

        @JvmStatic
        fun getString(key: String, defaultValue: String = "", secure: Boolean = false): String {
            val prefs = if (secure) getSecure() else getStandard()
            return prefs.getString(key, defaultValue) ?: defaultValue
        }

        // ===== Int =====

        @JvmStatic
        fun putInt(key: String, value: Int, secure: Boolean = false) {
            val prefs = if (secure) getSecure() else getStandard()
            prefs.edit().putInt(key, value).apply()
        }

        @JvmStatic
        fun getInt(key: String, defaultValue: Int = 0, secure: Boolean = false): Int {
            val prefs = if (secure) getSecure() else getStandard()
            return prefs.getInt(key, defaultValue)
        }

        // ===== Long =====

        @JvmStatic
        fun putLong(key: String, value: Long, secure: Boolean = false) {
            val prefs = if (secure) getSecure() else getStandard()
            prefs.edit().putLong(key, value).apply()
        }

        @JvmStatic
        fun getLong(key: String, defaultValue: Long = 0L, secure: Boolean = false): Long {
            val prefs = if (secure) getSecure() else getStandard()
            return prefs.getLong(key, defaultValue)
        }

        // ===== Float =====

        @JvmStatic
        fun putFloat(key: String, value: Float, secure: Boolean = false) {
            val prefs = if (secure) getSecure() else getStandard()
            prefs.edit().putFloat(key, value).apply()
        }

        @JvmStatic
        fun getFloat(key: String, defaultValue: Float = 0f, secure: Boolean = false): Float {
            val prefs = if (secure) getSecure() else getStandard()
            return prefs.getFloat(key, defaultValue)
        }

        // ===== Boolean =====

        @JvmStatic
        fun putBoolean(key: String, value: Boolean, secure: Boolean = false) {
            val prefs = if (secure) getSecure() else getStandard()
            prefs.edit().putBoolean(key, value).apply()
        }

        @JvmStatic
        fun getBoolean(key: String, defaultValue: Boolean = false, secure: Boolean = false): Boolean {
            val prefs = if (secure) getSecure() else getStandard()
            return prefs.getBoolean(key, defaultValue)
        }

        // ===== String Set =====

        @JvmStatic
        fun putStringSet(key: String, value: Set<String>, secure: Boolean = false) {
            val prefs = if (secure) getSecure() else getStandard()
            prefs.edit().putStringSet(key, value).apply()
        }

        @JvmStatic
        fun getStringSet(key: String, defaultValue: Set<String> = emptySet(), secure: Boolean = false): Set<String> {
            val prefs = if (secure) getSecure() else getStandard()
            return prefs.getStringSet(key, defaultValue) ?: defaultValue
        }

        // ===== Remove =====

        @JvmStatic
        fun remove(key: String) {
            getStandard().edit().remove(key).apply()
            getSecure().edit().remove(key).apply()
        }

        // ===== Contains =====

        @JvmStatic
        fun contains(key: String): Boolean {
            return getStandard().contains(key) || getSecure().contains(key)
        }

        // ===== Clear =====

        @JvmStatic
        fun clearAll() {
            getStandard().edit().clear().apply()
            getSecure().edit().clear().apply()
        }

        // ===== Get All =====

        @JvmStatic
        fun getAllStandard(): Map<String, Any> = getStandard().all
        @JvmStatic
        fun getAllSecure(): Map<String, Any> = getSecure().all

        // ===== Encrypted Value Storage =====

        @JvmStatic
        fun putEncrypted(key: String, value: String) {
            val encrypted = EncryptionUtils.encrypt(value)
            putString(key, encrypted, true)
        }

        @JvmStatic
        fun getEncrypted(key: String, defaultValue: String = ""): String {
            val encrypted = getString(key, "", true)
            return if (encrypted.isNotEmpty()) {
                try {
                    EncryptionUtils.decrypt(encrypted)
                } catch (e: Exception) {
                    defaultValue
                }
            } else defaultValue
        }

        // ===== JSON Object Storage =====

        @JvmStatic
        fun putJson(key: String, json: String, secure: Boolean = true) {
            putString(key, json, secure)
        }

        @JvmStatic
        fun getJson(key: String, secure: Boolean = true): String? {
            return getString(key, "", secure).takeIf { it.isNotEmpty() }
        }

        // ===== Migration Helpers =====

        @JvmStatic
        fun migrateToSecure(keys: List<String>) {
            val editor = getSecure().edit()
            for (key in keys) {
                val value = getStandard().getString(key, null)
                if (value != null) {
                    editor.putString(key, value)
                    getStandard().edit().remove(key).apply()
                }
            }
            editor.apply()
        }

        @JvmStatic
        fun backupToFile(context: Context, filePath: String): Boolean {
            return try {
                val allData = mutableMapOf<String, Any>()
                allData.putAll(getAllStandard())
                allData.putAll(getAllSecure())
                val json = com.google.gson.Gson().toJson(allData)
                java.io.File(filePath).writeText(json)
                true
            } catch (e: Exception) {
                false
            }
        }

        @JvmStatic
        fun restoreFromFile(context: Context, filePath: String): Boolean {
            return try {
                val json = java.io.File(filePath).readText()
                val data = com.google.gson.Gson().fromJson(json, Map<String, Any>::class.java)
                val stdEditor = getStandard().edit()
                val secEditor = getSecure().edit()
                data.forEach { (key, value) ->
                    when (value) {
                        is String -> {
                            // Decide based on key prefix
                            if (key.startsWith("secure_") || key.contains("password") || key.contains("token") || key.contains("key")) {
                                secEditor.putString(key, value)
                            } else {
                                stdEditor.putString(key, value)
                            }
                        }
                        is Int -> stdEditor.putInt(key, value)
                        is Long -> stdEditor.putLong(key, value)
                        is Float -> stdEditor.putFloat(key, value)
                        is Boolean -> stdEditor.putBoolean(key, value)
                        is Set<*> -> stdEditor.putStringSet(key, value as Set<String>)
                    }
                }
                stdEditor.apply()
                secEditor.apply()
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}