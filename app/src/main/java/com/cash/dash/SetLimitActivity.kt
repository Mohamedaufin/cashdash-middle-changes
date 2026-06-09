package com.cash.dash

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class SetLimitActivity : ThemedActivity() {

    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_set_limit)

        TutorialManager.showTutorialIfNeeded(
            this,
            "tut_setlimit",
            "Allocation Limits",
            "Set a strict spending limit on a specific allocation. If you spend more than this limit, the bar will turn red to warn you!"
        )

        input = findViewById(R.id.txtInput)

        val numberPad: GridLayout = findViewById(R.id.numberPad)
        val backspace: Button = findViewById(R.id.btnBackspace)
        val done: Button = findViewById(R.id.btnDone)
        val back: ImageButton = findViewById(R.id.btnBack)
        val next: ImageButton = findViewById(R.id.btnNext)
        val numpadContainer: FrameLayout = findViewById(R.id.numpadContainer)

        ViewCompat.setOnApplyWindowInsetsListener(numpadContainer) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, systemBars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        for (i in 0 until numberPad.childCount) {
            val child = numberPad.getChildAt(i)
            if (child is Button && child.id != R.id.btnBackspace && child.id != R.id.btnDone) {
                child.setOnClickListener { 
                    val currentText = input.text.toString()
                    if (currentText == "0") {
                        input.setText(child.text)
                    } else if (currentText.length < 9) {
                        input.append(child.text)
                    }
                }
            }
        }

        backspace.setOnClickListener {
            val txt = input.text.toString()
            if (txt.isNotEmpty() && txt != "0") {
                val newTxt = txt.dropLast(1)
                input.setText(if (newTxt.isEmpty()) "0" else newTxt)
            }
        }

        fun saveLimitAndFinish() {
            val value = input.text.toString()
            val categoryName = intent.getStringExtra("CATEGORY_NAME") ?: return

            if (value.isNotEmpty()) {
                val newLimit = value.toInt()

                val walletPrefs = getSharedPreferences("WalletPrefs", MODE_PRIVATE)
                val totalBalance = walletPrefs.getInt("initial_balance", 0).coerceAtLeast(0)

                val prefs = getSharedPreferences("CategoryPrefs", MODE_PRIVATE)
                val categories = prefs.getStringSet("categories", emptySet()) ?: emptySet()
                var currentSum = 0
                for (cat in categories) {
                    if (!cat.equals(categoryName, ignoreCase = true)) {
                        currentSum += prefs.getInt("LIMIT_$cat", 0)
                    }
                }
                val maxAllowed = totalBalance - currentSum

                if (newLimit > maxAllowed) {
                    ToastHelper.showToast(this@SetLimitActivity, "Exceeds total balance! Max allowed: ₹$maxAllowed")
                    input.setText(maxAllowed.toString())
                    return
                }

                prefs.edit().putInt("LIMIT_$categoryName", newLimit).apply()

                ToastHelper.showToast(this, "Limit for $categoryName updated to ₹$newLimit")
                
                FirestoreSyncManager.pushAllDataToCloud(this)

                finish()
            }
        }

        done.setOnClickListener { saveLimitAndFinish() }
        next.setOnClickListener { saveLimitAndFinish() }
        back.setOnClickListener { finish() }
    }
}
