package com.cash.dash

import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

fun View.setOnFastLongClickListener(delayMs: Long = 400L, action: () -> Unit) {
    this.setOnLongClickListener {
        action()
        true
    }
}
