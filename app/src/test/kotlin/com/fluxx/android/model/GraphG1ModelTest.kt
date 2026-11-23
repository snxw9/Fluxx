package com.fluxx.android.model

import com.fluxx.android.editor.*
import com.google.flatbuffers.FlatBufferBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** GRAPH G1: easing model (schema v13), evaluator, codec, curve-carrying edit paths, TrackMemo and SetKeyEasing. */
class GraphG1ModelTest {
    private val backOut = EasingCurve.bezier(.34f, 1.56f, .64f, 1f)
    private val backIn = EasingCurve.bezier(.36f, 0f, .66f, -.56f)
    private val wild = EasingCurve.bezier(.1f, -2f, .9f, 3f)

    private fun track1D(c: EasingCurve, a: Float = 0f, b: Float = 100f, end: Long = 1_000_000) =
        AnimatableProperty1D(true, a, FrozenList.of(listOf(Keyframe1D(0, a).withCurve(c), Keyframe1D(end, b))))

    /** Independent double-precision reference: bisection on x(s), then y(s). */
    private fun reference(p: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double {
        if (p <= 0) return 0.0; if (p >= 1) return 1.0
        var lo = 0.0; var hi = 1.0
        repeat(80) {
            val s = (lo + hi) / 2; val u = 1 - s
            val x = 3 * u * u * s * x1 + 3 * u * s * s * x2 + s * s * s
            if (x < p) lo = s else hi = s
        }
        val s = (lo + hi) / 2; val u = 1 - s
        return 3 * u * u * s * y1 + 3 * u * s * s * y2 + s * s * s
    }

    // ---- Evaluator ----------------------------------------------------------------------------------------------
    @Test fun linearAndEasyEaseKeepTheirFastPathBitForBit() {
        for (i in -20..1020) {
            val p = i / 1000f
            for (e in listOf(EasingPreset.LINEAR, EasingPreset.EASY_EASE)) {
                val fast = KeyframeEvaluator.easeProgress(p, e)
                assertEquals(fast.toRawBits(), KeyframeEvaluator.ease(p, e, 9f, 9f, 9f, 9f).toRawBits())
                assertEquals(fast.toRawBits(), EasingCurve(e).ease(p).toRawBits())
            }
        }
        // Keyed evaluation of default keys is the pre-G1 formula.
        val t = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(Keyframe1D(0, 3f), Keyframe1D(1_000_000, 11f, EasingPreset.LINEAR), Keyframe1D(2_000_000, -4f))))
        for (time in 0L..2_000_000L step 7_919) {
            val i = if (time < 1_000_000) 0 else 1
            val k0 = t.keyframes[i]; val k1 = t.keyframes[i + 1]
            val e = KeyframeEvaluator.easeProgress((time - k0.timeUs).toFloat() / (k1.timeUs - k0.timeUs).toFloat(), k0.easing)
            assertEquals((k0.value.toDouble() * (1.0 - e) + k1.value.toDouble() * e).toFloat().toRawBits(), t.evaluate(time).toRawBits())
        }
    }

    @Test fun holdStepsOn1D2DAndColour() {
        val s = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(Keyframe1D(0, 5f, EasingPreset.HOLD), Keyframe1D(1_000_000, 9f, EasingPreset.HOLD), Keyframe1D(2_000_000, 1f))))
        assertEquals(5f, s.evaluate(0), 0f); assertEquals(5f, s.evaluate(999_999), 0f)
        assertEquals(9f, s.evaluate(1_000_000), 0f); assertEquals(9f, s.evaluate(1_999_999), 0f); assertEquals(1f, s.evaluate(2_000_000), 0f)
        val v = AnimatableProperty2D(true, keyframes = FrozenList.of(listOf(Keyframe2D(0, 1f, 2f, EasingPreset.HOLD), Keyframe2D(1_000_000, 3f, 4f))))
        assertEquals(1f to 2f, v.evaluatePair(500_000)); assertEquals(3f to 4f, v.evaluatePair(1_000_000))
        val c = AnimatableColour(true, keyframes = FrozenList.of(listOf(ColourKeyframe(0, 0xffff0000.toInt(), EasingPreset.HOLD), ColourKeyframe(1_000_000, 0xff0000ff.toInt()))))
        assertEquals(0xffff0000.toInt(), c.evaluate(999_999)); assertEquals(0xff0000ff.toInt(), c.evaluate(1_000_000))
    }

    @Test fun customEqualsLinearAndEasyEaseAtTheirOwnFloats() {
        for (i in 0..1000) {
            val p = i / 1000f
            assertEquals(p, EasingCurve.bezier(0f, 0f, 1f, 1f).ease(p), 1e-5f)
            assertEquals(KeyframeEvaluator.easeProgress(p, EasingPreset.EASY_EASE), EasingCurve.bezier(.33f, 0f, .67f, 1f).ease(p), 2e-4f)
        }
    }

    @Test fun customOvershootsAndUndershootsUnclamped() {
        val samples = (0..200).map { backOut.ease(it / 200f) }
        assertTrue(samples.max() > 1.05f)
        assertTrue((0..200).map { backIn.ease(it / 200f) }.min() < -.05f)
        val t = track1D(backOut)
        assertTrue((0..100).map { t.evaluate(it * 10_000L) }.max() > 100f)
        assertEquals(100f, t.evaluate(1_000_000), 0f); assertEquals(0f, t.evaluate(0), 0f)
    }

    @Test fun solverMatchesAnIndependentDoubleSolveIncludingExtremeHandles() {
        val random = Random(1234)
        val curves = mutableListOf(floatArrayOf(0f, 0f, 0f, 0f), floatArrayOf(1f, 1f, 1f, 1f), floatArrayOf(1f, -2f, 0f, 3f),
            floatArrayOf(0f, 3f, 1f, -2f), floatArrayOf(1f, 0f, 1f, 0f), floatArrayOf(0f, 1f, 0f, 1f), floatArrayOf(.5f, -2f, .5f, 3f),
            floatArrayOf(.999f, 0f, .001f, 1f))
        repeat(400) { curves += floatArrayOf(random.nextFloat(), random.nextFloat() * 5 - 2, random.nextFloat(), random.nextFloat() * 5 - 2) }
        for (c in curves) for (i in 0..400) {
            val p = i / 400f
            val expected = reference(p.toDouble(), c[0].toDouble(), c[1].toDouble(), c[2].toDouble(), c[3].toDouble())
            assertEquals("curve ${c.toList()} at $p", expected.toFloat(), EasingMath.bezier(p, c[0], c[1], c[2], c[3]), 5e-4f)
        }
    }

    @Test fun timeMappingIsMonotonicForMonotoneHandles() {
        val random = Random(99)
        repeat(200) {
            val c = EasingCurve.bezier(random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat())
            var last = -1f
            for (i in 0..500) { val v = c.ease(i / 500f); assertTrue(v >= last - 1e-5f); last = v }
        }
    }

    @Test fun segmentBoundariesAreExactOnEveryTrackKind() {
        val s = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(Keyframe1D(100, 2f).withCurve(wild), Keyframe1D(200, 7f).withCurve(backOut), Keyframe1D(300, -1f))))
        assertEquals(2f, s.evaluate(0), 0f); assertEquals(2f, s.evaluate(100), 0f); assertEquals(7f, s.evaluate(200), 0f)
        assertEquals(-1f, s.evaluate(300), 0f); assertEquals(-1f, s.evaluate(400), 0f)
        val v = AnimatableProperty2D(true, keyframes = FrozenList.of(listOf(Keyframe2D(0, 0f, 10f).withCurve(backOut), Keyframe2D(1000, 10f, 0f))))
        assertEquals(10f to 0f, v.evaluatePair(1000))
        val mid = v.evaluatePair(500)
        // One curve drives both channels.
        assertEquals(backOut.ease(.5f), mid.first / 10f, 1e-5f); assertEquals(backOut.ease(.5f), 1f - mid.second / 10f, 1e-5f)
    }

    @Test fun colourProgressIsClampedSoChannelsNeverOvershoot() {
        val red = 0xffff0000.toInt(); val blue = 0xff0000ff.toInt()
        val c = AnimatableColour(true, keyframes = FrozenList.of(listOf(ColourKeyframe(0, red).withCurve(backOut), ColourKeyframe(1_000_000, blue))))
        val overshootTime = (1..99).map { it * 10_000L }.first { backOut.ease(it / 1_000_000f) > 1f }
        assertEquals(blue, c.evaluate(overshootTime))
        val e = AnimatableColour(true, keyframes = FrozenList.of(listOf(ColourKeyframe(0, red).withCurve(EasingCurve.elastic(4f, .2f)), ColourKeyframe(1_000_000, blue))))
        for (t in 0L..1_000_000L step 5_000) {
            val argb = e.evaluate(t)
            assertTrue((argb ushr 16 and 255) in 0..255 && (argb and 255) in 0..255 && (argb ushr 24) == 255)
        }
        assertEquals(blue, e.evaluate(1_000_000))
    }

    // ---- Bounce / Elastic -----------------------------------------------------------------------------------------
    @Test fun bounceAndElasticEndpointsContinuityDeterminism() {
        for (n in 1..8) for (r in listOf(.1f, .5f, .9f)) {
            assertEquals(0f, EasingMath.bounce(0f, n.toFloat(), r), 0f); assertEquals(1f, EasingMath.bounce(1f, n.toFloat(), r), 0f)
            assertEquals(1f, EasingMath.bounce(1.5f, n.toFloat(), r), 0f)
            var last = 0f; var touches = 0
            for (i in 1..20_000) {
                val v = EasingMath.bounce(i / 20_000f, n.toFloat(), r)
                assertTrue("bounce never above the target", v <= 1f && v >= 0f)
                assertTrue("continuous", kotlin.math.abs(v - last) < .02f)
                if (v > .99995f && last <= .99995f) touches++
                last = v
                assertEquals(v.toRawBits(), EasingMath.bounce(i / 20_000f, n.toFloat(), r).toRawBits())
            }
            assertTrue("reaches the target and bounces ($n, $r: $touches)", touches >= 1)
        }
        for (n in listOf(1f, 3f, 10f)) for (d in listOf(.1f, .5f, 1f)) {
            assertEquals(0f, EasingMath.elastic(0f, n, d), 0f); assertEquals(1f, EasingMath.elastic(1f, n, d), 0f)
            var last = 0f; var max = 0f
            for (i in 1..20_000) {
                val t = i / 20_000f; val v = EasingMath.elastic(t, n, d)
                assertTrue("continuous", kotlin.math.abs(v - last) < .05f); last = v; max = maxOf(max, v)
                assertTrue("envelope decays to 0 at the end", kotlin.math.abs(v - 1f) <= (1f - t) + 1e-5f)
            }
            assertTrue("elastic overshoots", max > 1f)
        }
    }

    // ---- Validation and codec -------------------------------------------------------------------------------------
    @Test fun parametersAreValidatedLikeOtherValues() {
        assertThrows(IllegalArgumentException::class.java) { EasingCurve.bezier(1.2f, 0f, .5f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { EasingCurve.bezier(.2f, 3.5f, .5f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { EasingCurve.bounce(9f, .5f) }
        assertThrows(IllegalArgumentException::class.java) { EasingCurve.elastic(3f, 0f) }
        assertThrows(IllegalArgumentException::class.java) { Keyframe1D(0, 1f, EasingPreset.LINEAR, Float.NaN) }
        // A new key given only a type gets that type's defaults.
        assertEquals(EasingCurve.bounce(3f, .5f), Keyframe1D(0, 1f, EasingPreset.BOUNCE).curve)
        assertEquals(EasingCurve.EASY_EASE, Keyframe1D(0, 1f).curve)
    }

    private fun everyType() = listOf(EasingCurve.LINEAR, EasingCurve.EASY_EASE, EasingCurve.HOLD, wild, backOut,
        EasingCurve.bezier(0f, 0f, 1f, 1f), EasingCurve.bounce(5f, .3f), EasingCurve.elastic(7.5f, .8f),
        // A Linear key that still carries a former Custom curve round-trips exactly too.
        EasingCurve(EasingPreset.LINEAR, .1f, -2f, .9f, 3f))

    private fun richProject(): ProjectDocument {
        val curves = everyType()
        fun s(base: Float) = AnimatableProperty1D(true, base, FrozenList.of(curves.mapIndexed { i, c -> Keyframe1D(i * 100_000L, base + i).withCurve(c) }))
        fun v() = AnimatableProperty2D(true, 1f, 1f, FrozenList.of(curves.mapIndexed { i, c -> Keyframe2D(i * 100_000L, 1f + i, 2f - i).withCurve(c) }))
        fun col() = AnimatableColour(true, -1, FrozenList.of(curves.mapIndexed { i, c -> ColourKeyframe(i * 100_000L, 0xff000000.toInt() or i).withCurve(c) }))
        val selector = TextRangeSelector(7, start = s(0f), offset = s(10f))
        val animator = TextAnimator(3, selectors = FrozenList.of(listOf(selector)), properties = FrozenList.of(listOf(
            TextPropertyOverride(AnimatorProperty.ROTATION, scalar = s(0f)), TextPropertyOverride(AnimatorProperty.POSITION, vector = v()),
            TextPropertyOverride(AnimatorProperty.FILL, colour = col()))))
        val text = TextProperties(source = AnimatableString(staticValue = "Hi"), size = s(20f), fill = col(), tracking = s(0f),
            strokeColour = col(), strokeWidth = s(1f), animators = FrozenList.of(listOf(animator)))
        // The static Transform mirrors the animated statics, as the codec stores it.
        val textLayer = CompositionLayer(1, LayerType.TEXT, timing = ClipTiming(0, 0, 2_000_000), text = text, transform = Transform(1f, 1f),
            animTransform = AnimatableTransform(v(), v(), s(0f), AnimatableProperty1D(true, 1f, FrozenList.of(curves.mapIndexed { i, c -> Keyframe1D(i * 100_000L, i / 10f).withCurve(c) }))))
        return ProjectDocument(Composition(durationUs = 2_000_000, layers = FrozenList.of(listOf(textLayer))))
    }

    @Test fun v13RoundTripsEveryEasingTypeOnEveryKeyKind() {
        val project = richProject()
        val bytes = ProjectCodec.encode(project)
        assertEquals(13, fluxx.schema.Project.getRootAsProject(java.nio.ByteBuffer.wrap(bytes)).version)
        assertEquals(project, ProjectCodec.decode(bytes))
        assertEquals(bytes.toList(), ProjectCodec.encode(ProjectCodec.decode(bytes)).toList())
    }

    @Test fun defaultCurvesCostNothingOnTheWire() {
        fun size(k: Keyframe1D): Int { val b = FlatBufferBuilder(64); b.finish(EasingWire.key1D(b, k)); return b.sizedByteArray().size }
        val plain = size(Keyframe1D(5, 1f)); val linear = size(Keyframe1D(5, 1f, EasingPreset.LINEAR))
        val custom = size(Keyframe1D(5, 1f).withCurve(EasingCurve.bezier(0f, 0f, .5f, .5f)))
        // Two v12-shaped keys (easing default omitted / LINEAR byte written) and a v13 key with all four floats.
        assertTrue(plain <= linear); assertTrue("custom $custom > linear $linear", custom >= linear + 12)
    }

    /** A v12 fixture written field by field as the v12 writer did (no curve fields). */
    private fun v12(transformEasing: Byte = 0, textEasing: Byte = 1, version: Int = 12): ByteArray {
        val b = FlatBufferBuilder(256)
        fun key1D(t: Long, v: Float, e: Byte): Int { fluxx.schema.Keyframe1D.startKeyframe1D(b); fluxx.schema.Keyframe1D.addTimeUs(b, t)
            fluxx.schema.Keyframe1D.addValue(b, v); fluxx.schema.Keyframe1D.addEasing(b, e); return fluxx.schema.Keyframe1D.endKeyframe1D(b) }
        val rot = fluxx.schema.AnimatableProperty1D.createAnimatableProperty1D(b, true, 0f,
            fluxx.schema.AnimatableProperty1D.createKeyframesVector(b, intArrayOf(key1D(0, 0f, transformEasing), key1D(1_000_000, 90f, 1))))
        val size = fluxx.schema.AnimatableProperty1D.createAnimatableProperty1D(b, true, 40f,
            fluxx.schema.AnimatableProperty1D.createKeyframesVector(b, intArrayOf(key1D(0, 40f, textEasing), key1D(1_000_000, 80f, 0))))
        val source = fluxx.schema.AnimatableString.createAnimatableString(b, false, b.createString("Hi"), 0, 0)
        fluxx.schema.TextPayload.startTextPayload(b); fluxx.schema.TextPayload.addSource(b, source); fluxx.schema.TextPayload.addSize(b, size)
        val payload = fluxx.schema.TextPayload.endTextPayload(b)
        fluxx.schema.Layer.startLayer(b)
        fluxx.schema.Layer.addId(b, 1UL); fluxx.schema.Layer.addType(b, LayerType.TEXT.ordinal); fluxx.schema.Layer.addDurationUs(b, 2_000_000)
        fluxx.schema.Layer.addAnimRotation(b, rot); fluxx.schema.Layer.addTextPayload(b, payload)
        val layer = fluxx.schema.Layer.endLayer(b)
        val layers = fluxx.schema.Composition.createLayersVector(b, intArrayOf(layer))
        fluxx.schema.Composition.startComposition(b); fluxx.schema.Composition.addLayers(b, layers)
        val c = fluxx.schema.Composition.endComposition(b)
        b.finish(fluxx.schema.Project.createProject(b, version, c)); return b.sizedByteArray()
    }

    @Test fun v12FixturesReadUnchanged() {
        val layer = ProjectCodec.decode(v12()).composition.layers.single()
        assertEquals(listOf(Keyframe1D(0, 0f, EasingPreset.LINEAR), Keyframe1D(1_000_000, 90f)), layer.animTransform.rotation.keyframes)
        assertEquals(listOf(Keyframe1D(0, 40f), Keyframe1D(1_000_000, 80f, EasingPreset.LINEAR)), layer.text.size.keyframes)
        // Historic leniency kept for v1–v12 transform tracks (any non-zero byte was Easy Ease); text tracks stay strict.
        assertEquals(EasingPreset.EASY_EASE, ProjectCodec.decode(v12(transformEasing = 2)).composition.layers.single().animTransform.rotation.keyframes[0].easing)
        assertThrows(IllegalArgumentException::class.java) { ProjectCodec.decode(v12(textEasing = 2)) }
        // From v13 the same bytes mean Hold; unknown bytes are corrupt data and refused.
        assertEquals(EasingPreset.HOLD, ProjectCodec.decode(v12(transformEasing = 2, version = 13)).composition.layers.single().animTransform.rotation.keyframes[0].easing)
        assertThrows(IllegalArgumentException::class.java) { ProjectCodec.decode(v12(transformEasing = 9, version = 13)) }
        // A project with only Linear / Easy Ease keys writes no curve field, so its v13 bytes read as v12 unchanged.
        val plain = ProjectDocument(Composition(layers = FrozenList.of(listOf(CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(0, 0, 1_000_000),
            animTransform = AnimatableTransform(rotation = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(Keyframe1D(0, 1f, EasingPreset.LINEAR), Keyframe1D(10, 2f))))))))))
        val bytes = ProjectCodec.encode(plain)
        val asV12 = bytes.copyOf().also { patchVersion(it, 12) }
        assertEquals(plain, ProjectCodec.decode(asV12))
    }
    private fun patchVersion(bytes: ByteArray, version: Int) {
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val root = bb.getInt(0); val vtable = root - bb.getInt(root)
        val field = bb.getShort(vtable + 4).toInt(); require(field != 0)
        bb.putInt(root + field, version)
    }

    @Test fun outOfRangeFloatsFromAFileAreClampedAndNonFiniteRefused() {
        fun file(e: Byte, a: Float, bb: Float, c: Float, d: Float): ByteArray {
            val b = FlatBufferBuilder(256)
            fun key(t: Long): Int { fluxx.schema.Keyframe1D.startKeyframe1D(b); fluxx.schema.Keyframe1D.addTimeUs(b, t); fluxx.schema.Keyframe1D.addValue(b, 1f)
                fluxx.schema.Keyframe1D.addEasing(b, e); b.addFloat(3, a, Double.NaN); b.addFloat(4, bb, Double.NaN); b.addFloat(5, c, Double.NaN); b.addFloat(6, d, Double.NaN)
                return fluxx.schema.Keyframe1D.endKeyframe1D(b) }
            val rot = fluxx.schema.AnimatableProperty1D.createAnimatableProperty1D(b, true, 0f, fluxx.schema.AnimatableProperty1D.createKeyframesVector(b, intArrayOf(key(0), key(10))))
            fluxx.schema.Layer.startLayer(b); fluxx.schema.Layer.addId(b, 1UL); fluxx.schema.Layer.addType(b, LayerType.SOLID.ordinal)
            fluxx.schema.Layer.addAnimRotation(b, rot); val layer = fluxx.schema.Layer.endLayer(b)
            val layers = fluxx.schema.Composition.createLayersVector(b, intArrayOf(layer))
            fluxx.schema.Composition.startComposition(b); fluxx.schema.Composition.addLayers(b, layers)
            val comp = fluxx.schema.Composition.endComposition(b); b.finish(fluxx.schema.Project.createProject(b, 13, comp)); return b.sizedByteArray()
        }
        fun curve(bytes: ByteArray) = ProjectCodec.decode(bytes).composition.layers.single().animTransform.rotation.keyframes[0].curve
        assertEquals(EasingCurve.bezier(1f, -2f, 0f, 3f), curve(file(3, 1.5f, -9f, -1f, 40f)))
        assertEquals(EasingCurve.bounce(8f, .1f), curve(file(4, 99f, 0f, 0f, 0f)))
        assertEquals(EasingCurve.elastic(1f, 1f), curve(file(5, 0f, 7f, 0f, 0f)))
        assertThrows(IllegalArgumentException::class.java) { curve(file(3, Float.NaN, 0f, 1f, 1f)) }
        assertThrows(IllegalArgumentException::class.java) { curve(file(1, .3f, Float.POSITIVE_INFINITY, 1f, 1f)) }
    }

    // ---- Every path that copies keys carries the curve --------------------------------------------------------------
    private fun keyed(c: EasingCurve = wild) = CompositionLayer(1, LayerType.TEXT, timing = ClipTiming(0, 0, 4_000_000),
        text = TextProperties(source = AnimatableString(staticValue = "AB"), size = AnimatableProperty1D(true, 50f, FrozenList.of(listOf(Keyframe1D(0, 50f).withCurve(c), Keyframe1D(3_000_000, 90f)))),
            fill = AnimatableColour(true, -1, FrozenList.of(listOf(ColourKeyframe(0, -1).withCurve(c), ColourKeyframe(3_000_000, 0xff000000.toInt())))),
            animators = FrozenList.of(listOf(TextAnimator(5, selectors = FrozenList.of(listOf(TextRangeSelector(6, start = AnimatableProperty1D(true, 0f,
                FrozenList.of(listOf(Keyframe1D(0, 0f).withCurve(c), Keyframe1D(3_000_000, 100f))))))),
                properties = FrozenList.of(listOf(TextPropertyOverride(AnimatorProperty.POSITION, vector = AnimatableProperty2D(true, 0f, 0f,
                    FrozenList.of(listOf(Keyframe2D(0, 0f, 0f).withCurve(c), Keyframe2D(3_000_000, 10f, 10f))))))))))),
        animTransform = AnimatableTransform(position = AnimatableProperty2D(true, keyframes = FrozenList.of(listOf(Keyframe2D(0, 0f, 0f).withCurve(c), Keyframe2D(3_000_000, .5f, .5f)))),
            rotation = AnimatableProperty1D(true, keyframes = FrozenList.of(listOf(Keyframe1D(0, 0f).withCurve(c), Keyframe1D(3_000_000, 90f))))))
    private fun vm(layer: CompositionLayer = keyed()) = EditorViewModel().also {
        it.dispatch(EditorAction.Load(ProjectDocument(Composition(durationUs = 4_000_000, layers = FrozenList.of(listOf(layer))))))
    }
    private fun curves(l: CompositionLayer) = listOf(l.animTransform.position.keyframes.first().curve, l.animTransform.rotation.keyframes.first().curve,
        l.text.size.keyframes.first().curve, l.text.fill.keyframes.first().curve, l.text.animators[0].selectors[0].start.keyframes.first().curve,
        l.text.animators[0].properties[0].vector!!.keyframes.first().curve)

    @Test fun rebaseSplitDuplicatePasteAndEncodeCarryTheCurve() {
        val v = vm()
        v.dispatch(EditorAction.Split(1, 1_000_000))
        for (l in v.state.project.composition.layers) assertEquals(List(6) { wild }, curves(l))
        v.duplicateLayer(1); v.copyLayer(1); v.pasteLayer()
        for (l in v.state.project.composition.layers) assertEquals(List(6) { wild }, curves(l))
        for (l in ProjectCodec.decode(ProjectCodec.encode(v.state.project)).composition.layers) assertEquals(List(6) { wild }, curves(l))
        assertEquals(List(6) { wild }, curves(keyed().copy(animTransform = keyed().animTransform.rebaseKeyframes(-250), text = keyed().text.rebase(-250))))
    }

    @Test fun valueEditsAndKeyMovesKeepAnExistingCurve() {
        val v = vm()
        // Every inspector value edit re-keys with the key's own easing type (as the UI callers pass it).
        val custom = EasingPreset.CUSTOM
        v.dispatch(EditorAction.SetKeyframe2D(1, AnimPropertyType.POSITION, 0, .2f, .3f, custom))
        v.dispatch(EditorAction.SetKeyframe(1, AnimPropertyType.ROTATION, 0, 33f, custom))
        v.dispatch(EditorAction.SetKeyframe(1, AnimPropertyType.FONT_SIZE, 0, 60f, custom))
        v.dispatch(TextEdits.colour(v.state.project.composition.layers[0], 0, AnimPropertyType.FILL_COLOUR, 0xff00ff00.toInt()))
        v.dispatch(EditorAction.EditTextAnimator(1, TextAnimatorEdits.set(v.state.project.composition.layers[0].text, TextAnimatorEdits.selectorField(5, 6, SelectorField.START), 0, 20f)))
        v.dispatch(EditorAction.EditTextAnimator(1, TextAnimatorEdits.set(v.state.project.composition.layers[0].text, TextAnimatorEdits.property(5, AnimatorProperty.POSITION), 0, 5f, 6f)))
        v.editTransform(1, v.state.project.composition.layers[0].evaluatedTransform(0).copy(rotationDegrees = 12f))
        var l = v.state.project.composition.layers[0]
        assertEquals(.2f, l.animTransform.position.keyframes[0].x, 0f); assertEquals(12f, l.animTransform.rotation.keyframes[0].value, 0f)
        assertEquals(60f, l.text.size.keyframes[0].value, 0f); assertEquals(20f, l.text.animators[0].selectors[0].start.keyframes[0].value, 0f)
        assertEquals(List(6) { wild }, curves(l))
        // Timeline Key MOVE (all properties) and TextEdits helpers carry it too.
        v.dispatch(EditorAction.MoveKeyframe(1, null, 0, 500_000))
        l = v.state.project.composition.layers[0]
        assertEquals(List(6) { wild }, curves(l)); assertEquals(500_000L, l.text.size.keyframes[0].timeUs)
        val sized = TextEdits.size(l, 500_000, 70f); assertEquals(wild, sized.size.keyframes[0].curve)
        assertEquals(wild, TextEdits.scalar(l, 500_000, AnimPropertyType.FONT_SIZE, 71f).size.keyframes[0].curve)
        // Changing the type through the old rail menu resets the parameters to that type's defaults.
        v.dispatch(EditorAction.SetKeyframe(1, AnimPropertyType.ROTATION, 500_000, 1f, EasingPreset.LINEAR))
        assertEquals(EasingCurve.LINEAR, v.state.project.composition.layers[0].animTransform.rotation.keyframes[0].curve)
    }

    @Test fun explodeCarriesTransformAndPaintCurves() = runBlocking {
        val source = keyed().copy(text = keyed().text.copy(animators = FrozenList.empty()))
        val plan = TextExplode.prepare(source, Composition(), 1_000_000, 2, TextExplodeOptions()) { text ->
            val d = text.source.sourceDocument; val edges = d.boundaries; val n = edges.size - 1
            TextPreparedGeometry(d, edges.dropLast(1).toIntArray(), edges.drop(1).toIntArray(), IntArray(n), IntArray(n),
                FloatArray(n * 4) { i -> when (i % 4) { 0 -> i / 4 * 12f; 1 -> -10f; 2 -> i / 4 * 12f + 12f; else -> 4f } },
                FloatArray(n * 2) { if (it % 2 == 0) it / 2 * 12f else 0f }, floatArrayOf(0f, -10f, n * 12f, 14f))
        }
        for (piece in plan.pieces) {
            assertEquals(wild, piece.animTransform.position.keyframes[0].curve); assertEquals(wild, piece.text.fill.keyframes[0].curve)
        }
    }

    @Test fun trackMemoSharesEqualCurvesAndSeparatesDifferentOnes() {
        val memo = TrackMemo()
        val a = memo.scalar(track1D(wild)); val b = memo.scalar(track1D(wild)); val c = memo.scalar(track1D(backOut))
        assertSame(a, b); assertNotSame(a, c); assertEquals(2, memo.slots)
        assertEquals(track1D(wild).evaluate(400_000), a.evaluate(400_000), 0f)
        assertNotEquals(a.evaluate(400_000), c.evaluate(400_000))
    }

    // ---- SetKeyEasing -------------------------------------------------------------------------------------------
    private fun threeKeys() = CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(0, 0, 4_000_000),
        animTransform = AnimatableTransform(scale = AnimatableProperty2D(true, 1f, 1f, FrozenList.of(listOf(Keyframe2D(0, 1f, 1f), Keyframe2D(1_000_000, 2f, 3f), Keyframe2D(2_000_000, 1f, 1f)))),
            opacity = AnimatableProperty1D(true, 1f, FrozenList.of(listOf(Keyframe1D(-500_000, 0f), Keyframe1D(1_000_000, 1f), Keyframe1D(9_000_000, .5f))))))

    @Test fun setKeyEasingIsOneUndoEntryWithRedo() {
        val v = vm(threeKeys())
        v.dispatch(EditorAction.SetKeyEasing(1, AnimPropertyType.SCALE, keyTimes = listOf(1_000_000), curve = backOut))
        val s = v.state.project.composition.layers[0].animTransform.scale.keyframes
        assertEquals(listOf(EasingCurve.EASY_EASE, backOut, EasingCurve.EASY_EASE), s.map { it.curve })
        // Position/Scale: one curve for both channels.
        val mid = v.state.project.composition.layers[0].animTransform.scale.evaluatePair(1_500_000)
        assertEquals(2f - backOut.ease(.5f), mid.first, 1e-5f); assertEquals(3f - 2f * backOut.ease(.5f), mid.second, 1e-5f)
        v.undo(); assertEquals(EasingCurve.EASY_EASE, v.state.project.composition.layers[0].animTransform.scale.keyframes[1].curve)
        assertFalse(v.canUndo)
        v.redo(); assertEquals(backOut, v.state.project.composition.layers[0].animTransform.scale.keyframes[1].curve)
    }

    @Test fun gestureBeginPreviewCommitIsOneUndoAndCancelDiscards() {
        val v = vm(threeKeys())
        v.beginGesture()
        for (y in listOf(.5f, 1f, 1.5f)) v.previewGesture(EditorAction.SetKeyEasing(1, AnimPropertyType.OPACITY, keyTimes = listOf(1_000_000), curve = EasingCurve.bezier(.3f, y, .7f, 1f)))
        assertEquals(EasingCurve.bezier(.3f, 1.5f, .7f, 1f), v.displayedState.project.composition.layers[0].animTransform.opacity.keyframes[1].curve)
        assertEquals(EasingCurve.EASY_EASE, v.state.project.composition.layers[0].animTransform.opacity.keyframes[1].curve)
        v.commitGesture()
        assertEquals(EasingCurve.bezier(.3f, 1.5f, .7f, 1f), v.state.project.composition.layers[0].animTransform.opacity.keyframes[1].curve)
        v.undo(); assertFalse(v.canUndo)
        v.beginGesture(); v.previewGesture(EditorAction.SetKeyEasing(1, AnimPropertyType.OPACITY, keyTimes = listOf(1_000_000), curve = wild)); v.cancelGesture()
        assertEquals(EasingCurve.EASY_EASE, v.state.project.composition.layers[0].animTransform.opacity.keyframes[1].curve); assertFalse(v.canUndo)
    }

    @Test fun applyToAllWritesEverySegmentAndKeepsHiddenKeysStored() {
        val v = vm(threeKeys())
        v.dispatch(EditorAction.SetKeyEasing(1, AnimPropertyType.OPACITY, curve = EasingCurve.HOLD))
        val k = v.state.project.composition.layers[0].animTransform.opacity.keyframes
        // The key before the clip (-0.5 s) and after it (9 s) stay stored; the last key's easing is unused and kept.
        assertEquals(listOf(-500_000L, 1_000_000L, 9_000_000L), k.map { it.timeUs })
        assertEquals(listOf(EasingCurve.HOLD, EasingCurve.HOLD, EasingCurve.EASY_EASE), k.map { it.curve })
        // Missing times are ignored.
        v.dispatch(EditorAction.SetKeyEasing(1, AnimPropertyType.OPACITY, keyTimes = listOf(12345), curve = wild))
        assertTrue(v.canUndo)
    }

    @Test fun everyPropertyAddressIsReachable() {
        val v = vm()
        val c = EasingCurve.elastic(2f, .3f)
        val l0 = v.state.project.composition.layers[0]
        val targets = listOf(GraphTarget(AnimPropertyType.POSITION), GraphTarget(AnimPropertyType.ROTATION), GraphTarget(AnimPropertyType.FONT_SIZE),
            GraphTarget(AnimPropertyType.FILL_COLOUR), GraphTarget(animator = TextAnimatorEdits.selectorField(5, 6, SelectorField.START)),
            GraphTarget(animator = TextAnimatorEdits.property(5, AnimatorProperty.POSITION)))
        for (t in targets) {
            assertTrue(t.toString(), GraphModel.enabled(l0, t))
            v.dispatch(GraphModel.action(1, t, listOf(0L), c))
            assertEquals(t.toString(), c, GraphModel.curveAt(v.state.project.composition.layers[0], t, 0))
        }
        assertEquals(List(6) { c }, curves(v.state.project.composition.layers[0]))
        // Every text-layer scalar, stroke colour, the remaining transform channels and animator scalar / colour / enabled / grouping.
        val text = l0.text.copy(tracking = track1D(wild), leading = track1D(wild), baselineShift = track1D(wild), strokeWidth = track1D(wild, 0f, 5f),
            strokeColour = AnimatableColour(true, -1, FrozenList.of(listOf(ColourKeyframe(0, -1), ColourKeyframe(100_000, 0)))),
            animators = FrozenList.of(listOf(l0.text.animators[0].copy(enabled = AnimatableProperty1D(true, 1f, FrozenList.of(listOf(Keyframe1D(0, 1f), Keyframe1D(100_000, 0f)))),
                properties = FrozenList.of(l0.text.animators[0].properties + listOf(TextPropertyOverride(AnimatorProperty.OPACITY, scalar = track1D(wild)),
                    TextPropertyOverride(AnimatorProperty.FILL, colour = AnimatableColour(true, -1, FrozenList.of(listOf(ColourKeyframe(0, -1), ColourKeyframe(100_000, 0)))))))))))
        val v2 = vm(l0.copy(text = text, animTransform = l0.animTransform.copy(scale = AnimatableProperty2D(true, 1f, 1f, FrozenList.of(listOf(Keyframe2D(0, 1f, 1f), Keyframe2D(100_000, 2f, 2f)))),
            opacity = track1D(EasingCurve.LINEAR, 0f, 1f))))
        val more = listOf(AnimPropertyType.SCALE, AnimPropertyType.OPACITY, AnimPropertyType.TRACKING, AnimPropertyType.LEADING, AnimPropertyType.BASELINE_SHIFT,
            AnimPropertyType.STROKE_WIDTH, AnimPropertyType.STROKE_COLOUR).map { GraphTarget(it) } +
            listOf(TextAnimatorEdits.property(5, AnimatorProperty.OPACITY), TextAnimatorEdits.property(5, AnimatorProperty.FILL), TextAnimatorEdits.animatorEnabled(5))
                .map { GraphTarget(animator = it) }
        for (t in more) {
            v2.dispatch(GraphModel.action(1, t, null, c))
            assertEquals(t.toString(), c, GraphModel.curveAt(v2.state.project.composition.layers[0], t, 0))
        }
        assertThrows(IllegalArgumentException::class.java) { GraphTarget(AnimPropertyType.SCALE, TextAnimatorEdits.animatorEnabled(5)) }
    }
}
