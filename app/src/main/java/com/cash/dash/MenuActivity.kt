@file:Suppress("DEPRECATION")
package com.cash.dash

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
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
import android.view.View
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

        // Fullscreen
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

        // Load User Profile
        val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val userName = appPrefs.getString("user_name", "User")
        findViewById<TextView>(R.id.tvUserName).text = userName

        // Buttons / Items
        val btnClose = findViewById<View>(R.id.btnCloseMenu)
        val btnBalance = findViewById<View>(R.id.btnBalanceBar)
        val btnUpdateSchedule = findViewById<View>(R.id.btnUpdateSchedule)
        val btnTheme = findViewById<View>(R.id.btnTheme)
        val btnHelp = findViewById<View>(R.id.btnHelp)
        val btnPrivacyPolicy = findViewById<View>(R.id.btnPrivacyPolicy)
        val btnNotifications = findViewById<View>(R.id.btnNotifications)
        val notificationBadge = findViewById<View>(R.id.notificationBadge)

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

        btnHelp.setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }

        btnPrivacyPolicy.setOnClickListener {
            val url = "https://github.com/Mohamedaufin/cashdash/blob/main/privacy_policy.md"
            val intent = Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            startActivity(intent)
        }

        btnNotifications.setOnClickListener {
            notificationBadge.visibility = View.GONE
            startActivity(Intent(this, NotificationActivity::class.java))
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }

        setupNotificationListener(notificationBadge)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    private fun setupNotificationListener(badge: View) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val email = user.email ?: return
        val db = FirebaseFirestore.getInstance()

        db.collection("users").document(email).collection("notifications")
            .whereEqualTo("read", false)
            .addSnapshotListener { snapshot, _ ->
                var hasUnreadReply = false
                if (snapshot != null) {
                    for (doc in snapshot.documents) {
                        val reply = doc.getString("reply")?.trim()
                        if (!reply.isNullOrEmpty() && reply != "Waiting for reply...") {
                            hasUnreadReply = true
                            break
                        }
                    }
                }
                badge.visibility = if (hasUnreadReply) View.VISIBLE else View.GONE
            }
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
