@file:Suppress("DEPRECATION")
package com.cash.dash

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class TaptrackActivity : ThemedActivity() {

    private lateinit var switchTaptrack: Switch
    private lateinit var tvTaptrackStatus: TextView
    private lateinit var iconTaptrackStatus: ImageView
    
    private lateinit var layoutOverlayPermission: LinearLayout
    private lateinit var switchOverlay: Switch
    
    private lateinit var layoutUsagePermission: LinearLayout
    private lateinit var switchUsage: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_taptrack)

        findViewById<View>(R.id.btnBack).setOnClickListener {
            finish()
        }

        switchTaptrack = findViewById(R.id.switchTaptrack)
        tvTaptrackStatus = findViewById(R.id.tvTaptrackStatus)
        iconTaptrackStatus = findViewById(R.id.iconTaptrackStatus)

        layoutOverlayPermission = findViewById(R.id.layoutOverlayPermission)
        switchOverlay = findViewById(R.id.switchOverlay)

        layoutUsagePermission = findViewById(R.id.layoutUsagePermission)
        switchUsage = findViewById(R.id.switchUsage)

        val smartPrefs = getSharedPreferences("SmartAssistantPrefs", Context.MODE_PRIVATE)
        var isTrackingOn = smartPrefs.getBoolean("tracking_enabled", false)

        // Validation check
        if (isTrackingOn) {
            if (!Settings.canDrawOverlays(this) || !hasUsageStatsPermission()) {
                isTrackingOn = false
                smartPrefs.edit().putBoolean("tracking_enabled", false).apply()
                stopService(Intent(this, AppUsageTrackerService::class.java))
            }
        }

        switchTaptrack.isChecked = isTrackingOn
        updateMasterToggleUI()

        switchTaptrack.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                checkAndRequestPermissions()
            } else {
                smartPrefs.edit().putBoolean("tracking_enabled", false).apply()
                stopService(Intent(this, AppUsageTrackerService::class.java))
                layoutOverlayPermission.visibility = View.GONE
                layoutUsagePermission.visibility = View.GONE
                updateMasterToggleUI()
            }
        }

        switchOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        switchUsage.setOnClickListener {
            AlertDialogHelper.createFlatDialogBuilder(this)
                .setTitle("Data Collection Disclosure")
                .setMessage("CashDash collects data about which apps you open to detect when you are using supported shopping applications. This enables the Taptrack widget to appear automatically while you shop. This data is kept strictly on your device and never shared.")
                .setTitleGravity(android.view.Gravity.CENTER)
                .setMessageGravity(android.view.Gravity.START)
                .setTitleTextSize(16f)
                .setMessageTextSize(14f)
                .setTitleTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL))
                .setPositiveTextColor(android.graphics.Color.WHITE)
                .setPositiveButton("I Agree") {
                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    startActivity(intent)
                }
                .setNegativeButton("Cancel") {
                    switchUsage.isChecked = false
                }
                .setCancelable(false)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasUsage = hasUsageStatsPermission()
        val smartPrefs = getSharedPreferences("SmartAssistantPrefs", Context.MODE_PRIVATE)
        val wasTrackingEnabled = smartPrefs.getBoolean("tracking_enabled", false)

        if (wasTrackingEnabled && (!hasOverlay || !hasUsage)) {
            // User manually revoked a permission from Android settings!
            // Force disable tracking and reset the master switch. 
            // Setting isChecked = false will trigger the listener, which hides panels and resets UI.
            switchTaptrack.isChecked = false
        } else if (switchTaptrack.isChecked) {
            // We are either in the middle of setup, or just returning from granting a permission
            checkAndRequestPermissions()
        }
    }

    private fun checkAndRequestPermissions() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasUsage = hasUsageStatsPermission()

        switchOverlay.isChecked = hasOverlay
        switchUsage.isChecked = hasUsage

        if (!hasOverlay) {
            layoutOverlayPermission.visibility = View.VISIBLE
            layoutUsagePermission.visibility = View.GONE
            updateMasterToggleUI()
            return
        }

        layoutOverlayPermission.visibility = View.GONE

        if (!hasUsage) {
            layoutUsagePermission.visibility = View.VISIBLE
            updateMasterToggleUI()
            return
        }

        layoutUsagePermission.visibility = View.GONE

        // If we reach here, both are granted!
        val smartPrefs = getSharedPreferences("SmartAssistantPrefs", Context.MODE_PRIVATE)
        smartPrefs.edit().putBoolean("tracking_enabled", true).apply()
        
        val serviceIntent = Intent(this, AppUsageTrackerService::class.java)
        androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)
        
        updateMasterToggleUI()
    }

    private fun updateMasterToggleUI() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasUsage = hasUsageStatsPermission()

        if (switchTaptrack.isChecked) {
            if (hasOverlay && hasUsage) {
                tvTaptrackStatus.text = "Taptrack is active"
                iconTaptrackStatus.setColorFilter(Color.parseColor("#1DD15D")) // Green
            } else {
                tvTaptrackStatus.text = "Setup Required"
                iconTaptrackStatus.setColorFilter(ThemeHelper.resolveColorAttr(this, R.attr.textMutedColor))
            }
        } else {
            tvTaptrackStatus.text = "Taptrack is off"
            iconTaptrackStatus.setColorFilter(ThemeHelper.resolveColorAttr(this, R.attr.textPrimaryColor))
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        } else {
            appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        }
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }
}
