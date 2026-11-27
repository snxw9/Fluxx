package com.fluxx.android.editor

import com.fluxx.android.model.FrameRate

/**
 * TLV1: the timeline's horizontal axis as TIME (pure, JVM tested). The scroll state is the time under the playhead
 * ([centreUs], Long us); the zoom is dp per second; the playhead stays at the viewport's centre column ([centrePx], a whole
 * pixel, TL1). Everything on screen is RELATIVE to the playhead: a time's screen x is `centrePx + (t − centre) × pxPerUs`
 * computed in Long / Double, never an absolute Float pixel (at 3 h and frame zoom content x reaches ~7e7 px, where a Float
 * is 8 px coarse).
 *
 * Only a WINDOW of the timeline is laid out: [viewportPx] plus [marginPx] each side, starting at the content pixel
 * [windowOriginPx] (TL1 whole-pixel content coordinates, `TimelineScale.timeX`). The origin moves in steps of [marginPx],
 * so between steps the window only translates (drawn, not recomposed); the visible part always lies inside it.
 */
class TimelineViewport(
    val centreUs: Long,
    val dpPerSecond: Float,
    val density: Float,
    val viewportPx: Int,
    /** The longest of the composition and the latest layer end: the centre's upper bound. */
    val endUs: Long,
) {
    init { require(density > 0f && viewportPx > 0 && dpPerSecond > 0f) }
    val pxPerUs: Double = dpPerSecond.toDouble() * density / 1_000_000.0
    /** TL1: the playhead column, a whole pixel. */
    val centrePx: Int = Math.round(viewportPx / 2f)
    val marginPx: Int = viewportPx / 2
    /** The laid-out window: no timeline node is ever wider than this. */
    val windowWidthPx: Int = viewportPx + 2 * marginPx

    /** A time's whole-pixel content x (exact in Long at any length; the TL1 frame grid for frame times). */
    fun contentPx(timeUs: Long, rate: FrameRate? = null): Long = if (rate != null) TimelineScale(rate, pxPerUs).timeX(timeUs) else Math.round(timeUs * pxPerUs)
    /** The scroll as a content pixel: the content x under the playhead. */
    fun scrollPx(rate: FrameRate? = null): Long = contentPx(centreUs, rate)

    /** Screen x of [timeUs], relative to the playhead (Double, exact enough for any length). */
    fun screenX(timeUs: Long): Double = centrePx + (timeUs - centreUs) * pxPerUs
    /** The time at screen x (us, rounded to the nearest us). */
    fun timeAt(screenX: Double): Long = centreUs + Math.round((screenX - centrePx) / pxPerUs)

    /** The visible time range (the viewport). */
    val visibleUs: LongRange get() = timeAt(0.0)..timeAt(viewportPx.toDouble())

    /** The window's first content pixel (moves in [marginPx] steps); see [windowTranslationPx]. */
    fun windowOriginPx(rate: FrameRate? = null): Long = originFor(scrollPx(rate), centrePx, marginPx)
    /** Where the window's left edge sits on screen (px, a whole pixel; between −1.5 and −0.5 margins). */
    fun windowTranslationPx(rate: FrameRate? = null): Long = centrePx - (scrollPx(rate) - windowOriginPx(rate))
    /** The laid-out time range (the window, a little more than the visible range plus a margin each side). */
    fun windowUs(rate: FrameRate? = null): LongRange {
        val o = windowOriginPx(rate)
        return Math.round(o / pxPerUs)..Math.round((o + windowWidthPx) / pxPerUs)
    }
    /** A time's x inside the window node (px; small at any length). */
    fun windowX(timeUs: Long, rate: FrameRate? = null): Double = timeUs * pxPerUs - windowOriginPx(rate)

    /** This viewport with the centre moved by [deltaPx] (a drag / fling step), clamped to 0..[endUs]. */
    fun scrolledBy(deltaPx: Double): TimelineViewport = withCentre(centreUs + Math.round(deltaPx / pxPerUs))
    fun withCentre(us: Long) = TimelineViewport(clampCentre(us, endUs), dpPerSecond, density, viewportPx, endUs)

    /**
     * The zoom about an anchor at screen x [anchorX] (the time under it stays under it). The timeline's pinch anchors at
     * the PLAYHEAD (the centre column), so its centre time does not change; any other anchor moves the centre.
     */
    fun zoomedAbout(anchorX: Double, newDpPerSecond: Float): TimelineViewport {
        val dp = clampZoom(newDpPerSecond)
        val anchorUs = timeAt(anchorX)
        val newPpu = dp.toDouble() * density / 1_000_000.0
        val centre = anchorUs - Math.round((anchorX - centrePx) / newPpu)
        return TimelineViewport(clampCentre(centre, endUs), dp, density, viewportPx, endUs)
    }

    /** A fling's velocity in us per second for [pxPerSecond] at this zoom (and back). */
    fun usPerSecond(pxPerSecond: Float): Double = pxPerSecond / pxPerUs
    fun pxPerSecond(usPerSecond: Double): Double = usPerSecond * pxPerUs
    /** An edge auto-scroll step of [px] screen px as time (us) at this zoom: the same screen speed at the same zoom. */
    fun edgeStepUs(px: Float): Double = px / pxPerUs
    /** The playhead after a scrub at this centre: the nearest whole frame, inside [lastUs] (TL1). */
    fun snappedCentre(rate: FrameRate, lastUs: Long): Long = TimelineScale(rate, pxPerUs).snappedTime(centreUs * pxPerUs, lastUs)

    companion object {
        /** The zoom bounds, independent of the composition's length (MP1.1's content-width ceiling is gone). */
        fun clampZoom(dpPerSecond: Float): Float = TimelineZoom.clamp(dpPerSecond)
        fun clampCentre(us: Long, endUs: Long): Long = us.coerceIn(0L, endUs.coerceAtLeast(0L))
        /** The window origin for a scroll position: `scroll − centre − margin`, quantised to whole [marginPx] steps. */
        fun originFor(scrollPx: Long, centrePx: Int, marginPx: Int): Long {
            val step = marginPx.coerceAtLeast(1).toLong()
            val wanted = scrollPx - centrePx - marginPx
            return Math.floorDiv(wanted + step / 2, step) * step
        }
    }
}
