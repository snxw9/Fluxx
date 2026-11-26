package com.fluxx.android.ui

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.fluxx.android.editor.*
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import com.fluxx.android.render.PreviewController
import com.fluxx.android.ui.editor.EditorScreen
import com.fluxx.android.ui.theme.FluxxTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TL3.1 measurements on the A16 (fixture compositions only; nothing is asserted about speed, the numbers are logged with
 * the tag TL31 and printed into the instrumentation status). A3: frame durations of the first scrubs after opening
 * twirl-downs. D3: playback frame pacing with twirl-downs collapsed vs expanded. Parts C / D1 / D2 are added below.
 */
class Tl31MeasureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = compose.activity
    private var previousLibrary: CurveLibraryStore? = null
    @Before fun lib() { previousLibrary = CurveLibraryStore.replaceForTest(CurveLibraryStore.inMemory()) }
    @After fun restore() { CurveLibraryStore.replaceForTest(previousLibrary) }
    private lateinit var editor: EditorViewModel
    private val controller = mutableStateOf<PreviewController?>(null)
    private val rate = FrameRate(30, 1)
    private fun f(n: Long) = rate.frameTimeUs(n)

    /** Real frame durations (ms) from the window's FrameMetrics while [block] runs. */
    private fun frames(window: Window, block: () -> Unit): List<Double> {
        val out = CopyOnWriteArrayList<Double>()
        val thread = HandlerThread("tl31-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, m, _ -> out += m.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6 }
        window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        try { block(); Thread.sleep(300) } finally { window.removeOnFrameMetricsAvailableListener(listener); thread.quitSafely() }
        return out.toList()
    }
    private fun pct(v: List<Double>, p: Double) = if (v.isEmpty()) 0.0 else v.sorted()[((v.size - 1) * p).toInt()]
    private fun report(label: String, v: List<Double>) {
        val line = "%s: n=%d max=%.1f p50=%.1f p95=%.1f p99=%.1f over16.7=%d".format(label, v.size, v.maxOrNull() ?: 0.0, pct(v, .5), pct(v, .95), pct(v, .99), v.count { it > 16.7 })
        Log.i("TL31", line)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("TL31", line) })
    }

    private fun keyed(id: Long) = CompositionLayer(id, LayerType.SOLID, name = "L$id", zOrder = (10 - id).toInt(), timing = ClipTiming(0, 0, f(600)),
        animTransform = AnimatableTransform(
            position = AnimatableProperty2D(true, keyframes = FrozenList.of((0..20).map { Keyframe2D(f(it * 25L), it * .01f, 0f) })),
            rotation = AnimatableProperty1D(true, keyframes = FrozenList.of((0..20).map { Keyframe1D(f(it * 25L + 7), it * 3f) })),
            opacity = AnimatableProperty1D(true, keyframes = FrozenList.of((0..20).map { Keyframe1D(f(it * 25L + 13), (it % 2).toFloat()) }))))

    private fun host(layers: List<CompositionLayer>) {
        editor = EditorViewModel()
        editor.dispatch(EditorAction.Load(ProjectDocument(Composition(durationUs = f(600), frameRate = rate, layers = FrozenList.of(layers)))))
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
        compose.runOnIdle { editor.dispatch(EditorAction.Seek(f(100))); controller.value!!.seek(editor.state.playheadUs) }
        Thread.sleep(500); compose.waitForIdle()
    }

    /** One scrub: a finger drag on the ruler, [steps] moves of [stepDp] dp, one per real frame (split gesture). */
    var lastScrubFrames = 0L
    private fun scrub(dir: Float, steps: Int = 40, stepDp: Float = 6f) {
        // The empty track area right of the D (a scroll of the timeline content = a scrub of the playhead).
        val area = compose.onNodeWithTag("timeline-playhead-line", useUnmergedTree = true)
        val before = editor.state.playheadUs
        area.performTouchInput { down(Offset(width / 2f + 60.dp.toPx(), height * .6f)) }
        repeat(steps) { area.performTouchInput { moveBy(Offset(dir * stepDp.dp.toPx(), 0f)) }; Thread.sleep(16) }
        area.performTouchInput { up() }
        Thread.sleep(400); compose.waitForIdle()
        lastScrubFrames = rate.nearestFrame(editor.state.playheadUs) - rate.nearestFrame(before)
    }

    @Test fun a3FirstScrubsAfterOpeningTwirlDownsVsLater() {
        host(listOf(keyed(1), keyed(2), keyed(3)))
        val window = compose.activity.window
        report("A3 baseline scrub (collapsed)", frames(window) { scrub(-1f) }); report("A3 baseline moved frames $lastScrubFrames", emptyList())
        compose.runOnIdle { editor.setExpanded(setOf(1L, 2L, 3L)) }
        Thread.sleep(600); compose.waitForIdle()
        for (i in 1..4) report("A3 scrub $i after expanding 3 layers", frames(window) { scrub(if (i % 2 == 1) 1f else -1f) })
    }

    /** Snxw: "the jolt is at the start of the timeline": scrubs that begin at 00:00, and the first frames of each scrub. */
    @Test fun a3ScrubsAtTheTimelineStartAndTheFirstFramesOfAScrub() {
        host(listOf(keyed(1), keyed(2), keyed(3)))
        compose.runOnIdle { editor.setExpanded(setOf(1L, 2L, 3L)); editor.dispatch(EditorAction.Seek(0)); controller.value!!.seek(0) }
        Thread.sleep(600); compose.waitForIdle()
        val window = compose.activity.window
        for (i in 1..3) {
            // From 00:00 rightward into the timeline, then back to 00:00 (past it: the scroll clamps at the start).
            val forward = frames(window) { scrub(-1f, steps = 20) }
            report("A3 from 00:00 scrub $i: first 6 frames", forward.take(6)); report("A3 from 00:00 scrub $i: rest", forward.drop(6))
            val back = frames(window) { scrub(1f, steps = 30) }
            report("A3 back to 00:00 scrub $i: first 6 frames", back.take(6)); report("A3 back to 00:00 scrub $i: last 10 frames", back.takeLast(10))
            report("A3 playhead after back $i: ${editor.state.playheadUs}us", emptyList())
        }
    }

    /** One vertical scroll of the track list: a finger moving UP ([up]) or down over the track area. */
    private fun vscroll(up: Boolean, steps: Int = 25, stepDp: Float = 8f) {
        val area = compose.onNodeWithTag("timeline-playhead-line", useUnmergedTree = true)
        val y0 = if (up) .85f else .35f
        area.performTouchInput { down(Offset(width / 2f + 60.dp.toPx(), height * y0)) }
        repeat(steps) {
            area.performTouchInput { moveBy(Offset(0f, (if (up) -1 else 1) * stepDp.dp.toPx())) }; Thread.sleep(16)
            // Where the D-button sits against its own row, mid-scroll (it used to lag a frame behind).
            for (id in 1L..8L) {
                val d = compose.onAllNodesWithTag("d-button-$id", useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot ?: continue
                val r = compose.onAllNodesWithTag("timeline-clip-hit-$id", useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot ?: continue
                maxDOffsetPx = maxOf(maxDOffsetPx, kotlin.math.abs(d.top - r.top))
            }
        }
        area.performTouchInput { up() }
        Thread.sleep(500); compose.waitForIdle()
    }
    var maxDOffsetPx = 0f

    /** Snxw: "with the twirl-down open on the first layer, scroll up": the jolt is on the FIRST vertical scrolls. */
    @Test fun a3VerticalScrollsWithTheFirstLayerExpanded() {
        host((1L..8L).map { keyed(it) })
        val window = compose.activity.window
        report("A3v collapsed scroll up", frames(window) { vscroll(up = true) }); report("A3v collapsed scroll down", frames(window) { vscroll(up = false) })
        // Snxw: the LAST layer's twirl-down is open (below the view); scrolling up brings it and its rows into view.
        compose.runOnIdle { editor.setExpanded(setOf(8L)) }
        Thread.sleep(800); compose.waitForIdle()
        for (i in 1..4) {
            val v = frames(window) { vscroll(up = true) }
            report("A3v last expanded scroll up $i: first 6 frames", v.take(6)); report("A3v last expanded scroll up $i: all", v)
            report("A3v last expanded scroll down $i: all", frames(window) { vscroll(up = false) })
        }
        report("A3v max D-button offset from its row during scrolls: ${maxDOffsetPx}px", emptyList())
    }

    /** A scrub without waiting for idle afterwards (to see whether the UI ever goes idle). */
    private fun scrubNoIdle(dir: Float, steps: Int) {
        val area = compose.onNodeWithTag("timeline-playhead-line", useUnmergedTree = true)
        area.performTouchInput { down(Offset(width / 2f + 60.dp.toPx(), height * .6f)) }
        repeat(steps) { area.performTouchInput { moveBy(Offset(dir * 6.dp.toPx(), 0f)) }; Thread.sleep(16) }
        area.performTouchInput { up() }
    }

    /** Snxw: "test from 00 too". Frames while IDLE after a scrub that ends past 00:00, collapsed and expanded; then vertical scrolls at 00:00. */
    @Test fun a3AtTheStartOfTheTimeline() {
        host((1L..8L).map { keyed(it) })
        compose.mainClock.autoAdvance = true
        val window = compose.activity.window
        fun idleAfterScrubPastStart(label: String) {
            compose.runOnIdle { editor.dispatch(EditorAction.Seek(f(10))); controller.value!!.seek(f(10)) }
            Thread.sleep(500)
            scrubNoIdle(1f, 30) // rightward: 10 frames back to 00:00, then past it
            Thread.sleep(800)
            val idle = frames(window) { Thread.sleep(3000) }
            report("$label: frames while idle 3 s after the scrub (playhead ${editor.state.playheadUs}us)", idle)
        }
        idleAfterScrubPastStart("A3-00 collapsed")
        compose.runOnIdle { editor.setExpanded(setOf(8L)) }; Thread.sleep(800)
        idleAfterScrubPastStart("A3-00 last layer expanded")
        compose.runOnIdle { editor.setExpanded(setOf(1L)) }; Thread.sleep(800)
        idleAfterScrubPastStart("A3-00 first layer expanded")
        // Vertical scrolls with the playhead at 00:00.
        compose.runOnIdle { editor.setExpanded(setOf(8L)); editor.dispatch(EditorAction.Seek(0)); controller.value!!.seek(0) }
        Thread.sleep(800)
        for (i in 1..3) report("A3-00 vertical scroll up $i at 00:00, last expanded", frames(window) { vscroll(up = true) })
    }

    /** A vertical scroll without waiting for idle afterwards. */
    private fun vscrollNoIdle(up: Boolean) {
        val area = compose.onNodeWithTag("timeline-playhead-line", useUnmergedTree = true)
        area.performTouchInput { down(Offset(width / 2f + 60.dp.toPx(), height * (if (up) .85f else .35f))) }
        repeat(25) { area.performTouchInput { moveBy(Offset(0f, (if (up) -1 else 1) * 8.dp.toPx())) }; Thread.sleep(16) }
        area.performTouchInput { up() }
    }

    /** Diagnosis: after a vertical scroll at 00:00, do frames continue, and does the playhead move on its own? */
    @Test fun a3VerticalScrollAt00Diagnosis() {
        host((1L..8L).map { keyed(it) })
        compose.mainClock.autoAdvance = true
        val window = compose.activity.window
        fun probe(label: String) {
            val playheads = java.util.Collections.synchronizedList(ArrayList<Long>())
            vscrollNoIdle(up = true); Thread.sleep(1000)
            val idle = frames(window) { repeat(30) { playheads += editor.state.playheadUs; Thread.sleep(100) } }
            report("$label: idle frames over 3 s, playheads seen ${playheads.distinct()}", idle)
        }
        compose.runOnIdle { editor.dispatch(EditorAction.Seek(0)); controller.value!!.seek(0) }; Thread.sleep(800)
        probe("A3-00v collapsed at 00:00")
        compose.runOnIdle { editor.setExpanded(setOf(8L)) }; Thread.sleep(800)
        probe("A3-00v last expanded at 00:00")
        compose.runOnIdle { editor.dispatch(EditorAction.Seek(f(100))); controller.value!!.seek(f(100)) }; Thread.sleep(800)
        probe("A3-00v last expanded at 03:10")
    }

    @Test fun d3PlaybackFramePacingCollapsedVsExpanded() {
        host(listOf(keyed(1), keyed(2), keyed(3)))
        val window = compose.activity.window
        fun play(label: String) {
            val v = frames(window) {
                compose.runOnIdle { controller.value!!.play() }
                Thread.sleep(4000)
                compose.runOnIdle { controller.value!!.pause() }
            }
            report(label, v)
            compose.runOnIdle { editor.dispatch(EditorAction.Seek(f(100))); controller.value!!.seek(f(100)) }
            Thread.sleep(500); compose.waitForIdle()
        }
        play("D3 playback, twirl-downs collapsed")
        compose.runOnIdle { editor.setExpanded(setOf(1L, 2L, 3L)) }
        Thread.sleep(600); compose.waitForIdle()
        play("D3 playback, 3 layers expanded")
    }
}
