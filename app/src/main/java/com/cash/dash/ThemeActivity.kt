package com.cash.dash

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class ThemeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_theme)

        // Fullscreen
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        val themeBlack = findViewById<android.view.View>(R.id.themeBlack)
        val themeBlue = findViewById<android.view.View>(R.id.themeBlue)
        val themeWhite = findViewById<android.view.View>(R.id.themeWhite)

        val prefs = getSharedPreferences("ThemePrefs", Context.MODE_PRIVATE)
        val currentTheme = prefs.getString("current_theme", "Black")

        themeBlack.setOnClickListener {
            handleThemeSelection("Black", currentTheme)
        }

        themeBlue.setOnClickListener {
            handleThemeSelection("Blue", currentTheme)
        }

        themeWhite.setOnClickListener {
            handleThemeSelection("White", currentTheme)
        }
    }

    private fun handleThemeSelection(selectedTheme: String, currentTheme: String?) {
        if (selectedTheme == currentTheme) {
            Toast.makeText(this, "You are already in $selectedTheme theme", Toast.LENGTH_SHORT).show()
            return
        }

        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 60, 60, 50)
            setBackgroundResource(R.drawable.bg_transaction)
        }

        android.widget.TextView(this).apply {
            text = "Change Theme"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 20)
            box.addView(this)
        }

        android.widget.TextView(this).apply {
            text = "You are about to change your theme of cashdash. Are you sure to proceed?"
            textSize = 16f
            setTextColor(Color.parseColor("#A0A0A0"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 50)
            box.addView(this)
        }

        val btnContainer = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setView(box).setCancelable(false).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        android.widget.Button(this).apply {
            text = "No"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_glass_input)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, 140, 1f).apply { setMargins(0, 0, 15, 0) }
            setOnClickListener { dialog.dismiss() }
            btnContainer.addView(this)
        }

        android.widget.Button(this).apply {
            text = "Yes"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_glass_input)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, 140, 1f).apply { setMargins(15, 0, 0, 0) }
            setOnClickListener {
                dialog.dismiss()
                when (selectedTheme) {
                    "White" -> Toast.makeText(this@ThemeActivity, "Coming soon", Toast.LENGTH_SHORT).show()
                    "Blue" -> {
                        // Right now do nothing as per request
                    }
                    "Black" -> {
                        // Current default theme
                        val prefs = getSharedPreferences("ThemePrefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("current_theme", "Black").apply()
                        Toast.makeText(this@ThemeActivity, "Black theme applied", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            btnContainer.addView(this)
        }

        box.addView(btnContainer)
        dialog.show()
    }
}
