package com.cash.dash

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.sin

/**
 * The animated welcome that plays ahead of the login and register screens.
 *
 * Three scenes build themselves, hold, and slide off to the left; after the third it starts
 * again from the first and keeps going until the user taps through.
 *
 * The continue arrow is the part with rules attached. It stays hidden until the animation has
 * been all the way to the end of the third scene once, which is what makes the first run feel
 * like something worth watching rather than a gate to click past. After that it stays on
 * screen for good, through every subsequent loop, so nobody who has already seen it has to
 * sit through the whole thing again to leave.
 *
 * "Once" is measured per process, not per install: [arrowUnlocked] is a static, so swiping the
 * app out of Recents takes the flag with it and the next cold start behaves like a first
 * attempt. That is the requested behaviour and it is why the flag is deliberately NOT written
 * to SharedPreferences.
 */
class IntroActivity : ThemedActivity() {

    /**
     * One element of a scene and how it arrives.
     *
     * [dx], [dy] and [driftY] are in dp and converted at use. [rotation] and [scale] are where
     * the piece starts; every piece finishes at its laid-out position, upright and full size,
     * so the layout stays the single source of truth for where things actually are.
     */
    private class Piece(
        val id: Int,
        val delay: Long,
        val dx: Float = 0f,
        val dy: Float = 0f,
        val scale: Float = 1f,
        val rotation: Float = 0f,
        val duration: Long = 620L,
        /** Ease out rather than settle past the mark. For anything that should not bounce. */
        val soft: Boolean = false,
        val driftY: Float = 0f,
        val driftRotation: Float = 0f
    )

    /** A piece that has landed and is now idling. */
    private class Drifter(
        val view: View,
        val phase: Float,
        val amplitudeY: Float,
        val amplitudeRotation: Float
    )

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: View
    private lateinit var particles: IntroParticlesView
    private lateinit var headline: TextView
    private lateinit var nextButton: ImageView
    private lateinit var sceneRoots: List<ViewGroup>
    private lateinit var dots: List<View>

    private val drifters = ArrayList<Drifter>()
    private var driftAnimator: ValueAnimator? = null
    private var dotAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    private var currentScene = 0
    private var navigated = false

