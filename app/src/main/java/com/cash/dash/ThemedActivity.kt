@file:Suppress("DEPRECATION")
package com.cash.dash

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge

open class ThemedActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        
        // Android 15 SDK 35 standard: Force Edge-to-Edge correctly
        enableEdgeToEdge()
        
        super.onCreate(savedInstanceState)
        
        // Restore custom status bar color logic for White theme
        if (ThemeHelper.isWhiteTheme(this)) {
            window.statusBarColor = android.graphics.Color.BLACK
        } else {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
        }
    }
}
