package com.fluxx.android.render

import com.fluxx.android.editor.EditorAction
import com.fluxx.android.editor.EditorViewModel
import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test

/** TLP T2: a hidden layer behaves as if it did not exist (nothing prepared, drawn, evaluated or exported); delayed release. */
class HiddenLayerTest {
    private fun video(id: Long, uri: String, visible: Boolean = true) = CompositionLayer(id, LayerType.VIDEO, zOrder = id.toInt(),
        timing = ClipTiming(0, 0, 4_000_000), asset = AssetReference("a$id", uri, MediaAccess.AVAILABLE, 10_000_000), visible = visible)
    private fun text(id: Long, visible: Boolean) = CompositionLayer(id, LayerType.TEXT, zOrder = id.toInt(), timing = ClipTiming(0, 0, 4_000_000),
        text = TextProperties(source = AnimatableString(staticValue = "T$id")), visible = visible)
    private fun animatedSolid(id: Long, visible: Boolean) = CompositionLayer(id, LayerType.SOLID, zOrder = id.toInt(), timing = ClipTiming(0, 0, 4_000_000),
        solidColorArgb = 0xff112233.toInt() + id.toInt(), visible = visible,
        animTransform = AnimatableTransform(opacity = AnimatableProperty1D(true, 1f, FrozenList.of(listOf(Keyframe1D(0, 0f), Keyframe1D(1_000_000, 1f))))))
    private fun project(vararg l: CompositionLayer) = ProjectDocument(Composition(durationUs = 4_000_000, layers = FrozenList.of(l.toList())))

    @Test fun hiddenLayersYieldNoDrawItemsNoEvaluationAndNoPreparation() {
        val p = project(animatedSolid(1, true), animatedSolid(2, false), text(3, true), text(4, false), video(5, "content://v/5", false))
        // Draw list (preview and export share FramePlan / FrameEvaluator): the hidden layers are never visited, so their
        // keyframes are never evaluated.
        val visited = ArrayList<Long>()
        FramePlan(p).forEachActive(500_000) { layer, _, _, _ -> visited += layer.id }
        assertEquals(listOf(1L, 3L), visited)
        assertEquals(listOf(1L, 3L), FrameEvaluator.evaluate(p, 500_000).layers.map { it.id })
        // Text preparation (shaping, layouts, glyph uploads): visible text only.
        assertEquals(listOf(3L), RenderLayers.preparedText(p.composition.layers).map { it.id })
        // Media keys: the hidden video's decoder and the hidden solid's texture are not wanted.
        assertEquals(emptySet<String>(), RenderLayers.wantedDecoders(p))
        assertEquals(setOf("solid:${0xff112233.toInt() + 1}"), RenderLayers.wantedRasters(p))
        // A source shared with a visible layer stays wanted.
        assertEquals(setOf("content://v/5"), RenderLayers.wantedDecoders(project(video(5, "content://v/5", false), video(6, "content://v/5", true))))
    }

    @Test fun exportExcludesHiddenLayersAndTheirAudio() {
        val p = project(video(1, "content://v/1", true), video(2, "content://v/2", false))
        // Export renders through FramePlan and mixes audio from visible layers only; headroom ignores the hidden track.
        val gains = ArrayList<Long>()
        FramePlan(p).forEachActive(1_000_000) { layer, _, _, _ -> gains += layer.id }
        assertEquals(listOf(1L), gains)
        assertEquals(1f, AudioMixPolicy.headroom(p), 0f)
        assertEquals(.5f, AudioMixPolicy.headroom(project(video(1, "content://v/1", true), video(2, "content://v/2", true))), 0f)
    }

    @Test fun hiddenLayersStayInTheDocumentUndoAndTheSavedFile() {
        val v = EditorViewModel()
        v.dispatch(EditorAction.Load(project(animatedSolid(1, true))))
        v.dispatch(EditorAction.SetVisibility(1, false))
        assertEquals(1, v.state.project.composition.layers.size)
        val bytes = ProjectCodec.encode(v.state.project)
        assertFalse(ProjectCodec.decode(bytes).composition.layers.single().visible)
        v.undo(); assertTrue(v.state.project.composition.layers.single().visible)
        // Still selectable and editable from the timeline while hidden.
        v.redo(); v.dispatch(EditorAction.Select(1)); v.dispatch(EditorAction.Rename(1, "Hidden"))
        assertEquals("Hidden", v.state.project.composition.layers.single().name)
    }

    @Test fun releaseWaitsForTheDelayAndAQuickToggleKeepsTheResource() {
        var now = 10_000L
        val r = DelayedRelease<String>(3_000L) { now }
        val held = listOf("v1", "v2")
        // v2 just became hidden: nothing is released yet.
        assertEquals(emptyList<String>(), r.due(held, setOf("v1")))
        now += 2_999; assertEquals(emptyList<String>(), r.due(held, setOf("v1")))
        // Back on before 3 s: kept, and the clock restarts on the next hide.
        now += 1; assertEquals(emptyList<String>(), r.due(held, setOf("v1", "v2"))); assertTrue(r.pending.isEmpty())
        assertEquals(emptyList<String>(), r.due(held, setOf("v1")))
        now += 3_000; assertEquals(listOf("v2"), r.due(held, setOf("v1")))
        // Once released it is not held any more; re-acquiring is the renderer's on-demand open.
        assertEquals(emptyList<String>(), r.due(listOf("v1"), setOf("v1"))); assertTrue(r.pending.isEmpty())
        assertEquals(3_000L, DelayedRelease.DEFAULT_DELAY_MS)
    }
}
