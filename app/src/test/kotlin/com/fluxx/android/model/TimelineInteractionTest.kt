package com.fluxx.android.model

import com.fluxx.android.editor.*
import com.fluxx.android.ui.timeline.reorderTargetIndex
import com.fluxx.android.ui.timeline.reorderVisualOffset
import com.fluxx.android.ui.timeline.reorderViewportTarget
import com.fluxx.android.ui.timeline.TimelineInspectorAnchorHeight
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class TimelineInteractionTest {
    @Test fun testReorderDisplacementCalculations() {
        val pitch = 36f
        val origin = 3
        val startTop = 112f
        for (movement in listOf(-180f, -54.1f, -18.1f, -17.9f, 0f, 17.9f, 18.1f, 54.1f, 180f)) {
            val target = reorderTargetIndex(origin, movement, pitch, 6)
            val slotTop = startTop + (target - origin) * pitch
            val desired = startTop + movement
            val offset = reorderVisualOffset(desired, slotTop)
            // The slot jumps, but the rendered position must remain exactly under the finger.
            assertEquals(desired, slotTop + offset, .0001f)
            // Scrolling/layout anchoring may move the slot independently of the pointer.
            assertEquals(desired, (slotTop - 12f) + reorderVisualOffset(desired, slotTop - 12f), .0001f)
            // LazyColumn applies content padding at placement, outside item.offset.
            assertEquals(desired, slotTop + 4f + reorderVisualOffset(desired, slotTop, 4f), .0001f)
        }
        assertEquals(0, reorderTargetIndex(origin, -1000f, pitch, 6))
        assertEquals(6, reorderTargetIndex(origin, 1000f, pitch, 6))
        assertEquals(3, reorderTargetIndex(origin, 17.9f, pitch, 6))
        assertEquals(4, reorderTargetIndex(origin, 18.1f, pitch, 6))
    }

    @Test fun testAnchorHeightCalculations() {
        assertEquals(76.dp, TimelineInspectorAnchorHeight)
    }

    @Test fun fastDragTargetsCurrentViewportWithoutWaitingForSiblingAnimation() {
        val pitch = 28f
        // Jump straight from the first slot to second-last in a twelve-layer list.
        assertEquals(10, reorderViewportTarget(284f, 0, 4f, pitch, 11))
        assertEquals(1, reorderViewportTarget(32f, 0, 4f, pitch, 11))
        // Key anchoring or edge scrolling changes which logical slot occupies this viewport.
        assertEquals(10, reorderViewportTarget(228f, 2, 4f, pitch, 11))
        assertEquals(10, reorderViewportTarget(214f, 2, -10f, pitch, 11))
        assertEquals(0, reorderViewportTarget(-100f, 0, 4f, pitch, 11))
        assertEquals(11, reorderViewportTarget(1000f, 0, 4f, pitch, 11))

        val vm = EditorViewModel()
        vm.dispatch(EditorAction.Load(ProjectDocument(Composition(durationUs = 5_000_000,
            layers = FrozenList.of((1L..12L).map { id ->
                CompositionLayer(id, LayerType.SOLID, timing = ClipTiming(0, 0, 5_000_000))
            })))))
        val original = order(vm.state)
        vm.beginGesture()
        vm.previewGesture(EditorAction.ReorderLayer(original.first(), original[7]))
        val releaseTarget = reorderViewportTarget(284f, 0, 4f, pitch, original.lastIndex)
        vm.previewGesture(EditorAction.ReorderLayer(original.first(), original[releaseTarget]))
        vm.commitGesture()
        assertEquals(original.first(), order(vm.state)[10])
        vm.undo()
        assertEquals(original, order(vm.state))
        assertFalse(vm.canUndo)
    }

    private fun project() = ProjectDocument(Composition(durationUs = 5_000_000,
        layers = FrozenList.of((1L..3L).map { id ->
            CompositionLayer(id, LayerType.SOLID, zOrder = 0,
                timing = ClipTiming(0, 0, 5_000_000), name = "Layer $id")
        })))

    private fun order(state: EditorState) = state.project.composition.layers
        .sortedWith(compareByDescending<CompositionLayer> { it.zOrder }.thenByDescending { it.id })
        .map { it.id }

    @Test fun emptyAutomaticTimelineStaysAtZeroWhileExplicitProjectsRetainExternalSeeks() {
        for (document in listOf(project(), ProjectDocument())) {
            val original = EditorState(document)
            val result = EditorReducer.reduce(original, EditorAction.Seek(90_000_000))
            assertEquals(if (document.composition.layers.isEmpty()) 0L else 90_000_000L, result.playheadUs)
            assertSame(document, result.project)
        }
    }

    @Test fun heldMoveIsIndependentOfPlayheadAndPreservesDuration() {
        val document = project()
        val layer = document.composition.layers.first()
        val state = EditorState(document, layer.id, 1_000_000)
        val result = EditorReducer.reduce(state, TimelineEdits.move(document, layer, 45_000_000))
        assertEquals(45_000_000L, result.selectedLayer!!.timing.startUs)
        assertEquals(layer.timing.durationUs, result.selectedLayer!!.timing.durationUs)
        assertEquals(1_000_000L, result.playheadUs)
    }

    @Test fun hierarchyPreviewCancelAndCommitUseOneUndoEntry() {
        val vm = EditorViewModel()
        vm.dispatch(EditorAction.Load(project()))
        vm.dispatch(EditorAction.Select(3))
        val original = vm.state.project
        vm.beginGesture()
        vm.previewGesture(EditorAction.ReorderLayer(3, 1))
        assertEquals(listOf(2L, 1L, 3L), order(vm.displayedState))
        assertSame(original, vm.state.project)
        vm.cancelGesture()
        assertEquals(listOf(3L, 2L, 1L), order(vm.displayedState))
        assertFalse(vm.canUndo)

        vm.beginGesture()
        vm.previewGesture(EditorAction.ReorderLayer(3, 2))
        vm.previewGesture(EditorAction.ReorderLayer(3, 1))
        vm.commitGesture()
        assertEquals(listOf(2L, 1L, 3L), order(vm.state))
        assertEquals(3L, vm.state.selectedLayerId)
        vm.undo()
        assertEquals(original, vm.state.project)
        assertFalse(vm.canUndo)
        vm.redo()
        assertEquals(listOf(2L, 1L, 3L), order(vm.state))
    }

    @Test fun hierarchyMovesUpAndIgnoresMissingOrIdenticalTargets() {
        val original = EditorState(project())
        assertEquals(listOf(1L, 3L, 2L), order(EditorReducer.reduce(original, EditorAction.ReorderLayer(1, 3))))
        assertSame(original, EditorReducer.reduce(original, EditorAction.ReorderLayer(1, 1)))
        assertSame(original, EditorReducer.reduce(original, EditorAction.ReorderLayer(1, 99)))
    }

    @Test fun lateReorderCallbacksCannotReviveAnEndedGestureOrChangeTheNextGesture() {
        val vm = EditorViewModel()
        vm.dispatch(EditorAction.Load(project()))
        val original = vm.state.project
        vm.beginGesture()
        val oldId = requireNotNull(vm.activeGestureId)
        vm.previewGesture(EditorAction.ReorderLayer(3, 1), oldId)
        vm.cancelGesture(oldId)
        // Both legacy and session-aware callbacks can arrive after pointer cancellation.
        vm.previewGesture(EditorAction.ReorderLayer(3, 1))
        vm.previewGesture(EditorAction.ReorderLayer(3, 1), oldId)
        vm.commitGesture(oldId)
        assertSame(original, vm.state.project)
        assertNull(vm.transientState)
        assertFalse(vm.canUndo)

        vm.beginGesture()
        val currentId = requireNotNull(vm.activeGestureId)
        vm.previewGesture(EditorAction.ReorderLayer(1, 3), currentId)
        val currentPreview = vm.transientState
        vm.previewGesture(EditorAction.ReorderLayer(3, 1), oldId)
        vm.cancelGesture(oldId)
        vm.commitGesture(oldId)
        assertSame(currentPreview, vm.transientState)
        assertEquals(currentId, vm.activeGestureId)
        vm.commitGesture(currentId)
        vm.previewGesture(EditorAction.ReorderLayer(3, 1), currentId)
        assertEquals(listOf(1L, 3L, 2L), order(vm.state))
        assertNull(vm.transientState)
        vm.undo()
        assertEquals(original, vm.state.project)
        assertFalse(vm.canUndo)
    }

    @Test fun duplicateIsImmediatelyAboveSourceForEveryPositionAndUsesOneUndoEntry() {
        for (sourceId in 1L..3L) for (tied in listOf(true, false)) {
            val vm = EditorViewModel()
            val base = project()
            vm.dispatch(EditorAction.Load(base.copy(composition = base.composition.copy(
                layers = FrozenList.of(base.composition.layers.map {
                    it.copy(zOrder = if (tied) Int.MAX_VALUE else it.id.toInt() * 10)
                })))))
            vm.dispatch(EditorAction.Select(sourceId))
            val original = vm.state.project
            val before = order(vm.state)
            val source = vm.state.selectedLayer!!
            vm.duplicateLayer(sourceId)
            val duplicate = vm.state.selectedLayer!!
            val expected = before.toMutableList().apply { add(indexOf(sourceId), duplicate.id) }
            assertEquals(expected, order(vm.state))
            assertEquals(source.timing, duplicate.timing)
            assertEquals(source.transform, duplicate.transform)
            assertEquals(source.animTransform, duplicate.animTransform)
            assertNotEquals(source.id, duplicate.id)
            vm.undo()
            assertEquals(original, vm.state.project)
            assertFalse(vm.canUndo)
            vm.redo()
            assertEquals(expected, order(vm.state))
        }
    }

    @Test fun pasteStillInsertsAtTheTopAtThePlayhead() {
        val vm = EditorViewModel()
        vm.dispatch(EditorAction.Load(project()))
        vm.copyLayer(1)
        vm.dispatch(EditorAction.Seek(2_000_000))
        vm.pasteLayer()
        assertEquals(vm.state.selectedLayerId, order(vm.state).first())
        assertEquals(2_000_000L, vm.state.selectedLayer!!.timing.startUs)
    }
}
