package com.cash.dash

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class WalletWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE_VISIBILITY) {
            val prefs = context.getSharedPreferences("WalletPrefs", Context.MODE_PRIVATE)
            val currentVisible = prefs.getBoolean("is_widget_visible", false)
            prefs.edit().putBoolean("is_widget_visible", !currentVisible).apply()
            pushUpdate(context)
        }
    }

    companion object {
        const val ACTION_TOGGLE_VISIBILITY = "com.cash.dash.ACTION_TOGGLE_VISIBILITY"

        fun pushUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WalletWidget::class.java))
            for (id in ids) {
                updateWidget(context, manager, id)
            }
        }

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
            val prefs = context.getSharedPreferences("WalletPrefs", Context.MODE_PRIVATE)
            val appPrefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            
            val balance = prefs.getInt("wallet_balance", 0)
            val isVisible = prefs.getBoolean("is_widget_visible", false)
            val username = appPrefs.getString("user_name", "User") ?: "User"

            val views = RemoteViews(context.packageName, R.layout.layout_wallet_widget)

            if (isVisible) {
                val initialRaw = prefs.getInt("initial_balance", -1)
                val initial = if (initialRaw <= 0) 0 else initialRaw
                val displayStr = if (initial > 0) "Balance: ₹$balance / ₹$initial" else "Balance: ₹$balance"
                views.setTextViewText(R.id.tvWidgetBalance, displayStr)
                views.setImageViewResource(R.id.btnWidgetToggle, R.drawable.ic_eye)
            } else {
                val calendar = java.util.Calendar.getInstance()
                val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
                val minute = calendar.get(java.util.Calendar.MINUTE)
                val totalMinutes = hour * 60 + minute

                val greeting = when {
                    totalMinutes in (4 * 60)..(11 * 60 + 59) -> "Good Morning"
                    totalMinutes in (12 * 60)..(15 * 60 + 59) -> "Good Afternoon"
                    totalMinutes in (16 * 60)..(20 * 60 + 30) -> "Good Evening"
                    else -> "Good Night"
                }

                views.setTextViewText(R.id.tvWidgetBalance, greeting)
                views.setImageViewResource(R.id.btnWidgetToggle, R.drawable.ic_eye_off)
            }

            // Click on the eye toggles visibility
            val toggleIntent = Intent(context, WalletWidget::class.java).apply {
                action = ACTION_TOGGLE_VISIBILITY
            }
            val togglePendingIntent = PendingIntent.getBroadcast(
                context, widgetId, toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btnWidgetToggle, togglePendingIntent)

            // Click on any other part does nothing now (removed app opening intent)
            // PendingIntent for opening app has been removed as requested

            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}
