package com.cash.dash

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth

class WidgetLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val widgetType = intent.getStringExtra("WIDGET_TYPE") ?: "Finminder"
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        
        if (firebaseUser == null) {
            val displayType = when (widgetType) {
                "FinminderList" -> "Finminder"
                "TapTrackToggle" -> "TapTrack"
                else -> widgetType
            }
            Toast.makeText(this, "Login/Register to continue using $displayType", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (widgetType == "TapTrackToggle") {
            // Handled here rather than in TaptrackWidget.onReceive: the widget
            // provider must stay exported for APPWIDGET_UPDATE, but this activity
            // is not exported, so only our own PendingIntent can reach the toggle.
            TaptrackWidget.handleToggle(this)
        } else if (widgetType == "Scanner") {
            startActivity(Intent(this, ScannerActivity::class.java))
        } else if (widgetType == "TapTrack") {
            startActivity(Intent(this, TaptrackActivity::class.java))
        } else if (widgetType == "FinminderList") {
            startActivity(Intent(this, FinminderActivity::class.java))
        } else {
            // Default to Finminder/Tracker
            val serviceIntent = Intent(this, FloatingWidgetService::class.java).apply {
                action = FloatingWidgetService.ACTION_SHOW_WIDGET
                putExtra(FloatingWidgetService.EXTRA_APP_NAME, "Manual Tracker")
            }
            try {
                startService(serviceIntent)
            } catch (e: Exception) {
                android.util.Log.e("WidgetLaunchActivity", "Failed to start FloatingWidgetService", e)
            }
        }
        
        finish()
    }
}
