package com.fluxx.android.ui.inspector

import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** GRAPH G3.1: the Speed view's framing, the no-crossing stop and the magnetic landmarks (pure parts). */
class GraphG31Test {
    private val rot = GraphTarget(AnimPropertyType.ROTATION)
    private fun layer(v2: Float = 180f, c0: EasingCurve = EasingCurve.EASY_EASE, c1: EasingCurve = EasingCurve.EASY_EASE, v1: Float = 90f) =
        CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(0, 0, 4_000_000), animTransform = AnimatableTransform(
            rotation = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(Keyframe1D(0, 0f).withCurve(c0), Keyframe1D(1_000_000, v1).withCurve(c1), Keyframe1D(3_000_000, v2))))))
    private fun segs(l: CompositionLayer) = GraphSpeed.segments(l, rot, GraphValues.axes(l, rot, 1080, 1920))

    // ---- §3 framing ----

    @Test fun frameIsTheSegmentPlusNeighbourSliversWithHeadroom() {
        val s = segs(layer())
        // Segment 1 (0–1 s): no left neighbour (8% of 1 s), right neighbour 2 s × 30% = 0.6 s.
        val f0 = SpeedFrame.frame(s, 0)!!
        assertEquals(-80_000.0, f0.t0, 1e-6); assertEquals(1_600_000.0, f0.t1, 1e-6)
        // Segment 2 (1–3 s): left 1 s × 30%, no right neighbour (8% of 2 s).
        val f1 = SpeedFrame.frame(s, 1)!!
        assertEquals(700_000.0, f1.t0, 1e-6); assertEquals(3_160_000.0, f1.t1, 1e-6)
        // The zero line, the average speed and the peak, with 35% of the span as headroom above and below.
        for ((f, sg) in listOf(f0 to s[0], f1 to s[1])) {
            assertTrue(f.v0 < 0.0); assertTrue(f.v1 > sg.rate)
            var peak = 0.0; for (i in 0..400) peak = maxOf(peak, GraphSpeed.speedAt(s, (f.t0 + f.timeSpan * i / 400).toLong()))
            val span = peak - 0.0
            assertEquals(-span * SpeedFrame.HEADROOM, f.v0, span * 1e-3); assertEquals(peak + span * SpeedFrame.HEADROOM, f.v1, span * 1e-2)
        }
        // A flat segment keeps a minimum span around 0; an overshoot (negative speed) is included.
        val flat = SpeedFrame.frame(segs(layer(v1 = 0f, v2 = 0f)), 0)!!
        assertEquals(-SpeedFrame.MIN_SPAN * SpeedFrame.HEADROOM, flat.v0, 1e-9); assertEquals(SpeedFrame.MIN_SPAN * SpeedFrame.HEADROOM, flat.v1, 1e-9)
        val back = SpeedFrame.frame(segs(layer(c0 = EasingCurve.bezier(.36f, -.6f, .66f, 1f))), 0)!!
        val minSpeed = (0..400).minOf { GraphSpeed.speedAt(segs(layer(c0 = EasingCurve.bezier(.36f, -.6f, .66f, 1f))), it * 2500L) }
        assertTrue(minSpeed < 0); assertTrue(back.v0 < minSpeed)
    }

    /** The window only changes once after a drag, and only when a handle is within 10% of an edge. */
    @Test fun expandOnceAfterReleaseOnlyNearAnEdge() {
        val vp = ValueViewport(0.0, 1_000_000.0, -10.0, 100.0)
        assertFalse(SpeedFrame.nearEdge(vp, listOf(500_000.0 to 50.0)))
        assertSame(vp, if (SpeedFrame.nearEdge(vp, listOf(500_000.0 to 50.0))) SpeedFrame.expand(vp, listOf(500_000.0 to 50.0)) else vp)
        val near = listOf(300_000.0 to 95.0)
        assertTrue(SpeedFrame.nearEdge(vp, near))
        val grown = SpeedFrame.expand(vp, near)
        assertTrue(grown.v1 > vp.v1); assertEquals(vp.v0, grown.v0, 0.0); assertEquals(vp.t0, grown.t0, 0.0)
        // After the one expansion the handle has headroom again (no second expansion).
        assertFalse(SpeedFrame.nearEdge(grown, near))
        // The dragged point is clamped inside the frozen window.
        assertEquals(1_000_000.0 to 100.0, SpeedFrame.clamp(vp, 2e6, 500.0)); assertEquals(0.0 to -10.0, SpeedFrame.clamp(vp, -5.0, -50.0))
    }

    // ---- §4 no crossing ----

    @Test fun handlesStopBeforeCrossing() {
        // In order (0.33 + 0.33 ≤ 1): the out handle may reach 1 − 0.33 − gap.
        val ease = floatArrayOf(.33f, 0f, .67f, 1f)
        assertEquals(1 - .33 - .01, SpeedCross.maxInfluence(ease, 0), 1e-6)
        assertEquals(1 - .33 - .05, SpeedCross.maxInfluence(ease, 1, gap = .05), 1e-6)
        // Starting crossed (Back-style x1 > x2: 0.6 + 0.7 > 1): the dragged influence may only decrease.
        val crossed = floatArrayOf(.6f, -.5f, .3f, 1.5f)
        assertEquals(.6, SpeedCross.maxInfluence(crossed, 0), 1e-6); assertEquals(.7, SpeedCross.maxInfluence(crossed, 1), 1e-6)
        // In order but the other handle almost at the far end: the stop never goes below the minimum influence.
        assertEquals(SpeedHandles.INFLUENCE_MIN.toDouble(), SpeedCross.maxInfluence(floatArrayOf(.003f, 0f, .005f, 1f), 0), 1e-6)
        // Applied through the mapping, the result keeps the handles apart; typed entries clamp the same way (flat too).
        val seg = segs(layer())[0]
        val b = SpeedHandles.set(seg, ease, 0, minOf(.95, SpeedCross.maxInfluence(ease, 0)), 30.0)
        assertTrue(SpeedHandles.influence(b, 0) + SpeedHandles.influence(b, 1) <= 1.0 - .01 + 1e-6)
        val flat = segs(layer(v1 = 0f, v2 = 0f))[0]
        val fb = SpeedHandles.set(flat, ease, 1, minOf(.99, SpeedCross.maxInfluence(ease, 1)), null)
        assertEquals(.66, SpeedHandles.influence(fb, 1), 1e-6)
    }

    // ---- §5 landmarks ----

    @Test fun landmarksAreZeroAverageOtherHandleAndNeighbour() {
        val s = segs(layer())
        val b = s[1].bezier!!
        // Segment 2's out-handle at key 2: 0, its average, its in-handle's speed, and segment 1's in speed at key 2.
        val (speeds, influences) = SpeedSnap.landmarks(s, 1, b, 0)
        assertEquals(listOf(0.0, s[1].rate, SpeedHandles.speed(s[1], b, 1), SpeedHandles.speed(s[0], s[0].bezier!!, 1)), speeds)
        assertEquals(listOf(1.0 / 3, .5, SpeedHandles.influence(b, 1)), influences)
        // The first key's out-handle has no neighbour; a flat segment has no speed landmarks.
        assertEquals(3, SpeedSnap.landmarks(s, 0, s[0].bezier!!, 0).first.size)
        assertTrue(SpeedSnap.landmarks(segs(layer(v1 = 0f, v2 = 0f)), 0, b, 0).first.isEmpty())
    }

    @Test fun snapLocksReleasesPrioritisesAndNeverMerges() {
        // Landmarks 0 and 90 (average): zone 10 units, grid 25.
        val snap = SpeedSnap(listOf(0.0, 90.0), listOf(1.0 / 3, .5), 10.0, .03, 25.0)
        assertEquals(90.0, snap.speed(83.0), 0.0); assertEquals(90.0, snap.lockedSpeed!!, 0.0)   // lock inside the zone
        assertEquals(90.0, snap.speed(99.0), 0.0)                                                // still locked (≤ zone)
        assertEquals(100.0, snap.speed(103.0), 0.0); assertNull(snap.lockedSpeed)               // released: the grid fallback
        // Landmarks take priority over the grid: 88 would round to 75 on the grid, but locks to 90.
        assertEquals(90.0, SpeedSnap(listOf(0.0, 90.0), emptyList(), 10.0, .03, 25.0).speed(88.0), 0.0)
        // Never skips: every grid value between the landmarks' zones is reachable.
        val free = SpeedSnap(listOf(0.0, 90.0), emptyList(), 10.0, .03, 25.0)
        assertEquals(setOf(0.0, 25.0, 50.0, 75.0, 90.0), (0..900).map { free.speed(it / 10.0) }.toSet())
        // Zone rule: two close landmarks (95, 100) get at most 35% of their distance (1.75), not 10.
        val close = SpeedSnap(listOf(95.0, 100.0), emptyList(), 10.0, .03, 0.0)
        assertEquals(98.0, close.speed(98.0), 0.0); assertEquals(100.0, close.speed(98.5), 0.0)
        // Influence: 50% locks within its zone; elsewhere the 5% steps.
        val inf = SpeedSnap(emptyList(), listOf(1.0 / 3, .5), 10.0, .03, 25.0)
        assertEquals(.5, inf.influence(.52), 0.0); assertEquals(.6, inf.influence(.61), 1e-9); assertEquals(1.0 / 3, inf.influence(.31), 1e-6)
        // With §4: a landmark beyond the stop is clamped by the stop (snap first, then the crossing limit).
        val maxI = SpeedCross.maxInfluence(floatArrayOf(.45f, 0f, .5f, 1f), 0)
        assertEquals(.49, minOf(SpeedSnap(emptyList(), listOf(.5), 10.0, .03, 0.0).influence(.5), maxI), 1e-6)
    }
}
