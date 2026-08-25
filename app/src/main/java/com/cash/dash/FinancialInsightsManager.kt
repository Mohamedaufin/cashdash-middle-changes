package com.cash.dash

import android.content.Context
import java.util.*
import kotlin.math.abs

object FinancialInsightsManager {

    data class AdvisoryInsights(
        val periodLabel: String,
        val totalSpent: Float,
        val previousTotal: Float,
        val changePercent: Float,
        val dailyAverage: Float,
        val topCategories: List<HistoryReportGenerator.CategorySummary>,
        val budgetStatus: BudgetStatus,
        val dailyPatterns: List<DayStat>,
        val savingsOpportunity: String,
        val isMonthlyMode: Boolean,
        val topWeeks: List<WeeklyTrend>, // Used in monthly mode
        val isCustomMode: Boolean = false
    )

    data class WeeklyTrend(val weekLabel: String, val amount: Float, val dates: String)
    data class BudgetStatus(val totalBudget: Int, val totalSpent: Float, val categoryProgress: List<CategoryBudget>)
    data class CategoryBudget(val category: String, val budget: Int, val spent: Float, val percent: Float)
    data class DayStat(val dayLabel: String, val amount: Float, val isPeak: Boolean, val isLow: Boolean)

    suspend fun generateReport(context: Context, isMonthly: Boolean, isCustomMode: Boolean = false, customStartMillis: Long = 0, customEndMillis: Long = 0, month: Int, year: Int, weekIndex: Int = -1): AdvisoryInsights = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val cal = Calendar.getInstance().apply { 
            firstDayOfWeek = Calendar.MONDAY
            set(year, month, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val startMillis: Long
        val endMillis: Long
        val prevStartMillis: Long
        val prevEndMillis: Long
        val label: String
        var topWeeksList = emptyList<WeeklyTrend>()

        if (isCustomMode) {
            startMillis = customStartMillis
            endMillis = customEndMillis
            
            val diff = endMillis - startMillis
            prevStartMillis = startMillis - diff - 1000L
            prevEndMillis = startMillis - 1000L
            
            val sdf = java.text.SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            label = "${sdf.format(Date(startMillis))} - ${sdf.format(Date(endMillis))}"
            
        } else if (isMonthly) {
            startMillis = cal.timeInMillis
            cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
            cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59)
            endMillis = cal.timeInMillis
            
            // Previous month
            cal.timeInMillis = startMillis
            cal.add(Calendar.MONTH, -1)
            prevStartMillis = cal.timeInMillis
            cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
            prevEndMillis = cal.timeInMillis
            
            label = java.text.SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(startMillis))
            
            // Calculate weekly top spenders for Monthly report
            topWeeksList = calculateWeeklyTrends(context, month, year).sortedByDescending { it.amount }
            
        } else {
            // Find specific week
            val weeks = calculateWeeklyTrends(context, month, year)
            if (weekIndex >= 0 && weekIndex < weeks.size) {
                val week = weeks[weekIndex]
                val sdf = java.text.SimpleDateFormat("MMM d", Locale.getDefault())
                val rangeParts = week.dates.split(" - ")
                val startDate = sdf.parse(rangeParts[0]) ?: Date()
                val endDate = sdf.parse(rangeParts[1]) ?: Date()
                
                cal.time = startDate
                cal.set(Calendar.YEAR, year)
                startMillis = cal.timeInMillis
                cal.time = endDate
                cal.set(Calendar.YEAR, year)
                cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59)
                endMillis = cal.timeInMillis
                
                prevStartMillis = startMillis - (7 * 24 * 60 * 60 * 1000L)
                prevEndMillis = startMillis - 1000L
                label = week.weekLabel
            } else {
                return@withContext generateEmptyReport("Week Selection Error")
            }
        }

        val currentBreakdown = HistoryDataManager.getCategoryBreakdownForRange(context, startMillis, endMillis)
        val prevBreakdown = HistoryDataManager.getCategoryBreakdownForRange(context, prevStartMillis, prevEndMillis)
        
        val totalSpent = currentBreakdown.values.sum()
        val prevTotal = prevBreakdown.values.sum()
        val changePer = if (prevTotal > 0) ((totalSpent - prevTotal) / prevTotal) * 100f else 0f
        val days = ((endMillis - startMillis) / (24*60*60*1000L) + 1).toInt().coerceAtLeast(1)
        val dailyAvg = totalSpent / days

        // Category breakdown
        val summaries = currentBreakdown.categories.mapIndexed { i, cat ->
            val amt = currentBreakdown.values[i]
            HistoryReportGenerator.CategorySummary(cat, amt, if (totalSpent > 0) (amt / totalSpent) * 100f else 0f)
        }.sortedByDescending { it.amount }


        val dowMap = mutableMapOf<Int, Float>()
        currentBreakdown.transactions.forEach { trans ->
            val p = trans.rawEntry.split("|")
            val ts = if (p.size >= 2) p[1].toLongOrNull() ?: 0L else 0L
            val c = Calendar.getInstance().apply { timeInMillis = ts }
            val dow = c.get(Calendar.DAY_OF_WEEK)
            dowMap[dow] = (dowMap[dow] ?: 0f) + trans.amount.toFloat()
        }
        
        // Daily Patterns (Date-wise or Day-wise based on mode)
        val dailyStats = mutableListOf<DayStat>()
        
        if (isCustomMode) {
            val dateMap = mutableMapOf<String, Float>()
            val sdf = java.text.SimpleDateFormat("MMM d", Locale.getDefault())
            currentBreakdown.transactions.forEach { trans ->
                val p = trans.rawEntry.split("|")
                val ts = if (p.size >= 2) p[1].toLongOrNull() ?: 0L else 0L
                val dateStr = sdf.format(Date(ts))
                dateMap[dateStr] = (dateMap[dateStr] ?: 0f) + trans.amount.toFloat()
            }
            
            val sortedDates = dateMap.entries.sortedByDescending { it.value }
            val dominantCount = if (days >= 10) 2 else 1
            sortedDates.take(dominantCount).forEach { entry ->
                dailyStats.add(DayStat(entry.key, entry.value, true, false))
            }
        } else {
            val dayLabels = if (isMonthly) listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat") else listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            val maxVal = dowMap.values.maxOrNull() ?: 1f
            val minVal = dowMap.values.minOrNull() ?: 0f
            
            for (i in (if (isMonthly) 1..7 else listOf(2,3,4,5,6,7,1))) {
                val amt = dowMap[i as Int] ?: 0f
                dailyStats.add(DayStat(
                    dayLabels[if (isMonthly) (i-1) else (if (i==1) 6 else i-2)], 
                    amt, 
                    amt == maxVal && amt > 0, 
                    amt == minVal && totalSpent > 0
                ))
            }
        }

        // Budget Status
        val budgetStatus = calculateBudgetStatus(context, currentBreakdown)

        // Savings Opportunity strings
        val savingsOpp = if (isMonthly && topWeeksList.isNotEmpty()) {
            val topWeek = topWeeksList.first()
            "You spent the most in ${topWeek.weekLabel} (${topWeek.dates}) consuming ₹${topWeek.amount.toInt()}. Reduce consumption in similar heavy weeks to save up to ₹${(topWeek.amount * 0.2f).toInt()}."
        } else {
            val top2Total = summaries.take(2).sumOf { it.amount.toDouble() }.toFloat()
            "Reducing your top 2 categories by 10% could save ₹${(top2Total * 0.1f).toInt()} next period."
        }


        AdvisoryInsights(
            label, totalSpent, prevTotal, changePer, dailyAvg, summaries, budgetStatus,
            dailyStats, savingsOpp, isMonthly, topWeeksList, isCustomMode
        )
    }

    private fun generateEmptyReport(error: String) = AdvisoryInsights(
        error, 0f, 0f, 0f, 0f, emptyList(), 
        BudgetStatus(0, 0f, emptyList()), emptyList(), "", false, emptyList()
    )

    suspend fun calculateWeeklyTrends(context: Context, month: Int, year: Int): List<WeeklyTrend> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val list = mutableListOf<WeeklyTrend>()
        val cal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            set(year, month, 1, 0, 0, 0)
        }
        
        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        
        var weekNum = 1
        val sdf = java.text.SimpleDateFormat("MMM d", Locale.getDefault())
        
        for (i in 0 until 6) {
            val weekStart = cal.timeInMillis
            val tuesdayCal = cal.clone() as Calendar
            tuesdayCal.add(Calendar.DAY_OF_YEAR, 1)
            
            if (tuesdayCal.get(Calendar.MONTH) == month) {
                val weekEndCal = cal.clone() as Calendar
                weekEndCal.add(Calendar.DAY_OF_YEAR, 6)
                weekEndCal.set(Calendar.HOUR_OF_DAY, 23); weekEndCal.set(Calendar.MINUTE, 59)
                val weekEnd = weekEndCal.timeInMillis
                
                val breakdown = HistoryDataManager.getCategoryBreakdownForRange(context, weekStart, weekEnd)
                val amt = breakdown.values.sum()
                val range = "${sdf.format(Date(weekStart))} - ${sdf.format(Date(weekEnd))}"
                list.add(WeeklyTrend("Week $weekNum", amt, range))
                weekNum++
            }
            cal.add(Calendar.DAY_OF_YEAR, 7)
            
            // Robust Termination: Safely exit loop if we pass into the next year or next month of target year
            val currentCalYear = cal.get(Calendar.YEAR)
            val currentCalMonth = cal.get(Calendar.MONTH)
            if (currentCalYear > year || (currentCalYear == year && currentCalMonth > month)) {
                break
            }
        }
        return@withContext list
    }

    private fun calculateBudgetStatus(context: Context, breakdown: HistoryDataManager.BreakdownResult): BudgetStatus {
        val prefs = context.getSharedPreferences("CategoryPrefs", Context.MODE_PRIVATE)
        val walletPrefs = WalletStore.get(context)
        val initialBalance = walletPrefs.getInt("initial_balance", 0)
        
        val progress = mutableListOf<CategoryBudget>()
        var totalBudget = 0
        breakdown.categories.forEachIndexed { i, cat ->
            val limit = if (cat == "no choice") 0 else prefs.getInt("LIMIT_$cat", 0)
            val spent = breakdown.values[i]
            progress.add(CategoryBudget(cat, limit, spent, if (limit > 0) (spent/limit)*100f else 0f))
            totalBudget += limit
        }
        if (totalBudget == 0) totalBudget = initialBalance
        return BudgetStatus(totalBudget, breakdown.values.sum(), progress)
    }
}
