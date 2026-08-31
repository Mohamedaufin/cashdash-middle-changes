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
 * Page 2: In-app payment flow:
 * 1. Scan happening
 * 2. Amount already prefilled (₹100) -> Sheet 1 slides up
 * 3. Pay button clicked -> Sheet 1 slides down, Sheet 2 (Allocation) slides up
 * 4. Cursor clicks 1st allocation (Food) -> Sheet 2 slides down, Sheet 3 (Confirm) slides up
 * 5. Cursor clicks "Pay Now" -> Sheet 3 slides down, Payment Successful appears cleanly
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

    private val confirmGroup: View
    private val finalPayNowBtn: View

    private val successGroup: View

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

        confirmGroup = findViewById(R.id.introScanConfirmGroup)
        finalPayNowBtn = findViewById(R.id.introScanFinalPayNow)

        successGroup = findViewById(R.id.introScanSuccess)
    }

    override fun resetScene() {
        generation++
        handler.removeCallbacksAndMessages(null)
        timeline?.cancel(); timeline = null

        val all = listOf(
            phoneContainer, frame, touchPointer,
            amountGroup, amountField, payBtn,
            allocGroup, allocFood, allocShopping,
            confirmGroup, finalPayNowBtn, successGroup
        )
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

        // Sheet 1: Amount Entry (starts off-screen, prefilled ₹100)
        amountGroup.alpha = 0f
        amountGroup.translationY = SHEET_SLIDE_DP.dp
        amountField.text = "100"
        payBtn.alpha = 1f
        payBtn.text = "Pay ₹100"

        // Sheet 2: Allocation Chooser (starts off-screen)
        allocGroup.alpha = 0f
        allocGroup.translationY = SHEET_SLIDE_DP.dp
        allocFood.alpha = 1f
        allocFood.scaleX = 1f
        allocFood.scaleY = 1f
        allocShopping.alpha = 1f

        // Sheet 3: Confirm & Pay (starts off-screen)
        confirmGroup.alpha = 0f
        confirmGroup.translationY = SHEET_SLIDE_DP.dp
        finalPayNowBtn.scaleX = 1f
        finalPayNowBtn.scaleY = 1f

        // Payment Success
        successGroup.alpha = 0f
        successGroup.scaleX = 0.85f
        successGroup.scaleY = 0.85f
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
                .setDuration(240)
                .setInterpolator(IntroTourActivity.EASE_OUT)
                .withEndAction {
                    // Click down
                    touchPointer.animate().scaleX(0.85f).scaleY(0.85f)
                        .setDuration(70)
                        .withEndAction {
                            onTapped()
                            // Release & fade
                            touchPointer.animate().scaleX(1f).scaleY(1f)
                                .setDuration(100)
                                .withEndAction {
                                    touchPointer.animate().alpha(0f).setDuration(200).start()
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

        // ── 2. Amount sheet slides UP (prefilled ₹100) ───────────────────────────
        val sheet1Start = 1700L
        schedule(sheet1Start) {
            frame.animate().alpha(0f).setDuration(260).start()
            amountGroup.alpha = 1f
            amountGroup.animate()
                .translationY(0f)
                .setDuration(350)
                .setInterpolator(IntroTourActivity.EASE_OUT)
                .start()
        }

        // ── 3. Pay button clicked by cursor -> Sheet 1 exits, Sheet 2 enters ────
        val payClickStart = sheet1Start + 350 + 800L
        schedule(payClickStart) {
            simulateTap(payBtn) {
                payBtn.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80)
                    .withEndAction {
                        payBtn.animate().scaleX(1f).scaleY(1f).setDuration(80).start()

                        // Dismiss Sheet 1 down cleanly
                        amountGroup.animate()
                            .translationY(SHEET_SLIDE_DP.dp)
                            .alpha(0f)
                            .setDuration(240)
                            .setInterpolator(IntroTourActivity.EASE_IN)
                            .withEndAction {
                                // Slide up Sheet 2 (Allocation) cleanly
                                allocGroup.alpha = 1f
                                allocGroup.translationY = SHEET_SLIDE_DP.dp
                                allocGroup.animate()
                                    .translationY(0f)
                                    .setDuration(320)
                                    .setInterpolator(IntroTourActivity.EASE_OUT)
                                    .start()
                            }
                            .start()
                    }
                    .start()
            }
        }

        // ── 4. Cursor clicks 1st allocation (Food) -> Sheet 2 exits, Sheet 3 enters ──
        // payClickStart (2850) + tap (410) + exit (240) + enter (320) + hold (800) = ~4620ms
        val selectFoodStart = payClickStart + 410 + 240 + 320 + 800L
        schedule(selectFoodStart) {
            simulateTap(allocFood) {
                allocFood.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80)
                    .withEndAction {
                        allocFood.animate().scaleX(1f).scaleY(1f).setDuration(80).start()

                        // Dismiss Sheet 2 down cleanly
                        allocGroup.animate()
                            .translationY(SHEET_SLIDE_DP.dp)
                            .alpha(0f)
                            .setDuration(240)
                            .setInterpolator(IntroTourActivity.EASE_IN)
                            .withEndAction {
                                // Slide up Sheet 3 (Confirm & Pay) cleanly
                                confirmGroup.alpha = 1f
                                confirmGroup.translationY = SHEET_SLIDE_DP.dp
                                confirmGroup.animate()
                                    .translationY(0f)
                                    .setDuration(320)
                                    .setInterpolator(IntroTourActivity.EASE_OUT)
                                    .start()
                            }
                            .start()
                    }
                    .start()
            }
        }

        // ── 5. Cursor clicks "Pay Now" -> Sheet 3 exits & Payment Successful appears ──
        // selectFoodStart (4620) + tap (410) + exit (240) + enter (320) + hold (800) = ~6390ms
        val finalPayClickStart = selectFoodStart + 410 + 240 + 320 + 800L
        schedule(finalPayClickStart) {
            simulateTap(finalPayNowBtn) {
                finalPayNowBtn.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80)
                    .withEndAction {
                        finalPayNowBtn.animate().scaleX(1f).scaleY(1f).setDuration(80).start()

                        // Dismiss Sheet 3 down cleanly
                        confirmGroup.animate()
                            .translationY(SHEET_SLIDE_DP.dp)
                            .alpha(0f)
                            .setDuration(260)
                            .setInterpolator(IntroTourActivity.EASE_IN)
                            .withEndAction {
                                // Animate Payment Successful cleanly into the center
                                successGroup.animate()
                                    .alpha(1f)
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .setDuration(320)
                                    .setInterpolator(IntroTourActivity.EASE_OUT)
                                    .start()
                            }
                            .start()
                    }
                    .start()
            }
        }
    }

    override fun sceneDurationMs(): Long {
        return 9800L
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
