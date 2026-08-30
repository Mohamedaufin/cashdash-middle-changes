package com.cash.dash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * The wallet ring on the intro tour's first scene.
 *
 * This is [GradientCircularProgressView] — the ring Home puts the wallet balance inside —
 * restated at intro size and in one colour. Same round cap, same start at twelve o'clock,
 * same proportion of stroke to diameter, so someone who sees this and then reaches Home
 * recognises the ring rather than meeting a second one.
 *
 * ### Why one colour and not the sweep
 *
 * The Home ring can be configured, and its default is a cyan-to-pink sweep. This is not that,
 * for two reasons. The app is monochrome everywhere else — white line icons, white type,
 * colour reserved for the few places it carries meaning, like red on money going out — so a
 * four-hue gradient on the first screen promises a look the rest of the app does not have.
 * And the ring here has no data behind it: it is decoration, and decoration should be the
 * quietest thing that still reads.
 *
 * So it fills from ?attr/primaryActionBackground over ?attr/progressTrackColor: the app's own
 * highest-contrast pair on each theme, off-white over charcoal on the dark ones and near-black
 * over pale grey on White. That is the same pairing the primary button uses.
 *
 * ### Why the Home view is not reused directly
 *
 * It fixes its stroke at 45dp, right for a ring up to 340dp across and far too heavy for one
 * that has to sit in an intro; it drives its own animator, where everything in a scene has to
 * move on the scene's clock; and it draws on a software layer for a shadow this does not have.
 * The stroke is therefore a FRACTION of the diameter — [STROKE_FRACTION] is Home's own 45-to-340
 * — which is what keeps the two reading as one ring at two sizes.
 */
class IntroWalletRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ThemeHelper.resolveColorAttr(context, R.attr.progressTrackColor)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ThemeHelper.resolveColorAttr(context, R.attr.primaryActionBackground)
    }

    private val bounds = RectF()

    /** 0 draws the bare track, 1 closes the ring. Clamped, so callers can overshoot safely. */
    var progress: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field == clamped) return
            field = clamped
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val size = min(w, h).toFloat()
        val stroke = size * STROKE_FRACTION
        trackPaint.strokeWidth = stroke
        arcPaint.strokeWidth = stroke

        val inset = stroke / 2f
        val left = (w - size) / 2f + inset
        val top = (h - size) / 2f + inset
        bounds.set(left, top, left + size - stroke, top + size - stroke)
    }

    override fun onDraw(canvas: Canvas) {
        if (bounds.isEmpty) return
        canvas.drawOval(bounds, trackPaint)
        // Skipped entirely at zero: a round cap still paints a dot at a sweep of nothing.
        if (progress <= 0f) return
        canvas.drawArc(bounds, START_ANGLE, 360f * progress, false, arcPaint)
    }

    private companion object {
        /** Home's 45dp stroke over its 340dp maximum, held as a ratio. */
        const val STROKE_FRACTION = 45f / 340f
        /** Twelve o'clock, as on Home. */
        const val START_ANGLE = 270f
    }
}
