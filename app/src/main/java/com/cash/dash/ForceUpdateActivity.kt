package com.cash.dash

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class ForceUpdateActivity : ThemedActivity() {

    companion object {
        const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.cash.dash&hl=en_IN"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_SUBTITLE = "extra_subtitle"
        const val EXTRA_BUTTON_TEXT = "extra_button_text"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_force_update)

        val tvTitle = findViewById<TextView>(R.id.tvUpdateTitle)
        val tvSubtitle = findViewById<TextView>(R.id.tvUpdateSubtitle)
        val btnUpdate = findViewById<Button>(R.id.btnUpdateApp)

        // Custom text from Intent if provided
        intent.getStringExtra(EXTRA_TITLE)?.let {
            if (it.isNotBlank()) tvTitle.text = it
        }
        intent.getStringExtra(EXTRA_SUBTITLE)?.let {
            if (it.isNotBlank()) tvSubtitle.text = it
        }
        intent.getStringExtra(EXTRA_BUTTON_TEXT)?.let {
            if (it.isNotBlank()) btnUpdate.text = it
        }

        btnUpdate.setOnClickListener {
            openPlayStore()
        }
    }

    private fun openPlayStore() {
        val packageName = packageName
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
        marketIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            startActivity(marketIntent)
        } catch (e: Exception) {
            // Fallback to web browser Play Store link
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_URL))
            webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(webIntent)
            } catch (ex: Exception) {
                Toast.makeText(this, "Could not open Play Store", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Prevent back press to lock the screen until updated
        ToastHelper.showToast(this, "Please update CashDash to continue")
    }
}
