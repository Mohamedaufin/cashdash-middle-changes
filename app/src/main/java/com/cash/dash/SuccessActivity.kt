package com.cash.dash

import android.os.Bundle
import android.view.View
import android.graphics.Color
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SuccessActivity : ThemedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.success_popup)

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

        findViewById<TextView>(R.id.paymentMessage).text =
            intent.getStringExtra("info") ?: "Payment Detected!"

        findViewById<android.view.View>(R.id.btnDone).setOnClickListener {
            finish()
        }
    }
}
