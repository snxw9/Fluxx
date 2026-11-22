package com.fluxx.android.ui.inspector

import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import com.fluxx.android.ui.timeline.MarkerEditing
import org.junit.Assert.*
import org.junit.Test

/** TEXT F3.3 UI state: popover exits, Tracking slider range, "+ Animator" visibility, sheet swipe on every page, markers. */
class TextF33UiStateTest {
    // ---- §1 leaving a slider popover -------------------------------------------------------------------------------
    @Test fun eachDismissPathClosesAndTimelineTransportRailDoNot() {
        val closes = setOf(PopoverDismissal.Touch.PREVIEW, PopoverDismissal.Touch.OTHER_TILE, PopoverDismissal.Touch.SAME_TILE,
            PopoverDismissal.Touch.EMPTY_SHEET, PopoverDismissal.Touch.SWIPE_DOWN, PopoverDismissal.Touch.BACK)
        for (t in PopoverDismissal.Touch.entries) assertEquals(t.name, t in closes, PopoverDismissal.dismisses(t))
        for (t in listOf(PopoverDismissal.Touch.TIMELINE, PopoverDismissal.Touch.TRANSPORT, PopoverDismissal.Touch.RAIL, PopoverDismissal.Touch.SLIDER))
            assertFalse("$t keeps the popover open", PopoverDismissal.dismisses(t))
    }

    @Test fun sameTileTogglesAndAnotherTileSwaps() {
        assertNull(PopoverToggle.next("p:POSITION", "p:POSITION"))           // the open tile again: close
        assertEquals("p:OPACITY", PopoverToggle.next("p:POSITION", "p:OPACITY")) // another tile: swap
        assertEquals("p:POSITION", PopoverToggle.next(null, "p:POSITION"))   // nothing open: open
    }

    @Test fun emptySheetTapRequestsDismissalOnlyForUnclaimedTaps() {
        assertTrue(PopoverDismissal.emptyTap(downConsumed = false, endedAsTap = true))
        assertFalse("a tile took the down", PopoverDismissal.emptyTap(downConsumed = true, endedAsTap = true))
        assertFalse("a scroll / drag is not a tap", PopoverDismissal.emptyTap(downConsumed = false, endedAsTap = false))
        // A request is the tick open non-modal popovers wait for.
        val before = PopoverDismissal.requests.value; PopoverDismissal.request(); assertEquals(before + 1, PopoverDismissal.requests.value)
    }

    @Test fun swipeDownIsVerticalAndDownwardOnly() {
        assertTrue(InspectorSwipe.isSwipeDown(2f, 20f))
        assertFalse("upwards", InspectorSwipe.isSwipeDown(0f, -20f))
        assertFalse("sideways (slider / tile swipe)", InspectorSwipe.isSwipeDown(20f, 10f))
        assertEquals(48f, InspectorSwipe.THRESHOLD_DP, 0f)
    }

    // ---- §2 Tracking slider range ----------------------------------------------------------------------------------
    @Test fun trackingSliderSpansItsHardLimit() {
        val l = PropertyLimits.ANIM_TRACKING
        assertEquals(-1000f to 1000f, l.sliderMin to l.sliderMax)
        assertEquals(l.hardMin to l.hardMax, l.sliderMin to l.sliderMax)
        assertEquals(-1000f to 1000f, l.typedMin to l.typedMax); assertEquals(1000f, l.typed(5000f), 0f)
        assertEquals(1f, l.fraction(1000f), 0f); assertEquals(0f, l.fraction(-1000f), 0f); assertEquals(.5f, l.fraction(0f), 1e-6f)
        // Other caps unchanged (the Character tab keeps its own tracking slider).
        assertEquals(-100f to 100f, PropertyLimits.TRACKING.sliderMin to PropertyLimits.TRACKING.sliderMax)
        assertEquals(-500f to 500f, PropertyLimits.ANIM_OFFSET.sliderMin to PropertyLimits.ANIM_OFFSET.sliderMax)
    }

