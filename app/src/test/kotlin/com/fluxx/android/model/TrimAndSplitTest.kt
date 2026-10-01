package com.fluxx.android.model

import com.fluxx.android.editor.*
import org.junit.Assert.*
import org.junit.Test

class TrimAndSplitTest {
    private fun video(start: Long = 0, duration: Long = 6_000_000, sourceIn: Long = 0) = CompositionLayer(
        id = 7, type = LayerType.VIDEO, timing = ClipTiming(start, sourceIn, duration),
        asset = AssetReference("video", "content://video/7", durationUs = 10_000_000),
        animTransform = AnimatableTransform(
            position = AnimatableProperty2D(true, keyframes = FrozenList.of(listOf(
                Keyframe2D(0, -.8f, -.5f), Keyframe2D(4_000_000, .8f, .5f)))),
            scale = AnimatableProperty2D(true, 1f, 1f, FrozenList.of(listOf(
                Keyframe2D(0, 1f, 1f), Keyframe2D(4_000_000, 2f, 3f)))),
            rotation = AnimatableProperty1D(true, keyframes = FrozenList.of(listOf(
                Keyframe1D(0, 0f), Keyframe1D(2_000_000, 180f), Keyframe1D(4_000_000, 720f)))),
            opacity = AnimatableProperty1D(true, 1f, FrozenList.of(listOf(
                Keyframe1D(0, .2f), Keyframe1D(4_000_000, .8f))))
        )
    )

    private fun state(vararg layers: CompositionLayer) = EditorState(
        ProjectDocument(Composition(durationUs = 20_000_000, layers = FrozenList.of(layers.toList()))),
        selectedLayerId = layers.firstOrNull()?.id)

    private fun quick(state: EditorState, kind: ClipQuickAction, p: Long): EditorState {
        val action = ClipQuickActions.action(kind, requireNotNull(state.selectedLayer), p, state.project.composition.durationUs)
        return if (action == null) state else EditorReducer.reduce(state, action)
    }

    @Test fun firstAndRepeatedInTrimsPreserveExactCompositionValues() {
        val original = video()
        val once = quick(state(original), ClipQuickAction.TRIM_IN, 1_000_000)
        val twice = quick(once, ClipQuickAction.TRIM_IN, 1_500_000).selectedLayer!!
        assertEquals(0L, twice.keyframeAnchorUs)
        for (time in listOf(1_500_000L, 2_000_000L, 2_456_789L, 4_000_000L)) {
            assertEquals(original.evaluatedTransform(time), twice.evaluatedTransform(time))
        }
        assertEquals(1_500_000L, twice.timing.sourceInUs)
        assertEquals(6_000_000L, twice.timing.endUs)
        assertEquals(original.animTransform, twice.animTransform)
    }

    @Test fun moveInPreservesDurationAndSourceWhileTranslatingAnimation() {
        val original = video(3_000_000, 3_000_000, 1_000_000)
        val moved = quick(state(original), ClipQuickAction.MOVE_IN, 1_000_000).selectedLayer!!
        assertEquals(ClipTiming(1_000_000, 1_000_000, 3_000_000), moved.timing)
        assertEquals(1_000_000L, moved.resolvedKeyframeAnchorUs)
        assertEquals(original.evaluatedTransform(4_000_000), moved.evaluatedTransform(2_000_000))
    }

    @Test fun extendInRevealsEarlierSourceAndClampsAtAvailableHandle() {
        val original = video(3_000_000, 3_000_000, 2_000_000)
        val extended = quick(state(original), ClipQuickAction.EXTEND_IN, 1_500_000).selectedLayer!!
        assertEquals(ClipTiming(1_500_000, 500_000, 4_500_000), extended.timing)
        assertEquals(3_000_000L, extended.keyframeAnchorUs)
        assertEquals(original.evaluatedTransform(4_000_000), extended.evaluatedTransform(4_000_000))
        val limited = quick(state(video(3_000_000, 3_000_000, 1_000_000)), ClipQuickAction.EXTEND_IN, 0)
        assertEquals(ClipTiming(2_000_000, 0, 4_000_000), limited.selectedLayer!!.timing)
        assertNull(ClipQuickActions.action(ClipQuickAction.EXTEND_IN, limited.selectedLayer!!, 0, 20_000_000))
    }

