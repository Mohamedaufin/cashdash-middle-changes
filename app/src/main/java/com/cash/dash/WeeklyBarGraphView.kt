package com.cash.dash

import android.content.Context
import android.graphics.*
import androidx.core.content.ContextCompat
import android.util.AttributeSet
import android.view.View

class WeeklyBarGraphView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var weekValues: List<Float> = listOf(0f, 0f, 0f, 0f)
    private val weekLabels = mutableListOf("W1", "W2", "W3", "W4")

    private var limitValue: Float = -1f

    private val barPaint = Paint().apply {
        isAntiAlias = true
    }

    private val highlightPaint = Paint().apply {
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = 32f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val limitPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val limitTextPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = 28f
        textAlign = Paint.Align.LEFT
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }

    private val barRadius = 40f

    fun setValues(values: List<Float>) {
        weekValues = values
        invalidate()
    }

    fun setLimit(limit: Int) {
        limitValue = limit.toFloat()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val maxVal = maxOf(
            limitValue.takeIf { it > 0 } ?: 0f,
            weekValues.maxOrNull() ?: 0f
        ).takeIf { it > 0 } ?: 1f

        val barCount = weekValues.size
        val availableWidth = width - paddingLeft - paddingRight
        val barWidth = availableWidth / 14f
        val spacing = availableWidth / (barCount + 1f)

        val bottom = height - 100f 
        val graphHeight = height - 260f 
        
        if (limitValue > 0) {
            val ratio = limitValue / maxVal
            var limitY = bottom - (ratio * graphHeight)

            if (limitY < 120f) limitY = 120f 
            if (limitY > bottom) limitY = bottom

            canvas.drawLine(paddingLeft.toFloat(), limitY, (width - paddingRight).toFloat(), limitY, limitPaint)
        }

        for (i in 0 until barCount) {
            val value = weekValues[i]
            val center = paddingLeft + (spacing * (i + 1))
            
            textPaint.textAlign = Paint.Align.CENTER

            if (value == 0f) {
                val label = if (i < weekLabels.size) weekLabels[i] else "W${i+1}"
                
                textPaint.typeface = Typeface.DEFAULT_BOLD
                textPaint.textSize = 38f
                canvas.drawText("₹0", center, bottom - 30f, textPaint)
                
                textPaint.typeface = Typeface.DEFAULT
                textPaint.textSize = if (label.length > 8) 24f else 32f
                canvas.drawText(label, center, height - 30f, textPaint)
                continue
            }

            var barHeight = (value / maxVal) * graphHeight
            if (barHeight < 25f) barHeight = 25f

            val left = center - barWidth / 2
            val right = center + barWidth / 2
            val top = bottom - barHeight

            val isOverLimit = limitValue > 0 && value >= limitValue
            if (isOverLimit) {
                val shader = LinearGradient(0f, top, 0f, bottom,
                    intArrayOf(Color.parseColor("#FF6B6B"), Color.parseColor("#D32F2F")),
                    null, Shader.TileMode.CLAMP)
                highlightPaint.shader = shader
                canvas.drawRoundRect(RectF(left, top, right, bottom), barRadius, barRadius, highlightPaint)
            } else {
                barPaint.shader = null
                barPaint.color = Color.parseColor("#D9D9D9")
                canvas.drawRoundRect(RectF(left, top, right, bottom), barRadius, barRadius, barPaint)
            }
            barPaint.shader = null

            val glossPaint = Paint().apply {
                isAntiAlias = true
                shader = LinearGradient(
                    center, top, center, top + barHeight * 0.5f,
                    Color.argb(100, 255, 255, 255), Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(
                RectF(left + 2f, top + 2f, right - 2f, top + barHeight * 0.4f),
                barRadius, barRadius,
                glossPaint
            )

            // Value Text (Amount)
            textPaint.textSize = 42f 
            textPaint.typeface = Typeface.DEFAULT_BOLD
            textPaint.color = ContextCompat.getColor(context, R.color.text_primary)
            canvas.drawText("₹${value.toInt()}", center, top - 25f, textPaint)

            // Label Text (Date)
            val label = if (i < weekLabels.size) weekLabels[i] else "W${i+1}"
            textPaint.textSize = if (label.length > 8) 24f else 32f
            textPaint.typeface = Typeface.DEFAULT
            textPaint.color = ContextCompat.getColor(context, R.color.text_primary)
            canvas.drawText(label, center, height - 30f, textPaint)
        }
    }

    fun setLabels(labels: List<String>) {
        weekLabels.clear()
        weekLabels.addAll(labels)
        invalidate()
    }
}
