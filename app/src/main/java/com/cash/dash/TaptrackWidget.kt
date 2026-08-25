package com.cash.dash

import android.app.AppOpsManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.widget.RemoteViews
import androidx.core.content.ContextCompat

class TaptrackWidget : AppWidgetProvider() {

    companion object {
        /**
         * Flips tracking on or off.
         *
         * Called from [WidgetLaunchActivity], which is not exported. It used to be
         * a broadcast action on this receiver — but an AppWidgetProvider has to be
         * exported to receive APPWIDGET_UPDATE, so any installed app could send the
         * toggle and start or stop the overlay service behind the user's back.
         */
        fun handleToggle(context: Context) {
            val prefs = context.getSharedPreferences("SmartAssistantPrefs", Context.MODE_PRIVATE)
            val isOn = prefs.getBoolean("tracking_enabled", false)
            val hasOverlay = Settings.canDrawOverlays(context)
            val hasUsage = hasUsageStatsPermission(context)

            if (isOn) {
                // Turn off tracking
                prefs.edit().putBoolean("tracking_enabled", false).apply()
                context.stopService(Intent(context, AppUsageTrackerService::class.java))
            } else if (hasOverlay && hasUsage) {
                // Turn on tracking – all permissions already granted
                prefs.edit().putBoolean("tracking_enabled", true).apply()
                ContextCompat.startForegroundService(context, Intent(context, AppUsageTrackerService::class.java))
            } else {
                // Permissions not granted: open TaptrackActivity so user can complete setup
                val activityIntent = Intent(context, TaptrackActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(activityIntent)
                return
            }
            refreshAllWidgets(context)
        }

        private fun hasUsageStatsPermission(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }

        fun refreshAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TaptrackWidget::class.java))
            if (ids.isNotEmpty()) {
                val intent = Intent(context, TaptrackWidget::class.java)
                intent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                context.sendBroadcast(intent)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val prefs = context.getSharedPreferences("SmartAssistantPrefs", Context.MODE_PRIVATE)
        val isOn = prefs.getBoolean("tracking_enabled", false)

        val views = RemoteViews(context.packageName, R.layout.layout_taptrack_widget)

        // Toggle Switch Image
        if (isOn) {
            views.setImageViewResource(R.id.ivWidgetToggle, R.drawable.ic_widget_switch_on)
        } else {
            views.setImageViewResource(R.id.ivWidgetToggle, R.drawable.ic_widget_switch_off)
        }

        // Toggle click. Routed through WidgetLaunchActivity (not exported) rather
        // than a broadcast back to this receiver, which any app could send.
        val toggleIntent = Intent(context, WidgetLaunchActivity::class.java).apply {
            putExtra("WIDGET_TYPE", "TapTrackToggle")
        }
        val togglePendingIntent = PendingIntent.getActivity(
            context, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetContainer, togglePendingIntent)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
