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
        if (isFirstLaunch) {
            // New user -> Go straight to Entry form
            startActivity(Intent(this, EntryActivity::class.java))
            finish()
        } else {
            val walletPrefs = getSharedPreferences("WalletPrefs", MODE_PRIVATE)
            val initialBalance = walletPrefs.getInt("initial_balance", -1)

            // 🔄 Silent background pull for established users:
            // Runs every launch so that any newly-added prefs (e.g. UpiAllocationPrefs)
            // or data from another device/reinstall are always up-to-date locally.
            val firebaseUser = FirebaseAuth.getInstance().currentUser
            if (firebaseUser != null) {
                FirestoreSyncManager.pullDataFromCloud(this) { _, _, _, _ ->
                    // No-op: we don't block navigation on this result.
                    // Data lands in local SharedPreferences silently in the background.
                }
            }

            if (initialBalance > 0) {
                // Established user -> Go directly to MainActivity (Home)
                startActivity(Intent(this, MainActivity::class.java))
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
