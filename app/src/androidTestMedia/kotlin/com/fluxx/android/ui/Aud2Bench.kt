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
import com.fluxx.android.engine.VideoDecoder
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import com.fluxx.android.render.PreviewController
import com.fluxx.android.render.PreviewFrames
import com.fluxx.android.ui.editor.EditorScreen
import com.fluxx.android.ui.theme.FluxxTheme
import com.fluxx.android.ui.timeline.WaveformProbe
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * AUD2 benchmark helpers. Both benchmark files stay where they are on the phone (read in place, never copied); documents
 * are in memory only (never a user project). A missing file SKIPS its test (no generated fallback for benchmark numbers).
 */
object Aud2Bench {
    /** The worst case: VP9 1920x1440 20 fps, AAC 44.1 kHz, 122 s (found by prefix, as in AUD1). */
    val VP9: (String) -> Boolean = { it.startsWith(Aud1Bench.PREFIX) }
    /** The typical clip: H.264 1920x1080 30 fps, AAC, 60 s. Its name has odd characters: matched by this substring only. */
    const val TYPICAL_ID = "a-X8h8cgzOI"
    val TYPICAL: (String) -> Boolean = { it.contains(TYPICAL_ID) }
    var lookupLog = ""

    /** As [Aud1Bench.file] (StorageManager volumes, Download/Seal), for any name predicate. */
    fun find(context: android.content.Context, match: (String) -> Boolean): File? {
        val log = StringBuilder()
        val sm = context.getSystemService(android.os.storage.StorageManager::class.java)
        val volumes = sm.storageVolumes.mapNotNull { it.directory } + File("/storage").listFiles().orEmpty()
        for (v in volumes.distinct()) {
            val dir = File(v, "Download/Seal")
            val names = dir.listFiles()
            log.append(dir.path).append(": ").append(names?.size?.toString() ?: "unlistable").append("; ")
            val f = names?.firstOrNull { match(it.name) } ?: continue
            val readable = runCatching { f.inputStream().use { it.read() } >= 0 }.getOrDefault(false)
            log.append("found ").append(f.length()).append(" bytes, readable=").append(readable).append("; ")
            if (readable) { lookupLog = log.toString(); return f }
        }
        lookupLog = log.toString()
        return null
    }

