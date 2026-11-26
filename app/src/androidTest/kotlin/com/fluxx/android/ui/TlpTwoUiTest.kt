package com.fluxx.android.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.editor.*
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import com.fluxx.android.render.PreviewController
import com.fluxx.android.render.TextMetricsRepository
import com.fluxx.android.ui.editor.EditorScreen
import com.fluxx.android.ui.preview.PreviewLayerOverlay
import com.fluxx.android.ui.preview.ViewSettings
import com.fluxx.android.ui.theme.FluxxTheme
import com.fluxx.android.ui.timeline.TimelineZoomKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * TLP.2 on the A16 (fixture compositions, never the user's projects; Compose test input only): the ONE red selector
 * box (no layer handles while it shows), the key bar in the sheet header, drag snapping and the drag readouts.
 */
class TlpTwoUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var previousLibrary: CurveLibraryStore? = null
    @Before fun lib() { previousLibrary = CurveLibraryStore.replaceForTest(CurveLibraryStore.inMemory()) }
    @After fun restore() { CurveLibraryStore.replaceForTest(previousLibrary) }
    private lateinit var editor: EditorViewModel
    private val controller = mutableStateOf<PreviewController?>(null)
    private val rate = FrameRate(30, 1)
    private fun f(n: Long) = rate.frameTimeUs(n)

    private fun settle() { Thread.sleep(500); compose.waitForIdle() }
    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun click(tag: String) { node(tag).performClick(); settle() }
    private fun state(tag: String) = node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
    private fun bounds(tag: String): Rect = node(tag).getBoundsInRoot().let { Rect(it.left.value, it.top.value, it.right.value, it.bottom.value) }
    private fun text(tag: String) = node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
    private fun layer(id: Long) = editor.state.project.composition.layers.first { it.id == id }

    /** The real EditorScreen over [layers], the playhead at [playhead]. */
    private fun host(layers: List<CompositionLayer>, playhead: Long) {
        editor = EditorViewModel()
        editor.dispatch(EditorAction.Load(ProjectDocument(Composition(durationUs = 10_000_000, frameRate = rate, layers = FrozenList.of(layers)))))
        compose.setContent {
            FluxxTheme {
                EditorScreen(editor, MediaRepository(context), controller.value, null, true, false,
                    onSurfaceAvailable = { s, w, h ->
                        if (controller.value == null) controller.value = PreviewController(context, s, w, h, onPosition = { editor.reportPosition(it) })
                        controller.value!!.update(editor.displayedState.project, editor.displayedState.playheadUs, false)
                    },
                    onSurfaceDestroyed = {}, onSurfaceResized = { _, _ -> }, onRequestMediaPermission = {}, onOpenDocumentPicker = {},
                    onSaveProject = {}, onLoadProject = {}, onExportStart = {}, onExportCancel = {}, onOpenExportedVideo = {})
            }
        }
        compose.waitUntil(10_000) { controller.value != null }
        compose.runOnIdle { editor.dispatch(EditorAction.Seek(playhead)); controller.value!!.seek(editor.state.playheadUs) }
        settle()
    }

    // ---- Part 2: one red box ----------------------------------------------------------------------------------------
    @Test fun oneRedBoxOnFocusNoHandlesAndBlurRestoresTheLayerBoxWithHandles() {
        // The real preview overlay with real text metrics: selector 2 covers the first half of "Selector box".
        val text = TextProperties(source = AnimatableString(staticValue = "Selector box"), animators = FrozenList.of(listOf(
            TextAnimator(1, selectors = FrozenList.of(listOf(TextRangeSelector(2, start = AnimatableProperty1D(staticValue = 0f),
                end = AnimatableProperty1D(staticValue = 50f)))),
                properties = FrozenList.of(listOf(TextPropertyOverride(AnimatorProperty.TRACKING, scalar = AnimatableProperty1D(staticValue = 50f))))))))
        val layer = CompositionLayer(7, LayerType.TEXT, timing = ClipTiming(durationUs = 4_000_000), text = text)
        val project = ProjectDocument(Composition(width = 1080, height = 1920, durationUs = 4_000_000, layers = FrozenList.of(listOf(layer))))
        val focus = mutableStateOf<AnimatorTrackAddress?>(null)
        compose.setContent { FluxxTheme { PreviewLayerOverlay(project, layer, 0, null, ViewSettings(), false, focus.value) } }
        settle()
        assertEquals("Layer box with handles", state("preview-layer-overlay"))
        // The box is the covered half: narrower than the layer box, in the same layout space.
        val metrics = runBlocking { TextMetricsRepository.get(context).measure(text.fontId, text.source.evaluateDocument(0), text.effectiveLigatures(0), text) }
        val box = metrics.selectorBox(text, 0, 1, 2)!!
        assertTrue(box.width > 0f && box.width < metrics.bounds(text, 0).width)
        // Focus a selector field: only the red box (no handles).
        focus.value = AnimatorTrackAddress(1, selectorId = 2, field = SelectorField.START)
        compose.waitUntil(5_000) { state("preview-layer-overlay") == "Selector box" }
        // Blur to an animator property field: the layer box and its handles return; so with no focus at all.
        focus.value = AnimatorTrackAddress(1, property = AnimatorProperty.TRACKING); settle()
        assertEquals("Layer box with handles", state("preview-layer-overlay"))
        focus.value = AnimatorTrackAddress(1, selectorId = 2); compose.waitUntil(5_000) { state("preview-layer-overlay") == "Selector box" }
        focus.value = null; settle()
        assertEquals("Layer box with handles", state("preview-layer-overlay"))
    }

    // ---- Part 3: the key bar in the sheet header --------------------------------------------------------------------
    @Test fun keyBarReplacesTheSheetHeaderWithoutMovingAnything() {
        val keyFrames = listOf(30L, 36L, 42L, 48L, 54L)
        host(listOf(CompositionLayer(1, LayerType.SOLID, name = "L1", timing = ClipTiming(0, 0, 8_000_000),
            animTransform = AnimatableTransform(position = AnimatableProperty2D(true, keyframes = FrozenList.of(
                keyFrames.mapIndexed { i, n -> Keyframe2D(f(n), i * .1f, 0f) }))))), f(45))
        node("timeline-clip-hit-1").performTouchInput { click(Offset(200.dp.toPx(), centerY)) }; settle()
        compose.onNodeWithContentDescription("Transform").performClick(); settle()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("timeline-key-1-${f(36)}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val header = bounds("inspector-section-header"); val rail = bounds("inspector-rail"); val key = bounds("timeline-key-1-${f(36)}")
        val ruler = bounds("timeline-ruler"); val strip = bounds("editor-action-bar-strip")
        node("timeline-key-1-${f(36)}").performTouchInput { doubleClick() }; settle()
        assertEquals(listOf(f(36)), editor.keySelection!!.times)
        // The bar takes the header's exact place; the page below, the timeline and the action bar do not move by a pixel.
        node("inspector-section-header").assertDoesNotExist()
        val bar = bounds("key-header-bar")
        assertEquals(header, bar)
        assertTrue("fully on screen", bar.top >= 0f && bar.left >= 0f && bar.right <= compose.onRoot().getBoundsInRoot().right.value)
        assertEquals(rail, bounds("inspector-rail")); assertEquals(key, bounds("timeline-key-1-${f(36)}"))
        assertEquals(ruler, bounds("timeline-ruler")); assertEquals(strip, bounds("editor-action-bar-strip"))
        // Tappable: Hold, then Linear on the selected key (one undo each).
        click("key-hold"); assertEquals(EasingPreset.HOLD, layer(1).animTransform.position.keyframes.first { it.timeUs == f(36) }.easing)
        click("key-linear"); assertEquals(EasingPreset.LINEAR, layer(1).animTransform.position.keyframes.first { it.timeUs == f(36) }.easing)
        // Back exits key selection; the header returns exactly as before.
        click("key-top-bar-back")
        assertNull(editor.keySelection); node("key-header-bar").assertDoesNotExist()
        assertEquals(header, bounds("inspector-section-header")); assertEquals(rail, bounds("inspector-rail"))
    }

    // ---- Parts 4 / 5: a layer drag snaps to the playhead and to another clip's end; the readouts follow -------------
    /** A at 0 … frame 37 (its end is a clip edge, not a tick); B at frame 66 for one second; the playhead at frame 42. */
    private fun hostDrag() = host(listOf(
        CompositionLayer(1, LayerType.SOLID, name = "A", zOrder = 2, timing = ClipTiming(0, 0, f(37))),
        CompositionLayer(2, LayerType.SOLID, name = "B", zOrder = 1, timing = ClipTiming(f(66), 0, f(30)))), f(42))

    /** Hold B's left edge and drag it so its start would land at [toFrame] + [extraFrames] without snapping. */
    private fun dragB(toFrame: Double, check: () -> Unit = {}) {
        // The zoom from the ruler (px per µs); the clip's own bounds are clipped where it runs past the viewport.
        val pxPerUs = compose.onNodeWithTag("timeline-ruler").fetchSemanticsNode().config[TimelineZoomKey]
        val dpPerFrame = (pxPerUs * f(1) / compose.density.density).toFloat()
        val dx = ((toFrame - 66.0) * dpPerFrame).toFloat()
        node("timeline-clip-hit-2").performTouchInput {
            down(Offset(8.dp.toPx(), centerY)); advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            for (i in 1..4) { moveBy(Offset((dx / 4f).dp.toPx(), 0f)); advanceEventTime(16) }
        }
        compose.waitForIdle()
        check()
        node("timeline-clip-hit-2").performTouchInput { up() }
        settle()
    }

    @Test fun aLayerDragSnapsToThePlayheadWithReadoutsThatVanishOnRelease() {
        hostDrag()
        val strip = bounds("editor-action-bar-strip")
        node("drag-readout").assertDoesNotExist()
        // Raw start 42.6 frames: 0.6 frame from the playhead (42), 2.4 from the mid tick (45): the playhead wins.
        dragB(42.6) {
            node("drag-readout").assertExists(); node("timeline-snap-guide").assertDoesNotExist() // TLP.3 A3: no guide line
            assertEquals("00:01:12", text("drag-readout-start"))
            assertEquals("-00:00:24", text("drag-readout-delta"))
            assertEquals("the overlay changes no layout", strip, bounds("editor-action-bar-strip"))
        }
        assertEquals(f(42), layer(2).timing.startUs)
        node("drag-readout").assertDoesNotExist()
        assertEquals(strip, bounds("editor-action-bar-strip"))
        assertEquals("no seek", f(42), editor.state.playheadUs)
        editor.undo(); settle(); assertEquals(f(66), layer(2).timing.startUs)
    }

    @Test fun aLayerDragSnapsToAnotherClipsEnd() {
        hostDrag()
        // Raw start 37.5 frames: half a frame from A's end (37); the playhead (42) and the ticks (30, 45) are out of the zone.
        dragB(37.5)
        assertEquals(f(37), layer(2).timing.startUs)
        assertEquals("duration kept", f(30), layer(2).timing.durationUs)
    }
}