    @Test fun moveOutPreservesDurationAndExtendOutRespectsSourceAndCompositionLimits() {
        val original = video(2_000_000, 3_000_000)
        val moved = quick(state(original), ClipQuickAction.MOVE_OUT, 8_000_000).selectedLayer!!
        assertEquals(ClipTiming(5_000_000, 0, 3_000_000), moved.timing)
        assertEquals(original.evaluatedTransform(3_000_000), moved.evaluatedTransform(6_000_000))
        val extended = quick(state(original), ClipQuickAction.EXTEND_OUT, 7_000_000).selectedLayer!!
        assertEquals(ClipTiming(2_000_000, 0, 5_000_000), extended.timing)
        val limited = quick(state(original), ClipQuickAction.EXTEND_OUT, 20_000_000).selectedLayer!!
        assertEquals(12_000_000L, limited.timing.endUs)
        assertNull(ClipQuickActions.action(ClipQuickAction.EXTEND_OUT, limited, 20_000_000, 20_000_000))
        val solid = CompositionLayer(9, LayerType.SOLID, timing = ClipTiming(2_000_000, 0, 3_000_000))
        val action = ClipQuickActions.action(ClipQuickAction.EXTEND_OUT, solid, 10_000_000, 8_000_000) as EditorAction.Trim
        assertEquals(8_000_000L, action.endUs)
    }

    @Test fun splitAfterTrimPreservesEntireOriginalCurveAndNegativeKeys() {
        val original = video()
        val trimmed = quick(state(original), ClipQuickAction.TRIM_IN, 1_000_000)
        val split = quick(trimmed, ClipQuickAction.SPLIT, 2_500_000)
        val right = split.selectedLayer!!
        val left = split.project.composition.layers.first { it.id == original.id }
        assertEquals(ClipTiming(1_000_000, 1_000_000, 1_500_000), left.timing)
        assertEquals(ClipTiming(2_500_000, 2_500_000, 3_500_000), right.timing)
        assertEquals(2_500_000L, right.keyframeAnchorUs)
        assertEquals(-2_500_000L, right.animTransform.position.keyframes.first().timeUs)
        assertEquals(original.animTransform, left.animTransform)
        // Compare the original curve at the SAME timestamp, not two different moving frames.
        for (time in 1_000_000L..5_999_999L step 17_003) {
            val segment = if (time < 2_500_000) left else right
            assertEquals(original.evaluatedTransform(time), segment.evaluatedTransform(time))
        }
        for (time in listOf(2_499_999L, 2_500_000L, 2_500_001L)) {
            assertEquals(original.evaluatedTransform(time), left.evaluatedTransform(time))
            assertEquals(original.evaluatedTransform(time), right.evaluatedTransform(time))
        }
        assertFalse(left.timing.isActive(2_500_000))
        assertTrue(right.timing.isActive(2_500_000))
        val splitAgain = quick(split, ClipQuickAction.SPLIT, 3_500_000).selectedLayer!!
        assertEquals(original.evaluatedTransform(3_750_000), splitAgain.evaluatedTransform(3_750_000))
        val moved = EditorReducer.reduce(state(splitAgain), EditorAction.Move(splitAgain.id, 5_000_000)).selectedLayer!!
        assertEquals(original.evaluatedTransform(3_750_000), moved.evaluatedTransform(5_250_000))
    }

