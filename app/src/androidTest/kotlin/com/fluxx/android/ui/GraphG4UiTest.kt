package com.fluxx.android.ui

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
 * GRAPH G4 on the device: Convert to keys from the Graph page's ⋮ menu (G4.1) and the Speed view's one re-frame after a
 * type-strip tap (G4.3), in the inspector sheet with a real EditorViewModel (in-memory curve library).
 */
class GraphG4UiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var vm: EditorViewModel
    private lateinit var store: CurveLibraryStore
    private var previous: CurveLibraryStore? = null
    private val playhead = 500_000L
    private val rot = GraphTarget(AnimPropertyType.ROTATION)

    @Before fun memory() { store = CurveLibraryStore.inMemory(); previous = CurveLibraryStore.replaceForTest(store) }
    @After fun restore() { CurveLibraryStore.replaceForTest(previous) }

    private fun layer() = CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(0, 0, 4_000_000), animTransform = AnimatableTransform(
        rotation = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(Keyframe1D(0, 0f), Keyframe1D(1_000_000, 90f), Keyframe1D(3_000_000, 180f))))))

    private fun start() {
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
        click("transform-mode-ROTATION"); click("inspector-rail-graph")
    }
    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot().let {
        Rect(it.left.value, it.top.value, it.right.value, it.bottom.value) }
    private fun click(tag: String) { compose.onNodeWithTag(tag, useUnmergedTree = true).performClick(); compose.waitForIdle() }
    private fun rot() = vm.state.project.composition.layers[0].animTransform.rotation.keyframes
    private fun type(name: String) { compose.onNodeWithTag("graph-type-strip").performScrollToNode(hasTestTag("graph-type-$name")); click("graph-type-$name") }
    private fun viewport() = compose.onNodeWithTag("graph-speed-canvas").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)!!
    private fun segs() = GraphSpeed.segments(vm.state.project.composition.layers[0], rot, GraphValues.axes(vm.state.project.composition.layers[0], rot, 1080, 1920))

    @Test fun convertToKeysIsOneUndoAndOnlyForBounceAndElastic() {
        start()
        val sheet = bounds("inspector-sheet")
        click("inspector-rail-menu")
        compose.onNodeWithTag("graph-menu-convert").assertIsNotEnabled()
        androidx.test.espresso.Espresso.pressBack(); compose.waitForIdle()
        type("BOUNCE")
        assertEquals(EasingPreset.BOUNCE, rot()[0].easing)
        val bounced = rot()
        click("inspector-rail-menu"); compose.onNodeWithTag("graph-menu-convert").assertIsEnabled()
        click("graph-menu-convert")
        // Default Bounce (3 bounces): the first impact, then an apex and an impact per arc (the last impact is key 2).
        assertEquals(bounced.size + 6, rot().size)
        assertTrue(rot().filter { it.timeUs < 1_000_000 }.all { it.easing == EasingPreset.CUSTOM })
        assertEquals(90f, rot().first { it.timeUs == 1_000_000L }.value, 0f)
        assertEquals(sheet, bounds("inspector-sheet")); assertEquals(playhead, vm.state.playheadUs)
        vm.undo(); compose.waitForIdle()
        assertEquals(bounced, rot())
        // Elastic converts too.
        type("ELASTIC"); click("inspector-rail-menu"); click("graph-menu-convert")
        assertTrue(rot().size > bounced.size); assertEquals(playhead, vm.state.playheadUs)
    }

    @Test fun speedViewReframesAtMostOnceAfterAStripTap() {
        start()
        click("graph-mode-GRAPH_SPEED"); compose.onNodeWithTag("graph-speed-canvas").assertExists()
        val sheet = bounds("inspector-sheet")
        val before = viewport()
        // A calm curve whose handles stay inside: the window does not move.
        type("LINEAR"); Thread.sleep(400); compose.waitForIdle()
        assertEquals("a tap that keeps the handles inside does not move the window", before, viewport())
        // Ease In ends fast: either it fits (no move) or ONE animated move to the segment frame.
        type("EASE_IN"); Thread.sleep(500); compose.waitForIdle()
        val after = viewport()
        val framed = SpeedFrame.frame(segs(), 0)!!.describe()
        assertTrue("unchanged or the segment frame: $before -> $after (frame $framed)", after == before || after == framed)
        Thread.sleep(400); compose.waitForIdle()
        assertEquals("one re-frame at most, then still", after, viewport())
        assertEquals(sheet, bounds("inspector-sheet")); assertEquals(playhead, vm.state.playheadUs)
        android.util.Log.d("GraphG43", "before $before, after Ease In $after, frame $framed")
    }
}
