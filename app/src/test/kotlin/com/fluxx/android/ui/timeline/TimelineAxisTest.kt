package com.fluxx.android.ui.timeline

import androidx.compose.runtime.mutableLongStateOf
import com.fluxx.android.editor.TimelineScale
import com.fluxx.android.editor.TimelineViewport
import com.fluxx.android.editor.TimelineZoom
import com.fluxx.android.model.FrameRate
import org.junit.Assert.*
import org.junit.Test

/**
 * TLV1 B4: the timeline's interactions on time (JVM). The ruler tap, markers and key / clip x go through [TimelineAxis]
 * (window x = t x pxPerUs - origin); drag, fling and edge scroll go through [TimelineScrollState] (px in, time out).
 */
class TimelineAxisTest {
    private val hour = 3_600_000_000L
    private val rate = FrameRate(30, 1)
    private fun ppu(dp: Float, d: Float = 2.8125f) = dp.toDouble() * d / 1_000_000.0

    private fun axisAt(centreUs: Long, dp: Float, viewport: Int = 1080): TimelineAxis {
        val v = TimelineViewport(centreUs, dp, 2.8125f, viewport, 3 * hour)
        return TimelineAxis(v.pxPerUs, mutableLongStateOf(v.windowOriginPx(rate)), v.windowWidthPx)
    }

    @Test fun rulerTapSeeksTheTimeUnderTheFingerAtAnyLength() {
        // R5: a tap at window x seeks axis.timeAt(x); round trip within half a px, 1 s to 3 h in, lowest to highest zoom.
        for (centre in listOf(1_000_000L, hour, 3 * hour - 1)) for (dp in listOf(TimelineZoom.MIN_DP_PER_SECOND, 120f, TimelineZoom.MAX_DP_PER_SECOND)) {
            val a = axisAt(centre, dp)
            for (x in listOf(0f, 1.5f, a.widthPx / 2f, a.widthPx - 1f)) {
                val t = a.timeAt(x)
                assertEquals("centre=$centre dp=$dp x=$x", x.toDouble(), a.x(t).toDouble(), 0.5)
            }
            // The playhead's time is under the centre column of the screen: window x = centre px - translation.
            val v = TimelineViewport(centre, dp, 2.8125f, 1080, 3 * hour)
            assertEquals(v.centrePx.toDouble() - v.windowTranslationPx(rate), a.x(TimelineScale(rate, v.pxPerUs).timeX(centre), true).toDouble(), 0.0)
        }
    }

    @Test fun markersAndKeysNearThePlayheadAreInsideTheWindowAndPlacedExactly() {
        // R15 / R24: a marker or key 50 ms after the playhead, 3 hours in, at frame zoom, is drawn 50 ms x zoom right of the line.
        val centre = 3 * hour - 10_000_000; val dp = TimelineZoom.MAX_DP_PER_SECOND
        val a = axisAt(centre, dp)
        val v = TimelineViewport(centre, dp, 2.8125f, 1080, 3 * hour)
        val lineX = v.centrePx - v.windowTranslationPx(rate)
        val markerX = a.x(centre + 50_000)
        assertEquals(lineX + 0.05 * dp * 2.8125, markerX.toDouble(), 1.0)
        assertTrue(markerX in 0f..a.widthPx.toFloat())
        assertTrue(a.startUs <= centre - 1080 / 2 / v.pxPerUs && a.endUs >= centre + 1080 / 2 / v.pxPerUs)
    }

    @Test fun dragFlingAndEdgeScrollMoveTimeAtTheSameScreenSpeed() {
        // R1 / R2 / R8: the scroll state turns px into time at the zoom; the edge auto-scroll's 16 px per frame is the same
        // screen speed it always was; the range is 0..the end, and a fling stops there (consumed px drop to 0).
        val s = TimelineScrollState(hour); s.pxPerUs = ppu(120f); s.endUs = 3 * hour
        val before = s.centreUs
        assertEquals(16f, s.dispatchRawDelta(16f), 1e-3f)
        assertEquals(16.0 / s.pxPerUs, (s.centreUs - before).toDouble(), 1.0)
        // A slow drag of 0.3 px steps loses nothing (the sub-us rest is kept).
        val s2 = TimelineScrollState(hour); s2.pxPerUs = ppu(2400f); s2.endUs = 3 * hour
        repeat(1000) { s2.dispatchRawDelta(0.3f) }
        assertEquals(300.0 / s2.pxPerUs, (s2.centreUs - hour).toDouble(), 2.0)
        // Bounds: 0 and the end; past them nothing is consumed.
        val s3 = TimelineScrollState(10); s3.pxPerUs = ppu(120f); s3.endUs = hour
        s3.dispatchRawDelta(-1e6f); assertEquals(0L, s3.centreUs); assertEquals(0f, s3.dispatchRawDelta(-5f), 0f)
        s3.dispatchRawDelta(1e9f); assertEquals(hour, s3.centreUs); assertEquals(0f, s3.dispatchRawDelta(5f), 0f)
        assertFalse(s3.canScrollForward); assertTrue(s3.canScrollBackward)
    }

    @Test fun followAndSettleAreNotAScrollInProgress() {
        // R4: playback follow and the settle after a seek write the time directly (never "in progress": never a scrub).
        val s = TimelineScrollState(); s.pxPerUs = ppu(120f); s.endUs = hour
        s.jumpTo(1_234_567); assertEquals(1_234_567L, s.centreUs); assertFalse(s.isScrollInProgress)
        s.jumpTo(-1); assertEquals(0L, s.centreUs); s.jumpTo(hour * 2); assertEquals(hour, s.centreUs)
    }

    @Test fun pinchKeepsThePlayheadTimeAndBothEndsReachTheCentre() {
        // R3: the pinch anchors at the playhead, and the state IS the time under it: a zoom change moves no time.
        val s = TimelineScrollState(hour / 3); s.pxPerUs = ppu(120f); s.endUs = hour
        s.pxPerUs = ppu(2400f); assertEquals(hour / 3, s.centreUs)
        // R21: time 0 and the end are reachable under the centre column.
        s.jumpTo(0); assertEquals(0L, s.centreUs); s.jumpTo(hour); assertEquals(hour, s.centreUs)
    }
}
