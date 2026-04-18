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
        val habitInsights: List<HabitInsight>,
        val alerts: List<AdvisoryAlert>,
        val dailyPatterns: List<DayStat>,
        val savingsOpportunity: String,
        val achievements: List<String>,
        val transactionStats: TransactionStats,
        val score: Int,
        val prediction: Float
    )

    data class HabitInsight(val category: String, val message: String, val icon: String)
    data class AdvisoryAlert(val title: String, val message: String, val severity: Int) // 0:Info, 1:Warning, 2:Critical
    data class WeeklyTrend(val weekLabel: String, val amount: Float, val dates: String)
    data class BudgetStatus(val totalBudget: Int, val totalSpent: Float, val categoryProgress: List<CategoryBudget>)
    data class CategoryBudget(val category: String, val budget: Int, val spent: Float, val percent: Float)
    data class DayStat(val dayLabel: String, val amount: Float, val isPeak: Boolean, val isLow: Boolean)
    data class TransactionStats(val totalCount: Int, val avgPerTrans: Float, val frequentCategory: String)

    fun generateReport(context: Context, isMonthly: Boolean, month: Int, year: Int, weekIndex: Int = -1): AdvisoryInsights {
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

        if (isMonthly) {
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
        } else {
            // Find specific week
            val weeks = calculateWeeklyTrends(context, month, year)
            if (weekIndex >= 0 && weekIndex < weeks.size) {
                // Parse dates from label (simple approach) or recalculate
                val week = weeks[weekIndex]
                val sdf = java.text.SimpleDateFormat("MMM d", Locale.getDefault())
                val rangeParts = week.dates.split(" - ")
                val startDate = sdf.parse(rangeParts[0]) ?: Date()
                val endDate = sdf.parse(rangeParts[1]) ?: Date()
                
                cal.time = startDate
                cal.set(Calendar.YEAR, year) // Fix year since sdf might parse to 1970
                startMillis = cal.timeInMillis
                cal.time = endDate
                cal.set(Calendar.YEAR, year)
                cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59)
                endMillis = cal.timeInMillis
                
                prevStartMillis = startMillis - (7 * 24 * 60 * 60 * 1000L)
                prevEndMillis = startMillis - 1000L
                label = week.weekLabel
            } else {
                return generateEmptyReport("Week Selection Error")
            }
        }

        val currentBreakdown = HistoryDataManager.getCategoryBreakdownForRange(context, startMillis, endMillis)
        val prevBreakdown = HistoryDataManager.getCategoryBreakdownForRange(context, prevStartMillis, prevEndMillis)
        
        val totalSpent = currentBreakdown.values.sum()
        val prevTotal = prevBreakdown.values.sum()
        val changePer = if (prevTotal > 0) ((totalSpent - prevTotal) / prevTotal) * 100f else 0f
        val days = ((endMillis - startMillis) / (24*60*60*1000L) + 1).toInt()
        val dailyAvg = totalSpent / days

        // Category breakdown for 3D Chart
        val summaries = currentBreakdown.categories.mapIndexed { i, cat ->
            val amt = currentBreakdown.values[i]
            HistoryReportGenerator.CategorySummary(cat, amt, if (totalSpent > 0) (amt / totalSpent) * 100f else 0f)
        }.filter { it.amount > 0 }.sortedByDescending { it.amount }

        // 1. Habits & Patterns
        val habits = mutableListOf<HabitInsight>()
        val dowMap = mutableMapOf<Int, Float>()
        val hourMap = mutableMapOf<Int, Float>()
        currentBreakdown.transactions.forEach { trans ->
            val p = trans.rawEntry.split("|")
            val ts = if (p.size >= 2) p[1].toLongOrNull() ?: 0L else 0L
            val c = Calendar.getInstance().apply { timeInMillis = ts }
            val dow = c.get(Calendar.DAY_OF_WEEK)
            val hour = c.get(Calendar.HOUR_OF_DAY)
            dowMap[dow] = (dowMap[dow] ?: 0f) + trans.amount.toFloat()
            hourMap[hour] = (hourMap[hour] ?: 0f) + trans.amount.toFloat()
        }
        
        val weekendSpan = (dowMap[Calendar.SATURDAY] ?: 0f) + (dowMap[Calendar.SUNDAY] ?: 0f)
        if (weekendSpan > (totalSpent * 0.4f)) habits.add(HabitInsight("Pattern", "You tend to spend more on weekends.", "⚡"))
        
        val lateNight = hourMap.filter { it.key >= 20 || it.key < 5 }.values.sum()
        if (lateNight > (totalSpent * 0.25f)) habits.add(HabitInsight("Timing", "Most of your expenses occur after 8 PM.", "🌙"))

        // 2. Alerts
        val alerts = mutableListOf<AdvisoryAlert>()
        val budgetStatus = calculateBudgetStatus(context, currentBreakdown)
        if (totalSpent > budgetStatus.totalBudget && budgetStatus.totalBudget > 0) {
            alerts.add(AdvisoryAlert("Overbudget Warning", "You've exceeded your total budget by ₹${(totalSpent - budgetStatus.totalBudget).toInt()}.", 2))
        }
        
        summaries.take(2).forEach { summary ->
            val prevIdx = prevBreakdown.categories.indexOf(summary.category)
            val prevAmt = if (prevIdx != -1) prevBreakdown.values[prevIdx] else 0f
            if (prevAmt > 0 && summary.amount > prevAmt * 1.5f) {
                alerts.add(AdvisoryAlert("Spike Detected", "Spending on ${summary.category} is ${( (summary.amount-prevAmt)/prevAmt * 100).toInt()}% higher than last period.", 1))
            }
        }

        // 3. Daily Patterns
        val dayLabels = if (isMonthly) listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat") else listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val dailyStats = mutableListOf<DayStat>()
        val maxVal = dowMap.values.maxOrNull() ?: 1f
        val minVal = dowMap.values.minOrNull() ?: 0f
        
        // Correct DOW mapping based on locale/mode
        for (i in (if (isMonthly) 1..7 else listOf(2,3,4,5,6,7,1))) {
            val amt = dowMap[i as Int] ?: 0f
            dailyStats.add(DayStat(
                dayLabels[if (isMonthly) (i-1) else (if (i==1) 6 else i-2)], 
                amt, 
                amt == maxVal && amt > 0, 
                amt == minVal && totalSpent > 0
            ))
        }

        // 4. Achievement & Savings
        val achievements = mutableListOf<String>()
        if (totalSpent < budgetStatus.totalBudget || budgetStatus.totalBudget == 0) achievements.add("Stayed under budget this ${if (isMonthly) "month" else "week"}! 🎉")
        if (changePer < -10) achievements.add("Efficiency unlocked: Spending is down ${abs(changePer).toInt()}%")
        
        val top2Total = summaries.take(2).sumOf { it.amount.toDouble() }.toFloat()
        val savingsOpp = "Reducing your top 2 categories by 10% could save ₹${(top2Total * 0.1f).toInt()} next period."

        // 5. Transaction Stats
        val freqCat = summaries.maxByOrNull { it.amount }?.category ?: "None"
        val stats = TransactionStats(
            currentBreakdown.transactions.size,
            if (currentBreakdown.transactions.isNotEmpty()) totalSpent / currentBreakdown.transactions.size else 0f,
            freqCat
        )

        return AdvisoryInsights(
            label, totalSpent, prevTotal, changePer, dailyAvg, summaries, budgetStatus,
            habits, alerts, dailyStats, savingsOpp, achievements, stats,
            calculateScore(totalSpent, budgetStatus.totalBudget, changePer),
            totalSpent * (1 + (changePer / 200f)).coerceIn(0.8f, 1.5f)
        )
    }

    private fun calculateScore(spent: Float, budget: Int, change: Float): Int {
        var s = 85
        if (budget > 0 && spent > budget) s -= 20
        if (change > 15) s -= 10
        if (change < -5) s += 5
        return s.coerceIn(0, 100)
    }

    private fun generateEmptyReport(error: String) = AdvisoryInsights(
        error, 0f, 0f, 0f, 0f, emptyList(), 
        BudgetStatus(0, 0f, emptyList()), emptyList(), emptyList(), emptyList(),
        "", emptyList(), TransactionStats(0, 0f, ""), 0, 0f
    )

    fun calculateWeeklyTrends(context: Context, month: Int, year: Int): List<WeeklyTrend> {
        val list = mutableListOf<WeeklyTrend>()
        val cal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            set(year, month, 1, 0, 0, 0)
        }
        
        // Find the first Monday on or before the 1st
        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        
        var weekNum = 1
        val sdf = java.text.SimpleDateFormat("MMM d", Locale.getDefault())
        
        repeat(6) {
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
            if (cal.get(Calendar.MONTH) > month && cal.get(Calendar.YEAR) >= year) return@repeat
        }
        return list
    }

    private fun calculateBudgetStatus(context: Context, breakdown: HistoryDataManager.BreakdownResult): BudgetStatus {
        val prefs = context.getSharedPreferences("CategoryPrefs", Context.MODE_PRIVATE)
        val walletPrefs = context.getSharedPreferences("WalletPrefs", Context.MODE_PRIVATE)
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

    fun detectRecurring(transactions: List<TransactionItem>): List<RecurringExpense> {
        return emptyList() // Placeholder or implement if needed
    }
    
    data class RecurringExpense(val title: String, val amount: Float, val freq: String)
}
