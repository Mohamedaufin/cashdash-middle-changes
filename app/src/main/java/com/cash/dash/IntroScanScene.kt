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
import kotlin.math.roundToInt

/**
 * Page 2: The full payment flow.
 *
 * Simulates a realistic payment video inside a mock phone frame.
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
    
    private val ringGroup: View
    private val ringView: IntroWalletRingView
    private val ringAmount: TextView

    private var timeline: ValueAnimator? = null
    private var typeAnim: ValueAnimator? = null
    private var ringAnim: ValueAnimator? = null

    /** Tracks which digits of "100" have been typed. */
    private var shownDigits = -1
    
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
        
        ringGroup = findViewById(R.id.introScanRingGroup)
        ringView = findViewById(R.id.introScanRing)
        ringAmount = findViewById(R.id.introScanRingAmount)
    }

    override fun resetScene() {
        generation++
        handler.removeCallbacksAndMessages(null)
        
        timeline?.cancel(); timeline = null
        typeAnim?.cancel(); typeAnim = null
        ringAnim?.cancel(); ringAnim = null

        // Cancel every in-flight ViewPropertyAnimator
        val all = listOf(phoneContainer, frame, touchPointer, amountGroup, amountField, payBtn,
            allocGroup, allocFood, allocShopping, confirmGroup, finalPayNowBtn, successGroup, ringGroup)
        for (v in all) v.animate().cancel()

        // Scanner (Centered)
        frame.alpha = 1f
        frame.scaleX = 1f
        frame.scaleY = 1f
        phoneContainer.cameraDistance = 8000f * resources.displayMetrics.density
        phoneContainer.rotationX = 0f
        phoneContainer.rotationY = 0f
        phoneContainer.translationY = 0f
        phoneContainer.translationZ = 0f
        phoneContainer.scaleX = 1f
        phoneContainer.scaleY = 1f
        frame.bracketProgress = 0f
        frame.moduleProgress = 0f
        frame.lockProgress = 0f
        frame.scanLineProgress = 0f

        // Touch pointer
        touchPointer.alpha = 0f
        touchPointer.scaleX = 1f
        touchPointer.scaleY = 1f

        // Sheet 1: Amount Entry (starts off-screen below bottom edge)
        amountGroup.alpha = 1f
        amountGroup.translationY = SHEET_SLIDE_DP.dp
        amountField.text = ""
        shownDigits = -1
        payBtn.alpha = 1f
        payBtn.text = "Pay"

        // Sheet 2: Allocation Chooser (starts off-screen below bottom edge)
        allocGroup.alpha = 1f
        allocGroup.translationY = SHEET_SLIDE_DP.dp
        allocFood.alpha = 1f
        allocFood.scaleX = 1f
        allocFood.scaleY = 1f
        allocShopping.alpha = 1f

        // Sheet 3: Confirm State (starts off-screen below bottom edge)
        confirmGroup.alpha = 0f
        confirmGroup.translationY = SHEET_SLIDE_DP.dp
        finalPayNowBtn.alpha = 1f
        finalPayNowBtn.scaleX = 1f
        finalPayNowBtn.scaleY = 1f
        
        // Success
        successGroup.alpha = 0f
        successGroup.scaleX = 0.8f
        successGroup.scaleY = 0.8f
        
        // Ring
        ringGroup.alpha = 0f
        ringGroup.scaleX = 0.85f
        ringGroup.scaleY = 0.85f
        ringView.progress = 1f
        ringAmount.text = "₹2000"
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

            // Tip of the desktop arrow cursor aims at the button
            val targetTipX = (targetLoc[0] - parentLoc[0]) + (targetView.width * 0.45f)
            val targetTipY = (targetLoc[1] - parentLoc[1]) + (targetView.height * 0.45f)

            touchPointer.pivotX = 0f
            touchPointer.pivotY = 0f
            touchPointer.translationX = targetTipX + 16f.dp
            touchPointer.translationY = targetTipY + 16f.dp
            touchPointer.scaleX = 1f
            touchPointer.scaleY = 1f
            touchPointer.alpha = 0f

            // 1. Desktop cursor glides smoothly into place
            touchPointer.animate()
                .alpha(1f)
                .translationX(targetTipX)
                .translationY(targetTipY)
                .setDuration(260)
                .setInterpolator(IntroTourActivity.EASE_OUT)
                .withEndAction {
                    // 2. Click down (tactile tip press)
                    touchPointer.animate().scaleX(0.85f).scaleY(0.85f)
                        .setDuration(80)
                        .withEndAction {
                            // Trigger target button action
                            onTapped()
                            // 3. Click release & fade
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
        // ── Beat 1: Scanner (0 -> 2000ms) ─────────────────────────────────────────
        timeline = ValueAnimator.ofFloat(0f, SCAN_MS.toFloat()).apply {
            duration = SCAN_MS
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                frame.bracketProgress = span(t, 0f, 600f, IntroTourActivity.EASE_OUT)
                frame.moduleProgress = span(t, 400f, 1200f, null)
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

        // ── Beat 2: Sheet 1 (Amount) slides UP from bottom edge ──────────────────
        val beat2Start = 2500L

        schedule(beat2Start) {
            amountGroup.animate().translationY(0f)
                .setDuration(450)
                .setInterpolator(IntroTourActivity.EASE_OUT).start()
        }

        val typeStart = beat2Start + 450 + 600L
        schedule(typeStart) {
            typeAnim = ValueAnimator.ofFloat(0f, AMOUNT_DIGITS.size.toFloat()).apply {
                duration = KEYPRESS_MS * AMOUNT_DIGITS.size
                addUpdateListener { a ->
                    val n = (a.animatedValue as Float).toInt().coerceIn(0, AMOUNT_DIGITS.size)
                    if (n == shownDigits) return@addUpdateListener
                    shownDigits = n
                    val amtStr = if (n == 0) "" else AMOUNT_DIGITS[n - 1]
                    amountField.text = amtStr
                    payBtn.text = if (amtStr.isEmpty()) "Pay" else "Pay ₹$amtStr"
                    
                    if (n > 0) {
                        amountField.animate().cancel()
                        amountField.scaleX = 1.04f
                        amountField.scaleY = 1.04f
                        amountField.animate().scaleX(1f).scaleY(1f).setDuration(140)
                            .setInterpolator(IntroTourActivity.EASE_OUT).start()
                    }
                }
                start()
            }
        }

        // ── Beat 3: Touch Cursor taps Pay Button -> Sheet 2 (Allocation) slides UP ────
        val payBtnClickStart = typeStart + KEYPRESS_MS * AMOUNT_DIGITS.size + 2200L
        schedule(payBtnClickStart) {
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

        val beat3Start = payBtnClickStart + 500L

        // Smooth physical slide-up of Allocation Sheet over Amount Sheet
        schedule(beat3Start) {
            allocGroup.animate().translationY(0f)
                .setDuration(450)
                .setInterpolator(IntroTourActivity.EASE_OUT)
                .withEndAction {
                    // Once alloc sheet is fully covering, put amount sheet safely away
                    amountGroup.translationY = SHEET_SLIDE_DP.dp
                    amountGroup.alpha = 0f
                }
                .start()
        }
        
        // ── Beat 4: Touch Cursor taps Food Card -> Sheet 2 slides DOWN ───────────
        val clickStart = beat3Start + 450 + 2600L
        schedule(clickStart) {
            simulateTap(allocFood) {
                allocFood.animate().cancel()
                allocFood.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.6f)
                    .setDuration(100).setInterpolator(IntroTourActivity.EASE_OUT)
                    .withEndAction {
                        allocFood.animate().scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(150).setInterpolator(IntroTourActivity.EASE_OUT).start()
                    }
                    .start()
            }
        }

        val beat4Start = clickStart + 500L

        schedule(beat4Start) {
            // Confirm sheet is resting ready underneath
            confirmGroup.alpha = 1f
            confirmGroup.translationY = 0f

            // Allocation sheet slides down off the bottom edge
            allocGroup.animate().translationY(SHEET_SLIDE_DP.dp)
                .setDuration(400)
                .setInterpolator(IntroTourActivity.EASE_IN).start()
        }
        
        // ── Beat 5: Touch Cursor taps Pay Now -> Sheet 3 slides DOWN ───────────
        val payClickStart = beat4Start + 400 + 2200L
        schedule(payClickStart) {
            simulateTap(finalPayNowBtn) {
                finalPayNowBtn.animate().cancel()
                finalPayNowBtn.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.6f)
                    .setDuration(100).setInterpolator(IntroTourActivity.EASE_OUT)
                    .withEndAction {
                        finalPayNowBtn.animate().scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(150).setInterpolator(IntroTourActivity.EASE_OUT).start()
                    }
                    .start()
            }
        }
        
        val beat5Start = payClickStart + 500L

        schedule(beat5Start) {
            amountGroup.translationY = SHEET_SLIDE_DP.dp
            amountGroup.alpha = 0f
            allocGroup.translationY = SHEET_SLIDE_DP.dp
            allocGroup.alpha = 0f

            confirmGroup.animate().translationY(SHEET_SLIDE_DP.dp).alpha(0f)
                .setDuration(380)
                .setInterpolator(IntroTourActivity.EASE_IN).start()

            frame.animate().alpha(0f).setDuration(250).start()
        }
        
        schedule(beat5Start + 250) {
            successGroup.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(350)
                .setInterpolator(OVERSHOOT).start()
        }
        
        // ── Beat 6: Success transitions to Wallet Ring deduction ──────────────
        val beat6Start = beat5Start + 250 + 350 + 2500L
        
        schedule(beat6Start) {
            successGroup.animate().alpha(0f)
                .setDuration(250)
                .setInterpolator(IntroTourActivity.EASE_IN).start()
        }
        
        schedule(beat6Start + 250) {
            ringGroup.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(350)
                .setInterpolator(IntroTourActivity.EASE_OUT).start()
        }
        
        // Deduct from 2000 to 1900 over 1200ms
        schedule(beat6Start + 250 + 350 + 1000L) {
            ringAnim = ValueAnimator.ofFloat(2000f, 1900f).apply {
                duration = 1200L
                interpolator = IntroTourActivity.EASE_OUT
                addUpdateListener { a ->
                    val v = a.animatedValue as Float
                    ringView.progress = v / 2000f
                    ringAmount.text = "₹${v.roundToInt()}"
                }
                start()
            }
        }
    }

    override fun sceneDurationMs(): Long {
        return 25500L // 25.5 seconds spacious, comfortable reading pacing
    }

    /** Maps [t] onto 0..1 across [from]..[to], clamped, optionally eased. */
    private fun span(t: Float, from: Float, to: Float, ease: android.view.animation.Interpolator?): Float {
        val f = ((t - from) / (to - from)).coerceIn(0f, 1f)
        return ease?.getInterpolation(f) ?: f
    }

    private val Float.dp: Float get() = this * resources.displayMetrics.density

    private companion object {
        val OVERSHOOT = android.view.animation.OvershootInterpolator(1.2f)
        const val SHEET_SLIDE_DP = 360f
        const val SCAN_MS = 2000L
        const val KEYPRESS_MS = 250L
        val AMOUNT_DIGITS = arrayOf("1", "10", "100")
    }
}
