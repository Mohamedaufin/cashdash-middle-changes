package com.cash.dash

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.google.firebase.auth.FirebaseAuth
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class Finminder : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val prefs = WalletStore.get(context)
        val currentIndex = prefs.getInt("widget_current_index", 0)

        when (intent.action) {
            ACTION_NEXT -> {
                prefs.edit().putInt("widget_current_index", currentIndex + 1).apply()
                pushUpdate(context)
            }
            ACTION_PREV -> {
                prefs.edit().putInt("widget_current_index", currentIndex - 1).apply()
                pushUpdate(context)
            }
        }
    }

    companion object {
        const val ACTION_NEXT = "com.cash.dash.ACTION_NEXT"
        const val ACTION_PREV = "com.cash.dash.ACTION_PREV"

        fun pushUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, Finminder::class.java))
            for (id in ids) {
                updateWidget(context, manager, id)
            }
        }

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.layout_wallet_widget)

            val items = FinminderRepository.getItems(context)
            
            val today = Calendar.getInstance()
            val todayDateStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(today.time)
            val todayDayStr = SimpleDateFormat("EEEE", Locale.getDefault()).format(today.time)
            val todayDateOfMonth = today.get(Calendar.DAY_OF_MONTH).toString()

            val todayItems = items.filter { item ->
                when (item.frequency) {
                    "One time" -> item.dateInfo == todayDateStr
                    "Daily" -> true
                    "Weekly" -> item.dateInfo == todayDayStr
                    "Monthly" -> item.dateInfo == todayDateOfMonth
                    else -> false
                }
            }

            val firebaseUser = FirebaseAuth.getInstance().currentUser

            if (firebaseUser == null) {
                views.setViewVisibility(R.id.tvEmptyState, View.VISIBLE)
                views.setViewVisibility(R.id.layoutContent, View.GONE)
                views.setViewVisibility(R.id.btnPrev, View.GONE)
                views.setViewVisibility(R.id.btnNext, View.GONE)
                views.setTextViewText(R.id.tvEmptyState, "Login/Register\nto continue")
            } else if (todayItems.isEmpty()) {
                views.setViewVisibility(R.id.tvEmptyState, View.VISIBLE)
                views.setViewVisibility(R.id.layoutContent, View.GONE)
                views.setViewVisibility(R.id.btnPrev, View.GONE)
                views.setViewVisibility(R.id.btnNext, View.GONE)
                views.setTextViewText(R.id.tvEmptyState, "No finminders today")
            } else {
                views.setViewVisibility(R.id.tvEmptyState, View.GONE)
                views.setViewVisibility(R.id.layoutContent, View.VISIBLE)

                val prefs = WalletStore.get(context)
                var currentIndex = prefs.getInt("widget_current_index", 0)
                if (currentIndex >= todayItems.size) {
                    currentIndex = 0
                    prefs.edit().putInt("widget_current_index", currentIndex).apply()
                } else if (currentIndex < 0) {
                    currentIndex = todayItems.size - 1
                    prefs.edit().putInt("widget_current_index", currentIndex).apply()
                }

                val item = todayItems[currentIndex]

                views.setTextViewText(R.id.tvWidgetTitle, item.title)
                views.setTextViewText(R.id.tvWidgetType, if (item.type == "CASH_OUT") "Cash out" else "Cash in")
                views.setTextViewText(R.id.tvWidgetAmount, item.quantity)
                views.setTextViewText(R.id.tvWidgetFrequency, item.frequency)

                if (todayItems.size > 1) {
                    views.setViewVisibility(R.id.btnPrev, View.VISIBLE)
                    views.setViewVisibility(R.id.btnNext, View.VISIBLE)
                } else {
                    views.setViewVisibility(R.id.btnPrev, View.GONE)
                    views.setViewVisibility(R.id.btnNext, View.GONE)
                }

                val nextIntent = Intent(context, Finminder::class.java).apply { action = ACTION_NEXT }
                val prevIntent = Intent(context, Finminder::class.java).apply { action = ACTION_PREV }

                val nextPending = PendingIntent.getBroadcast(context, widgetId, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val prevPending = PendingIntent.getBroadcast(context, widgetId, prevIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                views.setOnClickPendingIntent(R.id.btnNext, nextPending)
                views.setOnClickPendingIntent(R.id.btnPrev, prevPending)
            }

            // Apply Dynamic Theme
            val theme = ThemeHelper.getCurrentTheme(context)
            val isWhite = theme == "White"
            val isBlue = theme == "Blue"

            val bgCapsuleRes = when {
                isWhite -> R.drawable.bg_widget_capsule_white
                isBlue -> R.drawable.bg_widget_capsule_blue
                else -> R.drawable.bg_widget_capsule
            }
            views.setInt(R.id.widgetCapsule, "setBackgroundResource", bgCapsuleRes)

            val textColor = if (isWhite) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            views.setTextColor(R.id.tvWidgetTitle, textColor)
            views.setTextColor(R.id.tvWidgetType, textColor)
            views.setTextColor(R.id.tvWidgetAmount, textColor)
            views.setTextColor(R.id.tvWidgetFrequency, textColor)
            views.setTextColor(R.id.tvEmptyState, textColor)

            val arrowRes = if (isWhite) R.drawable.ic_widget_arrow_left_black else R.drawable.ic_widget_arrow_left_white
            views.setImageViewResource(R.id.btnNext, arrowRes)
            views.setImageViewResource(R.id.btnPrev, arrowRes)

            // Click to open FinminderActivity or Toast if logged out
            val rootIntent = Intent(context, WidgetLaunchActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("WIDGET_TYPE", "FinminderList")
            }
            val rootPending = PendingIntent.getActivity(context, widgetId, rootIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.walletWidgetRoot, rootPending)
            views.setOnClickPendingIntent(R.id.widgetCapsule, rootPending)

            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}
