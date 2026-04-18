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

    private fun updateTransactionTitle(item: TransactionItem, newTitle: String) {
        val prefs = getSharedPreferences("GraphData", Context.MODE_PRIVATE)
        val historyList = prefs.getStringSet("HISTORY_LIST", emptySet())?.toMutableSet() ?: mutableSetOf()

        if (historyList.remove(item.rawEntry)) {
            val parts = item.rawEntry.split("|").toMutableList()
            if (parts.size >= 9) {
                // Format: EXP|timestamp|title|category|amount|week|day|month|year
                val timestamp = parts[1]
                parts[2] = newTitle
                
                val newEntry = parts.joinToString("|")
                historyList.add(newEntry)
                
                val editor = prefs.edit()
                editor.putStringSet("HISTORY_LIST", historyList)
                editor.putString("TRANS_${timestamp}_TITLE", newTitle)
                editor.apply()
                
                ToastHelper.showToast(this, "Title updated")
            }
        }
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
            deleteTransaction(item)
            dialog.dismiss()
            refreshData(mode, week, day, month, year, categoryFilter)
            FirestoreSyncManager.pushAllDataToCloud(this)
        }
        
        btnNo.setOnClickListener { dialog.dismiss() }
        
        dialog.show()
    }

    private fun deleteTransaction(item: TransactionItem) {
        val prefsGraph = getSharedPreferences("GraphData", Context.MODE_PRIVATE)
        val prefsWallet = getSharedPreferences("WalletPrefs", Context.MODE_PRIVATE)
        val prefsWeek = getSharedPreferences("CategoryWeekData", Context.MODE_PRIVATE)

        val historyList = prefsGraph.getStringSet("HISTORY_LIST", emptySet())?.toMutableSet() ?: mutableSetOf()
        
        if (historyList.remove(item.rawEntry)) {
            prefsGraph.edit().putStringSet("HISTORY_LIST", historyList).apply()

            // 1. Restore Wallet Balance
            val currentBal = prefsWallet.getInt("wallet_balance", 0)
            prefsWallet.edit().putInt("wallet_balance", currentBal + item.amount).apply()

            // 2. Extract detailed info from rawEntry for category/time updates
            val parts = item.rawEntry.split("|")
            val category: String
            val amount: Float
            var hYear: Int? = null
            var hMonth: Int? = null
            var hWeek: Int? = null
            var hDay: Int? = null
            var timestamp: String? = null

            if (parts.size >= 9) {
                timestamp = parts[1]
                category = parts[3]
                amount = parts[4].toFloatOrNull() ?: 0f
                hWeek = parts[5].toIntOrNull()
                hDay = parts[6].toIntOrNull()
                hMonth = parts[7].toIntOrNull()
                hYear = parts[8].toIntOrNull()
            } else if (parts.size == 7) {
                category = parts[1]
                amount = parts[2].toFloatOrNull() ?: 0f
                hWeek = parts[3].toIntOrNull()
                hDay = parts[4].toIntOrNull()
                hMonth = parts[5].toIntOrNull()
                hYear = parts[6].toIntOrNull()
            } else if (parts.size >= 5) {
                timestamp = parts[1]
                category = parts[3]
                amount = parts[4].toFloatOrNull() ?: 0f
                timestamp.toLongOrNull()?.let { ts ->
                    val c = java.util.Calendar.getInstance()
                    c.setFirstDayOfWeek(java.util.Calendar.MONDAY)
                    c.setMinimalDaysInFirstWeek(1)
                    c.timeInMillis = ts
                    hYear = c.get(java.util.Calendar.YEAR)
                    hMonth = c.get(java.util.Calendar.MONTH)
                    hWeek = c.get(java.util.Calendar.WEEK_OF_MONTH) - 1
                    hDay = (c.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
                }
            } else return

            // Adjust SPENT_$category
            val oldSpent = prefsGraph.getFloat("SPENT_$category", 0f)
            prefsGraph.edit().putFloat("SPENT_$category", (oldSpent - amount).coerceAtLeast(0f)).apply()

            // Adjust DAY/WEEK/MONTH slots if available
            if (hYear != null && hMonth != null && hWeek != null && hDay != null) {
                val dayKey = "DAY_${hWeek}_${hDay}_${hMonth}_${hYear}"
                val weekKey = "WEEK_${hWeek}_${month}_${hYear}"
                val monthKey = "MONTH_${month}_${hYear}"

                prefsGraph.edit()
                    .putFloat(dayKey, (prefsGraph.getFloat(dayKey, 0f) - amount).coerceAtLeast(0f))
                    .putFloat(weekKey, (prefsGraph.getFloat(weekKey, 0f) - amount).coerceAtLeast(0f))
                    .putFloat(monthKey, (prefsGraph.getFloat(monthKey, 0f) - amount).coerceAtLeast(0f))
                    .apply()

                // Adjust CategoryWeekData
                val weekNum = hWeek + 1
                val catWeekKey = "${category}_W$weekNum"
                val oldCatWeek = prefsWeek.getInt(catWeekKey, 0)
                prefsWeek.edit().putInt(catWeekKey, (oldCatWeek - amount.toInt()).coerceAtLeast(0)).apply()
            }

            // 3. Wipe individual TRANS_ lookup keys if timestamp is available
            timestamp?.let { ts ->
                prefsGraph.edit()
                    .remove("TRANS_${ts}_TITLE")
                    .remove("TRANS_${ts}_CATEGORY")
                    .remove("TRANS_${ts}_AMOUNT")
                    .remove("TRANS_${ts}_WEEK")
                    .remove("TRANS_${ts}_DAY")
                    .remove("TRANS_${ts}_MONTH")
                    .remove("TRANS_${ts}_YEAR")
                    .apply()
            }
            
            ToastHelper.showToast(this, "Transaction deleted successfully")
        }
    }

    private fun showReallocationDialog(item: TransactionItem, mode: String, week: Int, day: Int, month: Int, year: Int, categoryFilter: String) {
        val bottomSheet = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (28 * density).toInt()
            setPadding(p, p, p, p)
            setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(this@DetailHistoryActivity, R.drawable.bg_transaction))
        }

        val title = TextView(this).apply {
            text = "Reallocate ₹${item.amount}"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_subhead))
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@DetailHistoryActivity, R.attr.textPrimaryColor))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (20 * density).toInt())
        }
        container.addView(title)

        val prefsCat = getSharedPreferences("CategoryPrefs", Context.MODE_PRIVATE)
        val categories = prefsCat.getStringSet("categories", emptySet()) ?: emptySet()

        val parts = item.rawEntry.split("|")
        val oldCat = if (parts.size >= 9) parts[3] else "no choice"

        if (categories.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No categories available."
                setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@DetailHistoryActivity, R.attr.textPrimaryColor))
            }
            container.addView(empty)
        } else {
            for (cat in categories) {
                if (cat.equals(oldCat, ignoreCase = true)) continue
                
                val btn = android.widget.Button(this).apply {
                    text = cat
                    setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@DetailHistoryActivity, R.attr.textPrimaryColor))
                    isAllCaps = false
                    setBackgroundResource(R.drawable.bg_glass_3d)
                    stateListAnimator = null
                    elevation = 0f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (54 * density).toInt()
                    ).apply { setMargins(0,0,0,20) }
                    
                    setOnClickListener {
                        reallocateTransaction(item.rawEntry, oldCat, cat, item.amount)
                        bottomSheet.dismiss()
                        refreshData(mode, week, day, month, year, categoryFilter)
                    }
                }
                container.addView(btn)
            }
        }

        bottomSheet.setContentView(container)
        bottomSheet.show()
    }

    private fun reallocateTransaction(rawEntry: String, oldCat: String, newCat: String, amount: Int) {
        val prefsGraph = getSharedPreferences("GraphData", Context.MODE_PRIVATE)
        val historyList = prefsGraph.getStringSet("HISTORY_LIST", emptySet())?.toMutableSet() ?: mutableSetOf()

        if (historyList.remove(rawEntry)) {
            // Precise Column-Based Replacement (avoid generic string.replace)
            val parts = rawEntry.split("|").toMutableList()
            if (parts.size >= 4) {
                parts[3] = newCat // Column 3 is Category
            }
            val newEntry = parts.joinToString("|")

            historyList.add(newEntry)
            prefsGraph.edit().putStringSet("HISTORY_LIST", historyList).apply()
            
            val oldSpent = prefsGraph.getFloat("SPENT_$oldCat", 0f)
            val newSpent = prefsGraph.getFloat("SPENT_$newCat", 0f)

            prefsGraph.edit()
                .putFloat("SPENT_$oldCat", oldSpent - amount)
                .putFloat("SPENT_$newCat", newSpent + amount)
                .apply()

            // 1. Update CategoryWeekData (Analytics Sync)
            val prefsWeek = getSharedPreferences("CategoryWeekData", Context.MODE_PRIVATE)
            if (parts.size >= 9) {
                val week = parts[5].toInt() + 1
                val oldCatWeekKey = "${oldCat}_W$week"
                val newCatWeekKey = "${newCat}_W$week"
                
                val oldWVal = prefsWeek.getInt(oldCatWeekKey, 0)
                val newWVal = prefsWeek.getInt(newCatWeekKey, 0)
                
                prefsWeek.edit()
                    .putInt(oldCatWeekKey, (oldWVal - amount).coerceAtLeast(0))
                    .putInt(newCatWeekKey, newWVal + amount)
                    .apply()
                
                // 2. Update Metadata Key
                val timestamp = parts[1]
                prefsGraph.edit()
                    .putString("TRANS_${timestamp}_CATEGORY", newCat)
                    .apply()
            }

            // 3. Trigger Cloud Sync
            FirestoreSyncManager.pushAllDataToCloud(this@DetailHistoryActivity)
                
            ToastHelper.showToast(this@DetailHistoryActivity, "Reallocated to $newCat")
        }
    }

    private fun refreshData(mode: String, week: Int, day: Int, month: Int, year: Int, categoryFilter: String) {
        val data = HistoryDataManager.getCategoryBreakdown(this, mode, if (mode == "DAILY") day else week, week, month, year, categoryFilter)
        val graph = findViewById<CategoryBreakdownGraphView>(R.id.categoryGraph)
        val recycler = findViewById<RecyclerView>(R.id.recyclerTransactions)
        
        graph.setData(data.categories, data.values)
        recycler.adapter = TransactionAdapter(data.transactions) { item ->
            showTransactionActionMenu(item, mode, week, day, month, year, categoryFilter)
        }
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
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@DetailHistoryActivity, R.attr.textPrimaryColor))
            setHintTextColor(Color.GRAY)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
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
                val newAmount = input.text.toString().toIntOrNull() ?: 0
                if (newAmount > 0) {
                    updateTransactionAmount(item, newAmount)
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

    private fun updateTransactionAmount(item: TransactionItem, newAmount: Int) {
        val delta = newAmount - item.amount
        val prefsGraph = getSharedPreferences("GraphData", Context.MODE_PRIVATE)
        val prefsWallet = getSharedPreferences("WalletPrefs", Context.MODE_PRIVATE)
        val prefsWeek = getSharedPreferences("CategoryWeekData", Context.MODE_PRIVATE)
        
        val historyList = prefsGraph.getStringSet("HISTORY_LIST", emptySet())?.toMutableSet() ?: mutableSetOf()
        
        if (historyList.remove(item.rawEntry)) {
            val parts = item.rawEntry.split("|").toMutableList()
            if (parts.size >= 9) {
                // EXP|timestamp|title|category|amount|week|day|month|year
                val timestamp = parts[1]
                val category = parts[3]
                parts[4] = newAmount.toString()
                
                val weekIdx = parts[5]
                val dayIdx = parts[6]
                val monthIdx = parts[7]
                val yearIdx = parts[8]
                
                val newEntry = parts.joinToString("|")
                historyList.add(newEntry)
                
                // 1. Update Wallet
                val currentBal = prefsWallet.getInt("wallet_balance", 0)
                prefsWallet.edit().putInt("wallet_balance", currentBal - delta).apply()
                
                // 2. Update GraphData
                val editor = prefsGraph.edit()
                editor.putStringSet("HISTORY_LIST", historyList)
                
                val spentKey = "SPENT_$category"
                editor.putFloat(spentKey, prefsGraph.getFloat(spentKey, 0f) + delta)
                
                val dayKey = "DAY_${weekIdx}_${dayIdx}_${monthIdx}_${yearIdx}"
                val weekKey = "WEEK_${weekIdx}_${monthIdx}_${yearIdx}"
                val monthKey = "MONTH_${monthIdx}_${yearIdx}"
                
                editor.putFloat(dayKey, (prefsGraph.getFloat(dayKey, 0f) + delta).coerceAtLeast(0f))
                editor.putFloat(weekKey, (prefsGraph.getFloat(weekKey, 0f) + delta).coerceAtLeast(0f))
                editor.putFloat(monthKey, (prefsGraph.getFloat(monthKey, 0f) + delta).coerceAtLeast(0f))
                
                editor.putInt("TRANS_${timestamp}_AMOUNT", newAmount)
                editor.apply()
                
                // 3. Update CategoryWeekData
                val wNum = weekIdx.toInt() + 1
                val catWeekKey = "${category}_W$wNum"
                val oldWVal = prefsWeek.getInt(catWeekKey, 0)
                prefsWeek.edit().putInt(catWeekKey, (oldWVal + delta).coerceAtLeast(0)).apply()
                
                ToastHelper.showToast(this@DetailHistoryActivity, "Amount updated")
            }
        }
    }
}
