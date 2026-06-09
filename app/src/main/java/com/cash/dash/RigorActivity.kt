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

class RigorActivity : ThemedActivity() {

    private lateinit var categoryList: LinearLayout
    private val PREFS = "CategoryPrefs"
    private val KEY = "categories"

    private var enteredAmount = 0
    private var selectedExpenseDate: Long = -1L
    private lateinit var inputTitle: EditText
    private var isPage2 = false

    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Robust refresh: only reload if we are actually viewing the categories page
            if (isPage2 && !isFinishing) {
                loadCategories()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rigor)

        TutorialManager.showTutorialIfNeeded(
            this,
            "tut_rigor",
            "Rigor Tracker",
            "This helps to manually record your expenses\n\n1. Enter expense title and amount\n2. Choose date of expense from calendar\n3. Tap Next and choose an allocation\n4. Thats it, Your expense has been recorded manually and wallet has been detucted\n\n*(Note: You can revisit these instructions anytime in the 'Help' section! Tap the Menu icon located next to 'Hello' on your Home dashboard to find it.)*"
        )

        inputTitle = findViewById(R.id.Title)
        val inputAmount = findViewById<EditText>(R.id.inputAmount)
        val btnNext = findViewById<Button>(R.id.btnNext)

        categoryList = findViewById(R.id.categoryListContainer)

        showPage1()

        // The CalendarView is styled via calendarTheme in themes.xml
        val calendarExpense = findViewById<android.widget.CalendarView>(R.id.calendarExpense)

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
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@RigorActivity, R.attr.textPrimaryColor))
            isAllCaps = false
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_subhead))
            background = ContextCompat.getDrawable(context, com.cash.dash.ThemeHelper.getDrawable(context, R.drawable.bg_glass_3d))
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (54 * resources.displayMetrics.density).toInt()
            ).apply { setMargins(0, 20, 0, 30) }
            
            setOnClickListener {
                showCreateCategoryDialog()
            }
        }
        categoryList.addView(btnCreateNew)

        val savedList = prefs.getStringSet(KEY, emptySet()) ?: emptySet()
        val recentCatsStr = getSharedPreferences("ScannerPrefs", MODE_PRIVATE).getString("recent_rigor_allocations", "") ?: ""
        val recentCats = if (recentCatsStr.isNotEmpty()) recentCatsStr.split("|").toMutableList() else mutableListOf()
        
        val sortedList = mutableListOf<String>()
        for (r in recentCats) {
            if (savedList.contains(r)) sortedList.add(r)
        }
        val remainingSorted = savedList.filter { !sortedList.contains(it) }.sortedBy { it.lowercase() }
        sortedList.addAll(remainingSorted)

        for (name in sortedList) {

            val row = layoutInflater.inflate(R.layout.item_rigor_category, categoryList, false)

            val txtName = row.findViewById<TextView>(R.id.categoryName)
            val spentBar = row.findViewById<View>(R.id.spentBar)
            val progressOuter = row.findViewById<View>(R.id.progressOuter)
            val txtSpent = row.findViewById<TextView>(R.id.txtSpent)
            val txtLimit = row.findViewById<TextView>(R.id.txtLimit)

            // 🔮 AI Keyword Custom Icons
            val iconView = row.findViewById<ImageView>(R.id.categoryIcon)
            val iconRes = CategoryIconHelper.getIconForCategory(this, name)
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

                val anim = android.animation.ValueAnimator.ofInt(0, targetWidth)
                anim.addUpdateListener { valueAnimator ->
                    val value = valueAnimator.animatedValue as Int
                    spentBar.layoutParams.width = value
                    spentBar.requestLayout()
                }
                anim.duration = 500
                anim.start()

                if (limit > 0 && spent >= limit) {
                    spentBar.setBackgroundResource(R.drawable.bg_glass_progress_fill_red)
                } else {
                    spentBar.setBackgroundResource(R.drawable.bg_glass_progress_fill)
                }
            }

            row.setOnClickListener {
                val sPrefs = getSharedPreferences("ScannerPrefs", MODE_PRIVATE)
                val historyStr = sPrefs.getString("recent_rigor_allocations", "") ?: ""
                val history = if (historyStr.isNotEmpty()) historyStr.split("|").toMutableList() else mutableListOf()
                history.remove(name)
                history.add(0, name)
                if (history.size > 3) history.subList(3, history.size).clear()
                sPrefs.edit().putString("recent_rigor_allocations", history.joinToString("|")).apply()
                saveExpense(name)
            }
            categoryList.addView(row)
        }
    }

    private fun showCreateCategoryDialog() {
        val density = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (28 * density).toInt()
            setPadding(p, p, p, (24 * density).toInt())
            setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(context, R.drawable.bg_transaction))
        }

        val titleView = TextView(this).apply {
            text = "New Allocation"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_title))
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@RigorActivity, R.attr.textPrimaryColor))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, (20 * density).toInt())
        }
        box.addView(titleView)

        val inputName = EditText(this).apply {
            hint = "Category Name (e.g. Travel)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@RigorActivity, R.attr.textPrimaryColor))
            setHintTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@RigorActivity, R.attr.textMutedColor))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, (16 * density).toInt())
            }
        }
        box.addView(inputName)

        val inputLimit = EditText(this).apply {
            hint = "Monthly Limit (Optional)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@RigorActivity, R.attr.textPrimaryColor))
            setHintTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@RigorActivity, R.attr.textMutedColor))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, (28 * density).toInt())
            }
        }
        box.addView(inputLimit)

        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            clipChildren = false
            clipToPadding = false
        }

        val dialog = AlertDialog.Builder(this)
            .setView(box)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnCancel = android.widget.Button(this).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@RigorActivity, R.attr.textPrimaryColor))
            val tv = android.util.TypedValue()
            context.theme.resolveAttribute(R.attr.cardBackground, tv, true)
            background = androidx.core.content.ContextCompat.getDrawable(context, tv.resourceId)
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, (8 * density).toInt(), 0)
            }
            minHeight = (54 * density).toInt()
            setOnClickListener { dialog.dismiss() }
        }
        buttonContainer.addView(btnCancel)

        val btnSave = android.widget.Button(this).apply {
            text = "Create"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@RigorActivity, R.attr.textPrimaryColor))
            val tv = android.util.TypedValue()
            context.theme.resolveAttribute(R.attr.cardBackground, tv, true)
            background = androidx.core.content.ContextCompat.getDrawable(context, tv.resourceId)
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins((8 * density).toInt(), 0, 0, 0)
            }
            minHeight = (54 * density).toInt()

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
            val titleText = inputTitle.text.toString().trim().replace("|", "-")
            
            val cal = Calendar.getInstance().apply { timeInMillis = selectedExpenseDate }
            val now = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, now.get(Calendar.HOUR_OF_DAY))
            cal.set(Calendar.MINUTE, now.get(Calendar.MINUTE))
            cal.set(Calendar.SECOND, now.get(Calendar.SECOND))
            cal.set(Calendar.MILLISECOND, now.get(Calendar.MILLISECOND))

            HistoryDataManager.saveTransaction(this, titleText, enteredAmount.toFloat(), category, cal.timeInMillis)
            ToastHelper.showCustomToast(this, "Expense recorded successfully!", 1600L)
            finish()
        } catch (e: Exception) {
            ToastHelper.showToast(this, "⚠ Error saving expense")
        }
    }

}
