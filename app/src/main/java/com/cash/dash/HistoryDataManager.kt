package com.cash.dash

import android.content.Context

object HistoryDataManager {

    data class BreakdownResult(
        val categories: List<String>,
        val values: List<Float>,
        val transactions: List<TransactionItem>
    )

    fun getCategoryBreakdown(
        context: Context,
        mode: String,
        index: Int,
        week: Int,
        month: Int,
        year: Int,
        categoryFilter: String = "Overall"
    ): BreakdownResult {

        val prefsGraph = context.getSharedPreferences("GraphData", Context.MODE_PRIVATE)
        val prefsCat = context.getSharedPreferences("CategoryPrefs", Context.MODE_PRIVATE)

        val savedCategories = prefsCat.getStringSet("categories", emptySet())?.toList() ?: emptyList()
        val categories = savedCategories.toMutableList()
        
        // Temporarily hold uncategorized value
        var noChoiceValue = 0f
        val values = MutableList(categories.size) { 0f }
        val transactions = mutableListOf<TransactionItem>()

        val historyList = prefsGraph.getStringSet("HISTORY_LIST", emptySet()) ?: emptySet()

        val cal = java.util.Calendar.getInstance().apply {
            firstDayOfWeek = java.util.Calendar.MONDAY
            minimalDaysInFirstWeek = 1
        }

        for (entry in historyList) {
            val parts = entry.split("|")
            if (parts.size < 5) continue

            val category: String
            val amount: Float
            val title: String
            var timestampLong: Long? = null

            if (parts.size >= 9) {
                timestampLong = parts[1].toLongOrNull()
                title = parts[2]
                category = parts[3]
                amount = parts[4].toFloatOrNull() ?: 0f
            } else if (parts.size == 7) {
                title = "Expense"
                category = parts[1]
                amount = parts[2].toFloatOrNull() ?: 0f
                // Legacy 7-part has no timestamp. We fallback to stored indices.
            } else {
                timestampLong = parts[1].toLongOrNull()
                title = "Expense"
                category = parts[3]
                amount = parts[4].toFloatOrNull() ?: 0f
            }

            val hYear: Int
            val hMonth: Int
            val hWeek: Int
            val hDay: Int

            if (parts.size >= 9) {
                hWeek = parts[5].toIntOrNull() ?: 0
                hDay = parts[6].toIntOrNull() ?: 0
                hMonth = parts[7].toIntOrNull() ?: 0
                hYear = parts[8].toIntOrNull() ?: 0
            } else if (parts.size == 7) {
                hWeek = parts[3].toIntOrNull() ?: 0
                hDay = parts[4].toIntOrNull() ?: 0
                hMonth = parts[5].toIntOrNull() ?: 0
                hYear = parts[6].toIntOrNull() ?: 0
            } else if (timestampLong != null) {
                cal.timeInMillis = timestampLong
                hYear = cal.get(java.util.Calendar.YEAR)
                hMonth = cal.get(java.util.Calendar.MONTH)
                hWeek = cal.get(java.util.Calendar.WEEK_OF_MONTH) - 1
                hDay = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
            } else continue

            val match = when (mode) {
                "DAILY" -> hWeek == week && hDay == index && hMonth == month && hYear == year
                "WEEKLY" -> hWeek == index && hMonth == month && hYear == year
                "MONTHLY" -> hMonth == index && hYear == year
                else -> false
            }

            if (!match) continue

            val catDisplay = "(${category.uppercase()})"

            // Filter transactions
            if (categoryFilter == "Overall" || category == categoryFilter) {
                transactions.add(
                    TransactionItem(
                        title = title,
                        category = catDisplay,
                        amount = amount.toInt(),
                        rawEntry = entry
                    )
                )
            }

            if (category == "no choice") {
                if (categoryFilter == "Overall" || categoryFilter == "no choice") {
                    noChoiceValue += amount
                }
            } else {
                val idx = categories.indexOf(category)
                if (idx != -1) {
                    if (categoryFilter == "Overall" || category == categoryFilter) {
                        values[idx] += amount
                    }
                }
            }
        }

        // Finalize categories list: 
        // 1. If categoryFilter is "Overall", show ALL saved categories (even if 0)
        // 2. If categoryFilter is a specific category, show ONLY that one
        // 3. Always show "no choice" if it has value > 0 and filter is Overall
        
        val finalCats = mutableListOf<String>()
        val finalVals = mutableListOf<Float>()
        
        if (categoryFilter == "Overall") {
            // Show all saved categories
            for (i in categories.indices) {
                finalCats.add(categories[i])
                finalVals.add(values[i])
            }
            // Add "no choice" only if it has data
            if (noChoiceValue > 0) {
                finalCats.add("no choice")
                finalVals.add(noChoiceValue)
            }
        } else {
            // Specific filter (including "no choice")
            if (categoryFilter == "no choice") {
                if (noChoiceValue > 0) {
                    finalCats.add("no choice")
                    finalVals.add(noChoiceValue)
                }
            } else {
                val idx = categories.indexOf(categoryFilter)
                if (idx != -1) {
                    finalCats.add(categories[idx])
                    finalVals.add(values[idx])
                }
            }
        }

        return BreakdownResult(finalCats, finalVals, transactions)
    }
}
