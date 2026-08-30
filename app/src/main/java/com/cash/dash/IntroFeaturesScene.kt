package com.cash.dash

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout

/**
 * Page 4: TapTrack, Finminder and Reports — the secondary features.
 *
 * Staggered arrival of the 3 feature cards, followed by gentle sequential micro-animations:
 * - TapTrack: floating overlay bubble pops into place.
 * - Finminder: reminder bell rings once.
 * - Reports: PDF document drops and settles.
 */
class IntroFeaturesScene @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), IntroScene {

    private val rows: List<View>
    private val bubble: View
    private val bell: View
    private val doc: View

    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0

    init {
        clipChildren = false
        clipToPadding = false
        LayoutInflater.from(context).inflate(R.layout.view_intro_scene_features, this, true)
        rows = listOf(
            findViewById(R.id.introFeatureRow1),
            findViewById(R.id.introFeatureRow2),
            findViewById(R.id.introFeatureRow3)
        )
        bubble = findViewById(R.id.introFeatureBubble)
        bell = findViewById(R.id.introFeatureBell)
        doc = findViewById(R.id.introFeatureDoc)
    }

    override fun resetScene() {
        generation++
        handler.removeCallbacksAndMessages(null)

        for (v in rows + listOf(bubble, bell, doc)) v.animate().cancel()

        for (row in rows) {
            row.alpha = 0f
            row.translationY = RISE_DP.dp
            row.scaleX = 0.96f
            row.scaleY = 0.96f
        }
        bubble.alpha = 0f
        bubble.translationY = BUBBLE_RISE_DP.dp
        bubble.scaleX = 0.5f
        bubble.scaleY = 0.5f
        bell.rotation = 0f
        doc.translationY = 0f
        doc.alpha = 1f
    }

    private fun schedule(delayMs: Long, action: () -> Unit) {
        val gen = generation
        handler.postDelayed({
            if (gen == generation) action()
        }, delayMs)
    }

    override fun playScene() {
        // ── Staggered entrance of cards ─────────────────────────────────────
        rows.forEachIndexed { i, row ->
            row.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(ROW_STAGGER_MS * i)
                .setDuration(440)
                .setInterpolator(IntroTourActivity.EASE_OUT)
                .start()
        }

        // ── TapTrack: floating overlay bubble pops up ─────────────────────────
        schedule(FIRST_BEAT_MS) {
            bubble.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1.3f)
                .scaleY(1.3f)
                .setDuration(220)
                .setInterpolator(IntroTourActivity.EASE_OUT)
                .withEndAction {
                    bubble.animate().scaleX(1f).scaleY(1f).setDuration(150)
                        .setInterpolator(IntroTourActivity.EASE_OUT).start()
                }
                .start()
        }

        // ── Finminder: gentle reminder bell swing ─────────────────────────────
        schedule(FIRST_BEAT_MS + BEAT_STAGGER_MS) {
            bell.animate().rotation(-14f)
                .setDuration(130)
                .setInterpolator(IntroTourActivity.EASE_OUT)
                .withEndAction {
                    bell.animate().rotation(10f).setDuration(150)
                        .setInterpolator(IntroTourActivity.EASE_OUT)
                        .withEndAction {
                            bell.animate().rotation(-6f).setDuration(170)
                                .setInterpolator(IntroTourActivity.EASE_OUT)
                                .withEndAction {
                                    bell.animate().rotation(0f).setDuration(200)
                                        .setInterpolator(IntroTourActivity.EASE_OUT).start()
                                }.start()
                        }.start()
                }.start()
        }

        // ── Reports: PDF file lands ready ─────────────────────────────────────
        schedule(FIRST_BEAT_MS + BEAT_STAGGER_MS * 2) {
            doc.animate().translationY(DOC_DROP_DP.dp).alpha(0f)
                .setDuration(260)
                .setInterpolator(IntroTourActivity.EASE_IN)
                .withEndAction {
                    doc.translationY = -DOC_DROP_DP.dp
                    doc.animate().translationY(0f).alpha(1f).setDuration(300)
                        .setInterpolator(IntroTourActivity.EASE_OUT).start()
                }.start()
        }
    }

    override fun sceneDurationMs(): Long {
        return 4500L
    }

    private val Float.dp: Float get() = this * resources.displayMetrics.density

    private companion object {
        const val ROW_STAGGER_MS = 120L
        const val FIRST_BEAT_MS = 500L
        const val BEAT_STAGGER_MS = 600L
        const val RISE_DP = 20f
        const val BUBBLE_RISE_DP = 10f
        const val DOC_DROP_DP = 10f
    }
}
