package com.fluxx.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.editor.*
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import com.fluxx.android.render.PreviewController
import com.fluxx.android.render.PreviewFrames
import com.fluxx.android.ui.editor.EditorScreen
import com.fluxx.android.ui.theme.FluxxTheme
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * AUD1 A6: scrub responsiveness after Pause, after playback ran to the end, after a tap seek, and with no playback (the
 * control), forward and backward, collapsed and with 3 twirl-downs, on the real benchmark file (skips when it is missing).
 */
class Aud1ScrubTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = compose.activity
    private var previousLibrary: CurveLibraryStore? = null
    private lateinit var editor: EditorViewModel
    private val controller = mutableStateOf<PreviewController?>(null)
    private lateinit var file: File
    private lateinit var media: Aud1Bench.Media
    private val presented = CopyOnWriteArrayList<LongArray>()

    @Before fun setUp() {
        previousLibrary = CurveLibraryStore.replaceForTest(CurveLibraryStore.inMemory())
        val f = Aud1Bench.file(context)
        assumeTrue("AUD1: the benchmark file '${Aud1Bench.PREFIX}…' was not found or is not readable (${Aud1Bench.lookupLog}); skipping", f != null)
        file = f!!; media = Aud1Bench.media(file)
        PreviewFrames.testObserver = { t, s, p -> presented += longArrayOf(t, s, p) }
    }
    @After fun tearDown() { CurveLibraryStore.replaceForTest(previousLibrary); PreviewFrames.testObserver = null }
    private fun report(line: String) {
        android.util.Log.i("AUD1", line)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("AUD1", line) })
    }
    private val c get() = controller.value!!

    private fun host(project: ProjectDocument) {
        editor = EditorViewModel(); editor.dispatch(EditorAction.Load(project))
        compose.setContent { FluxxTheme { EditorScreen(editor, MediaRepository(context), controller.value, null, true, false,
            onSurfaceAvailable = { s, w, h ->
                if (controller.value == null) controller.value = PreviewController(context, s, w, h, onPosition = { editor.reportPosition(it) })
                controller.value!!.update(editor.displayedState.project, editor.displayedState.playheadUs, false)
            }, onSurfaceDestroyed = {}, onSurfaceResized = { _, _ -> }, onRequestMediaPermission = {}, onOpenDocumentPicker = {},
            onSaveProject = {}, onLoadProject = {}, onExportStart = {}, onExportCancel = {}, onOpenExportedVideo = {}) } }
        compose.waitUntil(10_000) { controller.value != null }
        Thread.sleep(1500)
    }

    data class Result(val firstFrameMs: Double, val caughtUpMs: Double, val longestGapMs: Double, val maxRenderMs: Double)

    /** One scrub over the track area ([dir] -1 = forward in time, +1 = backward), 20 moves 16 ms apart. */
    private fun scrub(dir: Float): Result {
        val area = compose.onNodeWithTag("timeline-playhead-line", useUnmergedTree = true)
        presented.clear()
        area.performTouchInput { down(Offset(width / 2f + 60.dp.toPx(), height * .6f)) }
        val firstMove = System.nanoTime()
        repeat(20) { area.performTouchInput { moveBy(Offset(dir * 8.dp.toPx(), 0f)) }; Thread.sleep(16) }
        val lastMove = System.nanoTime()
        area.performTouchInput { up() }
        val target = FrameRate(media.fps, 1).let { r -> compose.runOnIdle { r.frameTimeUs(r.nearestFrame(editor.state.playheadUs)) } }
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline && presented.none { it[0] == target }) Thread.sleep(5)
        Thread.sleep(200)
        val frames = presented.toList().filter { it[2] >= firstMove }
        val first = frames.firstOrNull()?.let { (it[2] - firstMove) / 1e6 } ?: Double.NaN
        val caught = frames.firstOrNull { it[0] == target }?.let { (it[2] - lastMove) / 1e6 } ?: Double.NaN
        val gaps = (listOf(firstMove) + frames.map { it[2] }).zipWithNext { a, b -> (b - a) / 1e6 }
        return Result(first, caught, gaps.maxOrNull() ?: Double.NaN, frames.maxOfOrNull { (it[2] - it[1]) / 1e6 } ?: Double.NaN)
    }

    private fun seekTo(us: Long) { compose.runOnIdle { editor.dispatch(EditorAction.Seek(us)); c.seek(editor.state.playheadUs) }; Thread.sleep(1500) }
    private fun playFor(fromUs: Long, ms: Long) { seekTo(fromUs); compose.runOnIdle { c.play() }; Thread.sleep(ms); compose.runOnIdle { c.pause() } }

    private fun case(label: String, setup: () -> Unit) {
        val out = HashMap<String, MutableList<Result>>()
        repeat(2) {
            for ((name, dir) in listOf("forward" to -1f, "backward" to 1f)) {
                setup()
                out.getOrPut(name) { ArrayList() } += scrub(dir)
            }
        }
        for ((name, rs) in out) report("A6 $label, $name: first frame after the first move ${rs.joinToString("/") { "%.0f".format(it.firstFrameMs) }}ms; caught up after the last move ${rs.joinToString("/") { "%.0f".format(it.caughtUpMs) }}ms; longest gap without a new frame ${rs.joinToString("/") { "%.0f".format(it.longestGapMs) }}ms; slowest frame render ${rs.joinToString("/") { "%.0f".format(it.maxRenderMs) }}ms")
    }

    private fun allCases(tag: String) {
        val mid = media.durationUs / 2; val nearEnd = media.durationUs - 8_000_000
        case("$tag control (no playback) at the middle") { seekTo(mid) }
        case("$tag after Pause at the start") { playFor(2_000_000, 3000) }
        case("$tag after Pause at the middle") { playFor(mid, 3000) }
        case("$tag after Pause near the end") { playFor(nearEnd, 3000) }
        case("$tag after playback ran to the end") { seekTo(media.durationUs - 2_500_000); compose.runOnIdle { c.play() }; Thread.sleep(4500) }
        case("$tag after a tap seek") { seekTo(mid + 7_000_000) }
    }

    @Test fun a6ScrubAfterPausePlaybackEndAndTapCollapsed() {
        host(Aud1Bench.project(file, media, extra = 2))
        allCases("collapsed,")
    }

    @Test fun a6ScrubWithThreeTwirlDowns() {
        host(Aud1Bench.project(file, media, extra = 2))
        compose.runOnIdle { editor.setExpanded(setOf(1L, 10L, 11L)) }; Thread.sleep(800)
        allCases("3 expanded,")
    }
}
