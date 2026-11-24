package com.fluxx.android.ui

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.editor.*
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import com.fluxx.android.render.PreviewController
import com.fluxx.android.ui.editor.EditorScreen
import com.fluxx.android.ui.inspector.EasePlot
import com.fluxx.android.editor.CurveLibraryStore
import com.fluxx.android.ui.theme.FluxxTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * GRAPH G1.1 §7: Graph editing never moves the playhead, the timeline content or the selection. Hosts the real
 * EditorScreen with a real PreviewController wired exactly like MainActivity (onPosition → EditorViewModel.reportPosition),
 * with the playhead parked at an off-frame time. In-memory project; nothing is written to the app's projects.
 */
class GraphPlayheadTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val arbitrary = 1_744_321L

    // G2L: an in-memory curve library, so device tests never write the app's own library file.
    private var previousLibrary: CurveLibraryStore? = null
    @org.junit.Before fun inMemoryLibrary() { previousLibrary = CurveLibraryStore.replaceForTest(CurveLibraryStore.inMemory()) }
    @org.junit.After fun restoreLibrary() { CurveLibraryStore.replaceForTest(previousLibrary) }

    private fun host(layer: CompositionLayer): EditorViewModel {
        val editor = EditorViewModel()
        editor.dispatch(EditorAction.Load(ProjectDocument(com.fluxx.android.model.Composition(durationUs = 4_000_000, layers = FrozenList.of(listOf(layer))))))
        val controller = mutableStateOf<PreviewController?>(null)
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
        compose.runOnIdle { editor.dispatch(EditorAction.Seek(arbitrary)); controller.value!!.seek(arbitrary) }
        settle()
        return editor
    }
    private fun settle() { Thread.sleep(600); compose.waitForIdle() }
    /** Taps the clip pill at the playhead (its label can be scrolled off to the left) to open the inspector. */
    private fun openInspector(label: SemanticsMatcher) {
        val row = compose.onAllNodes(label).fetchSemanticsNodes().first().boundsInRoot.center.y
        compose.onRoot().performTouchInput { click(Offset(width * .8f, row)) }; settle()
    }
    private fun clipX(label: SemanticsMatcher) = compose.onAllNodes(label).fetchSemanticsNodes().map { it.boundsInRoot.left }
    /** Drag handle [handle] of [curve] by ([dx], [dy]) fractions of the plot, in small steps. */
    private fun dragHandle(curve: EasingCurve, handle: Int, dx: Float, dy: Float) {
        val b = curve.bezierOrNull()!!; val (lo, hi) = EasePlot.range(curve)
        compose.onNodeWithTag("graph-ease-canvas").performTouchInput {
            val inset = 10.dp.toPx(); val w = width - 2 * inset; val h = height - 2 * inset
            down(Offset(inset + b[handle * 2] * w, inset + (hi - b[handle * 2 + 1]) / (hi - lo) * h))
            repeat(20) { moveBy(Offset(dx * w / 20, dy * h / 20)) }; up()
        }
        settle()
    }

    @Test fun transformGraphEditsKeepThePlayhead() {
        val label = hasText("Spin")
        val editor = host(CompositionLayer(1, LayerType.SOLID, name = "Spin", timing = ClipTiming(0, 0, 4_000_000),
            animTransform = AnimatableTransform(rotation = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(
                Keyframe1D(0, 0f), Keyframe1D(1_000_000, 90f), Keyframe1D(3_000_000, 45f)))))))
        openInspector(label)
        compose.onNodeWithContentDescription("Transform").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("transform-mode-ROTATION").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("inspector-rail-graph", useUnmergedTree = true).performClick(); settle()
        val clip = clipX(label)
        fun curve() = editor.state.project.composition.layers[0].animTransform.rotation.keyframes[1].curve
        dragHandle(curve(), 0, -.6f, 0f)
        assertNotEquals(EasingCurve.EASY_EASE, curve())
        for (tag in listOf("graph-type-BOUNCE", "graph-type-LINEAR", "graph-pinned-preset:back.out")) {
            compose.onNodeWithTag(if (tag.startsWith("graph-type")) "graph-type-strip" else "graph-pinned-rail").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag, useUnmergedTree = true).performClick(); settle()
        }
        assertEquals(arbitrary, editor.state.playheadUs); assertEquals(clip, clipX(label)); assertEquals(1L, editor.state.selectedLayerId)
    }

    /** The user's repro: an animator Range Selector Offset field opened from the Animate tab, paused, one handle to the edge first. */
    @Test fun animatorOffsetGraphEditsKeepThePlayhead() {
        val label = hasText("Word") or hasText("Offset me", substring = true)
        val editor = host(CompositionLayer(1, LayerType.TEXT, name = "Word", timing = ClipTiming(0, 0, 4_000_000),
            text = TextProperties(source = AnimatableString(staticValue = "Offset me"), animators = FrozenList.of(listOf(TextAnimator(7,
                selectors = FrozenList.of(listOf(TextRangeSelector(8, offset = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(
                    Keyframe1D(0, -100f), Keyframe1D(1_000_000, 0f), Keyframe1D(3_000_000, 100f))))))),
                properties = FrozenList.of(listOf(TextPropertyOverride(AnimatorProperty.OPACITY, scalar = AnimatableProperty1D(staticValue = 0f))))))))))
        openInspector(label)
        compose.onNodeWithContentDescription("Edit Text").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("text-tab-ANIMATE").performClick(); compose.waitForIdle()
        fun tag(t: String) { compose.onNodeWithTag("animate-list").performScrollToNode(hasTestTag(t)); compose.onNodeWithTag(t, useUnmergedTree = true).performClick(); compose.waitForIdle() }
        tag("animate-animator-0")
        if (compose.onAllNodesWithTag("animate-field-OFFSET", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) tag("animate-selector-0-toggle")
        tag("animate-field-OFFSET"); settle()
        compose.onNodeWithTag("inspector-rail-graph", useUnmergedTree = true).performClick(); settle()
        compose.onNodeWithTag("graph-page").assertExists()
        val clip = clipX(label)
        fun curve() = editor.state.project.composition.layers[0].text.animators[0].selectors[0].offset.keyframes[1].curve
        // The left handle out to the left edge, then the right handle to the right edge, then both into overshoot.
        dragHandle(curve(), 0, -.6f, 0f); assertEquals(0f, curve().a, 1e-4f); assertEquals(arbitrary, editor.state.playheadUs)
        dragHandle(curve(), 1, .6f, 0f); assertEquals(1f, curve().c, 1e-4f); assertEquals(arbitrary, editor.state.playheadUs)
        dragHandle(curve(), 0, .2f, -.4f); dragHandle(curve(), 1, -.2f, .4f)
        assertEquals(EasingPreset.CUSTOM, curve().type)
        assertEquals(arbitrary, editor.state.playheadUs); assertEquals(arbitrary, editor.displayedState.playheadUs)
        assertEquals(clip, clipX(label)); assertEquals(1L, editor.state.selectedLayerId)
    }

    /**
     * The user's repro: playhead ON a keyframe / composition marker / layer marker; start Linear, move the right handle
     * down and hold before releasing; then move the right handle, then the left.
     */
    private fun onTarget(at: Long, markers: Boolean) {
        val label = hasText("Spin")
        val layer = CompositionLayer(1, LayerType.SOLID, name = "Spin", timing = ClipTiming(0, 0, 4_000_000),
            markers = if (markers) FrozenList.of(listOf(Marker(11, at, description = "L"))) else FrozenList.empty(),
            animTransform = AnimatableTransform(rotation = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(
                Keyframe1D(0, 0f), Keyframe1D(1_000_000, 90f), Keyframe1D(3_000_000, 45f))))))
        val editor = EditorViewModel()
        val comp = com.fluxx.android.model.Composition(durationUs = 4_000_000, layers = FrozenList.of(listOf(layer)),
            markers = if (markers) FrozenList.of(listOf(Marker(12, at, description = "C"))) else FrozenList.empty())
        editor.dispatch(EditorAction.Load(ProjectDocument(comp)))
        val controller = mutableStateOf<PreviewController?>(null)
        compose.setContent {
            FluxxTheme {
                EditorScreen(editor, MediaRepository(context), controller.value, null, true, false,
                    onSurfaceAvailable = { s, w, h ->
                        if (controller.value == null) controller.value = PreviewController(context, s, w, h, onPosition = {
                            android.util.Log.d("GraphPlayhead", "report $it"); editor.reportPosition(it) })
                        controller.value!!.update(editor.displayedState.project, editor.displayedState.playheadUs, false)
                    },
                    onSurfaceDestroyed = {}, onSurfaceResized = { _, _ -> }, onRequestMediaPermission = {}, onOpenDocumentPicker = {},
                    onSaveProject = {}, onLoadProject = {}, onExportStart = {}, onExportCancel = {}, onOpenExportedVideo = {})
            }
        }
        compose.waitUntil(10_000) { controller.value != null }
        // Select the layer near its start (its label is on screen), open Transform, then move the playhead onto the target.
        compose.runOnIdle { editor.dispatch(EditorAction.Seek(200_000)); controller.value!!.seek(200_000) }
        settle()
        compose.onNodeWithText("Spin").performClick(); settle()
        compose.onNodeWithContentDescription("Transform").performClick(); compose.waitForIdle()
        compose.runOnIdle { editor.dispatch(EditorAction.Seek(at)); controller.value!!.seek(at) }
        settle()
        android.util.Log.d("GraphPlayhead", "on target: state=${editor.state.playheadUs} marker=${editor.state.selectedMarkerId}")
        compose.onNodeWithTag("transform-mode-ROTATION").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("inspector-rail-graph", useUnmergedTree = true).performClick(); settle()
        compose.onNodeWithTag("graph-type-strip").performScrollToNode(hasTestTag("graph-type-LINEAR"))
        compose.onNodeWithTag("graph-type-LINEAR", useUnmergedTree = true).performClick(); settle()
        val before = editor.state.playheadUs
        android.util.Log.d("GraphPlayhead", "start at=$at markers=$markers before=$before selectedMarker=${editor.state.selectedMarkerId}")
        fun curve() = editor.state.project.composition.layers[0].animTransform.rotation.keyframes[1].curve
        // Right handle down, then hold still before releasing ("wait a bit").
        val b = curve().bezierOrNull()!!; val (lo, hi) = EasePlot.range(curve())
        compose.onNodeWithTag("graph-ease-canvas").performTouchInput {
            val inset = 10.dp.toPx(); val w = width - 2 * inset; val h = height - 2 * inset
            down(Offset(inset + b[2] * w, inset + (hi - b[3]) / (hi - lo) * h))
            repeat(10) { moveBy(Offset(0f, h * .03f)) }
        }
        repeat(3) { Thread.sleep(500); compose.mainClock.advanceTimeBy(500); android.util.Log.d("GraphPlayhead", "holding: state=${editor.state.playheadUs} displayed=${editor.displayedState.playheadUs}") }
        compose.onNodeWithTag("graph-ease-canvas").performTouchInput { up() }
        settle(); Thread.sleep(1500); compose.waitForIdle()
        android.util.Log.d("GraphPlayhead", "after hold: state=${editor.state.playheadUs} curve=${curve()} selectedMarker=${editor.state.selectedMarkerId}")
        assertEquals("hold drag with the playhead on a ${if (markers) "marker" else "keyframe"}", before, editor.state.playheadUs)
        dragHandle(curve(), 1, 0f, .2f); Thread.sleep(1500); compose.waitForIdle()
        android.util.Log.d("GraphPlayhead", "after right: state=${editor.state.playheadUs}")
        dragHandle(curve(), 0, 0f, -.2f); Thread.sleep(1500); compose.waitForIdle()
        android.util.Log.d("GraphPlayhead", "after left: state=${editor.state.playheadUs} curve=${curve()}")
        assertEquals(before, editor.state.playheadUs)
    }
    @Test fun playheadOnAKeyframe() = onTarget(1_000_000, markers = false)
    @Test fun playheadOnMarkers() = onTarget(2_000_000, markers = true)

    /** The traced phone repro: play, pause, Next onto a keyframe with the Graph page open, then drag a handle. */
    @Test fun nextOntoAKeyframeThenDrag() {
        val label = hasText("Spin")
        val editor = host(CompositionLayer(1, LayerType.SOLID, name = "Spin", timing = ClipTiming(0, 0, 4_000_000),
            animTransform = AnimatableTransform(rotation = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(
                Keyframe1D(0, 0f), Keyframe1D(1_000_000, 90f), Keyframe1D(3_000_000, 45f)))))))
        compose.runOnIdle { editor.dispatch(EditorAction.Seek(200_000)) }
        settle()
        compose.onNodeWithText("Spin").performClick(); settle()
        compose.onNodeWithContentDescription("Transform").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("transform-mode-ROTATION").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("inspector-rail-graph", useUnmergedTree = true).performClick(); settle()
        // With the Graph page open at 0.2s, Next onto the key at 1.0s (the traced phone sequence).
        compose.onNodeWithContentDescription("Next contextual boundary", useUnmergedTree = true).performClick(); settle()
        val before = editor.state.playheadUs
        assertEquals(1_000_000L, before)
        fun curve() = editor.state.project.composition.layers[0].animTransform.rotation.keyframes.first { it.timeUs == 1_000_000L }.curve
        dragHandle(curve(), 1, 0f, .2f); Thread.sleep(1500); compose.waitForIdle()
        assertEquals("the playhead after Next then a graph drag", before, editor.state.playheadUs)
    }
}
