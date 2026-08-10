package com.cash.dash

import android.os.Bundle
import android.os.CountDownTimer
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class FinminderHistoryActivity : ThemedActivity() {

    private lateinit var adapter: FinminderHistoryAdapter
    private var finminderId: String = ""
    private lateinit var rvHistoryRef: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_finminder_history)

        finminderId = intent.getStringExtra("FINMINDER_ID") ?: ""
        if (finminderId.isEmpty()) {
            finish()
            return
        }

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        adapter = FinminderHistoryAdapter { completedDateStr ->
            markAsCompleted(completedDateStr)
        }

        val rvHistory = findViewById<RecyclerView>(R.id.rvHistory)
        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = adapter
        rvHistoryRef = rvHistory

        val divider = androidx.recyclerview.widget.DividerItemDecoration(this, androidx.recyclerview.widget.DividerItemDecoration.VERTICAL)
        val drawable = android.graphics.drawable.GradientDrawable()
        drawable.setSize(0, (1 * resources.displayMetrics.density).toInt())
        val isWhite = ThemeHelper.isWhiteTheme(this)
        drawable.setColor(if (isWhite) android.graphics.Color.parseColor("#E0E0E0") else android.graphics.Color.parseColor("#33FFFFFF"))
        divider.setDrawable(drawable)
        rvHistory.addItemDecoration(divider)

        loadData()
    }

    private fun markAsCompleted(dateStr: String) {
        val allItems = FinminderRepository.getItems(this).toMutableList()
        val index = allItems.indexOfFirst { it.id == finminderId }
        if (index == -1) return

        val item = allItems[index]
        if (item.completedDates.contains(dateStr)) return

        // Apply immediately
        val newCompleted = item.completedDates.toMutableList()
        newCompleted.add(dateStr)
        val updatedItem = item.copy(completedDates = newCompleted)
        allItems[index] = updatedItem
        FinminderRepository.saveItem(this, updatedItem)
        FirestoreSyncManager.pushAllDataToCloud(this)
        loadData()

        // Snackbar with undo
        val snackbar = Snackbar.make(rvHistoryRef, "Marked as completed", 5000)
        ThemeHelper.styleSnackbar(this, snackbar)

        var timer: CountDownTimer? = null

        snackbar.setAction("UNDO (5)") {
            timer?.cancel()
            // Revert: remove date from completedDates
            val revertItems = FinminderRepository.getItems(this).toMutableList()
            val revertIndex = revertItems.indexOfFirst { it.id == finminderId }
            if (revertIndex != -1) {
                val revertItem = revertItems[revertIndex]
                val revertCompleted = revertItem.completedDates.toMutableList()
                revertCompleted.remove(dateStr)
                val revertedItem = revertItem.copy(completedDates = revertCompleted)
                FinminderRepository.saveItem(this, revertedItem)
                FirestoreSyncManager.pushAllDataToCloud(this)
                loadData()
            }
        }

        timer = object : CountDownTimer(5000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000) + 1
                snackbar.setAction("UNDO ($sec)") {
                    timer?.cancel()
                    val revertItems = FinminderRepository.getItems(this@FinminderHistoryActivity).toMutableList()
                    val revertIndex = revertItems.indexOfFirst { it.id == finminderId }
                    if (revertIndex != -1) {
                        val revertItem = revertItems[revertIndex]
                        val revertCompleted = revertItem.completedDates.toMutableList()
                        revertCompleted.remove(dateStr)
                        val revertedItem = revertItem.copy(completedDates = revertCompleted)
                        FinminderRepository.saveItem(this@FinminderHistoryActivity, revertedItem)
                        FirestoreSyncManager.pushAllDataToCloud(this@FinminderHistoryActivity)
                        loadData()
                    }
                }
            }
            override fun onFinish() {}
        }
        timer.start()

        snackbar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                timer?.cancel()
            }
        })

        snackbar.show()
    }

    private fun loadData() {
        val allItems = FinminderRepository.getItems(this)
        val item = allItems.find { it.id == finminderId } ?: return

        findViewById<TextView>(R.id.tvTitle).text = item.title
        findViewById<TextView>(R.id.tvFrequency).text = item.frequency
        findViewById<TextView>(R.id.tvType).text = if (item.type == "CASH_OUT") "Cashout" else "Cashin"
        findViewById<TextView>(R.id.tvAmount).text = item.quantity

        val historyItems = generateHistory(item)
        adapter.submitList(historyItems)
    }

    private fun generateHistory(item: FinminderItem): List<HistoryItem> {
        val list = mutableListOf<HistoryItem>()
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val today = Calendar.getInstance()
        today.set(Calendar.HOUR_OF_DAY, 0)
        today.set(Calendar.MINUTE, 0)
        today.set(Calendar.SECOND, 0)
        today.set(Calendar.MILLISECOND, 0)

        val startCal = Calendar.getInstance()
        startCal.timeInMillis = item.timestamp
        startCal.set(Calendar.HOUR_OF_DAY, 0)
        startCal.set(Calendar.MINUTE, 0)
        startCal.set(Calendar.SECOND, 0)
        startCal.set(Calendar.MILLISECOND, 0)

        var sNo = 1

        if (item.frequency == "One time") {
            try {
                val targetDate = sdf.parse(item.dateInfo)
                if (targetDate != null) {
                    val dateCal = Calendar.getInstance().apply { time = targetDate }
                    val status = getStatus(item, item.dateInfo, dateCal, today)
                    list.add(HistoryItem(sNo, item.dateInfo, status))
                }
            } catch (e: Exception) {}
        } else if (item.frequency == "Daily") {
            val currCal = startCal.clone() as Calendar
            // generate until today + 1 upcoming
            while (currCal.timeInMillis <= today.timeInMillis) {
                val dateStr = sdf.format(currCal.time)
                val status = getStatus(item, dateStr, currCal, today)
                list.add(HistoryItem(sNo++, dateStr, status))
                currCal.add(Calendar.DATE, 1)
            }
            // 1 upcoming
            val upcomingStr = sdf.format(currCal.time)
            list.add(HistoryItem(sNo++, upcomingStr, "Upcoming"))
        } else if (item.frequency == "Weekly") {
            val daysMap = mapOf(
                "Sunday" to Calendar.SUNDAY,
                "Monday" to Calendar.MONDAY,
                "Tuesday" to Calendar.TUESDAY,
                "Wednesday" to Calendar.WEDNESDAY,
                "Thursday" to Calendar.THURSDAY,
                "Friday" to Calendar.FRIDAY,
                "Saturday" to Calendar.SATURDAY
            )
            val targetDayOfWeek = daysMap[item.dateInfo]
            if (targetDayOfWeek != null) {
                val currCal = startCal.clone() as Calendar
                // align currCal to the first occurrence
                while (currCal.get(Calendar.DAY_OF_WEEK) != targetDayOfWeek) {
                    currCal.add(Calendar.DATE, 1)
                }
                while (currCal.timeInMillis <= today.timeInMillis) {
                    val dateStr = sdf.format(currCal.time)
                    val status = getStatus(item, dateStr, currCal, today)
                    list.add(HistoryItem(sNo++, dateStr, status))
                    currCal.add(Calendar.DATE, 7)
                }
                // 1 upcoming
                val upcomingStr = sdf.format(currCal.time)
                list.add(HistoryItem(sNo++, upcomingStr, "Upcoming"))
            }
        } else if (item.frequency == "Monthly") {
            try {
                val targetDay = item.dateInfo.toInt()
                val currCal = startCal.clone() as Calendar
                
                // Align to first occurrence
                val maxDaysFirst = currCal.getActualMaximum(Calendar.DAY_OF_MONTH)
                val dayToSetFirst = if (targetDay > maxDaysFirst) maxDaysFirst else targetDay
                
                if (currCal.get(Calendar.DAY_OF_MONTH) > dayToSetFirst) {
                    currCal.add(Calendar.MONTH, 1)
                }
                
                while (true) {
                    val tempCal = currCal.clone() as Calendar
                    val maxDays = tempCal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    val dayToSet = if (targetDay > maxDays) maxDays else targetDay
                    tempCal.set(Calendar.DAY_OF_MONTH, dayToSet)

                    if (tempCal.timeInMillis <= today.timeInMillis) {
                        val dateStr = sdf.format(tempCal.time)
                        val status = getStatus(item, dateStr, tempCal, today)
                        list.add(HistoryItem(sNo++, dateStr, status))
                        currCal.add(Calendar.MONTH, 1)
                    } else {
                        // 1 upcoming
                        val dateStr = sdf.format(tempCal.time)
                        list.add(HistoryItem(sNo++, dateStr, "Upcoming"))
                        break
                    }
                }
            } catch (e: Exception) {}
        }
        
        return list.reversed() // Usually history is latest first
    }

    private fun getStatus(item: FinminderItem, dateStr: String, dateCal: Calendar, todayCal: Calendar): String {
        if (item.completedDates.contains(dateStr)) return "Completed"
        if (dateCal.timeInMillis <= todayCal.timeInMillis) return "Not completed"
        return "Upcoming"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("finminderId", finminderId)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val restored = savedInstanceState.getString("finminderId", "")
        if (!restored.isNullOrEmpty()) {
            finminderId = restored
            loadData()
        }
    }
}
