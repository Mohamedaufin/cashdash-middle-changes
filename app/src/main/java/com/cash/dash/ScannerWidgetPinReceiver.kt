package com.cash.dash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScannerWidgetPinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // Widget pinned successfully — OS already confirms, no toast needed
    }
}
