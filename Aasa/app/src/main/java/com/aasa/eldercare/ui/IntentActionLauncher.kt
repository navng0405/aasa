package com.aasa.eldercare.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast

/**
 * Helpers that launch external apps for "real" trusted-contact actions.
 *
 * Phase 7 design rules baked in here:
 *  - We only ever use [Intent.ACTION_DIAL] (never `ACTION_CALL`) so we
 *    do not require the `CALL_PHONE` permission and we do not auto-dial.
 *  - SMS uses `ACTION_SENDTO` with the `smsto:` scheme so the user
 *    still has to press send inside their messaging app.
 *  - Every launcher catches [ActivityNotFoundException] and shows a
 *    small toast instead of crashing on emulators / devices without a
 *    dialer or messaging app.
 *
 * No state lives in this file – callers pass a [Context] (typically
 * `LocalContext.current`).
 */
object IntentActionLauncher {

    private const val TAG = "IntentActionLauncher"

    /**
     * Open the system dialer pre-populated with [phoneNumber]. The
     * elder still has to tap the green call button. Safe with no
     * runtime permissions.
     */
    fun openDialer(context: Context, phoneNumber: String) {
        val cleaned = phoneNumber.trim()
        if (cleaned.isBlank()) {
            toast(context, "No phone number available.")
            return
        }
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:${Uri.encode(cleaned)}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No dialer found for $cleaned", e)
            toast(context, "No dialer app found on this device.")
        }
    }

    /**
     * Open the system SMS app addressed to [phoneNumber] with [body]
     * pre-filled. Uses `ACTION_SENDTO` + `smsto:` so non-SMS apps
     * (email, share sheet, etc.) are excluded from the chooser.
     */
    fun openSms(context: Context, phoneNumber: String, body: String) {
        val cleaned = phoneNumber.trim()
        if (cleaned.isBlank()) {
            toast(context, "No phone number available.")
            return
        }
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:${Uri.encode(cleaned)}")
            putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No SMS app found for $cleaned", e)
            toast(context, "No messaging app found on this device.")
        }
    }

    /**
     * Send [body] directly to [phoneNumber]. Callers must hold
     * `android.permission.SEND_SMS` before invoking this.
     */
    @Suppress("DEPRECATION")
    fun sendSmsDirect(context: Context, phoneNumber: String, body: String): Boolean {
        val cleaned = phoneNumber.trim()
        if (cleaned.isBlank()) {
            toast(context, "No phone number available.")
            return false
        }
        return try {
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(body)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(cleaned, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(cleaned, null, body, null, null)
            }
            toast(context, "Emergency alert sent.")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing SMS permission for $cleaned", e)
            toast(context, "SMS permission is needed to send automatically.")
            false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Could not send SMS to $cleaned", e)
            toast(context, "Could not send SMS. Check the phone number.")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Could not send SMS to $cleaned", e)
            toast(context, "Could not send SMS on this device.")
            false
        }
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
