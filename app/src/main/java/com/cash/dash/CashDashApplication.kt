package com.cash.dash

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log

import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import android.content.SharedPreferences

class CashDashApplication : Application(), DefaultLifecycleObserver {
    private var walletPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    override fun onCreate() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super<Application>.onCreate()
        
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        
        // Start Security Monitoring globally
        SecurityManager.startListening(this)
        
        // Migrate legacy data to Room if needed
        MigrationManager.checkAndMigrate(this)
        
        createNotificationChannel()
        com.google.firebase.messaging.FirebaseMessaging.getInstance().subscribeToTopic("all_users")
        
        val walletPrefs = getSharedPreferences("WalletPrefs", Context.MODE_PRIVATE)
        walletPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "wallet_balance" || key == "initial_balance" || key == "balance_bar_mode" || key == "balance_bar_type") {
                WalletWidget.pushUpdate(this)
            }
        }
        walletPrefs.registerOnSharedPreferenceChangeListener(walletPrefsListener)
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
        setupRealtimePresence(this)
        try {
            startService(Intent(this, TaskMonitorService::class.java))
        } catch (e: Exception) {
            Log.e("CashDashApplication", "Failed to start TaskMonitorService", e)
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        setOfflineImmediate(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == TRIM_MEMORY_UI_HIDDEN) {
            setOfflineImmediate(this)
        }
    }

    companion object {
        var presenceListener: com.google.firebase.database.ValueEventListener? = null
        var isDeletingAccount = false

        fun setupRealtimePresence(context: Context) {
            if (isDeletingAccount) return
            val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser ?: return
            val email = user.email ?: return
            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            
            val adminEmails = listOf("mohamedaufin64@gmail.com", "arunbhalaji200904@gmail.com")
            if (adminEmails.contains(email.lowercase())) {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().subscribeToTopic("admins")
            } else {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().unsubscribeFromTopic("admins")
            }
            
            val safeEmail = email.replace(".", ",")
            val rtdbStatusRef = com.google.firebase.database.FirebaseDatabase.getInstance().getReference("status").child(safeEmail)
            val connectedRef = com.google.firebase.database.FirebaseDatabase.getInstance().getReference(".info/connected")
            
            // Clean up any lingering listener from a previous session
            presenceListener?.let { connectedRef.removeEventListener(it) }
            
            presenceListener = object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    val connected = snapshot.getValue(Boolean::class.java) ?: false
                    if (connected) {
                        val disconnectData = mapOf(
                            "state" to "Offline",
                            "last_changed" to com.google.firebase.database.ServerValue.TIMESTAMP
                        )
                        rtdbStatusRef.onDisconnect().setValue(disconnectData)
                        
                        val onlineData = mapOf(
                            "state" to "Online",
                            "last_changed" to com.google.firebase.database.ServerValue.TIMESTAMP
                        )
                        rtdbStatusRef.setValue(onlineData)
                    }
                }
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
            }
            connectedRef.addValueEventListener(presenceListener!!)

            // Also immediately sync to Firestore directly as a fallback
            val appVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            } catch (e: Exception) { "unknown" }

            val todayStr = java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.ENGLISH).apply {
                timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
            }.format(java.util.Date())
            val name = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("user_name", "User") ?: "User"

            val updates = hashMapOf<String, Any>(
                "status" to "Online",
                "appVersion" to appVersion,
                "name" to name,
                "activeDates" to com.google.firebase.firestore.FieldValue.arrayUnion(todayStr)
            )
            val mergeOptions = com.google.firebase.firestore.SetOptions.merge()
            db.collection("users").document(email).set(updates, mergeOptions).addOnFailureListener { }
            db.collection("users").document(email).collection("config").document("profile").set(updates, mergeOptions).addOnFailureListener { }
        }

        fun setOfflineImmediate(context: Context) {
            if (isDeletingAccount) return
            val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser ?: return
            val email = user.email ?: return
            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()

            val safeEmail = email.replace(".", ",")
            val rtdbStatusRef = com.google.firebase.database.FirebaseDatabase.getInstance().getReference("status").child(safeEmail)
            val connectedRef = com.google.firebase.database.FirebaseDatabase.getInstance().getReference(".info/connected")

            // IMPORTANT: Tear down the listener so it doesn't accidentally mark this user online later!
            presenceListener?.let { connectedRef.removeEventListener(it) }
            presenceListener = null

            val updates = hashMapOf<String, Any>()
            val sdf = java.text.SimpleDateFormat("dd/MM/yyyy, h:mm a", java.util.Locale.ENGLISH)
            sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
            val lastActive = sdf.format(java.util.Date())
            updates["status"] = "Offline"
            updates["lastActiveTime"] = lastActive

            val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            prefs.edit().putString("lastActiveTime", lastActive).commit()

            rtdbStatusRef.setValue(mapOf("state" to "Offline", "last_changed" to com.google.firebase.database.ServerValue.TIMESTAMP))
            rtdbStatusRef.onDisconnect().cancel()

            db.collection("users").document(email).update(updates).addOnFailureListener { }
            db.collection("users").document(email).collection("config").document("profile").update(updates).addOnFailureListener { }
        }
    }
}
