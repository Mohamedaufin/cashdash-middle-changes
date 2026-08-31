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
 * Page 1: Enter wallet balance and tentative date setup flow:
 * 1. Card appears: "Setup Your Wallet" / "Allocate what you want to spend"
 * 2. Types ₹2000 into wallet balance box
 * 3. Tentative date box populates: "25 Oct (7 Days)"
 * 4. Desktop cursor clicks "Save Balance"
 * 5. Card dissolves, and the Wallet Ring smoothly loads with:
 *    "This money is tentatively till 25 Oct" at the top!
 */
class IntroWalletScene @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), IntroScene {

    private val canvas: View
    private val setupCard: View
    private val inputField: TextView
    private val dateFieldBox: View
    private val saveBtn: View
    private val cursor: View

    private val ringGroup: View
    private val tentativeTop: TextView
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
        dateFieldBox = findViewById(R.id.introWalletDateFieldBox)
        saveBtn = findViewById(R.id.introWalletSaveBtn)
        cursor = findViewById(R.id.introWalletCursor)

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

        val all = listOf(setupCard, inputField, dateFieldBox, saveBtn, cursor, ringGroup, tentativeTop, ring, amount, label)
        for (v in all) v.animate().cancel()

        shownDigits = -1

        // Setup Card
        setupCard.alpha = 0f
        setupCard.scaleX = 0.94f
        setupCard.scaleY = 0.94f
        setupCard.translationY = 12f.dp
        inputField.text = "₹ "
        dateFieldBox.alpha = 0.6f
        saveBtn.scaleX = 1f
        saveBtn.scaleY = 1f

        // Cursor
        cursor.alpha = 0f
        cursor.scaleX = 1f
        cursor.scaleY = 1f

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

    private fun simulateTap(targetView: View, onTapped: () -> Unit) {
        targetView.post {
            val targetLoc = IntArray(2)
            targetView.getLocationInWindow(targetLoc)
            val parentLoc = IntArray(2)
            canvas.getLocationInWindow(parentLoc)

            val targetTipX = (targetLoc[0] - parentLoc[0]) + (targetView.width * 0.5f)
            val targetTipY = (targetLoc[1] - parentLoc[1]) + (targetView.height * 0.5f)

            cursor.pivotX = 0f
            cursor.pivotY = 0f
            cursor.translationX = targetTipX + 18f.dp
            cursor.translationY = targetTipY + 18f.dp
            cursor.scaleX = 1f
            cursor.scaleY = 1f
            cursor.alpha = 0f

            // Desktop cursor glides smoothly into place
            cursor.animate()
                .alpha(1f)
                .translationX(targetTipX)
                .translationY(targetTipY)
                .setDuration(240)
                .setInterpolator(IntroTourActivity.EASE_OUT)
                .withEndAction {
                    // Click down
                    cursor.animate().scaleX(0.85f).scaleY(0.85f)
                        .setDuration(70)
                        .withEndAction {
                            onTapped()
                            // Release & fade
                            cursor.animate().scaleX(1f).scaleY(1f)
                                .setDuration(100)
                                .withEndAction {
                                    cursor.animate().alpha(0f).setDuration(200).start()
                                }
                                .start()
                        }
                        .start()
                }
                .start()
        }
    }

    override fun playScene() {
        // ── 1. Setup Card blooms in (0 -> 350ms) ──────────────────────────────────
        setupCard.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(350)
            .setInterpolator(IntroTourActivity.EASE_OUT)
            .start()

        // ── 2. Digit typing (2 -> 20 -> 200 -> 2,000) ────────────────────────────
        val typeStart = 450L
        typeAnimator = ValueAnimator.ofFloat(0f, DIGITS.size.toFloat()).apply {
            startDelay = typeStart
            duration = KEYPRESS_MS * DIGITS.size
            addUpdateListener { a ->
                val n = (a.animatedValue as Float).toInt().coerceIn(0, DIGITS.size)
                if (n == shownDigits) return@addUpdateListener
                shownDigits = n
                if (n == 0) return@addUpdateListener
                inputField.text = context.getString(R.string.intro_tour_amount, money.format(DIGITS[n - 1]))

                // Subtle tactile bounce on each digit typed
                inputField.animate().cancel()
                inputField.scaleX = 1.05f
                inputField.scaleY = 1.05f
                inputField.animate().scaleX(1f).scaleY(1f).setDuration(150)
                    .setInterpolator(IntroTourActivity.EASE_OUT).start()
            }
            start()
        }

        // ── 3. Date field highlights ─────────────────────────────────────────────
        val dateHighlightStart = typeStart + (KEYPRESS_MS * DIGITS.size) + 150L
        schedule(dateHighlightStart) {
            dateFieldBox.animate().alpha(1f).setDuration(200).start()
        }

        // ── 4. Cursor clicks "Save Balance" ──────────────────────────────────────
        val saveClickStart = dateHighlightStart + 450L
        schedule(saveClickStart) {
            simulateTap(saveBtn) {
                saveBtn.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80)
                    .withEndAction {
                        saveBtn.animate().scaleX(1f).scaleY(1f).setDuration(80).start()

                        // Dissolve Setup Card
                        setupCard.animate()
                            .alpha(0f)
                            .scaleX(0.92f)
                            .scaleY(0.92f)
                            .setDuration(260)
                            .setInterpolator(IntroTourActivity.EASE_IN)
                            .withEndAction {
                                // Bloom Wallet Ring Group
                                ringGroup.animate()
                                    .alpha(1f)
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .setDuration(350)
                                    .setInterpolator(IntroTourActivity.EASE_OUT)
                                    .start()

                                // Top tentative date banner fades in
                                tentativeTop.animate()
                                    .alpha(1f)
                                    .setDuration(400)
                                    .setInterpolator(IntroTourActivity.EASE_OUT)
                                    .start()

                                // Sweep Ring from 0 to 100% smoothly
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
        }
    }

    override fun sceneDurationMs(): Long {
        return 7000L
    }

    private val Float.dp: Float get() = this * resources.displayMetrics.density

    private companion object {
        const val KEYPRESS_MS = 280L
        const val RING_DURATION_MS = 1500L
        val DIGITS = intArrayOf(2, 20, 200, 2000)
    }
}
