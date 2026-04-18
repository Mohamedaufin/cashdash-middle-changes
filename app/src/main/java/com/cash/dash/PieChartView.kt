package com.cash.dash

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

class PieChartView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    data class Slice(
        val category: String,
        val value: Float,
        val percentage: Float,
        val color: Int,
        var startAngle: Float = 0f,
        var sweepAngle: Float = 0f
    )

    private val slices = mutableListOf<Slice>()
    private var totalValue = 0f
    private var animationProgress = 0f
    private var selectedIndex = -1
    
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    val colorPalette = intArrayOf(
        Color.parseColor("#7C5CFC"), // Primary Purple
        Color.parseColor("#4DE1C1"), // Cyan
        Color.parseColor("#64B5F6"), // Blue
        Color.parseColor("#FFD54F"), // Amber
        Color.parseColor("#FF7043"), // Deep Orange
        Color.parseColor("#BA68C8"), // Purple Light
        Color.parseColor("#4DB6AC"), // Teal
        Color.parseColor("#90A4AE")  // Blue Grey
    )

    fun setData(data: List<HistoryReportGenerator.CategorySummary>) {
        slices.clear()
        totalValue = data.sumOf { it.amount.toDouble() }.toFloat()
        
        var currentAngle = -90f // Start from top
        data.forEachIndexed { index, summary ->
            val sweep = (summary.amount / totalValue) * 360f
            slices.add(Slice(
                category = summary.category,
                value = summary.amount,
                percentage = summary.percentage,
                color = colorPalette[index % colorPalette.size],
                startAngle = currentAngle,
                sweepAngle = sweep
            ))
            currentAngle += sweep
        }
        selectedIndex = -1
        animateIn()
    }

    private fun animateIn() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                animationProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (slices.isEmpty()) return

        val centerX = width / 2f
        val centerY = height / 2f
        val radius = Math.min(width, height) / 2.5f
        val strokeWidth = radius * 0.4f
        
        paint.strokeWidth = strokeWidth
        val rect = RectF(
            centerX - radius, centerY - radius,
            centerX + radius, centerY + radius
        )

        // Draw Slices
        slices.forEachIndexed { index, slice ->
            paint.color = slice.color
            if (index == selectedIndex) {
                paint.alpha = 255
                // Slightly larger radius for selected
                val extra = 15f * animationProgress
                val bigRect = RectF(rect.left - extra, rect.top - extra, rect.right + extra, rect.bottom + extra)
                canvas.drawArc(bigRect, slice.startAngle, slice.sweepAngle * animationProgress, false, paint)
            } else {
                paint.alpha = if (selectedIndex == -1) 255 else 100
                canvas.drawArc(rect, slice.startAngle, slice.sweepAngle * animationProgress, false, paint)
            }
        }

        // Center Text (Insights)
        if (selectedIndex != -1) {
            val slice = slices[selectedIndex]
            textPaint.color = ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor)
            
            // Category Name
            textPaint.textSize = radius * 0.15f
            canvas.drawText(slice.category.uppercase(), centerX, centerY - 10f, textPaint)
            
            // Percentage/Amount
            textPaint.textSize = radius * 0.25f
            canvas.drawText("${slice.percentage.toInt()}%", centerX, centerY + radius * 0.2f, textPaint)
            
            textPaint.textSize = radius * 0.12f
            textPaint.typeface = Typeface.DEFAULT
            canvas.drawText("₹${slice.value.toInt()}", centerX, centerY + radius * 0.35f, textPaint)
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        } else {
            // Default Center Text
            textPaint.color = ThemeHelper.resolveColorAttr(context, R.attr.textMutedColor)
            textPaint.textSize = radius * 0.12f
            canvas.drawText("TAP TO", centerX, centerY - 15f, textPaint)
            textPaint.textSize = radius * 0.18f
            textPaint.color = ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor)
            canvas.drawText("EXPLORE", centerX, centerY + 25f, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val x = event.x - width / 2f
            val y = event.y - height / 2f
            val distance = Math.sqrt((x * x + y * y).toDouble())
            
            val radius = Math.min(width, height) / 2.5f
            val innerRadius = radius * 0.6f
            val outerRadius = radius * 1.4f
            
            if (distance in innerRadius..outerRadius) {
                var angle = Math.toDegrees(Math.atan2(y.toDouble(), x.toDouble())).toFloat()
                if (angle < -90) angle += 360f
                
                slices.forEachIndexed { index, slice ->
                    if (angle >= slice.startAngle && angle < slice.startAngle + slice.sweepAngle) {
                        selectedIndex = if (selectedIndex == index) -1 else index
                        invalidate()
                        return true
                    }
                }
            } else {
                selectedIndex = -1
                invalidate()
            }
        }
        return true
    }
}
