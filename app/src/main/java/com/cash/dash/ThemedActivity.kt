@file:Suppress("DEPRECATION")
package com.cash.dash

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge

open class ThemedActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        
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
        
        // Make status bar icons light/dark
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = isWhite
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
