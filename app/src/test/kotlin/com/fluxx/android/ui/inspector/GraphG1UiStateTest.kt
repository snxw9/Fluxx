package com.fluxx.android.ui.inspector

import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test

/** GRAPH G1 pure functions and UI state: segment rule, clipboard text, presets, handle maths, rail icon rules, layout. */
class GraphG1UiStateTest {
    // ---- Segment from the playhead ----------------------------------------------------------------------------------
    @Test fun editedSegmentFollowsThePlayhead() {
        val keys = listOf(100L, 200L, 300L, 400L)
        assertNull(GraphModel.segmentIndex(emptyList(), 0)); assertNull(GraphModel.segmentIndex(listOf(5L), 5))
        assertEquals(0, GraphModel.segmentIndex(keys, 0))        // before the first key: the first segment
        assertEquals(0, GraphModel.segmentIndex(keys, 100))      // on key 1: outgoing 1 → 2
        assertEquals(0, GraphModel.segmentIndex(keys, 150))      // strictly between
        assertEquals(1, GraphModel.segmentIndex(keys, 200))      // on key 2: 2 → 3
        assertEquals(2, GraphModel.segmentIndex(keys, 399))
        assertEquals(2, GraphModel.segmentIndex(keys, 400))      // on the last key: the previous segment
        assertEquals(2, GraphModel.segmentIndex(keys, 10_000))   // after the last key: the last segment
        assertEquals(0, GraphModel.segmentIndex(listOf(-50L, 50L), 50))
    }

