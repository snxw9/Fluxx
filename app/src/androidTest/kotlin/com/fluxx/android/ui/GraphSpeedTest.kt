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
    /** GRAPH G3.1: in segment 1 (key 1 → 2), which the Speed view frames (keys 1 and 2 in view, key 3 outside). */
    private val playhead = 500_000L
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
        // G3.1 framing: the playhead's segment (key 1 → 2) plus a 30% sliver of the next one; key 3 (3 s) is outside.
        for (i in 0..1) compose.onNodeWithTag("graph-value-key-$i", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithTag("graph-value-key-2", useUnmergedTree = true).assertCountEquals(0)
        // Two handles; Easy Ease starts and ends at rest (speed 0).
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
        // Far enough to pass the zero landmark's zone and the visible grid step (Snap is on by default).
        dragFrom(at("graph-value-handle-0"), Offset(-6f, -70f))
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
        assertEquals(EasingPreset.BOUNCE, rot()[0].curve.type)
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

    // ---- GRAPH G3.1 ----

    private fun window(): DoubleArray = Regex("""t (-?[\d.]+)–(-?[\d.]+) s, v (-?[\d.]+)–(-?[\d.]+)""").find(viewport())!!.groupValues.drop(1).map { it.toDouble() }.toDoubleArray()
    /** Canvas-local dp of a speed value in the canvas's current window. */
    private fun yOf(speed: Double): Float { val w = window(); val c = bounds("graph-speed-canvas"); val inset = 12f
        return (inset + (w[3] - speed) / (w[3] - w[2]) * (c.height - 2 * inset)).toFloat() }
    private fun selectFirstSegment() { canvas { click(at("graph-value-key-0") * density) }; Thread.sleep(400) }

    /** §3: the window stays put during a hard drag toward the edges (the handle is clamped inside); at most one expansion after. */
    @Test fun windowIsFrozenDuringADragAndExpandsAtMostOnceAfter() {
        start(); speed(); selectFirstSegment()
        val sheet = bounds("inspector-sheet"); val framed = viewport()
        val h = at("graph-value-handle-0")
        compose.onNodeWithTag("graph-speed-canvas").performTouchInput { down(h * density) }
        for (step in 1..12) {
            compose.onNodeWithTag("graph-speed-canvas").performTouchInput { moveBy(Offset(9f, -30f) * density) }; compose.waitForIdle()
            assertEquals("frozen while dragging (step $step)", framed, viewport())
        }
        compose.onNodeWithTag("graph-value-handle-0", useUnmergedTree = true).assertExists() // clamped inside the plot
        compose.onNodeWithTag("graph-speed-canvas").performTouchInput { up() }; compose.waitForIdle(); Thread.sleep(400); compose.waitForIdle()
        val after = viewport()
        Thread.sleep(400); compose.waitForIdle()
        assertEquals("one expansion at most, then still", after, viewport())
        android.util.Log.d("GraphG31", "frame $framed -> after release $after")
        // A typed value does not move the window either.
        val before = viewport()
        click("graph-speed-out-speed")
        compose.onNodeWithContentDescription("Speed out, exact value in °/s").performTextReplacement("15")
        compose.onNodeWithContentDescription("Speed out, exact value in °/s").performImeAction(); compose.waitForIdle()
        assertEquals(before, viewport()); unchanged(sheet)
    }

    /** §3: no one-finger pan; with a handle selected a drag elsewhere moves it relatively (×0.35); tapping a key re-frames once. */
    @Test fun noPanRelativeDragAndKeyTapReframes() {
        start(); speed(); selectFirstSegment()
        val sheet = bounds("inspector-sheet"); val framed = viewport(); val before = rot()
        compose.onNodeWithTag("graph-speed-canvas").performTouchInput { swipe(Offset(width * .15f, height * .85f), Offset(width * .45f, height * .6f), 300) }
        compose.waitForIdle()
        assertEquals("no pan", framed, viewport()); assertEquals("nothing selected: nothing moved", before, rot())
        // Select the out handle with a tap, then drag on empty canvas.
        canvas { click(at("graph-value-handle-0") * density) }; Thread.sleep(400)
        compose.onNodeWithTag("graph-speed-canvas").performTouchInput {
            val p = Offset(width * .15f, height * .85f); down(p); repeat(20) { moveBy(Offset(4f, -8f) * density) }; up() }
        compose.waitForIdle()
        assertNotEquals("the selected handle moved", before[0].curve, rot()[0].curve)
        assertEquals("the window stayed", framed, viewport())
        vm.undo(); compose.waitForIdle()
        // Tapping key 2 (in the neighbour sliver) selects segment 2: the view re-frames once.
        canvas { click(at("graph-value-key-1") * density) }; Thread.sleep(500); compose.waitForIdle()
        val reframed = viewport()
        assertNotEquals(framed, reframed)
        assertEquals(SpeedFrame.frame(segs(), 1)!!.describe(), reframed)
        unchanged(sheet)
    }

    /** §4: the out handle stops before the in handle; a crossed preset can be uncrossed but not crossed further. */
    @Test fun handlesStopBeforeCrossing() {
        start(); speed(); selectFirstSegment()
        val sheet = bounds("inspector-sheet")
        dragFrom(at("graph-value-handle-0"), Offset(260f, 0f))
        val b = rot()[0].curve.bezierOrNull()!!
        val i0 = SpeedHandles.influence(b, 0); val i1 = SpeedHandles.influence(b, 1)
        android.util.Log.d("GraphG31", "stop: i0=$i0 i1=$i1 gap=${1 - i0 - i1}")
        assertTrue("stopped before the in handle: $i0 + $i1", i0 + i1 <= 1.0 - SpeedCross.GAP + 1e-4)
        assertTrue("it moved up to the stop", i0 > .5)
        vm.undo(); compose.waitForIdle()
        // A crossed preset (Back In-Out: x1 .68 > x2 .32, influences .68 + .68).
        selectFirstSegment()
        compose.onNodeWithTag("graph-pinned-rail").performScrollToNode(hasTestTag("graph-pinned-preset:back.in_out"))
        click("graph-pinned-preset:back.in_out")
        // A library / rail tap never moves the window (G3.1 §3): Fit re-frames to bring the new handles into view.
        Thread.sleep(400); canvas { doubleClick(Offset(8f, 8f) * density) }; Thread.sleep(400)
        val c0 = rot()[0].curve.bezierOrNull()!!
        val start0 = SpeedHandles.influence(c0, 0)
        assertTrue(start0 + SpeedHandles.influence(c0, 1) > 1)
        dragFrom(at("graph-value-handle-0"), Offset(80f, 0f))
        assertTrue("cannot cross further", SpeedHandles.influence(rot()[0].curve.bezierOrNull()!!, 0) <= start0 + 1e-4)
        dragFrom(at("graph-value-handle-0"), Offset(-60f, 0f))
        assertTrue("can uncross", SpeedHandles.influence(rot()[0].curve.bezierOrNull()!!, 0) < start0 - .05)
        unchanged(sheet)
    }

    /** §5: a drag near the average speed locks to it exactly; a typed value stays exact. */
    @Test fun dragLocksToTheAverageSpeedAndTypedStaysExact() {
        start(); speed(); selectFirstSegment()
        val avg = segs()[0].rate // 90°/s
        val h = at("graph-value-handle-0")
        val targetY = yOf(avg) + 4f // within the 10dp zone, below the line
        compose.onNodeWithTag("graph-speed-canvas").performTouchInput {
            down(h * density); repeat(20) { i -> moveTo(Offset(h.x, h.y + (targetY - h.y) * (i + 1) / 20f) * density) } }
        compose.waitForIdle()
        compose.onNodeWithTag("graph-speed-guides").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "guides 3, locked"))
        compose.onNodeWithTag("graph-speed-canvas").performTouchInput { up() }; compose.waitForIdle()
        assertEquals("locked to the average exactly", avg, SpeedHandles.speed(segs()[0], rot()[0].curve.bezierOrNull()!!, 0), 1e-3)
        click("graph-speed-out-speed")
        compose.onNodeWithContentDescription("Speed out, exact value in °/s").performTextReplacement("88")
        compose.onNodeWithContentDescription("Speed out, exact value in °/s").performImeAction(); compose.waitForIdle()
        assertEquals("typed stays exact", 88.0, SpeedHandles.speed(segs()[0], rot()[0].curve.bezierOrNull()!!, 0), 1e-3)
        assertEquals(playhead, vm.state.playheadUs)
    }
}
