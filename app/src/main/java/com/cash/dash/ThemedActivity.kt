@file:Suppress("DEPRECATION")
package com.cash.dash

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

open class ThemedActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        if (ThemeHelper.isWhiteTheme(this)) {
            window.statusBarColor = android.graphics.Color.BLACK
        } else {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
        }
    }
}
