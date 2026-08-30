package com.cash.dash

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout

/**
 * Page 4: TapTrack, Finminder and reports — the three that do not need a page each.
 *
 * Three rows arrive, then each tile plays one beat of its own: the TapTrack bubble rises into
 * place, the Finminder bell rings once, the report's arrow drops the way a file lands in
 * Downloads.
 *
 * ### Why the beats are staggered and why none of them loop
 *
 * A page of three simultaneously animating icons is a page of noise, and looping them would
 * make the screen restless for as long as anyone left it open. Each beat fires
 * [BEAT_STAGGER_MS] after the last, so exactly one thing is moving at any moment and the eye
 * is walked down the list in reading order. Each plays once and stops. What is left after two
 * seconds is a still, legible list — which is what this page is actually for.
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

    init {
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
        for (v in rows + listOf(bubble, bell, doc)) v.animate().cancel()

        for (row in rows) {
            row.alpha = 0f
            row.translationY = RISE_DP.dp
        }
        bubble.alpha = 0f
        bubble.translationY = BUBBLE_RISE_DP.dp
        bell.rotation = 0f
        doc.translationY = 0f
        doc.alpha = 1f
    }

    override fun playScene() {
        rows.forEachIndexed { i, row ->
            row.animate().alpha(1f).translationY(0f)
                .setStartDelay(ROW_STAGGER_MS * i).setDuration(440)
                .setInterpolator(IntroTourActivity.EASE_OUT).start()
        }

        // TapTrack: the bubble arriving over another app.
        bubble.animate().alpha(1f).translationY(0f)
            .setStartDelay(FIRST_BEAT_MS).setDuration(360)
            .setInterpolator(IntroTourActivity.EASE_OUT).start()

        // Finminder: one ring. Over-rotate slightly against the swing, then settle — a bell
        // that returns straight to centre reads as a wobble, not a strike.
        bell.animate().rotation(-13f)
            .setStartDelay(FIRST_BEAT_MS + BEAT_STAGGER_MS).setDuration(130)
            .setInterpolator(IntroTourActivity.EASE_OUT)
            .withEndAction {
                bell.animate().rotation(9f).setDuration(150)
                    .setInterpolator(IntroTourActivity.EASE_OUT)
                    .withEndAction {
                        bell.animate().rotation(0f).setDuration(220)
                            .setInterpolator(IntroTourActivity.EASE_OUT).start()
                    }.start()
            }.start()

        // Reports: the file leaving for Downloads. Drops and fades, then returns from above,
        // so the tile ends holding its icon rather than an empty square.
        doc.animate().translationY(DOC_DROP_DP.dp).alpha(0f)
            .setStartDelay(FIRST_BEAT_MS + BEAT_STAGGER_MS * 2).setDuration(300)
            .setInterpolator(IntroTourActivity.EASE_IN)
            .withEndAction {
                doc.translationY = -DOC_DROP_DP.dp
                doc.animate().translationY(0f).alpha(1f).setDuration(320)
                    .setInterpolator(IntroTourActivity.EASE_OUT).start()
            }.start()
    }

    private val Float.dp: Float get() = this * resources.displayMetrics.density

    private companion object {
        const val ROW_STAGGER_MS = 120L
        const val FIRST_BEAT_MS = 620L
        const val BEAT_STAGGER_MS = 280L
        const val RISE_DP = 18f
        const val BUBBLE_RISE_DP = 10f
        const val DOC_DROP_DP = 9f
    }
}
