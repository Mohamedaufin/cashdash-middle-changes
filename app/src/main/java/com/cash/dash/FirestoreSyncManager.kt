package com.cash.dash

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.FirebaseFirestore

import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager

object FirestoreSyncManager {

    const val ACTION_SYNC_UPDATE = "com.cash.dash.SYNC_UPDATE"
    private var listeners = mutableListOf<ListenerRegistration>()
    
    // ⚡ INSTANT SYNC STATE
    private var isSyncingFromCloud = false
    private val prefListeners = mutableMapOf<String, android.content.SharedPreferences.OnSharedPreferenceChangeListener>()
    private var lastSyncJob: Thread? = null
    private val syncDebounceMs = 0L
    private var lastPushTime = 0L

    private const val TAG = "FirestoreSyncManager"

    // High level wrapper to blindly sync everything
    // High level wrapper to blindly sync everything
    fun pushAllDataToCloud(context: Context) {
        val appContext = context.applicationContext
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val email = user.email ?: return 

        Thread {
            try {
                val db = FirebaseFirestore.getInstance()
                Log.d(TAG, "Starting background sync to cloud for $email")

                val walletPrefs = appContext.getSharedPreferences("WalletPrefs", Context.MODE_PRIVATE)
                val categoryPrefs = appContext.getSharedPreferences("CategoryPrefs", Context.MODE_PRIVATE)
                val graphPrefs = appContext.getSharedPreferences("GraphData", Context.MODE_PRIVATE)
                val schedulePrefs = appContext.getSharedPreferences("MoneySchedulePrefs", Context.MODE_PRIVATE)
                val userPrefs = appContext.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                val categoryWeekPrefs = appContext.getSharedPreferences("CategoryWeekData", Context.MODE_PRIVATE)
                val scannerHistPrefs = appContext.getSharedPreferences("ScannerHistory", Context.MODE_PRIVATE)
                val localScanPrefs = appContext.getSharedPreferences("LocalScanPrefs", Context.MODE_PRIVATE)

                val userDocRef = db.collection("users").document(email)
                val configColl = userDocRef.collection("config")

                // 1. App Settings / User Config
                val userConfigData = hashMapOf(
                    "name" to (userPrefs.getString("user_name", "User") ?: "User"),
                    "email" to email,
                    "phone" to (userPrefs.getString("user_phone", "") ?: ""),
                    "setup_complete" to !userPrefs.getBoolean("isFirstLaunch", true),
                    "wallet_popup_shown" to userPrefs.getBoolean("WalletPopupShown", false),
                    "account_creation_time" to userPrefs.getLong("account_creation_time", 0L),
                    "account_status" to "active"
                )
                Tasks.await(configColl.document("profile").set(userConfigData, SetOptions.merge()))
                Tasks.await(userDocRef.set(hashMapOf("email" to email), SetOptions.merge()))

                // 2. Wallet Data
                val initialBalance = walletPrefs.getInt("initial_balance", 0)
                val currentBalance = walletPrefs.getInt("wallet_balance", 0)
                val nextDateRaw = schedulePrefs.getLong("next_date", 0L)
                val formattedDate = if (nextDateRaw > 0) {
                    val sdf = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.US)
                    sdf.format(java.util.Date(nextDateRaw))
                } else "not set"

                val walletData = hashMapOf(
                    "initial_balance" to initialBalance,
                    "current_balance" to currentBalance,
                    "next_date" to formattedDate,
                    "next_date_ms" to nextDateRaw,
                    "frequency" to schedulePrefs.getInt("frequency", 30),
                    "cycle_initialized" to schedulePrefs.getBoolean("cycle_initialized", false)
                )
                Tasks.await(configColl.document("wallet").set(walletData))

                // 3. Category Data (Limits & Spent)
                val categoriesSet = categoryPrefs.getStringSet("categories", emptySet()) ?: emptySet()
                val catMap = mutableMapOf<String, Any>()
                categoriesSet.forEach { catName ->
                    val limit = categoryPrefs.getInt("LIMIT_$catName", 0)
                    val spent = graphPrefs.getFloat("SPENT_$catName", 0f)
                    catMap[catName] = hashMapOf(
                        "limit" to limit,
                        "spent" to spent
                    )
                }
                Tasks.await(configColl.document("categories").set(hashMapOf(
                    "data" to catMap,
                    "allocation_categories" to categoriesSet.toList()
                )))

                // 4. Transaction History
                val historySet = graphPrefs.getStringSet("HISTORY_LIST", emptySet()) ?: emptySet()
                val detailedTransactions = mutableListOf<Map<String, Any>>()

                historySet.forEach { entry ->
                    try {
                        val parts = entry.split("|")
                        if (parts.size >= 9) {
                            val itemMap = HashMap<String, Any>()
                            itemMap["type"] = parts[0]
                            itemMap["timestamp"] = parts[1]
                            itemMap["merchant"] = parts[2]
                            itemMap["category"] = parts[3]
                            itemMap["amount"] = parts[4].toIntOrNull() ?: 0
                            itemMap["week"] = parts[5].toIntOrNull() ?: 0
                            itemMap["day"] = parts[6].toIntOrNull() ?: 0
                            itemMap["month"] = parts[7].toIntOrNull() ?: 0
                            itemMap["year"] = parts[8].toIntOrNull() ?: 0
                            detailedTransactions.add(itemMap)
                        } else if (parts.size >= 5) {
                            val itemMap = HashMap<String, Any>()
                            itemMap["type"] = parts[0]
                            itemMap["timestamp"] = parts[1]
                            itemMap["merchant"] = parts[2]
                            itemMap["category"] = parts[3]
                            itemMap["amount"] = parts[4].toIntOrNull() ?: 0
                            detailedTransactions.add(itemMap)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping malformed history entry: $entry")
                    }
                }

                val historyPayload = HashMap<String, Any>()
                historyPayload["raw_list"] = historySet.toList()
                historyPayload["detailed_transactions"] = detailedTransactions
                Tasks.await(configColl.document("history").set(historyPayload))

                // 5. CategoryWeekData
                Tasks.await(configColl.document("analytics").set(hashMapOf("CategoryWeekData" to categoryWeekPrefs.all)))

                // 6. ScannerHistory
                Tasks.await(configColl.document("history_scanner").set(hashMapOf("ScannerHistory" to scannerHistPrefs.all)))

                // 7. LocalScanPrefs
                Tasks.await(configColl.document("undo_details").set(hashMapOf("LocalScanPrefs" to localScanPrefs.all)))

                Log.d(TAG, "✅ Background sync to cloud complete for user $email")
            } catch (e: Exception) {
                Log.e(TAG, "❌ FATAL SYNC ERROR: ${e.message}", e)
                // If it's a permission error, it will be visible in Logcat now.
            }
        }.start()
    }

