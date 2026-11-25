package com.fluxx.android.editor

import com.fluxx.android.model.CompositionTimecode
import com.fluxx.android.model.FrameRate
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

/** TL1: rational frame time, snap / floor readouts, the tiered ruler and the shared frame → x mapping. */
class TL1Test {
    private val rates = listOf(FrameRate(24000, 1001), FrameRate(24, 1), FrameRate(25, 1), FrameRate(30000, 1001), FrameRate(30, 1),
        FrameRate(50, 1), FrameRate(60000, 1001), FrameRate(60, 1))
    private val density = 2.8125f
    private fun us(rate: FrameRate, f: Long) = rate.frameTimeUs(f)

    @Test fun nominalFps() {
        assertEquals(listOf(24L, 24L, 25L, 30L, 30L, 50L, 60L, 60L), rates.map { CompositionTimecode.nominal(it) })
    }

    /** Frame times come from n·den/num of the fraction; no drift over 10 hours at 29.97 (or any rate). */
    @Test fun frameTimesFromTheRationalRateNeverDrift() {
        for (r in rates) {
            val tenHours = r.nearestFrame(36_000_000_000L)
            for (f in listOf(0L, 1L, 2L, 1001L, 30_000L, tenHours / 2, tenHours - 1, tenHours)) {
                val exact = BigInteger.valueOf(f).multiply(BigInteger.valueOf(1_000_000L * r.denominator)).divide(BigInteger.valueOf(r.numerator.toLong())).toLong()
                assertEquals("$r frame $f", exact, r.frameTimeUs(f))
                assertEquals(f, r.nearestFrame(r.frameTimeUs(f))); assertEquals(f, r.floorFrame(r.frameTimeUs(f)))
                if (f > 0) assertEquals(f - 1, r.floorFrame(r.frameTimeUs(f) - 1))
            }
        }
        // 29.97 at 10 h: 1,078,921 frames; frame 1,078,921 starts at 35,999,997,366 µs (no accumulated error).
        val ntsc = FrameRate(30000, 1001)
        assertEquals(1_078_921L, ntsc.nearestFrame(36_000_000_000L)); assertEquals(35_999_997_366L, ntsc.frameTimeUs(1_078_921L))
    }

    @Test fun snapIsNearestAndThePlayheadReadoutFloors() {
        for (r in rates) for (f in listOf(0L, 1L, 15L, 29L, 1000L)) {
            val a = us(r, f); val b = us(r, f + 1)
            val quarter = a + (b - a) / 4; val threeQuarters = a + (b - a) * 3 / 4
            assertEquals(a, r.snap(quarter)); assertEquals(b, r.snap(threeQuarters))
            assertEquals(f, r.floorFrame(quarter)); assertEquals(f, r.floorFrame(threeQuarters)); assertEquals(f, r.floorFrame(b - 1))
            // Playhead readout: floor (the frame on screen); durations / typed values keep nearest.
            assertEquals(CompositionTimecode.formatFrame(f, r), CompositionTimecode.formatPlayhead(threeQuarters, 0, r))
            assertEquals(CompositionTimecode.formatFrame(f + 1, r), CompositionTimecode.format(threeQuarters, r))
            // At rest (on a frame) floor and nearest agree.
            assertEquals(CompositionTimecode.format(a, r), CompositionTimecode.formatPlayhead(a, 0, r))
        }
        assertEquals("00:01:15", CompositionTimecode.formatPlayhead(1_500_000L + 30_000L, 0, FrameRate(30, 1)))
        assertEquals("60:00:00", CompositionTimecode.formatPlayhead(0, 3_600_000_000L, FrameRate(30, 1)))
    }

