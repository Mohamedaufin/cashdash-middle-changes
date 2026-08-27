@file:Suppress("DEPRECATION")
package com.cash.dash

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge

open class ThemedActivity : AppCompatActivity() {

    // Tracks which theme this activity was built with.
    // If the user changes theme in ThemeActivity and returns, onResume()
    // will detect the mismatch and call recreate() — which properly calls
    // onSaveInstanceState first, so no work is lost.
    private var activityCreatedTheme = ""
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        // Record the theme NOW so onResume() can detect if it changes
        activityCreatedTheme = ThemeHelper.getSavedTheme(this)
        
        val theme = ThemeHelper.getCurrentTheme(this)
        val isWhite = theme == "White"
        
        // One value for the bar and the page. applyTheme() above has already called
        // setTheme, so the attribute resolves here.
        //
        // These used to be three hardcoded lists in two places and they had drifted: on
        // White the mask painted #FFFFFF, the system bar said #F8FAFC and the page began
        // #F7F9FB, which is the seam under the status bar. Black was stale in the same
        // way, still on #0C0C0F after app_bg moved to #0D0F12.
        val pageTop = ThemeHelper.resolveColorAttr(this, R.attr.pageTopColor)

        val statusBarStyle = if (isWhite) {
            androidx.activity.SystemBarStyle.light(pageTop, pageTop)
        } else {
            androidx.activity.SystemBarStyle.dark(pageTop)
        }

        // Android 15 SDK 35 standard: Force Edge-to-Edge correctly
        enableEdgeToEdge(statusBarStyle = statusBarStyle)

        super.onCreate(savedInstanceState)

        // Make status bar icons light/dark
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = isWhite

        addStatusBarMask(pageTop)

        reportDeviceIntegrityOnce()
    }

    /**
     * Records device integrity signals once per process.
     *
     * Deliberately does NOT block or alter behaviour here. Every screen extends
     * this class, so gating on it would take the whole app down on a device that
     * merely looks unusual — and root is not, by itself, evidence of an attack
     * on anyone but the device's own owner. The payment path in ScannerActivity
     * is where this signal is actually acted on.
     *
     * Uses Log.e because Log.d/v/i/w are stripped from release builds, and a
     * signal that only exists in debug is no signal at all.
     */
    private fun reportDeviceIntegrityOnce() {
        if (integrityReported) return
        integrityReported = true

        val result = TamperCheck.evaluate(this)
        if (result.signals.isNotEmpty()) {
            android.util.Log.e(
                "TamperCheck",
                "device integrity signals: ${result.signals.joinToString()} " +
                    "(compromised=${result.isCompromised})"
            )
        }
    }

    companion object {
        @Volatile
        private var integrityReported = false
    }

    /** [color] is ?attr/pageTopColor, so the mask always matches the top of the page. */
    private fun addStatusBarMask(color: Int) {
        val maskView = android.view.View(this)
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
        val themePrefs = newBase.getSharedPreferences("ThemePrefs", android.content.Context.MODE_PRIVATE)
        val savedTheme = themePrefs.getString("current_theme", "System") ?: "System"

        val configuration = android.content.res.Configuration(newBase.resources.configuration)

        // Enforce font scale cap
        if (configuration.fontScale > 1.0f) {
            configuration.fontScale = 1.0f
        }

        // Override night mode based on saved app theme — ignores system setting
        if (savedTheme != "System") {
            val targetNightMode = if (savedTheme == "White") {
                android.content.res.Configuration.UI_MODE_NIGHT_NO
            } else {
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
            configuration.uiMode = (configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or targetNightMode
        }

        val context = newBase.createConfigurationContext(configuration)
        super.attachBaseContext(context)
    }

    override fun onStart() {
        super.onStart()
        setupGlobalEditTextFocusClearer()
    }

    override fun onResume() {
        super.onResume()
        // Detect if the user changed the theme while this activity was paused
        // (e.g. they visited ThemeActivity and applied a new theme)
        val currentSavedTheme = ThemeHelper.getSavedTheme(this)
        if (activityCreatedTheme.isNotEmpty() && currentSavedTheme != activityCreatedTheme) {
            // recreate() calls onSaveInstanceState before destroying, so all state is preserved
            recreate()
            return
        }
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
