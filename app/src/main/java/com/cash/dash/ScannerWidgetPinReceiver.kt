package com.cash.dash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScannerWidgetPinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val manufacturer = android.os.Build.MANUFACTURER
        if ("xiaomi".equals(manufacturer, ignoreCase = true) || 
            "poco".equals(manufacturer, ignoreCase = true) || 
            "redmi".equals(manufacturer, ignoreCase = true)) {
            
            ToastHelper.showCustomToast(context, "Scanner successfully added to Home Screen", 2000L)
        }
    }
}
