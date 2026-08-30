package com.cash.dash

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * Page 3: what the payment costs you, shown while you can still stop.
 *
 * Three allocations arrive and their bars fill. Two land inside their limit. The third crosses
 * it, and on the frame it crosses, the bar swaps to the app's own red fill — the same
 * `spent >= limit` test ScannerActivity applies in its allocation chooser.
 *
 * ### Why one of them is over
 *
 * Showing a person over budget on an intro screen looks, at first, like a strange thing to
 * advertise. It is the entire argument. Three green bars would say the app draws charts; one
 * red bar says the app will tell you something you would rather not hear, at the only moment
 * telling you is any use. If every bar were healthy this page could be deleted and nothing
 * would be lost.
 *
 * ### Why the red arrives late
 *
 * The swap is deliberately not applied at the start. The bar fills in the app's normal colour
 * and turns red **as it passes the limit**, so the colour reads as a threshold being crossed
 * rather than a label the row was already wearing.
 */
class IntroLimitsScene @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), IntroScene {

    private class Row(
        val root: View,
        val fill: View,
        val figures: TextView,
        val spent: Int,
        val limit: Int
    ) {
        /** How far the bar travels, clamped: an over-limit bar stops at full, as in the app. */
        val target: Float get() = (spent.toFloat() / limit).coerceAtMost(1f)
        /** Where in that travel the limit sits. 1 for a row that never reaches it. */
        val threshold: Float get() = if (spent > limit) limit.toFloat() / spent else 1f
        val overruns: Boolean get() = spent >= limit
    }

    private val chip: TextView
    private val rows: List<Row>

    private var barAnimator: ValueAnimator? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_intro_scene_limits, this, true)
        chip = findViewById(R.id.introLimitsChip)

        rows = listOf(
            bind(R.id.introLimitsRow1, R.drawable.ic_category_food, R.string.intro_limits_one, 350, 650),
            bind(R.id.introLimitsRow2, R.drawable.ic_category_shopping, R.string.intro_limits_three, 350, 200),
            bind(R.id.introLimitsRow3, R.drawable.ic_category_transport, R.string.intro_limits_two, 0, 500)
        )
    }

    private fun bind(rowId: Int, iconRes: Int, nameRes: Int, spent: Int, limit: Int): Row {
        val root = findViewById<View>(rowId)
        root.findViewById<ImageView>(R.id.introLimitIcon).setImageResource(iconRes)
        root.findViewById<TextView>(R.id.introLimitName).setText(nameRes)
        val figures = root.findViewById<TextView>(R.id.introLimitFigures)
        val fill = root.findViewById<View>(R.id.introLimitFill)
        // Revealed with scaleX, so it has to grow from its left edge, not its middle.
        fill.pivotX = 0f
        return Row(root, fill, figures, spent, limit)
    }

    override fun resetScene() {
        barAnimator?.cancel(); barAnimator = null
        chip.animate().cancel()
        chip.alpha = 0f
        chip.translationY = RISE_DP.dp

        for (row in rows) {
            row.root.animate().cancel()
            row.root.alpha = 0f
            row.root.translationY = RISE_DP.dp
            row.fill.scaleX = 0f
            row.fill.setBackgroundResource(R.drawable.bg_glass_progress_fill)
            // Cleared with the background, not just alongside it. Leaving the tag saying
            // "red" while the bar has been reset to normal would make the next run skip the
            // swap entirely, and the page would play once and never turn red again.
            row.fill.setTag(R.id.intro_bar_state_tag, null)
            row.figures.text = context.getString(R.string.intro_limits_figures, 0, row.limit)
        }
    }

    override fun playScene() {
        chip.animate().alpha(1f).translationY(0f)
            .setStartDelay(0).setDuration(360)
            .setInterpolator(IntroTourActivity.EASE_OUT).start()

        rows.forEachIndexed { i, row ->
            row.root.animate().alpha(1f).translationY(0f)
                .setStartDelay(160 + ROW_STAGGER_MS * i).setDuration(420)
                .setInterpolator(IntroTourActivity.EASE_OUT).start()
        }

        // One animator for all three bars and all three figures, so a row's number can never
        // land on a different frame from its own bar.
        barAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = 520
            duration = BAR_MS
            interpolator = IntroTourActivity.EASE_OUT
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                rows.forEachIndexed { i, row ->
                    val start = BAR_STAGGER * i
                    val local = ((f - start) / (1f - BAR_STAGGER * (rows.size - 1)))
                        .coerceIn(0f, 1f)
                    row.fill.scaleX = row.target * local

                    val shown = (row.spent * local).toInt()
                    row.figures.text = context.getString(R.string.intro_limits_figures, shown, row.limit)

                    if (row.overruns) {
                        // Swapped on the frame the bar passes the limit, not before it.
                        val res = if (local >= row.threshold) {
                            R.drawable.bg_glass_progress_fill_red
                        } else {
                            R.drawable.bg_glass_progress_fill
                        }
                        if (row.fill.getTag(R.id.intro_bar_state_tag) != res) {
                            row.fill.setTag(R.id.intro_bar_state_tag, res)
                            row.fill.setBackgroundResource(res)
                        }
                    }
                }
            }
            start()
        }
    }

    private val Float.dp: Float get() = this * resources.displayMetrics.density

    private companion object {
        const val ROW_STAGGER_MS = 110L
        const val BAR_MS = 1150L
        const val BAR_STAGGER = 0.13f
        const val RISE_DP = 16f
    }
}
