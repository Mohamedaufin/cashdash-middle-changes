package com.cash.dash

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewpager2.widget.ViewPager2

/**
 * The animated welcome that plays ahead of the login and register screens.
 *
 * Four pages, each a piece of the app's own interface, in the order the product actually
 * works: set what you have, pay from inside the app, see what the payment costs you against
 * your limits, and then the three features that catch everything else. Each page earns the
 * next — page two without page three is just a QR scanner, and page three without page two
 * never explains how the app knew.
 *
 * ### What this screen deliberately does not do
 *
 * No lit background, no floating card stack, no colour that is not already load-bearing
 * somewhere else in the app. The one exception is the over-limit bar on page three, and it is
 * the app's own red on the app's own `spent >= limit` threshold. Spending the intro's only
 * colour there is the point: it is the single moment the product does something no ledger app
 * does.
 *
 * ### Swipe, and why almost none of it is written here
 *
 * A non-circular [ViewPager2] cannot scroll before its first page or past its last, which is
 * exactly the rule this screen needs, so there is no edge-locking code. The auto-advance loop
 * calls [ViewPager2.setCurrentItem] directly and is not subject to that limit, which is how
 * the tour can return to page one on its own while a forward swipe on page four does nothing.
 *
 * The 4 to 1 return is a cut, not a slide: dragging backwards through three pages to reach the
 * start looks like a mistake rather than a loop.
 *
 * ### The generation counter
 *
 * The timeline is a chain of delayed callbacks, so there is no single animator to cancel.
 * Every scheduled step carries the [generation] it was queued under and drops itself if that
 * no longer matches, which lets a pause, a swipe or a tap on the button abandon a half-run
 * timeline without holding a reference to each pending Runnable.
 */
