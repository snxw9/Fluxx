package com.fluxx.android.ui.inspector

import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test

/** GRAPH G1.1: page geometry, left-rail fit, values bar model, XY pad mapping, presets, Y range and the playhead rule. */
class GraphG11Test {
    private val windows = listOf(360f, 384f, 411f)
    private val sheets = listOf(268f, 328f)
    private val scales = listOf(1f, 1.3f)

    @Test fun pageGeometryFitsEveryWidthHeightAndScale() {
        for (w in windows) for (base in sheets) for (s in scales) {
            val sheet = base * s
            val content = InspectorChromeBudget.contentHeight(sheet, s)
            val width = w - InspectorSheetBudget.FRAME_H
            val m = GraphLayout.compute(width, content, s)
            val label = "$w dp × $base × $s: $m"
            assertTrue(label, GraphLayout.leftRailFits(content))
            assertTrue(label, m.leftTile in GraphLayout.LEFT_MIN..GraphLayout.RAIL)
            // Rails + centre fit side by side; the touchpad and the bar share the centre item's width.
            // G2L: centreWidth is everything right of the left rail (touchpad + strip + pinned rail).
            assertTrue(label, GraphLayout.RAIL + GraphLayout.RAIL_GAP + m.centreWidth <= width + 1e-3f)
            assertTrue(label, m.padWidth <= m.centreWidth + 1e-3f && m.padWidth <= m.padHeight * GraphLayout.MAX_ASPECT + 1e-3f)
            // Header + gap + pad + hairline + bar fill the sheet exactly; nothing scrolls.
            assertEquals(label, sheet - InspectorSheetBudget.FRAME_V, InspectorChromeBudget.header(s) + InspectorChromeBudget.CONTENT_TOP +
                m.padHeight + GraphLayout.HAIRLINE + m.bar, 1e-3f)
            if (s == 1f) {
                assertTrue(label, m.padHeight >= if (base == 268f) 150f else 210f)
                if (w == 360f) assertTrue(label, m.padWidth >= 200f)
            } else assertTrue(label, m.padHeight >= 120f)
        }
    }

    @Test fun leftRailTilesShrinkToFitButNotBelow36dp() {
        // 268dp at font scale 1: 208dp of content, five 40dp tiles plus four 2dp gaps exactly.
        assertEquals(208f, InspectorChromeBudget.contentHeight(268f, 1f), 1e-3f)
        assertEquals(40f, GraphLayout.leftTile(208f), 1e-3f)
        assertEquals(40f, GraphLayout.leftTile(300f), 1e-3f)
        assertEquals(36.4f, GraphLayout.leftTile(190f), 1e-3f)
        assertEquals(36f, GraphLayout.leftTile(150f), 0f)
        assertTrue(GraphLayout.leftRailFits(188f)); assertFalse(GraphLayout.leftRailFits(150f))
    }

    @Test fun valuesBarTilesLabelsFormatsAndTypedValues() {
        fun names(c: EasingCurve) = EaseLimits.rows(c).map { it.first.name }
        for (c in listOf(EasingCurve.LINEAR, EasingCurve.EASY_EASE, EaseChoice.EASE_IN_CURVE, EaseChoice.EASE_OUT_CURVE, EasingCurve.bezier(.1f, 2f, .3f, -1f)))
            assertEquals(listOf("x1", "y1", "x2", "y2"), names(c))
        assertEquals(listOf("Bounces", "Height"), names(EasingCurve.bounce(3f, .5f)))
        assertEquals(listOf("Oscillations", "Damping"), names(EasingCurve.elastic(3f, .5f)))
        assertTrue(names(EasingCurve.HOLD).isEmpty())
        // Bezier types show their bezier values.
        assertEquals(listOf(.42f, 0f, 1f, 1f), EaseLimits.rows(EaseChoice.EASE_IN_CURVE).map { it.second })
        assertEquals("0.42", EaseLimits.format(EaseLimits.X1, .42f)); assertEquals("-0.56", EaseLimits.format(EaseLimits.Y2, -.56f))
        assertEquals("5", EaseLimits.format(EaseLimits.BOUNCES, 5f)); assertEquals("2.5", EaseLimits.format(EaseLimits.OSCILLATIONS, 2.5f))
        assertEquals("0.10", EaseLimits.format(EaseLimits.DAMPING, .1f))
        // Typing converts a bezier type to Custom and clamps; invalid input is discarded.
        val typed = EaseLimits.typed(EasingCurve.LINEAR, 1, ".6")!!
        assertEquals(EasingPreset.CUSTOM, typed.type); assertEquals(.6f, typed.b, 1e-6f)
        assertEquals(1f, EaseLimits.typed(EasingCurve.EASY_EASE, 0, "7")!!.a, 0f)
        assertEquals(3f, EaseLimits.typed(EasingCurve.EASY_EASE, 3, "12")!!.d, 0f)
        assertEquals(-2f, EaseLimits.typed(EasingCurve.EASY_EASE, 1, "-9")!!.b, 0f)
        assertEquals(EasingCurve.bounce(8f, .5f), EaseLimits.typed(EasingCurve.bounce(3f, .5f), 0, "12"))
        assertEquals(EasingCurve.bounce(4f, .5f), EaseLimits.typed(EasingCurve.bounce(3f, .5f), 0, "3.6"))
        assertEquals(EasingCurve.bounce(3f, .1f), EaseLimits.typed(EasingCurve.bounce(3f, .5f), 1, "0"))
        assertEquals(EasingCurve.elastic(10f, .5f), EaseLimits.typed(EasingCurve.elastic(3f, .5f), 0, "40"))
        assertEquals(EasingCurve.elastic(3f, 1f), EaseLimits.typed(EasingCurve.elastic(3f, .5f), 1, "5"))
        for (bad in listOf("", "abc", "1..2", "NaN")) assertNull(bad, EaseLimits.typed(EasingCurve.EASY_EASE, 0, bad))
        assertNull(EaseLimits.typed(EasingCurve.HOLD, 0, "1"))
    }

