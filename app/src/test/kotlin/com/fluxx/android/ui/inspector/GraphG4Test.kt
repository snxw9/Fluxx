package com.fluxx.android.ui.inspector

import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** GRAPH G4.1: Convert to keys (Bounce exact on the µs grid, Elastic fitted), one undo, measured error bounds. */
class GraphG4Test {
    private val rate = FrameRate(30, 1)
    private val rot = GraphTarget(AnimPropertyType.ROTATION)
    private val pos = GraphTarget(AnimPropertyType.POSITION)
    private val size = GraphTarget(AnimPropertyType.FONT_SIZE)

    private fun solid(c: EasingCurve, endUs: Long = 1_000_000) = CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(0, 0, 4_000_000),
        animTransform = AnimatableTransform(
            rotation = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(Keyframe1D(0, 0f, EasingPreset.LINEAR), Keyframe1D(500_000, 10f).withCurve(c),
                Keyframe1D(500_000 + endUs, 100f), Keyframe1D(3_000_000, 50f)))),
            position = AnimatableProperty2D(true, keyframes = FrozenList.of(listOf(Keyframe2D(0, 10f, 20f).withCurve(c), Keyframe2D(endUs, 110f, -180f))))))
    private fun text(c: EasingCurve) = CompositionLayer(1, LayerType.TEXT, timing = ClipTiming(0, 0, 4_000_000),
        text = TextProperties(source = AnimatableString(staticValue = "AB"),
            size = AnimatableProperty1D(true, 50f, FrozenList.of(listOf(Keyframe1D(0, 50f).withCurve(c), Keyframe1D(2_000_000, 90f))))))
    private fun vm(l: CompositionLayer) = EditorViewModel().also {
        it.dispatch(EditorAction.Load(ProjectDocument(Composition(durationUs = 4_000_000, layers = FrozenList.of(listOf(l))))))
        it.dispatch(EditorAction.Select(1))
    }
    private fun seg(l: CompositionLayer, t: GraphTarget, start: Long) = GraphModel.segment(l, t, start)!!

    /** Max |original − converted| / |travel| over [step]-µs samples of the segment, per channel. */
    private fun maxError(before: CompositionLayer, after: CompositionLayer, t: GraphTarget, s: GraphSegment, step: Long): Double {
        val a = GraphValues.sampler(before, t)!!; val b = GraphValues.sampler(after, t)!!
        val ka = GraphValues.keys(before, t); val k0 = ka.first { it.timeUs == s.startUs }; val k1 = ka.first { it.timeUs == s.endUs }
        val oa = FloatArray(2); val ob = FloatArray(2); var worst = 0.0
        val channels = GraphValues.channels(before, t)
        var time = s.startUs
        while (time <= s.endUs) {
            a(time, oa); b(time, ob)
            for (ch in 0 until channels) {
                val travel = abs(k1.value(ch) - k0.value(ch)).toDouble()
                if (travel > 0) worst = maxOf(worst, abs(oa[ch] - ob[ch]) / travel)
            }
            time += step
        }
        return worst
    }

    private fun convert(l: CompositionLayer, t: GraphTarget, start: Long): Pair<CompositionLayer, GraphSegment> {
        val s = seg(l, t, start)
        val v = vm(l)
        v.dispatch(GraphConvert.action(l, t, s, rate)!!)
        return v.state.project.composition.layers.single() to s
    }

    @Test fun bounceIsExactOnTheMicrosecondGrid() {
        val report = StringBuilder()
        var worst = 0.0
        for (n in 1..8) for (e in listOf(.1f, .3f, .5f, .7f, .9f)) for (d in listOf(400_000L, 1_000_000L, 2_400_000L)) {
            val l = solid(EasingCurve.bounce(n.toFloat(), e), d)
            val (after, s) = convert(l, rot, 500_000)
            val err = maxError(l, after, rot, s, 50)
            worst = maxOf(worst, err)
            assertTrue("bounce n=$n e=$e d=$d err=$err", err < 1e-4)
            // Ends preserved; every new segment is Custom; the neighbours are untouched.
            val keys = after.animTransform.rotation.keyframes
            assertEquals(10f, keys.first { it.timeUs == 500_000L }.value, 0f)
            assertEquals(100f, keys.first { it.timeUs == 500_000L + d }.value, 0f)
            assertEquals(0f, keys.first().value, 0f); assertEquals(EasingPreset.LINEAR, keys.first().easing)
            assertEquals(50f, keys.last().value, 0f)
            keys.filter { it.timeUs in 500_000L until 500_000L + d }.forEach { assertEquals(EasingPreset.CUSTOM, it.easing) }
        }
        val l = solid(EasingCurve.bounce(3f, .5f)); val (after, s) = convert(l, rot, 500_000)
        // One fall + 3 arcs: the first impact, then an apex and an impact per arc; the last impact is the end key → 6 new keys.
        assertEquals(4 + 6, after.animTransform.rotation.keyframes.size)
        report.append("Bounce (µs grid, 40 count×height×duration cases): max error ${"%.2e".format(worst)} of travel; 3/0.5 example ${"%.2e".format(maxError(l, after, rot, s, 10))}")
        println("G4.1 " + report)
    }

    @Test fun elasticIsWithinOnePercent() {
        var worst = 0.0; var keys = 0
        for (n in listOf(1f, 2f, 3f, 4.5f, 7f, 10f)) for (damp in listOf(.1f, .3f, .5f, 1f)) for (d in listOf(400_000L, 1_000_000L, 2_400_000L)) {
            val l = solid(EasingCurve.elastic(n, damp), d)
            val (after, s) = convert(l, rot, 500_000)
            val err = maxError(l, after, rot, s, 50)
            worst = maxOf(worst, err); keys = maxOf(keys, after.animTransform.rotation.keyframes.size - 4)
            assertTrue("elastic n=$n damping=$damp d=$d err=$err", err <= GraphConvert.TARGET)
            assertEquals(100f, after.animTransform.rotation.keyframes.first { it.timeUs == 500_000L + d }.value, 0f)
        }
        println("G4.1 Elastic (µs grid, 72 oscillations×damping×duration cases): max error ${"%.4f".format(worst * 100)}% of travel, at most $keys new keys")
    }

    @Test fun twoDimensionalConvertsBothAxesWithTheSameKeyTimes() {
        for (c in listOf(EasingCurve.bounce(4f, .6f), EasingCurve.elastic(3f, .4f))) {
            val l = solid(c, 2_000_000)
            val (after, s) = convert(l, pos, 0)
            val err = maxError(l, after, pos, s, 50)
            assertTrue("$c err=$err", err <= if (c.type == EasingPreset.BOUNCE) 1e-4 else GraphConvert.TARGET)
            val k = after.animTransform.position.keyframes
            assertEquals(10f, k.first().x, 0f); assertEquals(20f, k.first().y, 0f)
            assertEquals(110f, k.last().x, 0f); assertEquals(-180f, k.last().y, 0f)
            assertTrue(k.size > 4)
            println("G4.1 Position ${c.type}: max error ${"%.2e".format(err)} of travel (both axes), ${k.size - 2} new keys")
        }
    }

    @Test fun frameGridTracksAreKeyedOnFramesAndMeasuredOnFrames() {
        for (c in listOf(EasingCurve.bounce(3f, .5f), EasingCurve.elastic(3f, .5f), EasingCurve.elastic(10f, .1f))) {
            val l = text(c)
            val (after, s) = convert(l, size, 0)
            val keys = after.text.size.keyframes
            keys.forEach { assertEquals(it.timeUs, rate.snap(it.timeUs)) }
            // Measured on the frames, where text tracks are evaluated.
            var worst = 0.0
            for (f in 0..60L) {
                val t = rate.frameTimeUs(f)
                worst = maxOf(worst, abs(l.text.size.evaluate(t) - after.text.size.evaluate(t)) / 40.0)
            }
            assertTrue("$c err=$worst", worst <= GraphConvert.TARGET)
            println("G4.1 Font size (frame grid, 30 fps, 2 s) ${c.type} ${c.a}/${c.b}: max error at frames ${"%.4f".format(worst * 100)}% of travel, ${keys.size - 2} new keys")
        }
    }

    @Test fun oneUndoAndEnabledOnlyForBounceAndElastic() {
        val l = solid(EasingCurve.elastic(3f, .5f))
        val v = vm(l)
        val before = v.state.project
        v.dispatch(GraphConvert.action(l, rot, seg(l, rot, 500_000), rate)!!)
        assertNotEquals(before, v.state.project)
        v.undo()
        assertEquals(before, v.state.project)
        assertFalse(v.canUndo)
        // Disabled for bezier types and for colour (no Value graph).
        val plain = solid(EasingCurve.EASY_EASE)
        assertFalse(GraphConvert.enabled(plain, rot, seg(plain, rot, 500_000)))
        assertNull(GraphConvert.action(plain, rot, seg(plain, rot, 500_000), rate))
        val colour = CompositionLayer(1, LayerType.TEXT, timing = ClipTiming(0, 0, 4_000_000), text = TextProperties(source = AnimatableString(staticValue = "AB"),
            fill = AnimatableColour(true, -1, FrozenList.of(listOf(ColourKeyframe(0, -1).withCurve(EasingCurve.bounce(3f, .5f)), ColourKeyframe(1_000_000, 0))))))
        val fill = GraphTarget(AnimPropertyType.FILL_COLOUR)
        assertFalse(GraphConvert.enabled(colour, fill, GraphModel.segment(colour, fill, 0)))
    }

    @Test fun bouncePiecesAreTheEvaluatorsQuadratics() {
        val c = EasingCurve.bounce(3f, .5f)
        val pieces = GraphConvert.bouncePieces(c)
        for (i in 0 until pieces.lastIndex) {
            val (u0, p0, curve) = pieces[i]; val (u1, p1, _) = pieces[i + 1]
            for (j in 0..20) {
                val s = j / 20.0
                val expected = c.ease((u0 + s * (u1 - u0)).toFloat())
                val got = p0 + (p1 - p0) * curve!!.ease(s.toFloat())
                assertEquals(expected.toDouble(), got, 2e-6)
            }
        }
    }
}

