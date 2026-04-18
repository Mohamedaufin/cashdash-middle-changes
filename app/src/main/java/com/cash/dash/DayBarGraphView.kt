package com.cash.dash

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import java.util.*

class DayBarGraphView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val dailyData = MutableList(7) { 0f }
    private val weeklyTotals = mutableListOf<Float>()
    private val monthlyTotals = MutableList(12) { 0f }

    private var currentMode = "DAILY"

    private val dailyLabels = mutableListOf("Sun","Mon","Tue","Wed","Thu","Fri","Sat")
    private val weeklyLabels = mutableListOf("W1","W2","W3","W4","W5")
    private val monthlyLabels = mutableListOf(
        "Jan","Feb","Mar","Apr","May","Jun",
        "Jul","Aug","Sep","Oct","Nov","Dec"
    )

    private var highlightDay: Int = -1
    private var highlightWeek: Int = -1
    private var highlightMonth: Int = -1

    var onBarClickListener: ((index: Int, mode: String) -> Unit)? = null

    private val barPaint = Paint().apply {
        isAntiAlias = true
    }

    private val highlightBarPaint = Paint().apply {
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor)
        textSize = context.resources.getDimension(R.dimen.graph_text_subhead) 
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val barValues = when (currentMode) {
            "WEEKLY" -> weeklyTotals
            "MONTHLY" -> monthlyTotals
            else -> dailyData
        }

        val labels = when (currentMode) {
            "WEEKLY" -> weeklyLabels.take(barValues.size)
            "MONTHLY" -> monthlyLabels
            else -> dailyLabels
        }

        val highlightIndex = when (currentMode) {
            "WEEKLY" -> highlightWeek
            "MONTHLY" -> highlightMonth
            else -> highlightDay
        }

        val maxVal = barValues.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        val availableWidth = width - paddingLeft - paddingRight
        val spacing = availableWidth / (barValues.size + 1f)
        val barWidth = availableWidth / 14f
        val bottom = height - 100f 
        val graphHeight = height - 260f 

        var unifiedAmountSize = context.resources.getDimension(R.dimen.graph_text_subhead)
        if (barValues.isNotEmpty()) {
            textPaint.textSize = unifiedAmountSize
            textPaint.typeface = Typeface.DEFAULT
            var maxAmount = "₹0"
            var maxLen = 0f
            for (value in barValues) {
                val str = "₹${value.toInt()}"
                val len = textPaint.measureText(str)
                if (len > maxLen) { maxLen = len; maxAmount = str }
            }
            while (textPaint.measureText(maxAmount) > spacing - 4f && unifiedAmountSize > (8 * resources.displayMetrics.density)) {
                unifiedAmountSize -= 1f
                textPaint.textSize = unifiedAmountSize
            }
        }

        var unifiedLabelSize = context.resources.getDimension(R.dimen.graph_text_caption)
        if (labels.isNotEmpty()) {
            textPaint.textSize = unifiedLabelSize
            textPaint.typeface = Typeface.DEFAULT
            var maxLabel = labels[0]
            var maxLen = 0f
            for (lbl in labels) {
               val len = textPaint.measureText(lbl)
               if (len > maxLen) { maxLen = len; maxLabel = lbl }
            }
            while (textPaint.measureText(maxLabel) > spacing - 4f && unifiedLabelSize > (6 * resources.displayMetrics.density)) {
                unifiedLabelSize -= 1f
                textPaint.textSize = unifiedLabelSize
            }
        }

        for (i in barValues.indices) {
            val value = barValues[i]
            val center = paddingLeft + (spacing * (i + 1))
            val isHighlighted = (i == highlightIndex)
            
            textPaint.textAlign = Paint.Align.CENTER

            if (value == 0f) {
                textPaint.typeface = Typeface.DEFAULT
                textPaint.color = ThemeHelper.resolveColorAttr(context, R.attr.textMutedColor)
                textPaint.textSize = unifiedAmountSize
                canvas.drawText("₹0", center, bottom - 30f, textPaint)
                
                textPaint.typeface = Typeface.DEFAULT
                val labelStr = labels[i]
                textPaint.textSize = unifiedLabelSize
                textPaint.color = if (ThemeHelper.isWhiteTheme(context)) 
                    ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor) 
                    else ThemeHelper.resolveColorAttr(context, R.attr.textMutedColor)
                canvas.drawText(labelStr, center, height - 30f, textPaint)
                continue
            }

            val barHeight = (value / maxVal * graphHeight).coerceAtLeast(15f)
            val left = center - barWidth / 2
            val right = center + barWidth / 2
            val top = bottom - barHeight

            if (isHighlighted) {
                // Highlighted is Cyan for all themes now
                val colors = intArrayOf(Color.parseColor("#8BF7E6"), Color.parseColor("#4DE1C1"))
                val shader = LinearGradient(0f, top, 0f, bottom, colors, null, Shader.TileMode.CLAMP)
                highlightBarPaint.shader = shader
                canvas.drawRoundRect(RectF(left, top, right, bottom), 40f, 40f, highlightBarPaint)
            } else {
                barPaint.shader = null
                val isWhiteTheme = com.cash.dash.ThemeHelper.isWhiteTheme(context)
                barPaint.color = if (isWhiteTheme) Color.parseColor("#D9D9D9") else Color.WHITE
                canvas.drawRoundRect(RectF(left, top, right, bottom), 40f, 40f, barPaint)
            }

            textPaint.typeface = Typeface.DEFAULT
            textPaint.color = ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor)
            var amountStr = "₹${value.toInt()}"
            textPaint.textSize = unifiedAmountSize
            canvas.drawText(amountStr, center, top - 25f, textPaint)
            
            val labelStr = labels[i]
            textPaint.typeface = Typeface.DEFAULT
            textPaint.color = ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor)
            textPaint.textSize = unifiedLabelSize
            canvas.drawText(labelStr, center, height - 30f, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true

        val barCount = when (currentMode) {
            "WEEKLY" -> weeklyTotals.size
            "MONTHLY" -> monthlyTotals.size
            else -> dailyData.size
        }

        val availableWidth = width - paddingLeft - paddingRight
        val spacing = availableWidth / (barCount + 1f)
        val barWidth = availableWidth / 14f

        for (i in 0 until barCount) {
            val center = paddingLeft + (spacing * (i + 1))
            if (event.x in (center - barWidth / 2)..(center + barWidth / 2)) {

                when (currentMode) {
                    "WEEKLY" -> onBarClickListener?.invoke(i, "WEEKLY_SWITCHED")
                    "MONTHLY" -> onBarClickListener?.invoke(i, "MONTHLY_SWITCHED")
                    else -> onBarClickListener?.invoke(i, "DAILY")
                }
                return true
            }
        }

        return true
    }

    fun setDayMode() { currentMode = "DAILY"; invalidate() }
    fun setWeekMode() { currentMode = "WEEKLY"; invalidate() }
    fun setMonthMode() { currentMode = "MONTHLY"; invalidate() }

    fun setDailyData(list: List<Float>) {
        for (i in 0 until 7) dailyData[i] = list[i]
        invalidate()
    }

    fun setWeeklyData(list: List<Float>) {
        weeklyTotals.clear()
        weeklyTotals.addAll(list)
        invalidate()
    }

    fun setMonthlyData(list: List<Float>) {
        for (i in list.indices) monthlyTotals[i] = list[i]
        invalidate()
    }

    fun setDailyLabels(labels: List<String>) {
        dailyLabels.clear()
        dailyLabels.addAll(labels)
        invalidate()
    }

    fun setWeeklyLabels(labels: List<String>) {
        weeklyLabels.clear()
        weeklyLabels.addAll(labels)
        invalidate()
    }

    fun setMonthlyLabels(labels: List<String>) {
        monthlyLabels.clear()
        monthlyLabels.addAll(labels)
        invalidate()
    }

    fun setHighlightIndices(day: Int, week: Int, month: Int) {
        highlightDay = day
        highlightWeek = week
        highlightMonth = month
        invalidate()
    }

    fun getMonthlyLabel(index: Int) = monthlyLabels[index]
}
