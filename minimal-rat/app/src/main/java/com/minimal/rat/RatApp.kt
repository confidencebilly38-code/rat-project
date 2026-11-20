package com.minimal.rat

import android.app.Application
import android.content.Context

class RatApp : Application() {
    companion object {
        @Suppress("UNUSED_PARAMETER")
        private var instance: RatApp? = null
        fun getInstance(): RatApp? = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
    }
}