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

        // 0. Instantly check if the user was deleted from the Firebase Auth Console
        user.reload().addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                val exception = task.exception
                if (exception is com.google.firebase.auth.FirebaseAuthInvalidUserException) {
                    // User is deleted from Auth Console!
                    triggerLogout(appContext, "admin_deleted")
                }
            }
        }

        // 1. Monitor main profile status field (now under email)
        deletionListener = db.collection("users").document(email)
            .collection("config").document("profile")
            .addSnapshotListener { snapshot, e ->
                if (isTriggering) return@addSnapshotListener
                if (e != null) {
                    if (e is com.google.firebase.firestore.FirebaseFirestoreException && e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        user.reload().addOnCompleteListener { task ->
                            if (!task.isSuccessful) {
                                val exception = task.exception
                                if (exception is com.google.firebase.auth.FirebaseAuthInvalidUserException) {
                                    triggerLogout(appContext, "admin_deleted")
                                } else {
                                    triggerLogout(appContext, "session_expired")
                                }
                            }
                        }
                    }
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    val status = snapshot.getString("account_status") ?: "active"
                    if (status == "admin_deleted") {
                        triggerLogout(appContext, "admin_deleted")
                    }
                }
            }

        // 2. Monitor global deleted_accounts collection to catch admin deletions
        userDocListener = db.collection("deleted_accounts").document(email)
            .addSnapshotListener { snapshot, e ->
                if (isTriggering) return@addSnapshotListener
                if (e != null) {
                    if (e is com.google.firebase.firestore.FirebaseFirestoreException && e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        user.reload().addOnCompleteListener { task ->
                            if (!task.isSuccessful) {
                                val exception = task.exception
                                if (exception is com.google.firebase.auth.FirebaseAuthInvalidUserException) {
                                    triggerLogout(appContext, "admin_deleted")
                                } else {
                                    triggerLogout(appContext, "session_expired")
                                }
                            }
                        }
                    }
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    val status = snapshot.getString("status")
                    val deletedUid = snapshot.getString("uid")
                    
                    // Only apply the ban if the deleted UID matches the current user's UID.
                    // This allows them to register a new account (with a new UID) using the same email!
                    if (deletedUid == user.uid) {
                        if (status == "admin_deleted" || status == "ADMIN_DELETED") {
                            triggerLogout(appContext, "admin_deleted")
                        } else if (status != "PERMANENT_WIPE_COMPLETED") {
                            // Unknown deletion status, default to session expired
                            triggerLogout(appContext, "session_expired")
                        }
                    }
                }
            }
    }

    fun triggerLogout(context: Context, reason: String? = null) {
        if (isTriggering) return
        isTriggering = true

        stopListening()

        // 0. Stop automatic sync BEFORE clearing data
        FirestoreSyncManager.stopRealTimeSync(context)

        // 1. Clear all SharedPreferences
        val prefsToClear = listOf(
            "AppPrefs", "WalletPrefs", "WalletPrefs_v2", "CategoryPrefs",
            "GraphData", "CategoryWeekData", "MoneySchedulePrefs",
            "ScannerHistory", "LocalScanPrefs", "LocalScanPrefs_v2", "NotificationCache"
        )
        prefsToClear.forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply()
        }
        // Encrypted stores must be wiped through their own API: clearing them as
        // plain prefs deletes the keysets while a cached instance keeps the old
        // one in memory. See SecurePrefsStore.wipe.
        WalletStore.wipe(context)
        ScanStore.wipe(context)

        // 2. Sign out
        CashDashApplication.setOfflineImmediate(context)
        FirebaseAuth.getInstance().signOut()

        // 3. Redirect to IntroActivity with security notice
        val intent = Intent(context, IntroActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (reason != null) {
                putExtra("reason", reason)
            }
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