    /** Majors land exactly where the playhead readout shows frame :00; mids at floor(nominal / 2); minors elsewhere. */
    @Test fun tiersFollowTheDisplayedTimecode() {
        for (r in rates) for (start in listOf(0L, 3_600_000_000L, 1_234_567L)) {
            val n = CompositionTimecode.nominal(r)
            val level = RulerTiers.level(r, 40.0 * density, density) // zoomed in: every frame
            assertTrue(level.minors && level.mids); assertEquals(n, level.period)
            val startFrame = CompositionTimecode.startFrame(start, r)
            for (f in 0L until 4 * n) {
                val tier = RulerTiers.tier(startFrame + f, level)
                val readout = CompositionTimecode.formatPlayhead(r.frameTimeUs(f), start, r)
                val ff = readout.takeLast(2).toLong()
                val expected = when (ff) { 0L -> TickTier.MAJOR; n / 2 -> TickTier.MID; else -> TickTier.MINOR }
                assertEquals("$r start $start frame $f ($readout)", expected, tier)
            }
        }
        // The mid rule where half a second is not a whole frame: 25 fps → frame 12; 23.976 / 24 → 12; 29.97 / 30 → 15.
        assertEquals(12L, RulerTiers.level(FrameRate(25, 1), 40.0 * density, density).midOffset)
        assertEquals(12L, RulerTiers.level(FrameRate(24000, 1001), 40.0 * density, density).midOffset)
        assertEquals(15L, RulerTiers.level(FrameRate(30000, 1001), 40.0 * density, density).midOffset)
        assertEquals(25L, RulerTiers.level(FrameRate(50, 1), 40.0 * density, density).midOffset)
    }

    /**
     * GRAPH G3.1 density rules: majors ≥ 12dp (the step grows only when 1 s is under that), mids ≥ 5dp, minors ≥ 3dp (every
     * frame, else every Nth frame, else tenths / fifths of a zoomed-out step). Never a solid wall: every shown pitch ≥ 3dp.
     */
    @Test fun zoomAdaptationKeepsThreeTiersWherePossible() {
        for (r in rates) {
            val n = CompositionTimecode.nominal(r)
            var lastStep = 1L
            for (dpPerSecond in listOf(2400f, 600f, 240f, 180f, 120f, 90f, 60f, 24f, 16f, 12f, 8f, 4f, 2f, 1f, .5f, .2f, .1f, .03f)) {
                val pxPerFrame = dpPerSecond * density / (r.numerator.toDouble() / r.denominator)
                val l = RulerTiers.level(r, pxPerFrame, density)
                assertTrue(l.stepSeconds >= lastStep); lastStep = l.stepSeconds
                // Majors step up only when 1 s is under 12dp.
                if (dpPerSecond >= RulerTiers.MAJOR_DP) assertEquals("$r $dpPerSecond", 1L, l.stepSeconds)
                if (l.stepSeconds < RulerTiers.STEPS_SECONDS.last()) assertTrue(l.period * pxPerFrame >= RulerTiers.MAJOR_DP * density - 1e-6)
                // Mids whenever their pitch is ≥ 5dp (with the major minimum, always).
                val half = minOf(l.period / 2, l.period - l.period / 2) * pxPerFrame
                assertEquals("$r $dpPerSecond mids", half >= RulerTiers.MID_DP * density, l.mids)
                // Minors: every frame at ≥ 3dp, else the smallest N, else tenths / fifths; never denser than 3dp.
                if (l.stepSeconds == 1L) {
                    if (pxPerFrame >= RulerTiers.MINOR_DP * density) assertEquals(1L, l.minorEvery)
                    else if (l.minorEvery > 0) {
                        assertTrue(l.minorEvery * pxPerFrame >= RulerTiers.MINOR_DP * density)
                        val smaller = RulerTiers.MINOR_EVERY.filter { it < l.minorEvery }
                        assertTrue(smaller.all { it * pxPerFrame < RulerTiers.MINOR_DP * density })
                    }
                } else if (l.minorDivisions > 0) assertTrue(l.period.toDouble() / l.minorDivisions * pxPerFrame >= RulerTiers.MINOR_DP * density)
                // Every tick sits on a frame and the drawn offsets agree with the classifier.
                for (o in RulerTiers.offsets(l)) assertNotNull(RulerTiers.tier(o, l))
                for (f in 0L until l.period) assertEquals(f in RulerTiers.offsets(l).toList(), RulerTiers.tier(f, l) != null)
            }
        }
    }

