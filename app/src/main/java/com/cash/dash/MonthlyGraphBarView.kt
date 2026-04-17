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
        color = ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor)
        textSize = context.resources.getDimension(R.dimen.graph_text_subhead) 
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
                val labelStr = monthLabels[i]
                textPaint.color = if (ThemeHelper.isWhiteTheme(context)) 
                    ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor) 
                    else ThemeHelper.resolveColorAttr(context, R.attr.textMutedColor)
                canvas.drawText(labelStr, center, height - 70f, textPaint)
                continue
            }

            var barHeight = (value / maxVal) * graphHeight
            if (barHeight < 10f) barHeight = 10f

            val left = center - barWidth / 2
            val right = center + barWidth / 2
            val top = bottom - barHeight

            if (i == selectedMonthIndex) {
                 val isWhiteTheme = com.cash.dash.ThemeHelper.isWhiteTheme(context)
                 val colors = if (isWhiteTheme) {
                     intArrayOf(Color.parseColor("#8BF7E6"), Color.parseColor("#4DE1C1"))
                 } else {
                     intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#E0E0E0"))
                 }
                 val shader = LinearGradient(0f, top, 0f, bottom, colors, null, Shader.TileMode.CLAMP)
                highlightPaint.shader = shader
                canvas.drawRoundRect(RectF(left, top, right, bottom), barRadius, barRadius, highlightPaint)
            } else {
                barPaint.shader = null
                barPaint.color = Color.parseColor("#D9D9D9")
                canvas.drawRoundRect(RectF(left, top, right, bottom), barRadius, barRadius, barPaint)
            }

            textPaint.color = ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor)
            var amountStr = "₹${value.toInt()}"
            var amountSize = context.resources.getDimension(R.dimen.graph_text_subhead) 
            textPaint.textSize = amountSize
            while (textPaint.measureText(amountStr) > spacing - 8f && amountSize > (10 * resources.displayMetrics.density)) {
                amountSize -= 1f
                textPaint.textSize = amountSize
            }
            
            val labelY = if (barHeight > 60f) top - 20f else top - 10f
            canvas.drawText(amountStr, center, labelY, textPaint)

            textPaint.color = ContextCompat.getColor(context, R.color.text_primary)
            var labelStr = monthLabels[i]
            var labelSize = context.resources.getDimension(R.dimen.graph_text_body)
            textPaint.textSize = labelSize
            while (textPaint.measureText(labelStr) > spacing - 8f && labelSize > (10 * resources.displayMetrics.density)) {
                labelSize -= 1f
                textPaint.textSize = labelSize
            }
            canvas.drawText(labelStr, center, height - 70f, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val spacing = width / 13f
            for (i in 0 until 12) {
                val center = spacing * (i + 1)
                if (event.x > center - 30f && event.x < center + 30f) {
                    selectedMonthIndex = i
                    onMonthClick?.invoke(i)
                    invalidate()
                    break
                }
            }
        }
        return true
    }
}
