package com.cash.dash

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object MigrationManager {

    fun checkAndMigrate(context: Context) {
        val prefs = context.getSharedPreferences("GraphData", Context.MODE_PRIVATE)
        val historyList = prefs.getStringSet("HISTORY_LIST", null) ?: return
        
        if (historyList.isEmpty()) return

        // If we have history in prefs, migrate it to Room
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getDatabase(context)
            val dao = db.transactionDao()
            
            val entities = mutableListOf<TransactionEntity>()
            
            for (entry in historyList) {
                val parts = entry.split("|")
                if (parts.size < 5) continue

                var category = "no choice"
                var amount = 0f
                var title = "Expense"
                var timestamp = 0L
                var hWeek = 0
                var hDay = 0
                var hMonth = 0
                var hYear = 0

                if (parts.size >= 9) {
                    timestamp = parts[1].toLongOrNull() ?: 0L
                    title = parts[2]
                    category = parts[3]
                    amount = parts[4].toFloatOrNull() ?: 0f
                    hWeek = parts[5].toIntOrNull() ?: 0
                    hDay = parts[6].toIntOrNull() ?: 0
                    hMonth = parts[7].toIntOrNull() ?: 0
                    hYear = parts[8].toIntOrNull() ?: 0
                } else if (parts.size == 7) {
                    title = "Expense"
                    category = parts[1]
                    amount = parts[2].toFloatOrNull() ?: 0f
                    hWeek = parts[3].toIntOrNull() ?: 0
                    hDay = parts[4].toIntOrNull() ?: 0
                    hMonth = parts[5].toIntOrNull() ?: 0
                    hYear = parts[6].toIntOrNull() ?: 0
                    // No timestamp in legacy 7-part, we could estimate it but better to skip or use a default
                } else {
                    timestamp = parts[1].toLongOrNull() ?: 0L
                    title = "Expense"
                    category = parts[3]
                    amount = parts[4].toFloatOrNull() ?: 0f
                }

                entities.add(
                    TransactionEntity(
                        timestamp = timestamp,
                        title = title,
                        category = category,
                        amount = amount.toInt(),
                        week = hWeek,
                        day = hDay,
                        month = hMonth,
                        year = hYear,
                        rawEntry = entry
                    )
                )
            }
            
            if (entities.isNotEmpty()) {
                dao.insertAll(entities)
                // Once migrated, we clear the pref to avoid double migration
                // We keep the HISTORY_LIST key but empty it to mark completion
                prefs.edit().putStringSet("HISTORY_LIST", emptySet()).apply()
            }
        }
    }
}
