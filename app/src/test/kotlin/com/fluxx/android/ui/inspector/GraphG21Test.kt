package com.fluxx.android.ui.inspector

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/** GRAPH G2.1 (A16 acceptance fixes): units, live value, time, typed key edits, xy fields, drag draft, auto-scroll, detents, Snap. */
class GraphG21Test {
    private val rate = FrameRate(30, 1)
    private val w = 1080; private val h = 1920
    /** The user's case: a Position key the Transform page shows as X 639, Y 915.5 (composition pixels, top-left origin). */
    private val userX = (639f - 540f) / 540f
    private val userY = (915.5f - 960f) / 960f
    private fun layer() = CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(0, 0, 4_000_000),
        animTransform = AnimatableTransform(
            position = AnimatableProperty2D(true, keyframes = FrozenList.of(listOf(Keyframe2D(0, userX, userY), Keyframe2D(2_000_000, 0f, 0f)))),
            scale = AnimatableProperty2D(true, keyframes = FrozenList.of(listOf(Keyframe2D(0, 1.5f, .25f), Keyframe2D(2_000_000, 1f, 1f)))),
            rotation = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(Keyframe1D(0, 405f), Keyframe1D(1_000_000, -30f), Keyframe1D(3_000_000, 45f)))),
            opacity = AnimatableProperty1D(true, 1f, FrozenList.of(listOf(Keyframe1D(0, .5f), Keyframe1D(2_000_000, 1f))))))
    private fun text() = CompositionLayer(1, LayerType.TEXT, timing = ClipTiming(0, 0, 4_000_000),
        text = TextProperties(source = AnimatableString(staticValue = "AB"),
            size = AnimatableProperty1D(true, 50f, FrozenList.of(listOf(Keyframe1D(0, 72f), Keyframe1D(3_000_000, 90f)))),
            tracking = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(Keyframe1D(0, 150f), Keyframe1D(3_000_000, -40f)))),
            leading = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(Keyframe1D(0, 36f), Keyframe1D(3_000_000, 48f)))),
            baselineShift = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(Keyframe1D(0, -12.5f), Keyframe1D(3_000_000, 4f)))),
            strokeWidth = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(Keyframe1D(0, 2.5f), Keyframe1D(3_000_000, 6f)))),
            animators = FrozenList.of(listOf(TextAnimator(5, selectors = FrozenList.of(listOf(TextRangeSelector(6, offset = AnimatableProperty1D(true, 0f,
                FrozenList.of(listOf(Keyframe1D(0, 33.3f), Keyframe1D(3_000_000, 100f))))))),
                properties = FrozenList.of(listOf(
                    TextPropertyOverride(AnimatorProperty.POSITION, vector = AnimatableProperty2D(true, 0f, 0f,
                        FrozenList.of(listOf(Keyframe2D(0, 12.5f, -7f), Keyframe2D(3_000_000, 10f, 10f))))),
                    TextPropertyOverride(AnimatorProperty.ROTATION, scalar = AnimatableProperty1D(true, 0f,
                        FrozenList.of(listOf(Keyframe1D(0, 90f), Keyframe1D(3_000_000, 0f))))))))))))
    private fun vm(l: CompositionLayer) = EditorViewModel().also {
        it.dispatch(EditorAction.Load(ProjectDocument(com.fluxx.android.model.Composition(durationUs = 4_000_000, layers = FrozenList.of(listOf(l))))))
        it.dispatch(EditorAction.Select(1)); it.dispatch(EditorAction.Seek(1_234_567))
    }
    private fun f1(v: Float) = String.format(Locale.ROOT, "%.1f", v)

    // ---- §5 units audit: the Graph's text equals the inspector page's text, for every property with a Value graph ----

    /** The pre-G2.1 inline page formulas (copied from ElementInspectorSheet before extraction) and the Graph's text. */
    @Test fun unitsCrossCheckTable() {
        val l = layer(); val t = text()
        data class Row(val name: String, val layer: CompositionLayer, val target: GraphTarget, val channel: Int, val stored: Float, val page: String)
        val pos = GraphTarget(AnimPropertyType.POSITION); val sc = GraphTarget(AnimPropertyType.SCALE)
        val rows = listOf(
            Row("Position X", l, pos, 0, userX, f1(w / 2f + userX * (w / 2f))),
            Row("Position Y", l, pos, 1, userY, f1(h / 2f + userY * (h / 2f))),
            Row("Scale X", l, sc, 0, 1.5f, ScaleRulerMath.readout(1.5f).removeSuffix("%")),
            Row("Scale Y", l, sc, 1, .25f, ScaleRulerMath.readout(.25f).removeSuffix("%")),
            Row("Rotation", l, GraphTarget(AnimPropertyType.ROTATION), 0, 405f, formatRotation(405f)),
            Row("Opacity", l, GraphTarget(AnimPropertyType.OPACITY), 0, .5f, f1(.5f * 100f)),
            Row("Font size", t, GraphTarget(AnimPropertyType.FONT_SIZE), 0, 72f, PropertyLimits.format(PropertyLimits.FONT_SIZE, 72f)),
            Row("Tracking", t, GraphTarget(AnimPropertyType.TRACKING), 0, 150f, PropertyLimits.format(PropertyLimits.TRACKING, 150f)),
            Row("Leading", t, GraphTarget(AnimPropertyType.LEADING), 0, 36f, PropertyLimits.format(PropertyLimits.LEADING, 36f)),
            Row("Baseline shift", t, GraphTarget(AnimPropertyType.BASELINE_SHIFT), 0, -12.5f, PropertyLimits.format(PropertyLimits.BASELINE, -12.5f)),
            Row("Stroke width", t, GraphTarget(AnimPropertyType.STROKE_WIDTH), 0, 2.5f, PropertyLimits.format(PropertyLimits.STROKE_WIDTH, 2.5f)),
            Row("Animator Position X", t, GraphTarget(animator = TextAnimatorEdits.property(5, AnimatorProperty.POSITION)), 0, 12.5f,
                PropertyLimits.format(AnimateFields.property(AnimatorProperty.POSITION).limit!!, 12.5f)),
            Row("Animator Position Y", t, GraphTarget(animator = TextAnimatorEdits.property(5, AnimatorProperty.POSITION)), 1, -7f,
                PropertyLimits.format(AnimateFields.property(AnimatorProperty.POSITION).limit!!, -7f)),
            Row("Animator Rotation", t, GraphTarget(animator = TextAnimatorEdits.property(5, AnimatorProperty.ROTATION)), 0, 90f,
                PropertyLimits.format(AnimateFields.property(AnimatorProperty.ROTATION).limit!!, 90f)),
            Row("Selector Offset", t, GraphTarget(animator = TextAnimatorEdits.selectorField(5, 6, SelectorField.OFFSET)), 0, 33.3f,
                PropertyLimits.format(AnimateFields.selector(SelectorField.OFFSET).limit!!, 33.3f)),
        )
        for (r in rows) {
            val axes = GraphValues.axes(r.layer, r.target, w, h)
            assertTrue(r.name, axes.size > r.channel)
            // The key's stored value as the Value tile shows it equals the page's text.
            val key = GraphValues.keys(r.layer, r.target).first()
            assertEquals(r.name + " fixture", r.stored, key.value(r.channel), 0f)
            assertEquals(r.name, r.page, axes[r.channel].storedText(key.value(r.channel)))
        }
        // The user's case, literally.
        val axes = GraphValues.axes(l, pos, w, h)
        assertEquals("639.0", axes[0].storedText(userX)); assertEquals("915.5", axes[1].storedText(userY))
        assertEquals("px", axes[0].unit)
        // Round trip: a typed shown value maps back to the page's stored value.
        assertEquals(userX, axes[0].typed(639f), 1e-6f); assertEquals(userY, axes[1].typed(915.5f), 1e-6f)
        // The header labels use the same units and formatting.
        val s = GraphModel.segment(l, pos, 0)!!
        assertEquals("639.0, 915.5px" to "540.0, 960.0px", GraphValues.labels(l, pos, s, w, h))
        val rs = GraphModel.segment(l, GraphTarget(AnimPropertyType.ROTATION), 0)!!
        assertEquals("1x45.0°" to "-1x330.0°", GraphValues.labels(l, GraphTarget(AnimPropertyType.ROTATION), rs, w, h))
    }

    /** The page now calls the same helper; its arithmetic is the inline formula's, bit for bit. */
    @Test fun sharedHelperMatchesThePagesInlineArithmetic() {
        for (ref in listOf(0, 720, 1080, 2160)) {
            val refW = ref.takeIf { it > 0 } ?: w
            val u = PropertyDisplay.positionX(w, refW)
            for (x in listOf(-3.2f, -1f, -.123457f, 0f, userX, .5f, 1f, 7.77f)) {
                assertEquals(w / 2f + x * (refW / 2f), u.toDisplay(x), 0f)
                val typed = 639.25f
                assertEquals((typed - w / 2f) / (refW / 2f), u.toStored(typed), 0f)
            }
        }
        for (o in listOf(0f, .25f, .333f, 1f)) { assertEquals(f1(o * 100f), PropertyDisplay.OPACITY.storedText(o)); assertEquals(50f / 100f, PropertyDisplay.OPACITY.toStored(50f), 0f) }
        for (r in listOf(-725.5f, 0f, 45f, 360f, 3600.1f)) assertEquals(formatRotation(r), PropertyDisplay.ROTATION.storedText(r))
    }

    @Test fun valueTicksUseThePageFormatterOrFinerDecimals() {
        val pos = GraphValues.axes(layer(), GraphTarget(AnimPropertyType.POSITION), w, h)[0]
        assertEquals("650.0", valueLabel(650.0, 50.0, pos)); assertEquals("650.05", valueLabel(650.05, .05, pos))
        val rot = GraphValues.axes(layer(), GraphTarget(AnimPropertyType.ROTATION), w, h)[0]
        assertEquals("1x0.0", valueLabel(360.0, 90.0, rot)); assertEquals("0x0.0", valueLabel(1e-9, 10.0, rot))
    }

    // ---- §6 live value and typed value at the playhead ----

    @Test fun liveValueIsThePagesValueAtThePlayhead() {
        val l = layer(); val t = text()
        for (p in listOf(0L, 333_333L, 1_234_567L, 3_500_000L)) {
            val e = l.evaluatedTransform(p)
            assertArrayEquals(floatArrayOf(e.positionX, e.positionY), GraphValues.live(l, GraphTarget(AnimPropertyType.POSITION), p, rate), 0f)
            assertEquals(e.rotationDegrees, GraphValues.live(l, GraphTarget(AnimPropertyType.ROTATION), p, rate)!![0], 0f)
            assertEquals(e.opacity, GraphValues.live(l, GraphTarget(AnimPropertyType.OPACITY), p, rate)!![0], 0f)
            val local = rate.snap(p) - t.resolvedKeyframeAnchorUs
            assertEquals(t.text.sizeAt(local), GraphValues.live(t, GraphTarget(AnimPropertyType.FONT_SIZE), p, rate)!![0], 0f)
            val off = TextAnimatorEdits.selectorField(5, 6, SelectorField.OFFSET)
            assertEquals(TextAnimatorEdits.value(t.text, off, local), GraphValues.live(t, GraphTarget(animator = off), p, rate)!![0], 0f)
        }
    }

    @Test fun typedValueWithNoKeySetsTheValueAtThePlayheadWithOneUndo() {
        val l = layer(); val v = vm(l)
        val target = GraphTarget(AnimPropertyType.ROTATION)
        val a = GraphValues.atPlayheadAction(l, target, v.state.playheadUs, rate, 12f, 0f) as EditorAction.SetKeyframe
        assertEquals(1_234_567L, a.localTimeUs); assertEquals(EasingPreset.EASY_EASE, a.easing)
        v.dispatch(a)
        val after = v.state.project.composition.layers[0]
        assertEquals(12f, after.evaluatedTransform(1_234_567L).rotationDegrees, 0f)
        assertEquals(1_234_567L, v.state.playheadUs)
        v.undo(); assertEquals(l.animTransform.rotation, v.state.project.composition.layers[0].animTransform.rotation)
        // On a key: that key keeps its own easing (as the page's editTransform does).
        val onKey = GraphValues.atPlayheadAction(l, target, 1_000_000L, rate, 7f, 0f) as EditorAction.SetKeyframe
        assertEquals(l.animTransform.rotation.keyframes[1].easing, onKey.easing)
        // 2-D: the other channel keeps its live value; unchanged input does nothing.
        val pos = GraphTarget(AnimPropertyType.POSITION); val live = GraphValues.live(l, pos, 500_000L, rate)!!
        val p = GraphValues.atPlayheadAction(l, pos, 500_000L, rate, .1f, live[1]) as EditorAction.SetKeyframe2D
        assertEquals(live[1], p.y, 0f)
        assertNull(GraphValues.atPlayheadAction(l, pos, 500_000L, rate, live[0], live[1]))
        // Animator field: the Animate page's own edit.
        val t = text(); val off = GraphTarget(animator = TextAnimatorEdits.selectorField(5, 6, SelectorField.OFFSET))
        assertTrue(GraphValues.atPlayheadAction(t, off, 1_000_000L, rate, 40f, 0f) is EditorAction.EditTextAnimator)
    }

    /** G2 key-drag tests converted (§3): the Time / Value tiles' typed edit is one undo, clamps to neighbours and snaps to frames. */
    @Test fun typedKeyEditsSnapClampAndAreOneUndo() {
        val l = layer(); val v = vm(l); val target = GraphTarget(AnimPropertyType.ROTATION)
        val keys = GraphValues.keys(l, target); val times = keys.map { it.timeUs }
        // Typed "00:02:13" for key 2 (1 s): frame-snapped, inside its neighbours.
        val typed = GraphTime.parse("00:02:13", rate)!!
        val t = GraphValues.clampTime(l, rate, times, 1, typed)
        assertEquals(rate.frameTimeUs(73), t)
        // Past the next key: one frame before it.
        assertEquals(GraphValues.timeOf(l, rate, 89), GraphValues.clampTime(l, rate, times, 1, GraphTime.parse("00:05:00", rate)!!))
        val a = GraphValues.keyAction(l, target, keys[1], t, keys[1].x, 0f)!!
        v.dispatch(a)
        assertEquals(listOf(0L, t, 3_000_000L), GraphModel.keyTimes(v.state.project.composition.layers[0], target))
        assertEquals(1_234_567L, v.state.playheadUs)
        v.undo(); assertEquals(l.animTransform.rotation, v.state.project.composition.layers[0].animTransform.rotation)
        // Value: typed in the page's units (Opacity %), clamped by the page's typed limit.
        val op = GraphTarget(AnimPropertyType.OPACITY); val axis = GraphValues.axes(l, op, w, h)[0]
        assertEquals(1f, GraphValues.clampValue(op, axis, axis.typed(250f)), 0f); assertEquals(.42f, axis.typed(42f), 1e-6f)
    }

    // ---- §6a time ----

    /** Regression: the Graph's Time tile and axis used "%.2f" seconds / "Nf"; they now read exactly like the transport. */
    @Test fun graphTimesUseTheTransportTimecode() {
        for (r in listOf(FrameRate(30, 1), FrameRate(24, 1), FrameRate(30000, 1001), FrameRate(60, 1))) for (start in listOf(0L, 3_600_000_000L))
            for (f in listOf(0L, 1L, 15L, 29L, 45L, 1799L)) {
                val t = r.frameTimeUs(f)
                assertEquals(CompositionTimecode.format(start + t, r), GraphTime.label(t, r, start))
                // Round trip within half a frame: at 29.97 a 1-hour start timecode is not on the frame grid (107,892.1 frames),
                // so timecode → µs lands up to a fraction of a frame away (typed times are frame-snapped by clampTime anyway).
                val back = GraphTime.parse(GraphTime.label(t, r, start), r, start)!!
                assertTrue("$r $start $f: $back vs $t", kotlin.math.abs(back - t) < 1e6 * r.denominator / r.numerator / 2)
                if (start == 0L) assertEquals(t, back)
            }
        assertEquals("00:01:15", GraphTime.label(1_500_000L, rate))
        assertEquals("60:01:15", GraphTime.label(1_500_000L, rate, 3_600_000_000L))
        assertNotEquals(String.format(Locale.ROOT, "%.2f", 1.5), GraphTime.label(1_500_000L, rate))
        assertEquals(1_500_000L, GraphTime.parse("45", rate)); assertEquals(1_500_000L, GraphTime.parse("45f", rate))
        for (bad in listOf("", "abc", "1.5", "-3", "00:60:00", "00:00:30")) assertNull(bad, GraphTime.parse(bad, rate))
    }

    // ---- §7 xy fields ----

    @Test fun xyFieldsCommitOneFieldAtATime() {
        val c = EasingCurve.bezier(.1f, .9f, .2f, 1f)
        assertEquals(EasingCurve.bezier(.1f, .4f, .2f, 1f), XyEdit.commit(c, 1, "0.4"))
        assertEquals(EasingCurve.bezier(1f, .9f, .2f, 1f), XyEdit.commit(c, 0, "5"))
        assertNull("empty keeps", XyEdit.commit(c, 2, "")); assertNull("blank keeps", XyEdit.commit(c, 2, "  "))
        assertNull("invalid discarded", XyEdit.commit(c, 2, "abc"))
        assertNull("unchanged writes nothing", XyEdit.commit(c, 3, "1"))
        // Each field changes only its own parameter.
        val b = XyEdit.commit(c, 3, "-0.5")!!.bezierOrNull()!!
        assertArrayEquals(floatArrayOf(.1f, .9f, .2f, -.5f), b, 1e-6f)
        val bounce = EasingCurve.bounce(3f, .5f)
        assertEquals(bounce.copy(a = 8f), XyEdit.commit(bounce, 0, "12"))
        assertNull(XyEdit.commit(bounce, 2, "1"))
    }

    @Test fun imeArmIgnoresAPreviousKeyboardGoingDown() {
        val a = ImeArm()
        // Opened while the previous keyboard slides down: never armed, so reaching 0 is not Back.
        assertFalse(a.ime(900)); assertFalse(a.ime(500)); assertFalse(a.ime(0))
        // This field's keyboard rises, then Back hides it.
        assertFalse(a.ime(300)); assertFalse(a.ime(900)); assertTrue(a.ime(0))
        a.reset(); assertFalse(a.ime(900)); a.settled(900); assertTrue(a.ime(0))
    }

    // ---- §8 drag draft ----

    private val base = CurveLibraryState()
    private val free = EasePresets.ALL.map { CurveLibrary.presetId(it) }.filter { it !in base.pinned }

    @Test fun draftInsertReorderAndCommitAreTheSameOrder() {
        val p = base.pinned; val x = free[0]
        val ins = PinDrafts.draft(p, x, 2)
        assertEquals(p.take(2) + x + p.drop(2), ins.order); assertEquals(2, ins.gap); assertNull(ins.evicted); assertFalse(ins.unpinning)
        assertEquals(ins.order, PinDrafts.commit(base, x, 2).pinned)
        // Reorder: the slot is the index among the OTHER pinned curves.
        for (slot in 0..p.size - 1) {
            val d = PinDrafts.draft(p, p[0], slot)
            assertEquals(slot, d.order.indexOf(p[0])); assertEquals(d.order, PinDrafts.commit(base, p[0], slot).pinned)
        }
        // End slot.
        assertEquals(p + x, PinDrafts.draft(p, x, p.size).order)
    }

    @Test fun draftReplaceWhenFullKeepsTheOthersOrder() {
        var s = base; free.take(CurveLibrary.MAX_PINNED - base.pinned.size).forEach { s = CurveLibrary.pinAt(s, it, s.pinned.size) }
        assertEquals(CurveLibrary.MAX_PINNED, s.pinned.size)
        val x = free.first { it !in s.pinned }
        val d = PinDrafts.draft(s.pinned, x, 4)
        assertEquals(s.pinned[4], d.evicted); assertEquals(x, d.order[4]); assertEquals(CurveLibrary.MAX_PINNED, d.order.size)
        assertEquals(s.pinned.filterIndexed { i, _ -> i != 4 }, d.order.filter { it != x })
        assertEquals(d.order, PinDrafts.commit(s, x, 4).pinned)
    }

    @Test fun draftUnpinClosesTheSection() {
        val p = base.pinned
        val d = PinDrafts.draft(p, p[3], null)
        assertTrue(d.unpinning); assertEquals(p - p[3], d.order); assertNull(d.gap)
        assertEquals(d.order, PinDrafts.commit(base, p[3], null).pinned)
        // An unpinned curve outside Pinned changes nothing.
        val x = free[0]
        assertEquals(PinDraft(p, null, null, false), PinDrafts.draft(p, x, null)); assertSame(base, PinDrafts.commit(base, x, null))
    }

    @Test fun dropSlotFromThePointerWithHysteresis() {
        // A 2-column grid, rows 30 tall, cells 40 wide.
        val cells = (0 until 5).map { i -> val r = i / 2; val c = i % 2; Rect(c * 42f, r * 32f, c * 42f + 40f, r * 32f + 30f) }
        assertEquals(3, PinDrafts.candidate(cells, 60f, 45f, gapPresent = true, full = false))
        assertEquals(4, PinDrafts.candidate(cells, 10f, 200f, gapPresent = true, full = false))
        assertEquals(5, PinDrafts.candidate(cells, 10f, 200f, gapPresent = false, full = false))
        assertEquals(4, PinDrafts.candidate(cells, 10f, 200f, gapPresent = false, full = true))
        assertEquals(5, PinDrafts.candidate(cells, 35f, 70f, gapPresent = false, full = false)) // right half of the last cell
        assertEquals(0, PinDrafts.candidate(cells, 10f, -5f, gapPresent = true, full = false))
        assertNull(PinDrafts.candidate(cells, 10f, 31f, gapPresent = true, full = false)) // between rows: keep
        val slotAt = { o: Offset -> PinDrafts.candidate(cells, o.x, o.y, true, false) }
        // Just across the column boundary (x 41..43): within 8px of the current cell, the slot does not change.
        assertEquals(0, PinDrafts.stable(0, Offset(44f, 10f), cells[0], 8f, slotAt))
        assertEquals(1, PinDrafts.stable(0, Offset(70f, 10f), cells[0], 8f, slotAt))
        assertEquals(2, PinDrafts.stable(null, Offset(5f, 40f), null, 8f, slotAt))
    }

    /** A long Pinned section runs past the viewport: only laid-out cells have bounds; slots stay display indices. */
    @Test fun dropSlotWithCellsScrolledOutOfView() {
        // Display cells 4..9 are visible (rows 2..4 of 2 columns) out of 15.
        val visible = (4..9).map { i -> val r = i / 2; val c = i % 2; i to Rect(c * 42f, r * 32f - 64f, c * 42f + 40f, r * 32f - 34f) }
        assertEquals(7, PinDrafts.candidateIndexed(visible, 15, 60f, 45f, gapPresent = true, full = false))
        assertEquals(4, PinDrafts.candidateIndexed(visible, 15, 10f, -20f, gapPresent = true, full = false))
        // Below the last VISIBLE cell (not the last cell): its index, never a bogus end slot.
        assertEquals(9, PinDrafts.candidateIndexed(visible, 15, 10f, 500f, gapPresent = true, full = false))
        val tail = (10..14).map { i -> val r = i / 2; val c = i % 2; i to Rect(c * 42f, r * 32f, c * 42f + 40f, r * 32f + 30f) }
        assertEquals(15, PinDrafts.candidateIndexed(tail, 15, 10f, 900f, gapPresent = false, full = false))
        assertEquals(14, PinDrafts.candidateIndexed(tail, 15, 10f, 900f, gapPresent = false, full = true))
        assertEquals(0, PinDrafts.candidateIndexed(emptyList(), 0, 0f, 0f, false, false))
        assertNull(PinDrafts.candidateIndexed(emptyList(), 3, 0f, 0f, false, false))
    }

    @Test fun autoScrollRampsAndIsAtLeastThreeTimesG2() {
        val g2Max = 12f / .016f / 2.8125f // 12 px per 16 ms at the A16's density (450 dpi), in dp/s
        assertEquals(0f, DragAutoScroll.speed(56f), 0f); assertEquals(0f, DragAutoScroll.speed(80f), 0f)
        assertEquals(DragAutoScroll.MAX_DP_S, DragAutoScroll.speed(0f), 0f); assertEquals(DragAutoScroll.MAX_DP_S, DragAutoScroll.speed(-20f), 0f)
        var last = 0f
        for (d in 55 downTo 0) { val v = DragAutoScroll.speed(d.toFloat()); assertTrue(v >= last); last = v }
        assertTrue(DragAutoScroll.MAX_DP_S >= 3 * g2Max)
        assertTrue(DragAutoScroll.velocity(10f, 0f, 400f) < 0f); assertTrue(DragAutoScroll.velocity(390f, 0f, 400f) > 0f)
        assertEquals(0f, DragAutoScroll.velocity(200f, 0f, 400f), 0f)
    }

    // ---- §9 detents and Snap ----

    @Test fun magnetLocksInsideTheZoneReleasesOutsideAndNeverSkips() {
        val m = TransformDetents.linear(5f, .1f, 17.5f) // zone 1.75 value units (= 35% of 5)
        val out = (0..200).map { i -> val raw = i * .1f; raw to m.apply(raw) }
        for ((raw, o) in out) {
            val d = Math.round(raw / 5f) * 5f
            if (kotlin.math.abs(raw - d) <= 1.75f + 1e-4f) assertEquals("lock $raw", d, o, 1e-4f) else assertEquals("follow $raw", raw, o, 1e-4f)
        }
        // Every value between two zones is reached exactly (no skipping): 1.8..3.2 all pass through.
        assertTrue(out.filter { it.first in 1.8f..3.2f }.all { it.second == it.first })
        // Release once beyond the zone, then lock again on the way back.
        m.reset(); assertEquals(5f, m.apply(5.5f), 0f); assertEquals(6.9f, m.apply(6.9f), 0f); assertEquals(5f, m.apply(6.6f), 0f)
    }

    @Test fun zoneRuleKeepsZonesApart() {
        for (spacing in listOf(.5f, 3f, 5.2f, 13.7f, 30f, 80f, 400f)) {
            val z = TransformDetents.zonePx(TransformDetents.ZONE_DP, spacing)
            assertTrue("spacing $spacing zone $z", z < spacing / 2f); assertTrue(z <= TransformDetents.MAX_ZONE_FRACTION * spacing + 1e-5f)
        }
        // The Rotation dial: 144dp, handle radius 60dp; 5° detents are 5.24dp apart on the arc, so the zone is 1.83dp (1.75°).
        val density = 2.8125f; val r = 60f * density
        val spacing = (r * 5.0 * Math.PI / 180.0).toFloat()
        assertEquals(5.24f * density, spacing, .02f * density)
        val m = TransformDetents.rotation(r, TransformDetents.ZONE_DP * density)
        assertEquals(45f, m.apply(46.7f), 0f); m.reset(); assertEquals(46.8f, m.apply(46.8f), 0f)
        // Opacity on a 200dp track: 25% = 50dp apart; the zone is the full 8dp (4%).
        val o = TransformDetents.opacity(200f * density, TransformDetents.ZONE_DP * density)
        assertEquals(.5f, o.apply(.535f), 0f); o.reset(); assertEquals(.545f, o.apply(.545f), 0f)
    }

    @Test fun scaleDetentsFollowTheLogRuler() {
        val density = 2.8125f
        val m = TransformDetents.scale(ScaleRulerMath.DP_PER_DOUBLING * density, TransformDetents.ZONE_DP * density)
        assertEquals(1.25f, m.apply(1.27f), 0f); m.reset()
        assertEquals(1f, m.apply(.97f), 0f); m.reset()
        // At 1000% the 25% detents are 3.4dp apart: the zone shrinks with them (35%), so 1010% follows the finger.
        assertEquals(10.1f, m.apply(10.1f), 0f); m.reset()
        assertEquals(10f, m.apply(10.02f), 0f); m.reset()
        assertEquals(-1.5f, m.apply(-1.52f), 0f)
        assertEquals(31f, TransformDetents.scaleSpacingDoublings(1f) * ScaleRulerMath.DP_PER_DOUBLING, .5f)
    }

    @Test fun graphSnapIsOnByDefaultAndPersisted() {
        assertTrue(CurveLibraryState().graphSnap)
        val off = CurveLibraryFile.decode(CurveLibraryFile.encode(CurveLibraryState(graphSnap = false)))!!
        assertFalse(off.graphSnap)
        // A G2 file (no graphSnap key) reads as on.
        val g2 = CurveLibraryFile.encode(CurveLibraryState()).replace(",\"graphSnap\":true", "")
        assertFalse(g2.contains("graphSnap")); assertTrue(CurveLibraryFile.decode(g2)!!.graphSnap)
    }
}
