package com.cash.dash

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : ThemedActivity() {

    private val PREFS = "AppPrefs"
    private val KEY_FIRST = "isFirstLaunch"
    private val KEY_NAME = "user_name"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_splash)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean(KEY_FIRST, true)
        val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser

        if (isFirstLaunch || currentUser == null) {
            // New user or corrupted/expired session -> Go straight to Entry form
            startActivity(Intent(this, EntryActivity::class.java))
            finish()
        } else {
            val walletPrefs = getSharedPreferences("WalletPrefs", MODE_PRIVATE)
            val initialBalance = walletPrefs.getInt("initial_balance", -1)

            if (initialBalance > 0) {
                // Established user -> Instant transition to Scanner
                startActivity(Intent(this, ScannerActivity::class.java))
                finish()
            } else {
                // New user (after entry screen) -> Show Splash Transition
                setContentView(R.layout.activity_splash)

                val userName = prefs.getString(KEY_NAME, "User") ?: "User"
                val tvUsername = findViewById<TextView>(R.id.tvUsernameSplash)
                tvUsername.text = userName

                Handler(Looper.getMainLooper()).postDelayed({
                    val intent = Intent(this@SplashActivity, MainActivity::class.java)
                    intent.putExtra("from_splash", true)
                    val options = ActivityOptions.makeSceneTransitionAnimation(
                        this@SplashActivity, tvUsername, "greeting_text_transition"
                    )
                    startActivity(intent, options.toBundle())

                    // Finish SplashActivity after a short delay so the transition finishes
                    Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1000)
                }, 1000) // Hold splash briefly
            }
        }
    }
}
