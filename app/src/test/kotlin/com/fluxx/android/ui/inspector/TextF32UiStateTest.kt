package com.fluxx.android.ui.inspector

import androidx.compose.ui.unit.dp
import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test

/** TEXT F3.2 UI state: non-modal popover dismiss rule, slider caps and sizes, lenient typed entry, Animate layout rules. */
class TextF32UiStateTest {
    // ---- §6 popovers --------------------------------------------------------------------------------------------
    @Test fun nonModalPopoversNeverCloseForTimelineTransportRailOrSlider() {
        for (t in PopoverDismissal.Touch.entries) {
            // TEXT F3.3 added the same tile, empty sheet space and a swipe down (TextF33UiStateTest).
            val expected = t !in setOf(PopoverDismissal.Touch.TIMELINE, PopoverDismissal.Touch.TRANSPORT, PopoverDismissal.Touch.RAIL, PopoverDismissal.Touch.SLIDER)
            assertEquals(t.name, expected, PopoverDismissal.dismisses(t))
        }
        // The preview's request is an increasing tick that open popovers wait for.
        val before = PopoverDismissal.requests.value
        PopoverDismissal.request(); assertEquals(before + 1, PopoverDismissal.requests.value)
    }

    // ---- §7 caps, size, axes -------------------------------------------------------------------------------------
    @Test fun animateCapsAndSoftRanges() {
        fun typed(l: PropertyLimit) = l.typedMin to l.typedMax
        assertEquals(-10000f to 10000f, typed(PropertyLimits.ANIM_OFFSET)); assertEquals(10000f, PropertyLimits.ANIM_OFFSET.hard(1e9f), 0f)
        assertEquals(-2000f to 2000f, typed(PropertyLimits.ANIM_SCALE)); assertEquals(2000f, PropertyLimits.ANIM_SCALE.typed(5000f), 0f)
        assertEquals(-3600f to 3600f, typed(PropertyLimits.ANIM_ROTATION)); assertEquals(-3600f, PropertyLimits.ANIM_ROTATION.hard(-1e6f), 0f)
        assertEquals(-1000f to 1000f, typed(PropertyLimits.ANIM_TRACKING))
        assertEquals(0f to 250f, typed(PropertyLimits.ANIM_BLUR))
        assertEquals(-85f to 85f, typed(PropertyLimits.ANIM_SKEW)); assertEquals(0f to 100f, typed(PropertyLimits.ANIM_PERCENT))
        // Typed values beyond the soft slider range pin the thumb at the end.
        assertEquals(1f, PropertyLimits.ANIM_OFFSET.fraction(9000f), 0f); assertEquals(0f, PropertyLimits.ANIM_OFFSET.fraction(-9000f), 0f)
        assertEquals(1f, PropertyLimits.ANIM_SCALE.fraction(1500f), 0f)
        // The Animate specs use them; the Transform tab's dial and ruler limits are unchanged.
        assertSame(PropertyLimits.ANIM_SCALE, AnimateFields.property(AnimatorProperty.SCALE).limit)
        assertSame(PropertyLimits.ANIM_ROTATION, AnimateFields.property(AnimatorProperty.ROTATION).limit)
        assertSame(PropertyLimits.ANIM_TRACKING, AnimateFields.property(AnimatorProperty.TRACKING).limit)
        assertEquals(36000f, PropertyLimits.ROTATION.typedMax, 0f); assertEquals(-1000f to 1000f, typed(PropertyLimits.TRACKING)) // F3.4
    }

    @Test fun capsStayFiniteInTheEngine() {
        // Every animator cap at once evaluates to finite records (no NaN / overflow), blur clamped to the SDF budget.
        val p = listOf(
            TextPropertyOverride(AnimatorProperty.POSITION, vector = AnimatableProperty2D(staticX = 10000f, staticY = -10000f)),
            TextPropertyOverride(AnimatorProperty.ANCHOR, vector = AnimatableProperty2D(staticX = -10000f, staticY = 10000f)),
            TextPropertyOverride(AnimatorProperty.SCALE, vector = AnimatableProperty2D(staticX = 2000f, staticY = -2000f)),
            TextPropertyOverride(AnimatorProperty.ROTATION, scalar = AnimatableProperty1D(staticValue = 3600f)),
            TextPropertyOverride(AnimatorProperty.TRACKING, scalar = AnimatableProperty1D(staticValue = 1000f)),
            TextPropertyOverride(AnimatorProperty.BLUR, scalar = AnimatableProperty1D(staticValue = 250f)),
            TextPropertyOverride(AnimatorProperty.SKEW, scalar = AnimatableProperty1D(staticValue = 85f)))
        val text = TextProperties(size = AnimatableProperty1D(staticValue = 100f), animators = FrozenList.of(listOf(
            TextAnimator(1, selectors = FrozenList.of(listOf(TextRangeSelector(2))), properties = FrozenList.of(p)))))
        val metrics = doubleArrayOf(1000.0,800.0,-200.0,0.0,1.0,2.0,1200.0, 0.0,0.0,0.0,0.0,700.0,500.0,-700.0, 0.0,600.0,0.0,0.0,700.0,500.0,-700.0,
            2.0,0.0,1.0, 271828.0,2.0,2.0,1.0, 0.0,1.0,0.0,0.0,0.0,600.0,1.0,2.0,0.0,0.0,600.0,600.0, 0.0,0.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,1.0)
        val a = PreparedTextAnimation(text, metrics, SelectorEaseTable.shared).also { it.evaluate(0) }
        for (i in 0 until a.modifiers.capacity() / 4) assertTrue(a.modifiers.getFloat(i * 4).isFinite())
        assertTrue(a.inkBounds.all { it.isFinite() })
        // Blur (record +80) is clamped to 10% of the 100px glyph: 250 typed renders as the 10px budget, never an artefact.
        assertEquals(10f, a.modifiers.getFloat(80), 1e-4f)
    }

