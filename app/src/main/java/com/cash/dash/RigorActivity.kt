@file:Suppress("DEPRECATION")
package com.cash.dash

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.animation.ScaleAnimation
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import java.util.*

class RigorActivity : AppCompatActivity() {

    private lateinit var categoryList: LinearLayout
    private val PREFS = "CategoryPrefs"
    private val KEY = "categories"

    private var enteredAmount = 0
    private var selectedExpenseDate: Long = -1L
    private lateinit var inputTitle: EditText
    private var isPage2 = false

    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isPage2) loadCategories()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rigor)

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

        inputTitle = findViewById(R.id.Title)
        val inputAmount = findViewById<EditText>(R.id.inputAmount)
        val btnNext = findViewById<Button>(R.id.btnNext)
        val calendarExpense = findViewById<CalendarView>(R.id.calendarExpense)

        categoryList = findViewById(R.id.categoryListContainer)

        showPage1()

        calendarExpense.setOnDateChangeListener { _, year, month, day ->
            val cal = Calendar.getInstance()
            cal.set(year, month, day)
            selectedExpenseDate = cal.timeInMillis
        }

        btnNext.setOnClickListener {

            val titleText = inputTitle.text.toString().trim()
            if (titleText.isEmpty()) {
                ToastHelper.showToast(this, "Enter a title")
                return@setOnClickListener
            }

            val amtText = inputAmount.text.toString().trim()
            if (amtText.isEmpty()) {
                ToastHelper.showToast(this, "Enter an amount")
                return@setOnClickListener
            }

            enteredAmount = amtText.toIntOrNull() ?: 0
            if (enteredAmount <= 0) {
                ToastHelper.showToast(this, "Invalid amount")
                return@setOnClickListener
            }

            if (selectedExpenseDate == -1L)
                selectedExpenseDate = Calendar.getInstance().timeInMillis

            showPage2()
            loadCategories()
        }
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            syncReceiver, IntentFilter(FirestoreSyncManager.ACTION_SYNC_UPDATE)
        )
    }

    override fun onStop() {
        super.onStop()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(syncReceiver)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isPage2) {
            showPage1()
        } else {
            super.onBackPressed()
        }
    }

    private fun showPage1() {
        isPage2 = false
        findViewById<View>(R.id.rigorScrollPage1).visibility = View.VISIBLE
        findViewById<View>(R.id.rigorPage2).visibility = View.GONE
    }

    private fun showPage2() {
        isPage2 = true
        findViewById<View>(R.id.rigorScrollPage1).visibility = View.GONE
        findViewById<View>(R.id.rigorPage2).visibility = View.VISIBLE
    }

    private fun loadCategories() {

        categoryList.removeAllViews()

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val limitPrefs = getSharedPreferences("CategoryPrefs", MODE_PRIVATE)
        val spentPrefs = getSharedPreferences("GraphData", MODE_PRIVATE)

        // Add "Create New Allocation" button at the very top of Rigor category list
        val btnCreateNew = Button(this).apply {
            text = "+ Create New Allocation"
            setTextColor(Color.WHITE)
            isAllCaps = false
            textSize = 16f
            background = ContextCompat.getDrawable(context, R.drawable.bg_glass_3d)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 150
            ).apply { setMargins(0, 20, 0, 30) }
            
            setOnClickListener {
                showCreateCategoryDialog()
            }
        }
        categoryList.addView(btnCreateNew)

        val savedList = prefs.getStringSet(KEY, emptySet()) ?: emptySet()

        for (name in savedList) {

            val row = layoutInflater.inflate(R.layout.item_rigor_category, categoryList, false)

            val txtName = row.findViewById<TextView>(R.id.categoryName)
            val spentBar = row.findViewById<View>(R.id.spentBar)
            val progressOuter = row.findViewById<View>(R.id.progressOuter)
            val txtSpent = row.findViewById<TextView>(R.id.txtSpent)
            val txtLimit = row.findViewById<TextView>(R.id.txtLimit)

            // 🔮 AI Keyword Custom Icons
            val iconView = row.findViewById<ImageView>(R.id.categoryIcon)
            val iconRes = CategoryIconHelper.getIconForCategory(name)
            iconView.setImageResource(iconRes)

            txtName.text = name

            val limit = limitPrefs.getInt("LIMIT_$name", 0)
            val spent = spentPrefs.getFloat("SPENT_$name", 0f)

            txtSpent.text = "Spent: ₹${spent.toInt()}"
            txtLimit.text = if (limit > 0) "Limit: ₹$limit" else "Limit: —"

            val progress = if (limit > 0) (spent / limit).coerceIn(0f, 1f) else 0f

            row.post {
                val maxWidth = progressOuter.width
                val targetWidth = (maxWidth * progress).toInt()

                spentBar.clearAnimation()

                val anim = ScaleAnimation(
                    0f, progress, 1f, 1f,
                    ScaleAnimation.RELATIVE_TO_SELF, 0f,
                    ScaleAnimation.RELATIVE_TO_SELF, 0f
                )
                anim.duration = 500
                anim.fillAfter = true

                spentBar.startAnimation(anim)

                spentBar.layoutParams.width = targetWidth
                spentBar.requestLayout()

                if (limit > 0 && spent >= limit) {
                    spentBar.setBackgroundResource(R.drawable.bg_glass_progress_fill_red)
                } else {
                    spentBar.setBackgroundResource(R.drawable.bg_glass_progress_fill)
                }
            }

            row.setOnClickListener { saveExpense(name) }
            categoryList.addView(row)
        }
    }

    private fun showCreateCategoryDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(60, 60, 60, 50)
        box.setBackgroundResource(R.drawable.bg_transaction)

        val titleView = TextView(this).apply {
            text = "New Allocation"
            textSize = 22f
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_primary))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }
        box.addView(titleView)

        val inputName = EditText(this).apply {
            hint = "Category Name (e.g. Travel)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_dim))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 30)
            }
        }
        box.addView(inputName)

        val inputLimit = EditText(this).apply {
            hint = "Monthly Limit (Optional)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_dim))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 50)
            }
        }
        box.addView(inputLimit)

        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(box)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnCancel = android.widget.Button(this).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_primary))
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_glass_input)
            layoutParams = LinearLayout.LayoutParams(0, 140, 1f).apply {
                setMargins(0, 0, 15, 0)
            }
            setOnClickListener { dialog.dismiss() }
        }
        buttonContainer.addView(btnCancel)

        val btnSave = android.widget.Button(this).apply {
            text = "Create"
            isAllCaps = false
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_primary))
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_glass_input)
            layoutParams = LinearLayout.LayoutParams(0, 140, 1f).apply {
                setMargins(15, 0, 0, 0)
            }
            setOnClickListener {
                val catName = inputName.text.toString().trim().replace("|", "-")
                if (catName.equals("Overall", ignoreCase = true)) {
                    ToastHelper.showToast(this@RigorActivity, "'Overall' is a reserved name")
                    return@setOnClickListener
                }
                if (catName.isNotEmpty()) {
                    val prefs = getSharedPreferences("CategoryPrefs", MODE_PRIVATE)
                    val editor = prefs.edit()
                    
                    val existing = prefs.getStringSet("categories", emptySet())?.toMutableSet() ?: mutableSetOf()
                    
                    val limitStr = inputLimit.text.toString()
                    val newLimit = if (limitStr.isNotEmpty()) limitStr.toIntOrNull() ?: 0 else 0
                    
                    val walletPrefs = getSharedPreferences("WalletPrefs", MODE_PRIVATE)
                    val totalBalance = walletPrefs.getInt("initial_balance", 0).coerceAtLeast(0)
                    
                    var currentSumOfLimits = 0
                    for (cat in existing) {
                        currentSumOfLimits += prefs.getInt("LIMIT_$cat", 0)
                    }
                    val maxAllowed = totalBalance - currentSumOfLimits
                    
                    if (newLimit > maxAllowed) {
                        ToastHelper.showToast(this@RigorActivity, "Exceeds total balance! Max allowed: ₹$maxAllowed")
                        return@setOnClickListener
                    }

                    existing.add(catName)
                    editor.putStringSet("categories", existing)
                    
                    if (newLimit > 0) {
                        editor.putInt("LIMIT_$catName", newLimit)
                    }
                    editor.apply()
                    
                    // Immediately rebuild the list without leaving the screen
                    FirestoreSyncManager.pushAllDataToCloud(this@RigorActivity)
                    loadCategories()
                    dialog.dismiss()
                }
            }
        }
        buttonContainer.addView(btnSave)
        box.addView(buttonContainer)

        dialog.show()
    }

    private fun saveExpense(category: String) {
        try {
            val prefs = getSharedPreferences("GraphData", MODE_PRIVATE)
            val weeklyPrefs = getSharedPreferences("CategoryWeekData", MODE_PRIVATE)
            val walletPrefs = getSharedPreferences("WalletPrefs", MODE_PRIVATE)
            
            val editor = prefs.edit()
            val weekEditor = weeklyPrefs.edit()

            // Deduct from main balance
            val currentBal = walletPrefs.getInt("wallet_balance", 0)
            walletPrefs.edit().putInt("wallet_balance", currentBal - enteredAmount).apply()

            val titleText = inputTitle.text.toString().trim().replace("|", "-")
            val cal = Calendar.getInstance().apply { timeInMillis = selectedExpenseDate }
            
            val now = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, now.get(Calendar.HOUR_OF_DAY))
            cal.set(Calendar.MINUTE, now.get(Calendar.MINUTE))
            cal.set(Calendar.SECOND, now.get(Calendar.SECOND))
            cal.set(Calendar.MILLISECOND, now.get(Calendar.MILLISECOND))

            val timestamp = cal.timeInMillis.toString()
            val dayIndex = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
            val monthIndex = cal.get(Calendar.MONTH)
            val year = cal.get(Calendar.YEAR)
            val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
            cal.setFirstDayOfWeek(Calendar.MONDAY)
            cal.setMinimalDaysInFirstWeek(1)
            val weekIndex = cal.get(Calendar.WEEK_OF_MONTH) - 1

            val oldSpent = prefs.getFloat("SPENT_$category", 0f)
            editor.putFloat("SPENT_$category", oldSpent + enteredAmount)

            val dailyKey = "DAY_${weekIndex}_${dayIndex}_${monthIndex}_${year}"
            editor.putFloat(dailyKey, prefs.getFloat(dailyKey, 0f) + enteredAmount)

            val weeklyKey = "WEEK_${weekIndex}_${monthIndex}_${year}"
            editor.putFloat(weeklyKey, prefs.getFloat(weeklyKey, 0f) + enteredAmount)

            val monthlyKey = "MONTH_${monthIndex}_${year}"
            editor.putFloat(monthlyKey, prefs.getFloat(monthlyKey, 0f) + enteredAmount)

            val weekSlot = weekIndex + 1
            val categoryWeekKey = "${category}_W$weekSlot"
            val oldWeekValue = weeklyPrefs.getInt(categoryWeekKey, 0)
            weekEditor.putInt(categoryWeekKey, oldWeekValue + enteredAmount)

            val historySet = (prefs.getStringSet("HISTORY_LIST", emptySet()) ?: emptySet()).toMutableSet()
            historySet.add("EXP|$timestamp|$titleText|$category|$enteredAmount|$weekIndex|$dayIndex|$monthIndex|$year")
            editor.putStringSet("HISTORY_LIST", historySet)

            editor.putString("TRANS_${timestamp}_TITLE", titleText)
            editor.putString("TRANS_${timestamp}_CATEGORY", category)
            editor.putInt("TRANS_${timestamp}_AMOUNT", enteredAmount)
            editor.putInt("TRANS_${timestamp}_WEEK", weekIndex)
            editor.putInt("TRANS_${timestamp}_DAY", dayIndex)
            editor.putInt("TRANS_${timestamp}_MONTH", monthIndex)
            editor.putInt("TRANS_${timestamp}_YEAR", year)

            editor.apply()
            weekEditor.apply()
            
            FirestoreSyncManager.pushAllDataToCloud(this)

            finish()
        } catch (e: Exception) {
            ToastHelper.showToast(this, "⚠ Error saving expense")
        }
    }
}
