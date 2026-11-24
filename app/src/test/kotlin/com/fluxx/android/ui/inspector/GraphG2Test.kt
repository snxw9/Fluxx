package com.fluxx.android.ui.inspector

import androidx.compose.ui.geometry.Rect
import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test

/** GRAPH G2 (Value graph + G2L carry-over): pure model, viewport, selection, key edits and the library grid. */
class GraphG2Test {
    private val rate = FrameRate(30, 1)
    private val wild = EasingCurve.bezier(.2f, 1.6f, .7f, -.3f)
    private val rotation = GraphTarget(AnimPropertyType.ROTATION)
    private fun layer(curve: EasingCurve = wild) = CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(0, 0, 4_000_000),
        animTransform = AnimatableTransform(
            rotation = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(Keyframe1D(0, 0f).withCurve(curve), Keyframe1D(1_000_000, 90f), Keyframe1D(3_000_000, 45f)))),
            position = AnimatableProperty2D(true, keyframes = FrozenList.of(listOf(Keyframe2D(0, 10f, 20f).withCurve(curve), Keyframe2D(2_000_000, 110f, 220f))))))
    private fun text(c: EasingCurve = wild) = CompositionLayer(1, LayerType.TEXT, timing = ClipTiming(0, 0, 4_000_000),
        text = TextProperties(source = AnimatableString(staticValue = "AB"), size = AnimatableProperty1D(true, 50f, FrozenList.of(listOf(Keyframe1D(0, 50f).withCurve(c), Keyframe1D(3_000_000, 90f)))),
            fill = AnimatableColour(true, -1, FrozenList.of(listOf(ColourKeyframe(0, -1).withCurve(c), ColourKeyframe(3_000_000, 0xff000000.toInt())))),
            animators = FrozenList.of(listOf(TextAnimator(5, selectors = FrozenList.of(listOf(TextRangeSelector(6, offset = AnimatableProperty1D(true, 0f,
                FrozenList.of(listOf(Keyframe1D(0, 0f).withCurve(c), Keyframe1D(3_000_000, 100f))))))),
                properties = FrozenList.of(listOf(TextPropertyOverride(AnimatorProperty.POSITION, vector = AnimatableProperty2D(true, 0f, 0f,
                    FrozenList.of(listOf(Keyframe2D(0, 0f, 0f).withCurve(c), Keyframe2D(3_000_000, 10f, 10f))))),
                    TextPropertyOverride(AnimatorProperty.FILL, colour = AnimatableColour(true, -1, FrozenList.of(listOf(ColourKeyframe(0, -1), ColourKeyframe(100_000, 0))))))))))))
    private fun vm(l: CompositionLayer) = EditorViewModel().also {
        it.dispatch(EditorAction.Load(ProjectDocument(com.fluxx.android.model.Composition(durationUs = 4_000_000, layers = FrozenList.of(listOf(l))))))
        it.dispatch(EditorAction.Select(1)); it.dispatch(EditorAction.Seek(1_234_567))
    }

    @Test fun channelsAndEligibilityExcludeColour() {
        val l = layer(); val t = text()
        assertEquals(1, GraphValues.channels(l, rotation)); assertEquals(2, GraphValues.channels(l, GraphTarget(AnimPropertyType.POSITION)))
        assertTrue(GraphValues.available(l, rotation))
        assertEquals(0, GraphValues.channels(t, GraphTarget(AnimPropertyType.FILL_COLOUR)))
        assertFalse(GraphValues.available(t, GraphTarget(AnimPropertyType.FILL_COLOUR)))
        assertTrue(GraphModel.enabled(t, GraphTarget(AnimPropertyType.FILL_COLOUR))) // Ease still works for colour
        assertEquals(1, GraphValues.channels(t, GraphTarget(AnimPropertyType.FONT_SIZE)))
        val offset = GraphTarget(animator = TextAnimatorEdits.selectorField(5, 6, SelectorField.OFFSET))
        assertTrue(GraphValues.available(t, offset)); assertEquals(1, GraphValues.channels(t, offset))
        assertEquals(2, GraphValues.channels(t, GraphTarget(animator = TextAnimatorEdits.property(5, AnimatorProperty.POSITION))))
        assertEquals(0, GraphValues.channels(t, GraphTarget(animator = TextAnimatorEdits.property(5, AnimatorProperty.FILL))))
    }

    @Test fun keysAndSamplerUseTheEvaluator() {
        val l = layer()
        val keys = GraphValues.keys(l, rotation)
        assertEquals(listOf(0L, 1_000_000L, 3_000_000L), keys.map { it.timeUs }); assertEquals(listOf(0f, 90f, 45f), keys.map { it.x })
        assertEquals(GraphModel.keyTimes(l, rotation), keys.map { it.timeUs })
        val out = FloatArray(2); val s = GraphValues.sampler(l, rotation)!!
        for (t in listOf(-500_000L, 0L, 400_000L, 1_000_000L, 2_500_000L, 9_000_000L)) { s(t, out); assertEquals(l.animTransform.rotation.evaluate(t), out[0], 0f) }
        GraphValues.sampler(l, GraphTarget(AnimPropertyType.POSITION))!!(1_000_000L, out)
        assertEquals(l.animTransform.position.evaluatePair(1_000_000L), out[0] to out[1])
        val t = text(); val offset = GraphTarget(animator = TextAnimatorEdits.selectorField(5, 6, SelectorField.OFFSET))
        GraphValues.sampler(t, offset)!!(1_500_000L, out); assertEquals(TextAnimatorEdits.value(t.text, offset.animator!!, 1_500_000L), out[0], 1e-4f)
        assertEquals(GraphModel.keyTimes(t, offset), GraphValues.keys(t, offset).map { it.timeUs })
    }

    @Test fun axesAreDisplayUnitsWithHardLimits() {
        val l = layer()
        val op = GraphValues.axes(l, GraphTarget(AnimPropertyType.OPACITY), 1080, 1920).single()
        assertEquals(100f, op.units.factor); assertEquals(0f, op.min); assertEquals(1f, op.max); assertEquals(1f, op.clamp(3f))
        val sc = GraphValues.axes(l, GraphTarget(AnimPropertyType.SCALE), 1080, 1920)
        assertEquals(2, sc.size); assertEquals(150f, sc[0].toDisplay(1.5f), 1e-4f)
        val pos = GraphValues.axes(l, GraphTarget(AnimPropertyType.POSITION), 1080, 1920)
        // GRAPH G2.1 §5: the ±10× composition pixel caps expressed in stored units (centre origin, half the reference size per unit).
        assertEquals(-21f, pos[0].min, 1e-4f); assertEquals(19f, pos[1].max, 1e-4f)
        assertEquals(36000f, GraphValues.axes(l, rotation, 1080, 1920).single().max)
    }

    @Test fun keyTimeSnapsAndNeverCrossesItsNeighbours() {
        val l = layer(); val keys = GraphModel.keyTimes(l, rotation)
        // Frame snap.
        assertEquals(500_000L, GraphValues.clampTime(l, rate, keys, 1, 501_000L))
        assertEquals(GraphValues.timeOf(l, rate, 15), GraphValues.clampTime(l, rate, keys, 1, 498_000L))
        // Never on or past a neighbour: at least one frame away.
        assertEquals(GraphValues.timeOf(l, rate, 1), GraphValues.clampTime(l, rate, keys, 1, -2_000_000L))
        assertEquals(GraphValues.timeOf(l, rate, 89), GraphValues.clampTime(l, rate, keys, 1, 5_000_000L))
        // The first key stays inside the clip (the timeline's range); the last one too.
        assertEquals(0L, GraphValues.clampTime(l, rate, keys, 0, -400_000L))
        assertEquals(4_000_000L, GraphValues.clampTime(l, rate, keys, 2, 9_000_000L))
    }

    @Test fun keyEditIsOneBatchOfExistingActionsAndKeepsTheCurve() {
        val v = vm(layer())
        val l = v.state.project.composition.layers[0]
        val key = GraphValues.keys(l, rotation)[0]
        val a = GraphValues.keyAction(l, rotation, key, 500_000L, 30f, 0f) as EditorAction.Batch
        assertEquals(listOf(EditorAction.MoveKeyframe::class, EditorAction.SetKeyframe::class), a.actions.map { it::class })
        val before = v.state.playheadUs
        v.dispatch(a)
        val k = v.state.project.composition.layers[0].animTransform.rotation.keyframes[0]
        assertEquals(500_000L, k.timeUs); assertEquals(30f, k.value); assertEquals(wild, k.curve)
        assertEquals(before, v.state.playheadUs)
        v.undo(); assertEquals(layer().animTransform.rotation, v.state.project.composition.layers[0].animTransform.rotation)
        assertNull(GraphValues.keyAction(l, rotation, key, 0L, 0f, 0f))
        // 2-D: one channel changes, the other is kept.
        val pk = GraphValues.keys(l, GraphTarget(AnimPropertyType.POSITION))[0]
        val p = GraphValues.keyAction(l, GraphTarget(AnimPropertyType.POSITION), pk, 0L, 10f, 99f) as EditorAction.Batch
        assertEquals(listOf(EditorAction.SetKeyframe2D(1, AnimPropertyType.POSITION, 0L, 10f, 99f, pk.easing)), p.actions)
        v.dispatch(p); v.state.project.composition.layers[0].animTransform.position.keyframes[0].let { assertEquals(99f, it.y); assertEquals(wild, it.curve) }
    }

    @Test fun keyEditWorksForTextTracksAndAnimatorFieldsWithOneUndo() {
        val v = vm(text())
        val l = v.state.project.composition.layers[0]
        val size = GraphTarget(AnimPropertyType.FONT_SIZE)
        v.dispatch(GraphValues.keyAction(l, size, GraphValues.keys(l, size)[0], 1_000_000L, 64f, 0f)!!)
        v.state.project.composition.layers[0].text.size.keyframes[0].let { assertEquals(1_000_000L, it.timeUs); assertEquals(64f, it.value); assertEquals(wild, it.curve) }
        val offset = GraphTarget(animator = TextAnimatorEdits.selectorField(5, 6, SelectorField.OFFSET))
        val l2 = v.state.project.composition.layers[0]
        v.dispatch(GraphValues.keyAction(l2, offset, GraphValues.keys(l2, offset)[0], 500_000L, -20f, 0f)!!)
        v.state.project.composition.layers[0].text.animators[0].selectors[0].offset.keyframes[0].let {
            assertEquals(500_000L, it.timeUs); assertEquals(-20f, it.value); assertEquals(wild, it.curve) }
        v.undo()
        assertEquals(0L, v.state.project.composition.layers[0].text.animators[0].selectors[0].offset.keyframes[0].timeUs)
        // Preview through the gesture protocol: Batch is transient-allowed; commit is one undo.
        v.beginGesture(); v.previewGesture(GraphValues.keyAction(l2, offset, GraphValues.keys(l2, offset)[0], 250_000L, 7f, 0f)!!)
        assertNull(v.gestureError); v.commitGesture()
        assertEquals(7f, v.state.project.composition.layers[0].text.animators[0].selectors[0].offset.keyframes[0].value)
    }

    @Test fun valueClampAndSnap() {
        val l = layer()
        val axis = GraphValues.axes(l, GraphTarget(AnimPropertyType.OPACITY), 1080, 1920).single()
        assertEquals(1f, GraphValues.clampValue(GraphTarget(AnimPropertyType.OPACITY), axis, 2f))
        val vp = ValueViewport(0.0, 1e6, 0.0, 100.0)
        assertEquals(50.0, ValueView.step(100.0), 0.0); assertEquals(50.0, ValueView.snapValue(41.0, vp), 1e-9)
        assertEquals(40.0, ValueView.snapValue(41.0, ValueViewport(0.0, 1e6, 0.0, 10.0).let { ValueViewport(0.0, 1e6, 30.0, 50.0) }), 1e-9)
        assertEquals(.5, ValueView.step(2.0), 1e-12); assertEquals(2.0, ValueView.step(7.0), 1e-12)
    }

    @Test fun handlesMapBothWaysIncludingFlatSegments() {
        val b = floatArrayOf(.25f, .1f, .75f, 1.4f)
        val p1 = ValueHandles.point(b, 0, 0.0, 10.0, 1e6, 110.0)
        assertEquals(250_000.0, p1.first, 1e-6); assertEquals(20.0, p1.second, 1e-6)
        val back = ValueHandles.normalised(b, 1, 900_000.0, 80.0, 0.0, 10.0, 1e6, 110.0)
        assertEquals(.9f, back.first, 1e-6f); assertEquals(.7f, back.second, 1e-6f)
        // Flat: drawn on the value line, x moves, y is kept.
        assertTrue(ValueHandles.flat(5.0, 5.0))
        assertEquals(5.0, ValueHandles.point(b, 1, 0.0, 5.0, 1e6, 5.0).second, 0.0)
        assertEquals(.3f to 1.4f, ValueHandles.normalised(b, 1, 300_000.0, 999.0, 0.0, 5.0, 1e6, 5.0))
    }

    @Test fun viewportFitPanAndAxisAwareZoom() {
        val fit = ValueView.fit(0, 3_000_000, -10.0, 120.0)
        assertTrue(fit.t0 < 0 && fit.t1 > 3_000_000 && fit.v0 < -10 && fit.v1 > 120)
        // Overshoot: the sampled curve extent (beyond the key values) is what is fitted.
        val l = layer(EasingCurve.bezier(.3f, 0f, .6f, 1.8f)); val s = GraphValues.sampler(l, rotation)!!; val out = FloatArray(2)
        var hi = -1e9; var lo = 1e9
        for (i in 0..300) { s(i * 10_000L, out); hi = maxOf(hi, out[0].toDouble()); lo = minOf(lo, out[0].toDouble()) }
        assertTrue("overshoot above the 90° key: $hi", hi > 90.0)
        val f2 = ValueView.fit(0, 3_000_000, lo, hi); assertTrue(f2.v1 > hi && f2.v0 < lo)
        // Flat content still gets a visible span.
        assertTrue(ValueView.fit(0, 1_000_000, 5.0, 5.0).valueSpan >= 1.0)
        val limits = ValueView.limits(fit, 1e6 / 30)
        // Pan is clamped: the window's centre stays inside the content.
        val far = ValueView.pan(fit, 1e12, -1e9, limits)
        assertEquals(fit.t1, (far.t0 + far.t1) / 2, 1e-3); assertEquals(fit.v0, (far.v0 + far.v1) / 2, 1e-6)
        assertEquals(fit.timeSpan, far.timeSpan, 1e-6)
        // Horizontal pinch zooms time only; the focal point stays fixed.
        val z = ValueView.zoom(fit, .25, .5, 2.0, ZoomAxes.TIME, limits)
        assertEquals(fit.timeSpan / 2, z.timeSpan, 1e-6); assertEquals(fit.valueSpan, z.valueSpan, 1e-9)
        assertEquals(fit.t0 + .25 * fit.timeSpan, z.t0 + .25 * z.timeSpan, 1e-3)
        val zv = ValueView.zoom(fit, .5, .2, 4.0, ZoomAxes.VALUE, limits)
        assertEquals(fit.timeSpan, zv.timeSpan, 1e-9); assertEquals(fit.v1 - .2 * fit.valueSpan, zv.v1 - .2 * zv.valueSpan, 1e-6)
        // Min / max zoom.
        assertEquals(2e6 / 30, ValueView.zoom(fit, .5, .5, 1e9, ZoomAxes.BOTH, limits).timeSpan, 1e-6)
        assertEquals(fit.timeSpan * 20, ValueView.zoom(fit, .5, .5, 1e-9, ZoomAxes.BOTH, limits).timeSpan, 1e-3)
        assertEquals(ZoomAxes.TIME, ValueView.axes(100f, 10f)); assertEquals(ZoomAxes.VALUE, ValueView.axes(5f, 90f)); assertEquals(ZoomAxes.BOTH, ValueView.axes(50f, 40f))
        // Time ticks: frames while under a second, seconds beyond.
        assertTrue(ValueView.timeStep(.5e6, rate).second); assertFalse(ValueView.timeStep(10e6, rate).second)
    }

    @Test fun selectionRules() {
        val keys = listOf(0L, 1_000_000L, 3_000_000L)
        // A tapped key selects its departing segment; the last key selects the previous one.
        assertEquals(1, GraphSelection.segmentIndex(keys, KeySelection(1_000_000L, 42L), 42L, 2_500_000L))
        assertEquals(1, GraphSelection.segmentIndex(keys, KeySelection(3_000_000L, 42L), 42L, 100L))
        assertEquals(0, GraphSelection.segmentIndex(keys, KeySelection(0L, 42L), 42L, 2_500_000L))
        // Any playhead move hands it back to the playhead rule.
        assertEquals(1, GraphSelection.segmentIndex(keys, KeySelection(0L, 42L), 43L, 2_500_000L))
        // No selection (an empty tap clears it): the playhead rule.
        assertEquals(0, GraphSelection.segmentIndex(keys, null, 42L, 10L))
        // A key that no longer exists does not count.
        assertFalse(GraphSelection.valid(KeySelection(5L, 1L), keys, 1L))
        val l = layer()
        assertEquals(2, GraphSelection.segment(l, rotation, KeySelection(3_000_000L, 9L), 9L, 0L)!!.index + 1)
        // A preset tap in the Value view targets the SELECTED segment's departing key.
        val seg = GraphSelection.segment(l, rotation, KeySelection(1_000_000L, 9L), 9L, 0L)!!
        assertEquals(EditorAction.SetKeyEasing(1, AnimPropertyType.ROTATION, null, listOf(1_000_000L), EasingCurve.LINEAR),
            GraphModel.action(1, rotation, listOf(seg.startUs), EasingCurve.LINEAR))
        // Session reset.
        val session = GraphSession(); session.mode = GraphMode.VALUE; session.selection = KeySelection(0, 0); session.channel = 1
        session.viewport = ValueViewport(0.0, 1.0, 0.0, 1.0); session.reset()
        assertEquals(GraphMode.EASE, session.mode); assertNull(session.selection); assertNull(session.viewport); assertEquals(0, session.channel)
    }

    @Test fun libraryGridDropSlotsFromThePointer() {
        // Two columns of 38dp cells, rows 41dp tall.
        val cells = (0 until 5).map { i -> val c = i % 2; val r = i / 2; Rect(c * 39f, r * 42f, c * 39f + 38f, r * 42f + 41f) }
        assertEquals(0, GridDrop.index(cells, 5f, 10f)); assertEquals(1, GridDrop.index(cells, 30f, 10f))
        assertEquals(2, GridDrop.index(cells, 5f, 50f)); assertEquals(4, GridDrop.index(cells, 70f, 50f))
        assertEquals(5, GridDrop.index(cells, 5f, 400f)); assertEquals(0, GridDrop.index(emptyList(), 0f, 0f))
        assertEquals(3, GridDrop.slot(cells, 60f, 60f)); assertEquals(4, GridDrop.slot(cells, 70f, 100f)); assertNull(GridDrop.slot(cells, 5f, 300f))
        // Sections: no Types; Pinned, Presets, My curves.
        val s = CurveLibrary.sections(CurveLibraryState())
        assertEquals(12, s.pinned.size); assertTrue(s.presets.none { "preset:" + it.id.removePrefix("preset:") in CurveLibrary.DEFAULT_PINNED })
        // Rows visible at both heights.
        val content268 = 207.8f; val content328 = 267.8f
        assertTrue("rows at 268: ${GraphLayout.gridRowsVisible(content268, 1f)}", GraphLayout.gridRowsVisible(content268, 1f) >= 4f)
        assertTrue(GraphLayout.gridRowsVisible(content328, 1f) > GraphLayout.gridRowsVisible(content268, 1f))
        // The library fade is the same composable/constants open and closed: icon bounds + 6dp.
        assertEquals(6f, GraphLayout.SCRIM_EXTENT); assertEquals(GraphLayout.RAIL + GraphLayout.SCRIM_EXTENT, GraphLayout.scrimSize())
    }
}
