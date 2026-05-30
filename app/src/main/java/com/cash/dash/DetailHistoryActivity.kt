@file:Suppress("DEPRECATION")
package com.cash.dash

import android.os.Bundle
import android.view.View
import android.graphics.Color
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import android.widget.LinearLayout
import android.widget.Button
import android.widget.Toast
import android.view.Gravity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter

class DetailHistoryActivity : ThemedActivity() {
    
    private var mode: String = "DAILY"
    private var week: Int = 0
    private var day: Int = 0
    private var month: Int = 0
    private var year: Int = 0
    private var categoryFilter: String = "Overall"
    
    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail_history)

        mode = intent.getStringExtra("MODE") ?: "DAILY"
        week = intent.getIntExtra("WEEK", 0)
        day = intent.getIntExtra("DAY", 0)
        month = intent.getIntExtra("MONTH", 0)
        year = intent.getIntExtra("YEAR", 0)
        categoryFilter = intent.getStringExtra("FILTER_CATEGORY") ?: "Overall"
        
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        
        refreshUI()
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

    private fun refreshUI() {
        val title = findViewById<TextView>(R.id.tvDetailTitle)
        val graph = findViewById<CategoryBreakdownGraphView>(R.id.categoryGraph)
        val recycler = findViewById<RecyclerView>(R.id.recyclerTransactions)

        // Show loading state or just background it
        Thread {
            val data = HistoryDataManager.getCategoryBreakdown(
                this,
                mode,
                if (mode == "DAILY") day else week,
                week,
                month,
                year,
                categoryFilter
            )

            runOnUiThread {
                val cal = java.util.Calendar.getInstance().apply {
                    firstDayOfWeek = java.util.Calendar.MONDAY
                    minimalDaysInFirstWeek = 1
                    set(year, month, 1)
                    set(java.util.Calendar.WEEK_OF_MONTH, week + 1)
                    set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
                    if (get(java.util.Calendar.MONTH) != month) {
                        set(java.util.Calendar.MONTH, month)
                        set(java.util.Calendar.DAY_OF_MONTH, 1)
                    }
                    add(java.util.Calendar.DAY_OF_MONTH, day)
                }
                val dateStr = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(cal.time)

                title.text = when (mode) {
                    "DAILY" -> if (categoryFilter == "Overall") "Breakdown for $dateStr" else if (categoryFilter == "no choice") "Unallocated for $dateStr" else "$categoryFilter for $dateStr"
                    "WEEKLY" -> if (categoryFilter == "Overall") "Breakdown for Week ${week + 1}" else if (categoryFilter == "no choice") "Unallocated for Week ${week + 1}" else "$categoryFilter for Week ${week + 1}"
                    "MONTHLY" -> if (categoryFilter == "Overall") "Monthly Breakdown" else if (categoryFilter == "no choice") "Unallocated Monthly" else "$categoryFilter Monthly"
                    else -> "Details"
                }

                graph.setData(data.categories, data.values)

                recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
                val adapter = TransactionAdapter(data.transactions) { item ->
                    showTransactionActionMenu(item, mode, week, day, month, year, categoryFilter)
                }
                recycler.adapter = adapter
            }
        }.start()
    }

    private fun showTransactionActionMenu(item: TransactionItem, mode: String, week: Int, day: Int, month: Int, year: Int, categoryFilter: String) {
        val bottomSheet = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (24 * density).toInt()
            setPadding(p, p, p, (32 * density).toInt())
            setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(this.context, R.drawable.bg_transaction))
        }

        val title = TextView(this).apply {
            text = "Transaction Options"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_subhead))
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@DetailHistoryActivity, R.attr.textPrimaryColor))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (24 * density).toInt())
            gravity = android.view.Gravity.CENTER
        }
        container.addView(title)

        // EDIT TITLE BUTTON
        val btnEdit = android.widget.Button(this).apply {
            text = "Edit Title"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@DetailHistoryActivity, R.attr.textPrimaryColor))
            val tv = android.util.TypedValue()
            this@DetailHistoryActivity.theme.resolveAttribute(R.attr.cardBackground, tv, true)
            background = androidx.core.content.ContextCompat.getDrawable(this@DetailHistoryActivity, tv.resourceId)
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (54 * density).toInt()).apply {
                setMargins(0, 0, 0, (12 * density).toInt())
            }
            setOnClickListener {
                bottomSheet.dismiss()
                showEditTitleDialog(item, mode, week, day, month, year, categoryFilter)
            }
        }
        container.addView(btnEdit)
        
        // EDIT AMOUNT BUTTON
        val btnAmount = android.widget.Button(this).apply {
            text = "Edit Amount"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@DetailHistoryActivity, R.attr.textPrimaryColor))
            val tv = android.util.TypedValue()
            this@DetailHistoryActivity.theme.resolveAttribute(R.attr.cardBackground, tv, true)
            background = androidx.core.content.ContextCompat.getDrawable(this@DetailHistoryActivity, tv.resourceId)
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (54 * density).toInt()).apply {
                setMargins(0, 0, 0, (12 * density).toInt())
            }
            setOnClickListener {
                bottomSheet.dismiss()
                showEditAmountDialog(item, mode, week, day, month, year, categoryFilter)
            }
        }
        container.addView(btnAmount)

        // REALLOCATE BUTTON
        val btnReallocate = android.widget.Button(this).apply {
            text = "Reallocate Category"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@DetailHistoryActivity, R.attr.textPrimaryColor))
            val tv = android.util.TypedValue()
            this@DetailHistoryActivity.theme.resolveAttribute(R.attr.cardBackground, tv, true)
            background = androidx.core.content.ContextCompat.getDrawable(this@DetailHistoryActivity, tv.resourceId)
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (54 * density).toInt()).apply {
                setMargins(0, 0, 0, (12 * density).toInt())
            }
            setOnClickListener {
                bottomSheet.dismiss()
                showReallocationDialog(item, mode, week, day, month, year, categoryFilter)
            }
        }
        container.addView(btnReallocate)

        // DELETE BUTTON
        val btnDelete = android.widget.Button(this).apply {
            text = "Delete Transaction"
            isAllCaps = false
            setTextColor(android.graphics.Color.parseColor("#FF4D4D"))
            val tv = android.util.TypedValue()
            this@DetailHistoryActivity.theme.resolveAttribute(R.attr.cardBackground, tv, true)
            background = androidx.core.content.ContextCompat.getDrawable(this@DetailHistoryActivity, tv.resourceId)
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (54 * density).toInt())
            setOnClickListener {
                bottomSheet.dismiss()
                showDeleteConfirmation(item, mode, week, day, month, year, categoryFilter)
            }
        }
        container.addView(btnDelete)

        bottomSheet.setContentView(container)
        bottomSheet.show()
    }

    private fun showEditTitleDialog(item: TransactionItem, mode: String, week: Int, day: Int, month: Int, year: Int, categoryFilter: String) {
        val density = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (28 * density).toInt()
            setPadding(p, p, p, (24 * density).toInt())
            setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(this@DetailHistoryActivity, R.drawable.bg_transaction))
        }

        val titleView = TextView(this).apply {
            text = "Edit Title"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_subhead))
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@DetailHistoryActivity, R.attr.textPrimaryColor))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, (20 * density).toInt())
        }
        box.addView(titleView)

        val input = android.widget.EditText(this).apply {
            setText(item.title)
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@DetailHistoryActivity, R.attr.textPrimaryColor))
            setHintTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@DetailHistoryActivity, R.attr.textPrimaryColor))
            background = androidx.core.content.ContextCompat.getDrawable(this@DetailHistoryActivity, com.cash.dash.ThemeHelper.getDrawable(this@DetailHistoryActivity, R.drawable.bg_glass_input))
            setPadding(40, 40, 40, 40)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, (28 * density).toInt())
            }
        }
        box.addView(input)

        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            clipChildren = false
            clipToPadding = false
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(box)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnCancel = android.widget.Button(this).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@DetailHistoryActivity, R.attr.textPrimaryColor))
            val tv = android.util.TypedValue()
            this@DetailHistoryActivity.theme.resolveAttribute(R.attr.cardBackground, tv, true)
            background = androidx.core.content.ContextCompat.getDrawable(this@DetailHistoryActivity, tv.resourceId)
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(0, (54 * density).toInt(), 1f).apply {
                setMargins(0, 0, (8 * density).toInt(), 0)
            }
            setOnClickListener {
                dialog.dismiss()
            }
        }
        buttonContainer.addView(btnCancel)

        val btnSave = android.widget.Button(this).apply {
            text = "Save"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@DetailHistoryActivity, R.attr.textPrimaryColor))
            val tv = android.util.TypedValue()
            this@DetailHistoryActivity.theme.resolveAttribute(R.attr.cardBackground, tv, true)
            background = androidx.core.content.ContextCompat.getDrawable(this@DetailHistoryActivity, tv.resourceId)
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(0, (54 * density).toInt(), 1f).apply {
                setMargins((8 * density).toInt(), 0, 0, 0)
            }
            setOnClickListener {
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotEmpty()) {
                    updateTransactionTitle(item, newTitle)
                    refreshData(mode, week, day, month, year, categoryFilter)
                    FirestoreSyncManager.pushAllDataToCloud(this@DetailHistoryActivity)
                }
                dialog.dismiss()
            }
        }
        buttonContainer.addView(btnSave)

        box.addView(buttonContainer)

        dialog.show()
    }

    private fun showEditAmountDialog(item: TransactionItem, mode: String, week: Int, day: Int, month: Int, year: Int, categoryFilter: String) {
        val density = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (28 * density).toInt()
            setPadding(p, p, p, (24 * density).toInt())
            setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(this@DetailHistoryActivity, R.drawable.bg_transaction))
        }

        val titleView = TextView(this).apply {
            text = "Edit Amount"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_subhead))
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@DetailHistoryActivity, R.attr.textPrimaryColor))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, (20 * density).toInt())
        }
        box.addView(titleView)

        val input = android.widget.EditText(this).apply {
            setText(item.amount.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@DetailHistoryActivity, R.attr.textPrimaryColor))
            setHintTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@DetailHistoryActivity, R.attr.textPrimaryColor))
            background = androidx.core.content.ContextCompat.getDrawable(this@DetailHistoryActivity, com.cash.dash.ThemeHelper.getDrawable(this@DetailHistoryActivity, R.drawable.bg_glass_input))
            setPadding(40, 40, 40, 40)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, (28 * density).toInt())
            }
        }
        box.addView(input)

        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(box)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnCancel = android.widget.Button(this).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@DetailHistoryActivity, R.attr.textPrimaryColor))
            val tv = android.util.TypedValue()
            this@DetailHistoryActivity.theme.resolveAttribute(R.attr.cardBackground, tv, true)
            background = androidx.core.content.ContextCompat.getDrawable(this@DetailHistoryActivity, tv.resourceId)
            layoutParams = LinearLayout.LayoutParams(0, (54 * density).toInt(), 1f).apply {
                setMargins(0, 0, (8 * density).toInt(), 0)
            }
            setOnClickListener { dialog.dismiss() }
        }
        buttonContainer.addView(btnCancel)

        val btnSave = android.widget.Button(this).apply {
            text = "Save"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@DetailHistoryActivity, R.attr.textPrimaryColor))
            val tv = android.util.TypedValue()
            this@DetailHistoryActivity.theme.resolveAttribute(R.attr.cardBackground, tv, true)
            background = androidx.core.content.ContextCompat.getDrawable(this@DetailHistoryActivity, tv.resourceId)
            layoutParams = LinearLayout.LayoutParams(0, (54 * density).toInt(), 1f).apply {
                setMargins((8 * density).toInt(), 0, 0, 0)
            }
            setOnClickListener {
                val newAmt = input.text.toString().toIntOrNull() ?: 0
                if (newAmt > 0) {
                    updateTransactionAmount(item, newAmt)
                    refreshData(mode, week, day, month, year, categoryFilter)
                }
                dialog.dismiss()
            }
        }
        buttonContainer.addView(btnSave)
        box.addView(buttonContainer)
        dialog.show()
    }

    private fun showReallocationDialog(item: TransactionItem, mode: String, week: Int, day: Int, month: Int, year: Int, categoryFilter: String) {
        val bottomSheet = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (24 * density).toInt()
            setPadding(p, p, p, (32 * density).toInt())
            setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(this.context, R.drawable.bg_transaction))
        }

        val title = TextView(this).apply {
            text = "Reallocate ₹${item.amount}"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_subhead))
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@DetailHistoryActivity, R.attr.textPrimaryColor))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (24 * density).toInt())
            gravity = android.view.Gravity.CENTER
        }
        container.addView(title)

        val prefsCat = getSharedPreferences("CategoryPrefs", Context.MODE_PRIVATE)
        val categories = prefsCat.getStringSet("categories", emptySet())?.toMutableList() ?: mutableListOf()
        categories.removeAll { it.equals("no choice", ignoreCase = true) }

        val parts = item.rawEntry.split("|")
        val oldCat = if (parts.size >= 9) parts[3] else "no choice"
        val oldCatClean = oldCat.replace("(", "").replace(")", "").trim()

        if (categories.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No categories available."
                setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@DetailHistoryActivity, R.attr.textPrimaryColor))
                gravity = android.view.Gravity.CENTER
                setPadding(0, 0, 0, (24 * density).toInt())
            }
            container.addView(empty)
        } else {
            val scrollView = android.widget.ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                isFillViewport = true
            }
            val buttonListContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            
            for (cat in categories) {
                val catClean = cat.replace("(", "").replace(")", "").trim()
                if (catClean.equals(oldCatClean, ignoreCase = true)) continue
                
                val btn = android.widget.Button(this).apply {
                    text = cat
                    isAllCaps = false
                    setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@DetailHistoryActivity, R.attr.textPrimaryColor))
                    background = androidx.core.content.ContextCompat.getDrawable(
                        this@DetailHistoryActivity,
                        com.cash.dash.ThemeHelper.getDrawable(this@DetailHistoryActivity, R.drawable.bg_glass_3d)
                    )
                    stateListAnimator = null
                    elevation = 0f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (54 * density).toInt()
                    ).apply {
                        setMargins(0, 0, 0, (12 * density).toInt())
                    }
                    
                    setOnClickListener {
                        reallocateTransaction(item.rawEntry, oldCat, cat, item.amount)
                        bottomSheet.dismiss()
                        refreshData(mode, week, day, month, year, categoryFilter)
                    }
                }
                buttonListContainer.addView(btn)
            }
            scrollView.addView(buttonListContainer)
            container.addView(scrollView)
        }

        bottomSheet.setContentView(container)
        bottomSheet.show()
    }

    private fun updateTransactionTitle(item: TransactionItem, newTitle: String) {

        HistoryDataManager.updateTransactionTitle(this, item.rawEntry, newTitle)
        ToastHelper.showToast(this, "Title updated")
    }

    private fun showDeleteConfirmation(item: TransactionItem, mode: String, week: Int, day: Int, month: Int, year: Int, categoryFilter: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_action, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<TextView>(R.id.tvConfirmTitle).text = "Delete Transaction?"
        dialogView.findViewById<TextView>(R.id.tvConfirmMessage).text = "Are you sure to delete this transaction?"
        
        val btnYes = dialogView.findViewById<Button>(R.id.btnConfirmAction)
        val btnNo = dialogView.findViewById<Button>(R.id.btnConfirmCancel)

        btnYes.text = "Delete"
        btnYes.setOnClickListener {
            HistoryDataManager.deleteTransaction(this, item.rawEntry)
            dialog.dismiss()
            refreshData(mode, week, day, month, year, categoryFilter)
            ToastHelper.showToast(this, "Transaction deleted successfully")
        }
        
        btnNo.setOnClickListener { dialog.dismiss() }
        
        dialog.show()
    }

    private fun reallocateTransaction(rawEntry: String, oldCat: String, newCat: String, amount: Int) {
        HistoryDataManager.reallocateTransaction(this, rawEntry, newCat)
        ToastHelper.showToast(this, "Reallocated to $newCat")
    }

    private fun refreshData(mode: String, week: Int, day: Int, month: Int, year: Int, categoryFilter: String) {
        refreshUI()
    }

    private fun updateTransactionAmount(item: TransactionItem, newAmount: Int) {
        HistoryDataManager.updateTransactionAmount(this, item.rawEntry, newAmount)
        ToastHelper.showToast(this, "Amount updated")
    }
}
