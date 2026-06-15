@file:Suppress("DEPRECATION")
package com.cash.dash

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.animation.AlphaAnimation
import android.view.animation.ScaleAnimation
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.os.Build
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent

import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore


class MenuActivity : ThemedActivity() {

    private val PREFS = "WalletPrefs"
    private val KEY_BALANCE = "wallet_balance"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)

        // Load User Profile
        val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val userName = appPrefs.getString("user_name", "User")
        val tvUserName = findViewById<TextView>(R.id.tvUserName)
        tvUserName.text = userName

        // Buttons / Items
        val btnClose = findViewById<View>(R.id.btnCloseMenu)
        val btnBalance = findViewById<View>(R.id.btnBalanceBar)
        val btnUpdateSchedule = findViewById<View>(R.id.btnUpdateSchedule)
        val btnTheme = findViewById<View>(R.id.btnTheme)
        val btnPrivacyPolicy = findViewById<View>(R.id.btnPrivacyPolicy)
        val layoutProfileHeader = findViewById<View>(R.id.layoutProfileHeader)

        btnClose.setOnClickListener { finish() }

        btnBalance.setOnClickListener {
            startActivity(Intent(this, BalanceSetupActivity::class.java))
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }



        btnUpdateSchedule.setOnClickListener {
            startActivity(Intent(this, MoneyScheduleActivity::class.java))
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }

        btnTheme.setOnClickListener {
            startActivity(Intent(this, ThemeActivity::class.java))
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }

        val btnProfileOptions = findViewById<View>(R.id.btnProfileOptions)

        layoutProfileHeader.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }

        val user = FirebaseAuth.getInstance().currentUser
        val email = user?.email
        val adminEmails = listOf("mohamedaufin64@gmail.com", "arunbhalaji200904@gmail.com")
        val isAdmin = email != null && adminEmails.contains(email.lowercase())

        if (isAdmin) {
            layoutProfileHeader.isHapticFeedbackEnabled = true
            layoutProfileHeader.setOnLongClickListener {
                startActivity(Intent(this, AdminActivity::class.java))
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                true
            }
        } else {
            layoutProfileHeader.isHapticFeedbackEnabled = false
            layoutProfileHeader.isLongClickable = false
            layoutProfileHeader.setOnLongClickListener(null)
        }

        btnProfileOptions.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }

        btnPrivacyPolicy.setOnClickListener {
            val intent = Intent(this, WebViewActivity::class.java)
            intent.putExtra("url", "https://www.cashdash.co.in/privacy?source=app")
            intent.putExtra("title", "Privacy Policy")
            startActivity(intent)
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }

        // Help & Support
        val btnHelpSupport = findViewById<View>(R.id.btnHelpSupport)
        btnHelpSupport.setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }

        // Notifications
        val btnNotifications = findViewById<View>(R.id.btnNotifications)
        val notificationBadge = findViewById<View>(R.id.notificationBadge)
        btnNotifications.setOnClickListener {
            notificationBadge.visibility = android.view.View.GONE
            startActivity(Intent(this, NotificationActivity::class.java))
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }

        setupNotificationListener(notificationBadge)
    }

    private var queriesListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var announcementsListener: com.google.firebase.firestore.ListenerRegistration? = null

    private fun setupNotificationListener(badge: android.view.View) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val email = user.email ?: getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("user_email", null) ?: return
        val db = FirebaseFirestore.getInstance()

        var hasUnreadReply = false
        var hasUnreadAnnouncement = false

        fun updateBadgeVisibility() {
            badge.visibility = if (hasUnreadReply || hasUnreadAnnouncement) android.view.View.VISIBLE else android.view.View.GONE
        }

        queriesListener?.remove()
        queriesListener = db.collection("users").document(email).collection("notifications")
            .whereEqualTo("read", false)
            .addSnapshotListener { snapshot, _ ->
                hasUnreadReply = false
                if (snapshot != null) {
                    for (doc in snapshot.documents) {
                        val reply = doc.getString("reply")?.trim()
                        if (!reply.isNullOrEmpty() && reply != "Waiting for reply...") {
                            hasUnreadReply = true
                            break
                        }
                    }
                }
                updateBadgeVisibility()
            }

        val adminEmails = listOf("mohamedaufin64@gmail.com", "arunbhalaji200904@gmail.com")
        val isAdmin = adminEmails.contains(email.lowercase())

        announcementsListener?.remove()
        announcementsListener = db.collection("announcements")
            .addSnapshotListener { snapshot, _ ->
                hasUnreadAnnouncement = false
                if (snapshot != null) {
                    val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                    val email = user?.email?.lowercase() ?: ""
                    val registrationTime = user?.metadata?.creationTimestamp ?: 0L
                    val deletedPrefs = getSharedPreferences("DeletedAnnouncements", Context.MODE_PRIVATE)
                    val readPrefs = getSharedPreferences("ReadAnnouncements", Context.MODE_PRIVATE)
                    for (doc in snapshot.documents) {
                        val id = doc.id
                        val timestamp = doc.getLong("timestamp") ?: id.toLongOrNull() ?: 0L
                        if (timestamp < registrationTime) {
                            continue
                        }
                        if (deletedPrefs.contains(id) || readPrefs.contains(id)) {
                            continue
                        }
                        val adminOnly = doc.getBoolean("adminOnly") ?: false
                        val targetEmails = doc.get("targetEmails") as? List<String>
                        if (adminOnly && !isAdmin) {
                            continue
                        }
                        if (targetEmails != null && !targetEmails.map { it.lowercase() }.contains(email)) {
                            continue
                        }
                        hasUnreadAnnouncement = true
                        break
                    }
                }
                updateBadgeVisibility()
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        queriesListener?.remove()
        announcementsListener?.remove()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    override fun onResume() {
        super.onResume()
        // Reload User Profile to display changes immediately upon returning
        val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val userName = appPrefs.getString("user_name", "User")
        findViewById<TextView>(R.id.tvUserName)?.text = userName
    }

    private fun animateDialog(view: android.view.View) {
        val scale = ScaleAnimation(
            0.8f, 1f, 0.8f, 1f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
        )
        scale.duration = 200

        val fade = AlphaAnimation(0f, 1f)
        fade.duration = 200

        view.startAnimation(scale)
        view.startAnimation(fade)
    }
}
