package com.cash.dash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

class PaymentRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val prefs = context.getSharedPreferences("PendingTransactionPrefs", Context.MODE_PRIVATE)
        
        if (action == "com.cash.dash.PAYMENT_YES") {
            // Retrieve pending transaction details
            val amountStr = prefs.getString("pending_amount", "0") ?: "0"
            val amount = amountStr.toDoubleOrNull()?.toInt() ?: 0
            val category = prefs.getString("pending_category", "no choice") ?: "no choice"
            val title = prefs.getString("pending_title", "") ?: ""
            val upiId = prefs.getString("pending_upi", "") ?: ""
            
            if (amount > 0) {
                // Write to History (This also deducts wallet balance internally)
                HistoryDataManager.saveTransaction(context, title, amount.toFloat(), category, System.currentTimeMillis())
                
                // Sync to Cloud
                FirestoreSyncManager.pushAllDataToCloud(context)
            }
        }
        
        // Regardless of Yes or No, clear the pending state and cancel notification
        prefs.edit().clear().apply()
        NotificationManagerCompat.from(context).cancel(999)
        androidx.work.WorkManager.getInstance(context).cancelUniqueWork("upi_recovery_notification_work")
    }
}
