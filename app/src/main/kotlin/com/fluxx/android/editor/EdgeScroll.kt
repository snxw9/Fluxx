package com.fluxx.android.editor

/**
 * TL3.1 B1: auto-scroll while a key drag (single or group; twirl rows and keys with the sheet open) holds the finger near the
 * timeline's left or right edge. Pure, JVM tested. The speed ramps with depth into the edge zone (the curve library's drag
 * edge-scroll idea): 0 at the zone's inner edge, [MAX_DP_PER_FRAME] at the very edge. Horizontal only; the scroll state
 * clamps at its limits.
 */
object EdgeScroll {
    const val ZONE_DP = 48f
    const val MAX_DP_PER_FRAME = 14f

    /** Signed px per frame for a finger at [x] px inside a viewport [width] px wide (negative = toward 0). */
    fun speed(x: Float, width: Float, zonePx: Float, maxPx: Float): Float {
        if (width <= 0f || zonePx <= 0f) return 0f
        val left = x / zonePx; val right = (width - x) / zonePx
        return when {
            left < 1f -> -maxPx * (1f - left.coerceAtLeast(0f))
            right < 1f -> maxPx * (1f - right.coerceAtLeast(0f))
            else -> 0f
        }
    }

    /**
     * The drag in CONTENT px: the finger's SCREEN move since the down ([screenDx]) plus how far the content scrolled since
     * then. So a still finger in the edge zone keeps the key under it while the content scrolls.
     */
    fun contentDx(screenDx: Float, scrollAtDown: Int, scrollNow: Int): Float = screenDx + (scrollNow - scrollAtDown)
}
