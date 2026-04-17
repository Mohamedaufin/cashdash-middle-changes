package com.cash.dash

import android.content.Context
import android.widget.Toast

object ToastHelper {
    private var currentToast: Toast? = null

    fun showToast(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        currentToast?.cancel()
        currentToast = Toast.makeText(context, message, duration)
        currentToast?.show()
    }

    fun showCustomToast(context: Context, message: String, durationMs: Long) {
        currentToast?.cancel()
        val toast = Toast.makeText(context, message, Toast.LENGTH_LONG)
        currentToast = toast
        toast.show()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            toast.cancel()
        }, durationMs)
    }
}
