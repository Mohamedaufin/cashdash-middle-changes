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

    /** Every group that participates in the cross-fade, for easy iteration. */
    private val allGroups: List<View>

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

        allGroups = listOf(amountGroup, allocGroup, confirmGroup, successGroup, ringGroup)
    }

    override fun resetScene() {
        generation++
        handler.removeCallbacksAndMessages(null)
        
        timeline?.cancel(); timeline = null
        typeAnim?.cancel(); typeAnim = null
        ringAnim?.cancel(); ringAnim = null

        // Cancel every in-flight ViewPropertyAnimator
        val all = listOf(phoneContainer, frame, amountGroup, amountField, payBtn,
            allocGroup, allocFood, allocShopping, confirmGroup, finalPayNowBtn, successGroup, ringGroup)
        for (v in all) v.animate().cancel()

        // Scanner
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

        // Amount Entry (Sheet starting off bottom)
        amountGroup.alpha = 1f
        amountGroup.translationY = SHEET_SLIDE_DP.dp
        amountField.text = ""
        shownDigits = -1
        payBtn.alpha = 1f
        payBtn.translationY = 0f
        payBtn.text = "Pay"

        // Allocation (Sheet starting off bottom)
        allocGroup.alpha = 1f
        allocGroup.translationY = SHEET_SLIDE_DP.dp
        allocFood.alpha = 1f
        allocFood.translationY = 0f
        allocShopping.alpha = 1f
        allocShopping.translationY = 0f

        // Confirm (Sheet ready to be revealed underneath)
        confirmGroup.alpha = 0f
        confirmGroup.translationY = 0f
        finalPayNowBtn.alpha = 1f
        finalPayNowBtn.translationY = 0f
        
        // Success
        successGroup.alpha = 0f
        successGroup.scaleX = 0.8f
        successGroup.scaleY = 0.8f
        successGroup.translationY = 0f
        
        // Ring
        ringGroup.alpha = 0f
        ringGroup.translationY = RISE_DP.dp
        ringView.progress = 1f
        ringAmount.text = "₹2000"
    }
    
    private fun schedule(delayMs: Long, action: () -> Unit) {
        val gen = generation
        handler.postDelayed({
            if (gen == generation) action()
        }, delayMs)
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

        // ── Beat 2: Amount entry (Starts at 2300) ─────────────────────────────────────────
        val beat2Start = 2300L

        schedule(beat2Start) {
            frame.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f)
                .setDuration(250)
                .setInterpolator(IntroTourActivity.EASE_IN).start()
        }

        // Slide amount sheet up ONLY after scanner is completely gone
        schedule(beat2Start + 280) {
            amountGroup.alpha = 1f
            amountGroup.animate().translationY(0f)
                .setDuration(450)
                .setInterpolator(IntroTourActivity.EASE_OUT).start()
        }

        val typeStart = beat2Start + 280 + 450 + 600L
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

        // ── Beat 3: Allocation chooser (Starts after typing) ─────────────────────────────────────────
        val payBtnClickStart = typeStart + KEYPRESS_MS * AMOUNT_DIGITS.size + 1500L
        schedule(payBtnClickStart) {
            payBtn.animate().scaleX(0.95f).scaleY(0.95f)
                .setStartDelay(0).setDuration(100)
                .withEndAction {
                    payBtn.animate().scaleX(1f).scaleY(1f)
                        .setStartDelay(0).setDuration(100)
                        .setInterpolator(IntroTourActivity.EASE_OUT).start()
                }
                .setInterpolator(IntroTourActivity.EASE_IN).start()
        }

        val beat3Start = payBtnClickStart + 300L

        // Slide allocation sheet up over amount sheet
        schedule(beat3Start) {
            allocGroup.alpha = 1f
            allocGroup.animate().translationY(0f)
                .setDuration(420)
                .setInterpolator(IntroTourActivity.EASE_OUT).start()

            amountGroup.animate().alpha(0f)
                .setStartDelay(0).setDuration(120).start()
        }
        
        // ── Click Simulation on Food ─────────────────────────────────────────
        val clickStart = beat3Start + 420 + 1800L
        schedule(clickStart) {
            allocFood.animate().cancel()
            allocFood.animate().scaleX(0.92f).scaleY(0.92f).alpha(0.6f)
                .setDuration(100).setInterpolator(IntroTourActivity.EASE_OUT)
                .withEndAction {
                    allocFood.animate().scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(150).setInterpolator(IntroTourActivity.EASE_OUT).start()
                }
                .start()
        }

        // ── Beat 4: Allocation slides down, THEN Confirmation reveals cleanly ─────────────────────────
        val beat4Start = clickStart + 250 + 350L

        schedule(beat4Start) {
            // Alloc group slides DOWN off the bottom edge
            allocGroup.animate().translationY(SHEET_SLIDE_DP.dp).alpha(0f)
                .setDuration(350)
                .setInterpolator(IntroTourActivity.EASE_IN).start()
        }

        // Reveal confirm group after alloc group has cleared the screen
        schedule(beat4Start + 320) {
            confirmGroup.translationY = 0f
            confirmGroup.alpha = 0f
            confirmGroup.animate().alpha(1f)
                .setDuration(220)
                .setInterpolator(IntroTourActivity.EASE_OUT).start()
        }
        
        // ── Click Simulation on Pay Now ─────────────────────────────────────────
        val payClickStart = beat4Start + 320 + 220 + 1600L
        schedule(payClickStart) {
            finalPayNowBtn.animate().cancel()
            finalPayNowBtn.animate().scaleX(0.92f).scaleY(0.92f).alpha(0.6f)
                .setDuration(100).setInterpolator(IntroTourActivity.EASE_OUT)
                .withEndAction {
                    finalPayNowBtn.animate().scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(150).setInterpolator(IntroTourActivity.EASE_OUT).start()
                }
                .start()
        }
        
        // ── Beat 5: Confirm slides down, Success pops in ─────────────────────────────────────────
        val beat5Start = payClickStart + 250 + 350L

        schedule(beat5Start) {
            confirmGroup.animate().translationY(SHEET_SLIDE_DP.dp).alpha(0f)
                .setDuration(300)
                .setInterpolator(IntroTourActivity.EASE_IN).start()
        }
        
        schedule(beat5Start + 200) {
            successGroup.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(350)
                .setInterpolator(OVERSHOOT).start()
        }
        
        // ── Beat 6: Ring deduction ─────────────────────────────────────────
        val beat6Start = beat5Start + 200 + 350 + 1800L
        
        schedule(beat6Start) {
            successGroup.animate().alpha(0f)
                .setStartDelay(0).setDuration(250)
                .setInterpolator(IntroTourActivity.EASE_IN).start()
        }
        
        schedule(beat6Start + 250) {
            ringGroup.animate().alpha(1f).translationY(0f)
                .setStartDelay(0).setDuration(RISE_MS)
                .setInterpolator(IntroTourActivity.EASE_OUT).start()
        }
        
        // Deduct from 2000 to 1900 over 1000ms
        schedule(beat6Start + 250 + RISE_MS + 800L) {
            ringAnim = ValueAnimator.ofFloat(2000f, 1900f).apply {
                duration = 1000L
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
        return 25500L // Vastly extended for comfortable reading pacing (25.5 seconds)
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
        const val BEAT_HOLD_MS = 1200L
        const val FADE_MS = 400L
        const val RISE_MS = 600L
        const val RISE_DP = 16f
        const val KEYPRESS_MS = 250L
        val AMOUNT_DIGITS = arrayOf("1", "10", "100")
    }
}
