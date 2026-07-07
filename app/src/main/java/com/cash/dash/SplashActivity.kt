package com.cash.dash

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

class SplashActivity : ThemedActivity() {

    private val PREFS = "AppPrefs"
    private val KEY_FIRST = "isFirstLaunch"
    private val KEY_NAME = "user_name"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_splash)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean(KEY_FIRST, true)
        val firebaseUser = FirebaseAuth.getInstance().currentUser

        if (isFirstLaunch || firebaseUser == null) {
            // Not logged in -> Go straight to Entry form
            startActivity(Intent(this, EntryActivity::class.java))
            finish()
            return
        }

        // User is logged in. Silent background pull - do NOT block navigation on result.
        FirestoreSyncManager.pullDataFromCloud(this) { _, _, _, _ -> }

        val walletPrefs = getSharedPreferences("WalletPrefs", MODE_PRIVATE)
        val initialBalance = walletPrefs.getInt("initial_balance", -1)

        if (initialBalance >= 0) {
            // Established user with wallet set up -> Go directly to MainActivity (Home)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        } else {
            // Freshly registered user (wallet not set up yet) -> Show animated splash
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
                Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1000)
            }, 1000)
        }
    }
}
