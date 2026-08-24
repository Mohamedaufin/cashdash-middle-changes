package com.cash.dash

import android.app.Activity
import android.view.WindowManager

/**
 * Blocks screenshots, screen recording, and the recents-list thumbnail for a screen.
 *
 * Applied to the ADMIN screens only, deliberately. Those display other users'
 * data — support threads, email addresses, presence — so leaking them via a
 * screenshot or a recents thumbnail exposes people who did not consent to it.
 *
 * NOT applied to the user's own wallet/history screens: people legitimately want
 * to screenshot their own spending, and blocking that is a product regression
 * rather than a security win. To extend coverage, call [apply] from the target
 * activity's onCreate (before setContentView is fine either way).
 */
object SecureScreen {
    fun apply(activity: Activity) {
        activity.window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }
}