    /** The sync-frame (keyframe) spacing of the video track, read from the extractor (flags only; no sample data). */
    data class Gop(val frames: Int, val syncs: Int, val intervalsMs: List<Double>)
    fun gop(f: File): Gop {
        val ex = android.media.MediaExtractor(); ex.setDataSource(f.path)
        try {
            val t = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)!!.startsWith("video/") }
            ex.selectTrack(t)
            val syncs = ArrayList<Long>(); var frames = 0
            while (ex.sampleTime >= 0) {
                if (ex.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC != 0) syncs += ex.sampleTime
                frames++; ex.advance()
            }
            return Gop(frames, syncs.size, syncs.sorted().zipWithNext { a, b -> (b - a) / 1000.0 })
        } finally { ex.release() }
    }

    /** One in-memory video layer for [f]; [uri] lets several layers open SEPARATE decoders for the same file. */
    fun layer(id: Long, f: File, m: Aud1Bench.Media, uri: String = android.net.Uri.fromFile(f).toString(), z: Int = 10,
              timing: ClipTiming = ClipTiming(0, 0, m.durationUs), opacity: Float = 1f) =
        CompositionLayer(id, LayerType.VIDEO, name = "bench$id", zOrder = z, timing = timing, transform = Transform(opacity = opacity),
            asset = AssetReference("aud2-bench-$id", uri, access = MediaAccess.AVAILABLE, durationUs = m.durationUs))

    /**
     * The same file under a distinct URI string (the renderer pools decoders by URI, so each spelling gets its own decoder
     * and its own PCM cache entry). "/./" path segments are resolved by the kernel: the bytes read are the same file.
     */
    fun distinctUri(f: File, k: Int): String = if (k == 0) android.net.Uri.fromFile(f).toString()
        else android.net.Uri.fromFile(File(f.parentFile, "./".repeat(k) + f.name)).toString()

    fun project(width: Int, height: Int, durationUs: Long, fps: Int, layers: List<CompositionLayer>) =
        ProjectDocument(Composition(width = width, height = height, durationUs = durationUs, frameRate = FrameRate(fps, 1), layers = FrozenList.of(layers)))

    /** The canvas for a source within the 1080p budget (4:3 for the VP9 file, 16:9 for the typical clip). */
    fun canvas(m: Aud1Bench.Media): Pair<Int, Int> = if (m.width * 3 == m.height * 4) 1440 to 1080 else 1920 to 1080

    /** dumpsys meminfo (read-only, via the instrumentation's shell): App Summary values in kB. */
    fun meminfo(pkg: String): Map<String, Long> {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys meminfo $pkg")
        val text = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
        val out = LinkedHashMap<String, Long>()
        val summary = text.substringAfter("App Summary", "")
        for (key in listOf("Java Heap", "Native Heap", "Graphics", "Private Other", "System", "TOTAL PSS", "TOTAL RSS")) {
            Regex("""${Regex.escape(key)}:\s+(\d+)""").find(summary)?.let { out[key] = it.groupValues[1].toLong() }
        }
        if ("TOTAL PSS" !in out) Regex("""TOTAL:\s+(\d+)""").find(text)?.let { out["TOTAL PSS"] = it.groupValues[1].toLong() }
        return out
    }
    fun mem(m: Map<String, Long>) = m.entries.joinToString(", ") { "${it.key} %.1f MB".format(it.value / 1024.0) }

    fun pct(v: List<Double>, p: Double) = Aud1Bench.pct(v, p)
    fun stats(v: List<Double>) = Aud1Bench.stats(v)
}

/**
 * Everything the probes see during one window: presented preview frames, decoder events, playback events, clock reads,
 * AudioTimestamps (polled from the running track on a test thread), timeline draws and audio-row placement.
 */
class Aud2Recorder(private val controller: () -> PreviewController?) {
    val frames = CopyOnWriteArrayList<LongArray>()            // (time us, render start ns, presented ns)
    val decoder = CopyOnWriteArrayList<Pair<Long, String>>()  // (ns, event)
    val playback = CopyOnWriteArrayList<Pair<Long, String>>()
    val clock = CopyOnWriteArrayList<LongArray>()             // (ns, head, ts valid 0/1, position us)
    val stamps = CopyOnWriteArrayList<LongArray>()            // (track frame, ns, start frame)
    val draws = CopyOnWriteArrayList<DoubleArray>()           // (ns, centre px, pxPerUs)
    val placed = CopyOnWriteArrayList<DoubleArray>()          // (ns, root x)
    val scrolls = CopyOnWriteArrayList<DoubleArray>()         // (ns, scrolled content left edge in root px after layout, pxPerUs)
    val positions = CopyOnWriteArrayList<LongArray>()         // (ns, position us) as the preview posts them to the editor
    @Volatile var lastRoot = Double.NaN                       // the latest content left edge (kept across clear())
    @Volatile private var polling = false
    private var poller: Thread? = null
    var underruns = -1; private set