    @Test fun sliderPopoversAreSmallerButKeepA48dpTarget() {
        assertTrue(SliderPopoverMetrics.ROW >= 48.dp)
        assertTrue(SliderPopoverMetrics.MIN_WIDTH < 280.dp)
        assertTrue(SliderPopoverMetrics.TRACK < 16.dp && SliderPopoverMetrics.THUMB_HEIGHT < 44.dp)
        // Two-axis glyphs are in the one icon family.
        assertNotNull(InspectorIcons.icon("AXIS_X")); assertNotNull(InspectorIcons.icon("AXIS_Y"))
    }

    // ---- §8 typed entry -------------------------------------------------------------------------------------------
    @Test fun lenientSignedNumberEntry() {
        val n = ValueParsing::number
        assertEquals(-50.0, n("-50", "")!!, 0.0)
        assertEquals(-50.0, n("−50", "")!!, 0.0)       // Unicode minus
        assertEquals(-50.0, n("50-", "")!!, 0.0)            // sign typed after the digits (Samsung "." / "−" key order)
        assertEquals(-0.5, n("-,5", "")!!, 0.0)             // comma decimals
        assertEquals(12.5, n("12,5", "")!!, 0.0)
        assertEquals(1000.5, n("1,000.5", "")!!, 0.0)       // thousands comma with a decimal point
        assertEquals(5.0, n("+5", "")!!, 0.0); assertEquals(5.0, n(" 5 px ", "px")!!, 0.0); assertEquals(-12.0, n("-12 %", "%")!!, 0.0)
        assertEquals(5.0, n("5.", "")!!, 0.0); assertEquals(.5, n(".5", "")!!, 0.0)
        for (bad in listOf("", "-", "−", ".", "-.", "5-5", "1.2.3", "abc", "--", "1e", "NaN", "Infinity", "1/2"))
            assertNull("'$bad' is not a number", n(bad, ""))
        // Malformed input keeps the last value (null), never throws; typed values clamp to the cap.
        assertNull(PropertyLimits.parseTyped(PropertyLimits.ANIM_OFFSET, "-"))
        assertEquals(-10000f, PropertyLimits.parseTyped(PropertyLimits.ANIM_OFFSET, "-99999")!!, 0f)
        assertEquals(-50f, PropertyLimits.parseTyped(PropertyLimits.ANIM_OFFSET, "50-")!!, 0f)
        assertEquals(-45f, PropertyLimits.parseTyped(PropertyLimits.ANIM_ROTATION, "−45°")!!, 0f)
        assertEquals(-7L, ValueParsing.integer("7-")); assertNull(ValueParsing.integer("1.5"))
    }

    // ---- §9 Animate layout ----------------------------------------------------------------------------------------
    @Test fun swipeThresholdAndGlyph() {
        // Bars: 40% of the width. Tiles: at least 40dp so a slightly diagonal scroll never deletes.
        assertFalse(AnimateSwipe.deletes(100f, 300f, 0f)); assertTrue(AnimateSwipe.deletes(-121f, 300f, 0f))
        assertFalse(AnimateSwipe.deletes(30f, 48f, 40f)); assertTrue(AnimateSwipe.deletes(41f, 48f, 40f))
        assertTrue(AnimateGlyph.FRACTION < .6f)
    }

    @Test fun deletingAllPropertiesIsOneUndoStep() {
        val a = TextAnimator(1, selectors = FrozenList.of(listOf(TextRangeSelector(2))), properties = FrozenList.of(listOf(
            TextAnimatorEdits.newProperty(AnimatorProperty.POSITION), TextAnimatorEdits.newProperty(AnimatorProperty.OPACITY),
            TextAnimatorEdits.newProperty(AnimatorProperty.BLUR))))
        val project = ProjectDocument(Composition(durationUs = 2_000_000, layers = FrozenList.of(listOf(CompositionLayer(1, LayerType.TEXT,
            timing = ClipTiming(durationUs = 2_000_000), text = TextProperties(animators = FrozenList.of(listOf(a))))))))
        val editor = EditorViewModel(); editor.dispatch(EditorAction.Load(project))
        editor.dispatch(TextAnimatorEdits.action(1, a.properties.map { AnimatorEdit.RemoveProperty(1, it.property) }))
        assertTrue(editor.state.project.composition.layers[0].text.animators[0].properties.isEmpty())
        editor.undo()
        assertEquals(project, editor.state.project); assertFalse(editor.canUndo)
        // A single tile swipe removes one property, one undo step.
        editor.dispatch(TextAnimatorEdits.action(1, listOf(AnimatorEdit.RemoveProperty(1, AnimatorProperty.OPACITY))))
        assertEquals(listOf(AnimatorProperty.POSITION, AnimatorProperty.BLUR), editor.state.project.composition.layers[0].text.animators[0].properties.map { it.property })
        editor.undo(); assertEquals(project, editor.state.project)
    }
}
