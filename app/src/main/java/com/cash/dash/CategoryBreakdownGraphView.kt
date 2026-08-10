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
    
    private var currentPage = 0
    private val ITEMS_PER_PAGE = 4

    fun setData(catList: List<String>, valueList: List<Float>) {
        categories = catList
        values = valueList
        currentPage = 0
        invalidate()
    }

    private var initialX = 0f
    private val SWIPE_THRESHOLD = 150f

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        val totalPages = Math.ceil(categories.size / ITEMS_PER_PAGE.toDouble()).toInt()

        when (event.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                initialX = event.x
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                val deltaX = event.x - initialX

                // Swipe Right (Go back to previous page)
                if (deltaX > SWIPE_THRESHOLD && currentPage > 0) {
                    currentPage--
                    invalidate()
                    return true
                }

                // Swipe Left (Go forward to next page)
                if (deltaX < -SWIPE_THRESHOLD && currentPage < totalPages - 1) {
                    currentPage++
                    invalidate()
                    return true
                }

                // Tap on arrows
                if (Math.abs(deltaX) < 50f) {
                    if (currentPage > 0 && event.x < 120f) {
                        currentPage--
                        invalidate()
                        return true
                    }
                    if (currentPage < totalPages - 1 && event.x > width - 120f) {
                        currentPage++
                        invalidate()
                        return true
                    }
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (categories.isEmpty() || values.isEmpty()) return

        val totalPages = Math.ceil(categories.size / ITEMS_PER_PAGE.toDouble()).toInt()
        val pageStart = currentPage * ITEMS_PER_PAGE
        val pageEnd = Math.min(pageStart + ITEMS_PER_PAGE, categories.size)

        val pageCategories = categories.subList(pageStart, pageEnd)
        val pageValues = values.subList(pageStart, pageEnd)

        val count = pageValues.size
        val maxVal = values.maxOrNull()?.takeIf { it > 0 } ?: 1f

        val barWidth = width / 14f
        val spacing = width / (count + 1f)

        val bottom = height - 140f
        val graphHeight = height - 220f
        
        // Draw pagination arrows
        val arrowY = height / 2f
        val arrowPaint = Paint(textPaint).apply {
            textSize = context.resources.getDimension(R.dimen.text_title) * 1.5f
            color = ThemeHelper.resolveColorAttr(context, R.attr.textMutedColor)
        }
        
        if (currentPage > 0) {
            canvas.drawText("<", 60f, arrowY, arrowPaint)
        }
        if (currentPage < totalPages - 1) {
            canvas.drawText(">", width - 60f, arrowY, arrowPaint)
        }

        for (i in pageValues.indices) {

            val value = pageValues[i]
            val center = spacing * (i + 1)

            val rawLabel = pageCategories[i]
            val labelStr = if (rawLabel.equals("no choice", ignoreCase = true)) "No Allocation" else rawLabel
            
            val labelSize = context.resources.getDimension(R.dimen.graph_text_body)
            textPaint.textSize = labelSize

            val words = labelStr.split(" ")
            var line1 = labelStr
            var line2 = ""

            if (textPaint.measureText(labelStr) > spacing - 4f && words.size > 1) {
                val mid = (words.size + 1) / 2
                line1 = words.subList(0, mid).joinToString(" ")
                line2 = words.subList(mid, words.size).joinToString(" ")
            }

            line1 = android.text.TextUtils.ellipsize(line1, android.text.TextPaint(textPaint), spacing - 4f, android.text.TextUtils.TruncateAt.END).toString()
            if (line2.isNotEmpty()) {
                line2 = android.text.TextUtils.ellipsize(line2, android.text.TextPaint(textPaint), spacing - 4f, android.text.TextUtils.TruncateAt.END).toString()
            }

            if (value == 0f) {
                textPaint.color = ThemeHelper.resolveColorAttr(context, R.attr.textMutedColor)
                canvas.drawText("₹0", center, bottom - 25f, textPaint)
                
                textPaint.color = if (ThemeHelper.isWhiteTheme(context)) 
                    ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor) 
                    else ThemeHelper.resolveColorAttr(context, R.attr.textMutedColor)
                val labelY = height - 70f
                if (line2.isEmpty()) {
                    canvas.drawText(line1, center, labelY, textPaint)
                } else {
                    canvas.drawText(line1, center, labelY, textPaint)
                    canvas.drawText(line2, center, labelY + labelSize + 4f, textPaint)
                }
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
            textPaint.textSize = labelSize
            val labelY = height - 70f
            if (line2.isEmpty()) {
                canvas.drawText(line1, center, labelY, textPaint)
            } else {
                canvas.drawText(line1, center, labelY, textPaint)
                canvas.drawText(line2, center, labelY + labelSize + 4f, textPaint)
            }
        }
    }
}
