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
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : AppCompatActivity() {

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

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

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
                    btnDaily.text = "Daily"

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
                    btnDaily.text = "Weekly"
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
        forcedHighlightDay = -1 // Reset highlight to \"true today\" on return
        fetchCategories() // Refresh in case \"no choice\" was reallocated
        findViewById<DayBarGraphView>(R.id.dayGraph)?.let {
            loadGraphValues(it)
        }

        // Safety: If \"no choice\" was selected but is now gone, reset to Overall
        if (currentCategoryFilter == "no choice" && !categoriesList.contains("no choice")) {
            currentCategoryFilter = "Overall"
            findViewById<Button>(R.id.btnOverall)?.text = "Overall"
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

        // Dynamic \"no choice\" addition
        val hasNoChoice = historySet.any { it.contains("|no choice|") }
        if (hasNoChoice && !categoriesList.contains("no choice")) {
            categoriesList.add("no choice")
        }
    }

    private fun setupCategoryDropdown(btn: Button, graph: DayBarGraphView) {
        btn.setOnClickListener {
            fetchCategories() // Refresh before showing menu
            val wrapper = androidx.appcompat.view.ContextThemeWrapper(this, R.style.PopupMenuTheme)
            val popup = PopupMenu(wrapper, btn)
            for ((index, cat) in categoriesList.withIndex()) {
                popup.menu.add(0, index, 0, cat)
            }
            popup.setOnMenuItemClickListener { item ->
                currentCategoryFilter = categoriesList[item.itemId]
                btn.text = if (currentCategoryFilter == "no choice") "No Choice" else currentCategoryFilter
                loadGraphValues(graph)
                animateGraph(graph)
                true
            }
            popup.show()
        }
    }

    private fun loadGraphValues(graph: DayBarGraphView) {
        val prefs = getSharedPreferences("GraphData", MODE_PRIVATE)
        val historySet = prefs.getStringSet("HISTORY_LIST", emptySet()) ?: emptySet()

        val dailyMap = mutableMapOf<Int, Float>() // dayIndex -> total
        val weeklyMap = mutableMapOf<Int, Float>() // weekIndex -> total
        val monthlyMap = mutableMapOf<Int, Float>() // monthIndex -> total

        val cal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            minimalDaysInFirstWeek = 1
        }

        for (entry in historySet) {
            val p = entry.split("|")
            if (p.size < 5) continue

            val timestampLong = p[1].toLongOrNull()

            val category: String
            val amount: Float

            if (p.size >= 9) {
                category = p[3]
                amount = p[4].toFloatOrNull() ?: 0f
            } else if (p.size == 7) {
                category = p[1]
                amount = p[2].toFloatOrNull() ?: 0f
            } else {
                category = p[3]
                amount = p[4].toFloatOrNull() ?: 0f
            }

            if (currentCategoryFilter != "Overall" && category != currentCategoryFilter) continue

            val hYear: Int
            val hMonth: Int
            val hWeek: Int
            val hDay: Int

            if (p.size >= 9) {
                hWeek = p[5].toIntOrNull() ?: 0
                hDay = p[6].toIntOrNull() ?: 0
                hMonth = p[7].toIntOrNull() ?: 0
                hYear = p[8].toIntOrNull() ?: 0
            } else if (p.size == 7) {
                hWeek = p[3].toIntOrNull() ?: 0
                hDay = p[4].toIntOrNull() ?: 0
                hMonth = p[5].toIntOrNull() ?: 0
                hYear = p[6].toIntOrNull() ?: 0
            } else if (timestampLong != null && timestampLong > 1000000000000L) {
                cal.timeInMillis = timestampLong
                hYear = cal.get(Calendar.YEAR)
                hMonth = cal.get(Calendar.MONTH)
                hWeek = cal.get(Calendar.WEEK_OF_MONTH) - 1
                hDay = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
            } else continue

            if (hYear == selectedYear) {
                monthlyMap[hMonth] = (monthlyMap[hMonth] ?: 0f) + amount
                if (hMonth == selectedMonth) {
                    weeklyMap[hWeek] = (weeklyMap[hWeek] ?: 0f) + amount
                    if (hWeek == selectedWeek) {
                        dailyMap[hDay] = (dailyMap[hDay] ?: 0f) + amount
                    }
                }
            }
        }

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
                val wrapper = androidx.appcompat.view.ContextThemeWrapper(this, R.style.PopupMenuTheme)
                val popup = PopupMenu(wrapper, btn)
                val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                for (i in -2..2) {
                    val year = currentYear + i
                    popup.menu.add(0, year, 0, year.toString())
                }
                popup.setOnMenuItemClickListener { item ->
                    selectedYear = item.itemId
                    btn.text = selectedYear.toString()
                    loadGraphValues(graph)
                    animateGraph(graph)
                    true
                }
                popup.show()
            } else if (currentMode == "WEEKLY") {
                // Show Month Picker (Jan - Dec)
                val wrapper = androidx.appcompat.view.ContextThemeWrapper(this, R.style.PopupMenuTheme)
                val popup = PopupMenu(wrapper, btn)
                val months = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
                for (i in 0..11) {
                    popup.menu.add(0, i, 0, "${months[i]} $selectedYear")
                }
                popup.setOnMenuItemClickListener { item ->
                    selectedMonth = item.itemId
                    selectedWeek = 0 // Default to first week
                    val cal = Calendar.getInstance().apply { set(selectedYear, selectedMonth, 1) }
                    btn.text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
                    loadGraphValues(graph)
                    animateGraph(graph)
                    true
                }
                popup.show()
            } else {
                // Standard Date Picker (for DAILY mode)
                val picker = android.app.DatePickerDialog(this, { _, year, month, day ->
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
        val prefs = getSharedPreferences("GraphData", MODE_PRIVATE)
        val historySet = prefs.getStringSet("HISTORY_LIST", emptySet()) ?: emptySet()
        val dailyMap = mutableMapOf<Int, Float>()
        val cal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            minimalDaysInFirstWeek = 1
        }
        for (entry in historySet) {
            val p = entry.split("|")
            if (p.size < 5) continue

            val timestampLong = p[1].toLongOrNull()

            val category: String
            val amount: Float

            if (p.size >= 9) {
                category = p[3]
                amount = p[4].toFloatOrNull() ?: 0f
            } else if (p.size == 7) {
                category = p[1]
                amount = p[2].toFloatOrNull() ?: 0f
            } else {
                category = p[3]
                amount = p[4].toFloatOrNull() ?: 0f
            }

            if (currentCategoryFilter != "Overall" && category != currentCategoryFilter) continue

            val hYear: Int
            val hMonth: Int
            val hWeek: Int
            val hDay: Int

            if (p.size >= 9) {
                hWeek = p[5].toIntOrNull() ?: 0
                hDay = p[6].toIntOrNull() ?: 0
                hMonth = p[7].toIntOrNull() ?: 0
                hYear = p[8].toIntOrNull() ?: 0
            } else if (p.size == 7) {
                hWeek = p[3].toIntOrNull() ?: 0
                hDay = p[4].toIntOrNull() ?: 0
                hMonth = p[5].toIntOrNull() ?: 0
                hYear = p[6].toIntOrNull() ?: 0
            } else if (timestampLong != null && timestampLong > 1000000000000L) {
                cal.timeInMillis = timestampLong
                hYear = cal.get(Calendar.YEAR)
                hMonth = cal.get(Calendar.MONTH)
                hWeek = cal.get(Calendar.WEEK_OF_MONTH) - 1
                hDay = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
            } else continue

            if (hYear == selectedYear && hMonth == selectedMonth && hWeek == selectedWeek) {
                dailyMap[hDay] = (dailyMap[hDay] ?: 0f) + amount
            }
        }
        graph.setDailyData(List(7) { dailyMap[it] ?: 0f })
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
            val wrapper = androidx.appcompat.view.ContextThemeWrapper(this, R.style.PopupMenuTheme)
            val popup = PopupMenu(wrapper, btnDaily)

            popup.menu.add(0, 0, 0, "Daily")
            popup.menu.add(0, 1, 1, "Weekly")
            popup.menu.add(0, 2, 2, "Monthly")

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    0 -> switchMode("DAILY")
                    1 -> switchMode("WEEKLY")
                    2 -> switchMode("MONTHLY")
                }
                true
            }
            popup.show()
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
                btnDaily.text = "Daily"
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
                btnDaily.text = "Weekly"

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
                btnDaily.text = "Monthly"
                graph.setMonthMode()
                updateMonthlyLabels(graph)
                btnDate.text = selectedYear.toString()
            }
        }

        animateGraph(graph)
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
