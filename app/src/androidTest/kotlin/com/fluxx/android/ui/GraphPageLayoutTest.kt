package com.fluxx.android.ui

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import com.fluxx.android.ui.inspector.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * GRAPH G1.1: the reworked Graph page inside the real ElementInspectorSheet at the hard sheet heights (268 / 328dp)
 * and two phone widths: shared header row, left rail where every other page's rail is, the touchpad and values bar as
 * one item, the right rail list. Handle, pad, typed and tap edits go through the real EditorViewModel; none moves the
 * playhead.
 */
class GraphPageLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val size = mutableStateOf(Pair(384f, 268f))
    private var dismissed = 0
    private lateinit var vm: EditorViewModel
    private val selected = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected")

    // G2L: an in-memory curve library, so device tests never write the app's own library file.
    private var previousLibrary: CurveLibraryStore? = null
    @org.junit.Before fun inMemoryLibrary() { previousLibrary = CurveLibraryStore.replaceForTest(CurveLibraryStore.inMemory()) }
    @org.junit.After fun restoreLibrary() { CurveLibraryStore.replaceForTest(previousLibrary) }

    private fun rotationLayer() = CompositionLayer(1, LayerType.TEXT, timing = ClipTiming(0, 0, 4_000_000),
        text = TextProperties(source = AnimatableString(staticValue = "Graph"), size = AnimatableProperty1D(true, 72f,
            FrozenList.of(listOf(Keyframe1D(0, 40f), Keyframe1D(2_000_000, 120f))))),
        animTransform = AnimatableTransform(rotation = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(
            Keyframe1D(0, 0f), Keyframe1D(1_000_000, 90f), Keyframe1D(3_000_000, 45f))))))

    private fun start(playhead: Long = 0) {
        vm = EditorViewModel()
        vm.dispatch(EditorAction.Load(ProjectDocument(com.fluxx.android.model.Composition(durationUs = 4_000_000, layers = FrozenList.of(listOf(rotationLayer()))))))
        vm.dispatch(EditorAction.Select(1)); vm.dispatch(EditorAction.Seek(playhead))
        compose.setContent {
            val (w, h) = size.value
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (InspectorSheetBudget.compact(h)) 40.dp else 48.dp) {
                MaterialTheme {
                    key(size.value) {
                        ElementInspectorSheet(vm.displayedState.selectedLayer!!, 1080, 1920, compositionDurationUs = 4_000_000, playheadUs = vm.displayedState.playheadUs,
                            onAction = vm::dispatch, onRename = {}, onDuplicate = {}, onDelete = {}, onDismiss = { dismissed++ },
                            onTransformBegin = vm::beginGesture, onTransformPreview = {}, onTransformCommit = { vm.commitGesture() },
                            onTransformCancel = { vm.cancelGesture() }, onTransformEdit = {}, onTimingChange = {}, onAudioChange = { _, _ -> },
                            onColorChange = {}, onGesturePreview = { vm.previewGesture(it) },
                            modifier = Modifier.requiredSize(w.dp, h.dp).testTag("inspector-sheet"))
                    }
                }
            }
        }
        compose.waitForIdle()
    }
    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot().let {
        Rect(it.left.value, it.top.value, it.right.value, it.bottom.value) }
    private fun click(tag: String) {
        // The right rail is a vertical list: scroll its tile into view first, as a user does.
        if (tag.startsWith("graph-type-")) compose.onNodeWithTag("graph-type-strip").performScrollToNode(hasTestTag(tag))
        if (tag.startsWith("graph-pinned-")) compose.onNodeWithTag("graph-pinned-rail").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick(); compose.waitForIdle()
    }
    private fun curve(i: Int = 0) = vm.state.project.composition.layers[0].animTransform.rotation.keyframes[i].curve
    private fun openTransformRotation() {
        compose.onNodeWithContentDescription("Transform").performClick(); compose.waitForIdle()
        click("transform-mode-ROTATION")
    }
    private fun openRotationGraph() { openTransformRotation(); click("inspector-rail-graph"); compose.onNodeWithTag("graph-page").assertExists() }
    private fun valueText(i: Int) = compose.onNodeWithTag("graph-value-$i", useUnmergedTree = true).fetchSemanticsNode()
        .config[SemanticsProperties.ContentDescription].first()

    @Test fun layoutFitsBothHeightsAndTheRailSitsWhereTransformsDoes() {
        start()
        for (w in listOf(360f, 384f)) for (h in listOf(268f, 328f)) {
            compose.runOnIdle { size.value = w to h }; compose.waitForIdle()
            openTransformRotation()
            val transformRail = bounds("inspector-rail"); val transformHeader = bounds("inspector-section-header")
            click("inspector-rail-graph")
            val sheet = bounds("inspector-sheet")
            val at = "$w×$h"
            assertEquals("rail top at $at", transformRail.top, bounds("graph-left-rail").top, .5f)
            // Header row: back | modes | label, full width above the rails.
            assertEquals("header height at $at", transformHeader.height, bounds("graph-header").height, .5f)
            assertEquals("header top at $at", transformHeader.top, bounds("graph-header").top, .5f)
            assertTrue(bounds("graph-header").bottom <= bounds("graph-left-rail").top + .5f)
            for (tag in listOf("graph-back", "graph-mode-GRAPH_VALUE", "graph-mode-GRAPH_SPEED", "graph-mode-GRAPH_EASE", "graph-segment-label",
                "graph-left-rail", "graph-centre", "graph-type-strip", "graph-pinned-rail", "graph-library", "graph-ease-canvas", "graph-values-bar")) {
                val b = bounds(tag)
                assertTrue("$tag inside the sheet at $at: $b", b.left >= sheet.left - .5f && b.right <= sheet.right + .5f && b.top >= sheet.top - .5f && b.bottom <= sheet.bottom + .5f)
            }
            // Left rail, top to bottom: diamond, Graph, Invert, Tools, ⋮ (always last); every tile ≥ 36dp.
            val order = listOf("inspector-key-diamond", "inspector-rail-graph", "graph-tool-invert", "graph-tools", "inspector-rail-menu").map { bounds(it) }
            for (i in 1 until order.size) assertTrue("rail order at $at", order[i - 1].bottom <= order[i].top + .5f)
            order.forEach { assertTrue("rail tile ≥ 36dp at $at: $it", it.height >= 36f - .5f) }
            assertTrue(order.last().bottom <= bounds("graph-left-rail").bottom + .5f)
            // One centre item: touchpad over values bar, same width, inside one container.
            val centre = bounds("graph-centre"); val pad = bounds("graph-ease-canvas"); val bar = bounds("graph-values-bar")
            assertEquals(pad.width, bar.width, .5f); assertEquals(bounds("graph-pad-column").width, pad.width, .5f)
            // G2L: the type strip is inside the same container, on its right edge.
            val strip = bounds("graph-type-strip")
            assertTrue(strip.left >= pad.right - .5f && strip.right <= centre.right + .5f && strip.top >= centre.top - .5f)
            assertTrue(pad.top >= centre.top - .5f && bar.bottom <= centre.bottom + .5f && pad.bottom <= bar.top + .5f)
            assertTrue("touchpad at $at: $pad", pad.height >= (if (h == 268f) 150f else 210f) - .5f && pad.width >= 170f - .5f)
            assertTrue(bounds("graph-left-rail").right <= centre.left + .5f && centre.right <= bounds("graph-pinned-rail").left + .5f)
            compose.onNodeWithTag("graph-segment-label", useUnmergedTree = true).assertTextContains("Key 1 to 2", substring = true)
            // Back arrow returns to Transform in the Rotation mode, like the Graph icon.
            click("graph-back")
            compose.onNodeWithTag("graph-page").assertDoesNotExist()
            compose.onNodeWithTag("transform-mode-ROTATION").onChildren().filterToOne(isToggleable()).assertIsOn()
            compose.onNodeWithTag("inspector-section-back").performClick(); compose.waitForIdle()
        }
    }

    @Test fun handleDragUpdatesTheBarLiveCommitsOneUndoAndKeepsThePlayhead() {
        start(playhead = 0)
        openRotationGraph()
        val before = vm.state.playheadUs
        assertEquals("y1 0.00", valueText(1))
        compose.onNodeWithTag("graph-ease-canvas").performTouchInput {
            val inset = 10.dp.toPx(); val w = width - 2 * inset; val h = height - 2 * inset
            val (lo, hi) = EasePlot.range(EasingCurve.EASY_EASE)
            down(Offset(inset + .33f * w, inset + (hi - 0f) / (hi - lo) * h))
            moveBy(Offset(0f, -viewConfiguration.touchSlop - 4f)); moveBy(Offset(0f, -h * .3f))
        }
        compose.waitForIdle()
        assertNotEquals("the bar follows the live drag", "y1 0.00", valueText(1))
        assertEquals("not committed during the drag", EasingCurve.EASY_EASE, curve())
        compose.onNodeWithTag("graph-ease-canvas").performTouchInput { up() }; compose.waitForIdle()
        assertEquals(EasingPreset.CUSTOM, curve().type); assertTrue(curve().b > .2f)
        assertEquals(before, vm.state.playheadUs)
        vm.undo(); assertEquals(EasingCurve.EASY_EASE, curve()); assertFalse(vm.canUndo)
        compose.waitForIdle()
        // A preset tap and a typed value: one action each, playhead unchanged.
        click("graph-pinned-preset:css.ease_in_out")
        assertEquals(EasingCurve.bezier(.42f, 0f, .58f, 1f), curve()); assertEquals(before, vm.state.playheadUs)
        compose.onNodeWithTag("graph-pinned-preset:css.ease_in_out").assert(selected)
        click("graph-type-EASE_IN"); compose.onNodeWithTag("graph-type-EASE_IN").assert(selected)
        click("graph-value-1")
        compose.onNodeWithContentDescription("y1, exact value in ", substring = true).performTextReplacement("0.8")
        compose.onNodeWithContentDescription("y1, exact value in ", substring = true).performImeAction(); compose.waitForIdle()
        assertEquals(EasingPreset.CUSTOM, curve().type); assertEquals(.8f, curve().b, 1e-5f)
        assertEquals(before, vm.state.playheadUs)
        compose.onNodeWithTag("graph-type-EASE_IN").assert(!selected)
    }

    @Test fun bouncePadDragChangesCountAndHeight() {
        start()
        openRotationGraph()
        click("graph-type-BOUNCE")
        assertEquals(EasingCurve.bounce(3f, .5f), curve())
        assertEquals(listOf("Bounces 3", "Height 0.50"), listOf(valueText(0), valueText(1)))
        compose.onNodeWithTag("graph-ease-canvas").performTouchInput {
            val inset = 10.dp.toPx(); val w = width - 2 * inset; val h = height - 2 * inset
            val (u, v) = EasePad.position(EasingCurve.bounce(3f, .5f))
            down(Offset(inset + u * w, inset + (1 - v) * h))
            moveBy(Offset(viewConfiguration.touchSlop + 4f, 0f)); moveBy(Offset(w * .4f, -h * .3f)); up()
        }
        compose.waitForIdle()
        val c = curve()
        assertEquals(EasingPreset.BOUNCE, c.type); assertTrue("more bounces: $c", c.a > 3f); assertTrue("higher: $c", c.b > .5f)
        assertEquals(c.a, c.a.toInt().toFloat(), 0f)
        vm.undo(); assertEquals(EasingCurve.bounce(3f, .5f), curve())
    }

    @Test fun toolsPopoverOverflowItemsAndSwipeDown() {
        start()
        openRotationGraph()
        click("graph-tools"); compose.onNodeWithTag("graph-tools-popover", useUnmergedTree = true).assertExists()
        // GRAPH G2.1 §9: Snap is on by default (persisted app-level); a tap turns it off and back on.
        compose.onNodeWithTag("graph-tool-snap").assert(selected)
        click("graph-tool-snap"); compose.onNodeWithTag("graph-tool-snap").assert(!selected)
        assertFalse(CurveLibraryStore.get(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext).state.value.graphSnap)
        click("graph-tool-snap"); compose.onNodeWithTag("graph-tool-snap").assert(selected)
        click("graph-tool-mirror"); compose.onNodeWithTag("graph-tool-mirror").assert(selected)
        click("graph-tool-apply-all")
        compose.onNodeWithTag("graph-tools-popover", useUnmergedTree = true).assertExists() // momentary; stays open
        click("graph-tools"); compose.onNodeWithTag("graph-tools-popover", useUnmergedTree = true).assertDoesNotExist()
        // Snap / Mirror on: the closed Tools icon stays highlighted.
        compose.onNodeWithTag("graph-tools").assert(selected)
        click("inspector-rail-menu")
        val items = listOf("graph-menu-copy", "graph-menu-paste", "graph-menu-reset").map { bounds(it) }
        for (i in 1 until items.size) assertTrue(items[i - 1].bottom <= items[i].top + .5f)
        compose.onAllNodesWithText("Apply this curve to all keys").assertCountEquals(0)
        click("graph-menu-reset"); assertEquals(EasingCurve.EASY_EASE, curve())
        val before = dismissed
        compose.onNodeWithTag("inspector-sheet").performTouchInput { swipe(Offset(width * .6f, 20f * density), Offset(width * .6f, 160f * density), 300) }
        compose.waitForIdle(); assertEquals(before + 1, dismissed)
    }

    @Test fun editTextReturnsToItsPage() {
        start()
        compose.onNodeWithContentDescription("Edit Text").performClick(); compose.waitForIdle()
        click("inspector-rail-graph")
        compose.onNodeWithTag("graph-page").assertExists()
        click("inspector-rail-graph")
        compose.onNodeWithTag("text-tab-CHARACTER").assertIsSelected()
    }
}
