package com.cash.dash

import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

fun View.setOnFastLongClickListener(delayMs: Long = 400L, action: () -> Unit) {
    this.isClickable = true // Ensure view is clickable so it receives ACTION_UP
    var handler: Handler? = null
    var longClickRunnable: Runnable? = null
    var isLongPressFired = false
    var startX = 0f
    var startY = 0f

    this.setOnTouchListener { v, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isLongPressFired = false
                startX = event.x
                startY = event.y
                handler = Handler(Looper.getMainLooper())
                longClickRunnable = Runnable {
                    isLongPressFired = true
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    action()
                }
                handler?.postDelayed(longClickRunnable!!, delayMs)
                // Do not consume ACTION_DOWN so normal clicks can still register
                false
            }
            MotionEvent.ACTION_MOVE -> {
                // If user moves finger significantly, cancel the long press
                if (abs(event.x - startX) > 15f || abs(event.y - startY) > 15f) {
                    handler?.removeCallbacksAndMessages(null)
                }
                false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler?.removeCallbacksAndMessages(null)
                if (isLongPressFired && event.action == MotionEvent.ACTION_UP) {
                    // Consume the UP event if long press fired so regular onClick isn't triggered
                    return@setOnTouchListener true
                }
                false
            }
            else -> false
        }
    }
}
