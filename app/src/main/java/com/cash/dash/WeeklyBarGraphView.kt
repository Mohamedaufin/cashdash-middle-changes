package com.cash.dash

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class WeeklyBarGraphView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val weekValues = mutableListOf<Float>()
    private val weekLabels = mutableListOf<String>()
    private var limitValue: Float = 0f
    private var currentWeekIndex: Int = -1
    private val barRadius = 40f

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
    
    private val limitPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 6f
        isAntiAlias = true
    }



    fun setValues(list: List<Float>) {
        weekValues.clear()
        weekValues.addAll(list)
        invalidate()
    }

    fun setLimit(limit: Float) {
        limitValue = limit
        invalidate()
    }

    fun setCurrentWeekIndex(index: Int) {
        currentWeekIndex = index
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (weekValues.isEmpty()) return
        
        val maxVal = if (limitValue > 0f) {
            (weekValues.maxOrNull() ?: 1f).coerceAtLeast(limitValue)
        } else {
            weekValues.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        }

        val count = weekValues.size
        val availableWidth = width - paddingLeft - paddingRight
        val spacing = availableWidth / (count + 1f)
        val barWidth = availableWidth / 14f

        val bottom = height - 120f
        val graphHeight = height - 260f

        // Draw Limit Line
        if (limitValue > 0f) {
            val limitY = bottom - (limitValue / maxVal * graphHeight)
            
            val pathEffect = DashPathEffect(floatArrayOf(15f, 15f), 0f)
            limitPaint.pathEffect = pathEffect
            
            canvas.drawLine(paddingLeft.toFloat(), limitY, (width - paddingRight).toFloat(), limitY, limitPaint)
        }

        var unifiedAmountSize = context.resources.getDimension(R.dimen.graph_text_subhead)
        if (weekValues.isNotEmpty()) {
            textPaint.textSize = unifiedAmountSize
            textPaint.typeface = Typeface.DEFAULT
            var maxAmount = "₹0"
            var maxLen = 0f
            for (value in weekValues) {
                val str = "₹${value.toInt()}"
                val len = textPaint.measureText(str)
                if (len > maxLen) { maxLen = len; maxAmount = str }
            }
            while (textPaint.measureText(maxAmount) > spacing - 4f && unifiedAmountSize > (8 * resources.displayMetrics.density)) {
                unifiedAmountSize -= 1f
                textPaint.textSize = unifiedAmountSize
            }
        }

        var unifiedLabelSize = context.resources.getDimension(R.dimen.graph_text_body)
        if (weekLabels.isNotEmpty()) {
            textPaint.textSize = unifiedLabelSize
            textPaint.typeface = Typeface.DEFAULT
            var maxLabel = weekLabels[0]
            var maxLen = 0f
            for (lbl in weekLabels) {
               val len = textPaint.measureText(lbl)
               if (len > maxLen) { maxLen = len; maxLabel = lbl }
            }
            while (textPaint.measureText(maxLabel) > spacing - 4f && unifiedLabelSize > (6 * resources.displayMetrics.density)) {
                unifiedLabelSize -= 1f
                textPaint.textSize = unifiedLabelSize
            }
        }

        for (i in weekValues.indices) {
            val value = weekValues[i]
            val center = paddingLeft + (spacing * (i + 1))

            if (value == 0f) {
                val label = if (i < weekLabels.size) weekLabels[i] else "W${i+1}"
                
                textPaint.textSize = unifiedAmountSize
                textPaint.typeface = Typeface.DEFAULT
                textPaint.color = ThemeHelper.resolveColorAttr(context, R.attr.textMutedColor)
                canvas.drawText("₹0", center, bottom - 30f, textPaint)
                
                textPaint.typeface = Typeface.DEFAULT
                val labelStr = if (i < weekLabels.size) weekLabels[i] else "W${i+1}"
                
                
                textPaint.textSize = unifiedLabelSize
                
                val themeName = com.cash.dash.ThemeHelper.getCurrentTheme(context)
                val color1 = when(themeName) {
                    "White" -> Color.parseColor("#1A73E8") // Vibrant Blue
                    "Blue" -> Color.parseColor("#64B5F6")
                    else -> Color.parseColor("#4DB6AC")
                }
                val color2 = when(themeName) {
                    "White" -> Color.parseColor("#8E24AA") // Vibrant Purple
                    "Blue" -> Color.parseColor("#CE93D8")
                    else -> Color.parseColor("#7986CB")
                }
                textPaint.color = if (i % 2 == 0) color1 else color2
                
                canvas.drawText(labelStr, center, height - 30f, textPaint)
                continue
            }

            var barHeight = (value / maxVal) * graphHeight
            if (barHeight < 15f) barHeight = 15f

            val left = center - barWidth / 2
            val right = center + barWidth / 2
            val top = bottom - barHeight

            val isWhiteTheme = com.cash.dash.ThemeHelper.isWhiteTheme(context)
            val isLimitCrossed = limitValue > 0f && value > limitValue

            val colors = if (isLimitCrossed) {
                intArrayOf(Color.parseColor("#FFA1A1"), Color.parseColor("#FF4D4D"))
            } else if (isWhiteTheme) {
                if (i == currentWeekIndex && value > 0f) {
                    intArrayOf(Color.parseColor("#8BF7E6"), Color.parseColor("#4DE1C1"))
                } else {
                    intArrayOf(Color.parseColor("#D9D9D9"), Color.parseColor("#BDBDBD"))
                }
            } else {
                if (i == currentWeekIndex && value > 0f) {
                    intArrayOf(Color.parseColor("#8BF7E6"), Color.parseColor("#4DE1C1"))
                } else {
                    intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#FFFFFF"))
                }
            }

            val shader = LinearGradient(0f, top, 0f, bottom, colors, null, Shader.TileMode.CLAMP)
            val glossPaint = Paint().apply {
                isAntiAlias = true
                this.shader = shader
            }

            canvas.drawRoundRect(
                RectF(left, top, right, bottom),
                barRadius, barRadius,
                glossPaint
            )

            var amountStr = "₹${value.toInt()}"
            textPaint.textSize = unifiedAmountSize
            textPaint.typeface = Typeface.DEFAULT
            textPaint.color = ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor)
            canvas.drawText(amountStr, center, top - 25f, textPaint)

            // Label Text (Date)
            val labelStr = if (i < weekLabels.size) weekLabels[i] else "W${i+1}"
            textPaint.textSize = unifiedLabelSize
            textPaint.typeface = Typeface.DEFAULT
            val themeName = com.cash.dash.ThemeHelper.getCurrentTheme(context)
            val color1 = when(themeName) {
                "White" -> Color.parseColor("#1A73E8") // Vibrant Blue
                "Blue" -> Color.parseColor("#64B5F6")
                else -> Color.parseColor("#4DB6AC")
            }
            val color2 = when(themeName) {
                "White" -> Color.parseColor("#8E24AA") // Vibrant Purple
                "Blue" -> Color.parseColor("#CE93D8")
                else -> Color.parseColor("#7986CB")
            }
            textPaint.color = if (i % 2 == 0) color1 else color2
            canvas.drawText(labelStr, center, height - 30f, textPaint)
        }
    }

    fun setLabels(labels: List<String>) {
        weekLabels.clear()
        weekLabels.addAll(labels)
        invalidate()
    }
}
