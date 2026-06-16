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
    private lateinit var btnFreqWeekly: TextView
    private lateinit var btnFreqMonthly: TextView
    
    private var selectedFrequencyIndex = -1
    private lateinit var scrollView: ScrollView
    private lateinit var layoutOneTime: LinearLayout
    private lateinit var calendarView: CalendarView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_finminder)

        currentTab = intent.getStringExtra("TAB") ?: "CASH_OUT"

        val btnBack = findViewById<View>(R.id.btnBack)
        val tvAddTitle = findViewById<TextView>(R.id.tvAddTitle)
        
        tvAddTitle.text = if (currentTab == "CASH_OUT") "Finminder Cashout" else "Finminder Cashin"
        btnBack.setOnClickListener { finish() }

        etTitle = findViewById(R.id.etTitle)
        etQuantity = findViewById(R.id.etQuantity)
        layoutFrequencyExpandable = findViewById(R.id.layoutFrequencyExpandable)
        btnFrequencyToggle = findViewById(R.id.btnFrequencyToggle)
        tvSelectedFrequency = findViewById(R.id.tvSelectedFrequency)
        layoutFrequencyOptions = findViewById(R.id.layoutFrequencyOptions)
        btnFreqOneTime = findViewById(R.id.btnFreqOneTime)
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
        val updateHintText = {
            val title = etTitle.text.toString().trim().ifEmpty { "your expense" }
            if (selectedFrequencyIndex == 0) {
                val dateStrFull = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(
                    SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).parse(selectedDateStr) ?: Date()
                )
                tvNotificationHint.text = "You will be notified about $title on $dateStrFull"
                tvNotificationHint.visibility = View.VISIBLE
                tvNotificationHintWeekly.visibility = View.GONE
                tvNotificationHintMonthly.visibility = View.GONE
                scrollView.post { scrollView.smoothScrollTo(0, scrollView.bottom) }
            } else if (selectedFrequencyIndex == 1) {
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
                    tvNotificationHintWeekly.text = "You will be notified about $title on every $selectedDayStr\n\nExamples: ${dates.joinToString(", ")}, etc."
                    tvNotificationHintWeekly.visibility = View.VISIBLE
                    scrollView.post { scrollView.smoothScrollTo(0, scrollView.bottom) }
                }
            } else if (selectedFrequencyIndex == 2) {
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
                    tvNotificationHintMonthly.text = "You will be notified about $title on every month's $dayOfMonth\n\nExamples: ${dates.joinToString(", ")}, etc."
                    tvNotificationHintMonthly.visibility = View.VISIBLE
                    scrollView.post { scrollView.smoothScrollTo(0, scrollView.bottom) }
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
        
        btnDayToggle.setOnClickListener {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(btnDayToggle.windowToken, 0)
            layoutDayOptions.visibility = if (layoutDayOptions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        
        val selectDay = { text: String ->
            selectedDayStr = text
            tvSelectedDay.text = text
            layoutDayOptions.visibility = View.GONE
            updateHintText()
        }
        
        findViewById<View>(R.id.btnDayMonday).setOnClickListener { selectDay("Monday") }
        findViewById<View>(R.id.btnDayTuesday).setOnClickListener { selectDay("Tuesday") }
        findViewById<View>(R.id.btnDayWednesday).setOnClickListener { selectDay("Wednesday") }
        findViewById<View>(R.id.btnDayThursday).setOnClickListener { selectDay("Thursday") }
        findViewById<View>(R.id.btnDayFriday).setOnClickListener { selectDay("Friday") }
        findViewById<View>(R.id.btnDaySaturday).setOnClickListener { selectDay("Saturday") }
        findViewById<View>(R.id.btnDaySunday).setOnClickListener { selectDay("Sunday") }

        btnFrequencyToggle.setOnClickListener {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(btnFrequencyToggle.windowToken, 0)
            layoutFrequencyOptions.visibility = if (layoutFrequencyOptions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        val selectFrequency = { index: Int, text: String ->
            selectedFrequencyIndex = index
            tvSelectedFrequency.text = text
            layoutFrequencyOptions.visibility = View.GONE
            layoutOneTime.visibility = if (index == 0) View.VISIBLE else View.GONE
            layoutWeekly.visibility = if (index == 1) View.VISIBLE else View.GONE
            layoutMonthly.visibility = if (index == 2) View.VISIBLE else View.GONE
            validateForm()
            updateHintText()

        }

        btnFreqOneTime.setOnClickListener { selectFrequency(0, "One time only") }
        btnFreqWeekly.setOnClickListener { selectFrequency(1, "Repeat every week") }
        btnFreqMonthly.setOnClickListener { selectFrequency(2, "Repeat every month") }

        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        selectedDateStr = sdf.format(calendarView.date)
        calendarView.setOnDateChangeListener { _, year, month, dayOfMonth ->
            val cal = Calendar.getInstance()
            cal.set(year, month, dayOfMonth)
            selectedDateStr = sdf.format(cal.time)
            updateHintText()
            validateForm()
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
        
        if (freq == 2) { // Monthly
            if (etDateOfMonth.text.toString().trim().isEmpty()) {
                isValid = false
            }
        }

        btnSave.alpha = if (isValid) 1.0f else 0.5f
    }

    private fun saveFinminder() {
        val freqType = selectedFrequencyIndex // 0=One time, 1=Weekly, 2=Monthly
        var dateInfo = ""
        var freqStr = ""
        
        if (freqType == 0) {
            freqStr = "One time"
            dateInfo = selectedDateStr
        } else if (freqType == 1) {
            freqStr = "Weekly"
            dateInfo = selectedDayStr
        } else if (freqType == 2) {
            freqStr = "Monthly"
            dateInfo = etDateOfMonth.text.toString()
        }

        val item = FinminderItem(
            id = UUID.randomUUID().toString(),
            type = currentTab,
            title = etTitle.text.toString().trim(),
            quantity = etQuantity.text.toString().trim(),
            frequency = freqStr,
            dateInfo = dateInfo
        )

        FinminderRepository.saveItem(this, item)
        Toast.makeText(this, "Saved successfully!", Toast.LENGTH_SHORT).show()
        finish()
    }
}
