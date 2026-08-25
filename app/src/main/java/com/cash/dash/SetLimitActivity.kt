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

        input = findViewById(R.id.txtInput)

        val numberPad: GridLayout = findViewById(R.id.numberPad)
        val backspace: Button = findViewById(R.id.btnBackspace)
        val back: ImageView = findViewById(R.id.btnBack)
        val next: ImageButton = findViewById(R.id.btnNext)
        val numpadContainer: FrameLayout = findViewById(R.id.numpadContainer)
        val categoryInfoContainer: LinearLayout = findViewById(R.id.categoryInfoContainer)
        val tvCategoryName: TextView = findViewById(R.id.tvCategoryName)
        val tvCurrentLimit: TextView = findViewById(R.id.tvCurrentLimit)

        val categoryName = intent.getStringExtra("CATEGORY_NAME") ?: ""
        val prefs = getSharedPreferences("CategoryPrefs", android.content.Context.MODE_PRIVATE)
        val limit = prefs.getInt("LIMIT_$categoryName", 0)

        if (categoryName.isNotEmpty()) {
            val limitStr = if (limit > 0) "₹$limit" else "—"
            tvCategoryName.text = categoryName
            tvCurrentLimit.text = "Current Limit: $limitStr"
            categoryInfoContainer.visibility = View.VISIBLE
        } else {
            categoryInfoContainer.visibility = View.GONE
        }

        ViewCompat.setOnApplyWindowInsetsListener(numpadContainer) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, systemBars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        for (i in 0 until numberPad.childCount) {
            val child = numberPad.getChildAt(i)
            if (child is Button && child.id != R.id.btnBackspace) {
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

                val walletPrefs = WalletStore.get(this)
                val totalBalance = walletPrefs.getInt("initial_balance", 0).coerceAtLeast(0)

                val limitPrefs = getSharedPreferences("CategoryPrefs", MODE_PRIVATE)
                val catPrefs = getSharedPreferences("allocator_prefs", MODE_PRIVATE)
                val customCategories = catPrefs.getStringSet("allocator_categories", emptySet()) ?: emptySet()
                val defaultCategories = listOf("Food", "Shopping", "Entertainment", "Travel", "Bills", "Health", "Other")
                val categories = defaultCategories + customCategories

                var currentSum = 0
                for (cat in categories) {
                    if (!cat.equals(categoryName, ignoreCase = true)) {
                        currentSum += limitPrefs.getInt("LIMIT_$cat", 0)
                    }
                }
                val maxAllowed = totalBalance - currentSum

                if (newLimit > maxAllowed) {
                    ToastHelper.showToast(this@SetLimitActivity, "Exceeds total balance! Max allowed: ₹$maxAllowed")
                    input.setText(maxAllowed.toString())
                    return
                }

                limitPrefs.edit().putInt("LIMIT_$categoryName", newLimit).apply()

                ToastHelper.showToast(this, "Limit for $categoryName updated to ₹$newLimit")
                
                FirestoreSyncManager.pushAllDataToCloud(this)

                finish()
            }
        }

        next.setOnClickListener { saveLimitAndFinish() }
        back.setOnClickListener { finish() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("enteredLimit", input.text.toString())
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val savedLimit = savedInstanceState.getString("enteredLimit", "")
        if (!savedLimit.isNullOrEmpty()) {
            input.setText(savedLimit)
        }
    }
}
