package com.cash.dash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WalletWidgetPinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        ToastHelper.showCustomToast(context, "Finminder widget added to Home Screen", 2000L)
    }
}
