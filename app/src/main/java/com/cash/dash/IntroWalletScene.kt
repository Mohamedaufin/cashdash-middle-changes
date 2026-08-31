package com.cash.dash

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import java.text.NumberFormat
import java.util.Locale

/**
 * Page 1: the wallet balance you set the first time you open the app.
 *
 * The figure is typed in digit-by-digit — 2, 20, 200, 2000 — with clear, readable pacing.
 * The ring then smoothly sweeps around it at a relaxed, premium pace.
 */
class IntroWalletScene @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), IntroScene {

    private val ring: IntroWalletRingView
    private val amount: TextView
    private val label: TextView
    private val cycle: TextView

    private var typeAnimator: ValueAnimator? = null
    private var ringAnimator: ValueAnimator? = null
    private var shownDigits = -1

    private val money: NumberFormat = NumberFormat.getIntegerInstance(Locale.forLanguageTag("en-IN"))

    init {
        LayoutInflater.from(context).inflate(R.layout.view_intro_scene_wallet, this, true)
        ring = findViewById(R.id.introWalletRing)
        amount = findViewById(R.id.introWalletAmount)
        label = findViewById(R.id.introWalletLabel)
        cycle = findViewById(R.id.introWalletCycle)
    }

    override fun resetScene() {
        typeAnimator?.cancel(); typeAnimator = null
        ringAnimator?.cancel(); ringAnimator = null
        for (v in listOf(amount, label, cycle)) v.animate().cancel()

        shownDigits = -1
        amount.setText(R.string.intro_wallet_zero)
        amount.alpha = 0f
        amount.scaleX = 1f
        amount.scaleY = 1f
        label.alpha = 0f
        cycle.alpha = 0f
        cycle.translationY = 8f.dp
        ring.progress = 0f
    }

    override fun playScene() {
        amount.animate().alpha(1f).setStartDelay(0).setDuration(260)
            .setInterpolator(IntroTourActivity.EASE_OUT).start()
        label.animate().alpha(1f).setStartDelay(120).setDuration(300)
            .setInterpolator(IntroTourActivity.EASE_OUT).start()

        // Deliberate, clear digit typing (2 -> 20 -> 200 -> 2,000)
        val typeStart = 260L
        typeAnimator = ValueAnimator.ofFloat(0f, DIGITS.size.toFloat()).apply {
            startDelay = typeStart
            duration = KEYPRESS_MS * DIGITS.size
            addUpdateListener { a ->
                val n = (a.animatedValue as Float).toInt().coerceIn(0, DIGITS.size)
                if (n == shownDigits) return@addUpdateListener
                shownDigits = n
                if (n == 0) return@addUpdateListener
                amount.text = context.getString(R.string.intro_tour_amount, money.format(DIGITS[n - 1]))
                
                // Subtle tactile nudge on each key entry
                amount.animate().cancel()
                amount.scaleX = 1.05f
                amount.scaleY = 1.05f
                amount.animate().scaleX(1f).scaleY(1f).setDuration(160)
                    .setInterpolator(IntroTourActivity.EASE_OUT).start()
            }
            start()
        }

        // Smooth, relaxed circular ring loading (sweeps calmly over 1500ms)
        val ringStart = typeStart + (KEYPRESS_MS * DIGITS.size) + 120L
        ringAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = ringStart
            duration = RING_DURATION_MS
            interpolator = IntroTourActivity.EASE_OUT
            addUpdateListener { a -> ring.progress = a.animatedValue as Float }
            start()
        }

        // "Renews every 7 days" badge floats in as the ring completes
        val cycleStart = ringStart + RING_DURATION_MS - 400L
        cycle.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(cycleStart)
            .setDuration(400)
            .setInterpolator(IntroTourActivity.EASE_OUT)
            .start()
    }

    override fun sceneDurationMs(): Long {
        return 5200L
    }

    private val Float.dp: Float get() = this * resources.displayMetrics.density

    private companion object {
        const val KEYPRESS_MS = 280L
        const val RING_DURATION_MS = 1500L
        /** What the amount reads after each keypress, as BalanceSetupActivity's pad builds it. */
        val DIGITS = intArrayOf(2, 20, 200, 2000)
    }
}
