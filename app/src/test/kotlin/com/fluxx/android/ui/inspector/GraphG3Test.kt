package com.fluxx.android.ui.inspector

import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** GRAPH G3: the Speed graph model (speed, handles, Smooth through key, the bar's conversions, tabs, units). */
class GraphG3Test {
    private val rate = FrameRate(30, 1)
    private val rot = GraphTarget(AnimPropertyType.ROTATION)
    private val pos = GraphTarget(AnimPropertyType.POSITION)
    private fun layer(v1: Float = 90f, v2: Float = 180f, c0: EasingCurve = EasingCurve.EASY_EASE, c1: EasingCurve = EasingCurve.EASY_EASE) =
        CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(0, 0, 4_000_000), animTransform = AnimatableTransform(
            rotation = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(Keyframe1D(0, 0f).withCurve(c0), Keyframe1D(1_000_000, v1).withCurve(c1), Keyframe1D(3_000_000, v2)))),
            position = AnimatableProperty2D(true, keyframes = FrozenList.of(listOf(Keyframe2D(0, 0f, 0f), Keyframe2D(2_000_000, .5f, .25f))))))
    private fun axes(l: CompositionLayer, t: GraphTarget) = GraphValues.axes(l, t, 1080, 1920)
    private fun segs(l: CompositionLayer, t: GraphTarget = rot) = GraphSpeed.segments(l, t, axes(l, t))

    @Test fun speedIsDistanceOverTimeTimesTheEasingSlope() {
        val l = layer(c0 = EasingCurve.LINEAR)
        val s = segs(l)
        assertEquals(2, s.size)
        assertEquals(90.0, s[0].delta, 1e-6); assertEquals(90.0, s[0].rate, 1e-6) // 90° in 1 s
        assertEquals(90.0, s[0].speed(500_000), 1e-3)                               // linear: constant
        // Easy Ease: 0 at both ends, faster in the middle; outside the keys 0.
        assertEquals(0.0, s[1].speed(1_000_000), 1e-6); assertTrue(s[1].speed(2_000_000) > s[1].rate)
        assertEquals(0.0, GraphSpeed.speedAt(s, -1), 0.0); assertEquals(0.0, GraphSpeed.speedAt(s, 3_000_001), 0.0)
        // Position: one combined curve, the display-unit displacement length (px).
        val p = segs(l, pos).single()
        val a = axes(l, pos)
        assertEquals(hypot(a[0].toDisplay(.5f).toDouble() - a[0].toDisplay(0f), a[1].toDisplay(.25f).toDouble() - a[1].toDisplay(0f)), p.delta, 1e-3)
        assertEquals("px/s", GraphSpeed.unit(a[0]))
    }

    /** The analytic slope matches a numeric derivative of the real evaluator (bezier, Bounce, Elastic). */
    @Test fun derivativeMatchesTheEvaluator() {
        val curves = listOf(EasingCurve.LINEAR, EasingCurve.EASY_EASE, EasingCurve.bezier(.1f, .9f, .2f, 1f), EasingCurve.bezier(.42f, -.6f, .6f, 1.8f),
            EasingCurve.bezier(.9f, .1f, .95f, .2f), EasingCurve.bounce(3f, .5f), EasingCurve.elastic(3f, .5f))
        for (c in curves) for (i in 1..37) {
            val tau = i / 38.0
            val h = 1e-5
            val numeric = (c.ease((tau + h).toFloat()) - c.ease((tau - h).toFloat())).toDouble() / (2 * h)
            val numeric2 = (c.ease((tau + 1e-4).toFloat()) - c.ease((tau - 1e-4).toFloat())).toDouble() / 2e-4
            if (abs(numeric - numeric2) > .05 * (1 + abs(numeric2))) continue // a bounce kink right here
            val slope = SpeedMath.slope(c, tau)
            assertEquals("$c at $tau", numeric2, slope, .03 * (1 + abs(numeric2)))
        }
        assertEquals(0.0, SpeedMath.slope(EasingCurve.HOLD, .4), 0.0)
        // Ends: the handle slopes y1/x1 and (1 − y2)/(1 − x2).
        assertEquals(.9 / .1, SpeedMath.bezierSlope(0.0, .1f, .9f, .2f, 1f), 1e-3)
        assertEquals(0.0 / .8, SpeedMath.bezierSlope(1.0, .1f, .9f, .2f, 1f), 1e-3)
    }

    /** ∫ S dt over a segment = its travel D (any easing, overshoot included); the average speed is D/Δt. */
    @Test fun speedIntegratesToTheTravel() {
        for (c in listOf(EasingCurve.EASY_EASE, EasingCurve.bezier(.42f, -.6f, .6f, 1.8f), EasingCurve.bounce(3f, .5f), EasingCurve.elastic(4f, .4f))) {
            val s = segs(layer(c0 = c))[0]
            val n = 20_000; var sum = 0.0
            for (i in 0 until n) { val t = s.t0 + (s.t1 - s.t0) * (i + .5) / n; sum += s.speed(t.toLong()) * (s.seconds / n) }
            assertEquals("$c", s.delta, sum, s.delta * .005)
            assertEquals(s.delta / s.seconds, s.rate, 1e-9)
        }
    }

    /** speed / influence ↔ (x1, y1, x2, y2) round trip over the bezier range (negative speeds, overshoot, x1 → 0). */
    @Test fun handleMappingRoundTrips() {
        val seg = segs(layer())[0]
        val xs = listOf(.01f, .05f, .33f, .5f, .9f, 1f); val ys = listOf(-2f, -.6f, 0f, .4f, 1f, 1.8f, 3f)
        for (x1 in xs) for (y1 in ys) for (x2 in listOf(0f, .2f, .67f, .99f)) for (y2 in ys) {
            val b = floatArrayOf(x1, y1, x2, y2)
            for (h in 0..1) {
                if (h == 1 && 1 - x2 < SpeedHandles.INFLUENCE_MIN) continue
                val back = SpeedHandles.set(seg, b, h, SpeedHandles.influence(b, h), SpeedHandles.speed(seg, b, h))
                assertArrayEquals("$h ${b.toList()}", b, back, 1e-4f)
            }
        }
        // Negative speed (direction reverses at the key) and the clamps.
        val neg = SpeedHandles.set(seg, floatArrayOf(.33f, 0f, .67f, 1f), 0, .5, -45.0)
        assertEquals(-45.0, SpeedHandles.speed(seg, neg, 0), 1e-3)
        assertEquals(3f, SpeedHandles.set(seg, floatArrayOf(.33f, 0f, .67f, 1f), 0, 1.0, 1e6)[1], 0f)
        assertEquals(.01f, SpeedHandles.set(seg, floatArrayOf(.33f, 0f, .67f, 1f), 0, -1.0, 10.0)[0], 0f)
        assertEquals(0f, SpeedHandles.set(seg, floatArrayOf(.33f, 0f, .67f, 1f), 1, 5.0, 10.0)[2], 0f)
        // x1 → 0 (Linear as a bezier: 0, 0, 1, 1): the speed is the limit, the average.
        assertEquals(seg.rate, SpeedHandles.speed(seg, floatArrayOf(0f, 0f, 1f, 1f), 0), seg.rate * 1e-3)
        // Flat: speeds 0, the influence still moves, y is untouched.
        val flat = segs(layer(v1 = 0f, v2 = 0f))[0]
        assertTrue(flat.flat); assertEquals(0.0, SpeedHandles.speed(flat, floatArrayOf(.33f, .2f, .67f, 1f), 0), 0.0)
        assertArrayEquals(floatArrayOf(.6f, .2f, .67f, 1f), SpeedHandles.set(flat, floatArrayOf(.33f, .2f, .67f, 1f), 0, .6, 99.0), 1e-6f)
        // The handle point sits level with its key: (t0 + i·Δt, S0) and (t1 − i·Δt, S1).
        val b = floatArrayOf(.25f, .5f, .6f, 1f)
        assertEquals(250_000.0, SpeedHandles.point(seg, b, 0).first, .1); assertEquals(seg.rate * 2, SpeedHandles.point(seg, b, 0).second, 1e-4)
        assertEquals(600_000.0, SpeedHandles.point(seg, b, 1).first, .1); assertEquals(0.0, SpeedHandles.point(seg, b, 1).second, 1e-9)
        assertEquals(SpeedHandles.point(seg, b, 0).second, SpeedHandles.anchor(seg, b, 0).second, 0.0)
    }

    @Test fun dragSnapsInfluenceTo5PercentAndSpeedToTheGrid() {
        val seg = segs(layer())[0]
        val b = SpeedHandles.drag(seg, floatArrayOf(.33f, 0f, .67f, 1f), 0, 231_000.0, 47.0, snap = true, grid = 10.0)
        assertEquals(.25f, b[0], 1e-6f); assertEquals(50.0, SpeedHandles.speed(seg, b, 0), 1e-4)
        val free = SpeedHandles.drag(seg, floatArrayOf(.33f, 0f, .67f, 1f), 1, 770_000.0, 47.0, snap = false, grid = 10.0)
        assertEquals(.23, SpeedHandles.influence(free, 1), 1e-6); assertEquals(47.0, SpeedHandles.speed(seg, free, 1), 1e-3)
    }

    @Test fun smoothThroughKeyMatchesSpeedsAcrossAKey() {
        // Same direction (0 → 90 → 180): editing segment 1's in-handle at key 2 sets segment 2's out speed to the same value.
        val s = segs(layer())
        val edited = SpeedHandles.set(s[0], s[0].bezier!!, 1, .4, 120.0)
        val r = SmoothThroughKey.apply(s, 0, 1, edited, enabled = true)
        assertEquals(2, r.edits.size); assertEquals(0L, r.edits[0].first); assertEquals(1_000_000L, r.edits[1].first)
        val nb = r.edits[1].second.bezierOrNull()!!
        assertEquals(120.0, SpeedHandles.speed(s[1], nb, 0), 1e-3)
        assertEquals("influence untouched", SpeedHandles.influence(s[1].bezier!!, 0), SpeedHandles.influence(nb, 0), 1e-6)
        assertEquals(120.0, SpeedHandles.speed(s[0], r.edits[0].second.bezierOrNull()!!, 1), 1e-3)
        // The other way round: segment 2's out-handle at key 2 sets segment 1's in-handle.
        val e2 = SpeedHandles.set(s[1], s[1].bezier!!, 0, .3, 70.0)
        val r2 = SmoothThroughKey.apply(s, 1, 0, e2, true)
        assertEquals(70.0, SpeedHandles.speed(s[0], r2.edits[1].second.bezierOrNull()!!, 1), 1e-3)
        // Opposite directions (0 → 90 → 45, a peak at key 2): both sides become 0; the edited handle is pinned to 0.
        val p = segs(layer(v2 = 45f))
        val r3 = SmoothThroughKey.apply(p, 0, 1, SpeedHandles.set(p[0], p[0].bezier!!, 1, .4, 120.0), true)
        assertEquals(0.0, SpeedHandles.speed(p[0], r3.edits[0].second.bezierOrNull()!!, 1), 1e-9)
        assertEquals(0.0, SpeedHandles.speed(p[1], r3.edits[1].second.bezierOrNull()!!, 0), 1e-9)
        assertEquals(.4, SpeedHandles.influence(r3.edits[0].second.bezierOrNull()!!, 1), 1e-6)
        // First key's out-handle and last key's in-handle have no neighbour; toggle off changes no neighbour.
        assertEquals(1, SmoothThroughKey.apply(s, 0, 0, edited, true).edits.size)
        assertEquals(1, SmoothThroughKey.apply(s, 1, 1, e2, true).edits.size)
        assertEquals(1, SmoothThroughKey.apply(s, 0, 1, edited, false).edits.size)
        // A non-bezier neighbour is left alone.
        val bounce = segs(layer(c1 = EasingCurve.bounce(3f, .5f)))
        assertEquals(1, SmoothThroughKey.apply(bounce, 0, 1, edited, true).edits.size)
    }

    /** The edits join in ONE Batch of SetKeyEasing: one undo restores both keys. */
    @Test fun smoothEditIsOneUndo() {
        val l = layer()
        val v = EditorViewModel()
        v.dispatch(EditorAction.Load(ProjectDocument(com.fluxx.android.model.Composition(durationUs = 4_000_000, layers = FrozenList.of(listOf(l))))))
        v.dispatch(EditorAction.Select(1)); v.dispatch(EditorAction.Seek(1_234_567))
        val s = segs(l)
        val r = SmoothThroughKey.apply(s, 0, 1, SpeedHandles.set(s[0], s[0].bezier!!, 1, .4, 120.0), true)
        val batch = EditorAction.Batch(r.edits.map { (k, c) -> GraphModel.action(1, rot, listOf(k), c) })
        v.beginGesture(); v.previewGesture(batch); v.commitGesture()
        val keys = v.state.project.composition.layers[0].animTransform.rotation.keyframes
        assertEquals(r.edits[0].second, keys[0].curve); assertEquals(r.edits[1].second, keys[1].curve)
        assertEquals(1_234_567L, v.state.playheadUs)
        v.undo(); assertEquals(l.animTransform.rotation, v.state.project.composition.layers[0].animTransform.rotation)
    }

    @Test fun sceneSpeedViewFitsZeroAndPlacesKeysAndHandles() {
        val l = layer(); val a = axes(l, rot)
        val seg = GraphModel.segment(l, rot, 0)!!
        val scene = ValueScene(l, rot, rate, a, 0, seg, null, speed = true)
        assertTrue(scene.fit.v0 <= 0.0 && scene.fit.v1 > 0.0)
        assertEquals(1, scene.curves); assertEquals(0.0, scene.keyY(0), 1e-9) // Easy Ease starts at rest
        val h = scene.handlePoint(0)!!
        assertEquals(330_000.0, h.first, .1); assertEquals(0.0, h.second, 1e-9)
        val e = scene.dragHandle(seg.curve.bezierOrNull()!!, 0, 250_000.0, 60.0, snap = false, mirror = false, grid = 10.0)!!
        assertEquals(EasingPreset.CUSTOM, e.curve.type); assertEquals(60.0, SpeedHandles.speed(scene.speedSegs[0], e.curve.bezierOrNull()!!, 0), 1e-3)
        // The Value view's scene is unchanged by the speed machinery.
        val value = ValueScene(l, rot, rate, a, 0, seg, null)
        assertEquals(0.0, value.keyY(0), 1e-9); assertEquals(90.0, value.keyY(1), 1e-4)
    }

    @Test fun speedLabelsUseTheValueGraphsUnits() {
        val l = layer()
        val t = CompositionLayer(2, LayerType.TEXT, timing = ClipTiming(0, 0, 4_000_000), text = TextProperties(source = AnimatableString(staticValue = "A"),
            size = AnimatableProperty1D(true, 50f, FrozenList.of(listOf(Keyframe1D(0, 50f), Keyframe1D(1_000_000, 90f))))))
        val table = listOf(
            Triple(axes(l, pos)[0], "px/s", 150.25 to "150.3"),
            Triple(axes(l, rot)[0], "°/s", 45.0 to "45.0"),
            Triple(axes(l, GraphTarget(AnimPropertyType.OPACITY))[0], "%/s", 12.5 to "12.5"),
            Triple(axes(l, GraphTarget(AnimPropertyType.SCALE))[0], "%/s", 150.0 to "150"),
            Triple(axes(t, GraphTarget(AnimPropertyType.FONT_SIZE))[0], "px/s", 40.0 to "40"))
        for ((axis, unit, sample) in table) {
            assertEquals(unit, GraphSpeed.unit(axis)); assertEquals(sample.second, GraphSpeed.text(axis, sample.first))
        }
    }

    @Test fun lastUsedTabPersistsAndFallsBack() {
        assertEquals("EASE", CurveLibraryState().graphTab); assertFalse(CurveLibraryState().smoothThroughKey)
        val st = CurveLibraryFile.decode(CurveLibraryFile.encode(CurveLibraryState(graphTab = "SPEED", smoothThroughKey = true)))!!
        assertEquals("SPEED", st.graphTab); assertTrue(st.smoothThroughKey)
        val old = CurveLibraryFile.encode(CurveLibraryState()).replace(",\"graphTab\":\"EASE\",\"smoothThroughKey\":false", "")
        assertFalse(old.contains("graphTab")); assertEquals("EASE", CurveLibraryFile.decode(old)!!.graphTab)
        assertEquals("EASE", CurveLibraryFile.decode(CurveLibraryFile.encode(CurveLibraryState()).replace("\"EASE\"", "\"BOGUS\""))!!.graphTab)
        assertEquals(GraphMode.SPEED, GraphTabs.parse("SPEED")); assertEquals(GraphMode.EASE, GraphTabs.parse(null))
        assertEquals(GraphMode.EASE, GraphTabs.effective(GraphMode.SPEED, valueAvailable = false))
        assertEquals(GraphMode.SPEED, GraphTabs.effective(GraphMode.SPEED, valueAvailable = true))
        val session = GraphSession(); session.reset(GraphMode.SPEED); assertEquals(GraphMode.SPEED, session.mode); assertNull(session.speedViewport)
    }
}
