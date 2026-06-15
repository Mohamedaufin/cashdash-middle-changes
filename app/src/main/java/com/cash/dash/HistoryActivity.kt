@file:Suppress("DEPRECATION")
package com.cash.dash

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : ThemedActivity() {

    private var currentMode = "DAILY"
    private var currentCategoryFilter = "Overall"
    private var selectedYear = Calendar.getInstance().get(Calendar.YEAR)
    private var selectedMonth = Calendar.getInstance().get(Calendar.MONTH)
    private var selectedWeek = Calendar.getInstance().apply {
        firstDayOfWeek = Calendar.MONDAY
        minimalDaysInFirstWeek = 1
    }.get(Calendar.WEEK_OF_MONTH) - 1
    private var forcedHighlightDay = -1
    private var categoriesList = mutableListOf<String>()

    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            fetchCategories()
            findViewById<DayBarGraphView>(R.id.dayGraph)?.let {
                loadGraphValues(it)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        TutorialManager.showTutorialIfNeeded(
            this,
            "tut_history",
            "History",
            "1. View all your past transactions chronologically\n\n2. Use the search bar to find specific expenses quickly\n\n3. Tap the 'Statements' button to generate cumulative reports for any period"
        )

        val btnDate = findViewById<Button>(R.id.btnDate)
        val graph = findViewById<DayBarGraphView>(R.id.dayGraph)
        val title = findViewById<TextView>(R.id.tvGraphTitle)
        val btnDaily = findViewById<Button>(R.id.btnDaily)
        val btnOverall = findViewById<Button>(R.id.btnOverall)

        fetchCategories()
        setupCategoryDropdown(btnOverall, graph)

        btnDate.text = getTodayDate()

        loadGraphValues(graph)
        setupDropdown()
        setupNavigation()
        setupDatePicker(btnDate, graph)

        graph.onBarClickListener = { index, mode ->

            when (mode) {

                "WEEKLY_SWITCHED" -> {
                    currentMode = "DAILY"
                    selectedWeek = index

                    title.text = "Daily Spending"
                    btnDaily.text = "Daily ▼"
                    findViewById<TextView>(R.id.tvGraphHint)?.text = "Click on any graph to view daily breakdown"

                    loadDailyForSelectedWeek(graph)
                    updateDailyLabels(graph)

                    val realC = Calendar.getInstance().apply {
                        firstDayOfWeek = Calendar.MONDAY
                        minimalDaysInFirstWeek = 1
                    }
                    val currentWeekIndex = realC.get(Calendar.WEEK_OF_MONTH) - 1

                    if (selectedYear == realC.get(Calendar.YEAR) && selectedMonth == realC.get(Calendar.MONTH) && selectedWeek == currentWeekIndex) {
                        btnDate.text = getTodayDate()
                    } else {
                        val cal = Calendar.getInstance().apply {
                            firstDayOfWeek = Calendar.MONDAY
                            minimalDaysInFirstWeek = 1
                            set(selectedYear, selectedMonth, 1)
                            set(Calendar.WEEK_OF_MONTH, selectedWeek + 1)
                            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                            if (get(Calendar.MONTH) != selectedMonth) {
                                set(Calendar.MONTH, selectedMonth)
                                set(Calendar.DAY_OF_MONTH, 1)
                            }
                        }
                        btnDate.text = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(cal.time)
                    }
                    graph.setDayMode()
                    animateGraph(graph)
                }

                "MONTHLY_SWITCHED" -> {
                    currentMode = "WEEKLY"

                    selectedMonth = index

                    val realC = Calendar.getInstance().apply {
                        firstDayOfWeek = Calendar.MONDAY
                        minimalDaysInFirstWeek = 1
                    }
                    selectedWeek = if (selectedYear == realC.get(Calendar.YEAR) && selectedMonth == realC.get(Calendar.MONTH)) {
                        realC.get(Calendar.WEEK_OF_MONTH) - 1
                    } else {
                        0
                    }

                    loadGraphValues(graph)

                    title.text = "Weekly Spending"
                    btnDaily.text = "Weekly ▼"
                    findViewById<TextView>(R.id.tvGraphHint)?.text = "Click on any week graph to view daily graph"
                    val cal = Calendar.getInstance().apply { set(selectedYear, selectedMonth, 1) }
                    btnDate.text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)

                    graph.setWeekMode()
                    animateGraph(graph)
                }

                "DAILY" -> {
                    val intent = Intent(this, DetailHistoryActivity::class.java)
                    intent.putExtra("MODE", "DAILY")
                    intent.putExtra("WEEK", selectedWeek)
                    intent.putExtra("DAY", index)
                    intent.putExtra("MONTH", selectedMonth)
                    intent.putExtra("YEAR", selectedYear)
                    intent.putExtra("FILTER_CATEGORY", currentCategoryFilter)
                    startActivity(intent)
                }
            }
        }

        graph.onSwipeRightListener = {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }

        // Handle System Insets
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainContent)) { v, insets ->
            val statusInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            val navInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            
            // Apply status bar padding to top and navigation bar padding to bottom
            v.setPadding(v.paddingLeft, statusInsets.top + 10, v.paddingRight, navInsets.bottom)
            insets
        }

        findViewById<View>(R.id.searchBar).setOnClickListener {
            val intent = Intent(this, SearchActivity::class.java)
            startActivity(intent)
        }

        val cardReport = findViewById<View>(R.id.cardReport)
        cardReport.setOnClickListener {
            animateAndStart(cardReport) {
                startActivity(Intent(this, ReportActivity::class.java))
            }
        }

        val cardStatement = findViewById<View>(R.id.cardStatement)
        cardStatement.setOnClickListener {
            animateAndStart(cardStatement) {
                startActivity(Intent(this, StatementSelectionActivity::class.java))
            }
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

    override fun onResume() {
        super.onResume()
        forcedHighlightDay = -1 // Reset highlight to "true today" on return
        fetchCategories() // Refresh in case "no choice" was reallocated
        findViewById<DayBarGraphView>(R.id.dayGraph)?.let {
            loadGraphValues(it)
        }

        // Safety: If "no choice" was selected but is now gone, reset to Overall
        if (currentCategoryFilter == "no choice" && !categoriesList.contains("no choice")) {
            currentCategoryFilter = "Overall"
            findViewById<Button>(R.id.btnOverall)?.text = "Overall ▼"
        }
    }


    private fun fetchCategories() {
        val prefsCat = getSharedPreferences("CategoryPrefs", MODE_PRIVATE)
        val saved = prefsCat.getStringSet("categories", emptySet()) ?: emptySet()

        val prefsGraph = getSharedPreferences("GraphData", MODE_PRIVATE)
        val historySet = prefsGraph.getStringSet("HISTORY_LIST", emptySet()) ?: emptySet()

        categoriesList.clear()
        categoriesList.add("Overall")
        categoriesList.addAll(saved)

        // Dynamic "no choice" addition
        val hasNoChoice = historySet.any { it.contains("|no choice|") }
        if (hasNoChoice && !categoriesList.contains("no choice")) {
            categoriesList.add("no choice")
        }
    }

    private fun setupCategoryDropdown(btn: Button, graph: DayBarGraphView) {
        btn.setOnClickListener {
            fetchCategories() // Refresh before showing menu
            DropdownHelper.showBlinkingDropdown(this, btn, categoriesList, 200, null, android.view.Gravity.END) { index, cat ->
                currentCategoryFilter = cat
                btn.text = if (currentCategoryFilter == "no choice") "Overall ▼" else "$currentCategoryFilter ▼"
                loadGraphValues(graph)
                animateGraph(graph)
            }
        }
    }

    private fun loadGraphValues(graph: DayBarGraphView) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@HistoryActivity)
            val dao = db.transactionDao()
            val allTransactions = dao.getTransactionsInRange(0L, Long.MAX_VALUE)

            val dailyMap = mutableMapOf<Int, Float>()
            val weeklyMap = mutableMapOf<Int, Float>()
            val monthlyMap = mutableMapOf<Int, Float>()

            for (item in allTransactions) {
                if (currentCategoryFilter != "Overall" && item.category != currentCategoryFilter) continue

                if (item.year == selectedYear) {
                    monthlyMap[item.month] = (monthlyMap[item.month] ?: 0f) + item.amount
                    if (item.month == selectedMonth) {
                        weeklyMap[item.week] = (weeklyMap[item.week] ?: 0f) + item.amount
                        if (item.week == selectedWeek) {
                            dailyMap[item.day] = (dailyMap[item.day] ?: 0f) + item.amount
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                graph.setDailyData(List(7) { dailyMap[it] ?: 0f })

                val calMonth = Calendar.getInstance().apply {
                    firstDayOfWeek = Calendar.MONDAY
                    minimalDaysInFirstWeek = 1
                    set(selectedYear, selectedMonth, 1)
                }
                val totalWeeks = calMonth.getActualMaximum(Calendar.WEEK_OF_MONTH)
                graph.setWeeklyData(List(totalWeeks) { weeklyMap[it] ?: 0f })

                graph.setMonthlyData(List(12) { monthlyMap[it] ?: 0f })

                val realC = Calendar.getInstance().apply {
                    firstDayOfWeek = Calendar.MONDAY
                    minimalDaysInFirstWeek = 1
                }
                val realYear = realC.get(Calendar.YEAR)
                val realMonth = realC.get(Calendar.MONTH)
                val realDay = (realC.get(Calendar.DAY_OF_WEEK) + 5) % 7

                val hDay = if (forcedHighlightDay != -1) {
                    forcedHighlightDay
                } else if (realYear == selectedYear && realMonth == selectedMonth) {
                    val realWeekOfMonth = realC.apply {
                        firstDayOfWeek = Calendar.MONDAY
                        minimalDaysInFirstWeek = 1
                    }.get(Calendar.WEEK_OF_MONTH) - 1

                    if (realWeekOfMonth == selectedWeek) realDay else -1
                } else {
                    -1
                }

                val hWeek = if (realYear == selectedYear && realMonth == selectedMonth) {
                    realC.apply {
                        firstDayOfWeek = Calendar.MONDAY
                        minimalDaysInFirstWeek = 1
                    }.get(Calendar.WEEK_OF_MONTH) - 1
                } else -1
                val hMonth = if (realYear == selectedYear) realMonth else -1

                graph.setHighlightIndices(hDay, hWeek, hMonth)

                updateDailyLabels(graph)
                updateWeeklyLabels(graph)
                updateMonthlyLabels(graph)

                val hintTv = findViewById<TextView>(R.id.tvGraphHint)
                hintTv?.visibility = if (graph.hasDataForCurrentMode()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun updateMonthlyLabels(graph: DayBarGraphView) {
        val shortMonths = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        graph.setMonthlyLabels(shortMonths)
    }

    private fun updateDailyLabels(graph: DayBarGraphView) {
        val labels = mutableListOf<String>()
        val cal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            minimalDaysInFirstWeek = 1
            set(Calendar.YEAR, selectedYear)
            set(Calendar.MONTH, selectedMonth)
            set(Calendar.WEEK_OF_MONTH, selectedWeek + 1)
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        }

        for (i in 0 until 7) {
            if (cal.get(Calendar.MONTH) == selectedMonth) {
                labels.add("%02d/%02d".format(cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1))
            } else {
                labels.add("") // Day belongs to another month
            }
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        graph.setDailyLabels(labels)
    }

    private fun updateWeeklyLabels(graph: DayBarGraphView) {
        val labels = mutableListOf<String>()
        val cal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            minimalDaysInFirstWeek = 1
            set(Calendar.YEAR, selectedYear)
            set(Calendar.MONTH, selectedMonth)
            set(Calendar.DAY_OF_MONTH, 1)
        }

        val totalWeeks = cal.getActualMaximum(Calendar.WEEK_OF_MONTH)
        for (w in 1..totalWeeks) {
            cal.set(Calendar.WEEK_OF_MONTH, w)

            // Start of week (Monday or 1st of month)
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            if (cal.get(Calendar.MONTH) != selectedMonth) {
                cal.set(Calendar.MONTH, selectedMonth)
                cal.set(Calendar.DAY_OF_MONTH, 1)
            }
            val start = "%02d/%02d".format(cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1)

            // End of week (Sunday or last of month)
            cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
            if (cal.get(Calendar.MONTH) != selectedMonth) {
                cal.set(Calendar.MONTH, selectedMonth)
                cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
            }
            val end = "%02d/%02d".format(cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1)

            labels.add("$start-$end")
        }
        graph.setWeeklyLabels(labels)
    }

    private fun setupDatePicker(btn: Button, graph: DayBarGraphView) {
        btn.setOnClickListener {
            if (currentMode == "MONTHLY") {
                // Show Year Picker
                val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                val yearsRange = (-2..2).map { (currentYear + it).toString() }
                DropdownHelper.showBlinkingDropdown(this, btn, yearsRange, 200) { _, yearStr ->
                    selectedYear = yearStr.toInt()
                    btn.text = selectedYear.toString()
                    loadGraphValues(graph)
                    animateGraph(graph)
                }
            } else if (currentMode == "WEEKLY") {
                // Show Month Picker (Jan - Dec)
                val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
                val displayList = months.map { "$it $selectedYear" }
                DropdownHelper.showBlinkingDropdown(this, btn, displayList, 200) { position, _ ->
                    selectedMonth = position
                    selectedWeek = 0 // Default to first week
                    val cal = Calendar.getInstance().apply { set(selectedYear, selectedMonth, 1) }
                    btn.text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
                    loadGraphValues(graph)
                    animateGraph(graph)
                }
            } else {
                // Standard Date Picker (for DAILY mode)
                val picker = android.app.DatePickerDialog(this, ThemeHelper.getDatePickerTheme(this), { _, year, month, day ->
                    selectedYear = year
                    selectedMonth = month
                    selectedWeek = Calendar.getInstance().apply {
                        firstDayOfWeek = Calendar.MONDAY
                        minimalDaysInFirstWeek = 1
                        set(year, month, day)
                    }.get(Calendar.WEEK_OF_MONTH) - 1

                    val calPicked = Calendar.getInstance().apply { set(year, month, day) }
                    val realC = Calendar.getInstance()
                    val isToday = calPicked.get(Calendar.YEAR) == realC.get(Calendar.YEAR) &&
                            calPicked.get(Calendar.MONTH) == realC.get(Calendar.MONTH) &&
                            calPicked.get(Calendar.DAY_OF_MONTH) == realC.get(Calendar.DAY_OF_MONTH)

                    val sdf = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
                    btn.text = sdf.format(calPicked.time)

                    forcedHighlightDay = (calPicked.get(Calendar.DAY_OF_WEEK) + 5) % 7

                    loadGraphValues(graph)
                    animateGraph(graph)
                }, selectedYear, selectedMonth, 1)
                picker.show()
            }
        }
    }

    private fun loadDailyForSelectedWeek(graph: DayBarGraphView) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@HistoryActivity)
            val dao = db.transactionDao()
            val transactions = dao.getTransactionsInRange(0L, Long.MAX_VALUE)
            val dailyMap = mutableMapOf<Int, Float>()

            for (item in transactions) {
                if (currentCategoryFilter != "Overall" && item.category != currentCategoryFilter) continue
                if (item.year == selectedYear && item.month == selectedMonth && item.week == selectedWeek) {
                    dailyMap[item.day] = (dailyMap[item.day] ?: 0f) + item.amount
                }
            }
            withContext(Dispatchers.Main) {
                graph.setDailyData(List(7) { dailyMap[it] ?: 0f })
                val hintTv = findViewById<TextView>(R.id.tvGraphHint)
                hintTv?.visibility = if (graph.hasDataForCurrentMode()) View.VISIBLE else View.GONE
            }
        }
    }


    private fun setupNavigation() {
        findViewById<View>(R.id.tabHome)?.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }

        findViewById<View>(R.id.tabAllocator)?.setOnClickListener {
            val intent = Intent(this, AllocatorActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }
    }


    private fun setupDropdown() {
        val btnDaily = findViewById<Button>(R.id.btnDaily)

        btnDaily.setOnClickListener {
            DropdownHelper.showBlinkingDropdown(this, btnDaily, listOf("Daily", "Weekly", "Monthly"), 180) { index, _ ->
                when (index) {
                    0 -> switchMode("DAILY")
                    1 -> switchMode("WEEKLY")
                    2 -> switchMode("MONTHLY")
                }
            }
        }
    }


    private fun switchMode(mode: String) {
        val graph = findViewById<DayBarGraphView>(R.id.dayGraph)
        val title = findViewById<TextView>(R.id.tvGraphTitle)
        val btnDaily = findViewById<Button>(R.id.btnDaily)
        val btnDate = findViewById<Button>(R.id.btnDate)

        currentMode = mode
        forcedHighlightDay = -1 // Reset highlight to true today on mode switch

        when (mode) {
            "DAILY" -> {
                title.text = "Daily Spending"
                btnDaily.text = "Daily ▼"
                findViewById<TextView>(R.id.tvGraphHint)?.text = "Click on any graph to view daily breakdown"
                loadDailyForSelectedWeek(graph)
                updateDailyLabels(graph)

                val realC = Calendar.getInstance()
                if (selectedYear == realC.get(Calendar.YEAR) && selectedMonth == realC.get(Calendar.MONTH)) {
                    btnDate.text = getTodayDate()
                } else {
                    val cal = Calendar.getInstance().apply { set(selectedYear, selectedMonth, 1) }
                    btnDate.text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
                }
                graph.setDayMode()
            }

            "WEEKLY" -> {
                title.text = "Weekly Spending"
                btnDaily.text = "Weekly ▼"
                findViewById<TextView>(R.id.tvGraphHint)?.text = "Click on any week graph to view daily graph"

                val realC = Calendar.getInstance().apply {
                    firstDayOfWeek = Calendar.MONDAY
                    minimalDaysInFirstWeek = 1
                }
                if (selectedYear == realC.get(Calendar.YEAR) && selectedMonth == realC.get(Calendar.MONTH)) {
                    selectedWeek = realC.get(Calendar.WEEK_OF_MONTH) - 1
                }

                val cal = Calendar.getInstance().apply { set(selectedYear, selectedMonth, 1) }
                btnDate.text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
                graph.setWeekMode()
            }

            "MONTHLY" -> {
                title.text = "Monthly Spending"
                btnDaily.text = "Monthly ▼"
                findViewById<TextView>(R.id.tvGraphHint)?.text = "Click on any month graph to view weekly graph"
                updateMonthlyLabels(graph)
                graph.setMonthMode()
                btnDate.text = selectedYear.toString()
            }
        }

        animateGraph(graph)
    }


    private fun animateAndStart(view: View, action: () -> Unit) {
        view.animate()
            .scaleX(0.85f).scaleY(0.85f)
            .setDuration(7)
            .withEndAction {
                view.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(8)
                    .withEndAction { action() }
                    .start()
            }
            .start()
    }

    private fun animateGraph(graph: View) {
        graph.alpha = 0f
        graph.scaleX = 0.9f
        graph.scaleY = 0.9f
        graph.animate().alpha(1f).scaleX(1f).scaleY(1f).duration = 250
    }


    private fun getTodayDate(): String {
        val c = Calendar.getInstance()
        val sdf = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
        return sdf.format(c.time)
    }
}
