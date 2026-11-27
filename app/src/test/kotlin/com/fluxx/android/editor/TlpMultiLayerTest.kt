package com.fluxx.android.editor

import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test

/** TLP T3/T4: move together, the contextual trim / move set, delete, stagger and sequence (frames, selection order, one undo). */
class TlpMultiLayerTest {
    private val rate = FrameRate(30, 1)
    private fun f(n: Long) = rate.frameTimeUs(n)
    /** Layer i starts at frame 30·i, lasts 60 frames; layer 1 at the top. */
    private fun layers(n: Int) = (1..n).map { i -> CompositionLayer(i.toLong(), LayerType.SOLID, zOrder = n - i,
        timing = ClipTiming(f(30L * i), 0, f(60)), name = "L$i") }
    private fun vm(n: Int = 4) = EditorViewModel().also {
        it.dispatch(EditorAction.Load(ProjectDocument(Composition(durationUs = 60_000_000, frameRate = rate, layers = FrozenList.of(layers(n))))))
    }
    private fun EditorViewModel.start(id: Long) = state.project.composition.layers.first { it.id == id }.timing.startUs
    private fun EditorViewModel.dur(id: Long) = state.project.composition.layers.first { it.id == id }.timing.durationUs

    @Test fun moveTogetherKeepsRelativeOffsetsClampsAtZeroAndIsOneUndoThroughTheGesture() {
        val v = vm(); v.enterSelection(2); v.setSelection(v.layerSelection!!.add(4))
        val base = v.state.project
        // The existing protocol: begin, previews (each from the base), commit.
        v.beginGesture()
        v.previewGesture(MultiLayerEdits.moveTogether(base, v.layerSelection!!, 400_000)!!)
        v.previewGesture(MultiLayerEdits.moveTogether(base, v.layerSelection!!, 1_010_000)!!) // ≈ 30.3 frames → 30
        v.commitGesture()
        assertEquals(f(90), v.start(2)); assertEquals(f(150), v.start(4))
        assertEquals(f(30), v.start(1)); assertEquals(f(90), v.start(3))  // unselected layers untouched
        v.undo(); assertEquals(base, v.state.project)
        // Clamp: the earliest selected clip stops at 0, offsets kept.
        val far = MultiLayerEdits.moveTogether(base, v.layerSelection!!, -100_000_000)!! as EditorAction.Batch
        assertEquals(listOf(EditorAction.Move(2, 0), EditorAction.Move(4, f(60))), far.actions)
        assertNull(MultiLayerEdits.moveTogether(base, v.layerSelection!!, 1_000)) // under half a frame: nothing moves
    }

    @Test fun quickActionsUseThePrimarysSetAndSkipLayersWhereTheyDoNotApply() {
        val v = vm(); v.enterSelection(1); v.setSelection(v.layerSelection!!.add(2).add(3))
        // Playhead at frame 70: inside layers 1 (30–90) and 2 (60–120), before layer 3 (90–150).
        v.dispatch(EditorAction.Seek(f(70)))
        assertEquals(listOf(ClipQuickAction.MOVE_IN, ClipQuickAction.EXTEND_IN), MultiLayerEdits.quickSet(v.state.project, v.layerSelection!!, f(70)))
        v.setSelection(v.layerSelection!!.add(2)) // primary = 2 → trim set
        assertEquals(listOf(ClipQuickAction.TRIM_IN, ClipQuickAction.SPLIT, ClipQuickAction.TRIM_OUT),
            MultiLayerEdits.quickSet(v.state.project, v.layerSelection!!, f(70)))
        val trim = MultiLayerEdits.quick(ClipQuickAction.TRIM_OUT, v.state.project, v.layerSelection!!, f(70))
        assertEquals(1, trim.skipped) // layer 3 has no Trim Out at frame 70
        v.dispatch(trim.action!!)
        assertEquals(f(40), v.dur(1)); assertEquals(f(10), v.dur(2)); assertEquals(f(60), v.dur(3))
        assertTrue(v.canUndo); v.undo(); assertEquals(f(60), v.dur(1)); assertEquals(f(60), v.dur(2))
    }

    @Test fun deleteIsOneUndoAndExitsTheMode() {
        val v = vm(); v.enterSelection(1); v.setSelection(LayerOrder.odd(v.state.project.composition.layers))
        val before = v.state.project
        v.dispatch(MultiLayerEdits.delete(v.layerSelection!!)!!)
        assertEquals(listOf(2L, 4L), v.state.project.composition.layers.map { it.id })
        assertFalse(v.selectionMode)
        v.undo(); assertEquals(before, v.state.project)
    }