    // Pull from Cloud -> Overwrite Local (Parallelized for Performance)
    // Pull from Cloud -> Overwrite Local (Parallelized for Performance)
    fun pullDataFromCloud(context: Context, onComplete: (success: Boolean, profileExists: Boolean, isAdminDeleted: Boolean, profileData: Map<String, Any>?) -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            onComplete(false, false, false, null)
            return
        }

        val email = user.email ?: run { onComplete(false, false, false, null); return }
        val uid = user.uid

        // Internal helper to perform the actual pull from a specific document ID
        fun executePull(docId: String, isFallback: Boolean) {
            val db = FirebaseFirestore.getInstance()
            val userDoc = db.collection("users").document(docId).collection("config")

            // 🚀 Parallelize all 7 document fetches
            val tProfile = userDoc.document("profile").get()
            val tWallet = userDoc.document("wallet").get()
            val tCategories = userDoc.document("categories").get()
            val tHistory = userDoc.document("history").get()
            val tAnalytics = userDoc.document("analytics").get()
            val tScanner = userDoc.document("history_scanner").get()
            val tUndo = userDoc.document("undo_details").get()

            Tasks.whenAllComplete(tProfile, tWallet, tCategories, tHistory, tAnalytics, tScanner, tUndo)
                .addOnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        Log.e(TAG, "Failed to pull cloud data for $docId: ${task.exception?.message}")
                        onComplete(false, false, false, null)
                        return@addOnCompleteListener
                    }

                    val profileDoc = tProfile.result
                    if (profileDoc == null || !profileDoc.exists()) {
                        if (!isFallback) {
                            // 🔄 MIGRATION START: Try pulling from old UID path
                            Log.d(TAG, "Email profile not found for $email, trying legacy UID fallback for $uid")
                            executePull(uid, true)
                        } else {
                            onComplete(true, false, false, null)
                        }
                        return@addOnCompleteListener
                    }

