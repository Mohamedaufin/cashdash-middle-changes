package com.cash.dash

import android.animation.ValueAnimator
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * Page 3: TapTrack — Fast 3-Step Flow:
 * 1. Open TapTrack floating bubble
 * 2. Amount typed (with prefilled Shopping allocation)
 * 3. Save Expense clicked -> Success badge
 */
class IntroTapTrackScene @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), IntroScene {

    private val phoneContainer: View
    private val bubble: View
    private val dimOverlay: View
    private val card: View
    private val amountText: TextView
    private val saveBtn: TextView
    private val toast: View
    private val cursor: ImageView

    private var typeAnim: ValueAnimator? = null
    private var shownDigits = -1

    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0

    init {
        clipChildren = false
        clipToPadding = false
        LayoutInflater.from(context).inflate(R.layout.view_intro_scene_taptrack, this, true)

        phoneContainer = findViewById(R.id.introTapTrackPhone)
        bubble = findViewById(R.id.introTapTrackBubble)
        dimOverlay = findViewById(R.id.introTapTrackDim)
        card = findViewById(R.id.introTapTrackCard)
        amountText = findViewById(R.id.introTapTrackAmount)
        saveBtn = findViewById(R.id.introTapTrackSaveBtn)
        toast = findViewById(R.id.introTapTrackToast)
        cursor = findViewById(R.id.introTapTrackCursor)
    }

    override fun resetScene() {
        generation++
        handler.removeCallbacksAndMessages(null)
        typeAnim?.cancel()
        typeAnim = null

        val all = listOf(bubble, dimOverlay, card, toast, cursor, saveBtn)
        for (v in all) v.animate().cancel()

        bubble.alpha = 1f
        bubble.scaleX = 1f
        bubble.scaleY = 1f
        bubble.translationX = 0f
        bubble.translationY = 0f

        dimOverlay.alpha = 0f

        card.alpha = 0f
        card.scaleX = 0.6f
        card.scaleY = 0.6f
        card.translationX = -40f.dp
        card.translationY = -60f.dp

        amountText.text = ""
        shownDigits = -1

        toast.alpha = 0f
        toast.scaleX = 0.8f
        toast.scaleY = 0.8f
        toast.translationY = 10f.dp

        cursor.alpha = 0f
        cursor.scaleX = 1f
        cursor.scaleY = 1f
    }

    private fun schedule(delayMs: Long, action: () -> Unit) {
        val gen = generation
        handler.postDelayed({
            if (gen == generation) action()
        }, delayMs)
    }

    private fun simulateCursorTap(targetView: View, onTapped: () -> Unit) {
        targetView.post {
            val targetLoc = IntArray(2)
            targetView.getLocationInWindow(targetLoc)
            val parentLoc = IntArray(2)
            phoneContainer.getLocationInWindow(parentLoc)

            val targetTipX = (targetLoc[0] - parentLoc[0]) + (targetView.width * 0.45f)
            val targetTipY = (targetLoc[1] - parentLoc[1]) + (targetView.height * 0.45f)

            cursor.pivotX = 0f
            cursor.pivotY = 0f

            if (cursor.alpha == 0f) {
                cursor.translationX = targetTipX + 16f.dp
                cursor.translationY = targetTipY + 16f.dp
            }

            cursor.animate()
                .alpha(1f)
                .translationX(targetTipX)
                .translationY(targetTipY)
                .setDuration(260)
                .setInterpolator(IntroTourActivity.EASE_OUT)
                .withEndAction {
                    // Click down
                    cursor.animate().scaleX(0.85f).scaleY(0.85f)
                        .setDuration(80)
                        .withEndAction {
                            onTapped()
                            // Release & pause
                            cursor.animate().scaleX(1f).scaleY(1f)
                                .setDuration(120)
                                .start()
                        }
                        .start()
                }
                .start()
        }
    }

    override fun playScene() {
        // ── 1. Floating bubble pulses in on top-left ──────────────────────────
        bubble.alpha = 0f
        bubble.scaleX = 0.4f
        bubble.scaleY = 0.4f
        bubble.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(350)
            .setInterpolator(OVERSHOOT)
            .start()

        // ── 2. Cursor taps Floating Bubble -> Tracker Card Expands ───────────
        schedule(1000L) {
            simulateCursorTap(bubble) {
                bubble.animate().scaleX(0.7f).scaleY(0.7f).alpha(0f).setDuration(200).start()
                dimOverlay.animate().alpha(0.6f).setDuration(250).start()

                card.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationX(0f)
                    .translationY(0f)
                    .setDuration(350)
                    .setInterpolator(OVERSHOOT)
                    .start()
            }
        }

        // ── 3. Cursor taps Amount field & types 350 ────────────────────────────
        val typeAmountStart = 2400L
        schedule(typeAmountStart) {
            simulateCursorTap(amountText) {
                val digits = arrayOf("3", "35", "350")
                typeAnim = ValueAnimator.ofFloat(0f, digits.size.toFloat()).apply {
                    duration = 180L * digits.size
                    addUpdateListener { a ->
                        val n = (a.animatedValue as Float).toInt().coerceIn(0, digits.size)
                        if (n == shownDigits) return@addUpdateListener
                        shownDigits = n
                        val str = if (n == 0) "" else digits[n - 1]
                        amountText.text = str
                    }
                    start()
                }
            }
        }

        // ── 4. Cursor clicks "Save Expense" ────────────────────────────────────
        val saveClickStart = typeAmountStart + 1600L
        schedule(saveClickStart) {
            simulateCursorTap(saveBtn) {
                saveBtn.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80)
                    .withEndAction {
                        saveBtn.animate().scaleX(1f).scaleY(1f).setDuration(100).start()

                        // Card collapses back into the bubble
                        card.animate()
                            .alpha(0f)
                            .scaleX(0.6f)
                            .scaleY(0.6f)
                            .translationX(-40f.dp)
                            .translationY(-60f.dp)
                            .setDuration(280)
                            .setInterpolator(IntroTourActivity.EASE_IN)
                            .start()

                        dimOverlay.animate().alpha(0f).setDuration(250).start()

                        // Bubble reappears
                        bubble.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setStartDelay(200)
                            .setDuration(250)
                            .setInterpolator(OVERSHOOT)
                            .start()

                        // Green Success Toast pops up
                        toast.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .translationY(0f)
                            .setStartDelay(250)
                            .setDuration(300)
                            .setInterpolator(OVERSHOOT)
                            .start()

                        // Cursor fades out
                        cursor.animate().alpha(0f).setDuration(250).start()
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
        val OVERSHOOT = android.view.animation.OvershootInterpolator(1.2f)
    }
}