    /**
     * Cancels work already queued on [handler]. Every scheduled step carries the generation it
     * was queued under and drops itself if that no longer matches, which is what lets a pause,
     * a scene change or a tap on the arrow abandon a half-run timeline without having to hold
     * a reference to each pending Runnable.
     */
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_intro)

        root = findViewById(R.id.introRoot)
        particles = findViewById(R.id.introParticles)
        headline = findViewById(R.id.introHeadline)
        nextButton = findViewById(R.id.introNext)
        sceneRoots = listOf(
            findViewById(R.id.sceneOne),
            findViewById(R.id.sceneTwo),
            findViewById(R.id.sceneThree)
        )
        dots = listOf(
            findViewById(R.id.introDot1),
            findViewById(R.id.introDot2),
            findViewById(R.id.introDot3)
        )

        // The window is edge to edge, so the copy and the arrow would otherwise sit under the
        // gesture bar and the artwork under the status bar.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        nextButton.setOnClickListener { openEntry() }
    }

    override fun onResume() {
        super.onResume()
        particles.start()
        startDrift()
        if (arrowUnlocked) {
            nextButton.visibility = View.VISIBLE
            nextButton.alpha = 1f
            startPulse()
        }
        // Replay whichever scene was showing. The timeline is a chain of delayed callbacks and
        // there is nothing left of it to resume into, and restarting the loop from scene one
        // would be the wrong repair: someone who glanced at a notification should come back to
        // where they were, not to the beginning.
        playScene(currentScene)
    }

    override fun onPause() {
        super.onPause()
        stopTimeline()
        particles.stop()
        driftAnimator?.cancel()
        driftAnimator = null
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTimeline()
        dotAnimator?.cancel()
        dotAnimator = null
    }

    private fun stopTimeline() {
        generation++
        handler.removeCallbacksAndMessages(null)
    }

    // ---------------------------------------------------------------- the loop

    private fun playScene(index: Int) {
        val gen = ++generation
        currentScene = index
        drifters.clear()

        sceneRoots.forEachIndexed { i, scene ->
            scene.visibility = if (i == index) View.VISIBLE else View.INVISIBLE
        }

        applyHeadline(index)
        highlightDot(index)

        val scene = sceneRoots[index]
        val shift = resources.displayMetrics.widthPixels * SLIDE_FRACTION

        scene.animate().cancel()
        scene.translationX = shift
        scene.alpha = 0f
        scene.animate()
            .translationX(0f).alpha(1f)
            .setStartDelay(0)
            .setDuration(SLIDE_IN_MS)
            .setInterpolator(EASE_OUT)
            .start()

        headline.animate().cancel()
        headline.translationX = shift
        headline.alpha = 0f
        headline.animate()
            .translationX(0f).alpha(1f)
            .setStartDelay(70)
            .setDuration(SLIDE_IN_MS)
            .setInterpolator(EASE_OUT)
            .start()

        val builtBy = buildScene(gen, scene, SCENE_PIECES[index])
        schedule(gen, builtBy + HOLD_MS) { endScene(gen, index) }
    }

    /** Returns the moment, in ms from now, at which the last piece finishes arriving. */
    private fun buildScene(gen: Int, scene: ViewGroup, pieces: List<Piece>): Long {
        var built = 0L
        for (piece in pieces) {
            val view = scene.findViewById<View>(piece.id) ?: continue
            view.animate().cancel()
            view.alpha = 0f
            view.translationX = piece.dx.dp
            view.translationY = piece.dy.dp
            view.scaleX = piece.scale
            view.scaleY = piece.scale
            view.rotation = piece.rotation
            view.animate()
                .alpha(1f)
                .translationX(0f).translationY(0f)
                .scaleX(1f).scaleY(1f)
                .rotation(0f)
                .setStartDelay(piece.delay)
                .setDuration(piece.duration)
                .setInterpolator(if (piece.soft) EASE_OUT else SETTLE)
                .withEndAction {
                    // cancel() skips this, so a piece torn down mid flight never idles.
                    if (gen == generation && (piece.driftY != 0f || piece.driftRotation != 0f)) {
                        drifters += Drifter(
                            view,
                            drifters.size * PHASE_STEP,
                            piece.driftY.dp,
                            piece.driftRotation
                        )
                    }
                }
                .start()
            built = maxOf(built, piece.delay + piece.duration)
        }
        return built
    }

    private fun endScene(gen: Int, index: Int) {
        if (gen != generation) return
        if (index == sceneRoots.lastIndex && !arrowUnlocked) {
            arrowUnlocked = true
            revealArrow()
            schedule(gen, ARROW_DWELL_MS) { exitScene(gen, index) }
            return
        }
        exitScene(gen, index)
    }

    private fun exitScene(gen: Int, index: Int) {
        if (gen != generation) return
        val shift = -resources.displayMetrics.widthPixels * SLIDE_FRACTION

        sceneRoots[index].animate()
            .translationX(shift).alpha(0f)
            .setStartDelay(0)
            .setDuration(SLIDE_OUT_MS)
            .setInterpolator(EASE_IN)
            .start()
        headline.animate()
            .translationX(shift).alpha(0f)
            .setStartDelay(0)
            .setDuration(SLIDE_OUT_MS)
            .setInterpolator(EASE_IN)
            .start()

        schedule(gen, SLIDE_OUT_MS) { playScene((index + 1) % sceneRoots.size) }
    }

    private fun schedule(gen: Int, delay: Long, action: () -> Unit) {
        handler.postDelayed({ if (gen == generation) action() }, delay)
    }

    // ---------------------------------------------------------------- idle motion

    /**
     * One animator for every idling piece, rather than one animator each. It runs for as long
     * as the screen is up, so the difference is worth having.
     */
    private fun startDrift() {
        driftAnimator?.cancel()
        driftAnimator = ValueAnimator.ofFloat(0f, TAU).apply {
            duration = DRIFT_PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                for (i in drifters.indices) {
                    val drifter = drifters[i]
                    if (drifter.amplitudeY != 0f) {
                        drifter.view.translationY = sin(t + drifter.phase) * drifter.amplitudeY
                    }
                    if (drifter.amplitudeRotation != 0f) {
                        // A slower period than the bob, so the two never line up into a
                        // single mechanical wobble.
                        drifter.view.rotation =
                            sin(t * 0.72f + drifter.phase) * drifter.amplitudeRotation
                    }
                }
            }
            start()
        }
    }

    // ---------------------------------------------------------------- chrome

    private fun applyHeadline(index: Int) {
        val lead = getString(HEADLINES[index].first)
        val rest = getString(HEADLINES[index].second)
        val builder = SpannableStringBuilder()

        if (lead.isNotEmpty()) {
            builder.append(lead)
            builder.setSpan(
                ForegroundColorSpan(ThemeHelper.resolveColorAttr(this, R.attr.textPrimaryColor)),
                0,
                builder.length,
                Spanned.SPAN_INCLUSIVE_EXCLUSIVE
            )
            builder.append(' ')
        }

        val restStart = builder.length
        builder.append(rest)
        builder.setSpan(
            ForegroundColorSpan(ThemeHelper.resolveColorAttr(this, R.attr.textGreenColor)),
            restStart,
            builder.length,
            Spanned.SPAN_INCLUSIVE_EXCLUSIVE
        )

        headline.text = builder
    }

    private fun highlightDot(index: Int) {
        dotAnimator?.cancel()

        val active = ACTIVE_DOT_DP.dp.toInt()
        val inactive = DOT_DP.dp.toInt()
        val from = IntArray(dots.size) { dots[it].layoutParams.width }
        val to = IntArray(dots.size) { if (it == index) active else inactive }

        dots.forEachIndexed { i, dot ->
            dot.setBackgroundResource(
                if (i == index) R.drawable.bg_intro_dot_active else R.drawable.bg_intro_dot
            )
        }

        dotAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320L
            interpolator = EASE_OUT
            addUpdateListener { animator ->
                val f = animator.animatedValue as Float
                dots.forEachIndexed { i, dot ->
                    val params = dot.layoutParams
                    params.width = (from[i] + (to[i] - from[i]) * f).toInt()
                    dot.layoutParams = params
                }
            }
            start()
        }
    }

    private fun revealArrow() {
        if (nextButton.visibility == View.VISIBLE) return
        nextButton.alpha = 0f
        nextButton.scaleX = 0.55f
        nextButton.scaleY = 0.55f
        nextButton.visibility = View.VISIBLE
        nextButton.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(0)
            .setDuration(520L)
            .setInterpolator(OvershootInterpolator(1.6f))
            .withEndAction { startPulse() }
            .start()
    }

    /** A breath, not a throb: enough to read as live without pulling the eye off the copy. */
    private fun startPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(0f, TAU).apply {
            duration = 2600L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val scale = 1f + sin(animator.animatedValue as Float) * 0.035f
                nextButton.scaleX = scale
                nextButton.scaleY = scale
            }
            start()
        }
    }

    @Suppress("DEPRECATION")
    private fun openEntry() {
        if (navigated) return
        navigated = true
        stopTimeline()
        
        val entryIntent = Intent(this, EntryActivity::class.java)
        if (intent.extras != null) {
            entryIntent.putExtras(intent.extras!!)
        }
        startActivity(entryIntent)
        finish()
        
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    private val Float.dp: Float
        get() = this * resources.displayMetrics.density

    private companion object {
        /**
         * Survives an activity restart but not the process, which is exactly the requested
         * behaviour: reopening after a swipe from Recents starts over as a first attempt.
         */
        @Volatile
        var arrowUnlocked = false

        const val TAU = 6.2831855f
        const val PHASE_STEP = 0.8f

        const val HOLD_MS = 1900L
        const val ARROW_DWELL_MS = 1400L
        const val SLIDE_IN_MS = 520L
        const val SLIDE_OUT_MS = 420L
        const val DRIFT_PERIOD_MS = 5400L
        const val SLIDE_FRACTION = 0.16f

        const val DOT_DP = 11f
        const val ACTIVE_DOT_DP = 28f

        val EASE_OUT = PathInterpolator(0.2f, 0f, 0f, 1f)
        val EASE_IN = PathInterpolator(0.4f, 0f, 1f, 1f)
        /** Mild on purpose: alpha rides the same interpolator and must not overshoot far. */
        val SETTLE = OvershootInterpolator(1.2f)

        val HEADLINES = arrayOf(
            R.string.intro_one_lead to R.string.intro_one_rest,
            R.string.intro_two_lead to R.string.intro_two_rest,
            R.string.intro_three_lead to R.string.intro_three_rest
        )

        val SCENE_PIECES = arrayOf(
            // One: the archway assembles, the stairs climb into it, then the satellites.
            listOf(
                Piece(R.id.introOneArch, delay = 0, dy = 26f, scale = 0.62f, duration = 760),
                Piece(R.id.introOneStep1, delay = 380, dy = 22f, scale = 0.9f, duration = 420),
                Piece(R.id.introOneStep2, delay = 470, dy = 22f, scale = 0.9f, duration = 420),
                Piece(R.id.introOneStep3, delay = 560, dy = 22f, scale = 0.9f, duration = 420),
                Piece(R.id.introOneStep4, delay = 650, dy = 22f, scale = 0.9f, duration = 420),
                Piece(R.id.introOneBrand, delay = 840, scale = 0.86f, duration = 560, soft = true),
                Piece(
                    R.id.introOneChevron, delay = 240, dx = -150f, rotation = -18f,
                    duration = 780, driftY = 5f, driftRotation = 2.5f
                ),
                Piece(
                    R.id.introOneWallet, delay = 1000, dy = -34f, scale = 0.7f,
                    driftY = 7f, driftRotation = 3.5f
                ),
                Piece(
                    R.id.introOneCard, delay = 1100, dy = -34f, scale = 0.7f,
                    driftY = 6f, driftRotation = -3f
                ),
                Piece(
                    R.id.introOneCoin, delay = 1200, dx = -40f, scale = 0.7f,
                    driftY = 8f, driftRotation = 4f
                ),
                Piece(
                    R.id.introOneTag, delay = 1300, dx = 40f, scale = 0.7f,
                    driftY = 7f, driftRotation = -4f
                )
            ),
            // Two: the stack builds from the bottom up and the tick lands last.
            listOf(
                Piece(
                    R.id.introTwoPrism, delay = 120, dy = 70f, scale = 0.6f,
                    duration = 700, driftRotation = 1f
                ),
                Piece(
                    R.id.introTwoSlab, delay = 520, dy = -90f, rotation = -10f,
                    duration = 720, driftRotation = 1.6f
                ),
                Piece(
                    R.id.introTwoDisc, delay = 900, dy = -110f, rotation = 14f,
                    duration = 720, driftRotation = 2.4f
                ),
                Piece(
                    R.id.introTwoCheck, delay = 1420, dx = 90f, dy = -30f, rotation = -35f,
                    scale = 0.6f, duration = 700, driftY = 6f, driftRotation = 5f
                )
            ),
            // Three: the chevron swings in and everything it pays for gathers around it.
            listOf(
                Piece(
                    R.id.introThreeChevron, delay = 0, scale = 0.35f, rotation = -70f,
                    duration = 820, driftY = 7f, driftRotation = 5f
                ),
                Piece(
                    R.id.introThreeBag, delay = 420, dx = -40f, dy = -60f, scale = 0.7f,
                    duration = 640, driftY = 8f, driftRotation = 4f
                ),
                Piece(
                    R.id.introThreeWatch, delay = 520, dx = 40f, dy = -60f, scale = 0.7f,
                    duration = 640, driftY = 7f, driftRotation = -4f
                ),
                Piece(
                    R.id.introThreeLaptop, delay = 620, dx = 70f, scale = 0.7f,
                    duration = 640, driftY = 9f, driftRotation = 3f
                ),
                Piece(
                    R.id.introThreeShirt, delay = 720, dx = -70f, scale = 0.7f,
                    duration = 640, driftY = 8f, driftRotation = -3.5f
                ),
                Piece(
                    R.id.introThreeSuitcase, delay = 820, dx = 30f, dy = 60f, scale = 0.7f,
                    duration = 640, driftY = 7f, driftRotation = 4.5f
                ),
                Piece(
                    R.id.introThreePhone, delay = 920, dx = -30f, dy = 60f, scale = 0.7f,
                    duration = 640, driftY = 9f, driftRotation = -5f
                )
            )
        )
    }
}
