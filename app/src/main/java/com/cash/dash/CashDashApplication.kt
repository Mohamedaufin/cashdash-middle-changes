package com.cash.dash

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log

import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class CashDashApplication : Application(), DefaultLifecycleObserver {
    override fun onCreate() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super<Application>.onCreate()
        
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        
        // Start service to handle app swipe-kill safely (try-catch for Android 12+ background restrictions)
        try {
            startService(android.content.Intent(this, AppKillService::class.java))
        } catch (e: Exception) {
            // Ignore if OS blocks background service start
        }
        
        // Start Security Monitoring globally
        SecurityManager.startListening(this)
        
        // Migrate legacy data to Room if needed
        MigrationManager.checkAndMigrate(this)
        
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "cashdash_urgent_heads_up_v10"
            val channelName = "Urgent Support (V10)"
            val importance = NotificationManager.IMPORTANCE_HIGH
            
            val channel = NotificationChannel(channelId, channelName, importance).apply {
                description = "Critical heads-up alerts for your support queries"
                enableLights(true)
                lightColor = Color.YELLOW
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                
                val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()
                setSound(defaultSoundUri, audioAttributes)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        updateUserStatus("Online")
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        updateUserStatus("Offline")
    }

    private fun updateUserStatus(status: String) {
        val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser ?: return
        val email = user.email ?: return
        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()

        val updates = hashMapOf<String, Any>()
        if (status == "Online") {
            updates["status"] = "Online"
        } else {
            val sdf = java.text.SimpleDateFormat("dd/MM/yyyy, hh:mm a", java.util.Locale.ENGLISH)
            sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
            val lastActive = sdf.format(java.util.Date())
            updates["status"] = "Offline"
            updates["lastActiveTime"] = lastActive

            val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            prefs.edit().putString("lastActiveTime", lastActive).apply()
        }

        db.collection("users").document(email).update(updates).addOnFailureListener { }
        db.collection("users").document(email).collection("config").document("profile").update(updates).addOnFailureListener { }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // TRIM_MEMORY_UI_HIDDEN fires INSTANTLY the exact millisecond the user 
        // presses the Home button or opens the Recents menu. 
        // This gives the app a crucial head-start to send the 'Offline' signal 
        // before they can physically swipe to kill the app.
        if (level == TRIM_MEMORY_UI_HIDDEN) {
            updateUserStatus("Offline")
        }
    }
}
