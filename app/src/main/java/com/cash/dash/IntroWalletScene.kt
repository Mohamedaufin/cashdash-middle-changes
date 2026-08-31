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
import java.text.NumberFormat
import java.util.Locale

/**
 * Page 1: Enter wallet balance and choose tentative date via unified calendar card:
 * 1. Card appears with Amount box and embedded October Calendar
 * 2. Types ₹2000 into wallet balance box quickly
 * 3. Desktop cursor glides to Date 25 on the calendar and clicks it -> Day 25 highlights
 * 4. Steady reading pause with cursor resting on the card
 * 5. Cursor glides down from Date 25 to "Save Balance" and clicks it
 * 6. Card dissolves, and Wallet Ring smoothly loads with "This money is tentatively till 25 Oct"!
 */
class IntroWalletScene @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), IntroScene {

    private val canvas: View
    private val setupCard: View
    private val inputField: TextView

    private val calDay25: FrameLayout
    private val calDay25Text: TextView
    private val dateBadge: TextView
    private val saveBtn: View

    private val cursor: View

    private val ringGroup: View
    private val tentativeTop: View
    private val ring: IntroWalletRingView
    private val amount: TextView
    private val label: TextView

    private var typeAnimator: ValueAnimator? = null
    private var ringAnimator: ValueAnimator? = null
    private var shownDigits = -1

    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0

    private val money: NumberFormat = NumberFormat.getIntegerInstance(Locale.forLanguageTag("en-IN"))

    init {
        clipChildren = false
        clipToPadding = false
        LayoutInflater.from(context).inflate(R.layout.view_intro_scene_wallet, this, true)
        canvas = findViewById(R.id.introWalletCanvas)
        setupCard = findViewById(R.id.introWalletSetupCard)
        inputField = findViewById(R.id.introWalletInputField)

        calDay25 = findViewById(R.id.introWalletCalDay25)
        calDay25Text = findViewById(R.id.introWalletCalDay25Text)
        dateBadge = findViewById(R.id.introWalletSelectedDateBadge)
        saveBtn = findViewById(R.id.introWalletSaveBtn)

        cursor = findViewById(R.id.introWalletCursor)
        cursor.bringToFront()

        ringGroup = findViewById(R.id.introWalletRingGroup)
        tentativeTop = findViewById(R.id.introWalletTentativeTop)
        ring = findViewById(R.id.introWalletRing)
        amount = findViewById(R.id.introWalletAmount)
        label = findViewById(R.id.introWalletLabel)
    }