                    val status = profileDoc.getString("account_status") ?: "active"
                    if (status == "admin_deleted") {
                        onComplete(true, true, true, null) // Explicitly banned by admin
                        return@addOnCompleteListener
                    }

                    val profileData = profileDoc.data

                    // 1. Profile
                    val userPrefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                    val editor = userPrefs.edit()

                    // Only clear if we are sure we want a full mirror,
                    // but keep the current email as it is definitely correct from the successful login.
                    val currentEmail = userPrefs.getString("user_email", "")

                    isSyncingFromCloud = true
                    editor.clear().apply()

                    editor.putString("user_email", currentEmail)

                    profileDoc.getString("name")?.let { editor.putString("user_name", it) }
                    profileDoc.getString("phone")?.let { editor.putString("user_phone", it) }
                    profileDoc.getString("email")?.let { editor.putString("user_email", it) }

                    val setupComplete = profileDoc.getBoolean("setup_complete") ?: false
                    editor.putBoolean("isFirstLaunch", !setupComplete)

                    profileDoc.getBoolean("wallet_popup_shown")?.let { editor.putBoolean("WalletPopupShown", it) }
                    profileDoc.getLong("account_creation_time")?.let { editor.putLong("account_creation_time", it) }
                    editor.apply()
                    isSyncingFromCloud = false

                    // 2. Wallet & Schedule
                    val walletDoc = tWallet.result
                    if (walletDoc != null && walletDoc.exists()) {
                        val walletPrefs = context.getSharedPreferences("WalletPrefs", Context.MODE_PRIVATE)
                        val schedulePrefs = context.getSharedPreferences("MoneySchedulePrefs", Context.MODE_PRIVATE)

                        isSyncingFromCloud = true
                        walletPrefs.edit().clear().apply()
                        schedulePrefs.edit().clear().apply()

                        walletPrefs.edit()
                            .putInt("initial_balance", walletDoc.getLong("initial_balance")?.toInt() ?: 0)
                            .putInt("wallet_balance", walletDoc.getLong("current_balance")?.toInt() ?: 0)
                            .apply()
                        schedulePrefs.edit()
                            .putLong("next_date", walletDoc.getLong("next_date_ms") ?: 0L)
                            .putInt("frequency", walletDoc.getLong("frequency")?.toInt() ?: 30)
                            .putBoolean("cycle_initialized", walletDoc.getBoolean("cycle_initialized") ?: false)
                            .apply()
                        isSyncingFromCloud = false
                    }

                    // 3. Categories
                    val catDoc = tCategories.result
                    if (catDoc != null && catDoc.exists()) {
                        val catPrefs = context.getSharedPreferences("CategoryPrefs", Context.MODE_PRIVATE)
                        val graphPrefs = context.getSharedPreferences("GraphData", Context.MODE_PRIVATE)

                        isSyncingFromCloud = true
                        catPrefs.edit().clear().apply()
                        val cEdit = catPrefs.edit()
                        val gEdit = graphPrefs.edit()

                        val dataMap = catDoc.get("data") as? Map<String, Map<String, Any>>
                        if (dataMap != null) {
                            cEdit.putStringSet("categories", dataMap.keys)
                            for ((catName, valuesMap) in dataMap) {
                                val limit = (valuesMap["limit"] as? Number)?.toInt() ?: 0
                                val spent = (valuesMap["spent"] as? Number)?.toFloat() ?: 0f
                                cEdit.putInt("LIMIT_$catName", limit)
                                gEdit.putFloat("SPENT_$catName", spent)
                            }
                        }
                        cEdit.apply()
                        gEdit.apply()
                        isSyncingFromCloud = false
                    }

