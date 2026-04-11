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
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = 32f
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

        for (i in barValues.indices) {
            val value = barValues[i]
            val center = paddingLeft + (spacing * (i + 1))
            val isHighlighted = (i == highlightIndex)
            
            textPaint.textAlign = Paint.Align.CENTER

            if (value == 0f) {
                val label = labels[i]
                
                textPaint.typeface = Typeface.DEFAULT_BOLD
                textPaint.color = ContextCompat.getColor(context, R.color.text_dim)
                textPaint.textSize = 38f
                canvas.drawText("₹0", center, bottom - 30f, textPaint)
                
                textPaint.typeface = Typeface.DEFAULT
                textPaint.color = ContextCompat.getColor(context, R.color.text_muted)
                textPaint.textSize = if (label.length > 5) 24f else 32f
                canvas.drawText(label, center, height - 30f, textPaint)
                continue
            }

            val barHeight = (value / maxVal * graphHeight).coerceAtLeast(15f)
            val left = center - barWidth / 2
            val right = center + barWidth / 2
            val top = bottom - barHeight

            if (isHighlighted) {
                val shader = LinearGradient(0f, top, 0f, bottom,
                    intArrayOf(ContextCompat.getColor(context, R.color.primary_light), ContextCompat.getColor(context, R.color.primary_purple)),
                    null, Shader.TileMode.CLAMP)
                highlightBarPaint.shader = shader
                canvas.drawRoundRect(RectF(left, top, right, bottom), 40f, 40f, highlightBarPaint)
            } else {
                barPaint.shader = null
                barPaint.color = Color.parseColor("#D9D9D9")
                canvas.drawRoundRect(RectF(left, top, right, bottom), 40f, 40f, barPaint)
            }

            textPaint.typeface = Typeface.DEFAULT_BOLD
            textPaint.color = ContextCompat.getColor(context, R.color.text_primary)
            textPaint.textSize = 42f
            canvas.drawText("₹${value.toInt()}", center, top - 25f, textPaint)
            
            val label = labels[i]
            textPaint.typeface = Typeface.DEFAULT
            textPaint.color = ContextCompat.getColor(context, R.color.text_primary)
            textPaint.textSize = if (label.length > 5) 24f else 32f
            canvas.drawText(label, center, height - 30f, textPaint)
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
