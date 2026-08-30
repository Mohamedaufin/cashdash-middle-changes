package com.cash.dash

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * The four intro pages, for [androidx.viewpager2.widget.ViewPager2].
 *
 * Every page is its own type, so the RecyclerView underneath ViewPager2 can never hand a
 * scan page's view back as a limits page. Combined with an `offscreenPageLimit` of three in
 * [IntroTourActivity], all four are created once and live for the life of the screen — which
 * is what lets the activity hold references to them and drive their animations directly,
 * rather than fishing a view holder out of the pager on every page change.
 *
 * There is nothing to bind: each page's content is fixed and each page animates itself
 * through [IntroScene]. onBindViewHolder is therefore deliberately empty.
 */
class IntroPageAdapter : RecyclerView.Adapter<IntroPageAdapter.PageHolder>() {

    class PageHolder(val scene: View) : RecyclerView.ViewHolder(scene)

    /**
     * Created pages, keyed by position. A map rather than a list because onCreateViewHolder
     * is called in whatever order the pager decides to prefetch, so appending to a list would
     * key page 2 under index 0 the moment the pager built it first.
     */
    private val pages = HashMap<Int, View>(PAGE_COUNT)

    /** The page at [position], or null if the pager has not built it yet. */
    fun pageAt(position: Int): View? = pages[position]

    override fun getItemCount() = PAGE_COUNT

    /** One view type per position: no page is ever recycled into another page's slot. */
    override fun getItemViewType(position: Int) = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val context = parent.context
        val scene: View = when (viewType) {
            PAGE_WALLET -> IntroWalletScene(context)
            PAGE_SCAN -> IntroScanScene(context)
            PAGE_LIMITS -> IntroLimitsScene(context)
            else -> IntroFeaturesScene(context)
        }
        scene.layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            RecyclerView.LayoutParams.MATCH_PARENT
        )
        // A page arriving on screen for the first time must not be showing the end of an
        // animation it has never played.
        (scene as? IntroScene)?.resetScene()
        pages[viewType] = scene
        return PageHolder(scene)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) = Unit

    companion object {
        const val PAGE_WALLET = 0
        const val PAGE_SCAN = 1
        const val PAGE_LIMITS = 2
        const val PAGE_FEATURES = 3
        const val PAGE_COUNT = 4
    }
}
