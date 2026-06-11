package com.cash.dash

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class WidgetLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Start the Floating Widget Service
        val serviceIntent = Intent(this, FloatingWidgetService::class.java).apply {
            action = FloatingWidgetService.ACTION_SHOW_WIDGET
            putExtra(FloatingWidgetService.EXTRA_APP_NAME, "Manual Tracker")
        }
        
        try {
            startService(serviceIntent)
        } catch (e: Exception) {
            android.util.Log.e("WidgetLaunchActivity", "Failed to start FloatingWidgetService", e)
        }
        
        // Finish instantly so it acts completely invisibly
        finish()
    }
}
