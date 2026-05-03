package com.cash.dash

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object HistoryDataManager {

    fun saveTransaction(
        context: Context,
        title: String,
        amount: Float,
        category: String,
        timestamp: Long = System.currentTimeMillis()
    ) {
        val cal = java.util.Calendar.getInstance().apply {
            firstDayOfWeek = java.util.Calendar.MONDAY
            minimalDaysInFirstWeek = 1
            timeInMillis = timestamp
        }

        val hYear = cal.get(java.util.Calendar.YEAR)
        val hMonth = cal.get(java.util.Calendar.MONTH)
        val hWeek = cal.get(java.util.Calendar.WEEK_OF_MONTH) - 1
        val hDay = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7

        val rawEntry = "EXP|$timestamp|$title|$category|${amount.toInt()}|$hWeek|$hDay|$hMonth|$hYear"

        // 1. Save to Room (Async)
        CoroutineScope(Dispatchers.IO).launch {
            val entity = TransactionEntity(
                timestamp = timestamp,
                title = title,
                category = category,
                amount = amount.toInt(),
                week = hWeek,
                day = hDay,
                month = hMonth,
                year = hYear,
                rawEntry = rawEntry
            )
            AppDatabase.getDatabase(context).transactionDao().insert(entity)
        }

        // 2. Save to SharedPreferences (Legacy Support)
        val prefs = context.getSharedPreferences("GraphData", Context.MODE_PRIVATE)
        val historySet = prefs.getStringSet("HISTORY_LIST", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        historySet.add(rawEntry)
        
        val editor = prefs.edit()
        editor.putStringSet("HISTORY_LIST", historySet)
        
        // Update Spent and Slot keys
        editor.putFloat("SPENT_$category", prefs.getFloat("SPENT_$category", 0f) + amount)
        editor.putFloat("DAY_${hWeek}_${hDay}_${hMonth}_${hYear}", prefs.getFloat("DAY_${hWeek}_${hDay}_${hMonth}_${hYear}", 0f) + amount)
        editor.putFloat("WEEK_${hWeek}_${hMonth}_${hYear}", prefs.getFloat("WEEK_${hWeek}_${hMonth}_${hYear}", 0f) + amount)
        editor.putFloat("MONTH_${hMonth}_${hYear}", prefs.getFloat("MONTH_${hMonth}_${hYear}", 0f) + amount)
        
        // Metadata
        editor.putString("TRANS_${timestamp}_TITLE", title)
        editor.putString("TRANS_${timestamp}_CATEGORY", category)
        editor.putInt("TRANS_${timestamp}_AMOUNT", amount.toInt())
        editor.putInt("TRANS_${timestamp}_WEEK", hWeek)
        editor.putInt("TRANS_${timestamp}_DAY", hDay)
        editor.putInt("TRANS_${timestamp}_MONTH", hMonth)
        editor.putInt("TRANS_${timestamp}_YEAR", hYear)
        
        editor.apply()

        // 3. Update Wallet Balance
        val prefsWallet = context.getSharedPreferences("WalletPrefs", Context.MODE_PRIVATE)
        val currentBal = prefsWallet.getInt("wallet_balance", 0)
        prefsWallet.edit().putInt("wallet_balance", currentBal - amount.toInt()).apply()

        // CategoryWeekData
        val prefsWeek = context.getSharedPreferences("CategoryWeekData", Context.MODE_PRIVATE)
        val catWeekKey = "${category}_W${hWeek + 1}"
        prefsWeek.edit().putInt(catWeekKey, prefsWeek.getInt(catWeekKey, 0) + amount.toInt()).apply()

        // 3. Trigger Firestore Sync
        FirestoreSyncManager.pushAllDataToCloud(context)
    }

    fun deleteTransaction(context: Context, rawEntry: String) {
        val parts = rawEntry.split("|")
        if (parts.size < 5) return

        val amount = parts[4].toFloatOrNull() ?: 0f
        val category = parts[3]
        val timestampStr = parts[1]

        // 1. Delete from Room (Async)
        CoroutineScope(Dispatchers.IO).launch {
            AppDatabase.getDatabase(context).transactionDao().deleteByRawEntry(rawEntry)
        }

        // 2. Delete from SharedPreferences (Legacy)
        val prefsGraph = context.getSharedPreferences("GraphData", Context.MODE_PRIVATE)
        val historyList = prefsGraph.getStringSet("HISTORY_LIST", emptySet())?.toMutableSet() ?: mutableSetOf()
        
        if (historyList.remove(rawEntry)) {
            prefsGraph.edit().putStringSet("HISTORY_LIST", historyList).apply()

            // 3. Restore Wallet Balance
            val prefsWallet = context.getSharedPreferences("WalletPrefs", Context.MODE_PRIVATE)
            val currentBal = prefsWallet.getInt("wallet_balance", 0)
            prefsWallet.edit().putInt("wallet_balance", currentBal + amount.toInt()).apply()

            // 4. Adjust SPENT_$category
            val oldSpent = prefsGraph.getFloat("SPENT_$category", 0f)
            prefsGraph.edit().putFloat("SPENT_$category", (oldSpent - amount).coerceAtLeast(0f)).apply()

            // 5. Adjust analytical slots
            if (parts.size >= 9) {
                val hWeek = parts[5]
                val hDay = parts[6]
                val hMonth = parts[7]
                val hYear = parts[8]

                val dayKey = "DAY_${hWeek}_${hDay}_${hMonth}_${hYear}"
                val weekKey = "WEEK_${hWeek}_${hMonth}_${hYear}"
                val monthKey = "MONTH_${hMonth}_${hYear}"

                prefsGraph.edit()
                    .putFloat(dayKey, (prefsGraph.getFloat(dayKey, 0f) - amount).coerceAtLeast(0f))
                    .putFloat(weekKey, (prefsGraph.getFloat(weekKey, 0f) - amount).coerceAtLeast(0f))
                    .putFloat(monthKey, (prefsGraph.getFloat(monthKey, 0f) - amount).coerceAtLeast(0f))
                    .apply()

                // Adjust CategoryWeekData
                val prefsWeek = context.getSharedPreferences("CategoryWeekData", Context.MODE_PRIVATE)
                val catWeekKey = "${category}_W${hWeek.toInt() + 1}"
                val oldCatWeek = prefsWeek.getInt(catWeekKey, 0)
                prefsWeek.edit().putInt(catWeekKey, (oldCatWeek - amount.toInt()).coerceAtLeast(0)).apply()
            }

            // 6. Remove metadata
            prefsGraph.edit().remove("TRANS_${timestampStr}_TITLE").apply()
        }
        
        FirestoreSyncManager.pushAllDataToCloud(context)
    }

    fun updateTransactionTitle(context: Context, rawEntry: String, newTitle: String) {
        val parts = rawEntry.split("|").toMutableList()
        if (parts.size < 9) return

        val oldRawEntry = rawEntry
        parts[2] = newTitle
        val newRawEntry = parts.joinToString("|")
        val timestamp = parts[1]

        // 1. Update Room (Async)
        CoroutineScope(Dispatchers.IO).launch {
            AppDatabase.getDatabase(context).transactionDao().updateTitle(oldRawEntry, newRawEntry, newTitle)
        }

        // 2. Update SharedPreferences
        val prefs = context.getSharedPreferences("GraphData", Context.MODE_PRIVATE)
        val historyList = prefs.getStringSet("HISTORY_LIST", emptySet())?.toMutableSet() ?: mutableSetOf()
        
        if (historyList.remove(oldRawEntry)) {
            historyList.add(newRawEntry)
            prefs.edit()
                .putStringSet("HISTORY_LIST", historyList)
                .putString("TRANS_${timestamp}_TITLE", newTitle)
                .apply()
        }
        
        FirestoreSyncManager.pushAllDataToCloud(context)
    }

    fun reallocateTransaction(context: Context, rawEntry: String, newCategory: String) {
        val parts = rawEntry.split("|").toMutableList()
        if (parts.size < 9) return

        val oldRawEntry = rawEntry
        val oldCategory = parts[3]
        val amount = parts[4].toIntOrNull() ?: 0
        val timestamp = parts[1]
        val hWeek = parts[5].toInt()

        parts[3] = newCategory
        val newRawEntry = parts.joinToString("|")

        // 1. Update Room (Async)
        CoroutineScope(Dispatchers.IO).launch {
            AppDatabase.getDatabase(context).transactionDao().updateCategory(oldRawEntry, newRawEntry, newCategory)
        }

        // 2. Update SharedPreferences
        val prefs = context.getSharedPreferences("GraphData", Context.MODE_PRIVATE)
        val historyList = prefs.getStringSet("HISTORY_LIST", emptySet())?.toMutableSet() ?: mutableSetOf()
        
        if (historyList.remove(oldRawEntry)) {
            historyList.add(newRawEntry)
            
            val oldSpent = prefs.getFloat("SPENT_$oldCategory", 0f)
            val newSpent = prefs.getFloat("SPENT_$newCategory", 0f)

            prefs.edit()
                .putStringSet("HISTORY_LIST", historyList)
                .putFloat("SPENT_$oldCategory", (oldSpent - amount).coerceAtLeast(0f))
                .putFloat("SPENT_$newCategory", newSpent + amount)
                .putString("TRANS_${timestamp}_CATEGORY", newCategory)
                .apply()

            // 3. CategoryWeekData Analytics
            val prefsWeek = context.getSharedPreferences("CategoryWeekData", Context.MODE_PRIVATE)
            val oldKey = "${oldCategory}_W${hWeek + 1}"
            val newKey = "${newCategory}_W${hWeek + 1}"
            val oldVal = prefsWeek.getInt(oldKey, 0)
            val newVal = prefsWeek.getInt(newKey, 0)
            
            prefsWeek.edit()
                .putInt(oldKey, (oldVal - amount).coerceAtLeast(0))
                .putInt(newKey, newVal + amount)
                .apply()
        }
        
        FirestoreSyncManager.pushAllDataToCloud(context)
    }

    fun updateTransactionAmount(context: Context, rawEntry: String, newAmount: Int) {
        val parts = rawEntry.split("|").toMutableList()
        if (parts.size < 9) return

        val oldRawEntry = rawEntry
        val oldAmount = parts[4].toIntOrNull() ?: 0
        val delta = newAmount - oldAmount
        val category = parts[3]
        val timestamp = parts[1]
        
        val hWeek = parts[5]
        val hDay = parts[6]
        val hMonth = parts[7]
        val hYear = parts[8]

        parts[4] = newAmount.toString()
        val newRawEntry = parts.joinToString("|")

        // 1. Update Room (Async)
        CoroutineScope(Dispatchers.IO).launch {
            AppDatabase.getDatabase(context).transactionDao().updateAmount(oldRawEntry, newRawEntry, newAmount)
        }

        // 2. Update SharedPreferences
        val prefs = context.getSharedPreferences("GraphData", Context.MODE_PRIVATE)
        val historyList = prefs.getStringSet("HISTORY_LIST", emptySet())?.toMutableSet() ?: mutableSetOf()
        
        if (historyList.remove(oldRawEntry)) {
            historyList.add(newRawEntry)
            
            val prefsWallet = context.getSharedPreferences("WalletPrefs", Context.MODE_PRIVATE)
            val currentBal = prefsWallet.getInt("wallet_balance", 0)
            prefsWallet.edit().putInt("wallet_balance", currentBal - delta).apply()

            val editor = prefs.edit()
            editor.putStringSet("HISTORY_LIST", historyList)
            
            val spentKey = "SPENT_$category"
            editor.putFloat(spentKey, (prefs.getFloat(spentKey, 0f) + delta).coerceAtLeast(0f))
            
            val dayKey = "DAY_${hWeek}_${hDay}_${hMonth}_${hYear}"
            val weekKey = "WEEK_${hWeek}_${hMonth}_${hYear}"
            val monthKey = "MONTH_${hMonth}_${hYear}"
            
            editor.putFloat(dayKey, (prefs.getFloat(dayKey, 0f) + delta).coerceAtLeast(0f))
            editor.putFloat(weekKey, (prefs.getFloat(weekKey, 0f) + delta).coerceAtLeast(0f))
            editor.putFloat(monthKey, (prefs.getFloat(monthKey, 0f) + delta).coerceAtLeast(0f))
            
            editor.putInt("TRANS_${timestamp}_AMOUNT", newAmount)
            editor.apply()

            // 3. CategoryWeekData Analytics
            val prefsWeek = context.getSharedPreferences("CategoryWeekData", Context.MODE_PRIVATE)
            val catWeekKey = "${category}_W${hWeek.toInt() + 1}"
            val oldVal = prefsWeek.getInt(catWeekKey, 0)
            prefsWeek.edit().putInt(catWeekKey, (oldVal + delta).coerceAtLeast(0)).apply()
        }
        
        FirestoreSyncManager.pushAllDataToCloud(context)
    }

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
        val db = AppDatabase.getDatabase(context)
        val dao = db.transactionDao()
        
        val prefsCat = context.getSharedPreferences("CategoryPrefs", Context.MODE_PRIVATE)
        val savedCategories = prefsCat.getStringSet("categories", emptySet())?.toList() ?: emptyList()
        val categories = savedCategories.toMutableList()

        // Fetch all relevant transactions from Room (Blocking for sync compatibility)
        // In a real app, this should be suspend, but we are doing a mid-migration bridge.
        val allTransactions = dao.getTransactionsInRange(0, Long.MAX_VALUE)

        var noChoiceValue = 0f
        val values = MutableList(categories.size) { 0f }
        val transactions = mutableListOf<TransactionItem>()

        val cal = java.util.Calendar.getInstance().apply {
            firstDayOfWeek = java.util.Calendar.MONDAY
            minimalDaysInFirstWeek = 1
        }

        for (item in allTransactions) {
            val hYear = item.year
            val hMonth = item.month
            val hWeek = item.week
            val hDay = item.day

            val match = when (mode) {
                "DAILY" -> hWeek == week && hDay == index && hMonth == month && hYear == year
                "WEEKLY" -> hWeek == index && hMonth == month && hYear == year
                "MONTHLY" -> hMonth == index && hYear == year
                else -> false
            }

            if (!match) continue

            // Filter transactions
            if (categoryFilter == "Overall" || item.category == categoryFilter) {
                transactions.add(
                    TransactionItem(
                        title = item.title,
                        category = "(${item.category})",
                        amount = item.amount,
                        rawEntry = item.rawEntry
                    )
                )
            }

            if (item.category == "no choice") {
                if (categoryFilter == "Overall" || categoryFilter == "no choice") {
                    noChoiceValue += item.amount
                }
            } else {
                val idx = categories.indexOf(item.category)
                if (idx != -1) {
                    if (categoryFilter == "Overall" || item.category == categoryFilter) {
                        values[idx] += item.amount.toFloat()
                    }
                }
            }
        }

        val finalCats = mutableListOf<String>()
        val finalVals = mutableListOf<Float>()
        
        if (categoryFilter == "Overall") {
            for (i in categories.indices) {
                finalCats.add(categories[i]); finalVals.add(values[i])
            }
            if (noChoiceValue > 0) {
                finalCats.add("no choice"); finalVals.add(noChoiceValue)
            }
        } else {
            if (categoryFilter == "no choice") {
                if (noChoiceValue > 0) {
                    finalCats.add("no choice"); finalVals.add(noChoiceValue)
                }
            } else {
                val idx = categories.indexOf(categoryFilter)
                if (idx != -1) {
                    finalCats.add(categories[idx]); finalVals.add(values[idx])
                }
            }
        }
        return BreakdownResult(finalCats, finalVals, transactions)
    }

    fun getCategoryBreakdownForRange(
        context: Context,
        startMillis: Long,
        endMillis: Long,
        categoryFilter: String = "Overall"
    ): BreakdownResult {
        val db = AppDatabase.getDatabase(context)
        val dao = db.transactionDao()

        val prefsCat = context.getSharedPreferences("CategoryPrefs", Context.MODE_PRIVATE)
        val savedCategories = prefsCat.getStringSet("categories", emptySet())?.toList() ?: emptyList()
        val categories = savedCategories.toMutableList()
        
        val transactionsInRoom = if (categoryFilter == "Overall") {
            dao.getTransactionsInRange(startMillis, endMillis)
        } else {
            dao.getTransactionsByCategoryInRange(categoryFilter, startMillis, endMillis)
        }

        var noChoiceValue = 0f
        val values = MutableList(categories.size) { 0f }
        val resultTransactions = mutableListOf<TransactionItem>()

        for (item in transactionsInRoom) {
            resultTransactions.add(TransactionItem(item.title, "(${item.category})", item.amount, item.rawEntry))

            if (item.category == "no choice") {
                noChoiceValue += item.amount
            } else {
                val idx = categories.indexOf(item.category)
                if (idx != -1) values[idx] += item.amount.toFloat()
            }
        }

        val finalCats = mutableListOf<String>()
        val finalVals = mutableListOf<Float>()
        
        for (i in categories.indices) {
            finalCats.add(categories[i]); finalVals.add(values[i])
        }
        if (noChoiceValue > 0) {
            finalCats.add("no choice"); finalVals.add(noChoiceValue)
        }

        return BreakdownResult(finalCats, finalVals, resultTransactions)
    }
    fun renameCategory(context: Context, oldName: String, newName: String) {
        // 1. Update Room (Async)
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.getDatabase(context).transactionDao()
            val transactions = dao.getTransactionsByCategoryInRange(oldName, 0L, Long.MAX_VALUE)
            for (item in transactions) {
                val parts = item.rawEntry.split("|").toMutableList()
                if (parts.size >= 4) {
                    parts[3] = newName
                    val newRaw = parts.joinToString("|")
                    dao.updateCategory(item.rawEntry, newRaw, newName)
                }
            }
        }

        // 2. Update SharedPreferences
        val prefsGraph = context.getSharedPreferences("GraphData", Context.MODE_PRIVATE)
        val historyList = prefsGraph.getStringSet("HISTORY_LIST", emptySet())?.toMutableSet() ?: mutableSetOf()
        val newHistoryList = mutableSetOf<String>()
        
        historyList.forEach { entry ->
            val parts = entry.split("|").toMutableList()
            if (parts.size >= 4 && parts[3] == oldName) {
                parts[3] = newName
                val newEntry = parts.joinToString("|")
                newHistoryList.add(newEntry)
                
                val timestamp = parts[1]
                prefsGraph.edit().putString("TRANS_${timestamp}_CATEGORY", newName).apply()
            } else {
                newHistoryList.add(entry)
            }
        }
        prefsGraph.edit().putStringSet("HISTORY_LIST", newHistoryList).apply()

        // 3. Update SPENT_ keys
        val oldSpent = prefsGraph.getFloat("SPENT_$oldName", 0f)
        prefsGraph.edit().putFloat("SPENT_$newName", oldSpent).remove("SPENT_$oldName").apply()

        // 4. CategoryWeekData
        val prefsWeek = context.getSharedPreferences("CategoryWeekData", Context.MODE_PRIVATE)
        val weekEditor = prefsWeek.edit()
        for (w in 1..5) {
            val key = "${oldName}_W$w"
            val valW = prefsWeek.getInt(key, 0)
            if (valW > 0) {
                weekEditor.putInt("${newName}_W$w", valW).remove(key)
            }
        }
        weekEditor.apply()
        
        FirestoreSyncManager.pushAllDataToCloud(context)
    }

    fun deleteCategory(context: Context, category: String) {
        // 1. Update Room (Async)
        CoroutineScope(Dispatchers.IO).launch {
            AppDatabase.getDatabase(context).transactionDao().deleteByCategory(category)
        }

        // 2. Update SharedPreferences
        val prefsGraph = context.getSharedPreferences("GraphData", Context.MODE_PRIVATE)
        val historyList = prefsGraph.getStringSet("HISTORY_LIST", emptySet())?.toMutableSet() ?: mutableSetOf()
        val newHistoryList = historyList.filter { !it.contains("|$category|") }.toSet()
        
        prefsGraph.edit()
            .putStringSet("HISTORY_LIST", newHistoryList)
            .remove("SPENT_$category")
            .apply()

        // 3. CategoryWeekData
        val prefsWeek = context.getSharedPreferences("CategoryWeekData", Context.MODE_PRIVATE)
        val weekEditor = prefsWeek.edit()
        for (w in 1..5) {
            weekEditor.remove("${category}_W$w")
        }
        weekEditor.apply()
        
        FirestoreSyncManager.pushAllDataToCloud(context)
    }

}
