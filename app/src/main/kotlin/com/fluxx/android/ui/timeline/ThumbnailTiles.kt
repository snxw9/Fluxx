package com.fluxx.android.ui.timeline

/**
 * TLV1.1 A2: the thumbnail strip's tile layout (pure, JVM tested). Tiles are [tileW] px wide from the clip's REAL start; the
 * clip box is the clip's part in the window, starting [leadPx] after the real start and [boxWidthPx] wide. Every tile that
 * meets the box is listed in box x, from the first (it may start before the box) to the LAST, which is PARTIAL when the
 * clip's width is not a whole number of tiles: it ends exactly at the clip's real end, never beyond it. No gaps, no overlap.
 * (TLV1 floored the tile count, so the last partial tile was never drawn: up to one tile of bare clip at the real end.)
 */
object ThumbnailTiles {
    /** One tile: box x [x] (px), [width] px (< tileW only for the last, partial tile). */
    data class Tile(val index: Int, val x: Float, val width: Float)

    fun layout(fullWidthPx: Double, leadPx: Double, boxWidthPx: Float, tileW: Float): List<Tile> {
        if (tileW <= 0f || fullWidthPx <= 0.0 || boxWidthPx <= 0f) return emptyList()
        val out = ArrayList<Tile>()
        var i = kotlin.math.floor(leadPx / tileW).toInt().coerceAtLeast(0)
        while (i.toDouble() * tileW < fullWidthPx) {
            val x = i.toDouble() * tileW - leadPx
            if (x >= boxWidthPx) break
            val w = minOf(tileW.toDouble(), fullWidthPx - i.toDouble() * tileW)
            out += Tile(i, x.toFloat(), w.toFloat())
            i++
        }
        return out
    }
}
