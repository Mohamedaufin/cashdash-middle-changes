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


class MenuActivity : AppCompatActivity() {

    private val PREFS = "WalletPrefs"
    private val KEY_BALANCE = "wallet_balance"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)

        // Fullscreen like MainActivity
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

        // Buttons
        val btnClose = findViewById<ImageButton>(R.id.btnCloseMenu)
        val btnBalance = findViewById<Button>(R.id.btnBalanceBar)
        val btnHelp = findViewById<Button>(R.id.btnHelp)
        val btnUpdateSchedule = findViewById<Button>(R.id.btnUpdateSchedule)   // NEW BUTTON

        btnClose.setOnClickListener { finish() }

        btnBalance.setOnClickListener {
            startActivity(Intent(this, BalanceSetupActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        btnHelp.setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        btnUpdateSchedule.setOnClickListener {
            startActivity(Intent(this, MoneyScheduleActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        val btnPrivacyPolicy = findViewById<Button>(R.id.btnPrivacyPolicy)
        btnPrivacyPolicy.setOnClickListener {
            val url = "https://github.com/Mohamedaufin/cashdash/blob/main/privacy_policy.md" // Replace with your hosted URL
            val intent = Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            startActivity(intent)
        }

        val btnNotifications = findViewById<View>(R.id.btnNotifications)
        val notificationBadge = findViewById<View>(R.id.notificationBadge)

        btnNotifications.setOnClickListener {
            notificationBadge.visibility = View.GONE
            startActivity(Intent(this, NotificationActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        setupNotificationListener(notificationBadge)
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
