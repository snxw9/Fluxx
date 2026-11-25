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
}
