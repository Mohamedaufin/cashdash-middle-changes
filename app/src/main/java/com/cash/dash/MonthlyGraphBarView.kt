package com.cash.dash

import android.content.Context
import android.graphics.*
import androidx.core.content.ContextCompat
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class MonthlyBarGraphView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val monthlyTotals = MutableList(12) { 0f }

    private val monthLabels = listOf(
        "Jan","Feb","Mar","Apr","May","Jun",
        "Jul","Aug","Sep","Oct","Nov","Dec"
    )

    private val barPaint = Paint().apply {
        isAntiAlias = true
    }

    private val highlightPaint = Paint().apply {
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = 28f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val barRadius = 40f
    private var selectedMonthIndex = 0

    // Callback for tap
    var onMonthClick: ((Int) -> Unit)? = null

    fun setMonthlyData(list: List<Float>) {
        for (i in 0 until 12) monthlyTotals[i] = list[i]
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val maxVal = monthlyTotals.maxOrNull()?.takeIf { it > 0 } ?: 1f

        val barWidth = width / 28f
        val spacing = width / 13f

        val bottom = height - 140f
        val graphHeight = height - 220f

        for (i in 0 until 12) {

            val value = monthlyTotals[i]
            val center = spacing * (i + 1)

            if (value == 0f) {
                // Only draw month label, skip ₹0 to avoid crowding
                textPaint.color = ContextCompat.getColor(context, R.color.text_muted)
                textPaint.textSize = 24f
                canvas.drawText(monthLabels[i], center, height - 70f, textPaint)
                continue
            }

            var barHeight = (value / maxVal) * graphHeight
            if (barHeight < 8f) barHeight = 8f

            val left = center - barWidth / 2
            val right = center + barWidth / 2
            val top = bottom - barHeight

            val isHighlighted = (i == selectedMonthIndex)
            if (isHighlighted) {
                val shader = LinearGradient(0f, top, 0f, bottom,
                    intArrayOf(ContextCompat.getColor(context, R.color.primary_light), ContextCompat.getColor(context, R.color.primary_purple)),
                    null, Shader.TileMode.CLAMP)
                highlightPaint.shader = shader
                canvas.drawRoundRect(RectF(left, top, right, bottom), barRadius, barRadius, highlightPaint)
            } else {
                barPaint.shader = null
                barPaint.color = Color.parseColor("#D9D9D9")
                canvas.drawRoundRect(RectF(left, top, right, bottom), barRadius, barRadius, barPaint)
            }

            textPaint.color = ContextCompat.getColor(context, R.color.text_primary)
            val amountStr = "₹${value.toInt()}"
            var amountSize = 26f
            textPaint.textSize = amountSize
            while (textPaint.measureText(amountStr) > spacing - 8f && amountSize > 14f) {
                amountSize -= 1f
                textPaint.textSize = amountSize
            }
            // Calculate safe label Y: push up if it would overlap a neighbour bar
            var labelY = top - 20f
            listOf(i - 1, i + 1).forEach { ni ->
                if (ni in 0 until 12 && monthlyTotals[ni] > 0f) {
                    val nBarHeight = ((monthlyTotals[ni] / maxVal) * graphHeight).coerceAtLeast(8f)
                    val nTop = bottom - nBarHeight
                    if (labelY > nTop) labelY = nTop - 20f
                }
            }
            canvas.drawText(amountStr, center, labelY, textPaint)

            textPaint.color = ContextCompat.getColor(context, R.color.text_primary)
            val labelStr = monthLabels[i]
            var labelSize = 24f
            textPaint.textSize = labelSize
            while (textPaint.measureText(labelStr) > spacing - 8f && labelSize > 14f) {
                labelSize -= 1f
                textPaint.textSize = labelSize
            }
            canvas.drawText(labelStr, center, height - 70f, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true

        val barWidth = width / 28f
        val spacing = width / 13f

        for (i in 0 until 12) {
            val center = spacing * (i + 1)
            val left = center - barWidth / 2
            val right = center + barWidth / 2

            if (event.x in left..right) {
                selectedMonthIndex = i
                invalidate()
                onMonthClick?.invoke(i)
                return true
            }
        }
        return true
    }
}
