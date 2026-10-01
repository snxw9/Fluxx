package com.fluxx.android.model

import com.fluxx.android.editor.EditorAction
import com.fluxx.android.editor.EditorViewModel
import com.google.flatbuffers.FlatBufferBuilder
import org.junit.Assert.*
import org.junit.Test
import fluxx.schema.Composition as SComp
import fluxx.schema.Project as SProject

class CompositionTimecodeTest {
    @Test fun frameClockRoundTripsIncludingFractionalAndHighFrameRates() {
        for (rate in listOf(FrameRate(12), FrameRate(30), FrameRate(30000, 1001), FrameRate(120))) {
            for (frame in listOf(0L, 1L, 59L, 119L, 1801L, 108001L)) {
                val time = rate.frameTimeUs(frame)
                assertEquals(time, CompositionTimecode.parse(CompositionTimecode.format(time, rate), rate))
            }
        }
        assertNull(CompositionTimecode.parse("00:60:00", FrameRate()))
        assertNull(CompositionTimecode.parse("00:00:30", FrameRate()))
        assertNull(CompositionTimecode.parse("-1:00:00", FrameRate()))
        assertNull(CompositionTimecode.parse("1.5", FrameRate()))
        assertNull(CompositionTimecode.parse("9999999999999999:00:00", FrameRate()))
    }

    @Test fun singleFrameRateInputRetainsStandardFractionalRatesAndAcceptsNewPresets() {
        for (fps in listOf(12, 14, 18, 20, 120)) {
            assertEquals(FrameRate(fps), CompositionTimecode.parseFrameRate(fps.toString()))
            CompositionDraft(fps = fps).request().validate()
        }
        for (rate in listOf(FrameRate(24000, 1001), FrameRate(30000, 1001), FrameRate(60000, 1001))) {
            assertEquals(rate, CompositionTimecode.parseFrameRate(CompositionTimecode.frameRateLabel(rate)))
        }
        assertEquals(FrameRate(25, 2), CompositionTimecode.parseFrameRate("12.5"))
        assertNull(CompositionTimecode.parseFrameRate("0"))
        assertNull(CompositionTimecode.parseFrameRate("121"))
        assertNull(CompositionTimecode.parseFrameRate("NaN"))
    }

    @Test fun displayOriginPersistsAndUndoesWithoutMovingContentOrChangingAutomaticDuration() {
        val layer = CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(1_000_000, 0, 4_000_000),
            markers = FrozenList.of(listOf(Marker(1, 500_000))))
        val original = ProjectDocument(Composition(layers = FrozenList.of(listOf(layer)),
            markers = FrozenList.of(listOf(Marker(1, 2_000_000)))))
        val vm = EditorViewModel()
        vm.dispatch(EditorAction.Load(original))
        vm.dispatch(EditorAction.Seek(2_000_000))
        vm.dispatch(EditorAction.SetComposition(1080, 1920, null, FrameRate(), 60_000_000))
        val updated = vm.state.project
        assertEquals(original.composition.layers, updated.composition.layers)
        assertEquals(original.composition.markers, updated.composition.markers)
        assertNull(updated.composition.durationUs)
        assertEquals(5_000_000L, updated.composition.resolvedDurationUs)
        assertEquals(65_000_000L, updated.composition.endTimecodeUs)
        assertEquals(2_000_000L, vm.state.playheadUs)
        assertEquals(updated, ProjectCodec.decode(ProjectCodec.encode(updated)))
        vm.undo()
        assertEquals(original, vm.state.project)
        vm.redo()
        assertEquals(updated, vm.state.project)
    }

    @Test fun legacyDocumentsWithoutDisplayOriginStillStartAtZero() {
        for (version in 1..7) {
            val builder = FlatBufferBuilder(128)
            SComp.startComposition(builder)
            val comp = SComp.endComposition(builder)
            val project = SProject.createProject(builder, version, comp)
            builder.finish(project)
            assertEquals(0L, ProjectCodec.decode(builder.sizedByteArray()).composition.startTimecodeUs)
        }
    }
}
