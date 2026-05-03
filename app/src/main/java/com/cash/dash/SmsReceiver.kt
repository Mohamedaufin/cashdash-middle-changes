package com.cash.dash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {

        if(intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        val fullText = messages.joinToString("") { it.messageBody }.lowercase()

        // 🔥 IMPROVED UPI PAYMENT DETECTION & LOGGING
        if ("upi" in fullText && ("debited" in fullText || "sent" in fullText || "paid" in fullText)) {
            val amountRegex = Regex("(?:rs\\.?|inr)\\s?([\\d,]+\\.?\\d*)")
            val match = amountRegex.find(fullText)
            val amount = match?.groupValues?.get(1)?.replace(",", "")?.toFloatOrNull() ?: 0f

            if (amount > 0f) {
                // Determine a generic title or use sender info if available
                val title = "UPI Payment (Auto-detected)"
                HistoryDataManager.saveTransaction(context, title, amount, "Overall")
                ToastHelper.showToast(context, "✔ Paid ₹$amount (Logged)", Toast.LENGTH_LONG)
            } else {
                ToastHelper.showToast(context, "✔ Transaction Detected", Toast.LENGTH_LONG)
            }

            // Return → MainActivity to refresh UI
            val i = Intent(context, MainActivity::class.java)
            i.putExtra("payment_status", "success")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(i)
        }
    }
}