    @Test fun xyPadMapsPositionToParametersAndBack() {
        assertEquals(EasingCurve.bounce(1f, .1f), EasePad.curve(EasingPreset.BOUNCE, 0f, 0f))
        assertEquals(EasingCurve.bounce(8f, .9f), EasePad.curve(EasingPreset.BOUNCE, 1f, 1f))
        // Bounce counts snap to whole bounces; positions outside the pad clamp.
        assertEquals(4f, EasePad.curve(EasingPreset.BOUNCE, .4f, .5f).a, 0f)
        assertEquals(EasingCurve.bounce(8f, .9f), EasePad.curve(EasingPreset.BOUNCE, 3f, 9f))
        assertEquals(EasingCurve.bounce(1f, .1f), EasePad.curve(EasingPreset.BOUNCE, -2f, -1f))
        assertEquals(EasingCurve.elastic(1f, .1f), EasePad.curve(EasingPreset.ELASTIC, 0f, 0f))
        assertEquals(EasingCurve.elastic(10f, 1f), EasePad.curve(EasingPreset.ELASTIC, 1f, 1f))
        assertEquals(5.5f, EasePad.curve(EasingPreset.ELASTIC, .5f, .5f).a, 1e-5f)
        // Round trips.
        for (n in 1..8) for (h in listOf(.1f, .5f, .9f)) {
            val c = EasingCurve.bounce(n.toFloat(), h); val (x, y) = EasePad.position(c)
            val back = EasePad.curve(EasingPreset.BOUNCE, x, y); assertEquals(c.a, back.a, 0f); assertEquals(c.b, back.b, 1e-5f)
        }
        for (o in listOf(1f, 3.3f, 10f)) for (d in listOf(.1f, .55f, 1f)) {
            val c = EasingCurve.elastic(o, d); val (x, y) = EasePad.position(c)
            val back = EasePad.curve(EasingPreset.ELASTIC, x, y); assertEquals(o, back.a, 1e-4f); assertEquals(d, back.b, 1e-5f)
        }
        assertTrue(EasePad.applies(EasingCurve.bounce(2f, .3f)) && !EasePad.applies(EasingCurve.EASY_EASE))
        assertThrows(IllegalArgumentException::class.java) { EasePad.position(EasingCurve.LINEAR) }
        // Pad coordinates are the plot rectangle, 0 at the bottom, for any aspect.
        val plot = EasePlot(300f, 120f, 10f, -.15f, 1.15f)
        assertEquals(.25f, plot.padX(plot.padToX(.25f)), 1e-6f); assertEquals(.75f, plot.padY(plot.padToY(.75f)), 1e-6f)
        assertEquals(110f, plot.padToY(0f), 1e-4f); assertEquals(290f, plot.padToX(1f), 1e-4f)
    }