    @Test fun anchorPersistenceIncludesLegacyNullAndExplicitNegativeOne() {
        for (anchor in listOf<Long?>(null, 0, 2_000_000, -1, -2_000_000)) {
            val original = video().copy(keyframeAnchorUs = anchor)
            val decoded = ProjectCodec.decode(ProjectCodec.encode(state(original).project)).composition.layers.single()
            assertEquals(anchor, decoded.keyframeAnchorUs)
            assertEquals(original.evaluatedTransform(2_000_000), decoded.evaluatedTransform(2_000_000))
        }
        val split = quick(state(video()), ClipQuickAction.SPLIT, 2_500_000)
        assertEquals(split.project, ProjectCodec.decode(ProjectCodec.encode(split.project)))
        val trimmed = quick(state(video()), ClipQuickAction.TRIM_IN, 1)
        val moved = EditorReducer.reduce(trimmed, EditorAction.Move(7, 0)).selectedLayer!!
        assertEquals(-1L, moved.keyframeAnchorUs)
        val restored = ProjectCodec.decode(ProjectCodec.encode(state(moved).project)).composition.layers.single()
        assertEquals(moved.evaluatedTransform(500_000), restored.evaluatedTransform(500_000))
    }

    @Test fun splitUndoRedoRestoresDocumentAndSelectionInOneStep() {
        val vm = EditorViewModel()
        vm.dispatch(EditorAction.Load(state(video()).project))
        vm.dispatch(EditorAction.Select(7))
        val before = vm.state
        vm.dispatch(EditorAction.Split(7, 2_000_000))
        val after = vm.state
        assertNotEquals(7L, after.selectedLayerId)
        vm.undo()
        assertEquals(before, vm.state)
        vm.redo()
        assertEquals(after, vm.state)
    }

    @Test fun anchoredCopyPasteAndNumericEditsUseTheSameTimeOrigin() {
        val trimmed = quick(state(video()), ClipQuickAction.TRIM_IN, 1_000_000).selectedLayer!!
        val vm = EditorViewModel()
        vm.dispatch(EditorAction.Load(state(trimmed).project))
        vm.duplicateLayer(7)
        assertEquals(trimmed.keyframeAnchorUs, vm.state.selectedLayer!!.keyframeAnchorUs)
        vm.copyLayer(7)
        vm.dispatch(EditorAction.Seek(10_000_000))
        vm.pasteLayer()
        val pasted = vm.state.selectedLayer!!
        assertEquals(9_000_000L, pasted.keyframeAnchorUs)
        assertEquals(trimmed.evaluatedTransform(2_000_000), pasted.evaluatedTransform(11_000_000))
        vm.dispatch(EditorAction.Seek(11_000_000))
        vm.editTransform(pasted.id, pasted.evaluatedTransform(11_000_000).copy(rotationDegrees = 765f))
        assertEquals(765f, vm.state.selectedLayer!!.animTransform.rotation.keyframes.first { it.timeUs == 2_000_000L }.value, 0f)
    }

    @Test fun exactEndpointCollapseIsInactiveAndSafeToUndo() {
        val vm = EditorViewModel()
        vm.dispatch(EditorAction.Load(state(video()).project))
        vm.dispatch(EditorAction.Select(7))
        val before = vm.state.project
        val action = ClipQuickActions.action(ClipQuickAction.TRIM_IN, vm.state.selectedLayer!!, 6_000_000, 20_000_000)!!
        vm.dispatch(action)
        val collapsed = vm.state.selectedLayer!!
        assertEquals(0L, collapsed.timing.durationUs)
        assertFalse(collapsed.timing.isActive(6_000_000))
        var activeCount = 0
        FramePlan(vm.state.project).forEachActive(6_000_000) { _, _, _, _ -> activeCount++ }
        assertEquals(0, activeCount)
        vm.undo()
        assertEquals(before, vm.state.project)
        assertEquals(listOf(ClipQuickAction.TRIM_IN, ClipQuickAction.TRIM_OUT), ClipQuickActions.available(video(), 0))
        assertEquals(listOf(ClipQuickAction.EXTEND_OUT, ClipQuickAction.MOVE_OUT), ClipQuickActions.available(video(), 6_000_001))
        assertEquals(listOf(ClipQuickAction.TRIM_IN, ClipQuickAction.SPLIT, ClipQuickAction.TRIM_OUT), ClipQuickActions.available(video(), 1))
    }

    @Test fun noOpLimitsAndBoundarySplitsDoNotCreateUndoEntries() {
        val vm = EditorViewModel()
        vm.dispatch(EditorAction.Load(state(video()).project))
        vm.dispatch(EditorAction.Split(7, 0))
        vm.dispatch(EditorAction.Split(7, 6_000_000))
        vm.dispatch(EditorAction.Trim(7, 0, 6_000_000))
        assertFalse(vm.canUndo)
    }

