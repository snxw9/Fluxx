package com.fluxx.android.ui

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
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
 * GRAPH G1: the Graph page (Ease view) inside the real ElementInspectorSheet at the hard sheet heights (268 / 328dp)
 * and two phone widths. The page fits without scrolling (only the preset strip scrolls sideways), nothing is clipped,
 * every tile keeps a touch size, a handle can be dragged (one undo entry), the Graph icon returns to the page it came
 * from with its state, and the sheet's swipe-down still closes. Edits go through the real EditorViewModel.
 */
class GraphPageLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val size = mutableStateOf(Pair(384f, 268f))
    private var dismissed = 0
    private lateinit var vm: EditorViewModel

    private fun rotationLayer() = CompositionLayer(1, LayerType.TEXT, timing = ClipTiming(0, 0, 4_000_000),
        text = TextProperties(source = AnimatableString(staticValue = "Graph"), size = AnimatableProperty1D(true, 72f,
            FrozenList.of(listOf(Keyframe1D(0, 40f), Keyframe1D(2_000_000, 120f))))),
        animTransform = AnimatableTransform(rotation = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(
            Keyframe1D(0, 0f), Keyframe1D(1_000_000, 90f), Keyframe1D(3_000_000, 45f))))))

    private fun start() {
        vm = EditorViewModel()
        vm.dispatch(EditorAction.Load(ProjectDocument(com.fluxx.android.model.Composition(durationUs = 4_000_000, layers = FrozenList.of(listOf(rotationLayer()))))))
        vm.dispatch(EditorAction.Select(1))
        compose.setContent {
            val (w, h) = size.value
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (InspectorSheetBudget.compact(h)) 40.dp else 48.dp) {
                MaterialTheme {
                    key(size.value) {
                        ElementInspectorSheet(vm.displayedState.selectedLayer!!, 1080, 1920, compositionDurationUs = 4_000_000, playheadUs = vm.state.playheadUs,
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
    private fun click(tag: String) { compose.onNodeWithTag(tag, useUnmergedTree = true).performClick(); compose.waitForIdle() }
    private fun openRotationGraph() {
        compose.onNodeWithContentDescription("Transform").performClick(); compose.waitForIdle()
        click("transform-mode-ROTATION")
        click("inspector-rail-graph")
        compose.onNodeWithTag("graph-page").assertExists()
    }

    @Test fun graphPageFitsBothSheetHeightsWithNothingClipped() {
        start()
        for (w in listOf(360f, 384f)) for (h in listOf(268f, 328f)) {
            compose.runOnIdle { size.value = w to h }
            compose.waitForIdle()
            openRotationGraph()
            val sheet = bounds("inspector-sheet")
            val tags = listOf("graph-header", "graph-ease-canvas", "graph-controls", "graph-preset-strip", "inspector-rail") +
                EaseChoice.entries.map { "graph-type-${it.name}" } +
                listOf("snap", "mirror", "invert", "apply-all", "copy", "paste", "numeric").map { "graph-tool-$it" } +
                listOf("GRAPH_VALUE", "GRAPH_SPEED", "GRAPH_EASE").map { "graph-mode-$it" }
            for (tag in tags) {
                val b = bounds(tag)
                assertTrue("$tag inside the sheet at $w×$h: $b in $sheet", b.left >= sheet.left - .5f && b.right <= sheet.right + .5f &&
                    b.top >= sheet.top - .5f && b.bottom <= sheet.bottom + .5f)
                if (tag.startsWith("graph-type") || tag.startsWith("graph-tool")) assertTrue("$tag touch size at $w×$h: $b", b.width >= 32f && b.height >= 32f)
            }
            val canvas = bounds("graph-ease-canvas")
            assertTrue("square canvas at $w×$h: $canvas", kotlin.math.abs(canvas.width - canvas.height) < 1f && canvas.width >= 120f)
            // Nothing overlaps: the canvas sits above the strip and beside the controls.
            assertTrue(canvas.bottom <= bounds("graph-preset-strip").top + .5f); assertTrue(canvas.right <= bounds("graph-controls").left + .5f)
            assertTrue(bounds("graph-header").bottom <= canvas.top + .5f)
            // The rail still shows the diamond and the Graph icon (selected: it closes the page).
            compose.onNodeWithTag("inspector-key-diamond").assertIsDisplayed()
            compose.onNodeWithTag("inspector-rail-graph").assertIsSelected()
            compose.onNodeWithTag("graph-segment-label", useUnmergedTree = true).assertTextContains("Key 1 to 2", substring = true)
            click("inspector-rail-graph")
            compose.onNodeWithTag("graph-page").assertDoesNotExist()
            // Back on Transform with the Rotation mode it was opened from.
            compose.onNodeWithTag("transform-mode-ROTATION").onChildren().filterToOne(isToggleable()).assertIsOn()
            compose.onNodeWithTag("inspector-section-back").performClick(); compose.waitForIdle()
        }
    }

    @Test fun handleDragIsOneUndoAndTypeTapsApply() {
        start()
        openRotationGraph()
        // P1 of Easy Ease (0.33, 0) in the default y range [-0.25, 1.25], 10dp inset.
        compose.onNodeWithTag("graph-ease-canvas").performTouchInput {
            val inset = 10.dp.toPx(); val w = width - 2 * inset; val h = height - 2 * inset
            val p1 = Offset(inset + .33f * w, inset + (1.25f - 0f) / 1.5f * h)
            down(p1); moveBy(Offset(0f, -viewConfiguration.touchSlop - 4f)); moveBy(Offset(-10f, -h * .3f)); moveBy(Offset(-5f, -h * .1f)); up()
        }
        compose.waitForIdle()
        val curve = vm.state.project.composition.layers[0].animTransform.rotation.keyframes[0].curve
        assertEquals(EasingPreset.CUSTOM, curve.type)
        assertTrue("dragged up: $curve", curve.b > .3f)
        vm.undo(); assertEquals(EasingCurve.EASY_EASE, vm.state.project.composition.layers[0].animTransform.rotation.keyframes[0].curve)
        compose.waitForIdle()
        click("graph-type-BOUNCE")
        assertEquals(EasingPreset.BOUNCE, vm.state.project.composition.layers[0].animTransform.rotation.keyframes[0].easing)
        click("graph-type-HOLD")
        assertEquals(EasingPreset.HOLD, vm.state.project.composition.layers[0].animTransform.rotation.keyframes[0].easing)
        click("graph-preset-css.ease")
        assertEquals(EasingCurve.bezier(.25f, .1f, .25f, 1f), vm.state.project.composition.layers[0].animTransform.rotation.keyframes[0].curve)
        // InspectorIconButton reports selection as its state description.
        compose.onNodeWithTag("graph-preset-css.ease").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Selected"))
        click("graph-tool-apply-all")
        assertEquals(EasingCurve.bezier(.25f, .1f, .25f, 1f), vm.state.project.composition.layers[0].animTransform.rotation.keyframes[1].curve)
        // Numeric popover opens non-modal and closes again on the same tile.
        click("graph-tool-numeric"); compose.onNodeWithTag("graph-numeric-rows", useUnmergedTree = true).assertExists()
        click("graph-tool-numeric")
        val selected = SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Selected")
        compose.onNodeWithTag("graph-tool-numeric").assert(!selected)
        compose.waitUntil(3_000) { compose.onAllNodesWithTag("graph-numeric-rows", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun editTextReturnsToItsPageAndSwipeDownStillCloses() {
        start()
        compose.onNodeWithContentDescription("Edit Text").performClick(); compose.waitForIdle()
        // The Character tab focuses Size (keyed): the rail's Graph icon is enabled for it.
        click("inspector-rail-graph")
        compose.onNodeWithTag("graph-page").assertExists()
        compose.onNodeWithTag("graph-segment-label", useUnmergedTree = true).assertTextContains("Key 1 to 2", substring = true)
        click("inspector-rail-graph")
        compose.onNodeWithTag("text-tab-CHARACTER").assertIsSelected()
        click("inspector-rail-graph")
        val before = dismissed
        compose.onNodeWithTag("inspector-sheet").performTouchInput { swipe(Offset(width * .6f, 20f * density), Offset(width * .6f, 160f * density), 300) }
        compose.waitForIdle()
        assertEquals(before + 1, dismissed)
    }
}