    // ---- §3 "+ Animator" visibility ---------------------------------------------------------------------------------
    @Test fun addAnimatorShowsOnlyWhileEveryBarIsCollapsed() {
        val a = TextAnimator(1, selectors = FrozenList.of(listOf(TextRangeSelector(2))))
        val b = TextAnimator(3, selectors = FrozenList.of(listOf(TextRangeSelector(4))))
        val text = TextProperties(animators = FrozenList.of(listOf(a, b)))
        assertEquals("add", AnimateRows.build(text, AnimateBars(), 0, 6).last().key)
        for (open in listOf(a.id, b.id)) assertTrue(AnimateRows.build(text, AnimateBars().toggleAnimator(open), 0, 6).none { it == AnimateRow.Add })
        assertEquals("add", AnimateRows.build(text, AnimateBars().toggleAnimator(a.id).toggleAnimator(a.id), 0, 6).last().key)
        // Empty state: the add bar stays the first (only) row.
        assertEquals(listOf("add"), AnimateRows.build(TextProperties(), AnimateBars(), 0, 6).map { it.key })
    }

    // ---- §6 swipe down on every page --------------------------------------------------------------------------------
    @Test fun everyInspectorPageHasTheSheetSwipe() {
        // Landing (null) and every section, including Edit Text (CONTEXTUAL_EDIT: Character / Paragraph / Animate / Fonts).
        for (page in listOf<InspectorSection?>(null) + InspectorSection.entries) {
            assertTrue("$page", InspectorSwipe.enabled(page, textTyping = false))
            assertFalse("$page while typing the source", InspectorSwipe.enabled(page, textTyping = true))
        }
        // The band covers the frame and one header row, and grows with the font scale.
        assertEquals(8f + InspectorChromeBudget.header(1f), InspectorSwipe.band(1f), 0f)
        assertTrue(InspectorSwipe.band(1.3f) > InspectorSwipe.band(1f))
    }

    // ---- §7 marker add keeps the inspector open -------------------------------------------------------------------------
    @Test fun addingOrSelectingAMarkerKeepsTheSelection() {
        val layer = CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(0, 0, 4_000_000))
        val other = CompositionLayer(2, LayerType.SOLID, timing = ClipTiming(0, 0, 4_000_000))
        var state = EditorState(project = ProjectDocument(Composition(durationUs = 4_000_000, layers = FrozenList.of(listOf(layer, other)))))
        state = EditorReducer.reduce(state, EditorAction.Select(1))
        state = EditorReducer.reduce(state, EditorAction.Seek(1_000_000))
        // The playhead tap with a layer selected creates a layer marker...
        val create = MarkerEditing.playhead(state.project, state.selectedLayer, state.playheadUs, false, MarkerEditing.LAYER_DEFAULT)
            as MarkerEditing.PlayheadResult.Create
        state = EditorReducer.reduce(state, create.action)
        // ...and selecting it keeps the same layer (so the inspector, its page and focus stay).
        assertEquals(1L, MarkerEditing.selectionAfterMarker(state.selectedLayerId, create.ref.layerId))
        state = EditorReducer.reduce(state, EditorAction.SelectMarker(create.marker.id))
        assertEquals(1L, state.selectedLayerId); assertEquals(create.marker.id, state.selectedMarkerId)
        // A composition marker keeps the selection; only another layer's marker moves it there.
        assertEquals(1L, MarkerEditing.selectionAfterMarker(1L, null))
        assertEquals(2L, MarkerEditing.selectionAfterMarker(1L, 2L))
        assertNull(MarkerEditing.selectionAfterMarker(null, null))
        // Editing and deleting a marker never change the selection either.
        state = EditorReducer.reduce(state, MarkerEditing.edit(create.ref, 0xff00ff00.toInt(), "Beat"))
        assertEquals(1L, state.selectedLayerId)
        state = EditorReducer.reduce(state, EditorAction.DeleteLayerMarker(1, create.marker.id))
        assertEquals(1L, state.selectedLayerId)
    }
}