    @Test fun negativeKeyEditsAndTimingFieldsRetainTheResolvedOrigin() {
        val extended = quick(state(video(3_000_000, 3_000_000, 2_000_000)), ClipQuickAction.EXTEND_IN, 1_500_000)
        val vm = EditorViewModel()
        vm.dispatch(EditorAction.Load(extended.project))
        vm.dispatch(EditorAction.Select(7))
        vm.dispatch(EditorAction.Seek(2_000_000))
        val original = vm.state.selectedLayer!!
        vm.editTransform(7, original.evaluatedTransform(2_000_000).copy(rotationDegrees = -90f))
        assertEquals(-1_000_000L, vm.state.selectedLayer!!.animTransform.rotation.keyframes.first().timeUs)
        vm.beginGesture()
        vm.previewGesture(EditorAction.MoveKeyframe(7, AnimPropertyType.ROTATION, -1_000_000, -500_000))
        vm.commitGesture()
        assertEquals(-500_000L, vm.state.selectedLayer!!.animTransform.rotation.keyframes.first().timeUs)
        val timing = vm.state.selectedLayer!!.timing.copy(startUs = 2_500_000)
        vm.dispatch(EditorAction.SetTiming(7, timing))
        assertEquals(4_000_000L, vm.state.selectedLayer!!.resolvedKeyframeAnchorUs)
        vm.dispatch(EditorAction.RemoveKeyframe(7, AnimPropertyType.ROTATION, -500_000))
        assertFalse(vm.state.selectedLayer!!.animTransform.rotation.keyframes.any { it.timeUs < 0 })
    }

    @Test fun imagesAndSolidsExtendWithoutInventingSourceOffsets() {
        for (type in listOf(LayerType.IMAGE, LayerType.SOLID)) {
            val layer = CompositionLayer(7, type, timing = ClipTiming(3_000_000, 0, 3_000_000),
                asset = if (type == LayerType.IMAGE) AssetReference("image", "content://image/1") else null)
            val extended = quick(state(layer), ClipQuickAction.EXTEND_IN, 0).selectedLayer!!
            assertEquals(ClipTiming(0, 0, 6_000_000), extended.timing)
            val split = quick(state(extended), ClipQuickAction.SPLIT, 2_000_000).selectedLayer!!
            assertEquals(0L, split.timing.sourceInUs)
        }
    }

    @Test fun zeroDurationTailTrimAndUnresolvedTimingAreSafe() {
        val original = video(2_000_000, 3_000_000)
        val collapsed = quick(state(original), ClipQuickAction.TRIM_OUT, 2_000_000).selectedLayer!!
        assertEquals(0L, collapsed.timing.durationUs)
        assertFalse(collapsed.timing.isActive(2_000_000))
        val unresolved = original.copy(timing = ClipTiming(2_000_000))
        assertNull(ClipQuickActions.action(ClipQuickAction.SPLIT, unresolved, 3_000_000, 20_000_000))
        assertNull(ClipQuickActions.action(ClipQuickAction.EXTEND_OUT, unresolved, 10_000_000, 20_000_000))
        assertEquals(EditorAction.Move(7, 0), ClipQuickActions.action(ClipQuickAction.MOVE_IN, unresolved, 0, 20_000_000))
    }

    @Test fun splitPreservesRelativeRenderOrderWhenLayerOrdersTie() {
        val original = video()
        val above = CompositionLayer(8, LayerType.SOLID, timing = ClipTiming(durationUs = 6_000_000))
        val split = EditorReducer.reduce(state(original, above), EditorAction.Split(7, 2_000_000))
        assertEquals(listOf(7L, 8L), FrameEvaluator.evaluate(split.project, 1_000_000).layers.map { it.id })
        assertEquals(listOf(split.selectedLayerId, 8L), FrameEvaluator.evaluate(split.project, 3_000_000).layers.map { it.id })
    }
}
