package com.fluxx.android.ui

import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * AUD1 on the A16 with the real benchmark file (read in place; the test SKIPS when it is missing or unreadable). Nothing
 * here asserts speed: every number goes to the instrumentation status (key AUD1) for the checkpoint.
 */
class Aud1BenchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = compose.activity
    private var previousLibrary: CurveLibraryStore? = null
    private lateinit var editor: EditorViewModel
    private val controller = mutableStateOf<PreviewController?>(null)
    private lateinit var file: File
    private lateinit var media: Aud1Bench.Media

    @Before fun setUp() {
        previousLibrary = CurveLibraryStore.replaceForTest(CurveLibraryStore.inMemory())
        val f = Aud1Bench.file(context)
        assumeTrue("AUD1: the benchmark file '${Aud1Bench.PREFIX}…' was not found or is not readable (${Aud1Bench.lookupLog}); skipping", f != null)
        file = f!!; media = Aud1Bench.media(file)
    }
    @After fun tearDown() { CurveLibraryStore.replaceForTest(previousLibrary); PreviewFrames.testObserver = null }

    private fun report(line: String) {
        android.util.Log.i("AUD1", line)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("AUD1", line) })
    }

    private fun host(project: ProjectDocument) {
        editor = EditorViewModel()
        editor.dispatch(EditorAction.Load(project))
        compose.setContent { FluxxTheme { EditorScreen(editor, MediaRepository(context), controller.value, null, true, false,
            onSurfaceAvailable = { s, w, h ->
                if (controller.value == null) controller.value = PreviewController(context, s, w, h, onPosition = { editor.reportPosition(it) })
                controller.value!!.update(editor.displayedState.project, editor.displayedState.playheadUs, false)
            }, onSurfaceDestroyed = {}, onSurfaceResized = { _, _ -> }, onRequestMediaPermission = {}, onOpenDocumentPicker = {},
            onSaveProject = {}, onLoadProject = {}, onExportStart = {}, onExportCancel = {}, onOpenExportedVideo = {}) } }
        compose.waitUntil(10_000) { controller.value != null }
        Thread.sleep(1500)
    }
    private val c get() = controller.value!!

    /** Seeks to [atUs], presses Play and times: "Preparing audio" shown, the track playing, and the first heard sample. */
    private fun timedPlay(label: String, atUs: Long): Long {
        compose.runOnIdle { editor.dispatch(EditorAction.Seek(atUs)); c.seek(atUs) }; Thread.sleep(800)
        var preparingFrom = -1L; var preparingTo = -1L; var playingAt = -1L; var heardAt = -1L
        val t0 = System.nanoTime()
        compose.runOnIdle { c.play() }
        while (System.nanoTime() - t0 < 120_000_000_000L) {
            val msg = c.state.value.message
            val now = System.nanoTime()
            if (msg == "Preparing audio" && preparingFrom < 0) preparingFrom = now
            if (preparingFrom >= 0 && preparingTo < 0 && msg != "Preparing audio") preparingTo = now
            val t = Aud1Bench.track(c)
            if (t != null && t.playState == android.media.AudioTrack.PLAYSTATE_PLAYING && playingAt < 0) playingAt = now
            if (t != null && playingAt >= 0) { val ts = Aud1Bench.timestamp(t)
                if (ts != null && ts.first > 0) { heardAt = ts.second - ts.first * 1_000_000_000L / 48_000; break } }
            Thread.sleep(2)
        }
        fun ms(x: Long) = if (x < 0) -1 else (x - t0) / 1_000_000
        val scratch = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("preview_audio_") }.sumOf { it.length() } +
            java.io.File(context.cacheDir, "aud1-pcm").listFiles().orEmpty().sumOf { it.length() }
        report("A1 $label: Preparing audio ${if (preparingFrom < 0) "not shown" else "${ms(preparingFrom)}..${ms(preparingTo)}ms"}; track playing ${ms(playingAt)}ms; first sound heard ${ms(heardAt)}ms after Play; scratch / PCM cache %.1f MB".format(scratch / 1e6))
        return ms(heardAt)
    }

    @Test fun a1PrepareCosts() {
        val d = Aud1Bench.decodeEnergy(file)
        report("A1 file: %.1f s %dx%d %d fps; audio ${d.format.getString(android.media.MediaFormat.KEY_MIME)} ${d.format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)} Hz ${d.format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)} ch; first decoded pts ${d.firstPtsUs}us; encoder-delay key ${if (d.format.containsKey("encoder-delay")) d.format.getInteger("encoder-delay") else "absent"}; decode-only (test decoder) ${d.decodeMs}ms"
            .format(media.durationUs / 1e6, media.width, media.height, media.fps))
        host(Aud1Bench.project(file, media))
        timedPlay("cold play from 0", 0); compose.runOnIdle { c.pause() }; Thread.sleep(500)
        timedPlay("repeat play", 0); compose.runOnIdle { c.pause() }; Thread.sleep(500)
        // One edit (move the layer one frame) and one gain edit: what the next Play pays.
        compose.runOnIdle { editor.dispatch(EditorAction.Move(1, media.let { FrameRate(it.fps, 1).frameTimeUs(1) })); c.update(editor.state.project, 0, false) }; Thread.sleep(500)
        timedPlay("after a move edit", 0); compose.runOnIdle { c.pause() }; Thread.sleep(500)
        compose.runOnIdle { editor.dispatch(EditorAction.SetAudio(1, false, .5f)); c.update(editor.state.project, 0, false) }; Thread.sleep(500)
        timedPlay("after a gain edit", 0); compose.runOnIdle { c.pause() }
    }

    /** A2 + A3 + A5: output latency, and where heard beats fall against the presented video frames. */
    private fun avRun(label: String, startUs: Long, seconds: Long) {
        val d = Aud1Bench.decodeEnergy(file)
        val beats = Aud1Bench.beats(d).filter { it > startUs + 1_000_000 && it < startUs + seconds * 1_000_000 }
        val presented = CopyOnWriteArrayList<LongArray>()
        PreviewFrames.testObserver = { t, s, p -> presented += longArrayOf(t, s, p) }
        timedPlay("$label (A/V run)", startUs)
        val stamps = ArrayList<Pair<Long, Long>>(); var latency: Int? = null; var heads = ArrayList<Pair<Long, Long>>()
        val t0 = System.nanoTime(); val startFrame = Aud1Bench.startFrame(c) ?: (startUs * 48_000 / 1_000_000)
        while (System.nanoTime() - t0 < seconds * 1_000_000_000L) {
            val t = Aud1Bench.track(c) ?: break
            Aud1Bench.timestamp(t)?.let { stamps += it }
            heads += t.playbackHeadPosition.toLong() to System.nanoTime()
            if (latency == null) latency = Aud1Bench.latencyMs(t)
            Thread.sleep(20)
        }
        val t = Aud1Bench.track(c)
        val underruns = t?.underrunCount ?: -1; val bufferFrames = t?.bufferSizeInFrames ?: -1
        compose.runOnIdle { c.pause() }
        PreviewFrames.testObserver = null
        // Head vs timestamp: how far the head (today's clock) runs ahead of what is heard.
        val headLead = stamps.zip(heads).map { (ts, hd) -> val tsPos = ts.first + (hd.second - ts.second) * 48_000 / 1_000_000_000L; (hd.first - tsPos) * 1000.0 / 48_000 }
        report("A2 $label: getLatency ${latency ?: "unavailable"}ms; buffer $bufferFrames frames (%.0fms); head ahead of heard (timestamp) ${Aud1Bench.stats(headLead)} ms; underruns $underruns".format(bufferFrames / 48.0))
        // Heard instant of a composition time [us]: from the last timestamp before it is presented.
        fun heardNs(us: Long): Long? {
            val f = (us - startUs) * 48_000 / 1_000_000 + 0 // track frame index (the track starts at startUs)
            val ts = stamps.lastOrNull { it.first <= f } ?: stamps.firstOrNull() ?: return null
            return ts.second + (f - ts.first) * 1_000_000_000L / 48_000
        }
        val frameUs = 1_000_000L / media.fps
        val offsets = beats.mapNotNull { b ->
            val h = heardNs(b) ?: return@mapNotNull null
            // The video frame that shows time b: the presented frame whose span [t, t + frame) contains b.
            val v = presented.lastOrNull { it[0] <= b && b < it[0] + frameUs } ?: return@mapNotNull null
            (h - v[2]) / 1e6
        }
        report("A3 $label: ${beats.size} beats, ${offsets.size} matched; audio heard minus video presented (ms, + = audio late): ${Aud1Bench.stats(offsets)}; median %+.1f".format(Aud1Bench.pct(offsets, .5)))
        report("A3 $label per beat: " + offsets.take(20).joinToString(" ") { "%+.0f".format(it) })
        // A4: preview frame pacing and render (decode) time per frame.
        val pres = presented.toList()
        val intervals = pres.zipWithNext { a, b -> (b[2] - a[2]) / 1e6 }
        val render = pres.map { (it[2] - it[1]) / 1e6 }
        val skipped = pres.zipWithNext { a, b -> ((b[0] - a[0]) / frameUs - 1).coerceAtLeast(0) }.sum()
        report("A4 $label: preview frames ${pres.size} in ${seconds}s (target ${seconds * media.fps}); interval ${Aud1Bench.stats(intervals)} ms; render (incl. VP9 decode) ${Aud1Bench.stats(render)} ms; skipped frames $skipped")
        Thread.sleep(800)
    }

    private fun uiFrames(window: Window, block: () -> Unit): List<Double> {
        val out = CopyOnWriteArrayList<Double>(); val th = HandlerThread("aud1-metrics").apply { start() }
        val l = Window.OnFrameMetricsAvailableListener { _, m, _ -> out += m.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6 }
        window.addOnFrameMetricsAvailableListener(l, Handler(th.looper))
        try { block(); Thread.sleep(300) } finally { window.removeOnFrameMetricsAvailableListener(l); th.quitSafely() }
        return out.toList()
    }

    @Test fun a2a3a4a5PlaybackFromStartAndMiddleCollapsedAndExpanded() {
        host(Aud1Bench.project(file, media, extra = 2))
        val window = compose.activity.window
        report("A4 UI collapsed from 0: " + Aud1Bench.stats(uiFrames(window) { avRun("collapsed, from 0", 0, 20) }))
        report("A4 UI collapsed from the middle: " + Aud1Bench.stats(uiFrames(window) { avRun("collapsed, from the middle", media.durationUs / 2, 20) }))
        compose.runOnIdle { editor.setExpanded(setOf(1L, 10L, 11L)) }; Thread.sleep(800)
        report("A4 UI 3 expanded from 0: " + Aud1Bench.stats(uiFrames(window) { avRun("3 expanded, from 0", 0, 20) }))
        // Tap seeks: from Seek to the presented frame of that time.
        val presented = CopyOnWriteArrayList<LongArray>()
        PreviewFrames.testObserver = { t, s, p -> presented += longArrayOf(t, s, p) }
        val seekMs = ArrayList<Double>()
        for (target in listOf(10_000_000L, 70_000_000L, 30_000_000L, 100_000_000L, 5_000_000L)) {
            presented.clear(); val t0 = System.nanoTime()
            compose.runOnIdle { editor.dispatch(EditorAction.Seek(target)); c.seek(editor.state.playheadUs) }
            val want = FrameRate(media.fps, 1).let { it.frameTimeUs(it.nearestFrame(target)) }
            while (System.nanoTime() - t0 < 10_000_000_000L && presented.none { it[0] == want }) Thread.sleep(2)
            presented.firstOrNull { it[0] == want }?.let { seekMs += (it[2] - t0) / 1e6 }
        }
        PreviewFrames.testObserver = null
        report("A4 tap seek to a presented frame (5 jumps, both directions): ${Aud1Bench.stats(seekMs)} ms; " + seekMs.joinToString(" ") { "%.0f".format(it) })
    }
}
