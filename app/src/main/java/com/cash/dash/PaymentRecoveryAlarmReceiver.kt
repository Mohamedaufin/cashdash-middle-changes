package com.cash.dash

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Locale

class PaymentRecoveryAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("PendingTransactionPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("has_pending", false)) {
            // Transaction was successful and cleared normally, no need to notify
            return
        }

        val amount = prefs.getString("pending_amount", "0") ?: "0"
        val upiId = prefs.getString("pending_upi", "") ?: ""
        val title = prefs.getString("pending_title", "") ?: ""
        val formattedTitle = title.replace("(?i)^To:\\s*".toRegex(), "").split(" ").joinToString(" ") { word -> 
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } 
        }
        val displayName = if (formattedTitle.isNotBlank()) formattedTitle else upiId

        val channelId = "cashdash_urgent_heads_up_v10"

        // Ensure channel exists — required on Android 8+, otherwise notification is silently dropped
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(channelId) == null) {
                val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val channel = NotificationChannel(
                    channelId, "Urgent Support (V10)", NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Critical heads-up alerts for your support queries"
                    enableLights(true); lightColor = Color.YELLOW
                    enableVibration(true); vibrationPattern = longArrayOf(0, 500, 200, 500)
                    setShowBadge(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    setSound(defaultSoundUri, AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
                }
                nm.createNotificationChannel(channel)
            }
        }

        val yesIntent = Intent(context, PaymentRecoveryReceiver::class.java).apply {
            action = "com.cash.dash.PAYMENT_YES"
        }
        val yesPendingIntent = PendingIntent.getBroadcast(
            context, 1, yesIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val noIntent = Intent(context, PaymentRecoveryReceiver::class.java).apply {
            action = "com.cash.dash.PAYMENT_NO"
        }
        val noPendingIntent = PendingIntent.getBroadcast(
            context, 2, noIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("Payment Interrupted?")
            .setContentText("Did your payment of ₹$amount to $displayName succeed?")
            .setPriority(NotificationCompat.PRIORITY_MAX) // Max priority for immediate heads-up
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setAutoCancel(true)
            .addAction(0, "Payment Success", yesPendingIntent)
            .addAction(0, "Payment Failed", noPendingIntent)

        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(context).notify(999, builder.build())
        }
    }
}
