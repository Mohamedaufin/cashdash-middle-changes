import re

with open('app/src/main/java/com/cash/dash/FirestoreSyncManager.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# Fix 1: pushAllDataToCloud reads from Room instead of HISTORY_LIST
content = content.replace(
    '''// 4. Transaction History
                val historySet = graphPrefs.getStringSet("HISTORY_LIST", emptySet()) ?: emptySet()''',
    '''// 4. Transaction History
                val dao = AppDatabase.getDatabase(appContext).transactionDao()
                val historySet = dao.getTransactionsInRange(0, Long.MAX_VALUE).map { it.rawEntry }.toSet()'''
)

# Fix 2: pullDataFromCloud sum accumulation
old_pull = '''                            val finalTransactions = mutableSetOf<String>()
                            finalTransactions.addAll(rawList)

                            for (entry in rawList) {
                                val p = entry.split("|")
                                if (p.size >= 9) {
                                    val amount = p[4].toFloatOrNull() ?: 0f
                                    val timestamp = p[1]
                                    val hWeek = p[5].toIntOrNull() ?: 0
                                    val hDay = p[6].toIntOrNull() ?: 0
                                    val hMonth = p[7].toIntOrNull() ?: 0
                                    val hYear = p[8].toIntOrNull() ?: 0

                                    val dayKey = "DAY_\_\_\_\"
                                    val weekKey = "WEEK_\_\_\"
                                    val monthKey = "MONTH_\_\"

                                    gRestore.putFloat(dayKey, graphPrefs.getFloat(dayKey, 0f) + amount)
                                    gRestore.putFloat(weekKey, graphPrefs.getFloat(weekKey, 0f) + amount)
                                    gRestore.putFloat(monthKey, graphPrefs.getFloat(monthKey, 0f) + amount)'''

new_pull = '''                            val finalTransactions = mutableSetOf<String>()
                            finalTransactions.addAll(rawList)
                            val dSums = mutableMapOf<String, Float>()
                            val wSums = mutableMapOf<String, Float>()
                            val mSums = mutableMapOf<String, Float>()

                            for (entry in rawList) {
                                val p = entry.split("|")
                                if (p.size >= 9) {
                                    val amount = p[4].toFloatOrNull() ?: 0f
                                    val timestamp = p[1]
                                    val hWeek = p[5].toIntOrNull() ?: 0
                                    val hDay = p[6].toIntOrNull() ?: 0
                                    val hMonth = p[7].toIntOrNull() ?: 0
                                    val hYear = p[8].toIntOrNull() ?: 0

                                    val dayKey = "DAY_\_\_\_\"
                                    val weekKey = "WEEK_\_\_\"
                                    val monthKey = "MONTH_\_\"

                                    dSums[dayKey] = (dSums[dayKey] ?: 0f) + amount
                                    wSums[weekKey] = (wSums[weekKey] ?: 0f) + amount
                                    mSums[monthKey] = (mSums[monthKey] ?: 0f) + amount'''

content = content.replace(old_pull, new_pull)

old_pull_end = '''                                    gRestore.putInt("TRANS_\_YEAR", hYear)
                                }
                            }
                            gRestore.putStringSet("HISTORY_LIST", finalTransactions)'''

new_pull_end = '''                                    gRestore.putInt("TRANS_\_YEAR", hYear)
                                }
                            }
                            dSums.forEach { (k, v) -> gRestore.putFloat(k, v) }
                            wSums.forEach { (k, v) -> gRestore.putFloat(k, v) }
                            mSums.forEach { (k, v) -> gRestore.putFloat(k, v) }
                            gRestore.putStringSet("HISTORY_LIST", finalTransactions)'''

content = content.replace(old_pull_end, new_pull_end)

# Fix 3: startRealTimeSync listener detailed
old_sync_det_start = '''            if (detailed != null) {
                for (map in detailed) {'''

new_sync_det_start = '''            if (detailed != null) {
                val dSums = mutableMapOf<String, Float>()
                val wSums = mutableMapOf<String, Float>()
                val mSums = mutableMapOf<String, Float>()
                for (map in detailed) {'''

content = content.replace(old_sync_det_start, new_sync_det_start)

old_sync_det = '''                    val dKey = "DAY_\_\_\_\"
                    val wKey = "WEEK_\_\_\"
                    val mKey = "MONTH_\_\"
                    gRestore.putFloat(dKey, graphPrefs.getFloat(dKey, 0f) + pAmt)
                    gRestore.putFloat(wKey, graphPrefs.getFloat(wKey, 0f) + pAmt)
                    gRestore.putFloat(mKey, graphPrefs.getFloat(mKey, 0f) + pAmt)'''

new_sync_det = '''                    val dKey = "DAY_\_\_\_\"
                    val wKey = "WEEK_\_\_\"
                    val mKey = "MONTH_\_\"
                    dSums[dKey] = (dSums[dKey] ?: 0f) + pAmt
                    wSums[wKey] = (wSums[wKey] ?: 0f) + pAmt
                    mSums[mKey] = (mSums[mKey] ?: 0f) + pAmt'''

content = content.replace(old_sync_det, new_sync_det)

old_sync_det_end = '''                    gRestore.putInt("TRANS_\_YEAR", hYear)
                }
            } else if (rawList != null) {'''

new_sync_det_end = '''                    gRestore.putInt("TRANS_\_YEAR", hYear)
                }
                dSums.forEach { (k, v) -> gRestore.putFloat(k, v) }
                wSums.forEach { (k, v) -> gRestore.putFloat(k, v) }
                mSums.forEach { (k, v) -> gRestore.putFloat(k, v) }
            } else if (rawList != null) {'''

content = content.replace(old_sync_det_end, new_sync_det_end)

# Fix 4: startRealTimeSync listener rawList
old_sync_raw_start = '''            } else if (rawList != null) {
                finalTransactions.addAll(rawList)
                for (entry in rawList) {'''

new_sync_raw_start = '''            } else if (rawList != null) {
                val dSums = mutableMapOf<String, Float>()
                val wSums = mutableMapOf<String, Float>()
                val mSums = mutableMapOf<String, Float>()
                finalTransactions.addAll(rawList)
                for (entry in rawList) {'''

content = content.replace(old_sync_raw_start, new_sync_raw_start)

old_sync_raw_mid = '''                        val dKey = "DAY_\_\_\_\"
                        val wKey = "WEEK_\_\_\"
                        val mKey = "MONTH_\_\"
                        gRestore.putFloat(dKey, graphPrefs.getFloat(dKey, 0f) + amount)
                        gRestore.putFloat(wKey, graphPrefs.getFloat(wKey, 0f) + amount)
                        gRestore.putFloat(mKey, graphPrefs.getFloat(mKey, 0f) + amount)'''

new_sync_raw_mid = '''                        val dKey = "DAY_\_\_\_\"
                        val wKey = "WEEK_\_\_\"
                        val mKey = "MONTH_\_\"
                        dSums[dKey] = (dSums[dKey] ?: 0f) + amount
                        wSums[wKey] = (wSums[wKey] ?: 0f) + amount
                        mSums[mKey] = (mSums[mKey] ?: 0f) + amount'''

content = content.replace(old_sync_raw_mid, new_sync_raw_mid)

old_sync_raw_end = '''                        gRestore.putInt("TRANS_\_YEAR", hYear)
                    }
                }
            }
            gRestore.putStringSet("HISTORY_LIST", finalTransactions)'''

new_sync_raw_end = '''                        gRestore.putInt("TRANS_\_YEAR", hYear)
                    }
                }
                dSums.forEach { (k, v) -> gRestore.putFloat(k, v) }
                wSums.forEach { (k, v) -> gRestore.putFloat(k, v) }
                mSums.forEach { (k, v) -> gRestore.putFloat(k, v) }
            }
            gRestore.putStringSet("HISTORY_LIST", finalTransactions)'''

content = content.replace(old_sync_raw_end, new_sync_raw_end)

with open('app/src/main/java/com/cash/dash/FirestoreSyncManager.kt', 'w', encoding='utf-8') as f:
    f.write(content)

print("Modifications done!")
