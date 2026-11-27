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
import com.fluxx.android.ui.inspector.GraphValues
import com.fluxx.android.ui.theme.FluxxTheme
import com.fluxx.android.ui.timeline.TimelineView
import com.fluxx.android.ui.timeline.TimelineZoomKey
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** TL3.1 on the A16 (fixture compositions only; Compose test input): key gesture sequences, D zones, auto-scroll, Select all, curves, add-key, graph icon, waveform row. */
class Tl31UiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var previousLibrary: CurveLibraryStore? = null
    @Before fun lib() { previousLibrary = CurveLibraryStore.replaceForTest(CurveLibraryStore.inMemory()) }
    @After fun restore() { CurveLibraryStore.replaceForTest(previousLibrary) }
    private lateinit var editor: EditorViewModel
    private val controller = mutableStateOf<PreviewController?>(null)
    private val rate = FrameRate(30, 1)
    private fun f(n: Long) = rate.frameTimeUs(n)
    private val pos = GraphTarget(AnimPropertyType.POSITION)

    private fun settle() { Thread.sleep(500); compose.waitForIdle() }
    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun count(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun layer(id: Long) = editor.state.project.composition.layers.first { it.id == id }
    private fun pxPerUs() = compose.onNodeWithTag("timeline-ruler").fetchSemanticsNode().config[TimelineZoomKey]
    /**
     * TLV1: a time's x inside [row]'s VISIBLE bounds (what performTouchInput measures from): the row node is a window wider
     * than the screen, clipped at the screen's left, so its local 0 is not the visible left. Root x from the scroll semantics
     * (content px under the playhead), exactly as TimeAuditTest does.
     */
    private fun xIn(row: String, t: Long): Float {
        val s = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)).fetchSemanticsNodes().first()
        val scroll = s.config[SemanticsProperties.HorizontalScrollAxisRange].value()
        val rootX = s.boundsInRoot.left + Math.round(s.boundsInRoot.width / 2f) + (t * pxPerUs() - scroll).toFloat()
        return rootX - node(row).fetchSemanticsNode().boundsInRoot.left
    }
    private fun rowKey(id: Long, p: AnimPropertyType) = TwirlRow.Property(id, GraphTarget(p), "", "", TwirlValueKind.SCALAR).key
    private fun rowTag(id: Long, p: AnimPropertyType = AnimPropertyType.POSITION) = "twirl-row-" + rowKey(id, p)
    private fun posTimes(id: Long) = layer(id).animTransform.position.keyframes.map { it.timeUs }

    private fun keyed(id: Long) = CompositionLayer(id, LayerType.SOLID, name = "L$id", zOrder = (10 - id).toInt(), timing = ClipTiming(0, 0, f(240)),
        animTransform = AnimatableTransform(
            position = AnimatableProperty2D(true, keyframes = FrozenList.of(listOf(Keyframe2D(f(36), 0f, 0f), Keyframe2D(f(48), .2f, .1f), Keyframe2D(f(60), .3f, .3f)))),
            opacity = AnimatableProperty1D(true, keyframes = FrozenList.of(listOf(Keyframe1D(f(40), 1f), Keyframe1D(f(54), .5f))))))

    private fun host(layers: List<CompositionLayer>, playhead: Long = f(45)) {
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
    private fun expand(vararg ids: Long) { compose.runOnIdle { editor.setExpanded(ids.toSet()) }; settle() }
    private fun x(t: Long, row: String = rowTag(1)) = xIn(row, t)
    private fun tapKey(row: String, t: Long) { val xx = x(t, row); node(row).performTouchInput { click(Offset(xx, centerY)) }; settle() }
    private fun holdKey(row: String, t: Long) { val xx = x(t, row); node(row).performTouchInput { longClick(Offset(xx, centerY)) }; settle() }

    // ---- A1 ---------------------------------------------------------------------------------------------------------
    @Test fun a1AHoldDragBackToItsFrameNeverOpensThePopoverAndAHoldSelectThenTapDeselects() {
        host(listOf(keyed(1))); expand(1)
        val row = rowTag(1); val xx = x(f(48))
        // Hold, drag 30dp away and back to the same frame, release: no popover, no selection, no edit.
        node(row).performTouchInput {
            down(Offset(xx, centerY)); advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(30.dp.toPx(), 0f)); moveBy(Offset(-30.dp.toPx(), 0f)); up()
        }
        settle()
        assertEquals(0, count("twirl-value-popover")); assertNull(editor.keySelection)
        assertEquals(listOf(f(36), f(48), f(60)), posTimes(1))
        // Hold release selects (key mode); one TAP on that key deselects it, and key mode exits.
        holdKey(row, f(48)); assertEquals(listOf(f(48)), editor.keySelection!!.times)
        tapKey(row, f(48)); assertNull("the last key deselected: key mode exits", editor.keySelection)
    }

    @Test fun a1InKeyModeAHoldOnAnUnselectedKeyOpensItsOwnPopoverWithoutSelectingIt() {
        host(listOf(keyed(1))); expand(1)
        val row = rowTag(1)
        holdKey(row, f(36)); assertEquals(listOf(f(36)), editor.keySelection!!.times)
        holdKey(row, f(60))
        assertEquals("not selected", listOf(f(36)), editor.keySelection!!.times)
        assertEquals(1, count("twirl-value-popover"))
        compose.onNodeWithTag("twirl-value-count", useUnmergedTree = true).assertTextEquals("Changes 1 keyframe")
        // On a selected key: the popover for the selected keys of that property.
        tapKey(row, f(48)); holdKey(row, f(48))
        compose.onNodeWithTag("twirl-value-count", useUnmergedTree = true).assertTextEquals("Changes 2 keyframes")
    }

    @Test fun a1WithTheSheetOpenAHoldInKeyModeDoesNothing() {
        host(listOf(keyed(1)))
        node("timeline-clip-hit-1").performTouchInput { click(Offset(200.dp.toPx(), centerY)) }; settle()
        compose.onNodeWithContentDescription("Transform").performClick(); settle()
        fun key(n: Long) = "timeline-key-1-${f(n)}"
        compose.waitUntil(5_000) { exists(key(48)) }
        node(key(36)).performTouchInput { longClick() }; settle()
        assertEquals(listOf(f(36)), editor.keySelection!!.times)
        node(key(48)).performTouchInput { longClick() }; settle()
        assertEquals("a hold on an unselected key adds nothing", listOf(f(36)), editor.keySelection!!.times)
        assertEquals(0, count("twirl-value-popover"))
        node(key(48)).performTouchInput { click() }; settle()
        assertEquals(setOf(f(36), f(48)), editor.keySelection!!.times.toSet())
    }

    // ---- A2 ---------------------------------------------------------------------------------------------------------
    @Test fun a2TheDBodyDeselectsHowEverTheLayersWereSelected() {
        host((1L..4L).map { keyed(it) })
        fun bodyTap(id: Long) { node("d-button-$id").performTouchInput { click(Offset(28.dp.toPx(), centerY)) }; settle() }
        // Via hold.
        node("d-button-1").performTouchInput { longClick(Offset(14.dp.toPx(), centerY)) }; settle()
        node("d-button-2").performTouchInput { click(Offset(14.dp.toPx(), centerY)) }; settle()
        assertEquals(setOf(1L, 2L), editor.layerSelection!!.ids.toSet())
        bodyTap(2); assertEquals(setOf(1L), editor.layerSelection!!.ids.toSet())
        // Via Select all.
        node("selection-select-all").performClick(); settle()
        assertEquals(4, editor.layerSelection!!.size)
        bodyTap(3); assertFalse(3L in editor.layerSelection!!.ids)
        // Via Odd / Even.
        node("selection-odd-even").performClick(); settle(); node("selection-even").performClick(); settle()
        val even = editor.layerSelection!!.ids.toSet()
        val one = even.first(); bodyTap(one)
        assertEquals(even - one, editor.layerSelection!!.ids.toSet())
        // The eye zone still toggles visibility for the selection, not the selection.
        val keep = editor.layerSelection!!.ids.toSet()
        node("d-button-${keep.first()}").performTouchInput { click(Offset(10.dp.toPx(), centerY)) }; settle()
        assertEquals(keep, editor.layerSelection!!.ids.toSet())
    }

    // ---- B1 ---------------------------------------------------------------------------------------------------------
    @Test fun b1DraggingAKeyToTheEdgeScrollsTheTimeline() {
        host(listOf(keyed(1))); expand(1)
        val row = rowTag(1)
        val root = compose.onRoot().getBoundsInRoot()
        val rowLeft = node(row).getBoundsInRoot().left
        val start = x(f(48))
        // Hold the key, then move the finger into the right edge zone and keep it there.
        val target = (root.right - rowLeft - 10.dp).value
        node(row).performTouchInput {
            down(Offset(start, centerY)); advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(Offset(target.dp.toPx(), centerY))
        }
        Thread.sleep(1500)
        node(row).performTouchInput { up() }
        settle()
        val moved = posTimes(1).first { it > f(36) && it != f(60) }
        val fingerOnly = f(48) + ((target.dp.value * context.resources.displayMetrics.density - start) / pxPerUs()).toLong()
        assertTrue("the content scrolled under the still finger: key at $moved > finger-only $fingerOnly", moved > fingerOnly + f(10))
        assertEquals("no seek", f(45), editor.state.playheadUs)
    }

    // ---- B4 / B5 ----------------------------------------------------------------------------------------------------
    @Test fun b4SelectAllAsksLayersOrKeyframesWhenKeysAreShown() {
        host(listOf(keyed(1), keyed(2))); expand(1)
        compose.onNodeWithContentDescription("Layer actions").performClick(); settle()
        node("layer-menu-select-all").performClick(); settle()
        assertTrue(exists("select-all-popover"))
        node("select-all-keyframes").performClick(); settle()
        assertEquals("every key of the displayed rows", 5, editor.keySelection!!.size)
        assertNull(editor.layerSelection)
        // Nothing expanded: Select all selects all layers at once.
        compose.runOnIdle { editor.exitKeySelection(); editor.setExpanded(emptySet()) }; settle()
        compose.onNodeWithContentDescription("Layer actions").performClick(); settle()
        node("layer-menu-select-all").performClick(); settle()
        assertFalse(exists("select-all-popover")); assertEquals(2, editor.layerSelection!!.size)
    }

    @Test fun b5TheCurvesPopoverIsFourColumns() {
        host(listOf(keyed(1))); expand(1)
        holdKey(rowTag(1), f(36))
        node("key-more").performClick(); settle()
        val grid = node("key-more-grid").getBoundsInRoot()
        assertEquals(174f, (grid.right - grid.left).value, 1.5f)
        val tops = listOf("EASE_IN", "EASE_OUT", "BOUNCE", "ELASTIC").map { node("key-more-$it").getBoundsInRoot().top.value }
        assertTrue("one row of four: $tops", tops.distinct().size == 1)
        node("key-more-EASE_OUT").performClick(); settle()
        assertEquals(EaseChoice.EASE_OUT_CURVE, layer(1).animTransform.position.keyframes.first().curve)
        assertTrue("stays open", exists("key-more-grid"))
    }

    // ---- B7 / B8 ----------------------------------------------------------------------------------------------------
    @Test fun b7TheAddKeyButtonAddsAtThePlayheadWithoutChangingTheMotion() {
        host(listOf(keyed(1)), playhead = f(42)); expand(1)
        val before = layer(1)
        node("twirl-add-key-" + rowKey(1, AnimPropertyType.POSITION)).performClick(); settle()
        assertEquals(listOf(f(36), f(42), f(48), f(60)), posTimes(1))
        val a = GraphValues.sampler(before, pos)!!; val b = GraphValues.sampler(layer(1), pos)!!
        val o1 = FloatArray(2); val o2 = FloatArray(2); var worst = 0f; var t = f(30)
        while (t < f(70)) { a(t, o1); b(t, o2); worst = maxOf(worst, kotlin.math.abs(o1[0] - o2[0]), kotlin.math.abs(o1[1] - o2[1])); t += 1000 }
        assertTrue("motion kept: $worst", worst < 1e-3f)
        assertEquals("no seek", f(42), editor.state.playheadUs)
        // On a key now: unavailable (nothing changes).
        node("twirl-add-key-" + rowKey(1, AnimPropertyType.POSITION)).performClick(); settle()
        assertEquals(4, posTimes(1).size)
        editor.undo(); settle(); assertEquals(listOf(f(36), f(48), f(60)), posTimes(1))
    }

    @Test fun b8TheGraphIconOpensThatPropertysGraphPage() {
        host(listOf(keyed(1))); expand(1)
        node("twirl-graph-" + rowKey(1, AnimPropertyType.OPACITY)).performClick(); settle()
        compose.waitUntil(5_000) { exists("graph-header") }
        assertTrue(editor.expandedLayers.isEmpty())
        assertEquals(1L, editor.state.selectedLayerId)
    }

    // ---- B6 ---------------------------------------------------------------------------------------------------------
    @Test fun b6TheAudioRowIsTheHalfHeightRow() {
        val file = Tl31Fixtures.aacBursts(context, "tl31-b6.m4a", 48_000, 2, 1.0, listOf(.2 to .6))
        val uri = android.net.Uri.fromFile(file).toString()
        val video = CompositionLayer(4, LayerType.VIDEO, timing = ClipTiming(0, 0, 1_000_000), asset = AssetReference("tl31-b6", uri, durationUs = 1_000_000))
        val project = ProjectDocument(Composition(durationUs = 2_000_000, frameRate = rate, layers = FrozenList.of(listOf(video))))
        compose.setContent { FluxxTheme { TimelineView(project, MediaRepository(context), 500_000, null, {}, { _, _ -> }, { _, _ -> },
            {}, {}, {}, {}, {}, expandedLayers = setOf(4L)) } }
        settle()
        val h = node("twirl-row-tw|4|audio").getBoundsInRoot().let { (it.bottom - it.top).value }
        assertEquals(TwirlRows.AUDIO_DP, h, .5f)
        compose.waitUntil(15_000) { node("twirl-row-tw|4|audio").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() == "Audio waveform" }
    }
}
