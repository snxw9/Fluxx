package com.fluxx.android.editor

/**
 * TLV1.1 A1: where the playhead goes when a key drag ends (pure, JVM tested).
 * - The drag AUTO-SCROLLED the timeline (the edge scroll actually moved the view, not just the finger entering the edge zone)
 *   and was COMMITTED: the playhead seeks to the dragged (primary) key's new composition time, and the view settles there.
 * - Otherwise (no auto-scroll, a cancelled drag, or nothing moved): no seek; the view eases back to the unchanged playhead
 *   (the TL3.1 B1 rule).
 * Both key drags (layer bar and twirl-down rows) use it. The key move itself commits exactly as before (one gesture, one undo).
 */
object KeyDragLanding {
    /** Whether the edge scroll moved the view during the current drag. UI state only; reset when a drag begins. */
    class Tracker {
        var autoScrolled = false; private set
        fun begin() { autoScrolled = false }
        /** One edge-scroll step that moved the view by [consumedPx] (0 when the scroll state was at its limit). */
        fun onEdgeStep(consumedPx: Float) { if (consumedPx != 0f) autoScrolled = true }
    }

    /** The composition time to seek to on release, or null for "no seek, ease back". */
    fun seekTo(committed: Boolean, autoScrolled: Boolean, keyCompositionUs: Long?): Long? =
        if (committed && autoScrolled) keyCompositionUs else null

    /**
     * The dragged key's new LOCAL time after a [MultiKeyEdits.moveTogether] (twirl rows): the moved selection keeps every
     * track's order and every track's time order, so the key keeps its index in its own track. Null if it is not there.
     */
    fun movedTime(base: KeySelectionSet, moved: KeySelectionSet?, layerId: Long, target: GraphTarget, fromLocal: Long): Long? {
        val before = base.tracks.firstOrNull { it.layerId == layerId && it.target == target } ?: return null
        val index = before.times.indexOf(fromLocal).takeIf { it >= 0 } ?: return null
        val after = moved?.tracks?.firstOrNull { it.layerId == layerId && it.target == target } ?: return fromLocal
        return after.times.getOrNull(index)
    }
}