    @Test fun segmentLabelProgressAndValues() {
        val layer = CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(0, 0, 4_000_000), animTransform = AnimatableTransform(
            rotation = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(Keyframe1D(0, 0f), Keyframe1D(1_000_000, 90f).withCurve(EasingCurve.HOLD), Keyframe1D(3_000_000, 45f))))))
        val target = GraphTarget(AnimPropertyType.ROTATION)
        val s = GraphModel.segment(layer, target, 2_000_000)!!
        assertEquals("Key 2 to 3", s.label); assertEquals(EasingCurve.HOLD, s.curve); assertEquals(2_000_000L, s.durationUs)
        assertEquals(.5f, GraphModel.progress(s, 2_000_000)!!, 0f); assertNull(GraphModel.progress(s, 500_000))
        assertEquals("90°" to "45°", GraphModel.values(layer, target, s))
    }

    // ---- cubic-bezier() text ----------------------------------------------------------------------------------------
    @Test fun cubicBezierParseAndFormat() {
        val c = EasingCurve.bezier(.42f, 0f, .58f, 1f)
        assertEquals("cubic-bezier(0.42, 0, 0.58, 1)", CubicBezierText.format(c))
        assertEquals("cubic-bezier(0, 0, 1, 1)", CubicBezierText.format(EasingCurve.LINEAR))
        assertEquals("cubic-bezier(0.33, 0, 0.67, 1)", CubicBezierText.format(EasingCurve.EASY_EASE))
        assertNull(CubicBezierText.format(EasingCurve.HOLD)); assertNull(CubicBezierText.format(EasingCurve.bounce(3f, .5f)))
        for (text in listOf("cubic-bezier(0.42, 0, 0.58, 1)", "CUBIC-BEZIER( .42 ,0, .58 , 1 );", ".42, 0, .58, 1", ".42 0 .58 1", "  0.42,0,0.58,1  "))
            assertEquals(text, c, CubicBezierText.parse(text))
        assertEquals(EasingCurve.bezier(.68f, -.6f, .32f, 1.6f), CubicBezierText.parse("cubic-bezier(0.68, -0.6, 0.32, 1.6)"))
        // y is clamped to [-2, 3]; x outside [0, 1] is rejected; anything malformed is a silent null.
        assertEquals(EasingCurve.bezier(.1f, -2f, .9f, 3f), CubicBezierText.parse("0.1, -9, 0.9, 12"))
        for (bad in listOf(null, "", "   ", "1.2, 0, .5, 1", "-0.1, 0, .5, 1", "cubic-bezier(1,2,3)", "1,2,3,4,5", "ease-in", "cubic-bezier(a,b,c,d)",
            "0.5, NaN, 0.5, 1", "0.5, 1e99, 0.5, 1", "x".repeat(300), "steps(4)", "1..2, 0, 0, 1"))
            assertNull(bad, CubicBezierText.parse(bad))
        // Format and parse round-trip.
        for (p in EasePresets.ALL) assertTrue(EaseMath.same(p.curve, CubicBezierText.parse(CubicBezierText.format(p.curve))!!))
    }

    // ---- Presets ----------------------------------------------------------------------------------------------------
    @Test fun everyPresetIsValidAndNamedOnce() {
        assertTrue(EasePresets.ALL.size >= 24)
        assertEquals(EasePresets.ALL.size, EasePresets.ALL.map { it.name }.toSet().size)
        assertEquals(EasePresets.ALL.size, EasePresets.ALL.map { it.id }.toSet().size)
        for (p in EasePresets.ALL) {
            assertTrue(p.name, listOf(p.x1, p.y1, p.x2, p.y2).all { it.isFinite() })
            assertTrue(p.name, p.x1 in 0f..1f && p.x2 in 0f..1f && p.y1 in -2f..3f && p.y2 in -2f..3f)
            assertEquals(p, EasePresets.match(p.curve))
        }
        assertEquals("css.ease", EasePresets.match(EasingCurve.bezier(.252f, .1f, .25f, .996f))!!.id)
        assertNull(EasePresets.match(EasingCurve.bezier(.26f, .1f, .25f, 1f)))
        assertNull(EasePresets.match(EasingCurve.EASY_EASE)) // presets are CUSTOM curves
        // Back presets overshoot/undershoot; the others stay monotone.
        assertTrue((0..100).any { EasePresets.ALL.first { p -> p.id == "back.out" }.curve.ease(it / 100f) > 1f })
    }

    // ---- Type chooser, invert, mirror, snap -------------------------------------------------------------------------
    @Test fun typeChooserStartsFromTheClosestBezier() {
        assertEquals(EaseChoice.EASE_IN, EaseChoice.of(EasingCurve.bezier(.42f, 0f, 1f, 1f)))
        assertEquals(EaseChoice.EASE_OUT, EaseChoice.of(EasingCurve.bezier(0f, 0f, .58f, 1f)))
        assertEquals(EaseChoice.CUSTOM, EaseChoice.of(EasingCurve.bezier(.1f, 0f, 1f, 1f)))
        assertEquals(EaseChoice.BOUNCE, EaseChoice.of(EasingCurve.bounce(2f, .4f)))
        assertEquals(EasingCurve.bezier(0f, 0f, 1f, 1f), EaseChoice.curve(EaseChoice.CUSTOM, EasingCurve.LINEAR))
        assertEquals(EasingCurve.bezier(.33f, 0f, .67f, 1f), EaseChoice.curve(EaseChoice.CUSTOM, EasingCurve.EASY_EASE))
        for (from in listOf(EasingCurve.HOLD, EasingCurve.bounce(2f, .4f), EasingCurve.elastic(2f, .4f)))
            assertEquals(EaseChoice.CUSTOM_DEFAULT, EaseChoice.curve(EaseChoice.CUSTOM, from))
        val custom = EasingCurve.bezier(.2f, 1.4f, .3f, .9f)
        assertEquals(custom, EaseChoice.curve(EaseChoice.CUSTOM, custom))
        assertEquals(EasingCurve.bounce(3f, .5f), EaseChoice.curve(EaseChoice.BOUNCE, custom))
        assertEquals(EasingCurve.bounce(2f, .4f), EaseChoice.curve(EaseChoice.BOUNCE, EasingCurve.bounce(2f, .4f)))
        assertEquals(EasingCurve.elastic(3f, .5f), EaseChoice.curve(EaseChoice.ELASTIC, custom))
        for (c in EaseChoice.entries) assertEquals(c, EaseChoice.of(EaseChoice.curve(c, EasingCurve.bezier(.12f, .3f, .9f, .7f))))
    }

    @Test fun invertMirrorAndSnapMaths() {
        assertTrue(EaseMath.same(EasingCurve.bezier(0f, 0f, .58f, 1f), EaseMath.invert(EasingCurve.bezier(.42f, 0f, 1f, 1f))!!))
        val c = EasingCurve.bezier(.1f, -.5f, .7f, 1.4f)
        val twice = EaseMath.invert(EaseMath.invert(c)!!)!!
        assertTrue(EaseMath.same(c, twice))
        assertNull(EaseMath.invert(EasingCurve.HOLD)); assertNull(EaseMath.invert(EasingCurve.LINEAR)); assertNull(EaseMath.invert(EasingCurve.elastic(2f, .3f)))
        assertNotNull(EaseMath.invert(EasingCurve.EASY_EASE))
        assertEquals(.75f, EaseMath.mirror(.25f, 1.25f).first, 1e-6f); assertEquals(-.25f, EaseMath.mirror(.25f, 1.25f).second, 1e-6f)
        assertEquals(.35f, EaseMath.snap(.34f), 1e-6f); assertEquals(-.05f, EaseMath.snap(-.06f), 1e-6f)
        val base = floatArrayOf(.33f, 0f, .67f, 1f)
        assertEquals(EasingCurve.bezier(1f, 3f, .67f, 1f), EaseMath.moveHandle(base, 0, 4f, 9f, snap = false, mirror = false))
        assertTrue(EaseMath.same(EasingCurve.bezier(.3f, .2f, .7f, .8f), EaseMath.moveHandle(base, 0, .31f, .21f, snap = true, mirror = true)))
        val moved = EaseMath.moveHandle(base, 1, .9f, -.4f, snap = false, mirror = true)
        assertEquals(.1f, moved.a, 1e-6f); assertEquals(1.4f, moved.b, 1e-6f)
    }

    // ---- Rail icon enable rules, numeric rows -----------------------------------------------------------------------
    @Test fun railGraphIconEnablesForContinuousPropertiesWithTwoKeys() {
        fun layer(keys: Int) = CompositionLayer(1, LayerType.TEXT, timing = ClipTiming(0, 0, 4_000_000), animTransform = AnimatableTransform(
            rotation = AnimatableProperty1D(keys > 0, 0f, FrozenList.of((0 until keys).map { Keyframe1D(it * 1000L, it.toFloat()) }))),
            text = TextProperties(source = AnimatableString(true, "a", FrozenList.of(listOf(StringKeyframe(0, "a"), StringKeyframe(1000, "b")))),
                strokeOverFill = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(Keyframe1D(0, 0f), Keyframe1D(1000, 1f)))),
                animators = FrozenList.of(listOf(TextAnimator(9, selectors = FrozenList.of(listOf(TextRangeSelector(8,
                    shape = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(Keyframe1D(0, 0f), Keyframe1D(1000, 2f)))),
                    start = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(Keyframe1D(0, 0f), Keyframe1D(1000, 50f)))),
                    seedKeyframes = FrozenList.of(listOf(TextSeedKeyframe(0, 1), TextSeedKeyframe(1000, 3)))))))))))
        val rotation = GraphTarget(AnimPropertyType.ROTATION)
        assertFalse(GraphModel.enabled(layer(0), rotation)); assertFalse(GraphModel.enabled(layer(1), rotation))
        assertTrue(GraphModel.enabled(layer(2), rotation)); assertFalse(GraphModel.enabled(layer(2), null))
        // Discrete tracks hold: Source Text, stroke-over-fill, ligatures, hold selector fields and the seed stay disabled.
        assertFalse(GraphModel.enabled(layer(2), GraphTarget(AnimPropertyType.SOURCE_TEXT)))
        assertFalse(GraphModel.enabled(layer(2), GraphTarget(AnimPropertyType.STROKE_OVER_FILL)))
        assertFalse(GraphModel.enabled(layer(2), GraphTarget(animator = TextAnimatorEdits.selectorField(9, 8, SelectorField.SHAPE))))
        assertFalse(GraphModel.enabled(layer(2), GraphTarget(animator = TextAnimatorEdits.seed(9, 8))))
        assertTrue(GraphModel.enabled(layer(2), GraphTarget(animator = TextAnimatorEdits.selectorField(9, 8, SelectorField.START))))
        // A vanished animator (undo / delete) falls back safely.
        assertFalse(GraphModel.enabled(layer(2), GraphTarget(animator = TextAnimatorEdits.selectorField(77, 8, SelectorField.START))))
        assertNull(GraphModel.segment(layer(2), GraphTarget(animator = TextAnimatorEdits.selectorField(77, 8, SelectorField.START)), 0))
        // Text properties on a non-text layer are never graphable.
        assertFalse(GraphModel.enabled(layer(2).copy(type = LayerType.SOLID), GraphTarget(animator = TextAnimatorEdits.selectorField(9, 8, SelectorField.START))))
    }

    @Test fun railDiamondOnTheGraphPageUsesTheSameKeyModel() {
        val layer = CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(0, 0, 4_000_000), animTransform = AnimatableTransform(
            rotation = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(Keyframe1D(0, 0f), Keyframe1D(1_000_000, 90f))))))
        val t = GraphTarget(AnimPropertyType.ROTATION)
        assertEquals(EditorAction.RemoveKeyframe(1, AnimPropertyType.ROTATION, 0), GraphModel.toggleKey(layer, t, 0, FrameRate()))
        assertTrue(GraphModel.toggleKey(layer, t, 500_000, FrameRate()) is EditorAction.SetKeyframe)
        assertEquals(EditorAction.ToggleAnimated(1, AnimPropertyType.OPACITY, true, 0), GraphModel.toggleKey(layer, GraphTarget(AnimPropertyType.OPACITY), 0, FrameRate()))
    }

    @Test fun valueRowsMatchTheType() {
        assertEquals(listOf("x1", "y1", "x2", "y2"), EaseLimits.rows(EasingCurve.bezier(.1f, .2f, .3f, .4f)).map { it.first.name })
        assertEquals(EasingCurve.bezier(.1f, 2.5f, .3f, .4f), EaseLimits.rows(EasingCurve.bezier(.1f, .2f, .3f, .4f))[1].third(2.5f))
        // Editing a Linear/Easy Ease value makes it Custom; Hold has no rows.
        assertEquals(EasingPreset.CUSTOM, EaseLimits.rows(EasingCurve.LINEAR)[0].third(.2f).type)
        assertTrue(EaseLimits.rows(EasingCurve.HOLD).isEmpty())
        assertEquals(EasingCurve.bounce(8f, .5f), EaseLimits.rows(EasingCurve.bounce(3f, .5f))[0].third(12f))
        assertEquals(EasingCurve.elastic(3f, .1f), EaseLimits.rows(EasingCurve.elastic(3f, .5f))[1].third(0f))
    }

    @Test fun easeViewYRangeGrowsForOvershootAndSamplingIsExact() {
        assertEquals(EasePlot.DEFAULT_LOW to EasePlot.DEFAULT_HIGH, EasePlot.range(EasingCurve.EASY_EASE))
        val wild = EasingCurve.bezier(.1f, -2f, .9f, 3f)
        val (lo, hi) = EasePlot.range(wild)
        assertTrue(lo <= -2.1f + 1e-4f && hi >= 3.1f - 1e-4f)
        val plot = EasePlot(200f, 100f, 10f, -.15f, 1.15f)
        assertEquals(10f, plot.x(0f), 0f); assertEquals(190f, plot.x(1f), 1e-4f)
        assertEquals(.5f, plot.valueY(plot.y(.5f)), 1e-5f); assertEquals(.3f, plot.valueX(plot.x(.3f)), 1e-5f)
        // Drawn samples lie on the evaluated curve.
        for (c in listOf(EasingCurve.bounce(6f, .7f), EasingCurve.elastic(10f, .1f))) {
            val pts = EaseSampling.points(c)
            var i = 0; while (i < pts.size) { assertEquals(c.ease(pts[i]), pts[i + 1], 1e-6f); i += 2 }
            assertEquals(1f, pts[pts.size - 2], 0f); assertEquals(1f, pts.last(), 0f)
        }
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f), EaseSampling.points(EasingCurve.HOLD), 0f)
    }

    @Test fun inspectorSectionRulesListTheGraphPage() {
        assertTrue(InspectorSwipe.enabled(InspectorSection.GRAPH, textTyping = false))
        assertFalse(InspectorSwipe.enabled(InspectorSection.GRAPH, textTyping = true))
        assertEquals("GRAPH", categoryIconName(InspectorSection.GRAPH, LayerType.TEXT))
        for (name in listOf("GRAPH_VALUE", "GRAPH_SPEED", "GRAPH_EASE", "EASE_HOLD", "EASE_LINEAR", "EASE_EASY", "EASE_IN", "EASE_OUT", "EASE_CUSTOM",
            "EASE_BOUNCE", "EASE_ELASTIC", "EASE_SNAP", "EASE_MIRROR", "EASE_INVERT", "EASE_APPLY_ALL", "GRAPH_TOOLS", "PASTE"))
            assertTrue(name, name in InspectorIcons.PATHS)
        // The rail ⋮ easing menu is unchanged by the larger enum.
        assertEquals(listOf(EasingPreset.EASY_EASE, EasingPreset.LINEAR), RAIL_EASINGS)
    }
}
