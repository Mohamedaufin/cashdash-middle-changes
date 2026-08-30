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
        phoneContainer.scaleX = 1f
        phoneContainer.scaleY = 1f
        frame.bracketProgress = 0f
        frame.moduleProgress = 0f
        frame.lockProgress = 0f
        frame.scanLineProgress = 0f

        // Amount Entry
        amountGroup.alpha = 0f
        amountGroup.translationY = RISE_DP.dp
        amountField.text = ""
        shownDigits = -1
        payBtn.alpha = 0f
        payBtn.translationY = RISE_DP.dp
        payBtn.text = "Pay"

        // Allocation
        allocGroup.alpha = 0f
        allocGroup.translationY = RISE_DP.dp
        allocFood.alpha = 0f
        allocFood.translationY = RISE_DP.dp
        allocShopping.alpha = 0f
        allocShopping.translationY = RISE_DP.dp

        // Confirm
        confirmGroup.alpha = 0f
        confirmGroup.translationY = RISE_DP.dp
        finalPayNowBtn.alpha = 0f
        finalPayNowBtn.translationY = RISE_DP.dp
        
        // Success
        successGroup.alpha = 0f
        successGroup.translationY = RISE_DP.dp
        
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

        // ── Beat 2: Amount entry (Starts at 2500) ─────────────────────────────────────────
        val beat2Start = 2500L
        schedule(beat2Start) {
            phoneContainer.animate().scaleX(1.1f).scaleY(1.1f).rotationX(12f).rotationY(-3f).translationY(-10f.dp)
                .setDuration(1200).setInterpolator(IntroTourActivity.EASE_OUT).start()
        }

        schedule(beat2Start) {
            frame.animate().alpha(0f).scaleX(0.86f).scaleY(0.86f)
                .setStartDelay(0).setDuration(400)
                .setInterpolator(IntroTourActivity.EASE_IN).start()
        }

        schedule(beat2Start + 100) {
            amountGroup.animate().alpha(1f).translationY(0f)
                .setStartDelay(0).setDuration(RISE_MS)
                .setInterpolator(IntroTourActivity.EASE_OUT).start()
        }

        val typeStart = beat2Start + 100 + RISE_MS + 800L
        schedule(typeStart) {
            // Fade in the pay button as soon as typing starts!
            payBtn.animate().alpha(1f).translationY(0f)
                .setStartDelay(0).setDuration(RISE_MS)
                .setInterpolator(IntroTourActivity.EASE_OUT).start()
                
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

        // ── Beat 3: Allocation chooser (Starts at 8250) ─────────────────────────────────────────
        val beat3Start = typeStart + KEYPRESS_MS * AMOUNT_DIGITS.size + RISE_MS + 2500L

        schedule(beat3Start) {
            phoneContainer.animate().scaleX(1.25f).scaleY(1.25f).rotationX(5f).rotationY(4f).translationY(-40f.dp)
                .setDuration(1400).setInterpolator(IntroTourActivity.EASE_OUT).start()
        }

        val payBtnClickStart = beat3Start - 600L
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

        schedule(beat3Start) {
            amountGroup.animate().alpha(0f)
                .setStartDelay(0).setDuration(FADE_MS)
                .setInterpolator(IntroTourActivity.EASE_IN).start()
        }

        schedule(beat3Start + FADE_MS) {
            allocGroup.animate().alpha(1f).translationY(0f)
                .setStartDelay(0).setDuration(RISE_MS)
                .setInterpolator(IntroTourActivity.EASE_OUT).start()
        }

        schedule(beat3Start + FADE_MS + 200) {
            allocFood.animate().alpha(1f).translationY(0f)
                .setStartDelay(0).setDuration(RISE_MS)
                .setInterpolator(IntroTourActivity.EASE_OUT).start()
        }
        
        schedule(beat3Start + FADE_MS + 400) {
            allocShopping.animate().alpha(1f).translationY(0f)
                .setStartDelay(0).setDuration(RISE_MS)
                .setInterpolator(IntroTourActivity.EASE_OUT).start()
        }
        
        // ── Click Simulation on Food ─────────────────────────────────────────
        val clickStart = beat3Start + FADE_MS + 400 + RISE_MS + 1500L
        schedule(clickStart) {
            allocFood.animate().cancel()
            allocFood.animate().scaleX(0.92f).scaleY(0.92f).alpha(0.6f)
                .setDuration(100).setInterpolator(IntroTourActivity.EASE_OUT)
                .withEndAction {
                    allocFood.animate().scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(200).setInterpolator(IntroTourActivity.EASE_OUT).start()
                }
                .start()
        }

        // ── Beat 4: Confirmation (Pay Now) ─────────────────────────────────────────
        val beat4Start = clickStart + 300 + 1000L

        schedule(beat4Start) {
            phoneContainer.animate().scaleX(1.05f).scaleY(1.05f).rotationX(8f).rotationY(-2f).translationY(-20f.dp)
                .setDuration(1200).setInterpolator(IntroTourActivity.EASE_OUT).start()
        }

        schedule(beat4Start) {
            allocGroup.animate().alpha(0f)
                .setStartDelay(0).setDuration(FADE_MS)
                .setInterpolator(IntroTourActivity.EASE_IN).start()
        }

        schedule(beat4Start + FADE_MS) {
            confirmGroup.animate().alpha(1f).translationY(0f)
                .setStartDelay(0).setDuration(RISE_MS)
                .setInterpolator(IntroTourActivity.EASE_OUT).start()
        }

        schedule(beat4Start + FADE_MS + 200) {
            finalPayNowBtn.animate().alpha(1f).translationY(0f)
                .setStartDelay(0).setDuration(RISE_MS)
                .setInterpolator(IntroTourActivity.EASE_OUT).start()
        }
        
        // ── Click Simulation on Pay Now ─────────────────────────────────────────
        val payClickStart = beat4Start + FADE_MS + 200 + RISE_MS + 1500L
        schedule(payClickStart) {
            finalPayNowBtn.animate().cancel()
            finalPayNowBtn.animate().scaleX(0.92f).scaleY(0.92f).alpha(0.6f)
                .setDuration(100).setInterpolator(IntroTourActivity.EASE_OUT)
                .withEndAction {
                    finalPayNowBtn.animate().scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(200).setInterpolator(IntroTourActivity.EASE_OUT).start()
                }
                .start()
        }
        
        // ── Beat 5: Success ─────────────────────────────────────────
        val beat5Start = payClickStart + 300 + 700L

        schedule(beat5Start) {
            phoneContainer.animate().scaleX(1f).scaleY(1f).rotationX(0f).rotationY(0f).translationY(0f)
                .setDuration(1000).setInterpolator(IntroTourActivity.EASE_OUT).start()
        }
        
        schedule(beat5Start) {
            confirmGroup.animate().alpha(0f)
                .setStartDelay(0).setDuration(FADE_MS)
                .setInterpolator(IntroTourActivity.EASE_IN).start()
        }
        
        schedule(beat5Start + FADE_MS) {
            successGroup.animate().alpha(1f).translationY(0f)
                .setStartDelay(0).setDuration(RISE_MS)
                .setInterpolator(IntroTourActivity.EASE_OUT).start()
        }
        
        // ── Beat 6: Ring deduction ─────────────────────────────────────────
        val beat6Start = beat5Start + FADE_MS + RISE_MS + 2000L
        
        schedule(beat6Start) {
            successGroup.animate().alpha(0f)
                .setStartDelay(0).setDuration(FADE_MS)
                .setInterpolator(IntroTourActivity.EASE_IN).start()
        }
        
        schedule(beat6Start + FADE_MS) {
            ringGroup.animate().alpha(1f).translationY(0f)
                .setStartDelay(0).setDuration(RISE_MS)
                .setInterpolator(IntroTourActivity.EASE_OUT).start()
        }
        
        // Deduct from 2000 to 1900 over 1000ms
        schedule(beat6Start + FADE_MS + RISE_MS + 1000L) {
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
        const val SCAN_MS = 2000L
        const val BEAT_HOLD_MS = 1200L
        const val FADE_MS = 400L
        const val RISE_MS = 600L
        const val RISE_DP = 16f
        const val KEYPRESS_MS = 250L
        val AMOUNT_DIGITS = arrayOf("1", "10", "100")
    }
}
