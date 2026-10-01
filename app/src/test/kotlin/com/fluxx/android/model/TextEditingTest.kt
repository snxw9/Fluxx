package com.fluxx.android.model

import com.fluxx.android.editor.*
import com.fluxx.android.render.LayerGeometry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TextEditingTest {
    private fun metrics(source: String) = TextLayoutMetrics.fromNative(doubleArrayOf(
        1000.0, 800.0, -200.0, 0.0, 1.0, 0.0, source.length * 600.0), source.isEmpty())
    private fun editor(animated: Boolean = false): EditorViewModel = EditorViewModel().also {
        val source = if (animated) AnimatableString(true, "unused", FrozenList.of(listOf(
            StringKeyframe(0, "first"), StringKeyframe(900_000, "last")))) else AnimatableString(staticValue = "original")
        it.dispatch(EditorAction.Load(ProjectDocument(Composition(layers = FrozenList.of(listOf(
            CompositionLayer(1, LayerType.TEXT, timing = ClipTiming(durationUs = 2_000_000), text = TextProperties(source = source))))))))
        it.dispatch(EditorAction.Select(1)); it.dispatch(EditorAction.Seek(500_000))
    }
    private suspend fun CoroutineScope.drain() { coroutineContext[Job]!!.children.toList().joinAll() }

    @Test fun allDraftsCommitAsOneUndoAndRedoRestoresAtomicGeometry() = runBlocking {
        val editor = editor(); val original = editor.state.project
        val controller = TextEditController(editor, this, { _, source -> metrics(source) })
        controller.begin(1); controller.source("a"); controller.source("final draft"); controller.finish()
        drain()
        assertEquals("final draft", editor.state.selectedLayer!!.text.source.staticValue)
        val committed = editor.state.project
        assertTrue(editor.canUndo); editor.undo(); assertEquals(original, editor.state.project)
        assertFalse(editor.canUndo); editor.redo(); assertEquals(committed, editor.state.project)
    }
    @Test fun unchangedDraftCreatesNoKeyOrUndoAndCancelRemovesDraftKey() = runBlocking {
        val editor = editor(true); val original = editor.state.project
        val controller = TextEditController(editor, this, { _, source -> metrics(source) })
        controller.begin(1); controller.source("first"); controller.finish(); drain()
        assertEquals(original, editor.state.project); assertFalse(editor.canUndo)
        controller.begin(1); controller.source("draft"); drain()
        assertEquals(3, editor.displayedState.selectedLayer!!.text.source.keyframes.size)
        controller.cancel()
        assertEquals(original, editor.displayedState.project); assertFalse(editor.canUndo)
    }
    @Test fun keyedTypingCapturesItsTimeAndNavigationWaitsForLatestMetrics() = runBlocking {
        val editor = editor(true)
        val latestReady = CompletableDeferred<Unit>()
        val controller = TextEditController(editor, this, { _, source ->
            if (source == "latest") latestReady.await(); metrics(source)
        })
        controller.begin(1); controller.source("obsolete"); yield(); controller.source("latest")
        controller.finish { editor.dispatch(EditorAction.Seek(1_000_000)) }
        yield(); assertEquals(500_000L, editor.state.playheadUs)
        latestReady.complete(Unit); drain()
        val keys = editor.state.selectedLayer!!.text.source.keyframes
        assertEquals(listOf(0L, 500_000L, 900_000L), keys.map { it.timeUs })
        assertEquals("latest", keys[1].value); assertEquals(1_000_000L, editor.state.playheadUs)
        assertEquals(Transform(), editor.state.selectedLayer!!.transform)
    }
    @Test fun cancelledWorkerCompletionCannotReviveOldSession() = runBlocking {
        val editor = editor(); val original = editor.state.project
        val ready = CompletableDeferred<Unit>()
        val controller = TextEditController(editor, this, { _, source ->
            if (source == "stale") withContext(NonCancellable) { ready.await() }; metrics(source)
        })
        controller.begin(1); controller.source("stale"); yield(); controller.cancel()
        controller.begin(1); controller.source("current"); controller.finish()
        ready.complete(Unit); drain()
        assertEquals("current", editor.state.selectedLayer!!.text.source.staticValue)
        editor.undo(); assertEquals(original, editor.state.project); assertFalse(editor.canUndo)
    }
    @Test fun invalidFinalDraftRollsBackInsteadOfCommittingEarlierPreview() = runBlocking {
        val editor = editor(); val original = editor.state.project
        val controller = TextEditController(editor, this, { _, source -> metrics(source) })
        controller.begin(1); controller.source("valid preview"); drain(); controller.source("\ud800")
        controller.finish(); drain()
        assertEquals(original, editor.state.project); assertFalse(editor.canUndo)
    }
    @Test fun styleChangeFinishesTypingBeforeMergingAndFreezesNoOtherTracks() = runBlocking {
        val editor = editor()
        val ready = CompletableDeferred<Unit>()
        val controller = TextEditController(editor, this, { _, source ->
            if (source == "latest") ready.await(); metrics(source)
        })
        val oldStyle = editor.state.selectedLayer!!.text.copy(fontId = "fluxx.serif")
        controller.begin(1); controller.source("latest"); controller.edit(1, oldStyle)
        ready.complete(Unit); drain(); drain()
        assertEquals("latest", editor.state.selectedLayer!!.text.source.staticValue)
        assertEquals("fluxx.serif", editor.state.selectedLayer!!.text.fontId)
        editor.undo(); assertEquals("latest", editor.state.selectedLayer!!.text.source.staticValue)
        assertEquals("fluxx.sans", editor.state.selectedLayer!!.text.fontId)
    }
    @Test fun creationIsAssetlessTopmostAndPreservesExplicitDuration() {
        val editor = EditorViewModel()
        editor.dispatch(EditorAction.Load(ProjectDocument(Composition(durationUs = 1_000_000))))
        editor.dispatch(EditorAction.Seek(500_001)); editor.addText()
        val layer = editor.state.selectedLayer!!
        assertNull(layer.asset); assertEquals(500_000L, layer.timing.startUs)
        assertEquals(5_000_000L, layer.timing.durationUs); assertEquals(1_000_000L, editor.state.project.composition.durationUs)
        assertEquals(TextProperties(source = AnimatableString(staticValue = "Text")), layer.text)
        editor.undo(); assertTrue(editor.state.project.composition.layers.isEmpty())
        editor.dispatch(EditorAction.Load(ProjectDocument())); editor.addText()
        assertEquals(5_000_000L, editor.state.project.composition.resolvedDurationUs)
    }
    @Test fun copiedTypedPayloadRemainsIndependentAndPersistsWithHistory() {
        val editor = editor(true)
        val original = editor.state.selectedLayer!!
        val payload = original.text.copy(size = AnimatableProperty1D(true, 72f,
            FrozenList.of(listOf(Keyframe1D(0, 20f), Keyframe1D(900_000, 200f)))),
            fill = AnimatableColour(true, -1, FrozenList.of(listOf(ColourKeyframe(0, 0x00ff0000), ColourKeyframe(900_000, -1)))))
        editor.dispatch(EditorAction.SetTextPayload(1, payload))
        assertTrue(editor.copyLayer(1))
        editor.dispatch(EditorAction.Seek(1_000_000)); editor.pasteLayer()
        val pasted = editor.state.selectedLayer!!
        assertEquals(payload, pasted.text); assertNull(pasted.asset)
        assertEquals(1_000_000L, pasted.resolvedKeyframeAnchorUs)
        val persisted = editor.state.project
        assertEquals(persisted, ProjectCodec.decode(ProjectCodec.encode(persisted)))
        editor.dispatch(EditorAction.SetStringKeyframe(pasted.id, 500_000, "copy only"))
        assertEquals("first", editor.state.project.composition.layers.first { it.id == 1L }.text.source.evaluate(500_000))
        editor.undo(); assertEquals(persisted, editor.state.project)
        editor.undo(); assertEquals(1, editor.state.project.composition.layers.size)
        editor.redo(); assertEquals(persisted, editor.state.project)
    }
    @Test fun textNavigationIncludesFocusedTypedPropertyBeforeClipEdges() {
        val editor = editor(true)
        val layer = editor.state.selectedLayer!!
        assertEquals(900_000L, ContextualNavigation.findNext(editor.state.project, layer, true, AnimPropertyType.SOURCE_TEXT, 500_000))
        val frozen = EditorReducer.reduce(editor.state, EditorAction.ToggleAnimated(1, AnimPropertyType.SOURCE_TEXT, false, 1_000_000)).selectedLayer!!
        assertEquals("last", frozen.text.source.staticValue)
        assertFalse(frozen.text.source.isAnimated); assertTrue(frozen.text.source.keyframes.isEmpty())
    }
    @Test fun textHitTestingAndAnchorCompensationUseTheRenderedMatrix() {
        val bounds = TextBounds(0f, -80f, 180f, 100f)
        val transform = Transform(scaleX = -2f, scaleY = .5f, rotationDegrees = 45f)
        val matrix = FloatArray(16)
        LayerGeometry.textMatrix(matrix, transform, bounds, 640, 360)
        val x = matrix[0] * 90 + matrix[4] * -30 + matrix[12]
        val y = matrix[1] * 90 + matrix[5] * -30 + matrix[13]
        assertTrue(LayerGeometry.textContains(matrix, bounds, x, y))
        assertFalse(LayerGeometry.textContains(matrix, bounds, 10f, 10f))
        val compensated = LayerGeometry.compensateTextAnchor(transform, bounds, .5f, .5f, 0f, 1f, 640, 360)
        val next = FloatArray(16)
        LayerGeometry.textMatrix(next, compensated, bounds, 640, 360, anchorX = 0f, anchorY = 1f)
        assertArrayEquals(matrix, next, .00001f)
        assertFalse(LayerGeometry.textContains(matrix, TextBounds(0f, 0f, 0f, 0f), x, y))
    }
}
