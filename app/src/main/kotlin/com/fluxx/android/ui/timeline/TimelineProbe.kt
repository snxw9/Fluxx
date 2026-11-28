package com.fluxx.android.ui.timeline

import java.util.concurrent.atomic.AtomicLong

/**
 * TLV1: TEST-ONLY handles on the timeline (null / off in the app: nothing is built, nothing counted). While [enabled], the
 * view counts its own and its clip rows' recompositions and publishes [control] (zoom, a programmatic fling, the zoom in
 * use and the horizontal content's width), so a benchmark can measure the same thing before and after the virtualisation.
 */
object TimelineProbe {
    @Volatile var enabled = false
    val viewCompositions = AtomicLong()
    val rowCompositions = AtomicLong()

    class Control(
        /** Sets the chosen zoom (dp per second); the view applies its own bounds. */
        val setZoom: (Float) -> Unit,
        /** A fling of [velocity] px/s through the timeline's own fling behaviour (suspends until it settles). */
        val fling: (velocity: Float) -> kotlinx.coroutines.Job,
        /** The zoom in use (dp per second, after the bounds). */
        val zoomDpPerSecond: () -> Float,
        /** The widest horizontal content node the timeline lays out (px). */
        val contentWidthPx: () -> Double,
        /** The time under the playhead (us) as the scroll stands. */
        val centreUs: () -> Long,
    )
    @Volatile var control: Control? = null

    /** TLV1 (after the virtualisation): (window start us, window end us, visible start us, visible end us) per drawn frame. */
    @Volatile var coverage: ((Long, Long, Long, Long) -> Unit)? = null

    /** TLV1.1 A2: a clip's thumbnail tiles as drawn (layer id, tiles in box x, box width px, tile width px), per draw. */
    @Volatile var tiles: ((Long, List<ThumbnailTiles.Tile>, Float, Float) -> Unit)? = null

    fun reset() { viewCompositions.set(0); rowCompositions.set(0) }
}