    @Test fun rightRailTilesAndPresetsHaveNoDuplicateCurves() {
        assertEquals(listOf(EaseChoice.HOLD, EaseChoice.LINEAR, EaseChoice.EASY_EASE, EaseChoice.EASE_IN, EaseChoice.EASE_OUT, EaseChoice.BOUNCE, EaseChoice.ELASTIC),
            GRAPH_TYPE_TILES)
        assertEquals(30, EasePresets.ALL.size)
        assertTrue(EasePresets.ALL.none { it.id == "css.ease_in" || it.id == "css.ease_out" })
        val tileCurves = GRAPH_TYPE_TILES.map { EaseChoice.curve(it, EasingCurve.EASY_EASE) }.mapNotNull { c ->
            c.bezierOrNull()?.let { EasingCurve.bezier(it[0], it[1], it[2], it[3]) } }
        for (p in EasePresets.ALL) for (t in tileCurves) assertFalse("${p.id} duplicates a type tile", EaseMath.same(p.curve, t))
        for (i in EasePresets.ALL.indices) for (j in i + 1 until EasePresets.ALL.size)
            assertFalse("${EasePresets.ALL[i].id} = ${EasePresets.ALL[j].id}", EaseMath.same(EasePresets.ALL[i].curve, EasePresets.ALL[j].curve))
        // Highlights: types by type, bezier tiles by curve; an unmatched Custom curve highlights nothing.
        assertEquals(EaseChoice.EASE_IN, graphTypeTile(EasingCurve.bezier(.42f, 0f, 1f, 1f)))
        assertEquals(EaseChoice.BOUNCE, graphTypeTile(EasingCurve.bounce(5f, .2f)))
        assertNull(graphTypeTile(EasingCurve.bezier(.1f, .7f, .2f, .9f))); assertNull(EasePresets.match(EasingCurve.bezier(.1f, .7f, .2f, .9f)))
    }

    @Test fun defaultYRangeIsTighter() {
        assertEquals(-.15f to 1.15f, EasePlot.range(EasingCurve.EASY_EASE))
        assertEquals(-.15f to 1.15f, EasePlot.range(EasingCurve.LINEAR))
        val (lo, hi) = EasePlot.range(EasingCurve.bezier(.3f, 1.6f, .7f, 1f)); assertEquals(-.15f, lo, 0f); assertEquals(1.7f, hi, 1e-4f)
    }

    /**
     * §7: no Graph interaction moves the playhead. The EditorViewModel sequence of a Graph drag with the preview
     * controller's position reports interleaved (it echoes the time it was given), then single taps.
     */
    @Test fun graphEditingNeverMovesThePlayhead() {
        val layer = CompositionLayer(1, LayerType.TEXT, timing = ClipTiming(0, 0, 4_000_000), text = TextProperties(
            source = AnimatableString(staticValue = "Offset"), animators = FrozenList.of(listOf(TextAnimator(7, selectors = FrozenList.of(listOf(
                TextRangeSelector(8, offset = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(Keyframe1D(0, -100f), Keyframe1D(1_000_000, 0f),
                    Keyframe1D(3_000_000, 100f))))))))))))
        val vm = EditorViewModel()
        vm.dispatch(EditorAction.Load(ProjectDocument(Composition(durationUs = 4_000_000, layers = FrozenList.of(listOf(layer))))))
        vm.dispatch(EditorAction.Select(1)); vm.dispatch(EditorAction.Seek(1_744_321))
        val target = GraphTarget(animator = TextAnimatorEdits.selectorField(7, 8, SelectorField.OFFSET))
        val before = vm.state.playheadUs
        val key = GraphModel.segment(vm.state.selectedLayer!!, target, GraphModel.local(vm.state.selectedLayer!!, target, before, FrameRate()))!!.startUs
        vm.beginGesture(); vm.reportPosition(before)
        for (x in listOf(.2f, .1f, 0f)) { vm.previewGesture(GraphModel.action(1, target, listOf(key), EasingCurve.bezier(x, 0f, .67f, 1f))); vm.reportPosition(vm.displayedState.playheadUs) }
        vm.commitGesture(); vm.reportPosition(vm.state.playheadUs)
        assertEquals(before, vm.state.playheadUs); assertEquals(1L, vm.state.selectedLayerId)
        for (c in listOf(EasingCurve.bounce(3f, .5f), EasingCurve.HOLD, EasePresets.ALL[0].curve)) {
            vm.dispatch(GraphModel.action(1, target, listOf(key), c)); assertEquals(before, vm.state.playheadUs)
        }
        vm.dispatch(GraphModel.action(1, target, null, EasingCurve.LINEAR)); assertEquals(before, vm.state.playheadUs)
        assertEquals(1L, vm.state.selectedLayerId)
    }
}
