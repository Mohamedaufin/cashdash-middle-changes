@file:Suppress("DEPRECATION")
package com.cash.dash

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.content.Intent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import java.util.Calendar
import java.util.concurrent.TimeUnit
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.content.BroadcastReceiver
import android.content.IntentFilter

class CategoryAnalysisActivity : ThemedActivity() {

    private lateinit var tvCategoryName: TextView
    private lateinit var tvAverage: TextView
    private lateinit var weeklyGraph: WeeklyBarGraphView
    private var categoryName: String = "Unknown"

    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUI()
        }
    }

    private val LIMIT_PREF = "CategoryPrefs"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_category_analysis)

        tvCategoryName = findViewById(R.id.tvCategoryName)
        tvAverage = findViewById(R.id.tvAverage)
        weeklyGraph = findViewById(R.id.weeklyGraph)

        categoryName = intent.getStringExtra("CATEGORY_NAME") ?: "Unknown"
        tvCategoryName.text = categoryName

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnSetLimit).setOnClickListener {
            val i = Intent(this, SetLimitActivity::class.java)
            i.putExtra("CATEGORY_NAME", categoryName)
            startActivity(i)
        }

        val btnEditIcon = findViewById<ImageView>(R.id.btnEditIcon)
        btnEditIcon.setImageResource(CategoryIconHelper.getIconForCategory(this, categoryName))
        btnEditIcon.setOnClickListener {
            showIconPickerDialog()
        }

        // Info Icon Border for White Theme
        val infoIcon = findViewById<ImageView>(R.id.ivCategoryInfoIcon)
        if (ThemeHelper.isWhiteTheme(this)) {
            infoIcon.setBackgroundResource(R.drawable.bg_info_circle_white_bordered_black)
        }

        // Apply WindowInsets for edge-to-edge support
        val scrollView = findViewById<View>(android.R.id.content).rootView.findViewById<android.view.View>(R.id.tvTitle).parent.parent as android.widget.ScrollView
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, systemBars.bottom + 20)
            insets
        }

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
        lifecycleScope.launch(Dispatchers.IO) {
            val weeklyValues = loadWeeklyData(categoryName)
            val labels = calculateRollingLabels(categoryName)
            
            val prefs = getSharedPreferences(LIMIT_PREF, Context.MODE_PRIVATE)
            val limit = prefs.getInt("LIMIT_$categoryName", -1)

            val creationTime = getAccountCreationTime()
            val firstMonday = getFirstMonday(creationTime)
            val now = System.currentTimeMillis()
            var currentWeekIndexGlobal = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(now - firstMonday).toInt() / 7
            if (currentWeekIndexGlobal < 0) currentWeekIndexGlobal = 0
            val startWeekIndex = if (currentWeekIndexGlobal < 4) 0 else currentWeekIndexGlobal - 3
            val currentWeekRelative = currentWeekIndexGlobal - startWeekIndex

            withContext(Dispatchers.Main) {
                weeklyGraph.setValues(weeklyValues.map { it.toFloat() })
                weeklyGraph.setLabels(labels)
                weeklyGraph.setCurrentWeekIndex(currentWeekRelative)

                val tvLimitValue = findViewById<TextView>(R.id.tvLimitValue)
                if (limit > 0) {
                    weeklyGraph.setLimit(limit.toFloat())
                    tvLimitValue.text = "Limit : ₹$limit"
                    tvLimitValue.visibility = View.VISIBLE
                } else {
                    tvLimitValue.visibility = View.GONE
                }

                val avg = if (weeklyValues.isNotEmpty()) weeklyValues.sum() / weeklyValues.size else 0
                tvAverage.text = "₹${avg}"
            }
        }
    }

    private fun getAccountCreationTime(): Long {
        val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        return appPrefs.getLong("account_creation_time", System.currentTimeMillis())
    }

    private fun getFirstMonday(creationTime: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = creationTime
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        
        // Root back to Monday (Calendar.MONDAY is 2)
        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return cal.timeInMillis
    }

    private fun loadWeeklyData(categoryName: String): MutableList<Int> {
        val db = AppDatabase.getDatabase(this)
        val dao = db.transactionDao()
        val creationTime = getAccountCreationTime()
        val firstMonday = getFirstMonday(creationTime)
        val now = System.currentTimeMillis()
        
        // Current week index (0-indexed) relative to first Monday
        var currentWeekIndex = TimeUnit.MILLISECONDS.toDays(now - firstMonday).toInt() / 7
        if (currentWeekIndex < 0) currentWeekIndex = 0

        // If current week > 3 (meaning we are in Week 5 or later), shift start
        val startWeekIndex = if (currentWeekIndex < 4) 0 else currentWeekIndex - 3
        
        val weekBarSums = FloatArray(4) { 0f }

        // Fetch relevant transactions from Room
        val transactions = if (categoryName == "Overall") {
            dao.getTransactionsInRange(firstMonday, Long.MAX_VALUE)
        } else {
            dao.getTransactionsByCategoryInRange(categoryName, firstMonday, Long.MAX_VALUE)
        }

        for (item in transactions) {
            val daysSinceFirstMonday = TimeUnit.MILLISECONDS.toDays(item.timestamp - firstMonday).toInt()
            if (daysSinceFirstMonday < 0) continue 
            val entryWeekIndex = daysSinceFirstMonday / 7

            // Map the entry into our 4-bar display
            if (entryWeekIndex in startWeekIndex..(startWeekIndex + 3)) {
                val arrayIndex = entryWeekIndex - startWeekIndex
                weekBarSums[arrayIndex] += item.amount
            }
        }
        return weekBarSums.map { it.toInt() }.toMutableList()
    }

    private fun calculateRollingLabels(categoryName: String): List<String> {
        val creationTime = getAccountCreationTime()
        val firstMonday = getFirstMonday(creationTime)
        val now = System.currentTimeMillis()
        
        var currentWeekIndex = TimeUnit.MILLISECONDS.toDays(now - firstMonday).toInt() / 7
        if (currentWeekIndex < 0) currentWeekIndex = 0
        
        val startWeekIndex = if (currentWeekIndex < 4) 0 else currentWeekIndex - 3
        
        val labels = mutableListOf<String>()
        val cal = Calendar.getInstance()
        val sdf = java.text.SimpleDateFormat("dd/MM", java.util.Locale.getDefault())

        for (i in 0..3) {
            val absoluteWeekIndex = startWeekIndex + i
            
            // Calculate Start Date of the week
            cal.timeInMillis = firstMonday
            cal.add(Calendar.DAY_OF_YEAR, absoluteWeekIndex * 7)
            val startDate = sdf.format(cal.time)
            
            // Calculate End Date of the week
            cal.add(Calendar.DAY_OF_YEAR, 6)
            val endDate = sdf.format(cal.time)
            
            labels.add("$startDate-$endDate")
        }
        return labels
    }

    private fun showIconPickerDialog() {
        val density = resources.displayMetrics.density
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (28 * density).toInt()
            setPadding(p, p, p, (24 * density).toInt())
            setBackgroundResource(com.cash.dash.ThemeHelper.getDrawable(context, R.drawable.bg_transaction))
        }

        val titleView = TextView(this).apply {
            text = "Select Category Icon"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_title))
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@CategoryAnalysisActivity, R.attr.textPrimaryColor))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, (20 * density).toInt())
        }
        box.addView(titleView)

        // Define icons
        val icons = listOf(
            Pair("Food", R.drawable.ic_category_food),
            Pair("Shopping", R.drawable.ic_category_shopping),
            Pair("Fuel", R.drawable.ic_category_fuel),
            Pair("Transport", R.drawable.ic_category_transport),
            Pair("Others", R.drawable.ic_edit)
        )

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(box)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        icons.forEach { (name, resId) ->
            val btn = Button(this).apply {
                text = name
                isAllCaps = false
                setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@CategoryAnalysisActivity, R.attr.textPrimaryColor))
                
                // Only aggressively bound the raw PNG (ic_edit), keep others natural
                if (resId == R.drawable.ic_edit) {
                    val drw = androidx.core.content.ContextCompat.getDrawable(this@CategoryAnalysisActivity, resId)
                    drw?.let {
                        val iconSize = (24 * density).toInt()
                        val h = if (it.intrinsicWidth > 0) (iconSize * it.intrinsicHeight) / it.intrinsicWidth else iconSize
                        it.setBounds(0, 0, iconSize, h)
                    }
                    val tinted = com.cash.dash.ThemeHelper.tintDrawableIfWhiteTheme(this@CategoryAnalysisActivity, drw)
                    setCompoundDrawables(tinted, null, null, null)
                } else {
                    val drw = androidx.core.content.ContextCompat.getDrawable(this@CategoryAnalysisActivity, resId)
                    val tinted = com.cash.dash.ThemeHelper.tintDrawableIfWhiteTheme(this@CategoryAnalysisActivity, drw)
                    setCompoundDrawablesWithIntrinsicBounds(tinted, null, null, null)
                }
                
                compoundDrawablePadding = (12 * density).toInt()
                setPadding((16 * density).toInt(), 0, 0, 0)
                gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                background = androidx.core.content.ContextCompat.getDrawable(context, android.util.TypedValue().apply { context.theme.resolveAttribute(R.attr.cardBackground, this, true) }.resourceId)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (54 * density).toInt()
                ).apply { setMargins(0, 0, 0, (12 * density).toInt()) }

                setOnClickListener {
                    saveCustomIcon(resId)
                    dialog.dismiss()
                }
            }
            box.addView(btn)
        }

        val btnCancel = Button(this).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(com.cash.dash.ThemeHelper.resolveColorAttr(this@CategoryAnalysisActivity, R.attr.textPrimaryColor))
            background = androidx.core.content.ContextCompat.getDrawable(context, android.util.TypedValue().apply { context.theme.resolveAttribute(R.attr.cardBackground, this, true) }.resourceId)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (50 * density).toInt()
            )
            setOnClickListener { dialog.dismiss() }
        }
        box.addView(btnCancel)

        dialog.show()
    }

    private fun saveCustomIcon(resId: Int) {
        val prefs = getSharedPreferences(LIMIT_PREF, Context.MODE_PRIVATE)
        prefs.edit().putInt("ICON_$categoryName", resId).apply()
        FirestoreSyncManager.pushAllDataToCloud(this)
        findViewById<ImageView>(R.id.btnEditIcon).setImageResource(CategoryIconHelper.getIconForCategory(this, categoryName))
        val initialIntent = Intent(FirestoreSyncManager.ACTION_SYNC_UPDATE)
        LocalBroadcastManager.getInstance(this).sendBroadcast(initialIntent)
    }
}
