@file:Suppress("DEPRECATION")
package com.cash.dash

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge

open class ThemedActivity : AppCompatActivity() {
    private var lastAppliedTheme: String? = null
    private var lastSavedTheme: String? = null
    private var themeListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var isActivityResumed = false

    companion object {
        private var isThemeDialogShowing = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        lastAppliedTheme = ThemeHelper.getCurrentTheme(this)
        
        val isWhite = ThemeHelper.isWhiteTheme(this)
        val statusBarStyle = if (isWhite) {
            androidx.activity.SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        } else {
            androidx.activity.SystemBarStyle.dark(
                android.graphics.Color.TRANSPARENT
            )
        }
        
        // Android 15 SDK 35 standard: Force Edge-to-Edge correctly
        enableEdgeToEdge(statusBarStyle = statusBarStyle)
        
        super.onCreate(savedInstanceState)
        
        // Make status bar transparent for edge-to-edge
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = isWhite

        lastSavedTheme = ThemeHelper.getSavedTheme(this)
        val themePrefs = getSharedPreferences("ThemePrefs", MODE_PRIVATE)
        themeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "current_theme") {
                val newSavedTheme = themePrefs.getString("current_theme", "System") ?: "System"
                val originalTheme = lastSavedTheme
                if (originalTheme != null && originalTheme != newSavedTheme) {
                    if (FirestoreSyncManager.isSyncingFromCloud) {
                        if (this !is EntryActivity && this !is SplashActivity) {
                            showAccountThemePreferenceDialog(originalTheme, newSavedTheme)
                        }
                    } else {
                        lastSavedTheme = newSavedTheme
                    }
                }
            }
        }
        themePrefs.registerOnSharedPreferenceChangeListener(themeListener)
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        lastSavedTheme = ThemeHelper.getSavedTheme(this)
        
        val currentTheme = ThemeHelper.getCurrentTheme(this)
        if (lastAppliedTheme != null && lastAppliedTheme != currentTheme) {
            showThemeChangeRestartDialog(currentTheme)
        }
        lastAppliedTheme = currentTheme
    }

    override fun onDestroy() {
        super.onDestroy()
        themeListener?.let {
            getSharedPreferences("ThemePrefs", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(it)
        }
        isThemeDialogShowing = false
    }

    override fun onPause() {
        super.onPause()
        isActivityResumed = false
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        
        if (isActivityResumed && ThemeHelper.getSavedTheme(this) == "System") {
            val newNightMode = newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            val targetTheme = if (newNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) "Black" else "White"
            
            if (lastAppliedTheme != null && lastAppliedTheme != targetTheme) {
                showThemeChangeRestartDialog(targetTheme)
            }
        }
    }

    private fun showThemeChangeRestartDialog(targetTheme: String) {
        if (isThemeDialogShowing) return
        isThemeDialogShowing = true
        
        val targetThemeName = if (targetTheme == "Black") "dark" else "light"
        val targetThemeResId = if (targetTheme == "White") R.style.Theme_Cashdash_White else R.style.Theme_Cashdash
        val themedContext = androidx.appcompat.view.ContextThemeWrapper(this, targetThemeResId)
        
        AlertDialogHelper.createFlatDialogBuilder(themedContext)
            .setTitle("Theme Change Detected")
            .setMessage("Do you want to restart CashDash to apply $targetThemeName theme?")
            .setPositiveButton("Restart") {
                isThemeDialogShowing = false
                val intent = android.content.Intent(this, SplashActivity::class.java).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }
            .setNegativeButton("Cancel") {
                isThemeDialogShowing = false
                lastAppliedTheme = targetTheme
            }
            .setCancelable(false)
            .show()
    }

    private fun showAccountThemePreferenceDialog(originalTheme: String, cloudTheme: String) {
        if (isThemeDialogShowing) return
        isThemeDialogShowing = true

        val cloudThemeName = when (cloudTheme) {
            "System" -> "System Default"
            else -> cloudTheme
        }
        val originalThemeName = when (originalTheme) {
            "System" -> "system default"
            else -> "$originalTheme theme"
        }

        val message = "Your CashDash theme preference is $cloudThemeName, it is currently set to $originalThemeName. Do you want app to restart it?"

        val targetThemeResId = when (cloudTheme) {
            "White" -> R.style.Theme_Cashdash_White
            "Blue" -> R.style.Theme_Cashdash_Blue
            else -> R.style.Theme_Cashdash
        }
        val themedContext = androidx.appcompat.view.ContextThemeWrapper(this, targetThemeResId)

        AlertDialogHelper.createFlatDialogBuilder(themedContext)
            .setTitle("Theme Preference Sync")
            .setMessage(message)
            .setPositiveButton("Yes") {
                isThemeDialogShowing = false
                lastSavedTheme = cloudTheme
                val intent = android.content.Intent(this, SplashActivity::class.java).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }
            .setNegativeButton("No") {
                isThemeDialogShowing = false
                // Revert SharedPreferences to originalTheme
                getSharedPreferences("ThemePrefs", MODE_PRIVATE).edit().putString("current_theme", originalTheme).apply()
                lastSavedTheme = originalTheme
                // Sync the reversion back to Firebase
                FirestoreSyncManager.pushAllDataToCloud(this)
            }
            .setCancelable(false)
            .show()
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
                if (view.id != R.id.edtSearch) {
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
