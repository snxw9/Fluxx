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

    /**
     * GRAPH G2: the Value view on the real editor and preview controller. Key drag, handle drag, a pinned-preset tap,
     * pan, pinch zoom and fit never move the playhead, the clip or the selection; a double tap on a key seeks to it once
     * and the controller does not echo the playhead back anywhere else afterwards.
     */
    @Test fun valueGraphEditsKeepThePlayheadAndADoubleTapSeeksOnce() {
        val label = hasText("Spin")
        val editor = host(CompositionLayer(1, LayerType.SOLID, name = "Spin", timing = ClipTiming(0, 0, 4_000_000),
            animTransform = AnimatableTransform(rotation = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(
                Keyframe1D(0, 0f), Keyframe1D(1_000_000, 90f), Keyframe1D(3_000_000, 45f)))))))
        openInspector(label)
        compose.onNodeWithContentDescription("Transform").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("transform-mode-ROTATION").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("inspector-rail-graph", useUnmergedTree = true).performClick(); settle()
        compose.onNodeWithTag("graph-mode-GRAPH_VALUE").performClick(); settle()
        val clip = clipX(label)
        fun keys() = editor.state.project.composition.layers[0].animTransform.rotation.keyframes
        fun at(tag: String): Offset {
            val c = compose.onNodeWithTag("graph-value-canvas").fetchSemanticsNode().boundsInRoot
            val r = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            return Offset(r.center.x - c.left, r.center.y - c.top)
        }
        fun canvas(block: TouchInjectionScope.() -> Unit) { compose.onNodeWithTag("graph-value-canvas").performTouchInput(block); settle() }
        fun unchanged() { assertEquals(arbitrary, editor.state.playheadUs); assertEquals(arbitrary, editor.displayedState.playheadUs)
            assertEquals(clip, clipX(label)); assertEquals(1L, editor.state.selectedLayerId) }
        // A drag that starts on a key (hold before release, as in the G1.1 repro): G2.1 keys are not draggable, it pans.
        canvas { down(at("graph-value-key-1")); repeat(20) { moveBy(Offset(2f, -3f)) }; advanceEventTime(600); up() }
        assertEquals(90f, keys()[1].value); assertEquals(1_000_000L, keys()[1].timeUs); unchanged()
        Thread.sleep(400); canvas { doubleClick(Offset(10f, 10f)) }; Thread.sleep(400)
        // Handle drag on the selected segment.
        canvas { click(at("graph-value-key-0")) }
        canvas { down(at("graph-value-handle-0")); repeat(20) { moveBy(Offset(-2f, -2f)) }; up() }
        assertEquals(EasingPreset.CUSTOM, keys()[0].easing); unchanged()
        // Preset from the pinned rail, pan, pinch, fit.
        compose.onNodeWithTag("graph-pinned-preset:back.out", useUnmergedTree = true).performClick(); settle(); unchanged()
        canvas { swipe(Offset(width * .1f, height * .1f), Offset(width * .4f, height * .3f), 300) }; unchanged()
        canvas { val c = Offset(width / 2f, height / 2f); pinch(c - Offset(60f, 0f), c - Offset(200f, 0f), c + Offset(60f, 0f), c + Offset(200f, 0f), 400) }; unchanged()
        Thread.sleep(400)
        canvas { doubleClick(Offset(10f, 10f)) }; unchanged()
        Thread.sleep(400)
        // The one exception: a double tap on a key seeks to it.
        val target = keys()[2].timeUs
        canvas { doubleClick(at("graph-value-key-2")) }
        assertEquals(target, editor.state.playheadUs)
        settle(); Thread.sleep(600); compose.waitForIdle()
        assertEquals("no echo after the seek", target, editor.state.playheadUs)
    }

    /** GRAPH G3: Speed view handle drags (Smooth through key on), typed speed, pan / pinch / fit never move the playhead. */
    @Test fun speedGraphEditsKeepThePlayhead() {
        val label = hasText("Spin")
        val editor = host(CompositionLayer(1, LayerType.SOLID, name = "Spin", timing = ClipTiming(0, 0, 4_000_000),
            animTransform = AnimatableTransform(rotation = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(
                Keyframe1D(0, 0f), Keyframe1D(1_000_000, 90f), Keyframe1D(3_000_000, 180f)))))))
        openInspector(label)
        compose.onNodeWithContentDescription("Transform").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("transform-mode-ROTATION").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("inspector-rail-graph", useUnmergedTree = true).performClick(); settle()
        compose.onNodeWithTag("graph-mode-GRAPH_SPEED").performClick(); settle()
        val clip = clipX(label)
        fun unchanged() { assertEquals(arbitrary, editor.state.playheadUs); assertEquals(arbitrary, editor.displayedState.playheadUs)
            assertEquals(clip, clipX(label)); assertEquals(1L, editor.state.selectedLayerId) }
        fun keys() = editor.state.project.composition.layers[0].animTransform.rotation.keyframes
        fun at(tag: String): Offset {
            val c = compose.onNodeWithTag("graph-speed-canvas").fetchSemanticsNode().boundsInRoot
            val r = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            return Offset(r.center.x - c.left, r.center.y - c.top)
        }
        fun canvas(block: TouchInjectionScope.() -> Unit) { compose.onNodeWithTag("graph-speed-canvas").performTouchInput(block); settle() }
        compose.onNodeWithTag("graph-tools", useUnmergedTree = true).performClick(); compose.waitForIdle()
        compose.onNodeWithTag("graph-tool-smooth", useUnmergedTree = true).performClick(); compose.waitForIdle()
        compose.onNodeWithTag("graph-tools", useUnmergedTree = true).performClick(); settle()
        canvas { click(at("graph-value-key-0")) }
        canvas { down(at("graph-value-handle-1")); repeat(20) { moveBy(Offset(-1f, -4f)) }; advanceEventTime(600); up() }
        assertEquals(EasingPreset.CUSTOM, keys()[0].easing); assertEquals(EasingPreset.CUSTOM, keys()[1].easing); unchanged()
        compose.onNodeWithTag("graph-speed-out-speed").performClick(); compose.waitForIdle()
        compose.onNodeWithContentDescription("Speed out, exact value in °/s").performTextReplacement("30")
        compose.onNodeWithContentDescription("Speed out, exact value in °/s").performImeAction(); settle(); unchanged()
        canvas { swipe(Offset(width * .1f, height * .1f), Offset(width * .4f, height * .3f), 300) }; unchanged()
        canvas { val c = Offset(width / 2f, height / 2f); pinch(c - Offset(60f, 0f), c - Offset(200f, 0f), c + Offset(60f, 0f), c + Offset(200f, 0f), 400) }; unchanged()
        Thread.sleep(400); canvas { doubleClick(Offset(10f, 10f)) }; unchanged()
    }

    /**
     * GRAPH G2.1: the live Value tile, a typed value at the playhead (no key selected), and library / rail drags never
     * change the playhead, the clip or the selection (real editor and preview controller).
     */
    @Test fun liveValueTypedValueAndLibraryDragsKeepThePlayhead() {
        val label = hasText("Spin")
        val editor = host(CompositionLayer(1, LayerType.SOLID, name = "Spin", timing = ClipTiming(0, 0, 4_000_000),
            animTransform = AnimatableTransform(rotation = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(
                Keyframe1D(0, 0f), Keyframe1D(1_000_000, 90f), Keyframe1D(3_000_000, 45f)))))))
        openInspector(label)
        compose.onNodeWithContentDescription("Transform").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("transform-mode-ROTATION").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("inspector-rail-graph", useUnmergedTree = true).performClick(); settle()
        compose.onNodeWithTag("graph-mode-GRAPH_VALUE").performClick(); settle()
        val clip = clipX(label)
        fun unchanged() { assertEquals(arbitrary, editor.state.playheadUs); assertEquals(arbitrary, editor.displayedState.playheadUs)
            assertEquals(clip, clipX(label)); assertEquals(1L, editor.state.selectedLayerId) }
        // The live value at the playhead, as the Rotation page shows it.
        val layer0 = editor.state.project.composition.layers[0]
        val target = GraphTarget(AnimPropertyType.ROTATION)
        val live = com.fluxx.android.ui.inspector.GraphValues.live(layer0, target, arbitrary, editor.state.project.composition.frameRate)!![0]
        val text = com.fluxx.android.ui.inspector.GraphValues.axes(layer0, target, 1080, 1920)[0].storedText(live)
        compose.onNodeWithTag("graph-value-value").assertTextContains(text, substring = true)
        compose.onNodeWithTag("graph-value-time", useUnmergedTree = true).assertIsNotEnabled()
        // Typed value at the playhead.
        compose.onNodeWithTag("graph-value-value", useUnmergedTree = true).performClick(); compose.waitForIdle()
        compose.onNodeWithContentDescription("Rotation, exact value in \u00b0").performTextReplacement("20")
        compose.onNodeWithContentDescription("Rotation, exact value in \u00b0").performImeAction(); settle()
        assertEquals(20f, editor.state.project.composition.layers[0].evaluatedTransform(arbitrary).rotationDegrees, 1e-3f); unchanged()
        // A rail reorder drag.
        val pinned = CurveLibraryStore.get(context).state.value.pinned
        val rail = compose.onNodeWithTag("graph-pinned-rail").fetchSemanticsNode().boundsInRoot
        val a = compose.onNodeWithTag("graph-pinned-${pinned[0]}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithTag("graph-pinned-${pinned[2]}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("graph-pinned-rail").performTouchInput {
            down(Offset(a.center.x - rail.left, a.center.y - rail.top)); advanceEventTime(900)
            repeat(20) { i -> moveTo(Offset(a.center.x - rail.left, a.center.y - rail.top + (b.center.y - a.center.y) * (i + 1) / 20f)) }
            advanceEventTime(300); up() }
        settle(); unchanged()
        // A library drag (a preset onto Pinned).
        compose.onNodeWithTag("graph-library", useUnmergedTree = true).performClick(); settle()
        val col = compose.onNodeWithTag("graph-library-column").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithTag("graph-lib-${CurveLibraryStore.get(context).state.value.pinned[1]}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("graph-library-column").performTouchInput {
            down(Offset(first.center.x - col.left, first.center.y - col.top)); advanceEventTime(900)
            repeat(10) { moveBy(Offset(0f, 6f)) }; advanceEventTime(300); up() }
        settle(); unchanged()
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
