package com.fluxx.android.ui

import android.media.MediaMetadataRetriever
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.ProjectManager
import com.fluxx.android.editor.EditorAction
import com.fluxx.android.editor.EditorViewModel
import com.fluxx.android.editor.LayerSelection
import com.fluxx.android.editor.MultiLayerEdits
import com.fluxx.android.engine.RenderBridge
import com.fluxx.android.engine.VideoDecoder
import com.fluxx.android.export.CompositionExporter
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import com.fluxx.android.render.CompositionRenderer
import com.fluxx.android.render.MediaProbes
import com.fluxx.android.render.PreviewFrames
import com.fluxx.android.ui.timeline.TimelineProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * MP2.1: cuts, re-entry and UI frame cost, measured through the real preview path (EditorScreen, PreviewController) with
 * the Compose test clock pumped one frame per 16 ms (as [Tlv1Test]): the UI recomposes and draws while playback runs, as in
 * the app. The same class runs BEFORE (B0) and AFTER (B5) the MP2.1 changes; every line is printed for comparison.
 *  a1 cuts: 30 clips of 2 s, MP1's three layouts (contiguous / non-contiguous in-points / alternating H.264 and VP9).
 *  a2 Project 1 (read-only, in memory, never saved): its first 12 cuts.
 *  a3 a 4:3 mixed-source sequence of 10 Download/Seal files (content URIs, read in place), 4 s each.
 *  a4 re-entry: a video layer leaves the frame (position hold keys) and re-enters after 0.4 / 1 / 2 / 4 / 8 s.
 *  a5 UI cost: 5 s of playback with timeline and preview visible (UI frames, main thread, timeline / clip-row compositions).
 *  a6 presented versus composition frames: one 15 s clip; render calls by reason, decoder requests.
 *  a7 export identity: a short 6-layer export (decoder rotation): decoded output frame hashes, the export renderer's own
 *     readback hashes and the decoder open / close sequence (compare BEFORE with AFTER).
 * Per cut / re-entry: freeze beyond the steady interval, "clip becomes active" (the heard clock reaches the cut) to the
 * first presented frame at or after the cut, the decoder's open / seek / decode-to-first-frame times and whether the frame
 * for the new clip was decoded BEFORE the clip became active (a pre-opened, pre-positioned decoder).
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class Mp21Test : Mp12Base() {
    override val tag = "MP21"
    private val main get() = InstrumentationRegistry.getInstrumentation()
    private val clipUs = 2_000_000L
    private val renders = CopyOnWriteArrayList<Pair<String, Long>>()
    private val probes = CopyOnWriteArrayList<Pair<Long, String>>()

    private fun out(line: String) = Mp12Bench.say("MP21", line)
    private fun frame() { compose.mainClock.advanceTimeByFrame(); Thread.sleep(16) }
    private fun pump(ms: Long) { val end = System.nanoTime() + ms * 1_000_000; while (System.nanoTime() < end) frame() }
    private fun isPlaying(): Boolean { var p = false; main.runOnMainSync { p = c.state.value.playing }; return p }
    private fun play() = main.runOnMainSync { editor.dispatch(EditorAction.SelectMarker(null)); c.play() }
    private fun pause() = main.runOnMainSync { if (c.state.value.playing) c.pause() }
    private fun load(project: ProjectDocument, at: Long = 0) {
        main.runOnMainSync { editor.dispatch(EditorAction.Load(project)); c.update(editor.state.project, at, false) }
        pump(2500)
        main.runOnMainSync { editor.dispatch(EditorAction.Seek(at)); c.seek(editor.state.playheadUs) }
        pump(1500)
    }
    private fun startProbes() {
        rec.start()
        PreviewFrames.testRender = { r, t -> renders += r to t }
        MediaProbes.events = { e -> probes += System.nanoTime() to e }
    }
    private fun stopProbes() { PreviewFrames.testRender = null; MediaProbes.events = null }
    private fun clearAll() { rec.clear(); renders.clear(); probes.clear() }
    private fun key(uri: String) = android.net.Uri.parse(uri).toString().hashCode().toUInt().toString(16)
    private fun state(label: String): TestDeviceState.State { val s = TestDeviceState.read(); out("$label: state $s"); return s }

    // ---- one playback window ------------------------------------------------------------------------------------------
    private class Window(val t0: Long, val frames: List<LongArray>, val clock: List<LongArray>, val decoder: List<Pair<Long, String>>,
                         val renders: List<Pair<String, Long>>, val probes: List<Pair<Long, String>>, val presented: Long)

    /** Plays [project] from [fromUs] (frames pumped) until [untilUs] is shown, playback stops, or [limitMs]. */
    private fun playWindow(fromUs: Long, untilUs: Long, limitMs: Long): Window {
        clearAll()
        val p0 = PreviewFrames.count
        val t0 = System.nanoTime()
        play()
        while ((System.nanoTime() - t0) / 1_000_000 < limitMs) {
            pump(200)
            if ((rec.frames.lastOrNull()?.get(0) ?: 0) >= untilUs) break
            if (!isPlaying()) break
        }
        pause(); pump(400)
        return Window(t0, rec.frames.toList().filter { it[2] >= t0 }, rec.clock.toList().filter { it[0] >= t0 }, rec.decoder.toList(),
            renders.toList(), probes.toList(), PreviewFrames.count - p0)
    }

    /** A cut (or a re-entry) at composition [atUs]: the new clip's [uri] from source [inUs] for [durUs]. */
    private data class Cut(val index: Int, val atUs: Long, val uri: String, val inUs: Long, val durUs: Long, val what: String)
    private data class CutResult(val cut: Cut, val freezeMs: Double, val maxIvMs: Double, val latencyMs: Double, val openMs: Double, val seekMs: Double,
                                 val decodeMs: Double, val ready: Boolean, val leadMs: Double, val warm: String)

    private fun field(e: String, k: String) = Mp1Bench.field(e, k)

    private fun analyse(w: Window, cuts: List<Cut>, frameUs: Long, windowUs: Long = 300_000): List<CutResult> {
        val iv = w.frames.zipWithNext { a, b -> (b[2] - a[2]) / 1e6 }
        val steady = Aud2Bench.pct(iv, .5)
        return cuts.map { cut ->
            val win = w.frames.filter { it[0] in (cut.atUs - windowUs)..(cut.atUs + windowUs) }
            val wiv = win.zipWithNext { a, b -> (b[2] - a[2]) / 1e6 }
            val maxIv = wiv.maxOrNull() ?: Double.NaN
            // "Active": the preview starts rendering the first frame at or after the cut (it rounds the clock to the nearest
            // frame, so this is about half a frame before the heard clock reaches the cut).
            val firstFrame = w.frames.firstOrNull { it[0] >= cut.atUs }
            val active = firstFrame?.get(1)
            val shown = firstFrame?.get(2)
            val k = key(cut.uri)
            // The first decoder request that produced a frame of the new clip (its source range), and the open before it.
            // Only this cut's own requests: from the frame 3 s before it (an earlier clip may use the same source range).
            val since = maxOf(w.t0, w.frames.firstOrNull { it[0] >= cut.atUs - 3_000_000 }?.get(2) ?: w.t0)
            val first = w.decoder.firstOrNull { (ns, e) -> ns >= since && e.startsWith("frame $k ") &&
                (field(e, "t")?.toLongOrNull() ?: -1) in cut.inUs until (cut.inUs + minOf(cut.durUs, 400_000)) }
            val open = w.decoder.lastOrNull { (ns, e) -> e.startsWith("open $k ") && ns >= since && ns <= (first?.first ?: Long.MAX_VALUE) }
            val usAll = first?.second?.let { field(it, "us")?.toDoubleOrNull() }?.div(1000) ?: Double.NaN
            val seekMs = first?.second?.let { field(it, "seekus")?.toDoubleOrNull() }?.div(1000) ?: Double.NaN
            val ready = first != null && active != null && first.first < active
            val warm = w.probes.lastOrNull { (ns, e) -> e.startsWith("warm-") && e.contains(" key=$k") && ns <= (shown ?: Long.MAX_VALUE) && ns >= since }?.second ?: "-"
            CutResult(cut, (maxIv - steady).coerceAtLeast(0.0), maxIv, if (active != null && shown != null) (shown - active) / 1e6 else Double.NaN,
                open?.second?.let { field(it, "openus")?.toDoubleOrNull() }?.div(1000) ?: Double.NaN, seekMs,
                if (usAll.isNaN()) Double.NaN else usAll - (if (seekMs.isNaN()) 0.0 else seekMs), ready,
                if (first != null && active != null) (active - first.first) / 1e6 else Double.NaN, warm.substringBefore(' '))
        }
    }

    private fun f0(v: Double) = if (v.isNaN()) "-" else "%.0f".format(v)

    /** Raw lines (key MP21RAW) for offline analysis: frames (time us, render start / presented ms from t0) and decoder events. */
    private fun raw(label: String, w: Window) {
        val tag = label.substringBefore(' ')
        w.frames.chunked(40).forEach { ch -> Mp12Bench.say("MP21RAW", "$tag F " + ch.joinToString(" ") { "${it[0]}/${"%.1f".format((it[1] - w.t0) / 1e6)}/${"%.1f".format((it[2] - w.t0) / 1e6)}" }) }
        w.decoder.filter { it.first >= w.t0 && (it.second.startsWith("open ") || it.second.startsWith("close ") || (it.second.startsWith("frame ") && !it.second.contains("how=cached"))) }
            .chunked(12).forEach { ch -> Mp12Bench.say("MP21RAW", "$tag D " + ch.joinToString(" | ") { "${"%.1f".format((it.first - w.t0) / 1e6)} ${it.second}" }) }
        w.probes.filter { it.first >= w.t0 && it.second.startsWith("warm") }.chunked(12)
            .forEach { ch -> Mp12Bench.say("MP21RAW", "$tag W " + ch.joinToString(" | ") { "${"%.1f".format((it.first - w.t0) / 1e6)} ${it.second}" }) }
    }

    private fun report(label: String, w: Window, results: List<CutResult>, frameUs: Long) {
        raw(label, w)
        val iv = w.frames.zipWithNext { a, b -> (b[2] - a[2]) / 1e6 }
        val frameMs = frameUs / 1000.0
        val fr = results.map { it.freezeMs }.filter { !it.isNaN() }
        val hitches = results.count { it.maxIvMs - frameMs > 100 }
        val opens = w.decoder.count { it.first >= w.t0 && it.second.startsWith("open ") }
        val closes = w.decoder.count { it.first >= w.t0 && it.second.startsWith("close ") }
        val seeks = w.decoder.count { it.first >= w.t0 && it.second.startsWith("frame ") && !it.second.contains("seek=-1") }
        val blocked = w.probes.count { it.second.startsWith("warm-blocked") }
        out("$label: ${w.frames.size} frames; all intervals ${Aud2Bench.stats(iv)} ms; cuts ${results.size}: hitches (a frame > 100 ms late, MP1's rule) $hitches/${results.size}; " +
            "freeze beyond steady p50 ${f0(Aud2Bench.pct(fr, .5))} p90 ${f0(Aud2Bench.pct(fr, .9))} max ${f0(fr.maxOrNull() ?: Double.NaN)} ms; under 100 ms ${fr.count { it < 100 }}/${fr.size}; " +
            "active->shown p50 ${f0(Aud2Bench.pct(results.map { it.latencyMs }.filter { !it.isNaN() }, .5))} max ${f0(results.map { it.latencyMs }.filter { !it.isNaN() }.maxOrNull() ?: Double.NaN)} ms; " +
            "decoded before active ${results.count { it.ready }}/${results.size}; decoders opened $opens closed $closes, flush+seeks $seeks; warm-ups blocked $blocked; underruns ${rec.underruns}")
        results.chunked(5).forEach { ch -> out("$label cuts: " + ch.joinToString("; ") { r ->
            "#${r.cut.index} ${r.cut.what} freeze ${f0(r.freezeMs)} (max iv ${f0(r.maxIvMs)}) active->shown ${f0(r.latencyMs)} open ${f0(r.openMs)} seek ${f0(r.seekMs)} decode ${f0(r.decodeMs)} ready ${if (r.ready) "yes (lead ${f0(r.leadMs)})" else "no"} warm ${r.warm}" }) }
        val blockedWhy = w.probes.filter { it.second.startsWith("warm-blocked") }.groupBy { field(it.second, "why") ?: "?" }.mapValues { it.value.size }
        if (blockedWhy.isNotEmpty()) out("$label: warm-ups blocked by reason $blockedWhy")
    }

    // ---- a1: three cut layouts ----------------------------------------------------------------------------------------
    @Test fun a1Cuts() {
        state("a1")
        val f = file("typical H.264", Aud2Bench.TYPICAL); val m = Aud1Bench.media(f)
        val v = Aud2Bench.find(context, Aud2Bench.VP9)
        val tu = android.net.Uri.fromFile(f).toString()
        startProbes()
        try {
            host(Aud2Bench.project(1920, 1080, 30 * clipUs, 30, listOf(Aud2Bench.layer(1, f, m, timing = ClipTiming(0, 0, clipUs)))))
            warmPcm(tu, m.durationUs)
            fun clip(k: Int, file: File, media: Aud1Bench.Media, inUs: Long) = Aud2Bench.layer(1L + k, file, media, z = 10, timing = ClipTiming(k * clipUs, inUs, clipUs))
            cuts("a1 (i) contiguous ranges, one file", (0 until 30).map { k -> clip(k, f, m, k * clipUs) })
            cuts("a1 (ii) non-contiguous in-points, one file", (0 until 30).map { k -> clip(k, f, m, ((k * 13) % 29) * clipUs) })
            if (v != null) {
                val vm = Aud1Bench.media(v); warmPcm(android.net.Uri.fromFile(v).toString(), vm.durationUs)
                cuts("a1 (iii) alternating H.264 / VP9", (0 until 30).map { k -> if (k % 2 == 0) clip(k, f, m, (k % 29) * clipUs) else clip(k, v, vm, k * 3_900_000L) })
            } else out("a1 (iii) skipped: VP9 file not found (${Aud2Bench.lookupLog})")
        } finally { stopProbes() }
    }

    private fun cuts(label: String, layers: List<CompositionLayer>) {
        val project = Aud2Bench.project(1920, 1080, 30 * clipUs, 30, layers)
        load(project)
        val w = playWindow(0, 30 * clipUs - 100_000, 80_000)
        val list = layers.drop(1).mapIndexed { k, l -> Cut(k + 1, l.timing.startUs, l.asset!!.uri!!, l.timing.sourceInUs, clipUs,
            if (layers[k].asset!!.uri == l.asset!!.uri) "same" else "other") }
        report(label, w, analyse(w, list, 33_333), 33_333)
    }

    // ---- a2: Project 1, its first 12 cuts (read-only) -----------------------------------------------------------------
    @Test fun a2Project1() {
        state("a2")
        val file = File(context.filesDir, "projects/Project 1.fluxx")
        assumeTrue("MP21: Project 1 not found; skipping", file.isFile)
        assumeTrue("MP21: Project 1 has a pending atomic-save file; not touching it", !File(file.path + ".bak").exists() && !File(file.path + ".new").exists())
        val before = file.length() to file.lastModified()
        out("a2 Project 1 before: size ${before.first} mtime ${before.second}")
        startProbes()
        try {
            val project = kotlinx.coroutines.runBlocking { ProjectManager(context).loadProject(file) }.getOrThrow()
            val repo = MediaRepository(context)
            val clips = Mp12Bench.clips(project) { info(repo, it) }
            host(project)
            pump(3000)
            val until = clips.getOrNull(12)?.startUs?.plus(600_000) ?: project.composition.resolvedDurationUs
            val rate = project.composition.frameRate
            val frameUs = rate.frameTimeUs(1) - rate.frameTimeUs(0)
            main.runOnMainSync { editor.dispatch(EditorAction.Seek(0)); c.seek(0) }; pump(1500)
            val w = playWindow(0, until, until / 1000 * 2 + 30_000)
            val layers = project.composition.layers.filter { it.type == LayerType.VIDEO && it.visible && it.asset?.uri != null }
                .sortedWith(compareBy({ it.timing.startUs }, { -it.zOrder }))
            val list = clips.drop(1).take(12).map { cl ->
                val l = layers.first { it.timing.startUs == cl.startUs && it.asset!!.uri == cl.uri }
                Cut(cl.index - 1, cl.startUs, cl.uri, l.timing.sourceInUs, (cl.endUs - cl.startUs).coerceAtLeast(frameUs), cl.info.substringBefore(' ')) }
            report("a2 Project 1 first 12 cuts (${project.composition.width}x${project.composition.height} @ ${project.composition.frameRate})", w, analyse(w, list, frameUs, 500_000), frameUs)
        } finally {
            stopProbes()
            val after = file.length() to file.lastModified()
            out("a2 Project 1 after: size ${after.first} mtime ${after.second} -> ${if (after == before) "UNCHANGED" else "CHANGED"}")
            check(after == before) { "Project 1 changed on disk" }
        }
    }

    // ---- a3: a 4:3 mixed-source sequence from Download/Seal ------------------------------------------------------------
    @Test fun a3SealMixed() {
        state("a3")
        val clip = 4_000_000L
        val usable = Mp12Bench.sealVideos(context).filter { it.durationUs >= clip && it.width in 64..4096 && it.height in 64..4096 && it.fps > 1 }
        // Deterministic: MediaStore name order, 20 fps sources first, then round-robin over (codec, size, rate) groups.
        val groups = usable.sortedByDescending { Math.round(it.fps) == 20L }.groupBy { "${it.codec} ${it.width}x${it.height} ${Math.round(it.fps)}" }.values.map { ArrayDeque(it) }
        val picks = ArrayList<Mp12Bench.Source>(); var k = 0
        while (picks.size < 10 && groups.any { it.isNotEmpty() }) { groups[k % groups.size].removeFirstOrNull()?.let { picks += it }; k++ }
        assumeTrue("MP21 a3: only ${picks.size} usable Seal files; skipping", picks.size >= 10)
        out("a3 picked: " + picks.joinToString("; ") { "${it.codec} ${it.width}x${it.height} %.0f fps '${it.name.take(24)}'".format(it.fps) })
        startProbes()
        try {
            host(Aud2Bench.project(1440, 1080, 10_000_000, 30, emptyList()).let { p -> p.copy(composition = p.composition.copy(durationUs = null)) })
            val done = java.util.concurrent.CountDownLatch(1); var error: Throwable? = null
            CoroutineScope(Dispatchers.Main).launch {
                try { editor.addMediaBatch(MediaRepository(context), picks.map { it.uri to it.name }, 0, EditorViewModel.BulkPlacement.AT_PLAYHEAD) }
                catch (t: Throwable) { error = t } finally { done.countDown() }
            }
            while (done.count > 0) pump(100)
            error?.let { throw it }
            var project: ProjectDocument? = null
            main.runOnMainSync {
                val ids = editor.state.project.composition.layers.filter { it.type == LayerType.VIDEO }.map { it.id }
                editor.dispatch(EditorAction.Batch(ids.map { id -> EditorAction.SetTiming(id, ClipTiming(0, 0, clip)) }))
                MultiLayerEdits.sequence(editor.state.project, LayerSelection(ids), 0, MultiLayerEdits.SEQUENCE_REVERSE_DEFAULT)?.let { editor.dispatch(it) }
                c.update(editor.state.project, 0, false); project = editor.state.project
            }
            pump(3000)
            val p = project!!
            val repo = MediaRepository(context)
            val clips = Mp12Bench.clips(p) { info(repo, it) }
            val w = playWindow(0, p.composition.resolvedDurationUs - 100_000, p.composition.resolvedDurationUs / 1000 * 2 + 30_000)
            val list = clips.drop(1).map { cl -> Cut(cl.index - 1, cl.startUs, cl.uri, 0, clip, cl.info.substringBefore(' ')) }
            report("a3 Seal 4:3 @30 mixed, 10 files x 4 s", w, analyse(w, list, 33_333, 500_000), 33_333)
        } finally { stopProbes() }
    }

    // ---- a4: re-entry --------------------------------------------------------------------------------------------------
    @Test fun a4Reentry() {
        state("a4")
        val f = file("typical H.264", Aud2Bench.TYPICAL); val m = Aud1Bench.media(f)
        val gaps = listOf(400_000L, 1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L)
        val shownUs = 2_000_000L
        // Exit / return times: 2 s on screen, then off for the gap (hold keys: it jumps out and back in).
        val events = ArrayList<Pair<Long, Long>>(); var t = 1_000_000L + shownUs
        for (g in gaps) { events += t to t + g; t += g + shownUs }
        val total = t + 500_000
        assumeTrue("MP21 a4: the typical clip is too short", m.durationUs >= total)
        fun hk(at: Long, x: Float) = Keyframe2D(at, x, 0f, EasingPreset.HOLD)
        val keys = listOf(hk(0, .5f)) + events.flatMap { (exit, back) -> listOf(hk(exit, 5f), hk(back, .5f)) }
        val movingUri = Aud2Bench.distinctUri(f, 1)
        val moving = Mp1Bench.placed(2, f, m, movingUri, 11, ClipTiming(0, 0, total), .5f, 0f, .5f).let { l ->
            l.copy(animTransform = l.animTransform.copy(position = AnimatableProperty2D(true, keyframes = FrozenList.of(keys)))) }
        val still = Mp1Bench.placed(1, f, m, Aud2Bench.distinctUri(f, 0), 10, ClipTiming(0, 0, total), -.5f, 0f, .5f)
        startProbes()
        try {
            host(Aud2Bench.project(1920, 1080, total, 30, listOf(still)))
            warmPcm(Aud2Bench.distinctUri(f, 0), m.durationUs); warmPcm(movingUri, m.durationUs)
            load(Aud2Bench.project(1920, 1080, total, 30, listOf(still, moving)))
            val w = playWindow(0, total - 200_000, total / 1000 * 2 + 20_000)
            val rate = FrameRate(30, 1)
            val list = events.mapIndexed { i, (_, back) ->
                val entry = rate.frameTimeUs(rate.nearestFrame(back))
                Cut(i + 1, entry, movingUri, entry, shownUs, "gap ${gaps[i] / 1000} ms") }
            val res = analyse(w, list, 33_333, 300_000)
            report("a4 re-entry (1 still + 1 leaving the frame)", w, res, 33_333)
            val iv = w.frames.zipWithNext { a, b -> (b[2] - a[2]) / 1e6 }; val steady = Aud2Bench.pct(iv, .5)
            for (r in res) {
                val around = w.frames.filter { it[0] in (r.cut.atUs - 200_000)..(r.cut.atUs + 400_000) }.zipWithNext { a, b -> (b[2] - a[2]) / 1e6 }
                val k = key(movingUri)
                val near = w.decoder.filter { (ns, e) -> (e.startsWith("open $k ") || e.startsWith("close $k")) && around.isNotEmpty() &&
                    ns in (w.frames.first { it[0] >= r.cut.atUs - 9_000_000 }[2])..(w.frames.last { it[0] <= r.cut.atUs + 400_000 }[2]) }.map { it.second.substringBefore(" key").take(24) }
                out("a4 re-entry ${r.cut.what}: intervals around the entry ${around.joinToString(" ") { "%.0f".format(it) }} ms; worst %.0f ms = %.2f x steady (%.1f ms); decoder events of the moving layer since its exit: %s"
                    .format(around.maxOrNull() ?: Double.NaN, (around.maxOrNull() ?: Double.NaN) / steady, steady, near.joinToString(", ").ifEmpty { "none" }))
            }
        } finally { stopProbes() }
    }

    // ---- a5: UI cost while playing -------------------------------------------------------------------------------------
    @Test fun a5UiCost() {
        state("a5")
        val f = file("typical H.264", Aud2Bench.TYPICAL); val m = Aud1Bench.media(f)
        TimelineProbe.enabled = true
        try {
            val layers = (0 until 30).map { k -> Aud2Bench.layer(1L + k, f, m, z = 10, timing = ClipTiming(k * clipUs, ((k * 13) % 29) * clipUs, clipUs)) }
            val project = Aud2Bench.project(1920, 1080, 30 * clipUs, 30, layers)
            host(project); warmPcm(android.net.Uri.fromFile(f).toString(), m.durationUs)
            load(project, 0)
            var focus = false; main.runOnMainSync { focus = compose.activity.hasWindowFocus() }
            assumeTrue("MP21 a5: the test window is not in front; skipping", focus)
            for (run in 1..2) {
                main.runOnMainSync { editor.dispatch(EditorAction.Seek(0)); c.seek(0) }; pump(1500)
                TimelineProbe.reset()
                var ui = emptyList<Double>()
                var total = 0L; var worst = 0L; var began = 0L; var messages = 0
                android.os.Looper.getMainLooper().setMessageLogging { line ->
                    if (line.startsWith(">>>>>")) began = System.nanoTime()
                    else if (line.startsWith("<<<<<") && began > 0) { val d = System.nanoTime() - began; total += d; messages++; if (d > worst) worst = d; began = 0 }
                }
                try { ui = uiFrames(compose.activity.window) { play(); pump(5000); pause() } }
                finally { android.os.Looper.getMainLooper().setMessageLogging(null) }
                out("a5 UI cost run $run (30 clips, timeline + preview, 5 s of playback): timeline compositions ${TimelineProbe.viewCompositions.get()}, clip-row compositions ${TimelineProbe.rowCompositions.get()}; " +
                    "UI frames ${ui.size} p50 %.1f p95 %.1f p99 %.1f max %.1f ms; main thread %.0f ms busy in $messages messages, worst %.1f ms".format(
                        Aud2Bench.pct(ui, .5), Aud2Bench.pct(ui, .95), Aud2Bench.pct(ui, .99), ui.maxOrNull() ?: 0.0, total / 1e6, worst / 1e6))
                pump(800)
            }
        } finally { TimelineProbe.enabled = false }
    }

    // ---- a6: presented versus composition frames -----------------------------------------------------------------------
    @Test fun a6Presented() {
        state("a6")
        val f = file("typical H.264", Aud2Bench.TYPICAL); val m = Aud1Bench.media(f)
        startProbes()
        try {
            val single = Aud2Bench.project(1920, 1080, 20_000_000, 30, listOf(Aud2Bench.layer(1, f, m, timing = ClipTiming(0, 0, 20_000_000))))
            host(single); warmPcm(android.net.Uri.fromFile(f).toString(), m.durationUs)
            presented("a6 typical H.264 1080p30 alone, 30 fps composition, frames pumped", single, true)
            presented("a6 typical H.264 1080p30 alone, 30 fps composition, UI clock idle", single, false)
            val long = Mp1Bench.findLong(context)
            if (long != null) {
                val lm = Aud1Bench.media(long)
                val p = Aud2Bench.project(1920, 1080, 20_000_000, 30, listOf(Aud2Bench.layer(5, long, lm, timing = ClipTiming(0, 0, 20_000_000))))
                presented("a6 long HEVC source alone (MP1.1 s1's file), frames pumped", p, true)
            } else out("a6 long source not found (${Mp1Bench.lookupLog}); its case skipped")
        } finally { stopProbes() }
    }

    private fun presented(label: String, project: ProjectDocument, pumped: Boolean) {
        load(project, 0)
        clearAll()
        val p0 = PreviewFrames.count
        val t0 = System.nanoTime()
        play()
        if (pumped) pump(15_000) else Thread.sleep(15_000)
        pause()
        val p1 = PreviewFrames.count
        if (pumped) pump(500) else Thread.sleep(500)
        val frames = rec.frames.toList().filter { it[2] in t0..(t0 + 15_000_000_000L) }
        val times = frames.map { it[0] }
        val span = ((times.maxOrNull() ?: 0) - (times.minOrNull() ?: 0)) / 1e6
        val reasons = renders.toList().groupBy { it.first }.mapValues { it.value.size }
        val dec = rec.decoder.toList().filter { it.first in t0..(t0 + 15_000_000_000L) && it.second.startsWith("frame ") }
        val dupTimes = times.groupBy { it }.filter { it.value.size > 1 }
        out("$label: presented ${p1 - p0} (PreviewFrames.count), render calls finished ${frames.size}, distinct composition frames ${times.distinct().size} over %.2f s of composition (expected %.0f at 30 fps), repeated frame times ${times.size - times.distinct().size} (${dupTimes.size} distinct), backward steps ${times.zipWithNext().count { (a, b) -> b < a }}; render calls by reason $reasons; decoder frame requests ${dec.size} (cached ${dec.count { it.second.contains("how=cached") }}); position posts ${rec.positions.count { it[0] in t0..(t0 + 15_000_000_000L) }}"
            .format(span, span * 30))
    }

    // ---- a7: export identity --------------------------------------------------------------------------------------------
    @Test fun a7ExportIdentity() {
        state("a7")
        val f = file("typical H.264", Aud2Bench.TYPICAL); val m = Aud1Bench.media(f)
        val dur = 2_000_000L
        val cells = (0 until 6).map { k -> (-2f / 3 + (k % 3) * 2f / 3) to (if (k < 3) -.5f else .5f) }
        val layers = listOf(Mp1Bench.solid(100, 0xffff00ff.toInt(), dur)) + cells.mapIndexed { k, (x, y) ->
            Mp1Bench.placed(1L + k, f, m, Aud2Bench.distinctUri(f, k), 10 + k, ClipTiming(0, 5_000_000L + k * 7_000_000L, dur), x, y, 1f / 3) }
        val project = Aud2Bench.project(1920, 1080, dur, 30, layers)
        val events = CopyOnWriteArrayList<String>()
        VideoDecoder.testEvents = { e -> if (e.startsWith("open ") || e.startsWith("close ")) events += e.split(' ').take(2).joinToString(" ") }
        val out = File(context.cacheDir, "mp21-a7-export.mp4")
        try {
            kotlinx.coroutines.runBlocking { CompositionExporter(context).export(project, out) { } }
            val exportEvents = events.toList(); events.clear()
            val mmr = MediaMetadataRetriever(); mmr.setDataSource(out.path)
            val count = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toInt() ?: 0
            val hashes = (0 until count).map { i -> mmr.getFrameAtIndex(i)?.let { b -> val buf = ByteBuffer.allocate(b.byteCount); b.copyPixelsToBuffer(buf); b.recycle(); hash(buf.array()) } ?: "none" }
            mmr.release()
            this.out("a7 export: $count frames, ${out.length()} bytes; decoded frame digest ${hash(hashes.joinToString().toByteArray())}; decoder sequence (${exportEvents.size} events, opens ${exportEvents.count { it.startsWith("open") }}) digest ${hash(exportEvents.joinToString().toByteArray())}")
            hashes.chunked(10).forEachIndexed { i, ch -> this.out("a7 export frames ${i * 10}..: ${ch.joinToString(" ") { it.take(8) }}") }
            // The export renderer's own output (offscreen, as the exporter uses it): byte readback of every frame.
            val w = 640; val h = 360
            val small = project.copy(composition = project.composition.copy(width = w, height = h))
            val rate = FrameRate(30, 1)
            val readback = CompositionRenderer(context, MediaRepository(context)).use { r ->
                (0 until 60).map { i -> r.render(small, rate.frameTimeUs(i.toLong()))
                    val y = ByteBuffer.allocateDirect(w * h); val u = ByteBuffer.allocateDirect(w * h / 4); val v = ByteBuffer.allocateDirect(w * h / 4)
                    check(RenderBridge.copyYuv(r.session, y, u, v, w, w / 2, w / 2, 1, 1)) { "readback failed" }
                    hash(bytes(y) + bytes(u) + bytes(v)) }
            }
            this.out("a7 export renderer readback (640x360, 60 frames): digest ${hash(readback.joinToString().toByteArray())}; decoder sequence digest ${hash(events.joinToString().toByteArray())} (${events.size} events)")
        } finally { VideoDecoder.testEvents = null; out.delete() }
    }

    private fun bytes(b: ByteBuffer): ByteArray { b.rewind(); return ByteArray(b.remaining()).also { b.get(it) } }
    private fun hash(b: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }.take(16)
}
