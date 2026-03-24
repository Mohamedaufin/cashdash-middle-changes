package com.cash.dash

import android.content.Context
import android.content.Intent
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

object SecurityManager {
    private var deletionListener: ListenerRegistration? = null
    private var userDocListener: ListenerRegistration? = null
    private var isTriggering = false

    fun startListening(context: Context) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val email = user.email ?: return
        if (deletionListener != null || userDocListener != null) return

        val db = FirebaseFirestore.getInstance()
        val appContext = context.applicationContext

        // 1. Monitor main profile status field (now under email)
        deletionListener = db.collection("users").document(email)
            .collection("config").document("profile")
            .addSnapshotListener { snapshot, e ->
                if (e != null || isTriggering) return@addSnapshotListener
                if (snapshot != null && snapshot.exists()) {
                    val status = snapshot.getString("account_status") ?: "active"
                    if (status == "admin_deleted") {
                        triggerLogout(appContext)
                    }
                }
            }

        // 2. Monitor if the main user document itself is deleted (now under email)
        userDocListener = db.collection("users").document(email)
            .addSnapshotListener { snapshot, e ->
                if (isTriggering) return@addSnapshotListener
                
                // If the main document is gone, it means the account was wiped from the database
                if (snapshot != null && !snapshot.exists()) {
                    triggerLogout(appContext)
                }
            }
    }

    private fun triggerLogout(context: Context) {
        if (isTriggering) return
        isTriggering = true
        
        stopListening()
        
        // 0. Stop automatic sync BEFORE clearing data
        FirestoreSyncManager.stopRealTimeSync(context)

        // 1. Clear all SharedPreferences
        val prefsToClear = listOf(
            "AppPrefs", "WalletPrefs", "CategoryPrefs", 
            "GraphData", "CategoryWeekData", "MoneySchedulePrefs", 
            "ScannerHistory", "LocalScanPrefs", "NotificationCache"
        )
        prefsToClear.forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply()
        }

        // 2. Sign out
        FirebaseAuth.getInstance().signOut()

        // 3. Redirect to EntryActivity with security notice
        val intent = Intent(context, EntryActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("reason", "admin_deleted")
        }
        context.startActivity(intent)
    }

    fun stopListening() {
        deletionListener?.remove()
        userDocListener?.remove()
        deletionListener = null
        userDocListener = null
    }
}