/** GRAPH G4.3: the Speed view's one re-frame after a library / strip / preset tap. */
class GraphG43ReframeTest {
    private val vp = ValueViewport(0.0, 1_000_000.0, -10.0, 110.0)
    private val home = ValueViewport(-80_000.0, 1_080_000.0, -40.0, 400.0)

    @Test fun reframesOnlyWhenAHandleLeavesOrNearsAnEdge() {
        // Well inside: no move.
        assertNull(SpeedFrame.reframe(vp, listOf(300_000.0 to 50.0, 700_000.0 to 40.0), home))
        // Outside (above): to the segment frame.
        assertEquals(home, SpeedFrame.reframe(vp, listOf(300_000.0 to 250.0, 700_000.0 to 40.0), home))
        // Within 10% of the bottom edge (−10 + 12 = 2): to the segment frame.
        assertEquals(home, SpeedFrame.reframe(vp, listOf(300_000.0 to 1.0, 700_000.0 to 40.0), home))
        // Time edges:
        // Near a time edge but inside: no move (the frame puts zero-influence handles there); outside in time: move.
        assertNull(SpeedFrame.reframe(vp, listOf(50_000.0 to 50.0), home))
        assertEquals(home, SpeedFrame.reframe(vp, listOf(-1.0 to 50.0), home))
        // No handles (Bounce / Elastic / Hold) or already home: nothing.
        assertNull(SpeedFrame.reframe(vp, emptyList(), home))
        assertNull(SpeedFrame.reframe(home, listOf(0.0 to 1e9), home))
    }

