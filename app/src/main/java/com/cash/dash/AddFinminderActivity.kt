package com.cash.dash

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CalendarView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

class AddFinminderActivity : ThemedActivity() {

    private lateinit var etTitle: EditText
    private lateinit var etQuantity: EditText
    private lateinit var layoutFrequencyExpandable: LinearLayout
    private lateinit var btnFrequencyToggle: View
    private lateinit var tvSelectedFrequency: TextView
    private lateinit var layoutFrequencyOptions: LinearLayout
    private lateinit var btnFreqOneTime: TextView
    private lateinit var btnFreqDaily: TextView
    private lateinit var btnFreqWeekly: TextView
    private lateinit var btnFreqMonthly: TextView
    
    private var selectedFrequencyIndex = -1
    private lateinit var scrollView: ScrollView
    private lateinit var layoutOneTime: LinearLayout
    private lateinit var calendarView: CalendarView
    private lateinit var tvAddTitle: TextView
    private lateinit var tvNotificationHint: TextView
    private lateinit var tvNotificationHintWeekly: TextView
    private lateinit var tvNotificationHintMonthly: TextView
    private lateinit var layoutWeekly: LinearLayout
    private lateinit var btnDayToggle: View
    private lateinit var tvSelectedDay: TextView
    private lateinit var layoutDayOptions: LinearLayout
    private var selectedDayStr = "Monday"
    private lateinit var layoutMonthly: LinearLayout
    private lateinit var etDateOfMonth: EditText
    private lateinit var btnSave: Button

