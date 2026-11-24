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

    /** Zooming out: minors, then mids drop; majors step 1, 2, 5, 10, 30 s, … and are never closer than 12dp: no solid wall. */
    @Test fun zoomAdaptationDropsTiersInOrder() {
        for (r in rates) {
            val n = CompositionTimecode.nominal(r)
            var last = RulerLevel(1, n, true, true)
            var sawMidsOff = false
            for (dpPerSecond in listOf(2400f, 600f, 240f, 180f, 120f, 60f, 24f, 16f, 12f, 8f, 4f, 2f, 1f, .5f, .2f, .1f, .03f)) {
                val pxPerFrame = dpPerSecond * density / (r.numerator.toDouble() / r.denominator)
                val l = RulerTiers.level(r, pxPerFrame, density)
                // Tiers only ever drop or the step only grows as we zoom out.
                assertTrue(l.stepSeconds >= last.stepSeconds); if (!last.minors) assertFalse(l.minors)
                if (l.minors) assertTrue(pxPerFrame >= RulerTiers.MINOR_DP * density)
                if (l.mids) assertTrue(l.midOffset * pxPerFrame >= RulerTiers.MID_DP * density)
                if (!l.minors && l.stepSeconds < RulerTiers.STEPS_SECONDS.last()) assertTrue(l.period * pxPerFrame >= RulerTiers.MAJOR_DP * density)
                if (!l.minors && !l.mids) sawMidsOff = true
                last = l
            }
            assertFalse(last.minors); assertTrue(last.stepSeconds >= 60); assertTrue(sawMidsOff)
        }
        // The 30 fps sequence, explicitly.
        val r = FrameRate(30, 1)
        fun at(dp: Float) = RulerTiers.level(r, dp * density / 30.0, density)
        assertEquals(RulerLevel(1, 30, true, true), at(240f))      // 8dp per frame: all frames
        assertEquals(RulerLevel(1, 30, true, false), at(120f))     // 4dp per frame: minors hidden
        assertEquals(RulerLevel(1, 30, false, false), at(14f))     // half a second 7dp: mids hidden
        assertEquals(RulerLevel(2, 60, true, false), at(10f))      // 1 s 10dp < 12dp: majors every 2 s, mid at 1 s
        assertEquals(RulerLevel(5, 150, true, false), at(4f))      // 2 s 8dp < 12dp: majors every 5 s (20dp), mid at 2.5 s (10dp)
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
