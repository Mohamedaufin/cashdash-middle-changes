package com.cash.dash

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

class NotificationActionReceiver : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val notifId = intent.getIntExtra("notif_id", -1)
        val url = intent.getStringExtra("url")
        val promoId = intent.getStringExtra("promo_id")

        // Always cancel the notification first
        if (notifId != -1) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifId)
        }
        if (promoId != null) {
            val userEmail = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email
            if (userEmail != null) {
                com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    .collection("admin_logs").document(promoId)
                    .update("notif_clickers", com.google.firebase.firestore.FieldValue.arrayUnion(userEmail))
            }
        }

        // Open the URL in browser if provided
        if (!url.isNullOrEmpty()) {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(browserIntent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        finish()
    }
}
