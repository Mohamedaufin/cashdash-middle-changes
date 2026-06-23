package com.cash.dash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TaptrackWidgetPinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        ToastHelper.showCustomToast(context, "TapTrack widget added to Home Screen", 2000L)
    }
}