    private var currentTab = "CASH_OUT"
    private var selectedDateStr = ""
    private var editModeItemId: String? = null
    private var editModeTimestamp: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_finminder)

        currentTab = intent.getStringExtra("TAB") ?: "CASH_OUT"

        val btnBack = findViewById<View>(R.id.btnBack)
        tvAddTitle = findViewById(R.id.tvAddTitle)
        
        tvAddTitle.text = if (currentTab == "CASH_OUT") "Finminder Cash Out" else "Finminder Cash In"
        
        val tvFinminderHint = findViewById<TextView>(R.id.tvFinminderHint)
        if (currentTab == "CASH_OUT") {
            tvFinminderHint.text = "Cash-out is an expense where you set a future date to send money, and CashDash reminds you on that date."
        } else {
            tvFinminderHint.text = "Cash-in is an income where you expect to receive money from someone on a future date, and CashDash reminds you on that date."
        }
        
        btnBack.setOnClickListener { finish() }

        etTitle = findViewById(R.id.etTitle)
        etQuantity = findViewById(R.id.etQuantity)
        layoutFrequencyExpandable = findViewById(R.id.layoutFrequencyExpandable)
        btnFrequencyToggle = findViewById(R.id.btnFrequencyToggle)
        tvSelectedFrequency = findViewById(R.id.tvSelectedFrequency)
        layoutFrequencyOptions = findViewById(R.id.layoutFrequencyOptions)
        btnFreqOneTime = findViewById(R.id.btnFreqOneTime)
        btnFreqDaily = findViewById(R.id.btnFreqDaily)
        btnFreqWeekly = findViewById(R.id.btnFreqWeekly)
        btnFreqMonthly = findViewById(R.id.btnFreqMonthly)
        scrollView = findViewById(R.id.scrollView)
        layoutOneTime = findViewById(R.id.layoutOneTime)
        calendarView = findViewById(R.id.calendarView)
        val today = Calendar.getInstance()
        calendarView.minDate = today.timeInMillis
        
        tvNotificationHint = findViewById(R.id.tvNotificationHint)
        tvNotificationHintWeekly = findViewById(R.id.tvNotificationHintWeekly)
        tvNotificationHintMonthly = findViewById(R.id.tvNotificationHintMonthly)
        layoutWeekly = findViewById(R.id.layoutWeekly)
        btnDayToggle = findViewById(R.id.btnDayToggle)
        tvSelectedDay = findViewById(R.id.tvSelectedDay)
        layoutDayOptions = findViewById(R.id.layoutDayOptions)
        layoutMonthly = findViewById(R.id.layoutMonthly)
        etDateOfMonth = findViewById(R.id.etDateOfMonth)
        btnSave = findViewById(R.id.btnSave)

        setupListeners()
        validateForm()
    }

    private fun setupListeners() {
        etDateOfMonth.filters = arrayOf(android.text.InputFilter { source, _, _, dest, dstart, dend ->
            try {
                val input = (dest.subSequence(0, dstart).toString() + source + dest.subSequence(dend, dest.length)).toInt()
                if (input in 1..31) null else ""
            } catch (nfe: NumberFormatException) { "" }
        })

        fun updateHintText(shouldScroll: Boolean = false) {
            val title = etTitle.text.toString().trim().ifEmpty { "your expense" }
            if (selectedFrequencyIndex == 0) {
                val dateStrFull = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(
                    SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).parse(selectedDateStr) ?: Date()
                )
                tvNotificationHint.text = "You will be notified about $title on $dateStrFull."
                tvNotificationHint.visibility = View.VISIBLE
                tvNotificationHintWeekly.visibility = View.GONE
                tvNotificationHintMonthly.visibility = View.GONE
                if (shouldScroll) {
                    scrollView.post { scrollView.smoothScrollTo(0, scrollView.bottom) }
                }
            } else if (selectedFrequencyIndex == 1) { // Remind daily
                tvNotificationHint.text = "You will be notified about $title daily."
                tvNotificationHint.visibility = View.VISIBLE
                tvNotificationHintWeekly.visibility = View.GONE
                tvNotificationHintMonthly.visibility = View.GONE
                if (shouldScroll) {
                    scrollView.post { scrollView.smoothScrollTo(0, scrollView.bottom) }
                }
            } else if (selectedFrequencyIndex == 2) { // Remind weekly
                tvNotificationHint.visibility = View.GONE
                tvNotificationHintMonthly.visibility = View.GONE
                if (selectedDayStr.isNotEmpty()) {
                    val daysOfWeek = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
                    val targetDayIndex = daysOfWeek.indexOf(selectedDayStr) + 1
                    val dates = mutableListOf<String>()
                    val cal = Calendar.getInstance()
                    for (i in 0 until 3) {
                        while (cal.get(Calendar.DAY_OF_WEEK) != targetDayIndex) {
                            cal.add(Calendar.DATE, 1)
                        }
                        dates.add(SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(cal.time))
                        cal.add(Calendar.DATE, 1)
                    }
                    tvNotificationHintWeekly.text = "You will be notified about $title on every $selectedDayStr.\n\nExamples: ${dates.joinToString(", ")}, etc."
                    tvNotificationHintWeekly.visibility = View.VISIBLE
                    if (shouldScroll) {
                        scrollView.post { scrollView.smoothScrollTo(0, scrollView.bottom) }
                    }
                }
            } else if (selectedFrequencyIndex == 3) { // Remind monthly
                tvNotificationHint.visibility = View.GONE
                tvNotificationHintWeekly.visibility = View.GONE
                val dayOfMonthStr = etDateOfMonth.text.toString().trim()
                if (dayOfMonthStr.isNotEmpty()) {
                    val dayOfMonth = dayOfMonthStr.toIntOrNull() ?: 1
                    val dates = mutableListOf<String>()
                    val cal = Calendar.getInstance()
                    if (cal.get(Calendar.DAY_OF_MONTH) >= dayOfMonth) {
                        cal.add(Calendar.MONTH, 1)
                    }
                    for (i in 0 until 3) {
                        val monthCal = cal.clone() as Calendar
                        val maxDay = monthCal.getActualMaximum(Calendar.DAY_OF_MONTH)
                        monthCal.set(Calendar.DAY_OF_MONTH, kotlin.math.min(dayOfMonth, maxDay))
                        dates.add(SimpleDateFormat("MMMM dd", Locale.getDefault()).format(monthCal.time))
                        cal.add(Calendar.MONTH, 1)
                    }
                    tvNotificationHintMonthly.text = "You will be notified about $title on every month's $dayOfMonth.\n\nExamples: ${dates.joinToString(", ")}, etc."
                    tvNotificationHintMonthly.visibility = View.VISIBLE
                    if (shouldScroll) {
                        scrollView.post { scrollView.smoothScrollTo(0, scrollView.bottom) }
                    }
                } else {
                    tvNotificationHintMonthly.visibility = View.GONE
                }
            }
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { 
                validateForm() 
                updateHintText()
            }
        }
        etTitle.addTextChangedListener(watcher)
        etQuantity.addTextChangedListener(watcher)
        etDateOfMonth.addTextChangedListener(watcher)
        
        val rootContainer = findViewById<android.view.ViewGroup>(R.id.scrollView).getChildAt(0) as android.view.ViewGroup

        val btnDays = mapOf(
            "Monday" to findViewById<TextView>(R.id.btnDayMonday),
            "Tuesday" to findViewById<TextView>(R.id.btnDayTuesday),
            "Wednesday" to findViewById<TextView>(R.id.btnDayWednesday),
            "Thursday" to findViewById<TextView>(R.id.btnDayThursday),
            "Friday" to findViewById<TextView>(R.id.btnDayFriday),
            "Saturday" to findViewById<TextView>(R.id.btnDaySaturday),
            "Sunday" to findViewById<TextView>(R.id.btnDaySunday)
        )

        val updateDayTicks = { selectedDay: String ->
            val tick = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_check_green)
            for ((dayStr, btn) in btnDays) {
                btn.setCompoundDrawablesWithIntrinsicBounds(null, null, if (dayStr == selectedDay) tick else null, null)
            }
        }
        
        btnDayToggle.setOnClickListener {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(btnDayToggle.windowToken, 0)
            if (layoutDayOptions.visibility != View.VISIBLE) {
                updateDayTicks(selectedDayStr)
            }
            layoutDayOptions.visibility = if (layoutDayOptions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        
        val selectDay = { text: String ->
            layoutDayOptions.visibility = View.GONE
            if (text != selectedDayStr) {
                selectedDayStr = text
                tvSelectedDay.text = text
                updateHintText(true)
                validateForm()
            }
        }
        
        btnDays["Monday"]?.setOnClickListener { selectDay("Monday") }
        btnDays["Tuesday"]?.setOnClickListener { selectDay("Tuesday") }
        btnDays["Wednesday"]?.setOnClickListener { selectDay("Wednesday") }
        btnDays["Thursday"]?.setOnClickListener { selectDay("Thursday") }
        btnDays["Friday"]?.setOnClickListener { selectDay("Friday") }
        btnDays["Saturday"]?.setOnClickListener { selectDay("Saturday") }
        btnDays["Sunday"]?.setOnClickListener { selectDay("Sunday") }

        val updateFrequencyTicks = { selectedIndex: Int ->
            val tick = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_check_green)
            btnFreqOneTime.setCompoundDrawablesWithIntrinsicBounds(null, null, if (selectedIndex == 0) tick else null, null)
            btnFreqDaily.setCompoundDrawablesWithIntrinsicBounds(null, null, if (selectedIndex == 1) tick else null, null)
            btnFreqWeekly.setCompoundDrawablesWithIntrinsicBounds(null, null, if (selectedIndex == 2) tick else null, null)
            btnFreqMonthly.setCompoundDrawablesWithIntrinsicBounds(null, null, if (selectedIndex == 3) tick else null, null)
        }

        btnFrequencyToggle.setOnClickListener {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(btnFrequencyToggle.windowToken, 0)
            if (layoutFrequencyOptions.visibility != View.VISIBLE) {
                updateFrequencyTicks(selectedFrequencyIndex)
            }
            layoutFrequencyOptions.visibility = if (layoutFrequencyOptions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        val selectFrequency = { index: Int, text: String ->
            layoutFrequencyOptions.visibility = View.GONE
            if (index == selectedFrequencyIndex) {
                // Do nothing if selecting the same frequency (avoids layout reloading/glitch)
                Unit
            } else {
                selectedFrequencyIndex = index
                tvSelectedFrequency.text = text
                
                // Hide all sections first to avoid simultaneous layout causing flicker
                layoutOneTime.visibility = View.GONE
                layoutWeekly.visibility = View.GONE
                layoutMonthly.visibility = View.GONE
                
                // Then show the selected one
                when (index) {
                    0 -> layoutOneTime.visibility = View.VISIBLE
                    1 -> { /* Daily: no layout needed */ }
                    2 -> layoutWeekly.visibility = View.VISIBLE
                    3 -> layoutMonthly.visibility = View.VISIBLE
                }
                validateForm()
                updateHintText(true)
            }
        }

        btnFreqOneTime.setOnClickListener { selectFrequency(0, "One time only") }
        btnFreqDaily.setOnClickListener { selectFrequency(1, "Remind daily") }
        btnFreqWeekly.setOnClickListener { selectFrequency(2, "Remind every week") }
        btnFreqMonthly.setOnClickListener { selectFrequency(3, "Remind every month") }

        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        selectedDateStr = sdf.format(calendarView.date)
        calendarView.setOnDateChangeListener { _, year, month, dayOfMonth ->
            val cal = Calendar.getInstance()
            cal.set(year, month, dayOfMonth)
            selectedDateStr = sdf.format(cal.time)
            updateHintText(true)
            validateForm()
        }

        editModeItemId = intent.getStringExtra("ITEM_ID")
        editModeItemId?.let { id ->
            val item = FinminderRepository.getItems(this).find { it.id == id }
            if (item != null) {
                editModeTimestamp = item.timestamp
                etTitle.setText(item.title)
                etQuantity.setText(item.quantity)
                
                when (item.frequency) {
                    "One time" -> {
                        selectFrequency(0, "One time only")
                        try {
                            val sdf = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
                            val date = sdf.parse(item.dateInfo)
                            if (date != null) calendarView.date = date.time
                        } catch(e: Exception){}
                    }
                    "Daily" -> {
                        selectFrequency(1, "Remind daily")
                    }
                    "Weekly" -> {
                        selectFrequency(2, "Remind every week")
                        selectDay(item.dateInfo)
                    }
                    "Monthly" -> {
                        selectFrequency(3, "Remind every month")
                        etDateOfMonth.setText(item.dateInfo)
                    }
                }
                tvAddTitle.text = if (currentTab == "CASH_OUT") "Edit cash out" else "Edit cash in"
            }
        }

        btnSave.setOnClickListener {
            if (btnSave.alpha < 1.0f) {
                Toast.makeText(this, "Please submit all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveFinminder()
        }
    }

    private fun validateForm() {
        var isValid = true
        if (etTitle.text.toString().trim().isEmpty()) isValid = false
        if (etQuantity.text.toString().trim().isEmpty()) isValid = false
        
        val freq = selectedFrequencyIndex
        if (freq == -1) isValid = false
        
        if (freq == 3) { // Monthly
            val dayStr = etDateOfMonth.text.toString().trim()
            if (dayStr.isEmpty()) {
                isValid = false
                etDateOfMonth.error = null
            } else {
                val dayNum = dayStr.toIntOrNull()
                if (dayNum == null || dayNum < 1 || dayNum > 31) {
                    isValid = false
                    etDateOfMonth.error = "Date must be between 1 and 31"
                } else {
                    etDateOfMonth.error = null
                }
            }
        }

        btnSave.alpha = if (isValid) 1.0f else 0.5f
    }

    private fun saveFinminder() {
        val freqType = selectedFrequencyIndex // 0=One time, 1=Daily, 2=Weekly, 3=Monthly
        var dateInfo = ""
        var freqStr = ""
        
        if (freqType == 0) {
            freqStr = "One time"
            dateInfo = selectedDateStr
        } else if (freqType == 1) {
            freqStr = "Daily"
            dateInfo = ""
        } else if (freqType == 2) {
            freqStr = "Weekly"
            dateInfo = selectedDayStr
        } else if (freqType == 3) {
            freqStr = "Monthly"
            dateInfo = etDateOfMonth.text.toString()
        }

        val item = FinminderItem(
            id = editModeItemId ?: java.util.UUID.randomUUID().toString(),
            type = currentTab,
            title = etTitle.text.toString().trim(),
            quantity = etQuantity.text.toString().trim(),
            frequency = freqStr,
            dateInfo = dateInfo,
            timestamp = if (editModeTimestamp > 0) editModeTimestamp else System.currentTimeMillis()
        )

        FinminderRepository.saveItem(this, item)
        FirestoreSyncManager.pushAllDataToCloud(this)
        Toast.makeText(this, "Saved successfully!", Toast.LENGTH_SHORT).show()
        finish()
    }
}
