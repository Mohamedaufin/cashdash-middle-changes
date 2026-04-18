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

        // Handle System Insets for Status Bar Padding
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.topBar)) { v, insets ->
            val statusInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            v.setPadding(v.paddingLeft, statusInsets.top + 10, v.paddingRight, v.paddingBottom)
            insets
        }

        layoutContent = findViewById(R.id.reportContent)
        layoutLoading = findViewById(R.id.layoutAILoading)
        lottieLoading = findViewById(R.id.lottieLoading)
        tvAITimer = findViewById(R.id.tvAITimer)
        btnPeriodSelect = findViewById(R.id.btnPeriodSelect)
        toggleMode = findViewById(R.id.toggleMode)

        try {
            lottieLoading.setAnimation(R.raw.financial_analysis)
        } catch (e: Exception) { e.printStackTrace() }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnDownloadFinal).setOnClickListener { generateDownload() }

        toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isMonthlyMode = (checkedId == R.id.btnMonthly)
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
                renderReport()
            }
        }.start()
    }

    private fun renderReport() {
        try {
            val insights = FinancialInsightsManager.generateReport(
                this, isMonthlyMode, currentMonth, currentYear, if (isMonthlyMode) -1 else selectedWeekIndex
            )
            
            layoutContent.removeAllViews()
            if (insights.totalSpent == 0f && insights.topCategories.isEmpty()) {
                addEmptyStateCard(); return
            }

            // 0. Render 3D Chart (added AFTER removeAllViews so it's not wiped)
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

            // 3. Overspending Alerts
            if (insights.alerts.isNotEmpty()) {
                addCard("Integrity & Risk Alerts", 
                    "${insights.alerts.size} Active", 
                    "Anomalies detected in budget flow",
                    R.drawable.ic_glass_menu_vector) {
                    insights.alerts.forEach { alert ->
                        addWarningLabel("${alert.title}: ${alert.message}")
                    }
                }
            }

            // 4. Budget Tracker
            val budgetUsed = if (insights.budgetStatus.totalBudget > 0) 
                (insights.totalSpent / insights.budgetStatus.totalBudget) * 100 
                else 0f
            addCard("Budget Progress Tracker", 
                "${budgetUsed.toInt()}% Capacity", 
                "₹${insights.totalSpent.toInt()} / ₹${insights.budgetStatus.totalBudget}",
                R.drawable.ic_plus) {
                insights.budgetStatus.categoryProgress.take(3).forEach {
                    addInfoRow(it.category, "${it.percent.toInt()}%", "₹${it.spent.toInt()} / ₹${it.budget}")
                }
            }

            // 5. Habits & Timing
            addCard("Behavioral Habitation", 
                "${insights.habitInsights.size} Patterns", 
                "Strategic routine analysis",
                R.drawable.ic_glass_menu_vector) {
                insights.habitInsights.forEach { addInsightBullet(it.message) }
            }

            // 6. Daily Patterns
            val peak = insights.dailyPatterns.find { it.isPeak }
            addCard("Daily Spending Pattern", 
                peak?.dayLabel ?: "N/A", 
                "Peak velocity: ₹${peak?.amount?.toInt() ?: 0}",
                R.drawable.ic_glass_menu_vector) {
                insights.dailyPatterns.forEach { 
                    addInfoRow(it.dayLabel, "₹${it.amount.toInt()}", if (it.isPeak) "PEAK" else if (it.isLow) "LOW" else null)
                }
            }

            // 7. Transaction Insights
            addCard("Transaction Granularity", 
                "${insights.transactionStats.totalCount} Orders", 
                "Average ticket: ₹${insights.transactionStats.avgPerTrans.toInt()}",
                R.drawable.ic_glass_menu_vector) {
                addInfoRow("Most Frequent", insights.transactionStats.frequentCategory)
            }

            // 8. Financial Score
            addCard("Executive Health Score", 
                "${insights.score}/100", 
                getHealthLabel(insights.score),
                R.drawable.ic_glass_menu_vector)

            // 9. Savings Opportunity
            addCard("Savings Optimization", 
                "₹ Efficiency Opportunity", 
                "Actionable reduction strategy",
                R.drawable.ic_glass_menu_vector) {
                addInsightBullet(insights.savingsOpportunity)
            }

            // 10. Achievements
            if (insights.achievements.isNotEmpty()) {
                addCard("Milestones & Achievements", 
                    "${insights.achievements.size} Unlocked", 
                    "Strategic budget excellence",
                    R.drawable.ic_glass_menu_vector) {
                    insights.achievements.forEach { addAchievementLabel(it) }
                }
            }

            // 11. Prediction
            addCard("Fiscal Outlook Forecast", 
                "₹${insights.prediction.toInt()}", 
                "Projected burn for next period",
                R.drawable.ic_glass_menu_vector)

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
            text = "CAPITAL ALLOCATION  |  BUDGET: ₹${insights.budgetStatus.totalBudget}"
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
        chart.setData(summaries)
        chartContainer.addView(chart)
        cardWrapper.addView(chartContainer)

        // 3. The Legend
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

            // Color Indicator
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

    private fun LinearLayout.addWarningLabel(text: String) {
        addView(TextView(context).apply { this.text = "⚠️ $text"; setTextColor(Color.parseColor("#FF4D6D")); textSize = 12f; setPadding(0, 8, 0, 8); setTypeface(null, Typeface.BOLD) })
    }

    private fun LinearLayout.addAchievementLabel(text: String) {
        addView(TextView(context).apply { this.text = "🏆 $text"; setTextColor(Color.parseColor("#70E000")); textSize = 13f; setPadding(0, 8, 0, 8); setTypeface(null, Typeface.BOLD) })
    }

    private fun getHealthLabel(score: Int) = when {
        score >= 85 -> "Excellent Control"
        score >= 70 -> "Good Sustainability"
        score >= 50 -> "Atmospheric Risk Detected"
        else -> "Critical Budget Integrity Loss"
    }

    private fun generateDownload() {
        val cal = Calendar.getInstance().apply { set(currentYear, currentMonth, 1) }
        val start = cal.timeInMillis
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        val end = cal.timeInMillis
        PdfReportManager.generateAndSavePremiumReport(this, start, end)
    }
}
