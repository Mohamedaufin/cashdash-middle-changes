package com.cash.dash

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.*

class SuccessActivity : ThemedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.success_popup)

        val recipientName = intent.getStringExtra("recipient_name") ?: "Unknown"
        val recipientUpiId = intent.getStringExtra("recipient_upi_id") ?: "Unknown"
        val amount = intent.getIntExtra("amount", 0)
        val paymentApp = intent.getStringExtra("payment_app") ?: "Google Pay"
        val upiUri = intent.getStringExtra("upi_uri") ?: ""

        // Avatar Letter
        val firstChar = if (recipientName.isNotEmpty()) recipientName.take(1).uppercase(Locale.getDefault()) else "U"
        findViewById<TextView>(R.id.tvAvatarLetter).text = firstChar

        // Recipient Headers
        findViewById<TextView>(R.id.tvRecipientNameHeader).text = "To $recipientName"
        val tvUpiHeader = findViewById<TextView>(R.id.tvRecipientUpiHeader)
        if (recipientUpiId.isNotEmpty() && recipientUpiId != "Unknown") {
            tvUpiHeader.text = recipientUpiId
            tvUpiHeader.visibility = View.VISIBLE
        } else {
            tvUpiHeader.visibility = View.GONE
        }

        // Amount Paid
        findViewById<TextView>(R.id.tvAmount).text = "₹$amount"

        // Date and Time (e.g., 30 May 2026, 3:30 pm)
        val timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis())
        val txnDate = Date(timestamp)
        val sdfDate = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
        val sdfTime = SimpleDateFormat("h:mm a", Locale.getDefault())
        val datePart = sdfDate.format(txnDate)
        val timePart = sdfTime.format(txnDate).lowercase(Locale.getDefault())
        findViewById<TextView>(R.id.tvDateTime).text = "$datePart, $timePart"



        // To details card row
        findViewById<TextView>(R.id.tvToDetailsLabel).text = "To: $recipientName"
        val toSubText = if (recipientUpiId.isNotEmpty() && recipientUpiId != "Unknown") {
            "$paymentApp • $recipientUpiId"
        } else {
            paymentApp
        }
        findViewById<TextView>(R.id.tvToDetailsSub).text = toSubText

        // From details card row
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val userName = prefs.getString("user_name", "User") ?: "User"

        findViewById<TextView>(R.id.tvFromDetailsLabel).text = "From: $userName"
        findViewById<TextView>(R.id.tvFromDetailsSub).text = paymentApp





        // Action Handlers
        findViewById<View>(R.id.btnBack).setOnClickListener {
            goToMain()
        }

        findViewById<MaterialButton>(R.id.btnPayAgain).setOnClickListener {
            if (upiUri.isNotEmpty()) {
                val payAgainIntent = Intent(this, ScannerActivity::class.java).apply {
                    putExtra("pay_again_upi", upiUri)
                }
                startActivity(payAgainIntent)
                finish()
            }
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val fromHistory = intent.getBooleanExtra("from_history", false)
        if (fromHistory) {
            super.onBackPressed()
        } else {
            goToMain()
        }
    }

    private fun goToMain() {
        val fromHistory = intent.getBooleanExtra("from_history", false)
        if (fromHistory) {
            finish()
        } else {
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(mainIntent)
            finish()
        }
    }
}
