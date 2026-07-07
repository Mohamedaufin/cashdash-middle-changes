package com.cash.dash

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.google.firebase.auth.FirebaseAuth

class TrackerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.layout_tracker_widget)
        
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        if (firebaseUser == null) {
            views.setViewVisibility(R.id.tvLoggedOutText, View.VISIBLE)
            views.setViewVisibility(R.id.ivLogo, View.GONE)
        } else {
            views.setViewVisibility(R.id.tvLoggedOutText, View.GONE)
            views.setViewVisibility(R.id.ivLogo, View.VISIBLE)
        }

        // Launch the transparent activity that safely starts the Floating Service or shows Toast
        val intent = Intent(context, WidgetLaunchActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("WIDGET_TYPE", "Finminder")
        }
        val pendingIntent = PendingIntent.getActivity(
            context, appWidgetId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        views.setOnClickPendingIntent(R.id.widgetContainer, pendingIntent)
        
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
