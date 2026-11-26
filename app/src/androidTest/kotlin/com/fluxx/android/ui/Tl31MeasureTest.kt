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

    // ---- Part C: waveform correctness ---------------------------------------------------------------------------------
    /** Runs of [n] steps whose level exceeds [threshold], as (start, end) step indices (end exclusive). */
    private fun runs(n: Int, threshold: Float, level: (Int) -> Float): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>(); var start = -1
        for (i in 0 until n) { val on = level(i) > threshold
            if (on && start < 0) start = i; if (!on && start >= 0) { out += start to i; start = -1 } }
        if (start >= 0) out += start to n
        return out
    }

    private fun peaksOf(uri: String): PeakPyramid {
        val provider = com.fluxx.android.media.AudioPeakProvider.get(context)
        compose.runOnIdle { provider.states.remove(uri); provider.request(uri) }
        compose.waitUntil(60_000) { compose.runOnIdle { provider.states[uri] !is com.fluxx.android.media.AudioPeakProvider.Peaks.Loading && provider.states[uri] != null } }
        return (compose.runOnIdle { provider.states[uri] } as com.fluxx.android.media.AudioPeakProvider.Peaks.Ready).pyramid
    }

    /** The mixer's own PCM for one VIDEO layer of [uri] (AudioMixer is called, not changed): left channel, 48 kHz. */
    private fun mixerLeft(uri: String, durationUs: Long): Pair<ShortArray, Long> {
        val layer = CompositionLayer(1, LayerType.VIDEO, timing = ClipTiming(0, 0, durationUs), asset = AssetReference("tl31", uri, durationUs = durationUs))
        val project = ProjectDocument(Composition(durationUs = durationUs, frameRate = rate, layers = FrozenList.of(listOf(layer))))
        val out = java.io.File(context.cacheDir, "tl31-mix.pcm")
        val t0 = System.nanoTime()
        com.fluxx.android.render.AudioMixer.mix(context, project, out, { false })
        val ms = (System.nanoTime() - t0) / 1_000_000
        val bytes = out.readBytes(); val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val left = ShortArray(bytes.size / 4) { bb.getShort(it * 4) }
        out.delete()
        return left to ms
    }

    @Test fun cWaveformBurstsMatchTheTruthAndTheMixer() {
        val truth = listOf(.25 to .35, .6 to .65, 1.2 to 1.5)
        for ((sr, ch) in listOf(44_100 to 1, 48_000 to 2, 44_100 to 2, 48_000 to 1)) {
            val file = Tl31Fixtures.aacBursts(context, "tl31-c-$sr-$ch.m4a", sr, ch, 2.0, truth, amp = .5)
            val uri = android.net.Uri.fromFile(file).toString()
            val p = peaksOf(uri)
            val bucket = p.bucketUs(0)
            val (mn, mx) = p.levels[0]
            val found = runs(mx.size, .1f) { maxOf(kotlin.math.abs(mn[it]), kotlin.math.abs(mx[it])) }.map { (a, b) -> a * bucket / 1e6 to b * bucket / 1e6 }
            val peak = mx.maxOrNull() ?: 0f
            val (left, mixMs) = mixerLeft(uri, 2_000_000)
            // The mixer's PCM in 256-sample envelope buckets at 48 kHz (raw samples cross zero inside a tone).
            val mixBuckets = (left.size + 255) / 256
            val env = FloatArray(mixBuckets) { b -> var m = 0; for (i in b * 256 until minOf(left.size, b * 256 + 256)) m = maxOf(m, kotlin.math.abs(left[i].toInt())); m / 32767f }
            val mixRuns = runs(mixBuckets, .1f) { env[it] }.map { (a, b) -> a * 256 / 48_000.0 to b * 256 / 48_000.0 }
            val mixPeak = (left.maxOrNull() ?: 0).toFloat() / 32767f
            fun err(r: List<Pair<Double, Double>>) = r.zip(truth).joinToString(" ") { (f, t) -> "%+.1f/%+.1fms".format((f.first - t.first) * 1000, (f.second - t.second) * 1000) }
            report("C ${sr}Hz ${ch}ch: peaks runs=${found.size} bucket=%.2fms start/end vs truth: ${err(found)}; peak=%.3f".format(bucket / 1000, peak), emptyList())
            report("C ${sr}Hz ${ch}ch: mixer runs=${mixRuns.size} (${mixMs}ms) start/end vs truth: ${err(mixRuns)}; peak=%.3f (mixer gain x headroom)".format(mixPeak), emptyList())
            org.junit.Assert.assertEquals("$sr/$ch: the three bursts", 3, found.size)
            org.junit.Assert.assertEquals("$sr/$ch: the mixer's three bursts", 3, mixRuns.size)
            // The waveform matches what PLAYS (the mixer) within one bucket at both ends (+ the mixer's own 256-sample buckets).
            val tol = bucket / 1e6 + 256.0 / 48_000
            for ((f, m) in found.zip(mixRuns)) {
                org.junit.Assert.assertEquals("$sr/$ch start vs mixer", m.first, f.first, tol)
                org.junit.Assert.assertEquals("$sr/$ch end vs mixer", m.second, f.second, tol)
            }
            // Both sit at the file's time + the AAC encoder priming (2048 samples, not trimmed by either path): reported.
            val priming = 2048.0 / sr
            for ((f, t) in found.zip(truth)) org.junit.Assert.assertEquals("$sr/$ch start vs truth + priming", t.first + priming, f.first, tol + 1024.0 / sr)
        }
    }

    // ---- Part D: playback diagnosis (measure only; AudioMixer / playback are not changed) ------------------------------
    @Test fun d1PreparingAudioCostByLength() {
        for (secs in listOf(10.0, 60.0, 120.0)) {
            val t0 = System.nanoTime()
            val file = Tl31Fixtures.videoWithAudio(context, "tl31-d-${secs.toInt()}.mp4", secs)
            val genMs = (System.nanoTime() - t0) / 1_000_000
            val uri = android.net.Uri.fromFile(file).toString()
            // Extractor + decode (the peak decoder: same extractor / codec work as the mixer's per-layer decode).
            val d0 = System.nanoTime(); peaksOf(uri); val decodeMs = (System.nanoTime() - d0) / 1_000_000
            // The mixer's whole preparation (what "Preparing audio" waits for): decode + resample + scratch file + 16-bit convert.
            val (_, mixMs) = mixerLeft(uri, (secs * 1_000_000).toLong())
            val scratchMb = secs * 48_000 * 8 / 1e6
            report("D1 ${secs.toInt()}s source: mix (Preparing audio) ${mixMs}ms; decode-only ${decodeMs}ms; mix minus decode ${mixMs - decodeMs}ms (resample, %.1f MB float scratch read-modify-write, 16-bit convert); fixture made in ${genMs}ms; file %.1f KB"
                .format(scratchMb, file.length() / 1024.0), emptyList())
        }
    }

    @Test fun d2PlaybackStartAndUnderruns() {
        val file = Tl31Fixtures.videoWithAudio(context, "tl31-d2-60.mp4", 60.0)
        val uri = android.net.Uri.fromFile(file).toString()
        val video = CompositionLayer(1, LayerType.VIDEO, name = "fixture", timing = ClipTiming(0, 0, 60_000_000),
            asset = AssetReference("tl31-d2", uri, durationUs = 60_000_000))
        editor = EditorViewModel()
        editor.dispatch(EditorAction.Load(ProjectDocument(Composition(durationUs = 60_000_000, frameRate = rate, layers = FrozenList.of(listOf(video))))))
        compose.setContent { FluxxTheme { EditorScreen(editor, MediaRepository(context), controller.value, null, true, false,
            onSurfaceAvailable = { s, w, h ->
                if (controller.value == null) controller.value = PreviewController(context, s, w, h, onPosition = { editor.reportPosition(it) })
                controller.value!!.update(editor.displayedState.project, editor.displayedState.playheadUs, false)
            }, onSurfaceDestroyed = {}, onSurfaceResized = { _, _ -> }, onRequestMediaPermission = {}, onOpenDocumentPicker = {},
            onSaveProject = {}, onLoadProject = {}, onExportStart = {}, onExportCancel = {}, onOpenExportedVideo = {}) } }
        compose.waitUntil(10_000) { controller.value != null }
        Thread.sleep(1000)
        val c = controller.value!!
        val playbackField = PreviewController::class.java.getDeclaredField("playback").apply { isAccessible = true }
        fun track(): android.media.AudioTrack? = playbackField.get(c)?.let { pb -> pb.javaClass.getDeclaredField("track").apply { isAccessible = true }.get(pb) as android.media.AudioTrack }
        val window = compose.activity.window
        for (round in 1..2) {
            val t0 = System.nanoTime()
            compose.runOnIdle { c.play() }
            var readyMs = -1L
            while (System.nanoTime() - t0 < 120_000_000_000L) { val t = track(); if (t != null && t.playState == android.media.AudioTrack.PLAYSTATE_PLAYING) { readyMs = (System.nanoTime() - t0) / 1_000_000; break }; Thread.sleep(10) }
            val frames = frames(window) { Thread.sleep(8000) }
            val t = track()
            val underruns = t?.underrunCount ?: -1
            val bufferFrames = t?.bufferSizeInFrames ?: -1
            compose.runOnIdle { c.pause() }
            report("D2 round $round (60s fixture): play -> audio playing ${readyMs}ms; underruns in 8s: $underruns; AudioTrack buffer $bufferFrames frames (%.0fms) during playback".format(bufferFrames / 48.0), frames)
            Thread.sleep(800); compose.runOnIdle { c.seek(0) }; Thread.sleep(500)
        }
    }
}
