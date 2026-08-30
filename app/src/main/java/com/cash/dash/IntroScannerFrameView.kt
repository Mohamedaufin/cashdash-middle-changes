package com.cash.dash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * The viewfinder on the intro's scan page: four corner brackets that draw themselves, a block
 * of QR modules that resolves inside them, and the inward snap a scanner makes when it
 * actually acquires a code.
 *
 * Three independent properties rather than one clock, because the beats overlap: the modules
 * begin arriving while the brackets are still drawing, and the lock fires against both.
 *
 * ### Why brackets and not a rectangle with a travelling line
 *
 * Four corners is what ScannerActivity actually draws, and it is what every camera in the
 * world draws. A line sweeping down the frame is the film version of scanning; it is not
 * feedback, because it keeps moving whether or not anything was found. The snap in
 * [lockProgress] is feedback — it happens once, at the moment of acquisition, and it is the
 * only thing on this page that reports a result.
 *
 * The real viewfinder is cyan. This one is not: the intro spends its one colour on the
 * over-limit bar two pages later, and a second accent here would spend it early.
 */
class IntroScannerFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor)
    }
    private val modulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    
    private val scanLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.parseColor("#00E5FF") // Cyan scan line
    }

    private val corners = Array(4) { Path() }
    private val drawn = Path()
    private val measure = PathMeasure()

    private val moduleColorDim = ThemeHelper.resolveColorAttr(context, R.attr.introMuted)
    private val moduleColorLit = ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor)

    private var frame = RectF()
    private var moduleSize = 0f
    private var moduleGap = 0f
    private var moduleOrigin = 0f

    /** 0 draws no brackets, 1 draws all four complete. */
    var bracketProgress: Float = 0f
        set(value) {
            val c = value.coerceIn(0f, 1f); if (field == c) return; field = c; invalidate()
        }

    /** Fraction of [MODULES] that have arrived, in [REVEAL_ORDER]. */
    var moduleProgress: Float = 0f
        set(value) {
            val c = value.coerceIn(0f, 1f); if (field == c) return; field = c; invalidate()
        }

    /**
     * The acquisition snap. 0 is resting; 1 is fully closed in by [LOCK_TRAVEL_DP]. The host
     * drives this out and back, and lights the modules with it.
     */
    var lockProgress: Float = 0f
        set(value) {
            val c = value.coerceIn(0f, 1f); if (field == c) return; field = c; invalidate()
        }

    /** The travelling scan line. 0 is the top of the frame, 1 is the bottom. */
    var scanLineProgress: Float = 0f
        set(value) {
            val c = value.coerceIn(0f, 1f); if (field == c) return; field = c; invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val size = min(w, h).toFloat()
        val inset = bracketPaint.strokeWidth / 2f + LOCK_TRAVEL_DP * density
        val left = (w - size) / 2f + inset
        val top = (h - size) / 2f + inset
        frame.set(left, top, left + size - inset * 2f, top + size - inset * 2f)

        // The QR block sits inside the brackets with room to spare, so the corners always
        // read as framing it rather than touching it.
        val inner = frame.width() * 0.58f
        moduleGap = inner / (GRID * MODULE_PITCH)
        moduleSize = moduleGap * MODULE_FILL
        moduleOrigin = frame.centerX() - inner / 2f

        buildCorners()
    }

    /**
     * Each corner is one L drawn from its own outer angle inward, so all four grow away from
     * the corners at once. Built once per size change; the reveal is a trim, not a rebuild.
     */
    private fun buildCorners() {
        val arm = frame.width() * ARM_FRACTION
        val r = CORNER_RADIUS_DP * density

        corners[0].reset()
        corners[0].moveTo(frame.left, frame.top + arm)
        corners[0].lineTo(frame.left, frame.top + r)
        corners[0].quadTo(frame.left, frame.top, frame.left + r, frame.top)
        corners[0].lineTo(frame.left + arm, frame.top)

        corners[1].reset()
        corners[1].moveTo(frame.right - arm, frame.top)
        corners[1].lineTo(frame.right - r, frame.top)
        corners[1].quadTo(frame.right, frame.top, frame.right, frame.top + r)
        corners[1].lineTo(frame.right, frame.top + arm)

        corners[2].reset()
        corners[2].moveTo(frame.right, frame.bottom - arm)
        corners[2].lineTo(frame.right, frame.bottom - r)
        corners[2].quadTo(frame.right, frame.bottom, frame.right - r, frame.bottom)
        corners[2].lineTo(frame.right - arm, frame.bottom)

        corners[3].reset()
        corners[3].moveTo(frame.left + arm, frame.bottom)
        corners[3].lineTo(frame.left + r, frame.bottom)
        corners[3].quadTo(frame.left, frame.bottom, frame.left, frame.bottom - r)
        corners[3].lineTo(frame.left, frame.bottom - arm)
    }

    override fun onDraw(canvas: Canvas) {
        if (frame.isEmpty) return

        if (bracketProgress > 0f) {
            // Positive travel moves each corner towards the centre along both axes, which is
            // the whole of the snap: the frame tightens on what it found.
            val travel = lockProgress * LOCK_TRAVEL_DP * density
            for (i in corners.indices) {
                val dx = if (i == 0 || i == 3) travel else -travel
                val dy = if (i == 0 || i == 1) travel else -travel

                measure.setPath(corners[i], false)
                val len = measure.length
                drawn.reset()
                if (!measure.getSegment(0f, len * bracketProgress, drawn, true)) continue

                canvas.save()
                canvas.translate(dx, dy)
                canvas.drawPath(drawn, bracketPaint)
                canvas.restore()
            }
        }

        if (moduleProgress <= 0f) return
        // Modules light with the lock rather than on arrival, so the block reads as "seen"
        // only once the frame has snapped.
        modulePaint.color = blend(moduleColorDim, moduleColorLit, lockProgress)
        val shown = (REVEAL_ORDER.size * moduleProgress).toInt()
        for (i in 0 until shown) {
            val cell = REVEAL_ORDER[i]
            val col = cell % GRID
            val row = cell / GRID
            if (MODULES[row][col] != '1') continue
            val x = moduleOrigin + col * moduleGap
            val y = frame.centerY() - (GRID * moduleGap) / 2f + row * moduleGap
            canvas.drawRoundRect(
                x, y, x + moduleSize, y + moduleSize,
                MODULE_RADIUS_DP * density, MODULE_RADIUS_DP * density, modulePaint
            )
        }

        if (scanLineProgress > 0f && scanLineProgress < 1f) {
            val y = frame.top + (frame.height() * scanLineProgress)
            // Add a little glow by drawing a thicker line with some alpha
            scanLinePaint.alpha = 60
            scanLinePaint.strokeWidth = 6f * density
            canvas.drawLine(frame.left + 4f * density, y, frame.right - 4f * density, y, scanLinePaint)
            
            // Draw the core bright line
            scanLinePaint.alpha = 255
            scanLinePaint.strokeWidth = 2f * density
            canvas.drawLine(frame.left + 4f * density, y, frame.right - 4f * density, y, scanLinePaint)
        }
    }

    private companion object {
        const val GRID = 5
        const val MODULE_PITCH = 1f
        const val MODULE_FILL = 0.78f
        const val MODULE_RADIUS_DP = 1.5f
        const val ARM_FRACTION = 0.26f
        const val CORNER_RADIUS_DP = 6f
        const val LOCK_TRAVEL_DP = 5f

        /**
         * Not a real QR code and not trying to be — a readable one needs 21x21 modules
         * minimum, which at this size is grey mush. This is the silhouette of one: the three
         * finder squares in the corners that make a QR recognisable at a glance, with data
         * scattered between them.
         */
        val MODULES = arrayOf(
            "11101",
            "10100",
            "10011",
            "00101",
            "11100"
        )

        /**
         * Scattered rather than left-to-right. Reading order looks like a progress bar;
         * out of order looks like recognition.
         */
        val REVEAL_ORDER = intArrayOf(
            0, 12, 4, 20, 7, 2, 18, 10, 24, 5, 14, 1, 22, 9, 16, 3, 11, 23, 6, 19, 8, 13, 21, 15, 17
        )

        fun blend(from: Int, to: Int, f: Float): Int = Color.argb(
            255,
            (Color.red(from) + (Color.red(to) - Color.red(from)) * f).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).toInt()
        )
    }
}
