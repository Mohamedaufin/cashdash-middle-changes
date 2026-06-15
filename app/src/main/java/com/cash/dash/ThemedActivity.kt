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
        
        // Make status bar transparent for edge-to-edge
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = isWhite
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
}
