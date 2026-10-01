package com.fluxx.android.model

import com.fluxx.android.editor.*
import org.junit.Assert.*
import org.junit.Test

class TimelineDurationAndRulerTest {
    private fun layer(id: Long, start: Long, length: Long) = CompositionLayer(id, LayerType.SOLID,
        timing = ClipTiming(start, 0, length))

    @Test fun newCompositionStartsEmptyWithoutAFixedLength() {
        assertNull(CompositionDraft().request().durationUs)
        val comp = ProjectCodec.decode(ProjectCodec.encode(ProjectDocument())).composition
        assertNull(comp.durationUs)
        assertTrue(comp.layers.isEmpty())
        assertEquals(0L, comp.resolvedDurationUs)
        assertEquals(0L, ContextualNavigation.findNext(ProjectDocument(comp), null, false, null, 0))
    }

    @Test fun automaticLengthFollowsAddMoveTrimDeleteAndUndo() {
        val vm = EditorViewModel()
        vm.dispatch(EditorAction.Add(layer(1, 0, 2_000_000)))
        assertNull(vm.state.project.composition.durationUs)
        assertEquals(2_000_000L, vm.state.project.composition.resolvedDurationUs)
        vm.dispatch(EditorAction.Add(layer(2, 1_000_000, 5_000_000)))
        assertEquals(6_000_000L, vm.state.project.composition.resolvedDurationUs)
        vm.dispatch(EditorAction.Move(2, 4_000_000))
        assertEquals(9_000_000L, vm.state.project.composition.resolvedDurationUs)
        vm.dispatch(EditorAction.Trim(2, 4_000_000, 5_000_000))
        assertEquals(5_000_000L, vm.state.project.composition.resolvedDurationUs)
        vm.dispatch(EditorAction.Seek(5_000_000))
        vm.dispatch(EditorAction.Remove(2))
        assertEquals(2_000_000L, vm.state.project.composition.resolvedDurationUs)
        assertEquals(2_000_000L, vm.state.playheadUs)
        vm.undo()
        assertEquals(5_000_000L, vm.state.project.composition.resolvedDurationUs)
        vm.redo()
        vm.dispatch(EditorAction.Remove(1))
        assertEquals(0L, vm.state.project.composition.resolvedDurationUs)
        assertEquals(0L, vm.state.playheadUs)
    }

    @Test fun saveAndReloadPreservesAutomaticModeAndExplicitLegacyLengths() {
        val automatic = ProjectDocument(Composition(layers = FrozenList.of(listOf(layer(1, 0, 2_000_000)))))
        val decoded = ProjectCodec.decode(ProjectCodec.encode(automatic))
        assertNull(decoded.composition.durationUs)
        assertEquals(2_000_000L, decoded.composition.resolvedDurationUs)
        val deleted = EditorReducer.reduce(EditorState(decoded), EditorAction.Remove(1))
        assertEquals(0L, deleted.project.composition.resolvedDurationUs)
        val explicit = automatic.copy(composition = automatic.composition.copy(durationUs = 8_000_000))
        val legacy = ProjectCodec.decode(ProjectCodec.encode(explicit))
        assertEquals(8_000_000L, EditorReducer.reduce(EditorState(legacy), EditorAction.Remove(1)).project.composition.resolvedDurationUs)
    }

    @Test fun rulerStepsAlwaysDivideMajorFramesAndRespectZoom() {
        for (rate in listOf(FrameRate(24), FrameRate(25), FrameRate(30), FrameRate(60), FrameRate(30000, 1001))) {
            for (pixelsPerSecond in listOf(8.0, 120.0, 1200.0)) {
                val pixelsPerUs = pixelsPerSecond / 1_000_000
                val steps = timelineTickSteps(rate, pixelsPerUs, 6.0, 76.0)
                assertTrue(steps.minor > 0)
                assertEquals(0L, steps.major % steps.minor)
                val pixelsPerFrame = pixelsPerUs * 1_000_000 * rate.denominator / rate.numerator
                assertTrue(steps.minor * pixelsPerFrame >= 6)
                assertTrue(steps.major * pixelsPerFrame >= 76)
                for (i in 0L..100L) {
                    val frame = i * steps.minor
                    assertEquals(frame, rate.nearestFrame(rate.frameTimeUs(frame)))
                }
            }
        }
        // Thirty frames at 29.97fps occur at 1.001 seconds, not 1 second.
        assertEquals(1_001_000L, FrameRate(30000, 1001).frameTimeUs(30))
    }

    @Test fun committedTrimAndSwitchToAutomaticCannotRestoreAnOutOfRangePlayhead() {
        val vm = EditorViewModel()
        vm.dispatch(EditorAction.Add(layer(1, 0, 5_000_000)))
        vm.dispatch(EditorAction.Seek(4_000_000))
        vm.beginGesture()
        vm.previewGesture(EditorAction.Trim(1, 0, 2_000_000))
        vm.commitGesture()
        assertEquals(2_000_000L, vm.state.playheadUs)
        assertEquals(2_000_000L, vm.state.project.composition.resolvedDurationUs)
        vm.dispatch(EditorAction.SetComposition(1080, 1920, 10_000_000, FrameRate()))
        vm.dispatch(EditorAction.Seek(9_000_000))
        vm.dispatch(EditorAction.SetComposition(1080, 1920, null, FrameRate()))
        assertEquals(2_000_000L, vm.state.playheadUs)
        assertNull(vm.state.project.composition.durationUs)
    }
}
