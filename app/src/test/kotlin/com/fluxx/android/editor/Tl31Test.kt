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

    @Test fun edgeScrollRampsWithDepthAndTheDragFollowsInContentCoordinates() {
        val w = 1000f; val zone = 100f; val max = 20f
        assertEquals(0f, EdgeScroll.speed(500f, w, zone, max), 0f)            // the middle: no scroll
        assertEquals(0f, EdgeScroll.speed(100f, w, zone, max), 0f)            // the zone's inner edge
        assertEquals(-10f, EdgeScroll.speed(50f, w, zone, max), 1e-4f)        // halfway in: half speed, toward 0
        assertEquals(-20f, EdgeScroll.speed(0f, w, zone, max), 1e-4f)         // the very edge: full speed
        assertEquals(-20f, EdgeScroll.speed(-30f, w, zone, max), 1e-4f)       // past the edge: still full
        assertEquals(15f, EdgeScroll.speed(975f, w, zone, max), 1e-4f)        // the right edge scrolls forward
        // A still finger (screen move 30px) while the content scrolled 120px further: the key is 150px along the content.
        assertEquals(150f, EdgeScroll.contentDx(30f, scrollAtDown = 400.0, scrollNow = 520.0), 0f)
        assertEquals(-90f, EdgeScroll.contentDx(-30f, 400.0, 340.0), 0f)
        // The earliest-key >= 0 clamp still holds when the content scroll carries the drag far left.
        val l = layer(EasingCurve.LINEAR); val p = project(l)
        val sel = KeySelectionSet.of(KeyframeSelection(1, rot, listOf(f(10), f(70))))!!
        val dxUs = (EdgeScroll.contentDx(-30f, 400.0, 0.0) / 4f * f(1)).toLong() // 4px per frame
        assertEquals(listOf(0L, f(60)), MultiKeyEdits.moveTogether(p, sel, dxUs)!!.selection!!.tracks.single().times)
    }

    @Test fun aNameLongPressOpensTheExactPlace() {
        // Transform properties open the Transform page in their own mode; Opacity its page; text properties Edit Text.
        assertEquals(com.fluxx.android.ui.inspector.InspectorSection.TRANSFORM, com.fluxx.android.ui.inspector.InspectorOpenPage.section(GraphTarget(AnimPropertyType.SCALE)))
        assertEquals(com.fluxx.android.ui.inspector.TransformSubMode.SCALE, com.fluxx.android.ui.inspector.InspectorOpenPage.mode(GraphTarget(AnimPropertyType.SCALE)))
        assertEquals(com.fluxx.android.ui.inspector.InspectorSection.BLENDING_OPACITY, com.fluxx.android.ui.inspector.InspectorOpenPage.section(GraphTarget(AnimPropertyType.OPACITY)))
        assertEquals(com.fluxx.android.ui.inspector.InspectorSection.CONTEXTUAL_EDIT, com.fluxx.android.ui.inspector.InspectorOpenPage.section(GraphTarget(AnimPropertyType.TRACKING)))
        // Inside Edit Text: the property's own control, on its tab.
        val c = com.fluxx.android.ui.inspector.TextOpenTarget.control(AnimPropertyType.TRACKING)!!
        assertEquals(com.fluxx.android.ui.inspector.TextControl.TRACKING, c)
        assertEquals(com.fluxx.android.ui.inspector.TextTab.CHARACTER, com.fluxx.android.ui.inspector.TextOpenTarget.tab(c))
        for (p in listOf(AnimPropertyType.FONT_SIZE, AnimPropertyType.FILL_COLOUR, AnimPropertyType.STROKE_COLOUR, AnimPropertyType.STROKE_WIDTH,
            AnimPropertyType.LEADING, AnimPropertyType.BASELINE_SHIFT, AnimPropertyType.STROKE_OVER_FILL, AnimPropertyType.LIGATURES, AnimPropertyType.SOURCE_TEXT))
            assertNotNull("$p", com.fluxx.android.ui.inspector.TextOpenTarget.control(p))
        assertEquals(com.fluxx.android.ui.inspector.TextTab.PARAGRAPH, com.fluxx.android.ui.inspector.TextOpenTarget.tab(com.fluxx.android.ui.inspector.TextControl.INDENT_LEFT))
    }
}
