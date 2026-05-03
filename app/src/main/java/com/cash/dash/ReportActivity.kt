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

class ReportActivity : ThemedActivity() {

    private lateinit var layoutContent: LinearLayout
    private lateinit var layoutLoading: View
    private lateinit var lottieLoading: LottieAnimationView
    private lateinit var tvAITimer: TextView

    private lateinit var btnPeriodSelect: Button
    private lateinit var toggleMode: MaterialButtonToggleGroup

    private var currentMonth = Calendar.getInstance().get(Calendar.MONTH)
    private var currentYear = Calendar.getInstance().get(Calendar.YEAR)
    private var selectedWeekIndex = 0
    private var isMonthlyMode = true
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
            btnDownload.setPadding(0, 0, 0, navBarHeight)
            val btnParams = btnDownload.layoutParams
            btnParams.height = (64 * resources.displayMetrics.density).toInt() + navBarHeight
            btnDownload.layoutParams = btnParams

            insets
        }


        layoutContent = findViewById(R.id.reportContent)
        layoutLoading = findViewById(R.id.layoutAILoading)
        lottieLoading = findViewById(R.id.lottieLoading)
        tvAITimer = findViewById(R.id.tvAITimer)
        btnPeriodSelect = findViewById(R.id.btnPeriodSelect)
        toggleMode = findViewById(R.id.toggleMode)

        // White Theme UI Refinement
        if (ThemeHelper.isWhiteTheme(this)) {
            btnPeriodSelect.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Color.BLACK)
        }

        try {
            lottieLoading.setAnimation(R.raw.financial_analysis)
        } catch (e: Exception) { e.printStackTrace() }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnDownloadFinal).setOnClickListener { generateDownload() }

        toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isMonthlyMode = (checkedId == R.id.btnMonthly)
                if (!isMonthlyMode) {
                    selectedWeekIndex = getWeekIndexForNow()
                }
                updatePeriodLabel()
                loadReport()
            }
        }

        btnPeriodSelect.setOnClickListener { showPeriodPicker() }

        updatePeriodLabel()
        loadReport()
    }

    private fun updatePeriodLabel() {
        if (isMonthlyMode) {
            val sdf = java.text.SimpleDateFormat("MMMM yyyy", Locale.getDefault())
            val cal = Calendar.getInstance().apply { set(currentYear, currentMonth, 1) }
            btnPeriodSelect.text = sdf.format(cal.time)
        } else {
            val weeks = FinancialInsightsManager.calculateWeeklyTrends(this, currentMonth, currentYear)
            if (selectedWeekIndex < weeks.size) {
                btnPeriodSelect.text = "${weeks[selectedWeekIndex].weekLabel} (${weeks[selectedWeekIndex].dates})"
            } else {
                btnPeriodSelect.text = "Select Week"
            }
        }
    }

    private fun getWeekIndexForNow(): Int {
        val cal = Calendar.getInstance()
        if (currentMonth != cal.get(Calendar.MONTH) || currentYear != cal.get(Calendar.YEAR)) return 0
        
        val weeks = FinancialInsightsManager.calculateWeeklyTrends(this, currentMonth, currentYear)
        val sdf = java.text.SimpleDateFormat("MMM d", Locale.getDefault())
        val nowCal = Calendar.getInstance()
        
        for (i in weeks.indices) {
            val parts = weeks[i].dates.split(" - ")
            if (parts.size == 2) {
                try {
                    val start = sdf.parse(parts[0])
                    val end = sdf.parse(parts[1])
                    if (start != null && end != null) {
                        val startCal = Calendar.getInstance().apply { time = start; set(Calendar.YEAR, currentYear) }
                        val endCal = Calendar.getInstance().apply { time = end; set(Calendar.YEAR, currentYear); set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59) }
                        if (nowCal.timeInMillis in startCal.timeInMillis..endCal.timeInMillis) return i
                    }
                } catch (e: Exception) {}
            }
        }
        return if (weeks.isNotEmpty()) weeks.size - 1 else 0
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
        val weeks = FinancialInsightsManager.calculateWeeklyTrends(this, currentMonth, currentYear)
        val weekLabels = weeks.map { "${it.weekLabel} (${it.dates})" }.toTypedArray()
        
        android.app.AlertDialog.Builder(this)
            .setTitle("Select Week for ${java.text.DateFormatSymbols().months[currentMonth]}")
            .setItems(weekLabels) { _, which ->
                selectedWeekIndex = which
                updatePeriodLabel()
                loadReport()
            }.show()
    }

    private fun loadReport() {
        if (isGenerating) return
        isGenerating = true
        
        layoutLoading.visibility = View.VISIBLE
        layoutContent.removeAllViews()
        // Note: pie chart is now injected dynamically into layoutContent, no separate container needed
        
        object : android.os.CountDownTimer(2800, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000).toInt() + 1
                tvAITimer.text = "Curating report using deep algorithm. \n Ready in ${sec}s"
            }
            override fun onFinish() {
                isGenerating = false
                layoutLoading.visibility = View.GONE
                
                // Fix: Move DB-heavy report generation to IO thread
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val insights = FinancialInsightsManager.generateReport(
                            this@ReportActivity, isMonthlyMode, currentMonth, currentYear, if (isMonthlyMode) -1 else selectedWeekIndex
                        )
                        withContext(Dispatchers.Main) {
                            renderReport(insights)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        withContext(Dispatchers.Main) {
                            addCard("Advisory Offline", "Error", "Quantum engine error: ${e.localizedMessage}", R.drawable.ic_glass_menu_vector)
                        }
                    }
                }
            }
        }.start()
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
            addCard("${if (isMonthlyMode) "Monthly" else "Weekly"} Spending Summary", 
                "₹${insights.totalSpent.toInt()}", 
                "${if (insights.changePercent >= 0) "↑" else "↓"}${abs(insights.changePercent).toInt()}% vs last ${if (isMonthlyMode) "month" else "week"}",
                R.drawable.ic_glass_menu_vector) {
                addInfoRow("Daily Average", "₹${insights.dailyAverage.toInt()}")
            }

            // 2. Category Breakdown
            addCard("Category-wise Attribution", 
                insights.topCategories.firstOrNull()?.category ?: "None", 
                "Dominates ${insights.topCategories.firstOrNull()?.percentage?.toInt() ?: 0}% of budget",
                R.drawable.ic_category_transport) {
                insights.topCategories.take(5).forEach {
                    addInfoRow(it.category, "₹${it.amount.toInt()} (${it.percentage.toInt()}%)")
                }
            }

            // 3. Weekly/Daily Patterns
            if (isMonthlyMode) {
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
                        addInfoRow(it.category, "Original limit: ₹${it.budget}", status)
                    }
                }
            }

            // 5. Savings & Improvement Opportunities
            addCard("Savings Optimization", 
                "₹ Efficiency Strategy", 
                "Actionable reduction targets",
                R.drawable.ic_glass_menu_vector) {
                addInsightBullet(insights.savingsOpportunity)
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
            Color.parseColor("#00F5FF"), Color.parseColor("#FF4D6D"), Color.parseColor("#70E000")
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
                text = summary.category.uppercase()
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
        val cal = Calendar.getInstance().apply { set(currentYear, currentMonth, 1) }
        val start = cal.timeInMillis
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        val end = cal.timeInMillis
        PdfReportManager.generateAndSavePremiumReport(this, start, end, isMonthlyMode, selectedWeekIndex)
    }
}
