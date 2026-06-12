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

        layoutProfileHeader.setOnLongClickListener {
            val user = FirebaseAuth.getInstance().currentUser
            val email = user?.email
            val adminEmails = listOf("mohamedaufin64@gmail.com", "arunbhalaji200904@gmail.com")
            
            if (email != null && adminEmails.contains(email.lowercase())) {
                startActivity(Intent(this, AdminActivity::class.java))
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            }
            true
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
    }



    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    override fun onResume() {
        super.onResume()
        

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