                    // 4. History (Simplified Restore)
                    val histDoc = tHistory.result
                    if (histDoc != null && histDoc.exists()) {
                        val graphPrefs = context.getSharedPreferences("GraphData", Context.MODE_PRIVATE)
                        val rawList = histDoc.get("raw_list") as? List<String>

                        if (rawList != null) {
                            isSyncingFromCloud = true
                            val gEdit = graphPrefs.edit()
                            val spentValues = mutableMapOf<String, Float>()
                            graphPrefs.all.forEach { (k, v) ->
                                if (k.startsWith("SPENT_") && v is Float) spentValues[k] = v
                            }
                            gEdit.clear().apply()

                            val gRestore = graphPrefs.edit()
                            spentValues.forEach { (k, v) -> gRestore.putFloat(k, v) }

                            val finalTransactions = mutableSetOf<String>()
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

                                    val dayKey = "DAY_${hWeek}_${hDay}_${hMonth}_${hYear}"
                                    val weekKey = "WEEK_${hWeek}_${hMonth}_${hYear}"
                                    val monthKey = "MONTH_${hMonth}_${hYear}"

                                    gRestore.putFloat(dayKey, graphPrefs.getFloat(dayKey, 0f) + amount)
                                    gRestore.putFloat(weekKey, graphPrefs.getFloat(weekKey, 0f) + amount)
                                    gRestore.putFloat(monthKey, graphPrefs.getFloat(monthKey, 0f) + amount)

                                    gRestore.putString("TRANS_${timestamp}_TITLE", p[2])
                                    gRestore.putString("TRANS_${timestamp}_CATEGORY", p[3])
                                    gRestore.putInt("TRANS_${timestamp}_AMOUNT", amount.toInt())
                                    gRestore.putInt("TRANS_${timestamp}_WEEK", hWeek)
                                    gRestore.putInt("TRANS_${timestamp}_DAY", hDay)
                                    gRestore.putInt("TRANS_${timestamp}_MONTH", hMonth)
                                    gRestore.putInt("TRANS_${timestamp}_YEAR", hYear)
                                }
                            }
                            gRestore.putStringSet("HISTORY_LIST", finalTransactions)
                            gRestore.apply()
                            isSyncingFromCloud = false
                        }
                    }

                    // 5. Analytics
                    val analyticsDoc = tAnalytics.result
                    if (analyticsDoc != null && analyticsDoc.exists()) {
                        val cwdMap = analyticsDoc.get("CategoryWeekData") as? Map<String, Any>
                        if (cwdMap != null) {
                            isSyncingFromCloud = true
                            val edit = context.getSharedPreferences("CategoryWeekData", Context.MODE_PRIVATE).edit()
                            edit.clear().apply()
                            for ((k, v) in cwdMap) {
                                if (v is Number) edit.putInt(k, v.toInt())
                            }
                            edit.apply()
                            isSyncingFromCloud = false
                        }
                    }

                    // 6. Scanner History
                    val scannerDoc = tScanner.result
                    if (scannerDoc != null && scannerDoc.exists()) {
                        val scanMap = scannerDoc.get("ScannerHistory") as? Map<String, Any>
                        if (scanMap != null) {
                            isSyncingFromCloud = true
                            val edit = context.getSharedPreferences("ScannerHistory", Context.MODE_PRIVATE).edit()
                            edit.clear().apply()
                            for ((k, v) in scanMap) {
                                if (v is Number) edit.putInt(k, v.toInt())
                                else if (v is String) edit.putString(k, v)
                                else if (v is Boolean) edit.putBoolean(k, v)
                            }
                            edit.apply()
                            isSyncingFromCloud = false
                        }
                    }

                    // 7. Undo Details
                    val undoDoc = tUndo.result
                    if (undoDoc != null && undoDoc.exists()) {
                        val undoMap = undoDoc.get("LocalScanPrefs") as? Map<String, Any>
                        if (undoMap != null) {
                            isSyncingFromCloud = true
                            val edit = context.getSharedPreferences("LocalScanPrefs", Context.MODE_PRIVATE).edit()
                            edit.clear().apply()
                            for ((k, v) in undoMap) {
                                if (v is String) edit.putString(k, v)
                            }
                            edit.apply()
                            isSyncingFromCloud = false
                        }
                    }
                    Log.d(TAG, "Pull complete for $docId. Migration flow: $isFallback")
                    
                    if (isFallback) {
                        // 🔄 MIGRATION FINAL STEP: Push to new Email path immediately
                        Log.d(TAG, "Legacy pull successful, migrating data to $email document...")
                        pushAllDataToCloud(context)
                    }

                    onComplete(true, true, false, profileData)
                }
        }

        executePull(email, false)
    }

    private fun triggerInstantPush(context: Context) {
        if (isSyncingFromCloud) return
        
        val now = System.currentTimeMillis()
        if (now - lastPushTime < syncDebounceMs) return
        lastPushTime = now

        Log.d(TAG, "⚡ Instant Sync Triggered")
        pushAllDataToCloud(context)
    }

    fun startRealTimeSync(context: Context) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val email = user.email ?: return 
        if (listeners.isNotEmpty()) return // Already listening

        Log.d(TAG, "📡 Starting Real-Time Sync for: $email")

        val db = FirebaseFirestore.getInstance()
        val userDoc = db.collection("users").document(email).collection("config")
        val appContext = context.applicationContext

        val prefsToWatch = listOf(
            "AppPrefs", "WalletPrefs", "CategoryPrefs", "GraphData",
            "CategoryWeekData", "MoneySchedulePrefs", "ScannerHistory", "LocalScanPrefs"
        )

        prefsToWatch.forEach { name ->
            val p = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                triggerInstantPush(appContext)
            }
            p.registerOnSharedPreferenceChangeListener(listener)
            prefListeners[name] = listener
        }

        // 1. Profile Listener
        listeners.add(userDoc.document("profile").addSnapshotListener { snapshot, e ->
            if (e != null) { Log.e(TAG, "❌ Profile Sync Error: ${e.message}"); return@addSnapshotListener }
            if (snapshot == null || !snapshot.exists()) return@addSnapshotListener

            isSyncingFromCloud = true
            val prefs = appContext.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            snapshot.getString("name")?.let { editor.putString("user_name", it) }
            snapshot.getString("phone")?.let { editor.putString("user_phone", it) }
            snapshot.getString("email")?.let { editor.putString("user_email", it) }
            snapshot.getBoolean("setup_complete")?.let { editor.putBoolean("isFirstLaunch", !it) }
            editor.apply()
            isSyncingFromCloud = false
            notifyUI(appContext)
        })

        // 2. Wallet & Schedule Listener
        listeners.add(userDoc.document("wallet").addSnapshotListener { snapshot, e ->
            if (e != null) { Log.e(TAG, "❌ Wallet Sync Error: ${e.message}"); return@addSnapshotListener }
            if (snapshot == null || !snapshot.exists()) return@addSnapshotListener

            isSyncingFromCloud = true
            val walletPrefs = appContext.getSharedPreferences("WalletPrefs", Context.MODE_PRIVATE)
            val schedulePrefs = appContext.getSharedPreferences("MoneySchedulePrefs", Context.MODE_PRIVATE)

            walletPrefs.edit()
                .putInt("initial_balance", snapshot.getLong("initial_balance")?.toInt() ?: 0)
                .putInt("wallet_balance", snapshot.getLong("current_balance")?.toInt() ?: 0)
                .apply()

            schedulePrefs.edit()
                .putLong("next_date", snapshot.getLong("next_date_ms") ?: 0L)
                .putInt("frequency", snapshot.getLong("frequency")?.toInt() ?: 30)
                .putBoolean("cycle_initialized", snapshot.getBoolean("cycle_initialized") ?: false)
                .apply()
            isSyncingFromCloud = false
            notifyUI(appContext)
        })

        // 3. Categories Listener
        listeners.add(userDoc.document("categories").addSnapshotListener { snapshot, e ->
            if (e != null) { Log.e(TAG, "❌ Categories Sync Error: ${e.message}"); return@addSnapshotListener }
            if (snapshot == null || !snapshot.exists()) return@addSnapshotListener

            isSyncingFromCloud = true
            val catPrefs = appContext.getSharedPreferences("CategoryPrefs", Context.MODE_PRIVATE)
            val graphPrefs = appContext.getSharedPreferences("GraphData", Context.MODE_PRIVATE)
            val cEdit = catPrefs.edit()
            val gEdit = graphPrefs.edit()

            val dataMap = snapshot.get("data") as? Map<String, Map<String, Any>>
            if (dataMap != null) {
                cEdit.putStringSet("categories", dataMap.keys)
                for ((catName, valuesMap) in dataMap) {
                    cEdit.putInt("LIMIT_$catName", (valuesMap["limit"] as? Number)?.toInt() ?: 0)
                    gEdit.putFloat("SPENT_$catName", (valuesMap["spent"] as? Number)?.toFloat() ?: 0f)
                }
            }
            cEdit.apply()
            gEdit.apply()
            isSyncingFromCloud = false
            notifyUI(appContext)
        })

        // 4. History Listener
        listeners.add(userDoc.document("history").addSnapshotListener { snapshot, e ->
            if (e != null) { Log.e(TAG, "❌ History Sync Error: ${e.message}"); return@addSnapshotListener }
            if (snapshot == null || !snapshot.exists()) return@addSnapshotListener

            isSyncingFromCloud = true
            val graphPrefs = appContext.getSharedPreferences("GraphData", Context.MODE_PRIVATE)
            val detailed = snapshot.get("detailed_transactions") as? List<Map<String, Any>>
            val rawList = snapshot.get("raw_list") as? List<String>

            if (detailed == null && rawList == null) { isSyncingFromCloud = false; return@addSnapshotListener }

            val gEdit = graphPrefs.edit()
            val spentValues = mutableMapOf<String, Float>()
            graphPrefs.all.forEach { (k, v) -> if (k.startsWith("SPENT_") && v is Float) spentValues[k] = v }
            gEdit.clear().apply()

            val gRestore = graphPrefs.edit()
            spentValues.forEach { (k, v) -> gRestore.putFloat(k, v) }

            val finalTransactions = mutableSetOf<String>()

            if (detailed != null) {
                for (map in detailed) {
                    val pType = map["type"] as? String ?: "EXP"
                    val pTs = map["timestamp"]?.toString() ?: "0"
                    val pMerchant = map["merchant"] as? String ?: "Unknown"
                    val pCat = map["category"] as? String ?: "no choice"
                    val pAmt = (map["amount"] as? Number)?.toFloat() ?: 0f
                    var hWeek = (map["week"] as? Number)?.toInt()
                    var hDay = (map["day"] as? Number)?.toInt()
                    var hMonth = (map["month"] as? Number)?.toInt()
                    var hYear = (map["year"] as? Number)?.toInt()

                    if (hWeek == null || hDay == null || hMonth == null || hYear == null) {
                        val cal = java.util.Calendar.getInstance()
                        try {
                            cal.setFirstDayOfWeek(java.util.Calendar.MONDAY)
                            cal.setMinimalDaysInFirstWeek(1)
                            cal.timeInMillis = pTs.toLong()
                            hWeek = cal.get(java.util.Calendar.WEEK_OF_MONTH) - 1
                            hDay = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
                            hMonth = cal.get(java.util.Calendar.MONTH)
                            hYear = cal.get(java.util.Calendar.YEAR)
                        } catch (ex: Exception) { hWeek = 0; hDay = 0; hMonth = 0; hYear = 0 }
                    }

                    val entryStr = "$pType|$pTs|$pMerchant|$pCat|${pAmt.toInt()}|$hWeek|$hDay|$hMonth|$hYear"
                    finalTransactions.add(entryStr)

                    val dKey = "DAY_${hWeek}_${hDay}_${hMonth}_${hYear}"
                    val wKey = "WEEK_${hWeek}_${hMonth}_${hYear}"
                    val mKey = "MONTH_${hMonth}_${hYear}"
                    gRestore.putFloat(dKey, graphPrefs.getFloat(dKey, 0f) + pAmt)
                    gRestore.putFloat(wKey, graphPrefs.getFloat(wKey, 0f) + pAmt)
                    gRestore.putFloat(mKey, graphPrefs.getFloat(mKey, 0f) + pAmt)

                    gRestore.putString("TRANS_${pTs}_TITLE", pMerchant)
                    gRestore.putString("TRANS_${pTs}_CATEGORY", pCat)
                    gRestore.putInt("TRANS_${pTs}_AMOUNT", pAmt.toInt())
                    gRestore.putInt("TRANS_${pTs}_WEEK", hWeek)
                    gRestore.putInt("TRANS_${pTs}_DAY", hDay)
                    gRestore.putInt("TRANS_${pTs}_MONTH", hMonth)
                    gRestore.putInt("TRANS_${pTs}_YEAR", hYear)
                }
            } else if (rawList != null) {
                finalTransactions.addAll(rawList)
                for (entry in rawList) {
                    val p = entry.split("|")
                    if (p.size >= 9) {
                        val amount = p[4].toFloatOrNull() ?: 0f
                        val hWeek = p[5].toIntOrNull() ?: 0
                        val hDay = p[6].toIntOrNull() ?: 0
                        val hMonth = p[7].toIntOrNull() ?: 0
                        val hYear = p[8].toIntOrNull() ?: 0

                        val dKey = "DAY_${hWeek}_${hDay}_${hMonth}_${hYear}"
                        val wKey = "WEEK_${hWeek}_${hMonth}_${hYear}"
                        val mKey = "MONTH_${hMonth}_${hYear}"
                        gRestore.putFloat(dKey, graphPrefs.getFloat(dKey, 0f) + amount)
                        gRestore.putFloat(wKey, graphPrefs.getFloat(wKey, 0f) + amount)
                        gRestore.putFloat(mKey, graphPrefs.getFloat(mKey, 0f) + amount)

                        val timestamp = p[1]
                        gRestore.putString("TRANS_${timestamp}_TITLE", p[2])
                        gRestore.putString("TRANS_${timestamp}_CATEGORY", p[3])
                        gRestore.putInt("TRANS_${timestamp}_AMOUNT", amount.toInt())
                        gRestore.putInt("TRANS_${timestamp}_WEEK", hWeek)
                        gRestore.putInt("TRANS_${timestamp}_DAY", hDay)
                        gRestore.putInt("TRANS_${timestamp}_MONTH", hMonth)
                        gRestore.putInt("TRANS_${timestamp}_YEAR", hYear)
                    }
                }
            }
            gRestore.putStringSet("HISTORY_LIST", finalTransactions)
            gRestore.apply()
            isSyncingFromCloud = false
            notifyUI(appContext)
        })

        // 5. Analytics Listener
        listeners.add(userDoc.document("analytics").addSnapshotListener { snapshot, e ->
            if (e != null) { Log.e(TAG, "❌ Analytics Sync Error: ${e.message}"); return@addSnapshotListener }
            if (snapshot == null || !snapshot.exists()) return@addSnapshotListener
            xmlMapToPrefs(appContext, "CategoryWeekData", snapshot.get("CategoryWeekData") as? Map<String, Any>)
            notifyUI(appContext)
        })

        // 6. Scanner & Undo Listeners
        listeners.add(userDoc.document("history_scanner").addSnapshotListener { snapshot, e ->
            if (e != null) { Log.e(TAG, "❌ Scanner Sync Error: ${e.message}"); return@addSnapshotListener }
            if (snapshot == null || !snapshot.exists()) return@addSnapshotListener
            xmlMapToPrefs(appContext, "ScannerHistory", snapshot.get("ScannerHistory") as? Map<String, Any>)
            notifyUI(appContext)
        })
        listeners.add(userDoc.document("undo_details").addSnapshotListener { snapshot, e ->
            if (e != null) { Log.e(TAG, "❌ Undo Sync Error: ${e.message}"); return@addSnapshotListener }
            if (snapshot == null || !snapshot.exists()) return@addSnapshotListener
            xmlMapToPrefs(appContext, "LocalScanPrefs", snapshot.get("LocalScanPrefs") as? Map<String, Any>)
            notifyUI(appContext)
        })

        // 7. Notifications Global Cache Listener (Keeps it instantly available for NotificationActivity)
        listeners.add(db.collection("users").document(email).collection("notifications")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { _, _ -> /* Keeps offline cache warm silently */ }
        )
    }

    private fun xmlMapToPrefs(context: Context, prefName: String, map: Map<String, Any>?) {
        if (map == null) return
        isSyncingFromCloud = true
        val edit = context.getSharedPreferences(prefName, Context.MODE_PRIVATE).edit()
        edit.clear().apply()
        for ((k, v) in map) {
            when (v) {
                is Number -> edit.putInt(k, v.toInt())
                is String -> edit.putString(k, v)
                is Boolean -> edit.putBoolean(k, v)
            }
        }
        edit.apply()
        isSyncingFromCloud = false
    }

    private fun notifyUI(context: Context) {
        LocalBroadcastManager.getInstance(context).sendBroadcast(Intent(ACTION_SYNC_UPDATE))
    }

    fun stopRealTimeSync(context: Context) {
        listeners.forEach { it.remove() }
        listeners.clear()
        
        // Unregister Pref Listeners
        prefListeners.forEach { (name, listener) ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(listener)
        }
        prefListeners.clear()
    }
}
