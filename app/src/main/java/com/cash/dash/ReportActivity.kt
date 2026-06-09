package com.cash.dash

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.*
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.airbnb.lottie.LottieAnimationView
import com.google.android.material.button.MaterialButtonToggleGroup
import java.util.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import kotlin.math.abs
import java.text.SimpleDateFormat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReportActivity : ThemedActivity() {

    private lateinit var layoutContent: LinearLayout

    private lateinit var btnPeriodSelect: Button
    private lateinit var toggleMode: MaterialButtonToggleGroup
    private lateinit var layoutCustomDates: View
    private lateinit var btnCustomStart: Button
    private lateinit var btnCustomEnd: Button

    private var currentMonth = Calendar.getInstance().get(Calendar.MONTH)
    private var currentYear = Calendar.getInstance().get(Calendar.YEAR)
    private var selectedWeekIndex = 0
    private var isMonthlyMode = true
    private var isCustomMode = false
    private var customStartMillis = 0L
    private var customEndMillis = 0L
    private var isGenerating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val topBar = findViewById<View>(R.id.topBar)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.reportRoot)) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

            // Top Bar Margin
            val topBarView = findViewById<View>(R.id.topBar)
            val params = topBarView.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            params.topMargin = systemBars.top
            topBarView.layoutParams = params

            // Sticky Download Button (Absolute Edge-to-Edge)
            val btnDownload = findViewById<View>(R.id.btnDownloadFinal)
            val btnParams = btnDownload.layoutParams as android.view.ViewGroup.MarginLayoutParams
            btnParams.bottomMargin = navBarHeight
            btnDownload.layoutParams = btnParams

            insets
        }


        layoutContent = findViewById(R.id.reportContent)
        btnPeriodSelect = findViewById(R.id.btnPeriodSelect)
        toggleMode = findViewById(R.id.toggleMode)
        layoutCustomDates = findViewById(R.id.layoutCustomDates)
        btnCustomStart = findViewById(R.id.btnCustomStart)
        btnCustomEnd = findViewById(R.id.btnCustomEnd)

        // White Theme UI Refinement
        if (ThemeHelper.isWhiteTheme(this)) {
            btnPeriodSelect.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Color.BLACK)
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnDownloadFinal).setOnClickListener { generateDownload() }

        btnCustomStart.setOnClickListener { showDatePicker(true) }
        btnCustomEnd.setOnClickListener { showDatePicker(false) }

        toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isMonthlyMode = (checkedId == R.id.btnMonthly)
                isCustomMode = (checkedId == R.id.btnCustom)
                
                if (isCustomMode) {
                    btnPeriodSelect.visibility = View.GONE
                    layoutCustomDates.visibility = View.VISIBLE
                    layoutContent.removeAllViews() // wait for dates
                    if (customStartMillis > 0 && customEndMillis > 0) {
                        loadReport()
                    }
                } else {
                    btnPeriodSelect.visibility = View.VISIBLE
                    layoutCustomDates.visibility = View.GONE
                    lifecycleScope.launch(Dispatchers.IO) {
                        if (!isMonthlyMode) {
                            selectedWeekIndex = getWeekIndexForNow()
                        }
                        withContext(Dispatchers.Main) {
                            updatePeriodLabel()
                            loadReport()
                        }
                    }
                }
            }
        }

        btnPeriodSelect.setOnClickListener { showPeriodPicker() }

        updatePeriodLabel()
        loadReport()
    }

    private fun showDatePicker(isStart: Boolean) {
        val cal = Calendar.getInstance()
        val currentMillis = if (isStart) (if (customStartMillis > 0) customStartMillis else cal.timeInMillis) 
                            else (if (customEndMillis > 0) customEndMillis else cal.timeInMillis)
        cal.timeInMillis = currentMillis
        
        android.app.DatePickerDialog(this, { _, year, month, day ->
            val sel = Calendar.getInstance().apply { 
                set(year, month, day, if(isStart) 0 else 23, if(isStart) 0 else 59, if(isStart) 0 else 59) 
            }.timeInMillis
            
            if (isStart) {
                if (customEndMillis > 0 && sel > customEndMillis) {
                    ToastHelper.showToast(this, "Start date cannot be after end date")
                    return@DatePickerDialog
                }
                customStartMillis = sel
                btnCustomStart.text = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(sel))
            } else {
                if (customStartMillis > 0 && sel < customStartMillis) {
                    ToastHelper.showToast(this, "End date cannot be before start date")
                    return@DatePickerDialog
                }
                customEndMillis = sel
                btnCustomEnd.text = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(sel))
            }
            
            if (customStartMillis > 0 && customEndMillis > 0) {
                loadReport()
            }
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun updatePeriodLabel() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (isMonthlyMode) {
                val sdf = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
                val cal = Calendar.getInstance().apply { set(currentYear, currentMonth, 1) }
                val label = sdf.format(cal.time)
                withContext(Dispatchers.Main) {
                    btnPeriodSelect.text = label
                }
            } else {
                val weeks = FinancialInsightsManager.calculateWeeklyTrends(this@ReportActivity, currentMonth, currentYear)
                val label = if (selectedWeekIndex < weeks.size) {
                    "${weeks[selectedWeekIndex].weekLabel} (${weeks[selectedWeekIndex].dates})"
                } else {
                    "Select Week"
                }
                withContext(Dispatchers.Main) {
                    btnPeriodSelect.text = label
                }
            }
        }
    }

    private suspend fun getWeekIndexForNow(): Int = withContext(Dispatchers.IO) {
        val cal = Calendar.getInstance()
        if (currentMonth != cal.get(Calendar.MONTH) || currentYear != cal.get(Calendar.YEAR)) return@withContext 0
        
        val weeks = FinancialInsightsManager.calculateWeeklyTrends(this@ReportActivity, currentMonth, currentYear)
        val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
        val nowCal = Calendar.getInstance()
        
        for (i in weeks.indices) {
            val parts = weeks[i].dates.split(" - ")
            if (parts.size == 2) {
                try {
                    val start = sdf.parse(parts[0])
                    val end = sdf.parse(parts[1])
                    if (start != null && end != null) {
                        val startCal = Calendar.getInstance().apply { 
                            time = start
                            set(Calendar.YEAR, currentYear)
                        }
                        val endCal = Calendar.getInstance().apply { 
                            time = end
                            set(Calendar.YEAR, currentYear)
                            set(Calendar.HOUR_OF_DAY, 23)
                            set(Calendar.MINUTE, 59)
                        }
                        if (nowCal.timeInMillis in startCal.timeInMillis..endCal.timeInMillis) return@withContext i
                    }
                } catch (e: Exception) {}
            }
        }
        return@withContext if (weeks.isNotEmpty()) weeks.size - 1 else 0
    }

    private fun showPeriodPicker() {
        val months = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
        val picker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 11
            value = currentMonth
            displayedValues = months
        }
        
        android.app.AlertDialog.Builder(this)
            .setTitle(if (isMonthlyMode) "Select Month" else "Select Month First")
            .setView(picker)
            .setPositiveButton("OK") { _, _ ->
                currentMonth = picker.value
                if (isMonthlyMode) {
                    updatePeriodLabel()
                    loadReport()
                } else {
                    showWeekPicker()
                }
            }.show()
    }

    private fun showWeekPicker() {
        lifecycleScope.launch(Dispatchers.IO) {
            val weeks = FinancialInsightsManager.calculateWeeklyTrends(this@ReportActivity, currentMonth, currentYear)
            val weekLabels = weeks.map { "${it.weekLabel} (${it.dates})" }.toTypedArray()
            
            withContext(Dispatchers.Main) {
                android.app.AlertDialog.Builder(this@ReportActivity)
                    .setTitle("Select Week for ${java.text.DateFormatSymbols().months[currentMonth]}")
                    .setItems(weekLabels) { _, which ->
                        selectedWeekIndex = which
                        updatePeriodLabel()
                        loadReport()
                    }.show()
            }
        }
    }

    private fun loadReport() {
        if (isGenerating) return
        isGenerating = true
        
        layoutContent.removeAllViews()
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val insights = FinancialInsightsManager.generateReport(
                    this@ReportActivity, isMonthlyMode, isCustomMode, customStartMillis, customEndMillis, currentMonth, currentYear, if (isMonthlyMode) -1 else selectedWeekIndex
                )
                withContext(Dispatchers.Main) {
                    isGenerating = false
                    renderReport(insights)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isGenerating = false
                    addCard("Advisory Offline", "Error", "Quantum engine error: ${e.localizedMessage}", R.drawable.ic_glass_menu_vector)
                }
            }
        }
    }

    private fun renderReport(insights: FinancialInsightsManager.AdvisoryInsights) {
        try {
            layoutContent.removeAllViews()
            if (insights.totalSpent == 0f && insights.topCategories.isEmpty()) {
                addEmptyStateCard(); return
            }

            // 0. Render 3D Chart
            if (insights.topCategories.isNotEmpty()) {
                injectPieChartCard(insights)
            }

            // 1. Summary
            val modeLabel = if (insights.isCustomMode) "Custom" else if (isMonthlyMode) "Monthly" else "Weekly"
            addCard("$modeLabel Spending Summary", 
                "₹${insights.totalSpent.toInt()}", 
                "${if (insights.changePercent >= 0) "↑" else "↓"}${abs(insights.changePercent).toInt()}% vs previous period",
                R.drawable.ic_glass_menu_vector) {
                addInfoRow("Daily Average", "₹${insights.dailyAverage.toInt()}")
            }

            val topCategoryName = insights.topCategories.firstOrNull()?.category ?: "None"
            val displayTopCategoryName = if (topCategoryName.equals("no choice", ignoreCase = true)) "No Allocation" else topCategoryName
            addCard("Category-wise Attribution", 
                displayTopCategoryName, 
                "Dominates ${insights.topCategories.firstOrNull()?.percentage?.toInt() ?: 0}% of budget",
                R.drawable.ic_category_transport) {
                insights.topCategories.take(5).forEach {
                    val displayCatName = if (it.category.equals("no choice", ignoreCase = true)) "No Allocation" else it.category
                    addInfoRow(displayCatName, "₹${it.amount.toInt()} (${it.percentage.toInt()}%)")
                }
            }

            // 3. Weekly/Daily Patterns
            if (insights.isCustomMode) {
                addCard("Dominant Spending Dates", 
                    if (insights.dailyPatterns.isNotEmpty()) insights.dailyPatterns[0].dayLabel else "N/A", 
                    "Highest consumption dates in range",
                    R.drawable.ic_glass_menu_vector) {
                    insights.dailyPatterns.forEach { 
                        addInfoRow(it.dayLabel, "₹${it.amount.toInt()}")
                    }
                }
            } else if (isMonthlyMode) {
                val topWeek = insights.topWeeks.firstOrNull()
                addCard("Weekly Spending Pattern", 
                    topWeek?.weekLabel ?: "N/A", 
                    "Peak consumption week",
                    R.drawable.ic_glass_menu_vector) {
                    insights.topWeeks.forEach { 
                        addInfoRow(it.weekLabel, "₹${it.amount.toInt()}", it.dates)
                    }
                }
            } else {
                val peak = insights.dailyPatterns.find { it.isPeak }
                addCard("Daily Spending Pattern", 
                    peak?.dayLabel ?: "N/A", 
                    "Peak velocity: ₹${peak?.amount?.toInt() ?: 0}",
                    R.drawable.ic_glass_menu_vector) {
                    insights.dailyPatterns.forEach { 
                        addInfoRow(it.dayLabel, "₹${it.amount.toInt()}", if (it.isPeak) "PEAK" else if (it.isLow) "LOW" else null)
                    }
                }
            }

            // 4. Budget & Allocation Limits (Weekly Focus)
            if (!isMonthlyMode) {
                addCard("Allocation Limit Analysis", 
                    "${insights.budgetStatus.categoryProgress.count { it.percent > 100 }} Crossed", 
                    "Checking targets vs actuals",
                    R.drawable.ic_plus) {
                    insights.budgetStatus.categoryProgress.forEach {
                        val diffPer = (it.percent - 100).toInt()
                        val status = when {
                            it.percent > 100f -> "Used: ₹${it.spent.toInt()} (+$diffPer%)"
                            it.percent > 0f -> "Used: ₹${it.spent.toInt()} (${(it.percent - 100).toInt()}%)"
                            else -> "No spent recorded"
                        }
                        val displayCatName = if (it.category.equals("no choice", ignoreCase = true)) "No Allocation" else it.category
                        addInfoRow(displayCatName, "Original limit: ₹${it.budget}", status)
                    }
                }
            }



        } catch (e: Exception) {
            e.printStackTrace()
            addCard("Advisory Offline", "Error", "Quantum engine error: ${e.localizedMessage}", R.drawable.ic_glass_menu_vector)
        }
    }

    private fun injectPieChartCard(insights: FinancialInsightsManager.AdvisoryInsights) {
        val summaries = insights.topCategories
        val dp = resources.displayMetrics.density
        val ta = obtainStyledAttributes(intArrayOf(R.attr.cardBackground))
        val cardBg = ta.getDrawable(0)
        ta.recycle()

        // Main Wrapper Card
        val cardWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (20 * dp).toInt() }
            background = cardBg
            val pad = (16 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }

        // 1. Title for the Chart with Total Budget
        val tvHeader = TextView(this).apply {
            text = "TOTAL SPENT: ₹${insights.totalSpent.toInt()}  |  ${insights.periodLabel}"
            setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textMutedColor))
            textSize = 10f
            letterSpacing = 0.1f
            typeface = Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
        }
        cardWrapper.addView(tvHeader)

        // 2. The 3D Chart
        val chartContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (200 * dp).toInt()
            )
        }
        val chart = ThreeDPieChartView(this)
        chart.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        // Only show segments with > 0 amount in the actual chart
        chart.setData(summaries.filter { it.amount > 0 })
        chartContainer.addView(chart)
        cardWrapper.addView(chartContainer)

        // 3. The Legend (Shows ALL categories, including ₹0)
        val legendContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (16 * dp).toInt() }
        }

        val colorPalette = intArrayOf(
            Color.parseColor("#7C5CFC"), Color.parseColor("#FCA311"), 
            Color.parseColor("#00F5FF"), Color.parseColor("#FF4D6D"), 
            Color.parseColor("#70E000"), Color.parseColor("#3D5AFE"),
            Color.parseColor("#FF1744"), Color.parseColor("#00E5FF"),
            Color.parseColor("#76FF03"), Color.parseColor("#D500F9"),
            Color.parseColor("#1DE9B6"), Color.parseColor("#FF9100"),
            Color.parseColor("#F50057"), Color.parseColor("#00B0FF"),
            Color.parseColor("#C6FF00"), Color.parseColor("#651FFF")
        )

        summaries.forEachIndexed { index, summary ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
            }

            // Color Indicator (Always show in legend)
            val indicator = View(this).apply {
                layoutParams = LinearLayout.LayoutParams((12 * dp).toInt(), (12 * dp).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(colorPalette[index % colorPalette.size])
                }
            }
            row.addView(indicator)

            // Name
            val nameTv = TextView(this).apply {
                val displayCatName = if (summary.category.equals("no choice", ignoreCase = true)) "No Allocation" else summary.category
                text = displayCatName.uppercase()
                setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
                textSize = 13f
                setPadding((12 * dp).toInt(), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(nameTv)

            // Amount & %
            val statsTv = TextView(this).apply {
                text = "₹${summary.amount.toInt()}  •  ${summary.percentage.toInt()}%"
                setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.END
            }
            row.addView(statsTv)

            legendContainer.addView(row)
        }
        cardWrapper.addView(legendContainer)

        layoutContent.addView(cardWrapper, 0) // Insert at top of report
    }

    private fun addEmptyStateCard() {
        addCard("Data Insufficiency", "₹0", "Add more transactions to fuel AI strategy", R.drawable.ic_glass_menu_vector)
    }

    private fun addCard(title: String, value: String, subtitle: String, iconRes: Int, builder: (LinearLayout.() -> Unit)? = null) {
        val card = layoutInflater.inflate(R.layout.item_report_card, layoutContent, false)
        card.findViewById<TextView>(R.id.cardTitle).text = title
        card.findViewById<TextView>(R.id.cardValue).text = value
        card.findViewById<TextView>(R.id.cardSubtitle).text = subtitle
        card.findViewById<ImageView>(R.id.cardIcon).setImageResource(iconRes)
        
        val extra = card.findViewById<LinearLayout>(R.id.cardExtraContainer)
        if (builder != null) {
            extra.visibility = View.VISIBLE
            extra.builder()
        }
        
        layoutContent.addView(card)
    }

    private fun LinearLayout.addInfoRow(label: String, value: String, sub: String? = null) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        val labelTv = TextView(context).apply {
            text = label
            setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textMutedColor))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val valTv = TextView(context).apply {
            text = value
            setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.END
        }
        row.addView(labelTv)
        row.addView(valTv)
        addView(row)
        
        if (sub != null) {
            val subTv = TextView(context).apply {
                text = sub
                setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textMutedColor))
                textSize = 12f
                setPadding(0, 0, 0, 8)
            }
            addView(subTv)
        }
    }

    private fun LinearLayout.addInsightBullet(text: String) {
        val tv = TextView(context).apply {
            this.text = "• $text"
            setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
            textSize = 14f
            setPadding(0, 8, 0, 8)
            setLineSpacing(0f, 1.2f)
        }
        addView(tv)
    }

    private fun generateDownload() {
        val start: Long
        val end: Long
        if (isCustomMode) {
            if (customStartMillis == 0L || customEndMillis == 0L) {
                ToastHelper.showToast(this, "Select start and end dates first")
                return
            }
            start = customStartMillis
            end = customEndMillis
        } else {
            val cal = Calendar.getInstance().apply { set(currentYear, currentMonth, 1) }
            start = cal.timeInMillis
            cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
            end = cal.timeInMillis
        }
        
        lifecycleScope.launch(Dispatchers.IO) {
            PdfReportManager.generateAndSavePremiumReport(this@ReportActivity, start, end, isMonthlyMode, selectedWeekIndex, isCustomMode)
        }
    }
}
