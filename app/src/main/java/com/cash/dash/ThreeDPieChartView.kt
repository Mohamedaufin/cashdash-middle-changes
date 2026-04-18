package com.cash.dash

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class ThreeDPieChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var data: List<HistoryReportGenerator.CategorySummary> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 70
        maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
    }
    
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 34f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    internal val colorPalette = intArrayOf(
        Color.parseColor("#7C5CFC"), // Primary Purple
        Color.parseColor("#FCA311"), // Vibrant Gold
        Color.parseColor("#00F5FF"), // Cyan
        Color.parseColor("#FF4D6D"), // Crimson
        Color.parseColor("#70E000")  // Lime
    )

    private val pieRect = RectF()
    private val thickness = 45f
    
    fun setData(newData: List<HistoryReportGenerator.CategorySummary>) {
        data = newData
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (data.isEmpty()) return

        val centerX = width / 2f
        val centerY = height / 2f
        val radiusX = width * 0.35f
        val radiusY = radiusX * 0.55f // Compressed for perspective
        val thickness = 45f // Volumetric height

        val colorPalette = intArrayOf(
            Color.parseColor("#7C5CFC"), Color.parseColor("#FCA311"), 
            Color.parseColor("#00F5FF"), Color.parseColor("#FF4D6D"), Color.parseColor("#70E000")
        )

        val rect = RectF(centerX - radiusX, centerY - radiusY, centerX + radiusX, centerY + radiusY)
        
        // 1. FLOOR SHADOW (Deep Ambient Occlusion)
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            alpha = 80
            maskFilter = BlurMaskFilter(25f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawOval(centerX - radiusX * 1.1f, centerY + thickness + radiusY * 0.3f, 
                        centerX + radiusX * 1.1f, centerY + thickness + radiusY * 0.7f, shadowPaint)

        // 2. DRAW VOLUMETRIC SIDES
        var startAngle = 0f
        data.forEachIndexed { i, summary ->
            val sweep = (summary.percentage / 100f) * 360f
            val baseColor = colorPalette[i % colorPalette.size]
            
            // Side Paint (Darker with procedural rim light)
            val hsv = FloatArray(3)
            Color.colorToHSV(baseColor, hsv)
            hsv[2] *= 0.5f // Darken for depth
            val sideColor = Color.HSVToColor(hsv)
            
            paint.color = sideColor
            for (step in 1..thickness.toInt() step 2) {
                val sideRect = RectF(rect.left, rect.top + step, rect.right, rect.bottom + step)
                canvas.drawArc(sideRect, startAngle, sweep, true, paint)
                
                // Rim Light on the very edges
                if (step == thickness.toInt() - 1) {
                    val rimPaint = Paint(paint).apply { 
                        alpha = 40 
                        style = Paint.Style.STROKE
                        strokeWidth = 2f
                        color = Color.WHITE
                    }
                    canvas.drawArc(sideRect, startAngle, sweep, true, rimPaint)
                }
            }
            startAngle += sweep
        }

        // 3. DRAW TOP FACES WITH GLOSS
        startAngle = 0f
        data.forEachIndexed { i, summary ->
            val sweep = (summary.percentage / 100f) * 360f
            val baseColor = colorPalette[i % colorPalette.size]
            
            paint.style = Paint.Style.FILL
            paint.color = baseColor
            canvas.drawArc(rect, startAngle, sweep, true, paint)

            // Glossy Highlight (Specular)
            val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(centerX, centerY - radiusY, centerX, centerY, 
                    Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                alpha = 40
            }
            canvas.drawArc(rect, startAngle, sweep, true, glossPaint)

            // Percentage Text
            if (summary.percentage > 5) {
                val midAngle = startAngle + sweep / 2f
                val rad = Math.toRadians(midAngle.toDouble())
                val labelX = centerX + (radiusX * 0.7f) * Math.cos(rad).toFloat()
                val labelY = centerY + (radiusY * 0.7f) * Math.sin(rad).toFloat()
                
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 18f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("${summary.percentage.toInt()}%", labelX, labelY, textPaint)
            }
            
            startAngle += sweep
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return false
    }
}