    /** At the default zoom (a new timeline) all three tiers are visible at every supported rate. */
    @Test fun threeTiersAtTheDefaultZoomForEveryRate() {
        for (r in rates) for (start in listOf(0L, 3_600_000_000L, 1_234_567L)) {
            val pxPerFrame = TIMELINE_DEFAULT_DP_PER_SECOND * density / (r.numerator.toDouble() / r.denominator)
            val l = RulerTiers.level(r, pxPerFrame, density)
            assertEquals(1L, l.stepSeconds); assertTrue(l.mids); assertTrue("$r minors at the default zoom", l.minors)
            val sf = CompositionTimecode.startFrame(start, r)
            val tiers = (0L until 3 * l.period).mapNotNull { RulerTiers.tier(sf + it, l) }.toSet()
            assertEquals(setOf(TickTier.MAJOR, TickTier.MID, TickTier.MINOR), tiers)
            // Majors exactly where the readout shows :00.
            for (f in 0L until 3 * l.period) if (RulerTiers.tier(sf + f, l) == TickTier.MAJOR)
                assertEquals("00", CompositionTimecode.formatPlayhead(r.frameTimeUs(f), start, r).takeLast(2))
        }
        // The pitches at the default zoom (120dp/s): 24 fps 5dp per frame (every frame); 30 fps 4dp; 60 fps every 2nd frame (4dp).
        fun at(r: FrameRate) = RulerTiers.level(r, TIMELINE_DEFAULT_DP_PER_SECOND * density / (r.numerator.toDouble() / r.denominator), density)
        assertEquals(1L, at(FrameRate(24, 1)).minorEvery); assertEquals(1L, at(FrameRate(30, 1)).minorEvery)
        assertEquals(2L, at(FrameRate(60, 1)).minorEvery); assertEquals(2L, at(FrameRate(60000, 1001)).minorEvery)
        // Zoomed out at 30 fps: 10dp/s → 2 s majors (20dp), mid at 1 s (10dp), minors at fifths (4dp: tenths would be 2dp).
        val z = RulerTiers.level(FrameRate(30, 1), 10f * density / 30.0, density)
        assertEquals(RulerLevel(2, 60, true, minorDivisions = 5), z)
        // 25 fps 5 s step (3dp/s): mid on floor(125 / 2) = 62; tenths would be 1.5dp, so minors on fifths (25, 50, 75, 100).
        val q = RulerTiers.level(FrameRate(25, 1), 3f * density / 25.0, density)
        assertEquals(5L, q.stepSeconds); assertEquals(62L, q.midOffset)
        assertEquals(5, q.minorDivisions); assertEquals(TickTier.MINOR, RulerTiers.tier(25, q)); assertEquals(TickTier.MINOR, RulerTiers.tier(100, q))
        assertNull(RulerTiers.tier(12, q)); assertEquals(TickTier.MID, RulerTiers.tier(62, q))
    }

    /** One frame → x function: ticks and the playhead's scroll agree for every frame at several zooms. */
    @Test fun frameXAndScrollAgree() {
        for (r in rates) for (dpPerSecond in listOf(12f, 120f, 600f, 2400f)) {
            val s = TimelineScale(r, dpPerSecond * density / 1e6)
            for (f in (0L..400L) + listOf(10_000L, 107_892L, 1_078_921L)) {
                val t = r.frameTimeUs(f)
                assertEquals("$r $dpPerSecond dp/s frame $f", s.frameX(f), s.timeX(t))
                if (s.pxPerFrame >= 1.0) assertEquals(f, s.frameAt(s.frameX(f).toDouble()))
                if (s.pxPerFrame >= 10.0) {
                    // A scrub that stops within half a frame of a tick lands on that frame.
                    assertEquals(t, s.snappedTime(s.frameX(f) + s.pxPerFrame * .45, 1_000_000_000_000L))
                    assertEquals(t, s.snappedTime(s.frameX(f) - s.pxPerFrame * .45, 1_000_000_000_000L))
                }
            }
            // Never past the last frame inside the timeline.
            assertEquals(r.frameTimeUs(r.floorFrame(1_000_000L)), s.snappedTime(1e9, 1_000_000L))
        }
    }
}
