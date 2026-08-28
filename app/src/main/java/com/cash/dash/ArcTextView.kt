package com.cash.dash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Draws a single line of text bent around a circle.
 *
 * Used for the brand mark that curves over the archway in the intro's first scene. The
 * alternative was baking the lettering into the arch as path data, which would have frozen
 * both the wording and the typeface into a drawable.
 *
 * The circle is described in fractions of the view box rather than pixels, and the radius is
 * a fraction of the WIDTH specifically, so the arc stays circular no matter what aspect the
 * view ends up with. That matters here: this view is stretched to the archway ImageView's
 * bounds, which are 130x150, and a radius derived from both axes would come out as an
 * ellipse that no longer follows the arch.
 */
class ArcTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var pathLength = 0f

    var text: String = ""
        set(value) {
            field = value
            invalidate()
        }

    private var centerXFraction = 0.5f
    private var centerYFraction = 0.5f
    private var radiusFraction = 0.4f
    private var sweepDegrees = 150f

    init {
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        paint.style = Paint.Style.FILL

        val a = context.obtainStyledAttributes(attrs, R.styleable.ArcTextView)
        text = a.getString(R.styleable.ArcTextView_arcText) ?: ""
        paint.textSize = a.getDimension(R.styleable.ArcTextView_arcTextSize, 40f)
        paint.color = a.getColor(R.styleable.ArcTextView_arcTextColor, Color.BLACK)
        paint.letterSpacing = a.getFloat(R.styleable.ArcTextView_arcLetterSpacing, 0.06f)
        centerXFraction = a.getFloat(R.styleable.ArcTextView_arcCenterX, 0.5f)
        centerYFraction = a.getFloat(R.styleable.ArcTextView_arcCenterY, 0.5f)
        radiusFraction = a.getFloat(R.styleable.ArcTextView_arcRadius, 0.4f)
        sweepDegrees = a.getFloat(R.styleable.ArcTextView_arcSweep, 150f)
        a.recycle()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        path.reset()
        pathLength = 0f
        if (w <= 0 || h <= 0) return

        val cx = w * centerXFraction
        val cy = h * centerYFraction
        val r = w * radiusFraction

        // Centred on twelve o'clock, running left to right over the top, which is the one
        // direction that leaves the glyphs upright.
        path.addArc(RectF(cx - r, cy - r, cx + r, cy + r), 270f - sweepDegrees / 2f, sweepDegrees)
        pathLength = PathMeasure(path, false).length
    }

    override fun onDraw(canvas: Canvas) {
        if (text.isEmpty() || pathLength <= 0f) return
        val hOffset = (pathLength - paint.measureText(text)) / 2f
        canvas.drawTextOnPath(text, path, hOffset, 0f, paint)
    }
}
