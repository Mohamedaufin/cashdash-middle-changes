package com.cash.dash

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AppKillService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // We only want this service to run while the app is alive to catch the kill event
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        
        val user = FirebaseAuth.getInstance().currentUser
        val email = user?.email
        if (email != null) {
            val db = FirebaseFirestore.getInstance()
            val updates = hashMapOf<String, Any>()
            val sdf = SimpleDateFormat("dd/MM/yyyy, hh:mm a", Locale.ENGLISH)
            sdf.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
            val lastActive = sdf.format(Date())
            updates["status"] = "Offline"
            updates["lastActiveTime"] = lastActive

            // We use a CountDownLatch to block the destruction of this service for 
            // just a maximum of 1.5 seconds to guarantee the Firebase network requests 
            // complete before Android permanently kills our process memory.
            val latch = CountDownLatch(2)
            
            db.collection("users").document(email).update(updates)
                .addOnCompleteListener { latch.countDown() }
            
            db.collection("users").document(email).collection("config").document("profile").update(updates)
                .addOnCompleteListener { latch.countDown() }
            
            try {
                latch.await(1500, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                // Ignore interruption
            }
        }
        
        stopSelf()
    }
}