    fun start() {
        PreviewFrames.testObserver = { t, s, p -> frames += longArrayOf(t, s, p) }
        VideoDecoder.testEvents = { e -> decoder += System.nanoTime() to e }
        PreviewFrames.testPlayback = { e -> playback += System.nanoTime() to e }
        PreviewFrames.testClock = { now, head, ts, us -> clock += longArrayOf(now, head, if (ts) 1 else 0, us) }
        WaveformProbe.draw = { ns, centre, ppu -> draws += doubleArrayOf(ns.toDouble(), centre.toDouble(), ppu) }
        WaveformProbe.placed = { ns, x -> placed += doubleArrayOf(ns.toDouble(), x.toDouble()) }
        WaveformProbe.scroll = { ns, x, ppu -> scrolls += doubleArrayOf(ns.toDouble(), x.toDouble(), ppu); lastRoot = x.toDouble() }
        polling = true
        poller = Thread {
            while (polling) {
                val c = controller()
                val t = c?.let { Aud1Bench.track(it) }
                if (t != null) runCatching {
                    Aud1Bench.timestamp(t)?.let { (f, ns) -> stamps += longArrayOf(f, ns, Aud1Bench.startFrame(c) ?: 0) }
                    underruns = maxOf(underruns, t.underrunCount)
                }
                Thread.sleep(10)
            }
        }.apply { start() }
    }
    fun stop() {
        polling = false; poller?.join(1000)
        PreviewFrames.testObserver = null; VideoDecoder.testEvents = null; PreviewFrames.testPlayback = null; PreviewFrames.testClock = null
        WaveformProbe.draw = null; WaveformProbe.placed = null; WaveformProbe.scroll = null
    }
    fun clear() { frames.clear(); decoder.clear(); playback.clear(); clock.clear(); stamps.clear(); draws.clear(); placed.clear(); scrolls.clear(); positions.clear(); underruns = -1 }

    /** The composition time heard at [ns] (from the AudioTimestamps of the playback with [startFrame]); null before sound. */
    fun heardUs(ns: Long): Long? {
        val s = stamps.toList().filter { it[0] > 0 }
        if (s.isEmpty()) return null
        val ts = s.lastOrNull { it[1] <= ns } ?: return null
        val frame = ts[0] + (ns - ts[1]) * 48_000 / 1_000_000_000L
        return (ts[2] + frame) * 1_000_000 / 48_000
    }
    /** The moment frame 0 of the track was heard (first sound), from the first valid timestamp. */
    fun firstSoundNs(): Long? = stamps.firstOrNull { it[0] > 0 }?.let { it[1] - it[0] * 1_000_000_000L / 48_000 }
    /** The composition time on screen in the preview at [ns] (latest presented frame). */
    fun videoUs(ns: Long): Long? = frames.lastOrNull { it[2] <= ns }?.get(0)
}

/** Shared host for the AUD2 classes: EditorScreen on an in-memory document, the same wiring as the AUD1 benchmarks. */
abstract class Aud2Base {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    protected val context get() = compose.activity
    private var previousLibrary: CurveLibraryStore? = null
    protected lateinit var editor: EditorViewModel
    protected val controller = mutableStateOf<PreviewController?>(null)
    protected val c get() = controller.value!!
    protected val rec = Aud2Recorder { controller.value }

    @Before fun aud2SetUp() { previousLibrary = CurveLibraryStore.replaceForTest(CurveLibraryStore.inMemory()) }
    @After fun aud2TearDown() {
        rec.stop()
        CurveLibraryStore.replaceForTest(previousLibrary)
        controller.value?.let { pc -> kotlinx.coroutines.runBlocking { pc.close() } }
    }