class IntroTourActivity : ThemedActivity() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: View
    private lateinit var pager: ViewPager2
    private lateinit var brand: View
    private lateinit var eyebrow: TextView
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var cta: View
    private lateinit var dotsRow: View
    private lateinit var dots: List<View>

    private val adapter = IntroPageAdapter()

    private var dotAnimator: ValueAnimator? = null
    private var generation = 0
    private var currentPage = 0
    private var navigated = false

    /** False until the chrome has done its one-off arrival, so a resume does not replay it. */
    private var introduced = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_intro_tour)

        val rootV = findViewById<android.view.ViewGroup>(R.id.introTourRoot)
        rootV.clipChildren = false
        rootV.clipToPadding = false

        val pagerV = findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.introTourPager)
        pagerV.clipChildren = false
        pagerV.clipToPadding = false
        
        pagerV.post {
            val rv = pagerV.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView
            rv?.clipChildren = false
            rv?.clipToPadding = false
        }


        root = findViewById(R.id.introTourRoot)
        pager = findViewById(R.id.introTourPager)
        brand = findViewById(R.id.introTourBrand)
        eyebrow = findViewById(R.id.introTourEyebrow)
        title = findViewById(R.id.introTourTitle)
        body = findViewById(R.id.introTourBody)
        cta = findViewById(R.id.introTourCta)
        dotsRow = findViewById(R.id.introTourDots)
        dots = listOf(
            findViewById(R.id.introTourDot1),
            findViewById(R.id.introTourDot2),
            findViewById(R.id.introTourDot3),
            findViewById(R.id.introTourDot4)
        )

        pager.adapter = adapter
        // Every page stays alive, so the activity can drive their animations directly instead
        // of digging a view holder out of the pager on each change — and so a swiped-to page
        // is never mid-construction when it is asked to play.
        pager.offscreenPageLimit = IntroPageAdapter.PAGE_COUNT - 1

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                onPageArrived(position)
            }

            override fun onPageScrollStateChanged(state: Int) {
                // A finger on the pager cancels whatever advance was pending. Without this a
                // scheduled hop can fire mid-drag and fight the gesture.
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) stopTimeline()
            }
        })

        // The window is edge to edge, so the button would otherwise sit under the gesture bar
        // and the wordmark under the status bar.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        cta.setOnClickListener { openEntry() }

        applyCopy(0)
        highlightDot(0)
        if (ctaUnlocked) revealCta(animated = false)
    }

    override fun onResume() {
        super.onResume()
        if (!introduced) {
            introduced = true
            val chrome = mutableListOf(brand, eyebrow, title, body, dotsRow)
            if (ctaUnlocked) chrome += cta
            for (view in chrome) {
                view.alpha = 0f
                view.animate().alpha(1f).setStartDelay(100).setDuration(460)
                    .setInterpolator(EASE_OUT).start()
            }
        } else {
            // Everything the timeline animates has to be put back by hand. Pausing part way
            // through a transition leaves the copy at a fraction of its alpha, and replaying
            // the page does not touch it: the copy is only animated when the PAGE changes.
            val chrome = mutableListOf(brand, eyebrow, title, body, dotsRow)
            if (ctaUnlocked) chrome += cta
            for (view in chrome) {
                view.animate().cancel()
                view.alpha = 1f
                view.translationY = 0f
            }
        }
        // Nothing survives of a chain of delayed callbacks, so the page is replayed rather
        // than resumed. Restarting from page one would be the wrong repair: someone who
        // glanced at a notification should come back to where they were.
        playPage(++generation, currentPage, animateCopy = false)
    }

    override fun onPause() {
        super.onPause()
        stopTimeline()
        for (i in 0 until IntroPageAdapter.PAGE_COUNT) {
            (adapter.pageAt(i) as? IntroScene)?.resetScene()
        }
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

    /** Called for both an auto-advance and a swipe; the pager cannot tell them apart. */
    private fun onPageArrived(position: Int) {
        if (position == IntroPageAdapter.PAGE_COUNT - 1 && !ctaUnlocked) {
            ctaUnlocked = true
            revealCta(animated = true)
        }
        playPage(++generation, position, animateCopy = position != currentPage)
    }

    private fun playPage(gen: Int, position: Int, animateCopy: Boolean) {
        if (gen != generation) return
        currentPage = position

        applyCopy(position)
        highlightDot(position)

        if (animateCopy) {
            // The copy rises with the artwork rather than after it, so a page reads as one
            // thing arriving instead of two.
            for (line in listOf(eyebrow, title, body)) {
                line.animate().cancel()
                line.alpha = 0f
                line.translationY = COPY_RISE_DP.dp
                line.animate().alpha(1f).translationY(0f)
                    .setStartDelay(if (line === body) 60 else 0)
                    .setDuration(400).setInterpolator(EASE_OUT).start()
            }
        }

        // Every page but the current one is wound back, so a page swiped to later never opens
        // holding the last frame of its previous run.
        for (i in 0 until IntroPageAdapter.PAGE_COUNT) {
            val scene = adapter.pageAt(i) as? IntroScene ?: continue
            scene.resetScene()
            if (i == position) scene.playScene()
        }

        // Pages with longer animations declare their own duration; everyone else uses HOLD_MS.
        val scene = adapter.pageAt(position) as? IntroScene
        val hold = scene?.sceneDurationMs()?.takeIf { it > 0 } ?: HOLD_MS
        schedule(gen, hold) { advance(gen, position) }
    }

    private fun advance(gen: Int, from: Int) {
        if (gen != generation) return
        val last = IntroPageAdapter.PAGE_COUNT - 1

        if (from < last) {
            pager.setCurrentItem(from + 1, true)
            return
        }

        // Back to the start. Faded through rather than scrolled, because animating a pager
        // backwards across three pages to reach the first one reads as a mistake.
        pager.animate().alpha(0f).setDuration(LOOP_FADE_MS).setInterpolator(EASE_IN)
            .withEndAction {
                if (gen != generation) return@withEndAction
                pager.setCurrentItem(0, false)
                pager.animate().alpha(1f).setDuration(LOOP_FADE_MS)
                    .setInterpolator(EASE_OUT).start()
            }.start()
    }

    private fun schedule(gen: Int, delay: Long, action: () -> Unit) {
        handler.postDelayed({ if (gen == generation) action() }, delay)
    }

    // ---------------------------------------------------------------- chrome

    private fun applyCopy(index: Int) {
        eyebrow.setText(COPY[index].first)
        title.setText(COPY[index].second)
        body.setText(COPY[index].third)
    }

    private fun highlightDot(index: Int) {
        dotAnimator?.cancel()

        val active = ACTIVE_DOT_DP.dp.toInt()
        val inactive = DOT_DP.dp.toInt()
        val from = IntArray(dots.size) { dots[it].layoutParams.width }
        val to = IntArray(dots.size) { if (it == index) active else inactive }

        dots.forEachIndexed { i, dot ->
            dot.setBackgroundResource(
                if (i == index) R.drawable.bg_intro_dot_active_tour else R.drawable.bg_intro_dot
            )
        }

        dotAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300L
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

    private fun revealCta(animated: Boolean) {
        if (!animated) {
            cta.visibility = View.VISIBLE
            cta.alpha = 1f
            cta.scaleX = 1f
            cta.scaleY = 1f
            return
        }
        if (cta.visibility == View.VISIBLE) return
        cta.alpha = 0f
        cta.scaleX = 0.94f
        cta.scaleY = 0.94f
        cta.visibility = View.VISIBLE
        cta.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(220).setDuration(460)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()
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

    companion object {
        val EASE_OUT = PathInterpolator(0.2f, 0f, 0f, 1f)
        val EASE_IN = PathInterpolator(0.4f, 0f, 1f, 1f)

        /**
         * Whether the last page has been reached in this process.
         *
         * Survives an activity restart but not the process, so reopening after a swipe from
         * Recents behaves like a first attempt. That is the requested behaviour and it is why
         * this is deliberately NOT written to SharedPreferences. It is the same rule
         * [IntroActivity] applies to its continue arrow.
         */
        @Volatile
        private var ctaUnlocked = false

        /** How long a page holds before the tour moves itself on. */
        private const val HOLD_MS = 3400L
        private const val LOOP_FADE_MS = 260L
        private const val COPY_RISE_DP = 14f

        private const val DOT_DP = 9f
        private const val ACTIVE_DOT_DP = 24f

        /** Eyebrow, headline and body per page. */
        private val COPY = arrayOf(
            Triple(
                R.string.intro_tour_one_eyebrow,
                R.string.intro_tour_one_title,
                R.string.intro_tour_one_body
            ),
            Triple(
                R.string.intro_tour_two_eyebrow,
                R.string.intro_tour_two_title,
                R.string.intro_tour_two_body
            ),
            Triple(
                R.string.intro_tour_three_eyebrow,
                R.string.intro_tour_three_title,
                R.string.intro_tour_three_body
            ),
            Triple(
                R.string.intro_tour_four_eyebrow,
                R.string.intro_tour_four_title,
                R.string.intro_tour_four_body
            )
        )
    }
}
