package com.cash.dash

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class ThemeActivity : ThemedActivity() {
    private var currentToast: Toast? = null

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
        currentToast?.cancel()
        if (selectedTheme == currentTheme) {
            currentToast = Toast.makeText(this, "You are already in $selectedTheme theme", Toast.LENGTH_SHORT)
            currentToast?.show()
            return
        }

        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 60, 60, 50)
            setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(context, R.drawable.bg_transaction))
        }

        android.widget.TextView(this).apply {
            text = "Change Theme"
            textSize = 22f
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@ThemeActivity, R.attr.textPrimaryColor))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 20)
            box.addView(this)
        }

        android.widget.TextView(this).apply {
            text = "You are about to change your theme of cashdash. Are you sure to proceed?"
            textSize = 16f
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@ThemeActivity, R.attr.textMutedColor))
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
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@ThemeActivity, R.attr.textPrimaryColor))
            background = androidx.core.content.ContextCompat.getDrawable(context, com.cash.dash.ThemeHelper.getDrawable(context, R.drawable.bg_glass_input))
            layoutParams = android.widget.LinearLayout.LayoutParams(0, 140, 1f).apply { setMargins(0, 0, 15, 0) }
            setOnClickListener { dialog.dismiss() }
            btnContainer.addView(this)
        }

        android.widget.Button(this).apply {
            text = "Yes"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@ThemeActivity, R.attr.textPrimaryColor))
            background = androidx.core.content.ContextCompat.getDrawable(context, com.cash.dash.ThemeHelper.getDrawable(context, R.drawable.bg_glass_input))
            layoutParams = android.widget.LinearLayout.LayoutParams(0, 140, 1f).apply { setMargins(15, 0, 0, 0) }
            setOnClickListener {
                dialog.dismiss()
                when (selectedTheme) {
                    "White" -> {
                        val prefs = getSharedPreferences("ThemePrefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("current_theme", "White").apply()
                        currentToast?.cancel()
                        currentToast = Toast.makeText(this@ThemeActivity, "White theme applied", Toast.LENGTH_SHORT)
                        currentToast?.show()

                        // Restart app to apply theme globally
                        val intent = android.content.Intent(this@ThemeActivity, MainActivity::class.java)
                        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    }
                    "Blue" -> {
                        val prefs = getSharedPreferences("ThemePrefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("current_theme", "Blue").apply()
                        currentToast?.cancel()
                        currentToast = Toast.makeText(this@ThemeActivity, "Blue theme applied", Toast.LENGTH_SHORT)
                        currentToast?.show()
                        
                        // Restart app to apply theme globally
                        val intent = android.content.Intent(this@ThemeActivity, MainActivity::class.java)
                        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    }
                    "Black" -> {
                        val prefs = getSharedPreferences("ThemePrefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("current_theme", "Black").apply()
                        currentToast?.cancel()
                        currentToast = Toast.makeText(this@ThemeActivity, "Black theme applied", Toast.LENGTH_SHORT)
                        currentToast?.show()
                        
                        // Restart app to apply theme globally
                        val intent = android.content.Intent(this@ThemeActivity, MainActivity::class.java)
                        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    }
                }
            }
            btnContainer.addView(this)
        }

        box.addView(btnContainer)
        dialog.show()
    }
}
