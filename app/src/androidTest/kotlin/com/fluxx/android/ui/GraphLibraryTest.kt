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
import androidx.test.espresso.Espresso
import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import com.fluxx.android.ui.inspector.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * GRAPH G2L on the A16: the type strip and pinned rail, the library morph (sheet, touchpad and values-bar bounds
 * unchanged, mid-animation too), Back, save, the user-curve menu, drag-to-pin, the dot toggle and the playhead rule.
 * Uses an in-memory curve library (the app's own library file is never written).
 */
class GraphLibraryTest {
    @get:Rule val compose = createComposeRule()
    private val size = mutableStateOf(Pair(360f, 268f))
    private lateinit var vm: EditorViewModel
    private lateinit var store: CurveLibraryStore
    private var previous: CurveLibraryStore? = null
    private val selected = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected")

    @Before fun memory() { store = CurveLibraryStore.inMemory(); previous = CurveLibraryStore.replaceForTest(store) }
    @After fun restore() { CurveLibraryStore.replaceForTest(previous) }

    private fun start(state: CurveLibraryState = CurveLibraryState()) {
        store.update { state }
        vm = EditorViewModel()
        vm.dispatch(EditorAction.Load(ProjectDocument(com.fluxx.android.model.Composition(durationUs = 4_000_000, layers = FrozenList.of(listOf(
            CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(0, 0, 4_000_000), animTransform = AnimatableTransform(rotation = AnimatableProperty1D(true, 0f,
                FrozenList.of(listOf(Keyframe1D(0, 0f), Keyframe1D(1_000_000, 90f), Keyframe1D(3_000_000, 45f))))))))))))
        vm.dispatch(EditorAction.Select(1)); vm.dispatch(EditorAction.Seek(1_234_567))
        compose.setContent {
            val (w, h) = size.value
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (InspectorSheetBudget.compact(h)) 40.dp else 48.dp) {
                MaterialTheme { key(size.value) {
                    ElementInspectorSheet(vm.displayedState.selectedLayer!!, 1080, 1920, compositionDurationUs = 4_000_000, playheadUs = vm.displayedState.playheadUs,
                        onAction = vm::dispatch, onRename = {}, onDuplicate = {}, onDelete = {}, onDismiss = {},
                        onTransformBegin = vm::beginGesture, onTransformPreview = {}, onTransformCommit = { vm.commitGesture() },
                        onTransformCancel = { vm.cancelGesture() }, onTransformEdit = {}, onTimingChange = {}, onAudioChange = { _, _ -> },
                        onColorChange = {}, onGesturePreview = { vm.previewGesture(it) },
                        modifier = Modifier.requiredSize(w.dp, h.dp).testTag("inspector-sheet"))
                } }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Transform").performClick(); compose.waitForIdle()
        click("transform-mode-ROTATION"); click("inspector-rail-graph")
    }
    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot().let {
        Rect(it.left.value, it.top.value, it.right.value, it.bottom.value) }
    private fun click(tag: String) { compose.onNodeWithTag(tag, useUnmergedTree = true).performClick(); compose.waitForIdle() }
    private fun curve() = vm.state.project.composition.layers[0].animTransform.rotation.keyframes[1].curve
    private fun reveal(tag: String) { compose.onNodeWithTag("graph-library-column").onChildren().filterToOne(hasScrollAction()).performScrollToNode(hasTestTag(tag)); compose.waitForIdle() }
    private fun visible(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
        bounds(tag).let { r -> val c = bounds("graph-library-column"); r.top >= c.top + GraphLayout.LIBRARY_HEADER && r.bottom <= c.bottom - 40f }
    /**
     * Long-press [fromTag] and drag it through the library container (which owns the drag): to the top or bottom edge
     * so auto-scroll runs until [targetTag] is in view, then onto [targetY] (computed from the target's root bounds).
     */
    private fun libraryDrag(fromTag: String, targetTag: String, up: Boolean, targetY: (Rect) -> Float) {
        val col = bounds("graph-library-column"); val from = bounds(fromTag)
        val x = from.center.x - col.left
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("graph-library-column").performTouchInput { down(Offset(x * density, (from.center.y - col.top) * density)) }
        compose.mainClock.advanceTimeBy(900)
        if (!visible(targetTag)) {
            compose.onNodeWithTag("graph-library-column").performTouchInput { moveTo(Offset(x * density, if (up) 6f * density else (col.height - 6f) * density)) }
            var n = 0
            while (!visible(targetTag) && n++ < 400) compose.mainClock.advanceTimeBy(16)
        }
        val y = targetY(bounds(targetTag)) - col.top
        compose.onNodeWithTag("graph-library-column").performTouchInput { moveTo(Offset(x * density, y * density)); up() }
        compose.mainClock.advanceTimeBy(100)
        compose.mainClock.autoAdvance = true; compose.waitForIdle()
    }
    private fun inside(r: Rect, outer: Rect) = r.left >= outer.left - .5f && r.right <= outer.right + .5f && r.top >= outer.top - .5f && r.bottom <= outer.bottom + .5f

    @Test fun closedLayoutAndTheMorphKeepEveryBound() {
        start()
        for (w in listOf(360f, 384f)) for (h in listOf(268f, 328f)) {
            compose.runOnIdle { size.value = w to h }; compose.waitForIdle()
            if (compose.onAllNodesWithTag("graph-page").fetchSemanticsNodes().isEmpty()) {
                compose.onNodeWithContentDescription("Transform").performClick(); compose.waitForIdle()
                click("transform-mode-ROTATION"); click("inspector-rail-graph")
            }
            val at = "$w×$h"
            val sheet = bounds("inspector-sheet")
            for (t in listOf("graph-ease-canvas", "graph-values-bar", "graph-type-strip", "graph-pinned-rail", "graph-library"))
                assertTrue("$t inside at $at", inside(bounds(t), sheet))
            val pad = bounds("graph-ease-canvas"); val bar = bounds("graph-values-bar"); val icon = bounds("graph-library")
            if (w == 360f) assertTrue("touchpad width at $at: ${pad.width}", pad.width >= 170f - .5f)
            android.util.Log.d("GraphG2L", "closed $at: pad=${pad.width}x${pad.height} strip=${bounds("graph-type-strip").width} rail=${bounds("graph-pinned-rail").width}")
            // Open: sample the sheet mid-animation, then the end state.
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("graph-library", useUnmergedTree = true).performClick()
            compose.mainClock.advanceTimeBy(125)
            assertEquals("sheet mid-animation at $at", sheet, bounds("inspector-sheet"))
            assertEquals("touchpad mid-animation at $at", pad, bounds("graph-ease-canvas"))
            compose.mainClock.autoAdvance = true; compose.waitForIdle()
            assertEquals(sheet, bounds("inspector-sheet")); assertEquals(pad, bounds("graph-ease-canvas")); assertEquals(bar, bounds("graph-values-bar"))
            assertEquals("library icon stays put at $at", icon, bounds("graph-library"))
            compose.onNodeWithTag("graph-library").assert(selected)
            val lib = bounds("graph-library-column"); val content = sheet.width - InspectorSheetBudget.FRAME_H
            val fraction = (lib.right - pad.right) / content
            android.util.Log.d("GraphG2L", "open $at: library column=${lib.width} region=${lib.right - pad.right} fraction=$fraction thumbs=${(lib.height - GraphLayout.LIBRARY_HEADER) / GraphLayout.thumbItem(1f)}")
            assertTrue("library fraction $fraction at $at", fraction in .22f..0.30f)
            compose.onAllNodesWithTag("graph-type-strip").assertCountEquals(0)
            // The values bar stays tappable with the library open: enabled, clickable, and not covered by the library
            // column (its bounds lie entirely left of the library and inside the unchanged touchpad column).
            compose.onNodeWithTag("graph-value-1", useUnmergedTree = true).assertIsEnabled().assertHasClickAction()
            val tile = bounds("graph-value-1")
            assertTrue("tile clear of the library at $at", tile.right <= bounds("graph-library-column").left + .5f && inside(tile, bar))
            // System Back closes the library first, leaving the Graph page open.
            Espresso.pressBack(); compose.waitForIdle()
            compose.onNodeWithTag("graph-page").assertExists(); compose.onAllNodesWithTag("graph-library-column").assertCountEquals(0)
            assertEquals(icon, bounds("graph-library")); assertEquals(pad, bounds("graph-ease-canvas"))
        }
    }

    @Test fun saveFlowNamesStarsAndTheCap() {
        start(CurveLibraryState(pinned = emptyList()))
        val before = vm.state.playheadUs
        click("graph-library")
        click("graph-library-save")
        compose.onNodeWithText("Curve 1").assertExists() // the faded placeholder
        compose.onNodeWithTag("graph-name-field").performTextInput("Whip")
        compose.onNodeWithTag("graph-name-field").performImeAction(); compose.waitForIdle()
        assertEquals(listOf("Whip"), store.state.value.user.map { it.name })
        val whip = store.state.value.user[0].id
        reveal("graph-lib-$whip")
        compose.onNodeWithTag("graph-lib-$whip", useUnmergedTree = true).assert(hasContentDescription("Whip, user curve"))
        compose.onNodeWithTag("graph-lib-$whip", useUnmergedTree = true).onChildren().filter(hasContentDescription("User curve")).assertCountEquals(1)
        // Empty field + close by tapping away: saved under the default name.
        click("graph-library-save")
        compose.onNodeWithTag("graph-ease-canvas").performTouchInput { click(Offset(width - 4f, 4f)) }; compose.waitForIdle()
        Thread.sleep(300); compose.waitForIdle()
        assertEquals(listOf("Curve 1", "Whip"), store.state.value.user.map { it.name })
        assertTrue(store.state.value.pinned.isEmpty()) // not auto-pinned
        assertEquals(before, vm.state.playheadUs)
        // At the 60 cap the save icon is disabled.
        compose.runOnIdle { store.update { s -> var x = s; while (CurveLibrary.canAdd(x)) x = CurveLibrary.add(x, "c", EasingCurve.LINEAR)!!.first; x } }
        compose.waitForIdle()
        compose.onNodeWithTag("graph-library-save").assertIsNotEnabled()
        // Hold cannot be saved either.
        compose.runOnIdle { store.update { it.copy(user = it.user.drop(1)) } }
        reveal("graph-lib-type-HOLD"); click("graph-lib-type-HOLD"); compose.onNodeWithTag("graph-library-save").assertIsNotEnabled()
    }

    @Test fun longPressMenuOnUserCurvesOnlyAndDragToPin() {
        var s = CurveLibraryState(pinned = listOf("preset:back.out"))
        s = CurveLibrary.add(s, "Mine", EasingCurve.bezier(.1f, .9f, .2f, 1f), "user:m")!!.first
        start(s)
        click("graph-library")
        reveal("graph-lib-user:m")
        compose.onNodeWithTag("graph-lib-user:m").performTouchInput { longClick() }; compose.waitForIdle()
        val icons = listOf("graph-curve-xy", "graph-curve-rename", "graph-curve-delete").map { bounds(it) }
        assertTrue(icons[0].right <= icons[1].left + .5f && icons[1].right <= icons[2].left + .5f)
        click("graph-curve-rename")
        compose.onNodeWithTag("graph-name-field").performTextReplacement("Renamed"); compose.onNodeWithTag("graph-name-field").performImeAction(); compose.waitForIdle()
        assertEquals("Renamed", store.state.value.user[0].name)
        // A built-in shows nothing on long-press.
        reveal("graph-lib-preset:quad.in")
        compose.onNodeWithTag("graph-lib-preset:quad.in").performTouchInput { longClick() }; compose.waitForIdle()
        compose.onAllNodesWithTag("graph-curve-xy", useUnmergedTree = true).assertCountEquals(0)
        // Drag after long-press: quad.in up onto the Pinned section (auto-scroll brings it into view) pins it at the top.
        reveal("graph-lib-preset:quad.in")
        libraryDrag("graph-lib-preset:quad.in", "graph-lib-cap|pinned", up = true) { it.bottom + 6f }
        assertEquals("pinned first after the drop: ${store.state.value.pinned}", "preset:quad.in", store.state.value.pinned.firstOrNull())
        // Unpin by dragging a pinned curve out of Pinned, down into Presets.
        reveal("graph-lib-preset:back.out")
        libraryDrag("graph-lib-preset:back.out", "graph-lib-cap|presets", up = false) { it.bottom + 30f }
        assertFalse("unpinned: ${store.state.value.pinned}", "preset:back.out" in store.state.value.pinned)
        assertEquals(1_234_567L, vm.state.playheadUs)
    }

    @Test fun fullSetReplaceKeepsTheOthersInOrder() {
        val fifteen = EasePresets.ALL.take(15).map { CurveLibrary.presetId(it) }
        val st = CurveLibraryState(pinned = fifteen)
        assertEquals(fifteen.take(2) + "preset:circ.out" + fifteen.drop(3), CurveLibrary.pinAt(st, "preset:circ.out", 2).pinned)
        start(st)
        click("graph-library")
        reveal("graph-lib-preset:circ.out")
        libraryDrag("graph-lib-preset:circ.out", "graph-lib-${fifteen[1]}", up = true) { it.center.y }
        val pinned = store.state.value.pinned
        assertEquals(15, pinned.size); assertTrue("preset:circ.out" in pinned)
        assertEquals("the others keep their order", fifteen.filter { it in pinned }, pinned.filter { it != "preset:circ.out" })
    }

    @Test fun tapsDotToggleAndExchangeNeverMoveThePlayhead() {
        start()
        val before = vm.state.playheadUs
        click("graph-pinned-preset:back.out"); assertEquals(EasingCurve.bezier(.34f, 1.56f, .64f, 1f), curve())
        compose.onNodeWithTag("graph-pinned-preset:back.out").assert(selected)
        click("graph-library"); reveal("graph-lib-preset:sine.in"); click("graph-lib-preset:sine.in")
        assertEquals(EasingCurve.bezier(.12f, 0f, .39f, 0f), curve())
        click("graph-library") // close
        click("inspector-rail-menu"); click("graph-menu-dot"); assertFalse(store.state.value.showPlayheadDot)
        assertEquals(before, vm.state.playheadUs); assertEquals(1L, vm.state.selectedLayerId)
        // Export → import round trip of the real file format (the SAF picker itself is not driven by this test).
        val s = CurveLibrary.add(CurveLibraryState(), "Round", EasingCurve.elastic(4f, .4f), "user:r")!!.first
        val file = java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "g2l-test.fluxxcurves")
        file.writeText(CurveExchange.export(s))
        val back = CurveExchange.import(CurveLibraryState(), file.readBytes())
        file.delete()
        assertEquals(listOf("Round"), back.state.user.map { it.name }); assertEquals(EasingCurve.elastic(4f, .4f), back.state.user[0].curve)
    }
}
