package com.fluxx.android.ui

import androidx.compose.ui.test.onNodeWithTag
import com.fluxx.android.model.ClipTiming
import com.fluxx.android.ui.timeline.ThumbnailTiles
import com.fluxx.android.ui.timeline.TimelineProbe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * TLV1.1 A2 on the A16 (the typical H.264 clip read in place; an in-memory document): the thumbnail tiles the clip rows
 * actually DRAW (reported from their draw phase, no pixels read): the first tile at the clip's real start, the last tile
 * PARTIAL and ending exactly at the clip's real end, no gaps; a clip cut by the window edge keeps going past it.
 */
class Tlv11TilesTest : Aud2Base() {
    override val tag = "TLV11"
    private class Drawn(val tiles: List<ThumbnailTiles.Tile>, val boxPx: Float, val tilePx: Float)
    private val drawn = ConcurrentHashMap<Long, Drawn>()
    @After fun probeOff() { TimelineProbe.tiles = null }

    private fun frame() { compose.mainClock.advanceTimeByFrame(); Thread.sleep(16) }
    private fun pump(ms: Long) { val end = System.nanoTime() + ms * 1_000_000; while (System.nanoTime() < end) frame() }
    private fun waitDrawn(id: Long) { val t0 = System.nanoTime()
        while (drawn[id] == null && System.nanoTime() - t0 < 20_000_000_000L) frame() }

    private fun check(label: String, id: Long, fullPx: Double, leadPx: Double, realEndInBox: Boolean, realStartInBox: Boolean) {
        val d = drawn[id] ?: error("$label: no thumbnail drawn for layer $id")
        val t = d.tiles
        report("$label: box %.1f px, tile %.1f px, full %.1f px, lead %.1f px; %d tiles drawn, first %s, last %s".format(d.boxPx, d.tilePx, fullPx, leadPx, t.size, t.first(), t.last()))
        for ((a, b) in t.zipWithNext()) assertEquals("$label: no gap / overlap", a.x + a.width, b.x, 0.01f)
        if (realStartInBox) assertEquals("$label: the first tile starts at the clip's real start", 0f, t.first().x, 0.01f)
        else assertTrue("$label: the first tile starts at or before the box", t.first().x <= 0.01f)
        if (realEndInBox) {
            val expectedLast = (fullPx - Math.floor(fullPx / d.tilePx - 1e-9) * d.tilePx).toFloat()
            assertEquals("$label: the last tile is the partial one", expectedLast, t.last().width, 0.5f)
            assertEquals("$label: the last tile ends exactly at the clip's real end (the box end)", d.boxPx, t.last().x + t.last().width, 1.0f)
        } else assertTrue("$label: tiles reach the window cut", t.last().x + t.last().width >= d.boxPx - 0.01f)
    }

    @Test fun a2TheLastTileFillsToTheClipsRealEndAndTheFirstStartsAtItsRealStart() {
        val f = file("typical H.264", Aud2Bench.TYPICAL); val m = Aud1Bench.media(f)
        TimelineProbe.tiles = { id, tiles, box, tile -> if (tiles.isNotEmpty()) drawn[id] = Drawn(tiles, box, tile) }
        // Layer 1: 2.3 s from 1 s (wholly inside the window at 120 dp/s; not a whole number of 48 dp tiles).
        // Layer 2: the whole 60 s clip from 0 (cut by the window at the start; its real end inside at 60 s).
        val short = Aud2Bench.layer(1, f, m, z = 11, timing = ClipTiming(1_000_000, 0, 2_300_000))
        val long = Aud2Bench.layer(2, f, m, z = 10, timing = ClipTiming(0, 0, m.durationUs))
        val (w, h) = Aud2Bench.canvas(m)
        TimelineProbe.enabled = true
        host(Aud2Bench.project(w, h, m.durationUs, m.fps, listOf(short, long)))
        try {
            pump(1500)
            val ppu = { compose.onNodeWithTag("timeline-ruler").fetchSemanticsNode().config[com.fluxx.android.ui.timeline.TimelineZoomKey] }
            seekTo(2_000_000, 0); waitDrawn(1); waitDrawn(2); pump(500)
            check("short clip (end inside)", 1, 2_300_000 * ppu(), 0.0, realEndInBox = true, realStartInBox = true)
            check("long clip at its start (end cut by the window)", 2, m.durationUs * ppu(), 0.0, realEndInBox = false, realStartInBox = true)
            // At the long clip's end: its real start is far before the window (lead > 4 windows: the TLV1 lead cap).
            seekTo(m.durationUs - 500_000, 0); waitDrawn(2); pump(500)
            val d = drawn[2]!!
            val full = m.durationUs * ppu()
            check("long clip at its end (start far before the window)", 2, full, full - d.boxPx, realEndInBox = true, realStartInBox = false)
            // A zoom whose tile grid does not divide the clip either (77 dp/s).
            compose.runOnIdle { TimelineProbe.control?.setZoom?.invoke(77f) }; pump(800); waitDrawn(2); pump(300)
            val full77 = m.durationUs * ppu()
            check("long clip at its end, 77 dp/s", 2, full77, full77 - drawn[2]!!.boxPx, realEndInBox = true, realStartInBox = false)
        } finally { TimelineProbe.enabled = false; TimelineProbe.control = null }
    }
}
