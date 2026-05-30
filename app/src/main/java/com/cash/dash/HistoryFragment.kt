@file:Suppress("DEPRECATION")
package com.cash.dash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.text.SimpleDateFormat
import java.util.*

class HistoryFragment : Fragment() {

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
            view?.findViewById<DayBarGraphView>(R.id.dayGraph)?.let {
                loadGraphValues(it)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnDate = view.findViewById<Button>(R.id.btnDate)
        val graph = view.findViewById<DayBarGraphView>(R.id.dayGraph)
        val title = view.findViewById<TextView>(R.id.tvGraphTitle)
        val btnDaily = view.findViewById<Button>(R.id.btnDaily)
        val btnOverall = view.findViewById<Button>(R.id.btnOverall)

        fetchCategories()
        setupCategoryDropdown(btnOverall, graph)

        btnDate.text = getTodayDate()

        loadGraphValues(graph)
        setupModeDropdown(btnDaily, graph, title, btnDate)
        setupDatePicker(btnDate, graph)

        graph.onBarClickListener = { index, mode ->
            handleBarClick(index, mode, graph, title, btnDaily, btnDate)
        }

        graph.onSwipeRightListener = {
            (activity as? MainActivity)?.let { mainAct ->
                mainAct.navigateTo(1)
            }
        }

        view.findViewById<View>(R.id.searchBar).setOnClickListener {
            val intent = Intent(requireContext(), SearchActivity::class.java)
            startActivity(intent)
        }

        view.findViewById<View>(R.id.cardReport).setOnClickListener {
            val intent = Intent(requireContext(), ReportActivity::class.java)
            startActivity(intent)
        }

        view.findViewById<View>(R.id.cardStatement).setOnClickListener {
            val intent = Intent(requireContext(), StatementSelectionActivity::class.java)
            startActivity(intent)
        }
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            syncReceiver, IntentFilter(FirestoreSyncManager.ACTION_SYNC_UPDATE)
        )
    }

    override fun onStop() {
        super.onStop()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(syncReceiver)
    }

    override fun onResume() {
        super.onResume()
        forcedHighlightDay = -1
        fetchCategories()
        view?.findViewById<DayBarGraphView>(R.id.dayGraph)?.let {
            loadGraphValues(it)
        }

        if (currentCategoryFilter == "no choice" && !categoriesList.contains("no choice")) {
            currentCategoryFilter = "Overall"
            view?.findViewById<Button>(R.id.btnOverall)?.text = "Overall"
        }
    }

    private fun fetchCategories() {
        val prefsCat = requireContext().getSharedPreferences("CategoryPrefs", Context.MODE_PRIVATE)
        val saved = prefsCat.getStringSet("categories", emptySet()) ?: emptySet()

        val prefsGraph = requireContext().getSharedPreferences("GraphData", Context.MODE_PRIVATE)
        val historySet = prefsGraph.getStringSet("HISTORY_LIST", emptySet()) ?: emptySet()

        categoriesList.clear()
        categoriesList.add("Overall")
        categoriesList.addAll(saved)

        val hasNoChoice = historySet.any { it.contains("|no choice|") }
        if (hasNoChoice && !categoriesList.contains("no choice")) {
            categoriesList.add("no choice")
        }
    }

    private fun setupCategoryDropdown(btn: Button, graph: DayBarGraphView) {
        btn.setOnClickListener {
            fetchCategories()
            DropdownHelper.showBlinkingDropdown(requireContext(), btn, categoriesList, 200, null, android.view.Gravity.END) { index, cat ->
                currentCategoryFilter = cat
                btn.text = if (currentCategoryFilter == "no choice") "No Allocation" else currentCategoryFilter
                loadGraphValues(graph)
                animateGraph(graph)
            }
        }
    }

    private fun handleBarClick(index: Int, mode: String, graph: DayBarGraphView, title: TextView, btnDaily: Button, btnDate: Button) {
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
                val intent = Intent(requireContext(), DetailHistoryActivity::class.java)
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

    private fun loadGraphValues(graph: DayBarGraphView) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(requireContext())
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
                val hDay = if (forcedHighlightDay != -1) forcedHighlightDay else if (realC.get(Calendar.YEAR) == selectedYear && realC.get(Calendar.MONTH) == selectedMonth && (realC.get(Calendar.WEEK_OF_MONTH) - 1) == selectedWeek) (realC.get(Calendar.DAY_OF_WEEK) + 5) % 7 else -1
                val hWeek = if (realC.get(Calendar.YEAR) == selectedYear && realC.get(Calendar.MONTH) == selectedMonth) realC.get(Calendar.WEEK_OF_MONTH) - 1 else -1
                val hMonth = if (realC.get(Calendar.YEAR) == selectedYear) realC.get(Calendar.MONTH) else -1
                graph.setHighlightIndices(hDay, hWeek, hMonth)

                updateDailyLabels(graph)
                updateWeeklyLabels(graph)
                updateMonthlyLabels(graph)
            }
        }
    }

    private fun updateMonthlyLabels(graph: DayBarGraphView) {
        graph.setMonthlyLabels(listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"))
    }

    private fun updateDailyLabels(graph: DayBarGraphView) {
        val labels = mutableListOf<String>()
        val cal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            minimalDaysInFirstWeek = 1
            set(selectedYear, selectedMonth, 1)
            set(Calendar.WEEK_OF_MONTH, selectedWeek + 1)
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        }
        for (i in 0 until 7) {
            labels.add(if (cal.get(Calendar.MONTH) == selectedMonth) "%02d/%02d".format(cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1) else "")
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        graph.setDailyLabels(labels)
    }

    private fun updateWeeklyLabels(graph: DayBarGraphView) {
        val labels = mutableListOf<String>()
        val cal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            minimalDaysInFirstWeek = 1
            set(selectedYear, selectedMonth, 1)
        }
        val totalWeeks = cal.getActualMaximum(Calendar.WEEK_OF_MONTH)
        for (w in 1..totalWeeks) {
            cal.set(Calendar.WEEK_OF_MONTH, w)
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            if (cal.get(Calendar.MONTH) != selectedMonth) { cal.set(Calendar.MONTH, selectedMonth); cal.set(Calendar.DAY_OF_MONTH, 1) }
            val start = "%02d/%02d".format(cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1)
            cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
            if (cal.get(Calendar.MONTH) != selectedMonth) { cal.set(Calendar.MONTH, selectedMonth); cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH)) }
            val end = "%02d/%02d".format(cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1)
            labels.add("$start-$end")
        }
        graph.setWeeklyLabels(labels)
    }

    private fun setupModeDropdown(btn: Button, graph: DayBarGraphView, title: TextView, btnDate: Button) {
        btn.setOnClickListener {
            DropdownHelper.showBlinkingDropdown(requireContext(), btn, listOf("Daily", "Weekly", "Monthly"), 180) { index, _ ->
                when (index) {
                    0 -> switchMode("DAILY", graph, title, btn, btnDate)
                    1 -> switchMode("WEEKLY", graph, title, btn, btnDate)
                    2 -> switchMode("MONTHLY", graph, title, btn, btnDate)
                }
            }
        }
    }

    private fun switchMode(mode: String, graph: DayBarGraphView, title: TextView, btnDaily: Button, btnDate: Button) {
        currentMode = mode
        forcedHighlightDay = -1
        when (mode) {
            "DAILY" -> {
                title.text = "Daily Spending"
                btnDaily.text = "Daily"
                loadDailyForSelectedWeek(graph)
                updateDailyLabels(graph)
                val realC = Calendar.getInstance()
                btnDate.text = if (selectedYear == realC.get(Calendar.YEAR) && selectedMonth == realC.get(Calendar.MONTH)) getTodayDate() else SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Calendar.getInstance().apply { set(selectedYear, selectedMonth, 1) }.time)
                graph.setDayMode()
            }
            "WEEKLY" -> {
                title.text = "Weekly Spending"
                btnDaily.text = "Weekly"
                val realC = Calendar.getInstance().apply { firstDayOfWeek = Calendar.MONDAY; minimalDaysInFirstWeek = 1 }
                if (selectedYear == realC.get(Calendar.YEAR) && selectedMonth == realC.get(Calendar.MONTH)) selectedWeek = realC.get(Calendar.WEEK_OF_MONTH) - 1
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

    private fun setupDatePicker(btn: Button, graph: DayBarGraphView) {
        btn.setOnClickListener {
            if (currentMode == "MONTHLY") {
                val cy = Calendar.getInstance().get(Calendar.YEAR)
                val years = (-2..2).map { (cy + it).toString() }
                DropdownHelper.showBlinkingDropdown(requireContext(), btn, years, 200) { _, yearStr ->
                    selectedYear = yearStr.toInt()
                    btn.text = yearStr
                    loadGraphValues(graph)
                    animateGraph(graph)
                }
            } else if (currentMode == "WEEKLY") {
                // Show Month Picker (Jan - Dec)
                val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
                val displayList = months.map { "$it $selectedYear" }
                DropdownHelper.showBlinkingDropdown(requireContext(), btn, displayList, 200) { position, _ ->
                    selectedMonth = position
                    selectedWeek = 0
                    val cal = Calendar.getInstance().apply { set(selectedYear, selectedMonth, 1) }
                    btn.text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
                    loadGraphValues(graph)
                    animateGraph(graph)
                }
            } else {
                android.app.DatePickerDialog(requireContext(), ThemeHelper.getDatePickerTheme(requireContext()), { _, y, m, d ->
                    selectedYear = y; selectedMonth = m
                    selectedWeek = Calendar.getInstance().apply { firstDayOfWeek = Calendar.MONDAY; minimalDaysInFirstWeek = 1; set(y, m, d) }.get(Calendar.WEEK_OF_MONTH) - 1
                    
                    val cp = Calendar.getInstance().apply { set(y, m, d) }
                    btn.text = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(cp.time)
                    forcedHighlightDay = (cp.get(Calendar.DAY_OF_WEEK) + 5) % 7
                    
                    loadGraphValues(graph); animateGraph(graph)
                }, selectedYear, selectedMonth, 1).show()
            }
        }
    }

    private fun loadDailyForSelectedWeek(graph: DayBarGraphView) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(requireContext())
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
            }
        }
    }

    private fun animateGraph(graph: View) {
        graph.alpha = 0f; graph.scaleX = 0.9f; graph.scaleY = 0.9f
        graph.animate().alpha(1f).scaleX(1f).scaleY(1f).duration = 250
    }

    private fun getTodayDate(): String = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Calendar.getInstance().time)
}
