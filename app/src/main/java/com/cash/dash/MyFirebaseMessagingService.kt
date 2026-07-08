package com.cash.dash

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import android.graphics.Color
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        saveGlobalToken(token)
    }

    private fun saveGlobalToken(token: String) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val email = user.email ?: return 
        val db = FirebaseFirestore.getInstance()
        val data = hashMapOf("fcmToken" to token, "email" to email)
        db.collection("users").document(email)
            .set(data, com.google.firebase.firestore.SetOptions.merge())
        // Also save to fcmTokens collection for targeted push lookups
        db.collection("fcmTokens").document(email)
            .set(hashMapOf("token" to token, "email" to email), com.google.firebase.firestore.SetOptions.merge())
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val action = remoteMessage.data["action"]
        if (action == "force_logout") {
            SecurityManager.triggerLogout(applicationContext)
            return
        }

        val title = remoteMessage.notification?.title ?: remoteMessage.data["title"] ?: "CashDash Support"
        val body = remoteMessage.notification?.body ?: remoteMessage.data["body"] ?: ""
        
        val imageUrl = remoteMessage.data["imageUrl"]
        val triggerUrl = remoteMessage.data["triggerUrl"]
        val triggerText = remoteMessage.data["triggerText"]

        sendNotification(title, body, imageUrl, triggerUrl, triggerText)
    }

    private fun sendNotification(title: String, messageBody: String, imageUrl: String?, triggerUrl: String?, triggerText: String?) {
        val safeUrl = if (!triggerUrl.isNullOrEmpty()) {
            if (!triggerUrl.startsWith("http://") && !triggerUrl.startsWith("https://")) "https://$triggerUrl" else triggerUrl
        } else null

        val notifId = System.currentTimeMillis().toInt()
        
        // If triggerUrl exists, clicking the notification opens the website directly and cancels itself
        val pendingIntent = if (!safeUrl.isNullOrEmpty()) {
            val intent = Intent(this, NotificationActionReceiver::class.java).apply {
                action = "com.cash.dash.ACTION_OPEN_URL"
                putExtra("url", safeUrl)
                putExtra("notif_id", notifId)
            }
            PendingIntent.getActivity(
                this, notifId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            val intent = Intent(this, NotificationActivity::class.java).apply {
                action = "NotificationActivity"
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            PendingIntent.getActivity(
                this, notifId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        // 🔥 Ultimate Channel Rotation (v10) - Forces a Fresh Start
        val channelId = "cashdash_urgent_heads_up_v10"
        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_bell)
            .setLargeIcon(android.graphics.BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentTitle(title)
            .setContentText(messageBody)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX)      // Max for Heads-Up
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .setOnlyAlertOnce(true)                           // Master fix for sound persistence

        if (!imageUrl.isNullOrEmpty()) {
            try {
                val url = java.net.URL(imageUrl)
                var bitmap = android.graphics.BitmapFactory.decodeStream(url.openConnection().getInputStream())
                
                // Crop to 16:9 aspect ratio to ensure it fills the width on Android 12+
                val targetRatio = 16f / 9f
                val currentRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                
                if (currentRatio < targetRatio) {
                    // Image is too tall, crop top and bottom
                    val targetHeight = (bitmap.width / targetRatio).toInt()
                    val startY = (bitmap.height - targetHeight) / 2
                    bitmap = android.graphics.Bitmap.createBitmap(bitmap, 0, startY, bitmap.width, targetHeight)
                } else if (currentRatio > targetRatio) {
                    // Image is too wide, crop sides
                    val targetWidth = (bitmap.height * targetRatio).toInt()
                    val startX = (bitmap.width - targetWidth) / 2
                    bitmap = android.graphics.Bitmap.createBitmap(bitmap, startX, 0, targetWidth, bitmap.height)
                }

                // Scale up if it's too small to ensure it stretches
                if (bitmap.width < 1080) {
                    val scale = 1080f / bitmap.width
                    val matrix = android.graphics.Matrix()
                    matrix.postScale(scale, scale)
                    bitmap = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                }

                notificationBuilder.setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(bitmap)
                        .bigLargeIcon(null as android.graphics.Bitmap?)
                        .setSummaryText(messageBody)
                )
            } catch (e: Exception) {
                notificationBuilder.setStyle(NotificationCompat.BigTextStyle().bigText(messageBody))
            }
        } else {
            notificationBuilder.setStyle(NotificationCompat.BigTextStyle().bigText(messageBody))
        }
        if (!safeUrl.isNullOrEmpty()) {
            val actionIntent = Intent(this, NotificationActionReceiver::class.java).apply {
                action = "com.cash.dash.ACTION_OPEN_URL"
                putExtra("url", safeUrl)
                putExtra("notif_id", notifId)
            }
            val actionPendingIntent = PendingIntent.getActivity(
                this, notifId + 1, actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val btnText = if (!triggerText.isNullOrEmpty()) triggerText else "Open Website"
            notificationBuilder.addAction(R.drawable.ic_notif_open_url, "\u2197  $btnText", actionPendingIntent)
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Urgent Support (V10)",
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "Critical heads-up alerts for your support queries"
            channel.enableLights(true)
            channel.lightColor = Color.YELLOW
            channel.enableVibration(true)
            channel.vibrationPattern = longArrayOf(0, 500, 200, 500)
            channel.setShowBadge(true)
            channel.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            
            // 🚨 Sound enforcement on the channel level (vital for heads-up)
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build()
            channel.setSound(defaultSoundUri, audioAttributes)
            
            notificationManager.createNotificationChannel(channel)
        }

        notificationManager.notify(notifId, notificationBuilder.build())
    }
}
