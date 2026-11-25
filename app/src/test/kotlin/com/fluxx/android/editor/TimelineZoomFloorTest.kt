package com.fluxx.android.editor

import com.fluxx.android.model.CompositionTimecode
import com.fluxx.android.model.FrameRate
import org.junit.Assert.*
import org.junit.Test

/** GRAPH G4.2: the lower zoom floor, the ruler steps there, the one frame → x function and tappable targets. */
class TimelineZoomFloorTest {
    private val rates = listOf(FrameRate(30, 1), FrameRate(24, 1), FrameRate(25, 1), FrameRate(60, 1), FrameRate(30000, 1001), FrameRate(24000, 1001))
    private val density = 2.75f
    private fun scale(rate: FrameRate, dpPerSecond: Float) = TimelineScale(rate, dpPerSecond * density / 1_000_000.0)

    @Test fun floorFitsAWholeCompositionAndTheDefaultIsUnchanged() {
        assertEquals(.5f, TimelineZoom.MIN_DP_PER_SECOND, 0f)
        assertEquals(120f, TIMELINE_DEFAULT_DP_PER_SECOND, 0f)
        assertEquals(.5f, TimelineZoom.clamp(.01f), 0f); assertEquals(2400f, TimelineZoom.clamp(1e6f), 0f)
        // A 10 minute composition at the floor is 300dp: inside a phone's width.
        assertTrue(600 * TimelineZoom.MIN_DP_PER_SECOND <= 320f)
    }

    /** Majors are on timecode :00 of the chosen step, at least 12dp apart; mids and minors keep their own minimum pitches (G3.1 rule). */
    @Test fun rulerStepsKeepTheTierRulesAtEveryZoom() {
        val seen = sortedSetOf<Long>()
        for (rate in rates) {
            var dp = TimelineZoom.MIN_DP_PER_SECOND
            while (dp <= TimelineZoom.MAX_DP_PER_SECOND) {
                val s = scale(rate, dp)
                val level = RulerTiers.level(rate, s.pxPerFrame, density)
                seen += level.stepSeconds
                val nominal = CompositionTimecode.nominal(rate)
                assertEquals(level.stepSeconds * nominal, level.period)
                assertTrue("$rate $dp major pitch", level.period * s.pxPerFrame >= RulerTiers.MAJOR_DP * density - 1e-6)
                if (level.mids) assertTrue(level.midOffset * s.pxPerFrame >= RulerTiers.MID_DP * density - 1e-6)
                assertEquals(level.period / 2, level.midOffset)
                // Each tier keeps its own minimum pitch (the G3.1 rule: minor to minor, as TL1Test checks).
                if (level.minorEvery > 0) assertTrue("$rate $dp minor pitch", level.minorEvery * s.pxPerFrame >= RulerTiers.MINOR_DP * density - 1e-6)
                if (level.minorDivisions > 0) assertTrue("$rate $dp minor pitch", level.period.toDouble() / level.minorDivisions * s.pxPerFrame >= RulerTiers.MINOR_DP * density - 1e-6)
                assertEquals(TickTier.MAJOR, RulerTiers.tier(0, level)); assertEquals(TickTier.MAJOR, RulerTiers.tier(level.period * 7, level))
                dp *= 1.37f
            }
        }
        // The zoomed-out steps are used: 2, 5, 10 and 30 s (the floor needs 30 s at most; 1 min and up stay for lower floors).
        assertEquals(sortedSetOf(1L, 2L, 5L, 10L, 30L), seen)
        // At the floor, 30 fps: a 12dp major needs 24 s → the 30 s step.
        assertEquals(30L, RulerTiers.level(FrameRate(30, 1), scale(FrameRate(30, 1), .5f).pxPerFrame, density).stepSeconds)
    }

    /** The playhead (scroll) and the ticks use the same frame x at every zoom: 0.00 px offset, no ghost columns. */
    @Test fun oneFrameToXFunctionAtEveryZoom() {
        for (rate in rates) for (dp in listOf(.5f, .73f, 1f, 3.3f, 12f, 120f, 2400f)) {
            val s = scale(rate, dp)
            for (f in listOf(0L, 1L, 7L, 29L, 30L, 1799L, 18_000L, 108_000L)) {
                val t = rate.frameTimeUs(f)
                assertEquals("$rate $dp f$f", s.frameX(f), s.timeX(t))
            }
        }
    }

    @Test fun clustersSplitAtMidpointsSoTheNearestTargetWins() {
        // Five keys 1s apart at the floor: 0.5dp apart, all inside one 48dp target.
        val xs = (0 until 5).map { it * .5f + 100f }
        val spans = TimelineHits.spans(xs)
        for (i in 1 until spans.size) assertEquals(spans[i - 1].endInclusive, spans[i].start, 1e-6f) // no overlap, no gap
        for ((i, x) in xs.withIndex()) assertEquals(i, TimelineHits.hit(xs, x))                   // each key is reachable on itself
        assertEquals(0, TimelineHits.hit(xs, 100f - 23f)); assertEquals(4, TimelineHits.hit(xs, 102f + 23f))
        assertNull(TimelineHits.hit(xs, 100f - 25f))
        // Far apart: full 48dp targets.
        val wide = listOf(0f, 100f, 300f)
        TimelineHits.spans(wide).forEach { assertEquals(TimelineHits.TARGET_DP, it.endInclusive - it.start, 1e-6f) }
        // Nearest wins between two close keys.
        assertEquals(1, TimelineHits.hit(listOf(0f, 10f), 5.1f)); assertEquals(0, TimelineHits.hit(listOf(0f, 10f), 4.9f))
    }

    @Test fun clipsStayTappableAtTheFloor() {
        // A 1 s clip at 0.5dp/s is 0.5dp wide; its hit box is 48dp, centred on it.
        val (left, width) = TimelineHits.clipBox(200f, .5f)
        assertEquals(48f, width, 0f); assertEquals(200.25f - 24f, left, 1e-4f)
        // A wide clip keeps its own box.
        assertEquals(10f to 300f, TimelineHits.clipBox(10f, 300f))
    }
}
