package com.fluxx.android.editor

import com.fluxx.android.model.FrameRate
import org.junit.Assert.*
import org.junit.Test

/** TLV1 B1: the timeline's horizontal axis as time (pure). */
class TimelineViewportTest {
    private val hour = 3_600_000_000L
    private val lengths = listOf(1_000_000L, hour, 3 * hour, 10 * hour)
    private val zooms = listOf(TimelineZoom.MIN_DP_PER_SECOND, TIMELINE_DEFAULT_DP_PER_SECOND, TimelineZoom.MAX_DP_PER_SECOND)
    private val densities = listOf(1f, 2f, 2.8125f, 3.5f, 4f)
    private val viewports = listOf(720, 1080, 1440, 2560)
    private val rate = FrameRate(30, 1)

    @Test fun timeToXAndBackIsUnderHalfAPixelAtAnyLengthAndZoom() {
        for (len in lengths) for (dp in zooms) for (d in densities) for (vp in viewports) {
            for (centre in listOf(0L, len / 3, len)) {
                val v = TimelineViewport(centre, dp, d, vp, len)
                for (dx in listOf(-vp.toDouble(), -1.0, 0.0, 0.4, 17.3, vp.toDouble())) {
                    val x = v.centrePx + dx
                    val t = v.timeAt(x)
                    assertEquals("len=$len dp=$dp d=$d vp=$vp c=$centre dx=$dx", x, v.screenX(t), 0.5 + v.pxPerUs / 2)
                }
                // A time's screen x relative to the playhead does not depend on how far into the timeline it is.
                assertEquals(v.centrePx + 10.0 * v.pxPerUs * 1_000_000, v.screenX(centre + 10_000_000), 1e-6)
            }
        }
    }

    @Test fun theEndsReachTheCentreAndTheCentreIsClamped() {
        for (len in lengths) {
            val start = TimelineViewport(0, 120f, 2.8125f, 1080, len)
            assertEquals(start.centrePx.toDouble(), start.screenX(0), 1e-9)
            assertTrue("time 0 is mid-screen: the visible range starts before it", start.visibleUs.first < 0)
            val end = start.withCentre(len)
            assertEquals(end.centrePx.toDouble(), end.screenX(len), 1e-9)
            assertTrue(end.visibleUs.last > len)
            assertEquals(0L, start.withCentre(-5).centreUs); assertEquals(len, start.withCentre(len + 99).centreUs)
            assertEquals(0L, start.scrolledBy(-1e9).centreUs); assertEquals(len, start.scrolledBy(1e12).centreUs)
        }
    }

    @Test fun noOverflowAt24Hours() {
        val day = 24 * hour
        val v = TimelineViewport(day, TimelineZoom.MAX_DP_PER_SECOND, 4f, 2560, day)
        val px = v.contentPx(day, rate)
        assertTrue("content px ${px}", px in 1L until Long.MAX_VALUE / 1000)
        assertEquals(day, v.timeAt(v.centrePx.toDouble()))
        assertTrue(v.windowX(day, rate) in 0.0..v.windowWidthPx.toDouble())
    }

    @Test fun zoomBoundsDoNotDependOnLength() {
        for (len in lengths) for (d in densities) for (vp in viewports) {
            val v = TimelineViewport(len / 2, 1f, d, vp, len)
            assertEquals(TimelineZoom.MAX_DP_PER_SECOND, v.zoomedAbout(v.centrePx.toDouble(), 1e6f).dpPerSecond, 0f)
            assertEquals(TimelineZoom.MIN_DP_PER_SECOND, v.zoomedAbout(v.centrePx.toDouble(), 1e-6f).dpPerSecond, 0f)
        }
        // Frame level at 30 fps on the A16 (2.8125x): 225 px per frame at the top, at 3 hours as at 1 second.
        val top = TimelineViewport(0, TimelineZoom.MAX_DP_PER_SECOND, 2.8125f, 1080, 3 * hour)
        assertEquals(225.0, TimelineScale(rate, top.pxPerUs).pxPerFrame, 1e-9)
    }

    @Test fun pinchAtThePlayheadKeepsTheCentreTimeOtherAnchorsKeepTheirTime() {
        val v = TimelineViewport(3 * hour / 2, 120f, 2.8125f, 1080, 3 * hour)
        assertEquals(v.centreUs, v.zoomedAbout(v.centrePx.toDouble(), 2400f).centreUs)
        val anchorX = 100.0; val anchorUs = v.timeAt(anchorX)
        val z = v.zoomedAbout(anchorX, 600f)
        assertEquals(anchorX, z.screenX(anchorUs), 0.5)
    }

    @Test fun theWindowAlwaysCoversTheVisibleRangeAndIsNeverWider() {
        for (d in densities) for (vp in viewports) for (dp in zooms) {
            var v = TimelineViewport(0, dp, d, vp, 3 * hour)
            val step = vp / 7.3
            repeat(400) {
                val tx = v.windowTranslationPx(rate)
                // The screen (0..vp) in window coordinates lies inside the window.
                assertTrue("tx $tx vp $vp", -tx >= 0 && -tx + vp <= v.windowWidthPx)
                assertTrue(tx in -(v.marginPx * 3 / 2 + 1).toLong()..-(v.marginPx / 2 - 1).toLong())
                assertEquals(vp + 2 * (vp / 2), v.windowWidthPx)
                // The window's time range contains the visible one.
                val w = v.windowUs(rate); val vis = v.visibleUs
                assertTrue("$w vs $vis", w.first <= vis.first + 1 && w.last >= vis.last - 1)
                v = v.scrolledBy(step)
            }
        }
    }

    @Test fun theOriginMovesInMarginStepsSoScrollingRarelyRebases() {
        val v0 = TimelineViewport(60_000_000, 120f, 2.8125f, 1080, hour)
        val origins = HashSet<Long>()
        var v = v0
        repeat(1000) { origins += v.windowOriginPx(); v = v.scrolledBy(1.0) }    // 1000 px of scroll
        assertTrue("origins ${origins.size}", origins.size <= 1000 / v0.marginPx + 2)
    }

    @Test fun flingEdgeScrollAndScrubConversions() {
        val v = TimelineViewport(hour, 120f, 2.8125f, 1080, 3 * hour)
        assertEquals(8000.0, v.pxPerSecond(v.usPerSecond(8000f)), 1e-6)
        // 8000 px/s at 120 dp/s x 2.8125 (337.5 px/s) is 23.7 s of timeline per second.
        assertEquals(8000.0 / 337.5 * 1e6, v.usPerSecond(8000f), 1e-3)
        assertEquals(16.0 / v.pxPerUs, v.edgeStepUs(16f), 1e-9)
        // A scrub lands on the nearest whole frame.
        val s = v.withCentre(hour + 40_000).snappedCentre(rate, 3 * hour)
        assertEquals(0L, (s * 30) % 1_000_000L % 1)
        assertEquals(rate.frameTimeUs(rate.nearestFrame(hour + 40_000)), s)
    }
}
