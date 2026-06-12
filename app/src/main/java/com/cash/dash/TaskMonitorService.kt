package com.cash.dash

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class TaskMonitorService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("TaskMonitorService", "Service started")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d("TaskMonitorService", "onTaskRemoved triggered - marking offline")
        CashDashApplication.setOfflineImmediate(this)
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }
}
