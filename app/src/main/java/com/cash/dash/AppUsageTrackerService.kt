package com.cash.dash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AppUsageTrackerService : Service() {

    private val targetApps = mapOf(
        "in.swiggy.android" to "Swiggy",
        "com.application.zomato" to "Zomato",
        "com.zeptoconsumerapp" to "Zepto",
        "com.grofers.customerapp" to "Blinkit",
        "com.bigbasket.mobileapp" to "BigBasket",
        "in.amazon.mShop.android.shopping" to "Amazon",
        "com.flipkart.android" to "Flipkart",
        "com.myntra.android" to "Myntra",
        "com.rapido.passenger" to "Rapido",
        "com.ubercab" to "Uber",
        "com.olacabs.customer" to "Ola",
        "com.ril.ajio" to "Ajio",
        "com.meesho.supply" to "Meesho",
        "in.redbus.android" to "RedBus",
        "cris.org.in.prs.ima" to "IRCTC",
        "com.eaglefleet.redtaxi" to "RedTaxi"
    )

    private var currentForegroundPackage: String? = null
    private var trackingJob: Job? = null
    private lateinit var usageStatsManager: UsageStatsManager
    
    companion object {
        private const val NOTIFICATION_ID = 4001
        private const val CHANNEL_ID = "tracker_service_channel"
    }

    override fun onCreate() {
        super.onCreate()
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        
        if (trackingJob == null || trackingJob?.isActive == false) {
            startTracking()
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        trackingJob?.cancel()
        super.onDestroy()
    }

    private fun startTracking() {
        trackingJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                checkForegroundApp()
                delay(1500) // Poll every 1.5 seconds
            }
        }
    }

    private fun checkForegroundApp() {
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 10000 // Look back 10 seconds

        var topPackage: String? = null
        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()
        
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            // We ONLY care about ACTIVITY_RESUMED. It is the only reliable state.
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                topPackage = event.packageName
            }
        }

        // If no new activities resumed in the last 10 seconds, the user is still on the same screen.
        // We preserve our state completely and do nothing.
        if (topPackage == null) {
            return
        }

        // Ignore system UI, our own app, and common keyboards.
        // Returning here means we preserve the current state (e.g. they pulled down notification shade).
        if (topPackage == "com.android.systemui" || 
            topPackage == "com.cash.dash" ||
            topPackage.contains("inputmethod", ignoreCase = true) ||
            topPackage.contains("honeyboard", ignoreCase = true) ||
            topPackage.contains("swiftkey", ignoreCase = true)) {
            return
        }

        // We have a definitive, valid app resume event that differs from our current state.
        if (topPackage != currentForegroundPackage) {
            currentForegroundPackage = topPackage
            Log.d("UsageTracker", "Foreground App Changed: $topPackage")

            if (targetApps.containsKey(topPackage)) {
                // It's a target shopping app!
                val appName = targetApps[topPackage]
                showFloatingWidget(appName ?: "Shopping App")
            } else {
                // The user definitively left the shopping app (e.g. went to home screen or another app)
                hideFloatingWidget()
            }
        }
    }

    private fun showFloatingWidget(appName: String) {
        val intent = Intent(this, FloatingWidgetService::class.java).apply {
            action = FloatingWidgetService.ACTION_SHOW_WIDGET
            putExtra(FloatingWidgetService.EXTRA_APP_NAME, appName)
        }
        try {
            startService(intent)
        } catch (e: Exception) {
            Log.e("UsageTracker", "Failed to start FloatingWidgetService: ${e.message}")
        }
    }

    private fun hideFloatingWidget() {
        val intent = Intent(this, FloatingWidgetService::class.java).apply {
            action = FloatingWidgetService.ACTION_HIDE_WIDGET
        }
        try {
            startService(intent)
        } catch (e: Exception) {
            Log.e("UsageTracker", "Failed to stop FloatingWidgetService: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Smart Tracker Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Runs quietly in background to detect when you open shopping apps."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, TaptrackActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CashDash Tracker Active")
            .setSmallIcon(R.drawable.ic_logo_shield)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }
}
