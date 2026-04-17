package com.cash.dash

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

class GradientCircularProgressView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var progress = 0f
    private val strokeWidthDp = 45f
    private val ringStrokeWidth get() = strokeWidthDp * resources.displayMetrics.density

    private var colorMode: String = "gradient" // "gradient" or "single"
    private var colorType: String = "gradient1" // "gradient1", "gradient2", "purple", "yellow", "red", "green", "black", "white"
    private var currentAnimator: ValueAnimator? = null
    private var currentThemeName: String? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#262626") // Default to Charcoal (Black Theme)
        strokeCap = Paint.Cap.ROUND
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val rectF = RectF()
    private val argbEvaluator = android.animation.ArgbEvaluator()
    private var sweepGradient: SweepGradient? = null
    private val gradientMatrix = Matrix()

    init {
        // Required for shadows drawing properly in some views
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setColorConfig(mode: String, type: String, themeName: String? = null) {
        this.colorMode = mode
        this.colorType = type
        this.currentThemeName = themeName
        if (width > 0 && height > 0) {
            updateGradient(width.toFloat(), height.toFloat())
        }
        invalidate()
    }

    fun startLoadingAnimation() {
        currentAnimator?.cancel()
        progress = 0f
        val isGradient2 = colorMode == "gradient" && colorType == "gradient2"
        val animator = ValueAnimator.ofFloat(0f, 100f)
        
        // "Gradient 2" gets a slow, steady progress (Linear, 4s)
        // Others get the standard snappy feel (Decelerate, 1s)
        if (isGradient2) {
            animator.duration = 3000
            animator.interpolator = android.view.animation.LinearInterpolator()
        } else {
            animator.duration = 1500
            animator.interpolator = DecelerateInterpolator(1.2f)
        }
        
        animator.addUpdateListener { animation ->
            progress = animation.animatedValue as Float
            invalidate()
        }
        currentAnimator = animator
        animator.start()
    }

    fun setProgressCompat(newProgress: Int, animate: Boolean) {
        currentAnimator?.cancel()
        val target = newProgress.toFloat().coerceIn(0f, 100f)
        if (animate) {
            val animator = ValueAnimator.ofFloat(progress, target).apply {
                duration = 1200
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
            }
            currentAnimator = animator
            animator.start()
        } else {
            progress = target
            invalidate()
        }
    }

    private fun updateGradient(w: Float, h: Float) {
        val theme = currentThemeName ?: ThemeHelper.getCurrentTheme(context)
        
        // Dynamic Track Color based on Theme and Compatibility Rules
        var trackColor = when (theme) {
            "Blue" -> Color.parseColor("#08123A")
            "White" -> Color.parseColor("#E2E8F0")
            else -> Color.parseColor("#262626") // Black/Deep Default
        }

        // Specific Visibility Overrides
        if (colorMode == "single") {
            if (colorType == "white") {
                // White progress always needs the dark blue ring for contrast
                trackColor = Color.parseColor("#08123A")
            } else if (colorType == "black" && theme == "Blue") {
                // Black progress on Blue theme needs a white ring
                trackColor = Color.WHITE
            }
        }
        
        trackPaint.color = trackColor
        
        if (colorMode == "single") {
            progressPaint.shader = null
            val color = when (colorType) {
                "purple" -> Color.parseColor("#B65CFF")
                "yellow" -> Color.parseColor("#FFD600")
                "red" -> Color.parseColor("#FF0033")
                "green" -> Color.parseColor("#00E676")
                "black" -> Color.parseColor("#1A1B1F")
                "white" -> Color.parseColor("#E8ECF0")
                else -> Color.parseColor("#B65CFF")
            }
            progressPaint.color = color
            progressPaint.setShadowLayer(15f, 0f, 0f, (color and 0x7FFFFFFF)) 
        } else {
            when (colorType) {
                "gradient2" -> { // Dynamic Health Color (Themed Green -> Yellow -> Red)
                    val green = ThemeHelper.resolveColorAttr(context, R.attr.healthGreen)
                    val yellow = ThemeHelper.resolveColorAttr(context, R.attr.healthYellow)
                    val red = ThemeHelper.resolveColorAttr(context, R.attr.healthRed)

                    val color = when {
                        progress >= 50f -> {
                            val ratio = (progress - 50f) / 50f
                            argbEvaluator.evaluate(ratio, yellow, green) as Int
                        }
                        progress >= 15f -> {
                            val ratio = (progress - 15f) / 35f
                            argbEvaluator.evaluate(ratio, red, yellow) as Int
                        }
                        else -> red
                    }
                    progressPaint.shader = null
                    progressPaint.color = color
                    progressPaint.setShadowLayer(15f, 0f, 0f, (color and 0x7FFFFFFF))
                }
                else -> { // Default Gradient 1
                    val colors = if (progress <= 15f) {
                        intArrayOf(
                            Color.parseColor("#FF0033"), // Deep Red
                            Color.parseColor("#FF5C00"), // Intense Orange
                            Color.parseColor("#FF0033"), // Deep Red
                            Color.parseColor("#FF5C00"), // Intense Orange
                            Color.parseColor("#FF0033")
                        )
                    } else {
                        intArrayOf(
                            Color.parseColor("#00E5FF"), // Neon Cyan
                            Color.parseColor("#4AA3FF"), // Sky Blue
                            Color.parseColor("#B65CFF"), // Electric Purple
                            Color.parseColor("#FF007A"), // Hot Pink
                            Color.parseColor("#00E5FF")
                        )
                    }

                    sweepGradient = SweepGradient(
                        w / 2f, h / 2f,
                        colors,
                        floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
                    )
                    gradientMatrix.setRotate(270f, w / 2f, h / 2f)
                    sweepGradient?.setLocalMatrix(gradientMatrix)
                    progressPaint.shader = sweepGradient
                    
                    val shadowColor = if (progress <= 15f) Color.parseColor("#80FF0033") else Color.parseColor("#80FF007A")
                    progressPaint.setShadowLayer(15f, 0f, 0f, shadowColor)
                }
            }
        }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Ensure ring and shadow are not clipped by adding enough internal padding
        val pad = ringStrokeWidth / 2f + 30f 
        rectF.set(pad, pad, w - pad, h - pad)
        
        trackPaint.strokeWidth = ringStrokeWidth
        progressPaint.strokeWidth = ringStrokeWidth

        updateGradient(w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        if (width > 0 && height > 0) {
            updateGradient(width.toFloat(), height.toFloat())
        }
        super.onDraw(canvas)
        canvas.drawArc(rectF, 0f, 360f, false, trackPaint)
        val sweepAngle = (progress / 100f) * 360f
        if (sweepAngle > 0) {
            canvas.drawArc(rectF, 270f, sweepAngle, false, progressPaint)
        }
    }
}
