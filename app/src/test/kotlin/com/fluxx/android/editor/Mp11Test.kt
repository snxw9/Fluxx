package com.fluxx.android.editor

import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test

/** MP1.1 JVM tests: the timeline width / zoom limit, and the media browser's bulk add (Add, Sequence, Stagger). */
class Mp11Test {
    private val composeLimit = 262_143

    @Test fun noTimelineNodeIsWiderThanTheWindowAtAnyLengthOrZoom() {
        // TLV1: the old width-limit test. Only the window (the viewport plus half a viewport each side) is laid out, so no
        // node depends on the composition's length or the zoom, from 1 s to 10 h, at every zoom and density.
        val durations = listOf(1L, 10L, 60L, 300L, 780L, 1800L, 3018L, 3600L, 7200L, 10_800L, 36_000L).map { it * 1_000_000 }
        for (density in listOf(1f, 2f, 2.625f, 2.8125f, 3.5f, 4f)) for (viewport in listOf(720, 1080, 1440, 2560)) for (d in durations) {
            for (chosen in listOf(TimelineZoom.MIN_DP_PER_SECOND, TIMELINE_DEFAULT_DP_PER_SECOND, TimelineZoom.MAX_DP_PER_SECOND, 10_000f)) {
                val v = TimelineViewport(d / 2, TimelineZoom.clamp(chosen), density, viewport, d)
                assertTrue("d=${d / 1_000_000}s density=$density viewport=$viewport chosen=$chosen -> ${v.windowWidthPx} px",
                    v.windowWidthPx <= viewport + 2 * (viewport / 2) && v.windowWidthPx < composeLimit)
            }
        }
    }

    @Test fun theZoomRangeIsTheSameAtEveryLength() {
        for (d in listOf(1_000_000L, 60_000_000L, 3_018_400_000L, 10_800_000_000L)) {
            assertEquals(TimelineZoom.MAX_DP_PER_SECOND, TimelineZoom.clamp(1e9f), 0f)
            assertEquals(TimelineZoom.MIN_DP_PER_SECOND, TimelineZoom.clamp(0f), 0f)
            assertEquals(TIMELINE_DEFAULT_DP_PER_SECOND, TimelineZoom.clamp(TIMELINE_DEFAULT_DP_PER_SECOND), 0f)
            val top = TimelineViewport(d, TimelineZoom.MAX_DP_PER_SECOND, 2.8125f, 1080, d)
            assertEquals(2400f, top.dpPerSecond, 0f)
        }
    }

    @Test fun snxwsCrashLengthsKeepFrameLevelZoom() {
        // The phone's crash lengths (MP1.1 A0): 3018.4 s (the 50-minute import), 1800 s (Sequence of 30 x 60 s), and 3 h.
        // At the top zoom on the A16 (2.8125x, 30 fps) a frame is 225 px wide at every one of them; the window stays 2160 px.
        for (d in listOf(3_018_400_000L, 1_800_000_000L, 798_000_000L, 10_800_000_000L)) {
            val v = TimelineViewport(d, TimelineZoom.MAX_DP_PER_SECOND, 2.8125f, 1080, d)
            assertEquals(225.0, TimelineScale(FrameRate(30, 1), v.pxPerUs).pxPerFrame, 1e-9)
            assertEquals(2160, v.windowWidthPx)
        }
    }

    // ---- Bulk add ------------------------------------------------------------------------------------------------------

    private fun video(i: Int, durationUs: Long) = BulkAdd.Item(AssetReference("a$i", "content://media/external/video/media/$i",
        MediaAccess.AVAILABLE, durationUs), LayerType.VIDEO, "clip $i")
    private fun state(existing: Int = 0): EditorState {
        val layers = (1..existing).map { CompositionLayer(it.toLong(), LayerType.SOLID, zOrder = it, timing = ClipTiming(0, 0, 1_000_000)) }
        return EditorState(ProjectDocument(Composition(frameRate = FrameRate(30, 1), layers = FrozenList.of(layers))), null)
    }

    private fun apply(n: Int, placement: EditorViewModel.BulkPlacement, startUs: Long = 2_000_000, existing: Int = 0): Pair<EditorState, List<Long>> {
        val s0 = state(existing)
        val items = (1..n).map { video(it, (1 + it % 3) * 1_000_000L) }
        val (batch, ids) = BulkAdd.batch(s0, items, startUs, placement)
        assertTrue("one Batch", batch.actions.isNotEmpty())
        return EditorReducer.reduce(s0, batch) to ids
    }

    @Test fun addPutsEveryClipAtThePlayheadInSelectionOrderOnTop() {
        for (n in listOf(3, 30)) {
            val (s, ids) = apply(n, EditorViewModel.BulkPlacement.AT_PLAYHEAD, existing = 2)
            val added = ids.map { id -> s.project.composition.layers.first { it.id == id } }
            assertEquals(n, added.size)
            assertTrue(added.all { it.timing.startUs == 2_000_000L })
            assertEquals((1..n).map { "clip $it" }, added.map { it.name })
            // Stacked above the existing layers, later selections on top.
            assertEquals(added.map { it.zOrder }.sorted(), added.map { it.zOrder })
            assertTrue(added.first().zOrder > 2)
        }
    }

    @Test fun sequencePlacesTheClipsEndToEndFromThePlayhead() {
        for (n in listOf(3, 30)) {
            val (s, ids) = apply(n, EditorViewModel.BulkPlacement.SEQUENCE)
            val added = ids.map { id -> s.project.composition.layers.first { it.id == id } }
            assertEquals(2_000_000L, added.first().timing.startUs)
            for (k in 1 until n) assertEquals("clip $k", added[k - 1].timing.endUs, added[k].timing.startUs)
        }
    }

    @Test fun staggerOffsetsTheStartsAsTheLayerBarDoes() {
        for (n in listOf(3, 30)) {
            val (s, ids) = apply(n, EditorViewModel.BulkPlacement.STAGGER)
            val added = ids.map { id -> s.project.composition.layers.first { it.id == id } }
            // The layer bar's default: Reverse ON, 2 frames: the LAST selected stays at the playhead, earlier ones follow.
            val rate = FrameRate(30, 1)
            val reversed = added.reversed()
            for ((k, l) in reversed.withIndex()) assertEquals(rate.frameTimeUs(rate.nearestFrame(2_000_000) + 2L * k), l.timing.startUs)
            // The same as staggering the plain add with the layer bar's own call.
            val (plain, _) = apply(n, EditorViewModel.BulkPlacement.AT_PLAYHEAD)
            val viaBar = MultiLayerEdits.stagger(plain.project, LayerSelection(ids), MultiLayerEdits.STAGGER_DEFAULT_FRAMES, MultiLayerEdits.STAGGER_REVERSE_DEFAULT)!!
            assertEquals(EditorReducer.reduce(plain, viaBar).project.composition.layers, s.project.composition.layers)
        }
    }

    @Test fun bulkAddIsOneUndoStep() {
        val vm = EditorViewModel()
        vm.dispatch(EditorAction.Load(state().project))
        val (batch, _) = BulkAdd.batch(vm.state, (1..30).map { video(it, 2_000_000) }, 0, EditorViewModel.BulkPlacement.SEQUENCE)
        vm.dispatch(batch)
        assertEquals(30, vm.state.project.composition.layers.size)
        vm.undo()
        assertEquals("one undo removes the whole bulk add", 0, vm.state.project.composition.layers.size)
    }
}