    /** The real page model: an extreme preset on a calm segment's frame needs a re-frame; a calm one does not. */
    @Test fun extremePresetOnTheRealScene() {
        val rate = FrameRate(30, 1)
        fun layer(c: EasingCurve) = CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(0, 0, 4_000_000), animTransform = AnimatableTransform(
            rotation = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(Keyframe1D(0, 0f).withCurve(c), Keyframe1D(1_000_000, 90f), Keyframe1D(3_000_000, 180f))))))
        val target = GraphTarget(AnimPropertyType.ROTATION)
        fun scene(c: EasingCurve): ValueScene {
            val l = layer(c)
            val axes = GraphValues.axes(l, target, 1080, 1920)
            return ValueScene(l, target, rate, axes, 0, GraphModel.segment(l, target, 0)!!, null, 0L, speed = true, smooth = false)
        }
        val calm = scene(EasingCurve.EASY_EASE)
        val window = calm.home
        val handles = { s: ValueScene -> (0..1).mapNotNull { s.handlePoint(it) } }
        assertNull(SpeedFrame.reframe(window, handles(scene(EasingCurve.bezier(.4f, 0f, .6f, 1f))), scene(EasingCurve.bezier(.4f, 0f, .6f, 1f)).home))
        val wild = scene(EasingCurve.bezier(.05f, 1f, .95f, 0f))
        assertEquals(wild.home, SpeedFrame.reframe(window, handles(wild), wild.home))
    }
}
