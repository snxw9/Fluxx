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
import com.fluxx.android.ui.editor.EditorScreen
import com.fluxx.android.ui.theme.FluxxTheme
import com.fluxx.android.ui.timeline.DButtonLayout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * TLP on the A16, in the real EditorScreen (fixture composition, never the user's projects): the D-button's states and
 * geometry, selection mode entry / tap rules / exit, the bars (the bottom bar never resizes or moves the timeline), Odd/Even.
 */
class TlpUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var previousLibrary: CurveLibraryStore? = null
    @Before fun lib() { previousLibrary = CurveLibraryStore.replaceForTest(CurveLibraryStore.inMemory()) }
    @After fun restore() { CurveLibraryStore.replaceForTest(previousLibrary) }
    private lateinit var editor: EditorViewModel
    private val controller = mutableStateOf<PreviewController?>(null)
    private val playhead = 1_500_000L

    private fun host() {
        editor = EditorViewModel()
        // Layer 1 at the top; all three cover the playhead.
        editor.dispatch(EditorAction.Load(ProjectDocument(Composition(durationUs = 10_000_000, layers = FrozenList.of((1..3).map { i ->
            CompositionLayer(i.toLong(), LayerType.SOLID, name = "L$i", zOrder = 3 - i, timing = ClipTiming(0, 0, 8_000_000)) })))))
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
    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot().let {
        Rect(it.left.value, it.top.value, it.right.value, it.bottom.value) }
    private fun click(tag: String) { compose.onNodeWithTag(tag, useUnmergedTree = true).performClick(); settle() }
    private fun state(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
    private fun visible(id: Long) = editor.state.project.composition.layers.first { it.id == id }.visible
    /** A tap on a layer's clip body at a visible point (the clip starts 180dp left of the centred playhead). */
    private fun body(id: Long) { compose.onNodeWithTag("timeline-clip-hit-$id", useUnmergedTree = true).performTouchInput { click(Offset(200.dp.toPx(), centerY)) }; settle() }

    @Test fun dButtonGeometryStatesAndFade() {
        host()
        for (id in 1L..3L) {
            val b = bounds("d-button-$id")
            assertEquals("56dp wide", DButtonLayout.WIDTH_DP, b.width, .6f)
            assertEquals("one 28dp row tall", 28f, b.height, .6f)
            assertEquals("flat edge on the screen's left edge", 0f, b.left, .6f)
            assertEquals("Not selected", state("d-button-$id"))
        }
        // The fade: opaque near the icons, transparent at the rounded edge (the library scrim's stops).
        val stops = DButtonLayout.fadeStops()
        assertEquals(0f, stops.last().second, 0f); assertTrue(stops.first().second > stops[1].second)
        // Outside selection mode the eye toggles one layer.
        click("d-eye-2"); assertFalse(visible(2)); assertTrue(visible(1))
        editor.undo(); settle(); assertTrue(visible(2))
        assertFalse(editor.selectionMode)
    }

    @Test fun selectionModeEntryTapRulesAndExit() {
        host()
        val ruler = bounds("timeline-ruler"); val row1 = bounds("d-button-1")
        // Long-press enters selection mode with that layer; the bars replace / overlay, the timeline does not move.
        compose.onNodeWithTag("d-button-1", useUnmergedTree = true).performTouchInput { longClick(Offset(14.dp.toPx(), centerY)) }; settle()
        assertTrue(editor.selectionMode); assertEquals(listOf(1L), editor.layerSelection!!.ids)
        assertEquals("Selected", state("d-button-1"))
        compose.onNodeWithTag("selection-top-bar").assertExists(); compose.onNodeWithTag("selection-bottom-bar").assertExists()
        assertEquals("the bottom bar never resizes or moves the timeline", ruler, bounds("timeline-ruler")); assertEquals(row1, bounds("d-button-1"))
        val top = bounds("selection-top-bar"); val bottom = bounds("selection-bottom-bar")
        assertEquals(top.width, bottom.width, .6f); assertEquals(48f, bottom.height, .6f)
        // An unselected D-button adds; the layer body of an unselected layer adds too.
        click("d-eye-2"); assertEquals(listOf(1L, 2L), editor.layerSelection!!.ids)
        body(3); assertEquals(listOf(1L, 2L, 3L), editor.layerSelection!!.ids)
        // A selected eye sets every selected layer (inverse of the tapped one): one undo.
        click("d-eye-1"); assertFalse(visible(1) || visible(2) || visible(3))
        editor.undo(); settle(); assertTrue(visible(1) && visible(2) && visible(3)); assertTrue(editor.selectionMode)
        // A selected body removes; a long-press on a selected D-button removes.
        body(2); assertEquals(listOf(1L, 3L), editor.layerSelection!!.ids)
        compose.onNodeWithTag("d-button-3", useUnmergedTree = true).performTouchInput { longClick(Offset(14.dp.toPx(), centerY)) }; settle()
        assertEquals(listOf(1L), editor.layerSelection!!.ids)
        // A tap on empty track space (below the rows, away from the playhead column) deselects all and exits, without seeking.
        val below = bounds("d-button-3").bottom + 24f
        compose.onRoot().performTouchInput { click(Offset(width * .8f, below.dp.toPx())) }; settle()
        assertFalse(editor.selectionMode); assertEquals(playhead, editor.state.playheadUs)
        compose.onNodeWithTag("selection-top-bar").assertDoesNotExist()
        assertFalse("selection is not in undo history", editor.canUndo)
    }

    @Test fun backExitsAndOddEvenReplaceTheSelection() {
        host()
        compose.onNodeWithTag("d-button-2", useUnmergedTree = true).performTouchInput { longClick(Offset(14.dp.toPx(), centerY)) }; settle()
        click("selection-odd-even"); click("selection-odd")
        assertEquals(listOf(1L, 3L), editor.layerSelection!!.ids)
        click("selection-odd-even"); click("selection-even")
        assertEquals(listOf(2L), editor.layerSelection!!.ids)
        click("selection-select-all"); assertEquals(listOf(1L, 2L, 3L), editor.layerSelection!!.ids)
        click("selection-top-bar-back")
        assertFalse(editor.selectionMode); assertEquals(3, editor.state.project.composition.layers.size)
    }
}
