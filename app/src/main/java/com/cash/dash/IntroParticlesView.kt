package com.cash.dash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import java.util.Random
import kotlin.math.floor
import kotlin.math.sin

/**
 * The ambient layer behind the intro: small rings and dots drifting slowly upward.
 *
 * It is one view rather than a couple of dozen animated ImageViews because every particle
 * is a circle, and drawing them costs less than laying them out. Positions are held as
 * fractions of the view box so the field fills any screen without being re-tuned.
 *
 * Drives itself from onDraw via postInvalidateOnAnimation rather than a ValueAnimator, so
 * [start] and [stop] are the whole lifecycle. [stop] rolls the clock forward on resume so a
 * backgrounded activity does not come back to particles that have teleported.
 */
class IntroParticlesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private class Particle(
        val x: Float,
        val y: Float,
        val radiusDp: Float,
        val filled: Boolean,
        /** Fractions of the view's height per second. */
        val speed: Float,
        /** Horizontal wander, as a fraction of the view's width. */
        val sway: Float,
        val phase: Float,
        val alpha: Int
    )

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val particles = ArrayList<Particle>(PARTICLE_COUNT)
    private val density = resources.displayMetrics.density

    private var startedAt = 0L
    private var pausedAt = 0L
    private var running = false

    init {
        val tone = ThemeHelper.resolveColorAttr(context, R.attr.introMuted)
        fillPaint.color = tone
        ringPaint.color = tone
        ringPaint.strokeWidth = 1.4f * density

        // Fixed seed: the field is meant to look designed, not to be different every launch.
        val random = Random(0x1F2E3D4CL)
        repeat(PARTICLE_COUNT) {
            particles += Particle(
                x = random.nextFloat(),
                y = random.nextFloat(),
                radiusDp = 1.6f + random.nextFloat() * 4.2f,
                filled = random.nextInt(3) != 0,
                speed = 0.010f + random.nextFloat() * 0.024f,
                sway = 0.010f + random.nextFloat() * 0.028f,
                phase = random.nextFloat() * TAU,
                alpha = 60 + random.nextInt(120)
            )
        }
    }

    fun start() {
        if (running) return
        val now = SystemClock.uptimeMillis()
        when {
            startedAt == 0L -> startedAt = now
            pausedAt != 0L -> startedAt += now - pausedAt
        }
        pausedAt = 0L
        running = true
        postInvalidateOnAnimation()
    }

    fun stop() {
        if (!running) return
        running = false
        pausedAt = SystemClock.uptimeMillis()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val t = (SystemClock.uptimeMillis() - startedAt) / 1000f
        for (p in particles) {
            // Subtracting drifts upward; the fractional part wraps it back to the bottom.
            val fy = (p.y - t * p.speed).let { it - floor(it) }
            val cx = (p.x + sin(t * 0.5f + p.phase) * p.sway) * w
            val cy = fy * h
            val r = p.radiusDp * density
            if (p.filled) {
                fillPaint.alpha = p.alpha
                canvas.drawCircle(cx, cy, r, fillPaint)
            } else {
                ringPaint.alpha = p.alpha
                canvas.drawCircle(cx, cy, r, ringPaint)
            }
        }

        if (running) postInvalidateOnAnimation()
    }

    private companion object {
        const val PARTICLE_COUNT = 26
        const val TAU = 6.2831855f
    }
}
