package com.fluxx.android.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
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
import com.fluxx.android.ui.editor.EditorScreen
import com.fluxx.android.ui.theme.FluxxTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * TLP.1 on the A16 (fixture compositions, never the user's projects; Compose test input only):
 * layer Stagger / Sequence Reverse defaults, key tap rules (no seek), Copy / Paste keys in the property ⋮ (not the 6th
 * icon) and the hold-drag that moves selected keys together (the selector box test moved to TlpTwoUiTest in TLP.2).
 */
class TlpOneUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var previousLibrary: CurveLibraryStore? = null
    @Before fun lib() { previousLibrary = CurveLibraryStore.replaceForTest(CurveLibraryStore.inMemory()) }
    @After fun restore() { CurveLibraryStore.replaceForTest(previousLibrary) }
    private lateinit var editor: EditorViewModel
    private val controller = mutableStateOf<PreviewController?>(null)
    private val rate = FrameRate(30, 1)
    private fun f(n: Long) = rate.frameTimeUs(n)
    private val playhead get() = f(45) // 1.5 s
    /** Position keys of layer 1 at frames 30, 36, 42, 48, 54 (1.0 … 1.8 s), around the centred playhead. */
    private val keyFrames = listOf(30L, 36L, 42L, 48L, 54L)

    private fun host() {
        editor = EditorViewModel()
        val keyed = CompositionLayer(1, LayerType.SOLID, name = "L1", zOrder = 3, timing = ClipTiming(0, 0, 8_000_000),
            animTransform = AnimatableTransform(position = AnimatableProperty2D(true, keyframes = FrozenList.of(
                keyFrames.mapIndexed { i, n -> Keyframe2D(f(n), i * .1f, 0f) }))))
        val others = (2..3).map { i -> CompositionLayer(i.toLong(), LayerType.SOLID, name = "L$i", zOrder = 3 - i, timing = ClipTiming(0, 0, 8_000_000)) }
        editor.dispatch(EditorAction.Load(ProjectDocument(Composition(durationUs = 10_000_000, frameRate = rate, layers = FrozenList.of(listOf(keyed) + others)))))
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
    private fun settle() { Thread.sleep(500); compose.waitForIdle() }
    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun click(tag: String) { node(tag).performClick(); settle() }
    private fun state(tag: String) = node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
    private fun key(n: Long) = "timeline-key-1-${f(n)}"
    /** A single tap on a key: its tap fires after the double-tap timeout, which only passes on the Compose test clock. */
    private fun tapKey(n: Long) { node(key(n)).performTouchInput { click() }; compose.mainClock.advanceTimeBy(1_000); settle() }
    private fun times() = editor.state.project.composition.layers.first { it.id == 1L }.animTransform.position.keyframes.map { it.timeUs }
    /** Opens the inspector on layer 1 (a clip body tap), then its Transform page (Position), so the keys become editable. */
    private fun openLayer1() {
        node("timeline-clip-hit-1").performTouchInput { click(Offset(200.dp.toPx(), centerY)) }; settle()
        compose.onNodeWithContentDescription("Transform").performClick(); settle()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(key(42), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun layerStaggerOpensWithReverseOnAndSequenceWithReverseOff() {
        host()
        node("d-button-1").performTouchInput { longClick(Offset(14.dp.toPx(), centerY)) }; settle()
        click("d-eye-2")
        click("selection-stagger"); assertEquals("On", state("selection-stagger-reverse"))
        click("selection-stagger-reverse"); assertEquals("Off", state("selection-stagger-reverse"))
        click("selection-stagger") // close
        click("selection-stagger"); assertEquals("every open starts from the default", "On", state("selection-stagger-reverse"))
        click("selection-stagger")
        click("selection-sequence"); assertEquals("Off", state("selection-sequence-reverse"))
    }

    @Test fun keyTapsNeverSeekASingleTapDoesNothingADoubleTapSelects() {
        host(); openLayer1()
        val selected = editor.state.selectedLayerId
        tapKey(42)
        assertNull("a single tap selects nothing", editor.keySelection)
        assertEquals("no seek", playhead, editor.state.playheadUs); assertEquals(selected, editor.state.selectedLayerId)
        node(key(36)).performTouchInput { doubleClick() }; settle()
        assertEquals(listOf(f(36)), editor.keySelection!!.times); assertEquals(playhead, editor.state.playheadUs)
        // In the mode a tap toggles, a double tap toggles once.
        tapKey(48)
        assertEquals(listOf(f(36), f(48)), editor.keySelection!!.times)
        node(key(48)).performTouchInput { doubleClick() }; settle()
        assertEquals(listOf(f(36)), editor.keySelection!!.times)
        assertEquals(playhead, editor.state.playheadUs); assertFalse("selection is not in undo history", editor.canUndo)
    }

    @Test fun holdDragOnASelectedKeyMovesTheSelectedKeysTogetherOneUndo() {
        host(); openLayer1()
        node(key(36)).performTouchInput { doubleClick() }; settle()
        tapKey(48)
        val before = editor.state.project
        // Interior keys 36 → 42 are 6 frames apart and their hit spans are symmetric: px per frame from their centres.
        val a = node(key(36)).getBoundsInRoot(); val b = node(key(42)).getBoundsInRoot()
        val dpPerFrame = (((b.left + b.right) - (a.left + a.right)) / 2f).value / 6f
        node(key(48)).performTouchInput {
            down(center); advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset((dpPerFrame * 1.5f).dp.toPx(), 0f)); moveBy(Offset((dpPerFrame * 1.5f).dp.toPx(), 0f)); up()
        }
        settle()
        // Both selected keys moved 3 frames; the unselected keys stayed; the selection followed.
        assertEquals(listOf(f(30), f(39), f(42), f(51), f(54)), times())
        assertEquals(listOf(f(39), f(51)), editor.keySelection!!.times)
        assertEquals(playhead, editor.state.playheadUs)
        editor.undo(); settle(); assertEquals(before, editor.state.project)
    }

    @Test fun copyAndPasteKeysLiveInThePropertyMenuNotTheSixthIcon() {
        host(); openLayer1()
        // The 6th icon (Layer actions) no longer offers key items.
        compose.onNodeWithContentDescription("Layer actions").performClick(); settle()
        node("key-menu-copy").assertDoesNotExist(); node("key-menu-paste").assertDoesNotExist()
        compose.onNodeWithContentDescription("Layer actions").performClick(); settle()
        Thread.sleep(300); compose.waitForIdle()
        // No key selection: Copy keys copies every key of the property.
        click("inspector-rail-menu")
        node("rail-menu-paste-keys").assertIsNotEnabled()
        click("rail-menu-copy-keys")
        assertEquals(keyFrames.size, editor.keyClipboard!!.keys.size)
        // Paste at 3 s (frame 90), no key selection needed: one undo.
        compose.runOnIdle { editor.dispatch(EditorAction.Seek(f(90))) }; settle()
        click("inspector-rail-menu"); node("rail-menu-paste-keys").assertIsEnabled(); click("rail-menu-paste-keys")
        assertEquals(keyFrames.map { f(it) } + keyFrames.map { f(it + 60) }, times())
        editor.undo(); settle(); assertEquals(keyFrames.map { f(it) }, times())
    }
}
