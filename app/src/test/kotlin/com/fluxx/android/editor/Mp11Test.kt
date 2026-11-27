package com.fluxx.android.editor

import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test

/** MP1.1 JVM tests: the timeline width / zoom limit, and the media browser's bulk add (Add, Sequence, Stagger). */
class Mp11Test {
    private val composeLimit = 262_143

    @Test fun timelineContentNeverExceedsTheLimitFromOneSecondToThreeHours() {
        val durations = listOf(1L, 10L, 60L, 300L, 780L, 1800L, 3018L, 3600L, 7200L, 10_800L).map { it * 1_000_000 }
        for (density in listOf(1f, 2f, 2.625f, 2.8125f, 3.5f, 4f)) for (viewport in listOf(720, 1080, 1440, 2560)) for (d in durations) {
            val ceiling = TimelineZoom.ceiling(d, density, viewport); val floor = TimelineZoom.floor(d, density, viewport)
            assertTrue(floor > 0f && floor <= ceiling)
            for (chosen in listOf(TimelineZoom.MIN_DP_PER_SECOND, TIMELINE_DEFAULT_DP_PER_SECOND, TimelineZoom.MAX_DP_PER_SECOND, 10_000f)) {
                val used = TimelineZoom.clamp(chosen, d, density, viewport)
                val px = TimelineZoom.contentPx(d, used, density, viewport)
                assertTrue("d=${d / 1_000_000}s density=$density viewport=$viewport chosen=$chosen -> $px px",
                    px <= TimelineZoom.MAX_CONTENT_PX + 1 && px < composeLimit)
            }
        }
    }

    @Test fun shortCompositionsKeepTheirFullZoomRange() {
        // 1 min at 2.8x: the full 0.5 .. 2400 dp/s range would be 405,000 px at 2400, so only the top is capped; 1 s keeps all.
        assertEquals(TimelineZoom.MAX_DP_PER_SECOND, TimelineZoom.ceiling(1_000_000, 2.8125f, 1080), 0f)
        assertEquals(TimelineZoom.MIN_DP_PER_SECOND, TimelineZoom.floor(60_000_000, 2.8125f, 1080), 0f)
        assertEquals(TIMELINE_DEFAULT_DP_PER_SECOND, TimelineZoom.clamp(TIMELINE_DEFAULT_DP_PER_SECOND, 60_000_000, 2.8125f, 1080), 0f)
    }

    @Test fun snxwsCrashWidthsNowFit() {
        // The phone's crash widths (A0): 3018.4 s (the 50-minute import) and 1800 s (Sequence of 30 x 60 s), 2.8125x, 1080 px.
        for (d in listOf(3_018_400_000L, 1_800_000_000L, 798_000_000L)) {
            val before = TimelineZoom.contentPx(d, TIMELINE_DEFAULT_DP_PER_SECOND, 2.8125f, 1080)
            assertTrue("$before", before > composeLimit)
            val after = TimelineZoom.contentPx(d, TimelineZoom.clamp(TIMELINE_DEFAULT_DP_PER_SECOND, d, 2.8125f, 1080), 2.8125f, 1080)
            assertTrue("$after", after <= TimelineZoom.MAX_CONTENT_PX + 1)
        }
        // About 40 h at 2.8x: even the usual floor would overflow, so the floor drops below it.
        val veryLong = 40L * 3600 * 1_000_000
        assertTrue(TimelineZoom.floor(veryLong, 2.8125f, 1080) < TimelineZoom.MIN_DP_PER_SECOND)
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