    override fun resetScene() {
        generation++
        handler.removeCallbacksAndMessages(null)

        typeAnimator?.cancel(); typeAnimator = null
        ringAnimator?.cancel(); ringAnimator = null

        val all = listOf(
            setupCard, inputField, calDay25, dateBadge, saveBtn,
            cursor, ringGroup, tentativeTop, ring, amount, label
        )
        for (v in all) v.animate().cancel()

        shownDigits = -1

        // Setup Card
        setupCard.alpha = 0f
        setupCard.scaleX = 0.94f
        setupCard.scaleY = 0.94f
        setupCard.translationY = 12f.dp
        inputField.text = "₹ "

        // Calendar Day 25 reset
        calDay25.background = null
        calDay25.scaleX = 1f
        calDay25.scaleY = 1f
        calDay25Text.setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))

        // Badge
        dateBadge.text = "Tap a target date on calendar"
        dateBadge.setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textMutedColor))

        saveBtn.scaleX = 1f
        saveBtn.scaleY = 1f

        // Cursor
        cursor.bringToFront()
        cursor.alpha = 0f
        cursor.scaleX = 1f
        cursor.scaleY = 1f
        cursor.translationZ = 30f.dp

        // Ring Group
        ringGroup.alpha = 0f
        ringGroup.scaleX = 0.92f
        ringGroup.scaleY = 0.92f
        tentativeTop.alpha = 0f
        amount.text = "₹2,000"
        label.alpha = 1f
        ring.progress = 0f
    }

    private fun schedule(delayMs: Long, action: () -> Unit) {
        val gen = generation
        handler.postDelayed({
            if (gen == generation) action()
        }, delayMs)
    }

    private fun getTargetCenter(targetView: View): Pair<Float, Float> {
        val targetLoc = IntArray(2)
        targetView.getLocationInWindow(targetLoc)
        val parentLoc = IntArray(2)
        canvas.getLocationInWindow(parentLoc)

        val targetTipX = (targetLoc[0] - parentLoc[0]) + (targetView.width * 0.5f)
        val targetTipY = (targetLoc[1] - parentLoc[1]) + (targetView.height * 0.5f)
        return Pair(targetTipX, targetTipY)
    }

    override fun playScene() {
        // ── 1. Setup Card blooms in (0 -> 400ms) ──────────────────────────────────
        setupCard.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(400)
            .setInterpolator(IntroTourActivity.EASE_OUT)
            .start()

        // ── 2. Digit typing (2 -> 20 -> 200 -> 2,000) quick and snappy ───────────
        val typeStart = 450L
        typeAnimator = ValueAnimator.ofFloat(0f, DIGITS.size.toFloat()).apply {
            startDelay = typeStart
            duration = KEYPRESS_FAST_MS * DIGITS.size
            addUpdateListener { a ->
                val n = (a.animatedValue as Float).toInt().coerceIn(0, DIGITS.size)
                if (n == shownDigits) return@addUpdateListener
                shownDigits = n
                if (n == 0) return@addUpdateListener
                inputField.text = context.getString(R.string.intro_tour_amount, money.format(DIGITS[n - 1]))

                // Tactile pop on each digit typed
                inputField.animate().cancel()
                inputField.scaleX = 1.05f
                inputField.scaleY = 1.05f
                inputField.animate().scaleX(1f).scaleY(1f).setDuration(120)
                    .setInterpolator(IntroTourActivity.EASE_OUT).start()
            }
            start()
        }

        // ── 3. Cursor glides to Date 25 and clicks it down ───────────────────────
        // typeStart (450) + (140 * 4 = 560) + pause (700) = 1710ms
        val calDayClickStart = typeStart + (KEYPRESS_FAST_MS * DIGITS.size) + 700L
        schedule(calDayClickStart) {
            calDay25.post {
                val (day25X, day25Y) = getTargetCenter(calDay25)

                cursor.pivotX = 0f
                cursor.pivotY = 0f
                cursor.translationX = day25X + 24f.dp
                cursor.translationY = day25Y + 24f.dp
                cursor.scaleX = 1f
                cursor.scaleY = 1f
                cursor.alpha = 0f

                // Cursor glides to Date 25
                cursor.animate()
                    .alpha(1f)
                    .translationX(day25X)
                    .translationY(day25Y)
                    .setDuration(380)
                    .setInterpolator(IntroTourActivity.EASE_OUT)
                    .withEndAction {
                        // Click down on Day 25
                        cursor.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80)
                            .withEndAction {
                                calDay25.animate().scaleX(0.9f).scaleY(0.9f).setDuration(70)
                                    .withEndAction {
                                        calDay25.animate().scaleX(1f).scaleY(1f).setDuration(80).start()

                                        // Highlight Day 25 with circular primary badge
                                        calDay25.setBackgroundResource(R.drawable.bg_intro_cta)
                                        val primaryActionText = ThemeHelper.resolveColorAttr(context, R.attr.primaryActionText)
                                        calDay25Text.setTextColor(primaryActionText)

                                        // Update selection status badge
                                        val textPrimary = ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor)
                                        dateBadge.text = "Budget duration: 7 Days (till 25 Oct)"
                                        dateBadge.setTextColor(textPrimary)
                                    }
                                    .start()

                                cursor.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                            }
                            .start()
                    }
                    .start()
            }
        }

        // ── 4. Cursor glides smoothly from Date 25 down to "Save Balance" ─────────
        // calDayClickStart (1710) + glide (380) + click (170) + reading pause (1200) = ~3460ms
        val saveClickStart = calDayClickStart + 380 + 170 + 1200L
        schedule(saveClickStart) {
            saveBtn.post {
                val (saveX, saveY) = getTargetCenter(saveBtn)

                // Cursor glides from Date 25 to Save Balance button
                cursor.animate()
                    .translationX(saveX)
                    .translationY(saveY)
                    .setDuration(360)
                    .setInterpolator(IntroTourActivity.EASE_OUT)
                    .withEndAction {
                        // Click down on Save Balance
                        cursor.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80)
                            .withEndAction {
                                saveBtn.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80)
                                    .withEndAction {
                                        saveBtn.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                                    }
                                    .start()

                                cursor.animate().scaleX(1f).scaleY(1f).setDuration(90)
                                    .withEndAction {
                                        cursor.animate().alpha(0f).setDuration(180).start()
                                    }
                                    .start()

                                // Dissolve Setup Card
                                setupCard.animate()
                                    .alpha(0f)
                                    .scaleX(0.92f)
                                    .scaleY(0.92f)
                                    .setDuration(320)
                                    .setInterpolator(IntroTourActivity.EASE_IN)
                                    .withEndAction {
                                        // Bloom Wallet Ring Group
                                        ringGroup.animate()
                                            .alpha(1f)
                                            .scaleX(1f)
                                            .scaleY(1f)
                                            .setDuration(450)
                                            .setInterpolator(IntroTourActivity.EASE_OUT)
                                            .start()

                                        // Top tentative date banner fades in
                                        tentativeTop.animate()
                                            .alpha(1f)
                                            .setDuration(500)
                                            .setInterpolator(IntroTourActivity.EASE_OUT)
                                            .start()

                                        // Sweep Ring from 0 to 100% smoothly over 2.0s
                                        ringAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                                            duration = RING_DURATION_MS
                                            interpolator = IntroTourActivity.EASE_OUT
                                            addUpdateListener { a -> ring.progress = a.animatedValue as Float }
                                            start()
                                        }
                                    }
                                    .start()
                            }
                            .start()
                    }
                    .start()
            }
        }
    }

    override fun sceneDurationMs(): Long {
        return 11500L
    }

    private val Float.dp: Float get() = this * resources.displayMetrics.density

    private companion object {
        const val KEYPRESS_FAST_MS = 140L
        const val RING_DURATION_MS = 2000L
        val DIGITS = intArrayOf(2, 20, 200, 2000)
    }
}
