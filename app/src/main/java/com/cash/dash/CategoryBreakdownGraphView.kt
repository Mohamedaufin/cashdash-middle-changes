package com.cash.dash

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class CategoryBreakdownGraphView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var categories: List<String> = emptyList()
    private var values: List<Float> = emptyList()

    private val barPaint = Paint().apply {
        color = Color.parseColor("#D9D9D9")
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor)
        textSize = context.resources.getDimension(R.dimen.graph_text_subhead) 
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val barRadius = 40f

    fun setData(catList: List<String>, valueList: List<Float>) {
        categories = catList
        values = valueList
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (categories.isEmpty() || values.isEmpty()) return

        val count = values.size
        val maxVal = values.maxOrNull()?.takeIf { it > 0 } ?: 1f

        val barWidth = width / 14f
        val spacing = width / (count + 1f)

        val bottom = height - 140f
        val graphHeight = height - 220f

        for (i in values.indices) {

            val value = values[i]
            val center = spacing * (i + 1)

            if (value == 0f) {
                textPaint.color = ThemeHelper.resolveColorAttr(context, R.attr.textMutedColor)
                canvas.drawText("₹0", center, bottom - 25f, textPaint)
                
                val labelStr = categories[i]
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

            val isWhiteTheme = com.cash.dash.ThemeHelper.isWhiteTheme(context)
            if (isWhiteTheme) {
                barPaint.shader = null
                barPaint.color = Color.parseColor("#D9D9D9")
            } else {
                val colors = intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#E0E0E0"))
                val shader = LinearGradient(0f, top, 0f, bottom, colors, null, Shader.TileMode.CLAMP)
                barPaint.shader = shader
            }

            canvas.drawRoundRect(
                RectF(left, top, right, bottom),
                barRadius,
                barRadius,
                barPaint
            )

            textPaint.color = ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor)
            var amountStr = "₹${value.toInt()}"
            var amountSize = context.resources.getDimension(R.dimen.graph_text_subhead)
            textPaint.textSize = amountSize
            while (textPaint.measureText(amountStr) > spacing - 4f && amountSize > (9 * resources.displayMetrics.density)) {
                amountSize -= 1f
                textPaint.textSize = amountSize
            }
            canvas.drawText(amountStr, center, top - 20f, textPaint)

            textPaint.color = ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor)
            var labelStr = categories[i]
            var labelSize = context.resources.getDimension(R.dimen.graph_text_body)
            textPaint.textSize = labelSize
            while (textPaint.measureText(labelStr) > spacing - 4f && labelSize > (8 * resources.displayMetrics.density)) {
                labelSize -= 1f
                textPaint.textSize = labelSize
            }
            canvas.drawText(labelStr, center, height - 70f, textPaint)
        }
    }
}
