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
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import com.fluxx.android.ui.inspector.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * GRAPH G3 on the A16: the Speed graph in the inspector sheet with a real EditorViewModel (in-memory curve library).
 * Every Speed interaction keeps the sheet's bounds and the playhead.
 */
class GraphSpeedTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var vm: EditorViewModel
    private lateinit var store: CurveLibraryStore
    private var previous: CurveLibraryStore? = null
    private val playhead = 1_234_567L
    private val rot = GraphTarget(AnimPropertyType.ROTATION)

    @Before fun memory() { store = CurveLibraryStore.inMemory(); previous = CurveLibraryStore.replaceForTest(store) }
    @After fun restore() { CurveLibraryStore.replaceForTest(previous) }

    private fun layer() = CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(0, 0, 4_000_000), animTransform = AnimatableTransform(
        rotation = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(Keyframe1D(0, 0f), Keyframe1D(1_000_000, 90f), Keyframe1D(3_000_000, 180f)))),
        position = AnimatableProperty2D(true, keyframes = FrozenList.of(listOf(Keyframe2D(0, 0f, 0f), Keyframe2D(2_000_000, .5f, .25f))))))

    private fun start(mode: String? = "transform-mode-ROTATION") {
        vm = EditorViewModel()
        vm.dispatch(EditorAction.Load(ProjectDocument(com.fluxx.android.model.Composition(durationUs = 4_000_000, layers = FrozenList.of(listOf(layer()))))))
        vm.dispatch(EditorAction.Select(1)); vm.dispatch(EditorAction.Seek(playhead))
        compose.setContent {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
                MaterialTheme {
                    ElementInspectorSheet(vm.displayedState.selectedLayer!!, 1080, 1920, compositionDurationUs = 4_000_000, playheadUs = vm.displayedState.playheadUs,
                        onAction = { vm.dispatch(it) }, onRename = {}, onDuplicate = {}, onDelete = {}, onDismiss = {},
                        onTransformBegin = vm::beginGesture, onTransformPreview = {}, onTransformCommit = { vm.commitGesture() },
                        onTransformCancel = { vm.cancelGesture() }, onTransformEdit = {}, onTimingChange = {}, onAudioChange = { _, _ -> },
                        onColorChange = {}, onGesturePreview = { vm.previewGesture(it) },
                        modifier = Modifier.requiredSize(360.dp, 268.dp).testTag("inspector-sheet"))
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Transform").performClick(); compose.waitForIdle()
        mode?.let { click(it) }; click("inspector-rail-graph")
    }
    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot().let {
        Rect(it.left.value, it.top.value, it.right.value, it.bottom.value) }
    private fun click(tag: String) { compose.onNodeWithTag(tag, useUnmergedTree = true).performClick(); compose.waitForIdle() }
    private fun rot() = vm.state.project.composition.layers[0].animTransform.rotation.keyframes
    private fun at(tag: String): Offset { val c = bounds("graph-speed-canvas"); val r = bounds(tag); return Offset(r.center.x - c.left, r.center.y - c.top) }
    private fun canvas(block: TouchInjectionScope.() -> Unit) { compose.onNodeWithTag("graph-speed-canvas").performTouchInput(block); compose.waitForIdle() }
    private fun dragFrom(p: Offset, d: Offset) = canvas { val s = p * density; down(s); repeat(20) { moveBy(d * density / 20f) }; up() }
    private fun speed() { click("graph-mode-GRAPH_SPEED"); compose.onNodeWithTag("graph-speed-canvas").assertExists() }
    private fun viewport() = compose.onNodeWithTag("graph-speed-canvas").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)!!
    private fun segs() = GraphSpeed.segments(vm.state.project.composition.layers[0], rot, GraphValues.axes(vm.state.project.composition.layers[0], rot, 1080, 1920))
    private fun unchanged(sheet: Rect) { assertEquals(sheet, bounds("inspector-sheet")); assertEquals(playhead, vm.state.playheadUs) }

    @Test fun speedTabOpensWithCurveKeysAndHandles() {
        start()
        val sheet = bounds("inspector-sheet"); val ease = bounds("graph-ease-canvas")
        compose.onNodeWithTag("graph-mode-GRAPH_SPEED").assertIsEnabled()
        speed()
        compose.onNodeWithTag("graph-mode-GRAPH_SPEED").assertIsSelected()
        compose.onNodeWithTag("graph-speed-canvas").assert(hasContentDescription("Speed graph"))
        assertEquals(ease, bounds("graph-speed-canvas"))
        for (i in 0..2) compose.onNodeWithTag("graph-value-key-$i", useUnmergedTree = true).assertExists()
        // The playhead's segment (key 2 → 3): two handles; Easy Ease starts and ends at rest (speed 0).
        compose.onNodeWithTag("graph-value-handle-0", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("graph-value-handle-1", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("graph-speed-out-speed").assertTextContains("0.0", substring = true)
        compose.onNodeWithTag("graph-speed-out-influence").assertTextContains("33.0", substring = true)
        unchanged(sheet)
    }

    @Test fun handleDragsEditTheEasingTheEaseViewShowsAndAreOneUndo() {
        start(); speed()
        val sheet = bounds("inspector-sheet"); val before = rot()
        canvas { click(at("graph-value-key-0") * density) }; Thread.sleep(400)
        // Out handle up (faster start), then the in handle.
        dragFrom(at("graph-value-handle-0"), Offset(-6f, -30f))
        val c = rot()[0].curve
        assertEquals(EasingPreset.CUSTOM, c.type); assertTrue("faster start: $c", c.b > 0f)
        assertTrue(SpeedHandles.speed(segs()[0], c.bezierOrNull()!!, 0) > 0)
        unchanged(sheet)
        // The Ease view shows the same stored curve.
        click("graph-mode-GRAPH_EASE")
        compose.onNodeWithTag("graph-value-1").assert(hasContentDescription(EaseLimits.format(EaseLimits.Y1, c.b), substring = true))
        click("graph-mode-GRAPH_SPEED")
        vm.undo(); compose.waitForIdle(); assertEquals(before, rot())
        canvas { click(at("graph-value-key-0") * density) }; Thread.sleep(400)
        dragFrom(at("graph-value-handle-1"), Offset(8f, -30f))
        assertNotEquals(before[0].curve, rot()[0].curve); assertEquals(before[1].curve, rot()[1].curve)
        vm.undo(); compose.waitForIdle(); assertEquals(before, rot())
        unchanged(sheet)
    }

    @Test fun smoothThroughKeyMatchesBothSidesWithOneUndo() {
        start(); speed()
        val sheet = bounds("inspector-sheet"); val before = rot()
        click("graph-tools"); click("graph-tool-smooth"); click("graph-tools")
        assertTrue(store.state.value.smoothThroughKey)
        canvas { click(at("graph-value-key-0") * density) }; Thread.sleep(400)
        dragFrom(at("graph-value-handle-1"), Offset(-10f, -40f))
        val s = segs(); val k = rot()
        assertNotEquals(before[0].curve, k[0].curve); assertNotEquals("the neighbour changed too", before[1].curve, k[1].curve)
        val inSpeed = SpeedHandles.speed(s[0], k[0].curve.bezierOrNull()!!, 1); val outSpeed = SpeedHandles.speed(s[1], k[1].curve.bezierOrNull()!!, 0)
        assertTrue(inSpeed > 0); assertEquals("speeds match across key 2", inSpeed, outSpeed, inSpeed * 1e-3)
        vm.undo(); compose.waitForIdle(); assertEquals("one undo restores both", before, rot())
        unchanged(sheet)
    }

    @Test fun typedSpeedAndInfluence() {
        start(); speed()
        canvas { click(at("graph-value-key-0") * density) }; Thread.sleep(400)
        click("graph-speed-out-speed")
        compose.onNodeWithContentDescription("Speed out, exact value in °/s").performTextReplacement("60")
        compose.onNodeWithContentDescription("Speed out, exact value in °/s").performImeAction(); compose.waitForIdle()
        assertEquals(60.0, SpeedHandles.speed(segs()[0], rot()[0].curve.bezierOrNull()!!, 0), 1e-3)
        click("graph-speed-in-influence")
        compose.onNodeWithContentDescription("Influence in, exact value in %").performTextReplacement("40")
        compose.onNodeWithContentDescription("Influence in, exact value in %").performImeAction(); compose.waitForIdle()
        assertEquals(.4, SpeedHandles.influence(rot()[0].curve.bezierOrNull()!!, 1), 1e-5)
        assertEquals(60.0, SpeedHandles.speed(segs()[0], rot()[0].curve.bezierOrNull()!!, 0), 1e-3)
        vm.undo(); compose.waitForIdle(); vm.undo(); compose.waitForIdle()
        assertEquals(EasingCurve.EASY_EASE, rot()[0].curve)
        assertEquals(playhead, vm.state.playheadUs)
    }

    @Test fun positionIsOneCombinedCurveInPixelsPerSecond() {
        start(mode = null); speed()
        compose.onAllNodesWithTag("graph-value-channel").assertCountEquals(0)
        compose.onNodeWithTag("graph-speed-out-speed").assertTextContains("px/s", substring = true)
        assertEquals(playhead, vm.state.playheadUs)
    }

    @Test fun panZoomFitAndNonBezierTypesDisableTheHandles() {
        start(); speed()
        val sheet = bounds("inspector-sheet"); val fit = viewport()
        canvas { val c = Offset(width / 2f, height / 2f); pinch(c - Offset(40f, 0f), c - Offset(160f, 0f), c + Offset(40f, 0f), c + Offset(160f, 0f), 400) }
        assertNotEquals(fit, viewport())
        Thread.sleep(400); canvas { doubleClick(Offset(8f, 8f) * density) }; assertEquals(fit, viewport())
        // Bounce on the selected segment: no handles, the bar says "Edit in Ease".
        compose.onNodeWithTag("graph-type-strip").performScrollToNode(hasTestTag("graph-type-BOUNCE")); click("graph-type-BOUNCE")
        assertEquals(EasingPreset.BOUNCE, rot()[1].curve.type)
        compose.onAllNodesWithTag("graph-value-handle-0", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("graph-speed-out-speed").assertIsNotEnabled().assert(hasContentDescription("Edit in Ease"))
        compose.onNodeWithTag("graph-type-strip").performScrollToNode(hasTestTag("graph-type-HOLD")); click("graph-type-HOLD")
        compose.onAllNodesWithTag("graph-value-handle-1", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("graph-speed-in-influence").assertIsNotEnabled()
        unchanged(sheet)
    }

    @Test fun theTabIsRememberedAcrossClosingAndReopening() {
        start(); speed()
        assertEquals("SPEED", store.state.value.graphTab)
        click("graph-back")
        compose.onAllNodesWithTag("graph-page").assertCountEquals(0)
        click("inspector-rail-graph")
        compose.onNodeWithTag("graph-speed-canvas").assertExists(); compose.onNodeWithTag("graph-mode-GRAPH_SPEED").assertIsSelected()
        click("graph-mode-GRAPH_EASE"); assertEquals("EASE", store.state.value.graphTab)
        assertEquals(playhead, vm.state.playheadUs)
    }
}
