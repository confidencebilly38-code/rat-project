package com.minimal.rat.util

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager private constructor() {
    companion object {
        private const val PREFS_NAME = "rat_prefs"
        private var prefs: SharedPreferences? = null

        fun init(context: Context) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        private val p: SharedPreferences get() = prefs ?: throw IllegalStateException("Not initialized")

        fun putString(key: String, value: String) = p.edit().putString(key, value).apply()
        fun getString(key: String, def: String = "") = p.getString(key, def) ?: def

        fun putInt(key: String, value: Int) = p.edit().putInt(key, value).apply()
        fun getInt(key: String, def: Int = 0) = p.getInt(key, def)

        fun putBoolean(key: String, value: Boolean) = p.edit().putBoolean(key, value).apply()
        fun getBoolean(key: String, def: Boolean = false) = p.getBoolean(key, def)
    }
}