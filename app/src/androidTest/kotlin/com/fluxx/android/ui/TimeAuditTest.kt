package com.fluxx.android.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.editor.*
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import com.fluxx.android.render.PreviewController
import com.fluxx.android.ui.editor.EditorScreen
import com.fluxx.android.ui.inspector.GraphTime
import com.fluxx.android.ui.theme.Background
import com.fluxx.android.ui.theme.FluxxTheme
import com.fluxx.android.ui.theme.TextSecondary
import com.fluxx.android.ui.timeline.TimelineZoomKey
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/**
 * GRAPH G2.1 §6a + TL1 on the A16 (fixture compositions, never the user's projects). Scrubs the real timeline at three zoom
 * levels per rate and checks, after release: the playhead is on a frame, the playhead line covers its frame's tick (0 px),
 * the pill shows that frame, and an exact pixel scan of the ruler around the playhead finds every expected tick as a
 * single solid run of whole pixels (no anti-aliased ghost columns) at its tier's height. Logs a table (tag TL1).
 */
class TimeAuditTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var previousLibrary: CurveLibraryStore? = null
    @Before fun lib() { previousLibrary = CurveLibraryStore.replaceForTest(CurveLibraryStore.inMemory()) }
    @After fun restore() { CurveLibraryStore.replaceForTest(previousLibrary) }
    private lateinit var editor: EditorViewModel
    private val controller = mutableStateOf<PreviewController?>(null)

    private fun host(rate: FrameRate, start: Long, layer: CompositionLayer? = null) {
        editor = EditorViewModel()
        editor.dispatch(EditorAction.Load(ProjectDocument(Composition(durationUs = 10_000_000, frameRate = rate, startTimecodeUs = start,
            layers = FrozenList.of(listOf(layer ?: CompositionLayer(1, LayerType.SOLID, name = "Audit", timing = ClipTiming(0, 0, 10_000_000))))))))
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
        compose.runOnIdle { editor.dispatch(EditorAction.Seek(rate.frameTimeUs(45))); controller.value!!.seek(editor.state.playheadUs) }
        settle()
    }
    private fun settle() { Thread.sleep(700); compose.waitForIdle() }
    private fun scroller() = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)).fetchSemanticsNodes()
        .first { n -> compose.onNodeWithTag("timeline-ruler").fetchSemanticsNode().let { r -> n.boundsInRoot.top <= r.boundsInRoot.top && n.boundsInRoot.bottom >= r.boundsInRoot.bottom } }
    private fun pxPerUs() = compose.onNodeWithTag("timeline-ruler").fetchSemanticsNode().config[TimelineZoomKey]
    private fun codes() = compose.onAllNodes(SemanticsMatcher("timecode") { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { Regex("""\d{2}:\d{2}:\d{2}""").containsMatchIn(it.text) } == true }, useUnmergedTree = true)
        .fetchSemanticsNodes().flatMap { n -> n.config[SemanticsProperties.Text].map { it.text } }
    /** A slow one-finger drag on the ruler row (never under the inspector sheet; no fling), by [dx] px. */
    private fun rulerY() = compose.onNodeWithTag("timeline-ruler").fetchSemanticsNode().boundsInRoot.let { it.top + it.height * .75f }
    private fun scrub(dx: Float) {
        val v = scroller().boundsInRoot
        val ry = rulerY()
        compose.onRoot().performTouchInput {
            // Start clear of the playhead marker's 48dp target at the centre (holding there opens the marker popover).
            val y = ry; val x0 = v.center.x + 48f * density * (if (dx < 0) 1.6f else -1.6f)
            down(Offset(x0, y)); val steps = 30
            repeat(steps) { i -> moveTo(Offset(x0 + dx * (i + 1) / steps, y)); advanceEventTime(30) }
            advanceEventTime(300); up()
        }
        settle()
    }
    private fun zoom(factor: Float) {
        val v = scroller().boundsInRoot
        val ry = rulerY()
        compose.onRoot().performTouchInput {
            val c = Offset(v.center.x, ry)
            val a = 48f * density * 1.6f; val b = a * factor
            pinch(c - Offset(a, 0f), c - Offset(b, 0f), c + Offset(a, 0f), c + Offset(b, 0f), 500)
        }
        settle()
    }

    /** After a scrub: the playhead is on a frame, scroll = frameX, the line covers the tick, the pill shows the frame. Returns the line - tick offset. */
    private fun check(rate: FrameRate, start: Long, log: StringBuilder, what: String): Float {
        val p = editor.state.playheadUs
        val f = rate.nearestFrame(p)
        assertEquals("$what: playhead on a whole frame", rate.frameTimeUs(f), p)
        val scale = TimelineScale(rate, pxPerUs())
        val sc = scroller(); val scroll = sc.config[SemanticsProperties.HorizontalScrollAxisRange].value()
        assertEquals("$what: scroll is the frame's x", scale.frameX(f).toFloat(), scroll, 0f)
        val view = sc.boundsInRoot
        val centre = Math.round(view.width / 2f)
        val density = compose.density.density
        val lineW = Math.round(1f * density).coerceAtLeast(1)
        val tickLeft = view.left + centre + scale.frameX(f) - scroll - lineW / 2
        val line = compose.onNodeWithTag("timeline-playhead-line", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val offset = line.left - tickLeft
        val expected = CompositionTimecode.formatPlayhead(p, start, rate)
        val shown = codes()
        log.append("$what | frame $f | ${"%.1f".format(scale.pxPerFrame)} px/frame | pill ${shown.joinToString(" ")} | line - tick ${"%.2f".format(offset)} px\n")
        assertTrue("$what: within 0.5 px ($offset)", abs(offset) <= .5f)
        assertTrue("$what: the pill shows $expected: $shown", expected in shown)
        return offset
    }

    /** Exact column scan of the ruler around the playhead: every expected tick is one solid run; nothing else is ink. */
    private fun scan(rate: FrameRate, start: Long, log: StringBuilder, what: String): Set<TickTier> {
        val density = compose.density.density
        val scale = TimelineScale(rate, pxPerUs())
        val level = RulerTiers.level(rate, scale.pxPerFrame, density)
        val sc = scroller(); val scroll = sc.config[SemanticsProperties.HorizontalScrollAxisRange].value().toLong(); val view = sc.boundsInRoot
        val centre = Math.round(view.width / 2f)
        val ruler = compose.onNodeWithTag("timeline-ruler").fetchSemanticsNode().boundsInRoot
        val line = compose.onNodeWithTag("timeline-playhead-line", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val img = compose.onRoot().captureToImage().asAndroidBitmap()
        val heightPx = Math.round(32f * density); val baseline = Math.round(heightPx - density)
        val row = (ruler.top + baseline - 3).toInt()
        val bg = Background.toArgb(); val inkMajor = TextSecondary.copy(alpha = .95f).compositeOver(Background).toArgb()
        val inkMinor = TextSecondary.copy(alpha = .65f).compositeOver(Background).toArgb()
        fun near(a: Int, b: Int) = listOf(16, 8, 0).all { sh -> abs((a ushr sh and 255) - (b ushr sh and 255)) <= 10 }
        val wMajor = Math.round(1f * density).coerceAtLeast(1); val wMinor = Math.round(.75f * density).coerceAtLeast(1)
        val startFrame = CompositionTimecode.startFrame(start, rate)
        val expected = HashMap<Int, TickTier>()
        // Most of the viewport around the playhead (60 px margins).
        val half = (view.width / 2f - 60f)
        val firstX = (line.left - half).toInt(); val lastX = (line.left + half).toInt()
        var f = (scale.frameAt((scroll - half - 40).toDouble())).coerceAtLeast(0)
        while (scale.frameX(f) <= scroll + half + 40) {
            RulerTiers.tier(startFrame + f, level)?.let { tier ->
                val w = if (tier == TickTier.MAJOR) wMajor else wMinor
                val left = (view.left + centre + scale.frameX(f) - scroll - w / 2).toInt()
                // Only frames inside the timeline. The first and last frames' ticks are centred on their frame, so they
                // overhang the ruler canvas by up to half a tick: that ink is expected.
                if (f <= scale.lastFrame(10_000_000L)) for (x in left until left + w) expected[x] = tier
            }
            f++
        }
        val skip = (line.left - 3).toInt()..(line.right + 3).toInt()
        val missing = ArrayList<Int>(); val ghostAt = ArrayList<Int>()
        var ink = 0; var ghosts = 0; val seen = HashSet<TickTier>(); val heights = HashMap<TickTier, Int>()
        for (x in firstX..lastX) {
            if (x in skip || x < 0 || x >= img.width) continue
            val px = img.getPixel(x, row)
            val tier = expected[x]
            if (tier != null) {
                if (!near(px, if (tier == TickTier.MAJOR) inkMajor else inkMinor)) { missing += x; continue }
                ink++; seen += tier
                if (tier !in heights) { var y = row; while (y > ruler.top && !near(img.getPixel(x, y - 1), bg)) y--; heights[tier] = (ruler.top + baseline - y).toInt() }
            } else if (!near(px, bg)) { ghosts++; ghostAt += x }
        }
        // Save the ruler strip (a tl1-audit folder in the debug app's external files; nothing is deleted).
        runCatching {
            val dir = java.io.File(context.getExternalFilesDir(null)!!, "tl1-audit").also { it.mkdirs() }
            val crop = android.graphics.Bitmap.createBitmap(img, maxOf(0, firstX), ruler.top.toInt(), minOf(lastX, img.width - 1) - maxOf(0, firstX), heightPx)
            java.io.File(dir, "ruler-${what.replace(Regex("[^A-Za-z0-9.]"), "_")}.png").outputStream().use { crop.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        if (missing.isNotEmpty() || ghostAt.isNotEmpty()) android.util.Log.d("TL1", "$what: scroll $scroll view ${view.left}..${view.right} line ${line.left} ruler ${ruler.left}..${ruler.right} missing $missing ghosts $ghostAt expected majors ${expected.filterValues { it == TickTier.MAJOR }.keys.sorted()}")
        log.append("$what | level step ${level.stepSeconds}s mids ${level.mids} minors ${level.minors} | ink columns $ink, ghost columns $ghosts | tiers $seen heights $heights px\n")
        android.util.Log.d("TL1", log.toString()); log.setLength(0)
        assertEquals("$what: no ghost or misplaced ink", 0, ghosts)
        assertTrue("$what: every expected tick is solid: missing $missing", missing.isEmpty())
        assertEquals("$what: every tier expected in the window is drawn", expected.filterKeys { it in firstX..lastX && it !in skip }.values.toSet(), seen)
        if (TickTier.MAJOR in heights && TickTier.MID in heights) assertTrue(heights[TickTier.MAJOR]!! > heights[TickTier.MID]!!)
        if (TickTier.MID in heights && TickTier.MINOR in heights) assertTrue(heights[TickTier.MID]!! > heights[TickTier.MINOR]!!)
        return seen
    }

    private fun audit(rate: FrameRate, start: Long = 0) {
        host(rate, start)
        val log = StringBuilder("\nTL1 ${CompositionTimecode.frameRateLabel(rate)} fps, start ${start}us\n")
        val offsets = ArrayList<Float>()
        // GRAPH G3.1: three tiers at the default zoom and at two zoomed-out levels (×0.12 ≈ 14dp/s; ×0.2 twice ≈ 4.8dp/s).
        val states = listOf("default 120dp/s" to listOf(), "zoomed in" to listOf(maxOf(2.2f, 7f * CompositionTimecode.nominal(rate) / 120f)),
            "zoomed out x0.12" to listOf(.12f), "zoomed out x0.04" to listOf(.2f, .2f))
        for ((zoomName, factors) in states) {
            for (f in factors) zoom(f)
            for (dx in listOf(-73.7f, -211.3f, 57.1f)) { scrub(dx); offsets += check(rate, start, log, "$zoomName scrub $dx") }
            val tiers = scan(rate, start, log, "$zoomName ${CompositionTimecode.frameRateLabel(rate)}")
            // Zoomed in, a second can be wider than the screen (60 fps), so a major may be off-window.
            if (zoomName == "zoomed in") assertTrue("minors and mids when zoomed in: $tiers", TickTier.MINOR in tiers && TickTier.MID in tiers)
            else assertEquals("three tiers at $zoomName: $tiers", setOf(TickTier.MAJOR, TickTier.MID, TickTier.MINOR), tiers)
            for (f in factors.reversed()) zoom(1f / f)
        }
        android.util.Log.d("TL1", log.toString())
        assertTrue(offsets.all { abs(it) <= .5f })
    }

    @Test fun fps24() = audit(FrameRate(24, 1))
    @Test fun fps25() = audit(FrameRate(25, 1))
    @Test fun fps2997WithAStartTimecode() = audit(FrameRate(30000, 1001), 3_600_000_000L)
    @Test fun fps30() = audit(FrameRate(30, 1))
    @Test fun fps60() = audit(FrameRate(60, 1))

    /** TL1c: with no key selected, the Graph's Time tile shows the playhead's timecode, live, as the pill does. */
    @Test fun graphTimeTileFollowsTheScrub() {
        val rate = FrameRate(30, 1)
        host(rate, 0, CompositionLayer(1, LayerType.SOLID, name = "Spin", timing = ClipTiming(0, 0, 10_000_000),
            animTransform = AnimatableTransform(rotation = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(
                Keyframe1D(0, 0f), Keyframe1D(4_000_000, 90f), Keyframe1D(8_000_000, 45f)))))))
        val row = compose.onAllNodes(hasText("Spin")).fetchSemanticsNodes().first().boundsInRoot.center.y
        compose.onRoot().performTouchInput { click(Offset(width * .8f, row)) }; settle()
        compose.onNodeWithContentDescription("Transform").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("transform-mode-ROTATION").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("inspector-rail-graph", useUnmergedTree = true).performClick(); settle()
        compose.onNodeWithTag("graph-mode-GRAPH_VALUE").performClick(); settle()
        val log = StringBuilder("\nTL1c Graph Time tile\n")
        for (dx in listOf(-90f, -37.3f, 140f)) {
            scrub(dx)
            check(rate, 0, log, "graph open, scrub $dx")
            val expected = GraphTime.playhead(editor.state.playheadUs, rate)
            compose.onNodeWithTag("graph-value-time").assertTextContains(expected, substring = true).assertIsNotEnabled()
            log.append("  Time tile $expected\n")
        }
        android.util.Log.d("TL1", log.toString())
    }

    /**
     * TL1a playback: pause rests the playhead on the frame that was on screen (floor), with no echo. Taps are injected raw
     * (UiAutomation): Compose never idles while playing, so a semantics click would only land when playback ends.
     */
    @Test fun playbackPauseRestsOnTheFrameOnScreen() {
        val rate = FrameRate(30, 1)
        host(rate, 0)
        val b = compose.onNodeWithContentDescription("Play").fetchSemanticsNode().boundsInWindow
        val origin = IntArray(2)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).first().window.decorView.getLocationOnScreen(origin)
        }
        fun tap() {
            val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
            val x = b.center.x + origin[0]; val y = b.center.y + origin[1]
            val t = android.os.SystemClock.uptimeMillis()
            fun ev(a: Int, at: Long) = android.view.MotionEvent.obtain(t, at, a, x, y, 0).also { it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            ui.injectInputEvent(ev(android.view.MotionEvent.ACTION_DOWN, t), true); ui.injectInputEvent(ev(android.view.MotionEvent.ACTION_UP, t + 40), true)
        }
        tap(); Thread.sleep(1300)
        val before = editor.state.playheadUs
        tap(); Thread.sleep(800)
        compose.waitForIdle()
        val p = editor.state.playheadUs
        android.util.Log.d("TL1", "playback: playhead before the pause tap $before us; paused at $p us (frame ${rate.nearestFrame(p)}), pill ${codes()}")
        assertTrue("it played: $p", p > rate.frameTimeUs(50) && p < 9_000_000)
        assertEquals("paused on a frame", rate.snap(p), p)
        assertTrue(CompositionTimecode.formatPlayhead(p, 0, rate) in codes())
        Thread.sleep(600); compose.waitForIdle()
        assertEquals("no echo after the pause snap", p, editor.state.playheadUs)
    }
}