    /** The status key of [report] lines (MP1 classes use "MP1"). */
    protected open val tag = "AUD2"
    protected fun report(line: String) {
        android.util.Log.i(tag, line)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString(tag, line) })
    }

    protected fun file(kind: String, match: (String) -> Boolean): File {
        val f = Aud2Bench.find(context, match)
        assumeTrue("AUD2: the $kind benchmark file was not found or is not readable (${Aud2Bench.lookupLog}); skipping", f != null)
        return f!!
    }

    protected fun host(project: ProjectDocument) {
        editor = EditorViewModel()
        editor.dispatch(EditorAction.Load(project))
        compose.setContent { FluxxTheme { EditorScreen(editor, MediaRepository(context), controller.value, null, true, false,
            onSurfaceAvailable = { s, w, h ->
                if (controller.value == null) controller.value = PreviewController(context, s, w, h, onPosition = { editor.reportPosition(it); rec.positions += longArrayOf(System.nanoTime(), it) })
                controller.value!!.update(editor.displayedState.project, editor.displayedState.playheadUs, false)
            }, onSurfaceDestroyed = {}, onSurfaceResized = { _, _ -> }, onRequestMediaPermission = {}, onOpenDocumentPicker = {},
            onSaveProject = {}, onLoadProject = {}, onExportStart = {}, onExportCancel = {}, onOpenExportedVideo = {}) } }
        compose.waitUntil(10_000) { controller.value != null }
        Thread.sleep(1500)
    }

    protected fun seekTo(us: Long, settleMs: Long = 1200) { compose.runOnIdle { editor.dispatch(EditorAction.Seek(us)); c.seek(editor.state.playheadUs) }; Thread.sleep(settleMs) }
    /** The app's Play / Pause toggle, as EditorScreen runs it (pause, then rest on the floor frame that was on screen). */
    protected fun toggle() = compose.runOnIdle {
        if (c.state.value.playing) {
            c.pause()
            val rate = editor.state.project.composition.frameRate
            val shown = rate.frameTimeUs(rate.floorFrame(editor.state.playheadUs))
            if (shown != editor.state.playheadUs) { editor.dispatch(EditorAction.Seek(shown)); c.seek(editor.state.playheadUs) }
        } else { editor.dispatch(EditorAction.SelectMarker(null)); c.play() }
    }
    protected val playing get() = compose.runOnIdle { c.state.value.playing }

    /** Warm the decoded-PCM cache of [uri] (so a benchmark measures playback, not the first decode). */
    protected fun warmPcm(uri: String, durationUs: Long, timeoutMs: Long = 180_000) {
        val pcm = com.fluxx.android.render.SourcePcmCache.get(context).get(uri, durationUs)
        val t0 = System.nanoTime()
        // MP1 B2: the cache decodes only a source's used range; for this warm-up the whole source is that range.
        pcm.setScope(listOf(0L..durationUs))
        while (!pcm.complete && (System.nanoTime() - t0) / 1_000_000 < timeoutMs) {
            Thread.sleep(100)
            // If the worker stopped, ask for the first gap (a reader's demand).
            if (!pcm.decoding) { var f = 0L; while (f < pcm.frames && pcm.covers(f, minOf(pcm.frames, f + 48_000))) f += 48_000
                if (f < pcm.frames) pcm.ensure(f) }
        }
        report("PCM cache ${if (pcm.complete) "warm" else "NOT complete"} after ${(System.nanoTime() - t0) / 1_000_000}ms (${uri.takeLast(24)})")
    }

    protected fun uiFrames(window: Window, block: () -> Unit): List<Double> {
        val out = CopyOnWriteArrayList<Double>(); val th = HandlerThread("aud2-metrics").apply { start() }
        val l = Window.OnFrameMetricsAvailableListener { _, m, _ -> out += m.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6 }
        window.addOnFrameMetricsAvailableListener(l, Handler(th.looper))
        try { block(); Thread.sleep(300) } finally { window.removeOnFrameMetricsAvailableListener(l); th.quitSafely() }
        return out.toList()
    }

    /** Heard-minus-presented offsets (ms) for the beats in [beats] (composition time), from what [rec] recorded. */
    protected fun beatOffsets(beats: List<Long>, frameUs: Long): List<Double> = beats.mapNotNull { b ->
        val ts = rec.stamps.toList().filter { it[0] > 0 }
        val start = ts.firstOrNull()?.get(2) ?: return@mapNotNull null
        val f = b * 48_000 / 1_000_000 - start
        val s = ts.lastOrNull { it[0] <= f } ?: ts.first()
        val heard = s[1] + (f - s[0]) * 1_000_000_000L / 48_000
        val v = rec.frames.lastOrNull { it[0] <= b && b < it[0] + frameUs } ?: return@mapNotNull null
        (heard - v[2]) / 1e6
    }
}
