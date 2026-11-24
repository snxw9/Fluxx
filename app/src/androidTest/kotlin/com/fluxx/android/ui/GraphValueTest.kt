package com.fluxx.android.ui

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
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
 * GRAPH G2 on the A16: the Value graph. The inspector sheet with a real EditorViewModel; every action the sheet sends is
 * recorded so "exactly one seek" is checked directly. In-memory curve library.
 */
class GraphValueTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var vm: EditorViewModel
    private var previous: CurveLibraryStore? = null
    private val actions = mutableListOf<EditorAction>()
    private val playhead = 1_234_567L

    @Before fun memory() { previous = CurveLibraryStore.replaceForTest(CurveLibraryStore.inMemory()) }
    @After fun restore() { CurveLibraryStore.replaceForTest(previous) }

    private fun rotationLayer() = CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(0, 0, 4_000_000), animTransform = AnimatableTransform(
        rotation = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(Keyframe1D(0, 0f), Keyframe1D(1_000_000, 90f), Keyframe1D(3_000_000, 45f)))),
        position = AnimatableProperty2D(true, keyframes = FrozenList.of(listOf(Keyframe2D(0, 100f, 200f), Keyframe2D(2_000_000, 500f, 900f))))))

    /** [mode] null keeps the Transform page's default mode (Position; tapping it again would switch to Anchor). */
    private fun start(layer: CompositionLayer = rotationLayer(), mode: String? = "transform-mode-ROTATION", w: Float = 360f, h: Float = 268f) {
        vm = EditorViewModel()
        vm.dispatch(EditorAction.Load(ProjectDocument(com.fluxx.android.model.Composition(durationUs = 4_000_000, layers = FrozenList.of(listOf(layer))))))
        vm.dispatch(EditorAction.Select(1)); vm.dispatch(EditorAction.Seek(playhead))
        compose.setContent {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (InspectorSheetBudget.compact(h)) 40.dp else 48.dp) {
                MaterialTheme {
                    ElementInspectorSheet(vm.displayedState.selectedLayer!!, 1080, 1920, compositionDurationUs = 4_000_000, playheadUs = vm.displayedState.playheadUs,
                        onAction = { actions += it; vm.dispatch(it) }, onRename = {}, onDuplicate = {}, onDelete = {}, onDismiss = {},
                        onTransformBegin = vm::beginGesture, onTransformPreview = {}, onTransformCommit = { vm.commitGesture() },
                        onTransformCancel = { vm.cancelGesture() }, onTransformEdit = {}, onTimingChange = {}, onAudioChange = { _, _ -> },
                        onColorChange = {}, onGesturePreview = { vm.previewGesture(it) },
                        modifier = Modifier.requiredSize(w.dp, h.dp).testTag("inspector-sheet"))
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
    private fun layer() = vm.state.project.composition.layers[0]
    private fun rot() = layer().animTransform.rotation.keyframes
    private fun viewport() = compose.onNodeWithTag("graph-value-canvas").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)!!
    private fun spans(): Pair<Double, Double> {
        val n = Regex("""t (-?[\d.]+)–(-?[\d.]+) s, v (-?[\d.]+)–(-?[\d.]+)""").find(viewport())!!.groupValues.drop(1).map { it.toDouble() }
        return (n[1] - n[0]) to (n[3] - n[2])
    }
    /** A canvas-local point (px) for a marker tag's centre. */
    private fun at(tag: String): Offset { val c = bounds("graph-value-canvas"); val r = bounds(tag); return Offset(r.center.x - c.left, r.center.y - c.top) }
    private fun canvas(block: TouchInjectionScope.() -> Unit) { compose.onNodeWithTag("graph-value-canvas").performTouchInput(block); compose.waitForIdle() }
    private fun dragFrom(p: Offset, d: Offset) = canvas { val s = p * density; down(s); repeat(20) { moveBy(d * density / 20f) }; up() }
    private fun value() { click("graph-mode-GRAPH_VALUE"); compose.onNodeWithTag("graph-value-canvas").assertExists() }

    @Test fun valueTabOpensWithKeysAndKeepsEveryBound() {
        start()
        val sheet = bounds("inspector-sheet"); val ease = bounds("graph-ease-canvas"); val bar = bounds("graph-values-bar")
        compose.onNodeWithTag("graph-mode-GRAPH_SPEED").assertIsNotEnabled()
        value()
        compose.onNodeWithTag("graph-mode-GRAPH_VALUE").assertIsSelected()
        for (i in 0..2) compose.onNodeWithTag("graph-value-key-$i", useUnmergedTree = true).assertExists()
        assertEquals(sheet, bounds("inspector-sheet")); assertEquals(ease, bounds("graph-value-canvas")); assertEquals(bar, bounds("graph-values-bar"))
        // Keys left to right in time; the 90° key is the highest.
        val k = (0..2).map { bounds("graph-value-key-$it").center }
        assertTrue(k[0].x < k[1].x && k[1].x < k[2].x); assertTrue(k[1].y < k[2].y && k[2].y < k[0].y)
        // The library opens over the strip and rail, and popovers, in the Value view too: bounds unchanged.
        click("graph-library"); assertEquals(sheet, bounds("inspector-sheet")); assertEquals(ease, bounds("graph-value-canvas"))
        click("graph-library")
        click("graph-tools"); assertEquals(sheet, bounds("inspector-sheet")); click("graph-tools")
        click("graph-mode-GRAPH_EASE"); assertEquals(ease, bounds("graph-ease-canvas")); assertEquals(sheet, bounds("inspector-sheet"))
        assertEquals(playhead, vm.state.playheadUs)
    }

    @Test fun tapSelectsDoubleTapSeeksOnceAndEmptyDoubleTapFits() {
        start(); value()
        canvas { click(at("graph-value-key-1") * density) }
        compose.onNodeWithTag("graph-value-key-1", useUnmergedTree = true).assertIsSelected()
        compose.onNodeWithTag("graph-segment-label", useUnmergedTree = true).assertTextContains("Key 2 to 3", substring = true)
        assertEquals(playhead, vm.state.playheadUs); assertTrue(actions.none { it is EditorAction.Seek })
        // Tapping empty canvas clears the selection (back to the playhead's segment, key 2 → 3 here).
        canvas { click(Offset(8f, 8f) * density) }
        compose.onNodeWithTag("graph-value-key-1", useUnmergedTree = true).assertIsNotSelected()
        Thread.sleep(400)
        // Double tap on key 3 seeks to it: exactly one Seek, frame-snapped.
        canvas { doubleClick(at("graph-value-key-2") * density) }
        assertEquals(1, actions.count { it is EditorAction.Seek }); assertEquals(3_000_000L, vm.state.playheadUs)
        // Pan, then a double tap on empty canvas fits again.
        Thread.sleep(400)
        val fit = viewport()
        dragFrom(Offset(20f, 20f), Offset(60f, 30f)); assertNotEquals(fit, viewport())
        canvas { doubleClick(Offset(8f, 8f) * density) }; assertEquals(fit, viewport())
        assertEquals(1, actions.count { it is EditorAction.Seek })
    }

    /** GRAPH G2.1 §3 (converted from the G2 key-drag test): a drag that starts on a key pans; handles still drag; typed edits are one undo. */
    @Test fun aKeyDragPansHandleDragAndTypedValuesAreOneUndoEach() {
        start(); value()
        val before = rot(); val fit = viewport()
        dragFrom(at("graph-value-key-1"), Offset(30f, -20f))
        assertEquals("keys are not draggable", before, rot()); assertNotEquals("the drag panned", fit, viewport())
        assertEquals(playhead, vm.state.playheadUs); assertTrue(actions.isEmpty())
        Thread.sleep(400); canvas { doubleClick(Offset(8f, 8f) * density) }; Thread.sleep(400)
        // Handle drag on the selected segment (key 1 -> 2): the easing becomes Custom; one undo.
        canvas { click(at("graph-value-key-0") * density) }; Thread.sleep(400)
        compose.onNodeWithTag("graph-value-handle-0", useUnmergedTree = true).assertExists()
        dragFrom(at("graph-value-handle-0"), Offset(10f, -25f))
        assertEquals(EasingPreset.CUSTOM, rot()[0].easing); assertEquals(playhead, vm.state.playheadUs)
        vm.undo(); compose.waitForIdle(); assertEquals(before, rot())
        // Typed Time (the transport's timecode, or frames) and Value on the selected key.
        canvas { click(at("graph-value-key-1") * density) }; Thread.sleep(400)
        compose.onNodeWithTag("graph-value-time").assertTextContains("00:01:00", substring = true)
        click("graph-value-time")
        compose.onNodeWithContentDescription("Time, exact value in ").performTextReplacement("00:01:15")
        compose.onNodeWithContentDescription("Time, exact value in ").performImeAction(); compose.waitForIdle()
        assertEquals(1_500_000L, rot()[1].timeUs)
        // Frame snap and neighbour clamp: 200 frames is past key 3 (90 frames), so one frame before it.
        click("graph-value-time")
        compose.onNodeWithContentDescription("Time, exact value in ").performTextReplacement("200")
        compose.onNodeWithContentDescription("Time, exact value in ").performImeAction(); compose.waitForIdle()
        assertEquals(FrameRate(30, 1).frameTimeUs(89), rot()[1].timeUs)
        vm.undo(); compose.waitForIdle(); assertEquals(1_500_000L, rot()[1].timeUs)
        // The undone time no longer exists, so the selection is gone (a typed value would go to the playhead): select key 2 again.
        canvas { click(at("graph-value-key-1") * density) }; Thread.sleep(400)
        click("graph-value-value")
        compose.onNodeWithContentDescription("Rotation, exact value in °").performTextReplacement("120")
        compose.onNodeWithContentDescription("Rotation, exact value in °").performImeAction(); compose.waitForIdle()
        assertEquals(120f, rot()[1].value); assertEquals(1_500_000L, rot()[1].timeUs)
        assertEquals(playhead, vm.state.playheadUs)
        vm.undo(); compose.waitForIdle(); assertEquals(90f, rot()[1].value); assertEquals(1_500_000L, rot()[1].timeUs)
    }

    /** GRAPH G2.1 §6: no key selected: the Value tile is the live value at the playhead (as the page shows it), the Time tile is inert. */
    @Test fun liveValueTileAndInertTimeTile() {
        start(); value()
        val target = GraphTarget(AnimPropertyType.ROTATION)
        fun expected() = GraphValues.axes(layer(), target, 1080, 1920)[0].storedText(GraphValues.live(layer(), target, vm.state.playheadUs, FrameRate())!![0])
        compose.onNodeWithTag("graph-value-time").assertIsNotEnabled().assertTextContains("–", substring = true)
        compose.onNodeWithTag("graph-value-value").assertIsEnabled().assertTextContains(expected(), substring = true)
        // It follows the playhead (scrub / play): the same number the Rotation page shows there.
        compose.runOnIdle { vm.dispatch(EditorAction.Seek(2_000_000L)) }; compose.waitForIdle()
        compose.onNodeWithTag("graph-value-value").assertTextContains(expected(), substring = true)
        compose.runOnIdle { vm.dispatch(EditorAction.Seek(playhead)) }; compose.waitForIdle()
        // Typing with no key sets the value at the playhead: one undo, the playhead stays.
        val before = layer().animTransform.rotation
        click("graph-value-value")
        compose.onNodeWithContentDescription("Rotation, exact value in °").performTextReplacement("12")
        compose.onNodeWithContentDescription("Rotation, exact value in °").performImeAction(); compose.waitForIdle()
        assertEquals(12f, layer().evaluatedTransform(playhead).rotationDegrees, 1e-4f)
        assertTrue(rot().any { it.timeUs == playhead }); assertEquals(playhead, vm.state.playheadUs)
        compose.onNodeWithTag("graph-value-value").assertTextContains("0x12.0", substring = true)
        compose.onNodeWithTag("graph-value-time").assertIsNotEnabled()
        vm.undo(); compose.waitForIdle(); assertEquals(before, layer().animTransform.rotation)
        assertTrue(actions.none { it is EditorAction.Seek })
    }

    /** GRAPH G2.1 §4: zoomed in hard, nothing draws through the values bar (the canvas is clipped and the bar is opaque). */
    @Test fun nothingDrawsThroughTheValuesBar() {
        start(); value()
        val canvasBounds = bounds("graph-value-canvas"); val bar = bounds("graph-values-bar")
        repeat(4) { canvas { val c = Offset(width / 2f, height / 2f); pinch(c - Offset(0f, 10f), c - Offset(0f, 110f), c + Offset(0f, 10f), c + Offset(0f, 110f), 300) } }
        assertEquals(canvasBounds, bounds("graph-value-canvas")); assertEquals(bar, bounds("graph-values-bar"))
        // The bar's bottom rows are only its own fill or the tile hairlines: never curve ink.
        val img = compose.onNodeWithTag("graph-values-bar").captureToImage().asAndroidBitmap()
        val allowed = listOf(com.fluxx.android.ui.theme.SurfaceHigh, com.fluxx.android.ui.theme.Border, com.fluxx.android.ui.theme.Surface).map { it.toArgb() }
        fun near(a: Int, b: Int) = listOf(16, 8, 0).all { sh -> kotlin.math.abs((a ushr sh and 255) - (b ushr sh and 255)) <= 6 }
        var checked = 0
        for (y in listOf(img.height - 2, img.height - 3)) for (x in 0 until img.width) {
            val px = img.getPixel(x, y)
            assertEquals("opaque at $x,$y", 255, px ushr 24)
            assertTrue("pixel $x,$y = ${Integer.toHexString(px)}", allowed.any { near(px, it) }); checked++
        }
        assertTrue(checked > 100)
    }

    @Test fun positionHasTwoChannelsAndTheToggleEditsOne() {
        start(mode = null)
        value()
        compose.onNodeWithTag("graph-value-channel").assertExists()
        click("graph-value-channel-1")
        canvas { click(at("graph-value-key-0") * density) }; Thread.sleep(400)
        click("graph-value-value")
        // GRAPH G2.1 §5: typed in the Transform page's units (composition px, top-left origin).
        compose.onNodeWithContentDescription("Position Y, exact value in px").performTextReplacement("333")
        compose.onNodeWithContentDescription("Position Y, exact value in px").performImeAction(); compose.waitForIdle()
        layer().animTransform.position.keyframes[0].let { assertEquals(100f, it.x); assertEquals((333f - 960f) / 960f, it.y, 1e-6f) }
        compose.onNodeWithTag("graph-value-value").assertTextContains("333.0px", substring = true)
        assertEquals(playhead, vm.state.playheadUs)
    }

    @Test fun pinchZoomIsAxisAwareAndFitRestores() {
        start(); value()
        val fit = viewport(); val (t0, v0) = spans()
        // A horizontal pinch-out zooms time only.
        canvas { val c = Offset(width / 2f, height / 2f); pinch(c - Offset(40f, 0f), c - Offset(160f, 0f), c + Offset(40f, 0f), c + Offset(160f, 0f), 400) }
        val (t1, v1) = spans()
        assertTrue("time zoomed: $t0 → $t1", t1 < t0 * .7); assertEquals(v0, v1, v0 * 1e-3)
        // A vertical one zooms value only.
        canvas { val c = Offset(width / 2f, height / 2f); pinch(c - Offset(0f, 30f), c - Offset(0f, 120f), c + Offset(0f, 30f), c + Offset(0f, 120f), 400) }
        val (t2, v2) = spans(); assertEquals(t1, t2, t1 * 1e-3); assertTrue(v2 < v1 * .7)
        Thread.sleep(400)
        canvas { doubleClick(Offset(8f, 8f) * density) }; assertEquals(fit, viewport())
        assertEquals(playhead, vm.state.playheadUs); assertTrue(actions.none { it is EditorAction.Seek })
    }

    @Test fun libraryAndRailApplyToTheSelectedSegment() {
        start(); value()
        canvas { click(at("graph-value-key-1") * density) }; Thread.sleep(400)
        click("graph-pinned-preset:back.out")
        assertEquals(EasingCurve.bezier(.34f, 1.56f, .64f, 1f), rot()[1].curve); assertEquals(EasingCurve.EASY_EASE, rot()[0].curve)
        click("graph-type-LINEAR"); assertEquals(EasingPreset.LINEAR, rot()[1].easing)
        assertEquals(playhead, vm.state.playheadUs)
    }

    @Test fun colourHasNoValueGraphButEaseWorks() {
        val target = GraphTarget(AnimPropertyType.FILL_COLOUR)
        val layer = CompositionLayer(1, LayerType.TEXT, timing = ClipTiming(0, 0, 4_000_000), text = TextProperties(source = AnimatableString(staticValue = "AB"),
            fill = AnimatableColour(true, -1, FrozenList.of(listOf(ColourKeyframe(0, -1), ColourKeyframe(3_000_000, 0xff000000.toInt()))))))
        val session = GraphSession().also { it.mode = GraphMode.VALUE }
        compose.setContent { MaterialTheme { androidx.compose.foundation.layout.Column(Modifier.requiredSize(360.dp, 268.dp)) {
            GraphPageHeader(layer, target, 0L, FrameRate(30, 1), onBack = {}, session = session)
            GraphPage(layer, target, 0L, FrameRate(30, 1), {}, {}, {}, {}, {}, onClose = {}, session = session)
        } } }
        compose.waitForIdle()
        compose.onNodeWithTag("graph-mode-GRAPH_VALUE").assertIsNotEnabled()
        compose.onNodeWithTag("graph-ease-canvas").assertExists(); compose.onAllNodesWithTag("graph-value-canvas").assertCountEquals(0)
    }
}
