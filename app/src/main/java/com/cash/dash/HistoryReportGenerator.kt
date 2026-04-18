package com.cash.dash

import android.content.Context
import java.util.*

object HistoryReportGenerator {

    data class CategorySummary(
        val category: String,
        val amount: Float,
        val percentage: Float
    )

    data class DeepAIInsight(
        val title: String,
        val content: String,
        val severity: Int, // 0: Neutral, 1: Warning, 2: Critical
        val type: InsightType = InsightType.GENERAL
    )

    enum class InsightType {
        VELOCITY, CATEGORY_SPIKE, OPTIMIZATION, STABILITY, GENERAL
    }

    data class ReportData(
        val summaries: List<CategorySummary>,
        val totalSpent: Float,
        val dailyAverage: Float,
        val topCategory: String,
        val previousPeriodTotal: Float = 0f,
        val forecastedMonthEnd: Float = 0f,
        val startMillis: Long = 0L,
        val endMillis: Long = 0L,
        val deepInsights: List<DeepAIInsight> = emptyList()
    )

    fun generateReport(context: Context, mode: String, year: Int, index: Int, month: Int = -1): ReportData {
        val breakdown = HistoryDataManager.getCategoryBreakdown(
            context, mode, index, index, if (mode == "WEEKLY") month else index, year
        )
        return processBreakdown(breakdown, 0)
    }

    fun generateReportForRange(context: Context, startMillis: Long, endMillis: Long): ReportData {
        val breakdown = HistoryDataManager.getCategoryBreakdownForRange(context, startMillis, endMillis)
        val days = ((endMillis - startMillis) / (1000 * 60 * 60 * 24)).coerceAtLeast(1).toInt()
        
        val report = processBreakdown(breakdown, days)
        return report.copy(startMillis = startMillis, endMillis = endMillis)
    }

    fun generateEnhancedReportForRange(context: Context, startMillis: Long, endMillis: Long): ReportData {
        val currentReport = generateReportForRange(context, startMillis, endMillis)
        
        // Calculate Previous Period (Duration based)
        val duration = endMillis - startMillis
        val prevStart = startMillis - duration - (1000 * 60 * 60 * 24)
        val prevEnd = startMillis - (1000 * 60 * 60 * 24)
        
        val prevBreakdown = HistoryDataManager.getCategoryBreakdownForRange(context, prevStart, prevEnd)
        val prevTotal = prevBreakdown.values.sum()
        
        val days = ((endMillis - startMillis) / (1000 * 60 * 60 * 24)).coerceAtLeast(1).toInt()
        val insights = synthesizeInsights(currentReport, prevBreakdown, days)

        return currentReport.copy(
            previousPeriodTotal = prevTotal,
            deepInsights = insights
        )
    }

    private fun synthesizeInsights(current: ReportData, prevBreakdown: HistoryDataManager.BreakdownResult, durationDays: Int): List<DeepAIInsight> {
        val list = mutableListOf<DeepAIInsight>()
        
        val totalDiff = current.totalSpent - prevBreakdown.values.sum()
        val totalPer = if (prevBreakdown.values.sum() > 0) (totalDiff / prevBreakdown.values.sum()) * 100f else 0f
        
        // 1. Overall Trajectory
        val trendTitle = if (totalDiff >= 0) "Expenditure Velocity Alert" else "Efficiency Achievement"
        val trendContent = if (prevBreakdown.values.sum() == 0f) {
            "First observation for this ${durationDays}-day duration. Maintaining a daily average of ₹${current.dailyAverage.toInt()}."
        } else {
            val direction = if (totalDiff >= 0) "higher" else "lower"
            "Your overall spending is ${kotlin.math.abs(totalPer).toInt()}% $direction compared to the previous $durationDays days."
        }
        list.add(DeepAIInsight(trendTitle, trendContent, if (totalPer > 15) 1 else 0, InsightType.VELOCITY))

        // 2. Category Deep Dives
        current.summaries.forEach { summary ->
            val prevIdx = prevBreakdown.categories.indexOf(summary.category)
            val prevAmt = if (prevIdx != -1) prevBreakdown.values[prevIdx] else 0f
            
            if (prevAmt > 0) {
                val catDiff = summary.amount - prevAmt
                val catPer = (catDiff / prevAmt) * 100f
                
                if (catPer > 20) {
                    list.add(DeepAIInsight(
                        "Strategic Shift: ${summary.category}",
                        "Expenditure here has spiked by ${catPer.toInt()}%. Suggest reducing allocation to preserve liquidity.",
                        if (catPer > 40) 2 else 1,
                        InsightType.CATEGORY_SPIKE
                    ))
                } else if (catPer < -20) {
                    list.add(DeepAIInsight(
                        "Budget Optimization: ${summary.category}",
                        "Discipline detected. Spending is down ${kotlin.math.abs(catPer).toInt()}%. Surplus can be reallocated.",
                        0,
                        InsightType.OPTIMIZATION
                    ))
                }
            }
        }
        
        if (list.size < 2 && current.totalSpent > 0) {
            list.add(DeepAIInsight("Stability Note", "Spending patterns are remarkably consistent. No immediate tactical adjustments required.", 0, InsightType.STABILITY))
        }

        return list
    }

    private fun processBreakdown(breakdown: HistoryDataManager.BreakdownResult, days: Int): ReportData {
        val total = breakdown.values.sum()
        val rawSummaries = mutableListOf<CategorySummary>()

        if (total > 0) {
            for (i in breakdown.categories.indices) {
                val amt = breakdown.values[i]
                if (amt > 0) {
                    rawSummaries.add(CategorySummary(
                        category = breakdown.categories[i],
                        amount = amt,
                        percentage = (amt / total) * 100f
                    ))
                }
            }
        }
        
        rawSummaries.sortByDescending { it.amount }

        val finalSummaries = mutableListOf<CategorySummary>()
        if (rawSummaries.size > 5) {
            for (i in 0 until 4) finalSummaries.add(rawSummaries[i])
            var otherAmt = 0f
            for (i in 4 until rawSummaries.size) otherAmt += rawSummaries[i].amount
            finalSummaries.add(CategorySummary("Other", otherAmt, (otherAmt / total) * 100f))
        } else {
            finalSummaries.addAll(rawSummaries)
        }

        val calcDays = if (days <= 0) 1 else days

        return ReportData(
            summaries = finalSummaries,
            totalSpent = total,
            dailyAverage = total / calcDays,
            topCategory = if (finalSummaries.isNotEmpty()) finalSummaries[0].category else "None"
        )
    }
}
