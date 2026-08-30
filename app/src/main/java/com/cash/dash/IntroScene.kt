package com.cash.dash

/**
 * One page of the pre-login intro.
 *
 * Each page owns its own choreography rather than the host activity holding four timelines
 * in a `when` over the page index. The pages have almost nothing in common — one types a
 * figure into a ring, one acquires a QR code, one fills limit bars, one rings a bell — so a
 * shared timeline would be four unrelated bodies of code sharing a function.
 *
 * The contract is deliberately two calls and no state query. [IntroTourActivity] decides
 * WHEN a page plays; a page decides only WHAT playing means. A page must therefore be safe
 * to reset and replay any number of times, including part way through its own animation,
 * because a swipe can land on a page that is still animating from the last visit.
 */
interface IntroScene {

    /**
     * Returns every animated property to its pre-animation value and cancels anything in
     * flight. Called immediately before [playScene], and on any page that is not the current
     * one, so an off-screen page is never left holding a half-finished frame for the next
     * time it is swiped to.
     */
    fun resetScene()

    /** Runs the page's timeline from the top. Always preceded by [resetScene]. */
    fun playScene()

    /**
     * How long this page's animation needs before the tour should advance, in ms.
     * Pages with longer sequences override this; pages happy with the default return 0,
     * which tells [IntroTourActivity] to use its own [HOLD_MS].
     */
    fun sceneDurationMs(): Long = 0
}
