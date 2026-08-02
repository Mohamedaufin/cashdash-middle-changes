@file:Suppress("DEPRECATION")
package com.cash.dash

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge

open class ThemedActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        
        val theme = ThemeHelper.getCurrentTheme(this)
        val isWhite = theme == "White"
        
        val statusBarStyle = when (theme) {
            "White" -> androidx.activity.SystemBarStyle.light(
                android.graphics.Color.parseColor("#F8FAFC"),
                android.graphics.Color.parseColor("#F8FAFC")
            )
            "Blue" -> androidx.activity.SystemBarStyle.dark(
                android.graphics.Color.parseColor("#070B1D") // Dark blue for the top of the gradient
            )
            else -> androidx.activity.SystemBarStyle.dark(
                android.graphics.Color.parseColor("#0C0C0F") // App bg for black theme
            )
        }
        
        // Android 15 SDK 35 standard: Force Edge-to-Edge correctly
        enableEdgeToEdge(statusBarStyle = statusBarStyle)
        
        super.onCreate(savedInstanceState)
        
        // Make status bar icons light/dark
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = isWhite
        
        addStatusBarMask(theme)
    }

    private fun addStatusBarMask(theme: String) {
        val maskView = android.view.View(this)
        val color = when (theme) {
            "White" -> android.graphics.Color.parseColor("#FFFFFF")
            "Blue" -> android.graphics.Color.parseColor("#0D1B6E")
            else -> android.graphics.Color.parseColor("#0C0C0F")
        }
        maskView.setBackgroundColor(color)
        
        val layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            0
        )
        layoutParams.gravity = android.view.Gravity.TOP
        
        val decorView = window.decorView as android.view.ViewGroup
        decorView.addView(maskView, layoutParams)
        
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(maskView) { v, insets ->
            val statusBarInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            val lp = v.layoutParams
            lp.height = statusBarInsets.top
            v.layoutParams = lp
            insets
        }
    }
    
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        val themePrefs = getSharedPreferences("ThemePrefs", android.content.Context.MODE_PRIVATE)
        val savedTheme = themePrefs.getString("current_theme", "System") ?: "System"
        if (savedTheme == "System") {
            super.onConfigurationChanged(newConfig)
            recreate()
        } else {
            val targetNightMode = if (savedTheme == "White") {
                android.content.res.Configuration.UI_MODE_NIGHT_NO
            } else {
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
            newConfig.uiMode = (newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or targetNightMode
            resources.configuration.updateFrom(newConfig)
            super.onConfigurationChanged(newConfig)
        }
    }
    override fun attachBaseContext(newBase: android.content.Context) {
        val configuration = android.content.res.Configuration(newBase.resources.configuration)
        if (configuration.fontScale > 1.0f) {
            configuration.fontScale = 1.0f
            val context = newBase.createConfigurationContext(configuration)
            super.attachBaseContext(context)
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onStart() {
        super.onStart()
        setupGlobalEditTextFocusClearer()
    }

    override fun onResume() {
        super.onResume()
        trackActivityResume()
    }

    private fun trackActivityResume() {
        val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser ?: return
        val email = user.email ?: return
        val prefs = getSharedPreferences("AppPrefs", android.content.Context.MODE_PRIVATE)
        
        val lastSync = prefs.getLong("last_active_sync_time", 0L)
        val now = System.currentTimeMillis()
        
        // Update at most once every 10 seconds to avoid spamming and prevent infinite loops
        if (now - lastSync > 10000) {
            prefs.edit().putLong("last_active_sync_time", now).apply()
            
            val sdf = java.text.SimpleDateFormat("dd/MM/yyyy, h:mm a", java.util.Locale.ENGLISH)
            sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
            val lastActive = sdf.format(java.util.Date())
            prefs.edit().putString("lastActiveTime", lastActive).apply()
            
            val safeEmail = email.replace(".", ",")
            val rtdbStatusRef = com.google.firebase.database.FirebaseDatabase.getInstance().getReference("status").child(safeEmail)
            rtdbStatusRef.setValue(mapOf(
                "state" to "Online",
                "last_changed" to com.google.firebase.database.ServerValue.TIMESTAMP
            ))
        }
    }

    private fun setupGlobalEditTextFocusClearer() {
        val decorView = window.decorView
        decorView.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val root = decorView.findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
                attachFocusClearListeners(root)
            }
        })
    }

    private fun attachFocusClearListeners(view: android.view.View) {
        if (view is android.widget.EditText) {
            if (view.getTag(R.id.focus_clearer_tag) == null) {
                view.setTag(R.id.focus_clearer_tag, true)
                if (view.id != R.id.etSearch) {
                    view.setOnEditorActionListener { _, actionId, event ->
                        val isActionDone = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT ||
                                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                                (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_UP)
                                
                        if (isActionDone) {
                            view.clearFocus()
                            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                            imm.hideSoftInputFromWindow(view.windowToken, 0)
                            true
                        } else {
                            false
                        }
                    }
                }
            }
        } else if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                attachFocusClearListeners(view.getChildAt(i))
            }
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is android.widget.EditText) {
                val outRect = android.graphics.Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    v.clearFocus()
                    val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}
