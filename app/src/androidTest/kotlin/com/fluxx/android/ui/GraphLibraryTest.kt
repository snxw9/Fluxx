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
    private var root: android.view.View? = null
    private fun ime(): Int = androidx.core.view.ViewCompat.getRootWindowInsets(root!!)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0
    /** Waits (real time) until the keyboard is up / down; returns whether it got there. */
    private fun waitIme(up: Boolean, ms: Long = 3000): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { compose.waitForIdle(); if ((ime() > 0) == up) return true; Thread.sleep(50) }
        return (ime() > 0) == up
    }
    private fun field(name: String) = compose.onNodeWithContentDescription("$name, exact value")

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
            root = androidx.compose.ui.platform.LocalView.current
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
    /** Scrolls [tag] into view and, like a user would, clear of the header and the library icon (scroll-to-node stops at the edge). */
    private fun reveal(tag: String) {
        val grid = compose.onNodeWithTag("graph-library-column").onChildren().filterToOne(hasScrollAction())
        grid.performScrollToNode(hasTestTag(tag)); compose.waitForIdle()
        val col = bounds("graph-library-column"); val r = bounds(tag)
        val low = r.bottom - (col.bottom - GraphLayout.scrimSize() - 2f); val high = (col.top + GraphLayout.LIBRARY_HEADER + 2f) - r.top
        val by = when { low > 0f -> low; high > 0f -> -high; else -> 0f }
        if (by != 0f) { grid.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, by * compose.density.density) }; compose.waitForIdle() }
    }
    private fun visible(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
        bounds(tag).let { r -> val c = bounds("graph-library-column"); r.top >= c.top + GraphLayout.LIBRARY_HEADER && r.bottom <= c.bottom - 40f }
    /**
     * Long-press [fromTag] and drag it through the library container (which owns the drag): to the top or bottom edge
     * so auto-scroll runs until [targetTag] is in view, then onto [targetY] (computed from the target's root bounds).
     */
    private fun libraryDrag(fromTag: String, targetTag: String, up: Boolean, target: (Rect) -> Offset) {
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
        val t = target(bounds(targetTag)) - Offset(col.left, col.top)
        compose.onNodeWithTag("graph-library-column").performTouchInput { moveTo(Offset(x * density, t.y * density)); moveTo(t * density); up() }
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
        click("graph-library"); click("graph-type-HOLD"); click("graph-library")
        compose.onNodeWithTag("graph-library-save").assertIsNotEnabled()
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
        libraryDrag("graph-lib-preset:quad.in", "graph-lib-cap|pinned", up = true) { Offset(it.left + 4f, it.bottom + 8f) }
        assertEquals("pinned first after the drop: ${store.state.value.pinned}", "preset:quad.in", store.state.value.pinned.firstOrNull())
        // Unpin by dragging a pinned curve out of Pinned, down into Presets.
        reveal("graph-lib-preset:back.out")
        libraryDrag("graph-lib-preset:back.out", "graph-lib-cap|presets", up = false) { Offset(it.left + 10f, it.bottom + 20f) }
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
        libraryDrag("graph-lib-preset:circ.out", "graph-lib-${fifteen[1]}", up = true) { it.center }
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

    /** GRAPH G2 (A1/A2): a 2-column grid, no Types, ≥ 4 rows at 268dp, and the icon's fade is the same small scrim open and closed. */
    @Test fun gridSectionsRowsAndTheIconFade() {
        start()
        for ((h, min) in listOf(268f to 4f, 328f to 5f)) {
            compose.runOnIdle { size.value = 360f to h }; compose.waitForIdle()
            if (compose.onAllNodesWithTag("graph-page").fetchSemanticsNodes().isEmpty()) {
                compose.onNodeWithContentDescription("Transform").performClick(); compose.waitForIdle()
                click("transform-mode-ROTATION"); click("inspector-rail-graph")
            }
            val icon = bounds("graph-library"); val scrimClosed = bounds("graph-library-scrim")
            click("graph-library")
            compose.onAllNodesWithTag("graph-lib-cap|types", useUnmergedTree = true).assertCountEquals(0)
            compose.onAllNodesWithTag("graph-lib-type-HOLD", useUnmergedTree = true).assertCountEquals(0)
            val col = bounds("graph-library-column")
            // Two columns: the first two pinned cells share a row.
            val a = bounds("graph-lib-preset:css.ease_in_out"); val b = bounds("graph-lib-preset:back.in")
            assertEquals(a.top, b.top, .5f); assertTrue(b.left >= a.right - .5f)
            assertTrue("cell width ${a.width}", a.width in 36f..41f)
            // Rows fully visible below the header.
            val cells = compose.onAllNodes(hasTestTag("graph-lib-preset:css.ease_in_out") or hasTestTag("graph-lib-preset:back.in") or
                hasTestTag("graph-lib-preset:back.out") or hasTestTag("graph-lib-preset:back.in_out") or hasTestTag("graph-lib-preset:expo.in") or
                hasTestTag("graph-lib-preset:expo.out") or hasTestTag("graph-lib-preset:expo.in_out") or hasTestTag("graph-lib-preset:circ.in_out") or
                hasTestTag("graph-lib-preset:cubic.in_out") or hasTestTag("graph-lib-preset:quad.out") or hasTestTag("graph-lib-preset:material.standard") or
                hasTestTag("graph-lib-preset:material.emphasized"), useUnmergedTree = true).fetchSemanticsNodes()
            // boundsInRoot is clipped to the viewport, so a row's real bottom is its top + the (unclipped) row height.
            val rowHeight = a.height
            val rows = cells.map { it.boundsInRoot.top / density() }.distinct()
                .count { top -> top >= col.top + GraphLayout.LIBRARY_HEADER - .5f && top + rowHeight <= col.bottom + .5f }
            val tops = cells.map { it.boundsInRoot.top / density() - col.top }.distinct().sorted()
            android.util.Log.d("GraphG2", "grid at 360x$h: rows fully visible=$rows (row height ${a.height}, column ${col.width}x${col.height}, row tops below the column top=$tops, caption=${bounds("graph-lib-cap|pinned")})")
            assertTrue("rows visible at $h: $rows", rows >= min)
            // The icon and its scrim do not move; the scrim is the icon + 6dp (left and top) in both states.
            assertEquals(icon, bounds("graph-library")); assertEquals(scrimClosed, bounds("graph-library-scrim"))
            val scrim = bounds("graph-library-scrim")
            assertEquals(icon.left - GraphLayout.SCRIM_EXTENT, scrim.left, .6f); assertEquals(icon.top - GraphLayout.SCRIM_EXTENT, scrim.top, .6f)
            assertEquals(icon.right, scrim.right, .6f); assertEquals(icon.bottom, scrim.bottom, .6f)
            // A cell 12dp or more from the icon is entirely outside the scrim (fully visible).
            val clear = cells.map { it.boundsInRoot.let { r -> Rect(r.left / density(), r.top / density(), r.right / density(), r.bottom / density()) } }
                .filter { it.right <= icon.left - 12f || it.bottom <= icon.top - 12f }
            assertTrue(clear.isNotEmpty()); assertTrue(clear.all { !it.overlaps(scrim) })
            click("graph-library")
        }
    }
    private fun density() = compose.density.density

    /** GRAPH G2 (A3) / G2.1 §7: the xy popover edits the stored curve with typed fields; the graph is untouched. */
    @Test fun xyPopoverEditsAndPersistsTheStoredCurve() {
        var s = CurveLibraryState(pinned = listOf("user:m"))
        s = CurveLibrary.add(s, "Mine", EasingCurve.bezier(.1f, .9f, .2f, 1f), "user:m")!!.first
        start(s)
        val graph = curve()
        compose.onNodeWithTag("graph-pinned-user:m").performTouchInput { longClick() }; compose.waitForIdle()
        click("graph-curve-xy")
        field("y1").performClick(); field("y1").performTextReplacement("0.4"); field("y1").performImeAction(); compose.waitForIdle()
        assertEquals(EasingCurve.bezier(.1f, .4f, .2f, 1f), store.state.value.user[0].curve)
        // Edit again, invalid input is discarded, empty keeps the value.
        field("x2").performClick(); field("x2").performTextReplacement("abc"); field("x2").performImeAction(); compose.waitForIdle()
        field("y1").performClick(); field("y1").performTextReplacement(""); field("y1").performImeAction(); compose.waitForIdle()
        field("x1").performClick(); field("x1").performTextReplacement("5"); field("x1").performImeAction(); compose.waitForIdle()
        assertEquals("x clamps to 1", EasingCurve.bezier(1f, .4f, .2f, 1f), store.state.value.user[0].curve)
        assertEquals(graph, curve()); assertEquals(1_234_567L, vm.state.playheadUs)
        compose.onNodeWithTag("graph-pinned-user:m").assertExists()
    }

    /**
     * GRAPH G2.1 §7: a compact 2 x 2 grid of independent fields. No auto-focus; editing one field and tapping away
     * changes only it and closes the keyboard; Done never brings the keyboard back; moving to another field keeps the
     * keyboard up; y2 is fully visible above the keyboard, at 268dp and 328dp.
     */
    @Test fun xyFieldsAreIndependentAndStayAboveTheKeyboard() {
        for (h in listOf(268f, 328f)) {
            var s = CurveLibraryState(pinned = listOf("user:m"))
            s = CurveLibrary.add(s, "Mine", EasingCurve.bezier(.1f, .9f, .2f, 1f), "user:m")!!.first
            if (h == 268f) start(s) else {
                compose.runOnIdle { store.update { s }; size.value = 360f to h }; compose.waitForIdle()
                if (compose.onAllNodesWithTag("graph-page").fetchSemanticsNodes().isEmpty()) {
                    compose.onNodeWithContentDescription("Transform").performClick(); compose.waitForIdle()
                    click("transform-mode-ROTATION"); click("inspector-rail-graph")
                }
            }
            val graph = curve()
            compose.onNodeWithTag("graph-pinned-user:m").performTouchInput { longClick() }; compose.waitForIdle()
            click("graph-curve-xy")
            // No auto-focus, no keyboard.
            Thread.sleep(600); compose.waitForIdle()
            compose.onAllNodes(isFocused()).assertCountEquals(0); assertTrue("no keyboard on open at $h", waitIme(false, 300))
            // 2 x 2, values-bar sized tiles.
            val t = listOf("x1", "y1", "x2", "y2").map { field(it).fetchSemanticsNode().boundsInRoot }
            val tiles = (0..3).map { compose.onNodeWithTag("graph-curve-value-$it").fetchSemanticsNode().boundsInRoot }
            assertEquals(tiles[0].top, tiles[1].top, 1f); assertTrue(tiles[2].top >= tiles[0].bottom - 1f); assertEquals(tiles[0].left, tiles[2].left, 1f)
            val d = compose.density.density
            assertEquals(XyEdit.TILE_W, tiles[0].width / d, 1f); assertEquals(XyEdit.TILE_H, tiles[0].height / d, 1f)
            assertTrue(t.all { it.width > 0f })
            // Edit only x1, then tap away (inside the popover, between the tiles): only x1 changes, the keyboard closes.
            field("x1").performClick(); waitIme(true)
            field("x1").performTextReplacement("0.3")
            compose.onNodeWithTag("graph-curve-values").performTouchInput { click(center) }; compose.waitForIdle()
            assertEquals(EasingCurve.bezier(.3f, .9f, .2f, 1f), store.state.value.user.first { it.id == "user:m" }.curve)
            compose.onAllNodes(isFocused()).assertCountEquals(0); assertTrue("keyboard closed after tap-away at $h", waitIme(false))
            // Moving straight to another field commits the first and keeps the keyboard up.
            field("x2").performClick(); assertTrue(waitIme(true)); field("x2").performTextReplacement("0.25")
            field("y1").performClick(); compose.waitForIdle(); Thread.sleep(300)
            assertEquals(.25f, store.state.value.user.first { it.id == "user:m" }.curve.c, 1e-4f)
            android.util.Log.d("GraphG21", "xy at 360x$h: ime while moving between fields=${ime()}")
            field("y1").performImeAction(); compose.waitForIdle()
            // All four with Done each; after Done the keyboard stays hidden (600ms).
            for ((name, v) in listOf("x1" to "0.15", "y1" to "0.8", "x2" to "0.35", "y2" to "1.1")) {
                field(name).performClick(); waitIme(true)
                field(name).performTextReplacement(v); field(name).performImeAction(); compose.waitForIdle()
                Thread.sleep(600); compose.waitForIdle()
                compose.onAllNodes(isFocused()).assertCountEquals(0)
                assertEquals("keyboard stays hidden after Done on $name at $h", 0, ime())
            }
            assertEquals(EasingCurve.bezier(.15f, .8f, .35f, 1.1f), store.state.value.user.first { it.id == "user:m" }.curve)
            // y2 lies inside the window and above the keyboard while it is up.
            field("y2").performClick(); val up = waitIme(true)
            Thread.sleep(400); compose.waitForIdle()
            val node = field("y2").fetchSemanticsNode()
            // The window (decor view), not the test's 268dp host view.
            val win = root!!.rootView
            val loc = IntArray(2); win.getLocationOnScreen(loc)
            val top = node.positionOnScreen.y; val bottom = top + node.size.height
            val imeTop = loc[1] + win.height - ime()
            android.util.Log.d("GraphG21", "xy y2 at 360x$h: top=$top bottom=$bottom imeTop=$imeTop window=${loc[1]}..${loc[1] + win.height} ime=${ime()} up=$up")
            assertTrue("keyboard up for the y2 check at $h", up)
            assertTrue("y2 inside the window at $h", top >= loc[1]); assertTrue("y2 above the keyboard at $h: $bottom > $imeTop", bottom <= imeTop)
            field("y2").performImeAction(); compose.waitForIdle(); waitIme(false)
            assertEquals("the graph is untouched", graph, curve()); assertEquals(1_234_567L, vm.state.playheadUs)
            Espresso.pressBack(); compose.waitForIdle()
        }
    }

    /**
     * GRAPH G2.1 §8: long-press lifts the tile into an overlay that follows the pointer exactly, the other pinned cells
     * slide aside as the slot changes, nothing is written while dragging, and the drop is one write.
     */
    @Test fun liftFollowReflowAndOneWritePerDrop() {
        start()
        click("graph-library")
        val p = store.state.value.pinned
        val col = bounds("graph-library-column")
        val from = bounds("graph-lib-${p[0]}"); val target = bounds("graph-lib-${p[3]}")
        val before3 = target; val writes = store.writes
        compose.mainClock.autoAdvance = false
        val start = Offset(from.center.x - col.left, from.center.y - col.top)
        compose.onNodeWithTag("graph-library-column").performTouchInput { down(start * density) }
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithTag("graph-library-column").performTouchInput { moveTo((start + Offset(0f, 12f)) * density) }
        compose.mainClock.advanceTimeBy(32)
        val lifted = compose.onNodeWithTag("graph-drag-lifted", useUnmergedTree = true)
        lifted.assertExists()
        fun liftedCentre() = lifted.getBoundsInRoot().let { Offset(((it.left + it.right) / 2).value, ((it.top + it.bottom) / 2).value) }
        val grab = liftedCentre() - (Offset(col.left, col.top) + start + Offset(0f, 12f))
        // It follows the pointer exactly.
        for (step in listOf(Offset(10f, 20f), Offset(-6f, 30f), Offset(15f, 8f))) {
            val at = start + Offset(0f, 12f) + step
            compose.onNodeWithTag("graph-library-column").performTouchInput { moveTo(at * density) }
            compose.mainClock.advanceTimeBy(16)
            val c = liftedCentre() - (Offset(col.left, col.top) + at)
            assertEquals("follows x", grab.x, c.x, 1f); assertEquals("follows y", grab.y, c.y, 1f)
        }
        // Onto pinned cell 4 (row 2, right): the cells between slide aside (animated), nothing is written yet.
        val over = Offset(target.center.x - col.left, target.center.y - col.top)
        compose.onNodeWithTag("graph-library-column").performTouchInput { moveTo(over * density) }
        compose.mainClock.advanceTimeBy(400)
        assertEquals("no store writes while dragging", writes, store.writes)
        assertNotEquals("cell 4 moved aside", before3, bounds("graph-lib-${p[3]}"))
        compose.onNodeWithTag("graph-library-column").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(300); compose.mainClock.autoAdvance = true; compose.waitForIdle()
        assertEquals("one write per drop", writes + 1, store.writes)
        assertEquals(3, store.state.value.pinned.indexOf(p[0])); assertEquals(p - p[0], store.state.value.pinned - p[0])
        compose.onAllNodesWithTag("graph-drag-lifted", useUnmergedTree = true).assertCountEquals(0)
        assertEquals(1_234_567L, vm.state.playheadUs)
    }

    /** GRAPH G2.1 §8: auto-scroll (56dp ramped zone) carries a lifted pinned curve to My curves; dropping there unpins it. */
    @Test fun autoScrollReachesAFarSectionAndUnpins() {
        var s = CurveLibraryState()
        for (i in 0 until 6) s = CurveLibrary.add(s, "c$i", EasingCurve.bezier(.1f * i, .5f, .5f, 1f), "user:$i")!!.first
        start(s)
        click("graph-library")
        val p = store.state.value.pinned; val writes = store.writes
        val col = bounds("graph-library-column"); val from = bounds("graph-lib-${p[0]}")
        val x = from.center.x - col.left
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("graph-library-column").performTouchInput { down(Offset(x, from.center.y - col.top) * density) }
        compose.mainClock.advanceTimeBy(900)
        // Rest the finger inside the bottom zone (10dp from the edge) and count frames until My curves shows.
        compose.onNodeWithTag("graph-library-column").performTouchInput { moveTo(Offset(x, (col.height - 10f) * density)) }
        var frames = 0
        while (!visible("graph-lib-cap|mine") && frames < 600) { compose.mainClock.advanceTimeBy(16); frames++ }
        val grid = compose.onNodeWithTag("graph-library-grid")
        android.util.Log.d("GraphG21", "auto-scroll to My curves: $frames frames (${frames * 16} ms) at 360x268")
        assertTrue("reached My curves in $frames frames", visible("graph-lib-cap|mine"))
        assertTrue("faster than G2 (about 3x): $frames frames", frames < 200)
        assertEquals(writes, store.writes)
        val mine = bounds("graph-lib-cap|mine")
        compose.onNodeWithTag("graph-library-column").performTouchInput { moveTo(Offset(x, mine.bottom - col.top + 20f) * density); up() }
        compose.mainClock.advanceTimeBy(300); compose.mainClock.autoAdvance = true; compose.waitForIdle()
        grid.assertExists()
        assertFalse("unpinned: ${store.state.value.pinned}", p[0] in store.state.value.pinned)
        assertEquals(writes + 1, store.writes); assertEquals(1_234_567L, vm.state.playheadUs)
    }

    /** GRAPH G2.1 §8: the pinned rail reorders with the same lift and slide-aside; one write. */
    @Test fun railReorderLiftsFollowsAndWritesOnce() {
        start()
        val p = store.state.value.pinned; val writes = store.writes
        val rail = bounds("graph-pinned-rail"); val a = bounds("graph-pinned-${p[0]}"); val d = bounds("graph-pinned-${p[1]}")
        // Outside both auto-scroll zones (the rail would scroll under the finger and the slot would run on).
        android.util.Log.d("GraphG21", "rail ${rail.height}dp tall; target ${d.center.y - rail.top}dp from the top")
        compose.mainClock.autoAdvance = false
        val s0 = Offset(a.center.x - rail.left, a.center.y - rail.top)
        compose.onNodeWithTag("graph-pinned-rail").performTouchInput { down(s0 * density) }
        compose.mainClock.advanceTimeBy(900)
        var y = s0.y
        val end = d.center.y - rail.top
        while (y < end) { y = minOf(end, y + 6f); val at = Offset(s0.x, y); compose.onNodeWithTag("graph-pinned-rail").performTouchInput { moveTo(at * density) }; compose.mainClock.advanceTimeBy(16) }
        compose.onNodeWithTag("graph-drag-lifted", useUnmergedTree = true).assertExists()
        compose.mainClock.advanceTimeBy(300)
        assertEquals(writes, store.writes)
        compose.onNodeWithTag("graph-pinned-rail").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(300); compose.mainClock.autoAdvance = true; compose.waitForIdle()
        assertEquals(writes + 1, store.writes)
        assertEquals("moved to slot 2: ${store.state.value.pinned}", 1, store.state.value.pinned.indexOf(p[0]))
        assertEquals(1_234_567L, vm.state.playheadUs)
    }

    /**
     * GRAPH G2 (A4) regression: a typed entry opened right after another one closed used to cancel itself (the previous
     * keyboard sliding down read as Back), so the second popover closed at once and the typed value was lost.
     */
    @Test fun aSecondTypedEntryRightAfterTheFirstStaysOpenAndCommits() {
        start()
        fun popups() = compose.onAllNodes(isPopup()).fetchSemanticsNodes().size
        click("graph-value-1")
        compose.onNodeWithContentDescription("y1, exact value in ").performTextReplacement("0.4")
        compose.onNodeWithContentDescription("y1, exact value in ").performImeAction(); compose.waitForIdle()
        assertEquals(.4f, curve().b, 1e-4f)
        // Immediately: the next tile, then the same tile again, each while the previous keyboard is still going down.
        click("graph-value-0")
        assertEquals("the second entry stays open", 1, popups())
        compose.onNodeWithContentDescription("x1, exact value in ").performTextReplacement("0.2")
        compose.onNodeWithContentDescription("x1, exact value in ").performImeAction(); compose.waitForIdle()
        assertEquals(.2f, curve().a, 1e-4f)
        click("graph-value-1")
        assertEquals(1, popups())
        compose.onNodeWithContentDescription("y1, exact value in ").performTextReplacement("0.6")
        compose.onNodeWithContentDescription("y1, exact value in ").performImeAction(); compose.waitForIdle()
        assertEquals(.6f, curve().b, 1e-4f)
        // The same in the library's xy popover.
        var st = CurveLibrary.add(store.state.value, "Mine", EasingCurve.bezier(.1f, .9f, .2f, 1f), "user:m")!!.first
        st = CurveLibrary.pinAt(st, "user:m", 0)
        compose.runOnIdle { store.update { st } }; compose.waitForIdle()
        compose.onNodeWithTag("graph-pinned-rail").performScrollToNode(hasTestTag("graph-pinned-user:m"))
        compose.onNodeWithTag("graph-pinned-user:m").performTouchInput { longClick() }; compose.waitForIdle()
        click("graph-curve-xy")
        field("y2").performClick(); field("y2").performTextReplacement("0.5"); field("y2").performImeAction(); compose.waitForIdle()
        assertEquals(.5f, store.state.value.user.first { it.id == "user:m" }.curve.d, 1e-4f)
    }
}
