package com.fluxx.android.ui.timeline

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

/**
 * TLV1 B2: the timeline's ONE horizontal scroll state, in TIME: the time under the playhead ([centreUs], Long us). A drag
 * or fling step of `delta` px moves it by `delta / pxPerUs` (the sub-microsecond rest is kept, so slow drags lose nothing),
 * clamped to `0..[endUs]` (the longest of the composition and the latest layer end). It is a [ScrollableState], so the
 * same `scrollable` gesture, platform fling decay and "scroll in progress" semantics as the old `horizontalScroll` apply.
 * - user drag / fling / edge scroll / animated returns go through [scroll] (they ARE a scroll in progress);
 * - playback follow and the settle after a seek use [jumpTo] (a state write: never "in progress", so never a scrub).
 * [pxPerUs] and [endUs] are set by the view on every composition (not state: they change with the zoom / document).
 */
@Stable
class TimelineScrollState(initialCentreUs: Long = 0) : ScrollableState {
    var centreUs by mutableLongStateOf(initialCentreUs); private set
    private var restUs = 0.0
    var pxPerUs = 1e-4
    var endUs = 0L

    private val inner = ScrollableState { delta -> consume(delta) }

    private fun consume(delta: Float): Float {
        if (pxPerUs <= 0.0 || delta == 0f) return 0f
        val now = centreUs + restUs
        val next = (now + delta / pxPerUs).coerceIn(0.0, endUs.coerceAtLeast(0).toDouble())
        val whole = kotlin.math.floor(next).toLong()
        centreUs = whole; restUs = next - whole
        return ((next - now) * pxPerUs).toFloat()
    }

    override val isScrollInProgress: Boolean get() = inner.isScrollInProgress
    override fun dispatchRawDelta(delta: Float): Float = inner.dispatchRawDelta(delta)
    override suspend fun scroll(scrollPriority: MutatePriority, block: suspend ScrollScope.() -> Unit) = inner.scroll(scrollPriority, block)
    override val canScrollForward: Boolean get() = centreUs < endUs
    override val canScrollBackward: Boolean get() = centreUs > 0

    /** Puts [us] under the playhead without a scroll in progress (playback follow, settling on the playhead). */
    fun jumpTo(us: Long) { val c = us.coerceIn(0L, endUs.coerceAtLeast(0)); if (c != centreUs || restUs != 0.0) { centreUs = c; restUs = 0.0 } }
    /** Eases [us] under the playhead (a scroll in progress, like the old animateScrollTo), landing exactly on it. */
    suspend fun animateTo(us: Long) {
        val target = us.coerceIn(0L, endUs.coerceAtLeast(0))
        animateScrollBy(((target - centreUs - restUs) * pxPerUs).toFloat())
        scroll { jumpTo(target) }
    }
    /** The scroll as a Double content pixel (for drags measured in content coordinates while the view auto-scrolls). */
    fun contentPx(): Double = (centreUs + restUs) * pxPerUs
}

/**
 * TLV1 B3: how a timeline child maps time to its OWN x. Every horizontal node is laid out for the window only (the viewport
 * plus half a viewport each side, see [com.fluxx.android.editor.TimelineViewport]); node-local x 0 is the content pixel
 * [originPx], so `x(t) = t × pxPerUs − originPx` stays a small number at any length. The origin moves in half-viewport
 * steps (a recomposition then); in between the window only translates.
 */
@Stable
class TimelineAxis(val pxPerUs: Double, private val origin: State<Long>, val widthPx: Int) {
    val originPx: Long get() = origin.value
    /** [timeUs]'s x in the window (px). */
    fun x(timeUs: Long): Float = (timeUs * pxPerUs - originPx).toFloat()
    fun x(timeUs: Double): Float = (timeUs * pxPerUs - originPx).toFloat()
    /** A whole-pixel content x (TL1 frame grid) in the window. */
    fun x(contentPx: Long, @Suppress("UNUSED_PARAMETER") wholePixel: Boolean): Float = (contentPx - originPx).toFloat()
    /** The composition time at window x [localX] (us, Double). */
    fun timeAt(localX: Float): Double = (localX.toDouble() + originPx) / pxPerUs
    /** The window's time range (us). */
    val startUs: Long get() = Math.round(originPx / pxPerUs)
    val endUs: Long get() = Math.round((originPx + widthPx) / pxPerUs)
}
