package com.fluxx.android.editor

import com.fluxx.android.model.*
import com.fluxx.android.ui.inspector.GraphValues
import com.fluxx.android.ui.inspector.KeyCurvePopover
import com.fluxx.android.ui.timeline.LayerKeyMarkers
import org.junit.Assert.*
import org.junit.Test

/** TL3.1: B7 add-key at the playhead (motion kept), B3 hold keys, B5 popover layout. */
class Tl31Test {
    private val rate = FrameRate(30, 1)
    private fun f(n: Long) = rate.frameTimeUs(n)
    private val rot = GraphTarget(AnimPropertyType.ROTATION)
    private val pos = GraphTarget(AnimPropertyType.POSITION)

    private fun layer(curve: EasingCurve) = CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(0, 0, f(120)), animTransform = AnimatableTransform(
        position = AnimatableProperty2D(true, keyframes = FrozenList.of(listOf(Keyframe2D(f(10), 0f, 1f).withCurve(curve), Keyframe2D(f(70), 300f, -50f)))),
        rotation = AnimatableProperty1D(true, keyframes = FrozenList.of(listOf(Keyframe1D(f(10), 0f).withCurve(curve), Keyframe1D(f(70), 90f))))))
    private fun project(l: CompositionLayer) = ProjectDocument(Composition(durationUs = f(200), frameRate = rate, layers = FrozenList.of(listOf(l))))
    private fun apply(p: ProjectDocument, a: EditorAction) = EditorReducer.reduce(EditorState(p), a).project.composition.layers.single()

    /** The largest difference of [target]'s value(s) before / after over the whole layer, sampled every ~1ms. */
    private fun maxDiff(before: CompositionLayer, after: CompositionLayer, target: GraphTarget): Float {
        val a = GraphValues.sampler(before, target)!!; val b = GraphValues.sampler(after, target)!!
        val x = FloatArray(2); val y = FloatArray(2); var worst = 0f; var t = 0L
        while (t <= f(120)) { a(t, x); b(t, y); worst = maxOf(worst, kotlin.math.abs(x[0] - y[0]), kotlin.math.abs(x[1] - y[1])); t += 997 }
        return worst
    }

    @Test fun addingAKeyAtThePlayheadKeepsTheMotionForEveryEasing() {
        for (curve in listOf(EasingCurve.LINEAR, EasingCurve.EASY_EASE, EasingCurve.bezier(.2f, .1f, .4f, .95f), EasingCurve.HOLD)) {
            val l = layer(curve); val p = project(l)
            for (target in listOf(rot, pos)) {
                assertEquals(KeyAdd.Why.OK, KeyAdd.check(p, l, target, f(31)))
                val after = apply(p, KeyAdd.action(p, l, target, f(31))!!)
                assertEquals(3, TwirlRows.keyTimes(after, target).size)
                assertTrue(f(31) in TwirlRows.keyTimes(after, target))
                val d = maxDiff(l, after, target)
                println("TL3.1 B7 ${curve.type} $target: max difference $d")
                // Floating-point level (Easy Ease is evaluated from its LUT, the halves exactly: measured 2.4e-4).
                assertTrue("${curve.type} $target: $d", d <= 1e-3f)
            }
        }
    }

    @Test fun beforeTheFirstAndAfterTheLastKeyTheHeldValueIsAdded() {
        val l = layer(EasingCurve.EASY_EASE); val p = project(l)
        for (t in listOf(f(3), f(100))) {
            val after = apply(p, KeyAdd.action(p, l, rot, t)!!)
            assertEquals(3, after.animTransform.rotation.keyframes.size)
            assertEquals(0f, maxDiff(l, after, rot), 0f)
        }
    }

    @Test fun disabledOnAKeyOutsideTheClipAndOnBounceOrElastic() {
        val l = layer(EasingCurve.LINEAR); val p = project(l)
        assertEquals(KeyAdd.Why.ON_KEY, KeyAdd.check(p, l, rot, f(10)))
        assertEquals(KeyAdd.Why.OUTSIDE_CLIP, KeyAdd.check(p, l, rot, f(150)))
        assertNull(KeyAdd.action(p, l, rot, f(150)))
        val bounce = layer(EasingCurve.bounce(EasingLimits.BOUNCE_DEFAULT_COUNT, EasingLimits.BOUNCE_DEFAULT_RESTITUTION))
        assertEquals(KeyAdd.Why.BOUNCE_ELASTIC, KeyAdd.check(project(bounce), bounce, rot, f(31)))
        // After the Bounce segment's last key it is fine (no segment to split).
        assertEquals(KeyAdd.Why.OK, KeyAdd.check(project(bounce), bounce, rot, f(90)))
    }

    @Test fun holdOnlyFieldsAddAHoldKeyAndHoldKeysAreSquares() {
        val ligatures = AnimatableProperty1D(true, keyframes = FrozenList.of(listOf(Keyframe1D(f(0), 1f, EasingPreset.HOLD), Keyframe1D(f(30), 0f, EasingPreset.HOLD))))
        val text = CompositionLayer(2, LayerType.TEXT, timing = ClipTiming(0, 0, f(120)), text = TextProperties(ligatures = ligatures))
        val lig = GraphTarget(AnimPropertyType.LIGATURES)
        val p = project(text)
        val after = apply(p, KeyAdd.action(p, text, lig, f(20))!!)
        val k = after.text.ligatures.keyframes.first { it.timeUs == f(20) }
        assertEquals(1f, k.value); assertEquals(EasingPreset.HOLD, k.easing)
        // B3: hold-only tracks and Hold keys draw as squares; others as diamonds.
        assertTrue(LayerKeyMarkers.holdAt(text, lig, f(0)))
        val l = layer(EasingCurve.HOLD)
        assertTrue(LayerKeyMarkers.holdAt(l, rot, f(10))); assertFalse(LayerKeyMarkers.holdAt(layer(EasingCurve.LINEAR), rot, f(10)))
        // The bar's aggregated marker is a square only when EVERY key at that time is a hold key.
        assertEquals(setOf(f(10)), LayerKeyMarkers.holdClipTimes(l, 0))
        assertEquals(emptySet<Long>(), LayerKeyMarkers.holdClipTimes(layer(EasingCurve.LINEAR), 0))
    }

    @Test fun theCurvesPopoverIsFourColumnsAboutFourRowsTall() {
        assertEquals(4, KeyCurvePopover.COLUMNS); assertEquals(4, KeyCurvePopover.VISIBLE_ROWS)
        assertEquals(174f, KeyCurvePopover.WIDTH, 1e-3f)
        assertEquals("about 4 compact rows", 124f, KeyCurvePopover.maxHeight(1f), .01f)
    }
}
