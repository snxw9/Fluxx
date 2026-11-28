package com.fluxx.android.ui.timeline

import org.junit.Assert.*
import org.junit.Test

/** TLV1.1 A2: the thumbnail tiles cover the clip from its real start to its real end: no gaps, no overdraw, a partial last tile. */
class ThumbnailTilesTest {
    private val tile = 135f // 48 dp on the A16 (2.8125)

    private fun assertCovers(tiles: List<ThumbnailTiles.Tile>, from: Float, to: Float) {
        assertTrue(tiles.isNotEmpty())
        assertTrue("first tile starts at or before the box: ${tiles.first()}", tiles.first().x <= from + 1e-3f)
        for ((a, b) in tiles.zipWithNext()) assertEquals("no gap / overlap between $a and $b", a.x + a.width, b.x, 1e-3f)
        assertTrue("last tile reaches the box end: ${tiles.last()}", tiles.last().x + tiles.last().width >= to - 1e-3f)
    }

    @Test fun aClipNotAWholeNumberOfTilesEndsWithAPartialTileAtItsRealEnd() {
        // 400 px clip wholly inside the window: 2 full tiles + a 130 px partial (TLV1 drew 2 and left 130 px bare).
        val t = ThumbnailTiles.layout(fullWidthPx = 400.0, leadPx = 0.0, boxWidthPx = 400f, tileW = tile)
        assertEquals(3, t.size)
        assertEquals(listOf(tile, tile, 130f), t.map { it.width })
        assertCovers(t, 0f, 400f)
        assertEquals("nothing beyond the real end", 400f, t.last().x + t.last().width, 1e-3f)
    }

    @Test fun aWholeNumberOfTilesHasNoExtraSliver() {
        val t = ThumbnailTiles.layout(405.0, 0.0, 405f, tile)
        assertEquals(listOf(tile, tile, tile), t.map { it.width })
    }

    @Test fun aClipShorterThanATileIsOnePartialTile() {
        val t = ThumbnailTiles.layout(32.0, 0.0, 32f, tile)
        assertEquals(listOf(ThumbnailTiles.Tile(0, 0f, 32f)), t)
    }

    @Test fun theClipStartBeforeTheWindowKeepsTheGridOfTheRealStart() {
        // The real start 1000 px before the box: the first tile is tile 7 (945 px), starting 55 px before the box.
        val t = ThumbnailTiles.layout(fullWidthPx = 1600.0, leadPx = 1000.0, boxWidthPx = 600f, tileW = tile)
        assertEquals(7, t.first().index)
        assertEquals(-55f, t.first().x, 1e-3f)
        assertCovers(t, 0f, 600f)
        // The real end (1600) is the box end (600): the last tile is the partial one, 1600 - 11 * 135 = 115 px.
        assertEquals(11, t.last().index); assertEquals(115f, t.last().width, 1e-3f)
        assertEquals(600f, t.last().x + t.last().width, 1e-3f)
    }

    @Test fun aClipCutByTheWindowEdgeStopsAtTheBoxNotAtItsRealEnd() {
        // A clip 100,000 px long from 0; the box (window + pad) is 2430 px: tiles stop once past the box.
        val t = ThumbnailTiles.layout(100_000.0, 0.0, 2430f, tile)
        assertCovers(t, 0f, 2430f)
        assertTrue(t.last().x < 2430f)
        assertEquals(18, t.size)
    }

    @Test fun hoursBeforeTheWindowTheGridStaysExact() {
        // A clip starting 3 h before the window at 120 dp/s (337.5 px/s): lead 3,645,000 px; the box is the clip's last 700 px.
        val lead = 3_645_000.0; val full = lead + 700.0
        val t = ThumbnailTiles.layout(full, lead, 700f, tile)
        assertCovers(t, 0f, 700f)
        val endX = t.last().x + t.last().width
        assertEquals("ends exactly at the real end", 700f, endX, 1e-2f)
        // The grid is the real start's: every tile's content x is a whole multiple of the tile width.
        for (k in t) assertEquals(0.0, ((k.x + lead) / tile) - k.index, 1e-6)
    }

    @Test fun emptyInputsDrawNothing() {
        assertTrue(ThumbnailTiles.layout(0.0, 0.0, 100f, tile).isEmpty())
        assertTrue(ThumbnailTiles.layout(100.0, 0.0, 0f, tile).isEmpty())
    }
}
