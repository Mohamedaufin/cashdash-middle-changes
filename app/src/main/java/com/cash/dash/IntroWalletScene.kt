package com.cash.dash

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import java.text.NumberFormat
import java.util.Locale

/**
 * Page 1: the wallet balance you set the first time you open the app.
 *
 * The figure is typed in a digit at a time — 2, 20, 200, 2000 — the way BalanceSetupActivity's
 * numpad enters it, then the ring closes around it. Typing rather than counting up is the
 * point of difference from every other balance animation: this number is not being measured,
 * it is being **declared**. You are telling the app what you have.
 *
 * The ring finishes closed. Immediately after setup the balance IS the budget, so full is the
 * only honest state for it, and it gives the page a different silhouette from a partial ring
 * anyone will meet later on Home.
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
        ring.progress = 0f
    }

    override fun playScene() {
        amount.animate().alpha(1f).setStartDelay(0).setDuration(260)
            .setInterpolator(IntroTourActivity.EASE_OUT).start()
        label.animate().alpha(1f).setStartDelay(120).setDuration(300)
            .setInterpolator(IntroTourActivity.EASE_OUT).start()

        // One keypress per digit. Linear on purpose: someone entering a number does not
        // accelerate, and easing this would read as the figure counting itself up.
        typeAnimator = ValueAnimator.ofFloat(0f, DIGITS.size.toFloat()).apply {
            startDelay = 220
            duration = KEYPRESS_MS * DIGITS.size
            addUpdateListener { a ->
                val n = (a.animatedValue as Float).toInt().coerceIn(0, DIGITS.size)
                if (n == shownDigits) return@addUpdateListener
                shownDigits = n
                if (n == 0) return@addUpdateListener
                amount.text = context.getString(R.string.intro_tour_amount, money.format(DIGITS[n - 1]))
                // The nudge a key gives back. Small enough to feel rather than watch.
                amount.animate().cancel()
                amount.scaleX = 1.045f
                amount.scaleY = 1.045f
                amount.animate().scaleX(1f).scaleY(1f).setDuration(160)
                    .setInterpolator(IntroTourActivity.EASE_OUT).start()
            }
            start()
        }

        // Starts as the last digit lands, so the ring reads as a consequence of the figure.
        ringAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = 220 + KEYPRESS_MS * (DIGITS.size - 1)
            duration = 880
            interpolator = IntroTourActivity.EASE_OUT
            addUpdateListener { a -> ring.progress = a.animatedValue as Float }
            start()
        }

        cycle.animate().alpha(1f).setStartDelay(1180).setDuration(360)
            .setInterpolator(IntroTourActivity.EASE_OUT).start()
    }

    private companion object {
        const val KEYPRESS_MS = 150L
        /** What the amount reads after each keypress, as BalanceSetupActivity's pad builds it. */
        val DIGITS = intArrayOf(2, 20, 200, 2000)
    }
}
