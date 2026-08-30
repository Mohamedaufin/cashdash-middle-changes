package com.cash.dash

import android.animation.ValueAnimator
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Page 2: Simple in-app payment flow:
 * 1. Scan happening
 * 2. Amount already prefilled (₹100)
 * 3. Pay button clicked
 * 4. Choose Allocation sheet slides up
 */
class IntroScanScene @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), IntroScene {

    private val frame: IntroScannerFrameView
    private val phoneContainer: View
    private val touchPointer: View

    private val amountGroup: View
    private val amountField: TextView
    private val payBtn: TextView

    private val allocGroup: View
    private val allocFood: View
    private val allocShopping: View

    private var timeline: ValueAnimator? = null

    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0

    init {
        clipChildren = false
        clipToPadding = false
        LayoutInflater.from(context).inflate(R.layout.view_intro_scene_scan, this, true)
        frame = findViewById(R.id.introScanFrame)
        phoneContainer = findViewById(R.id.introScanPhone)
        touchPointer = findViewById(R.id.introScanTouchPointer)

        amountGroup = findViewById(R.id.introScanAmountGroup)
        amountField = findViewById(R.id.introScanAmountField)
        payBtn = findViewById(R.id.introScanPayBtn)

        allocGroup = findViewById(R.id.introScanAllocGroup)
        allocFood = findViewById(R.id.introScanAllocFood)
        allocShopping = findViewById(R.id.introScanAllocShopping)
    }

    override fun resetScene() {
        generation++
        handler.removeCallbacksAndMessages(null)
        timeline?.cancel(); timeline = null

        val all = listOf(phoneContainer, frame, touchPointer, amountGroup, amountField, payBtn, allocGroup, allocFood, allocShopping)
        for (v in all) v.animate().cancel()

        // Scanner (Centered)
        frame.alpha = 1f
        frame.scaleX = 1f
        frame.scaleY = 1f
        frame.bracketProgress = 0f
        frame.moduleProgress = 0f
        frame.lockProgress = 0f
        frame.scanLineProgress = 0f

        // Touch pointer
        touchPointer.alpha = 0f
        touchPointer.scaleX = 1f
        touchPointer.scaleY = 1f

        // Sheet 1: Amount Entry (starts off-screen below bottom edge, with prefilled ₹100)
        amountGroup.alpha = 1f
        amountGroup.translationY = SHEET_SLIDE_DP.dp
        amountField.text = "100"
        payBtn.alpha = 1f
        payBtn.text = "Pay ₹100"

        // Sheet 2: Allocation Chooser (starts off-screen below bottom edge)
        allocGroup.alpha = 1f
        allocGroup.translationY = SHEET_SLIDE_DP.dp
        allocFood.alpha = 1f
        allocShopping.alpha = 1f
    }

    private fun schedule(delayMs: Long, action: () -> Unit) {
        val gen = generation
        handler.postDelayed({
            if (gen == generation) action()
        }, delayMs)
    }

    private fun simulateTap(targetView: View, onTapped: () -> Unit) {
        targetView.post {
            val targetLoc = IntArray(2)
            targetView.getLocationInWindow(targetLoc)
            val parentLoc = IntArray(2)
            phoneContainer.getLocationInWindow(parentLoc)

            val targetTipX = (targetLoc[0] - parentLoc[0]) + (targetView.width * 0.45f)
            val targetTipY = (targetLoc[1] - parentLoc[1]) + (targetView.height * 0.45f)

            touchPointer.pivotX = 0f
            touchPointer.pivotY = 0f
            touchPointer.translationX = targetTipX + 16f.dp
            touchPointer.translationY = targetTipY + 16f.dp
            touchPointer.scaleX = 1f
            touchPointer.scaleY = 1f
            touchPointer.alpha = 0f

            // Desktop cursor glides smoothly into place
            touchPointer.animate()
                .alpha(1f)
                .translationX(targetTipX)
                .translationY(targetTipY)
                .setDuration(260)
                .setInterpolator(IntroTourActivity.EASE_OUT)
                .withEndAction {
                    // Click down
                    touchPointer.animate().scaleX(0.85f).scaleY(0.85f)
                        .setDuration(80)
                        .withEndAction {
                            onTapped()
                            // Release & fade
                            touchPointer.animate().scaleX(1f).scaleY(1f)
                                .setDuration(120)
                                .withEndAction {
                                    touchPointer.animate().alpha(0f).setDuration(240).start()
                                }
                                .start()
                        }
                        .start()
                }
                .start()
        }
    }

    override fun playScene() {
        // ── 1. Scanning happening (0 -> 1600ms) ──────────────────────────────────
        timeline = ValueAnimator.ofFloat(0f, SCAN_MS.toFloat()).apply {
            duration = SCAN_MS
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                frame.bracketProgress = span(t, 0f, 500f, IntroTourActivity.EASE_OUT)
                frame.moduleProgress = span(t, 300f, 1000f, null)
                frame.scanLineProgress = if (t < SCAN_MS - 200f) (t % 500f) / 500f else 1f
                frame.lockProgress = when {
                    t < SCAN_MS - 200f -> 0f
                    t < SCAN_MS - 100f -> (t - (SCAN_MS - 200f)) / 100f
                    t < SCAN_MS -> 1f - (t - (SCAN_MS - 100f)) / 100f
                    else -> 0f
                }
            }
            start()
        }

        // ── 2. Amount sheet slides UP (already prefilled ₹100) ───────────────────
        val sheet1Start = 1800L
        schedule(sheet1Start) {
            amountGroup.animate().translationY(0f)
                .setDuration(400)
                .setInterpolator(IntroTourActivity.EASE_OUT).start()
        }

        // ── 3. Pay button clicked by cursor ─────────────────────────────────────
        val payClickStart = sheet1Start + 400 + 800L
        schedule(payClickStart) {
            simulateTap(payBtn) {
                payBtn.animate().scaleX(0.94f).scaleY(0.94f)
                    .setDuration(100)
                    .withEndAction {
                        payBtn.animate().scaleX(1f).scaleY(1f)
                            .setDuration(100)
                            .setInterpolator(IntroTourActivity.EASE_OUT).start()
                    }
                    .setInterpolator(IntroTourActivity.EASE_IN).start()
            }
        }

        // ── 4. Choose Allocation sheet slides UP ─────────────────────────────────
        val allocStart = payClickStart + 350L
        schedule(allocStart) {
            allocGroup.animate().translationY(0f)
                .setDuration(420)
                .setInterpolator(IntroTourActivity.EASE_OUT)
                .withEndAction {
                    amountGroup.translationY = SHEET_SLIDE_DP.dp
                    amountGroup.alpha = 0f
                }
                .start()
        }
    }

    override fun sceneDurationMs(): Long {
        return 7500L
    }

    /** Maps [t] onto 0..1 across [from]..[to], clamped, optionally eased. */
    private fun span(t: Float, from: Float, to: Float, ease: android.view.animation.Interpolator?): Float {
        val f = ((t - from) / (to - from)).coerceIn(0f, 1f)
        return ease?.getInterpolation(f) ?: f
    }

    private val Float.dp: Float get() = this * resources.displayMetrics.density

    private companion object {
        const val SHEET_SLIDE_DP = 360f
        const val SCAN_MS = 1600L
    }
}