    @Test fun staggerUsesSelectionOrderInFramesAndKeepsDurations() {
        val v = vm(); v.enterSelection(3); v.setSelection(v.layerSelection!!.add(1).add(4)) // order 3, 1, 4
        val before = v.state.project
        v.dispatch(MultiLayerEdits.stagger(v.state.project, v.layerSelection!!, 5, reverse = false)!!)
        assertEquals(f(90), v.start(3))          // layer 1 of the order stays
        assertEquals(f(95), v.start(1)); assertEquals(f(100), v.start(4))
        listOf(1L, 3L, 4L).forEach { assertEquals(f(60), v.dur(it)) }
        v.undo(); assertEquals(before, v.state.project)
        // Reverse: 4, 1, 3 → 4 stays at 120.
        v.dispatch(MultiLayerEdits.stagger(v.state.project, v.layerSelection!!, 5, reverse = true)!!)
        assertEquals(f(120), v.start(4)); assertEquals(f(125), v.start(1)); assertEquals(f(130), v.start(3))
        // N = 0 stacks them on the first start.
        v.undo(); v.dispatch(MultiLayerEdits.stagger(v.state.project, v.layerSelection!!, 0, reverse = false)!!)
        assertEquals(f(90), v.start(1)); assertEquals(f(90), v.start(4))
    }

    @Test fun staggerDefaultsToReverseAndSequenceToForward() {
        assertTrue(MultiLayerEdits.STAGGER_REVERSE_DEFAULT); assertFalse(MultiLayerEdits.SEQUENCE_REVERSE_DEFAULT)
        // Order 3, 1, 4 with the default: reversed (4 stays at 120).
        val v = vm(); v.enterSelection(3); v.setSelection(v.layerSelection!!.add(1).add(4))
        v.dispatch(MultiLayerEdits.stagger(v.state.project, v.layerSelection!!, 5)!!)
        assertEquals(f(120), v.start(4)); assertEquals(f(125), v.start(1)); assertEquals(f(130), v.start(3))
    }

    @Test fun sequencePlacesClipsEndToEndWithAGapOrOverlap() {
        val v = vm(); v.enterSelection(2); v.setSelection(v.layerSelection!!.add(1).add(3)) // order 2, 1, 3 (60 frames each)
        v.dispatch(MultiLayerEdits.sequence(v.state.project, v.layerSelection!!)!!)
        assertEquals(f(60), v.start(2)); assertEquals(f(120), v.start(1)); assertEquals(f(180), v.start(3))
        v.undo(); v.dispatch(MultiLayerEdits.sequence(v.state.project, v.layerSelection!!, gapFrames = 10)!!)
        assertEquals(f(130), v.start(1)); assertEquals(f(200), v.start(3))
        v.undo(); v.dispatch(MultiLayerEdits.sequence(v.state.project, v.layerSelection!!, gapFrames = -15)!!)
        assertEquals(f(105), v.start(1)); assertEquals(f(150), v.start(3))
        // A large overlap never goes before 0.
        v.undo(); v.dispatch(MultiLayerEdits.sequence(v.state.project, v.layerSelection!!, gapFrames = -1000)!!)
        assertEquals(0L, v.start(1)); assertEquals(0L, v.start(3))
        // In-points are kept (Move, not Trim).
        assertEquals(0L, v.state.project.composition.layers.first { it.id == 1L }.timing.sourceInUs)
    }

    @Test fun duplicateTimesNAndMultiLayerCopyPasteAreOneUndo() {
        val v = vm(3)
        v.duplicateLayers(listOf(1L, 3L), 3)
        assertEquals(3 + 6, v.state.project.composition.layers.size)
        val order = LayerOrder.timeline(v.state.project.composition.layers).map { it.name }
        // Each source's copies sit directly above it.
        assertEquals(listOf("L1 Copy", "L1 Copy", "L1 Copy", "L1", "L2", "L3 Copy", "L3 Copy", "L3 Copy", "L3"), order)
        v.undo(); assertEquals(3, v.state.project.composition.layers.size)
        // Copy layers 3 and 1, paste at frame 300: the earliest (1 at 30) goes to 300, 3 keeps +60 frames; stacking kept on top.
        assertTrue(v.copyLayers(listOf(3L, 1L))); assertEquals(2, v.copiedLayerCount)
        v.dispatch(EditorAction.Seek(f(300)))
        v.pasteLayer()
        val pasted = LayerOrder.timeline(v.state.project.composition.layers)
        assertEquals(listOf("L1 Copy", "L3 Copy", "L1", "L2", "L3"), pasted.map { it.name })
        assertEquals(f(300), pasted[0].timing.startUs); assertEquals(f(360), pasted[1].timing.startUs)
        v.undo(); assertEquals(3, v.state.project.composition.layers.size)
    }
}
