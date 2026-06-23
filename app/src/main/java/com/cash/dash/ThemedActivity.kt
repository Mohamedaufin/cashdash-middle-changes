@file:Suppress("DEPRECATION")
package com.cash.dash

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge

open class ThemedActivity : AppCompatActivity() {
    private var lastAppliedTheme: String? = null
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
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        
        val currentTheme = ThemeHelper.getCurrentTheme(this)
        if (lastAppliedTheme != null && lastAppliedTheme != currentTheme) {
            if (ThemeHelper.getSavedTheme(this) == "System") {
                showThemeChangeRestartDialog(currentTheme)
            } else {
                recreate()
            }
        }
        lastAppliedTheme = currentTheme
    }

    override fun onDestroy() {
        super.onDestroy()
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
