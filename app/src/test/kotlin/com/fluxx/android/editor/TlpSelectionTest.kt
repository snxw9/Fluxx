package com.fluxx.android.editor

import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test

/** TLP T0/T1: the ordered selection, the D-button rules, bulk visibility, and selection outside undo history. */
class TlpSelectionTest {
    /** Layers 1..n, layer 1 at the top (highest zOrder). */
    private fun layers(n: Int) = (1..n).map { i -> CompositionLayer(i.toLong(), LayerType.SOLID, zOrder = n - i,
        timing = ClipTiming(i * 1_000_000L, 0, 2_000_000), name = "L$i") }
    private fun vm(n: Int = 5) = EditorViewModel().also {
        it.dispatch(EditorAction.Load(ProjectDocument(Composition(durationUs = 20_000_000, layers = FrozenList.of(layers(n))))))
    }

    @Test fun selectionIsOrderedAndThePrimaryIsTheLastSelected() {
        var s = LayerSelection.of(3)
        s = s.add(1).add(5)
        assertEquals(listOf(3L, 1L, 5L), s.ids); assertEquals(5L, s.primary)
        s = s.add(3) // re-adding moves it to the end
        assertEquals(listOf(1L, 5L, 3L), s.ids); assertEquals(3L, s.primary)
        assertEquals(listOf(1L, 3L), s.remove(5).ids)
        assertEquals(listOf(5L, 3L), s.toggle(1).ids)
        assertEquals(listOf(1L, 3L), s.prune(listOf(1L, 3L, 9L)).ids)
    }

    @Test fun oddEvenSelectAllAndInvertCountFromTheTopInTimelineOrder() {
        val l = layers(5).shuffled(java.util.Random(4))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), LayerOrder.all(l).ids)
        assertEquals(listOf(1L, 3L, 5L), LayerOrder.odd(l).ids)
        assertEquals(listOf(2L, 4L), LayerOrder.even(l).ids)
        assertEquals(listOf(1L, 4L, 5L), LayerOrder.invert(l, LayerSelection.of(3, 2)).ids)
    }

    @Test fun dButtonRules() {
        // Outside selection mode.
        assertEquals(DEffect.TOGGLE_VISIBILITY, DButtonRules.tap(false, false, DZone.EYE))
        assertEquals(DEffect.NONE, DButtonRules.tap(false, false, DZone.ARROW))          // the arrow is disabled
        assertEquals(DEffect.ENTER, DButtonRules.longPress(false, false))
        assertNull(DButtonRules.body(false, false)); assertNull(DButtonRules.empty(false)) // existing behaviour
        // In selection mode.
        assertEquals(DEffect.ADD, DButtonRules.tap(true, false, DZone.EYE))
        assertEquals(DEffect.ADD, DButtonRules.tap(true, false, DZone.ARROW))
        assertEquals(DEffect.BULK_VISIBILITY, DButtonRules.tap(true, true, DZone.EYE))
        assertEquals(DEffect.NONE, DButtonRules.tap(true, true, DZone.ARROW))           // bulk expand: TL3
        assertEquals(DEffect.REMOVE, DButtonRules.longPress(true, true))
        assertEquals(DEffect.ADD, DButtonRules.body(true, false)); assertEquals(DEffect.REMOVE, DButtonRules.body(true, true))
        assertEquals(DEffect.EXIT, DButtonRules.empty(true))
        assertNull(DButtonRules.afterRemove(LayerSelection.of(2), 2))                    // the last one exits
        assertEquals(listOf(1L), DButtonRules.afterRemove(LayerSelection.of(1, 2), 2)!!.ids)
    }

    @Test fun bulkVisibilityIsTheInverseOfTheTappedLayerAndOneUndo() {
        val v = vm()
        v.dispatch(EditorAction.SetVisibility(2, false))
        v.enterSelection(1); v.setSelection(v.layerSelection!!.add(2).add(3))
        val before = v.state.project
        // Layer 2 is hidden: tapping its eye shows all three.
        v.dispatch(MultiLayerEdits.visibility(v.state.project, v.layerSelection!!, 2)!!)
        assertTrue(v.state.project.composition.layers.filter { it.id in listOf(1L, 2L, 3L) }.all { it.visible })
        v.undo(); assertEquals(before, v.state.project)
        // Layer 1 is visible: tapping its eye hides all three.
        v.dispatch(MultiLayerEdits.visibility(v.state.project, v.layerSelection!!, 1)!!)
        assertTrue(v.state.project.composition.layers.filter { it.id in listOf(1L, 2L, 3L) }.none { it.visible })
        assertTrue(v.state.project.composition.layers.filter { it.id !in listOf(1L, 2L, 3L) }.all { it.visible })
    }

    @Test fun selectionIsNotInUndoHistoryAndIsPrunedAfterChanges() {
        val v = vm()
        assertFalse(v.selectionMode); assertFalse(v.canUndo)
        v.enterSelection(2)
        assertTrue(v.selectionMode); assertEquals(2L, v.state.selectedLayerId)
        v.setSelection(v.layerSelection!!.add(4))
        assertEquals(4L, v.state.selectedLayerId)   // the single selection mirrors the primary
        assertFalse("selecting is not an undoable edit", v.canUndo)
        // Deleting a selected layer prunes it; undo brings the layer back but not into the selection.
        v.dispatch(EditorAction.Remove(4))
        assertEquals(listOf(2L), v.layerSelection!!.ids); assertEquals(2L, v.state.selectedLayerId)
        v.undo()
        assertEquals(listOf(2L), v.layerSelection!!.ids)
        // Deleting the last selected layer exits the mode.
        v.dispatch(EditorAction.Remove(2)); assertFalse(v.selectionMode)
        v.enterSelection(1); v.exitSelection(); assertFalse(v.selectionMode)
        // Loading a project exits the mode.
        v.enterSelection(1); v.dispatch(EditorAction.Load(ProjectDocument())); assertFalse(v.selectionMode)
    }
}
