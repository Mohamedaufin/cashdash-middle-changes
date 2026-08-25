package com.cash.dash

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import com.google.firebase.auth.FirebaseAuth

class SplashActivity : ThemedActivity() {

    private val PREFS = "AppPrefs"
    private val KEY_FIRST = "isFirstLaunch"
    private val KEY_NAME = "user_name"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // NOTE: Do NOT call setContentView here for existing users — that's what caused
        // the "Hello User" splash to flash for established users during the Firestore version check.
        // setContentView is only called below when actually showing the new-user splash animation.

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean(KEY_FIRST, true)
        val firebaseUser = FirebaseAuth.getInstance().currentUser

        if (isFirstLaunch || firebaseUser == null) {
            // Not logged in -> Go straight to Entry form immediately, no splash needed
            startActivity(Intent(this, EntryActivity::class.java))
            finish()
            return
        }

        val walletPrefs = WalletStore.get(this)
        val initialBalance = walletPrefs.getInt("initial_balance", -1)

        if (initialBalance >= 0) {
            // Established user with wallet set up -> navigate directly to MainActivity with NO splash layout shown
            // Run cloud pull silently in background after going home
            FirestoreSyncManager.pullDataFromCloud(this) { _, _, _, _ -> }
            // Version check runs but any redirect to ForceUpdateActivity happens from MainActivity.onResume
            VersionCheckManager.checkAppVersion(this) { /* already going home */ }
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        } else {
            // Freshly registered user (wallet not yet set up) -> Show animated splash greeting
            setContentView(R.layout.activity_splash)

            FirestoreSyncManager.pullDataFromCloud(this) { _, _, _, _ -> }

            VersionCheckManager.checkAppVersion(this) {
                val userName = prefs.getString(KEY_NAME, "User") ?: "User"
                val tvUsername = findViewById<TextView>(R.id.tvUsernameSplash)
                tvUsername?.text = userName

                Handler(Looper.getMainLooper()).postDelayed({
                    val intent = Intent(this@SplashActivity, MainActivity::class.java)
                    intent.putExtra("from_splash", true)
                    if (tvUsername != null) {
                        val options = ActivityOptions.makeSceneTransitionAnimation(
                            this@SplashActivity, tvUsername, "greeting_text_transition"
                        )
                        startActivity(intent, options.toBundle())
                    } else {
                        startActivity(intent)
                    }
                    Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1000)
                }, 1000)
            }
        }
    }
}
