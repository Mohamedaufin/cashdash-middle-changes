package com.cash.dash

import android.content.Context
import android.widget.Toast

object ToastHelper {
    private var currentToast: Toast? = null

    fun showToast(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT, gravity: Int? = null, yOffset: Int = 0) {
        currentToast?.cancel()
        val toast = Toast.makeText(context, message, duration)
        if (gravity != null) {
            toast.setGravity(gravity, 0, yOffset)
        }
        currentToast = toast
        toast.show()
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

    /** The Activity behind a Context, unwrapping ContextWrappers (themed contexts). */
    private fun activityOf(context: Context): android.app.Activity? {
        var current: Context? = context
        while (current is android.content.ContextWrapper) {
            if (current is android.app.Activity) return current
            current = current.baseContext
        }
        return null
    }

    fun showErrorDialog(context: Context, title: String, message: String) {
        // Callers reach this from coroutines that may resume after their host
        // Activity is gone — showing a dialog on a dead window throws
        // BadTokenException ("token ... is not valid; is your activity running?")
        // and takes the whole app down. There is no window to show it in, so drop it.
        val activity = activityOf(context)
        if (activity != null && (activity.isFinishing || activity.isDestroyed)) {
            android.util.Log.w("ToastHelper", "Dropped error dialog for a dead activity: $title — $message")
            return
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy") { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Error Message", message)
                clipboard.setPrimaryClip(clip)
                showToast(context, "Error copied to clipboard")
            }
            .create()
        dialog.show()
    }
}
