package com.fluxx.android.ui

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.ProjectManager
import com.fluxx.android.editor.EditorAction
import com.fluxx.android.media.AudioPeakProvider
import com.fluxx.android.model.*
import com.fluxx.android.render.MediaProbes
import com.fluxx.android.render.SourcePcm
import com.fluxx.android.render.SourcePcmCache
import com.fluxx.android.render.UsedRange
import com.fluxx.android.ui.timeline.WaveformProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TLV1.1 C1 / C3 on the A16 (run once before the fix and once after: `-e phase before|after`; files read in place through
 * MediaStore content URIs; Project 1 read-only through ProjectManager.loadProject, never saved).
 * - a: C3 waveform TIMING: transients in the PCM cache's true-timeline samples against the same onsets in the peaks the
 *   phone has STORED now (if any) and in freshly built peaks; in source ms, in px at 120 dp/s and at frame zoom, in frames.
 * - b: C1 waveform SPEED: from expanding the audio row to the first drawn waveform column and to every clip column of the
 *   laid-out window, COLD (no peaks, no PCM for the source: deleted through the caches' own files, never app data), PEAKS
 *   only, PCM only (the used range decoded for playback) and BOTH; with the provider's own probe lines (what ran, when).
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class Tlv1WaveformTest : Aud2Base() {
    override val tag = "TLV11W"
    private val phase = InstrumentationRegistry.getArguments().getString("phase") ?: "?"
    private val events = CopyOnWriteArrayList<Pair<Long, String>>()
    /** Per audio-row draw: (ns, layer, columns drawn with data, clip columns in the window). */
    private val drawsSeen = CopyOnWriteArrayList<LongArray>()
    private val provider get() = AudioPeakProvider.get(context)

    private var hosted = false
    private fun ensureHost(p: ProjectDocument) {
        if (!hosted) { host(p); hosted = true }
        else compose.runOnIdle { editor.setExpanded(emptySet()); editor.dispatch(EditorAction.Load(p)); c.update(editor.state.project, 0, false) }
    }

    @Before fun probesOn() {
        hosted = false
        MediaProbes.events = { s -> events += System.nanoTime() to s }
        WaveformProbe.columns = { ns, id, drawn, clip -> drawsSeen += longArrayOf(ns, id, drawn.toLong(), clip.toLong()) }
    }
    @After fun probesOff() { MediaProbes.events = null; WaveformProbe.columns = null }

    private fun say(line: String) = report("[$phase] $line")
    private fun frame() { compose.mainClock.advanceTimeByFrame(); Thread.sleep(16) }
    private fun pump(ms: Long) { val end = System.nanoTime() + ms * 1_000_000; while (System.nanoTime() < end) frame() }

    // ---- files ---------------------------------------------------------------------------------------------------------
    private fun content(like: String): Pair<Uri, String>? {
        val cr = context.contentResolver
        for (col in listOf(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, android.provider.MediaStore.Files.getContentUri("external")))
            cr.query(col, arrayOf(android.provider.MediaStore.MediaColumns._ID, android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
                "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf(like), null)?.use { c ->
                if (c.moveToFirst()) return android.content.ContentUris.withAppendedId(col, c.getLong(0)) to c.getString(1) }
        return null
    }
    private val p1File get() = File(context.filesDir, "projects/Project 1.fluxx")
    private var p1Stat: Pair<Long, Long>? = null
    private fun project1(): ProjectDocument? {
        if (!p1File.isFile) return null
        if (p1Stat == null) p1Stat = p1File.length() to p1File.lastModified()
        return kotlinx.coroutines.runBlocking { ProjectManager(context).loadProject(p1File) }.getOrNull()
    }
    private fun checkP1() { val s = p1Stat ?: return; val now = p1File.length() to p1File.lastModified()
        say("Project 1 stat: before ${s.first}/${s.second}, now ${now.first}/${now.second} -> ${if (now == s) "UNCHANGED" else "CHANGED"}")
        check(now == s) { "Project 1 changed on disk" } }
    /** Project 1's clip [k] (as AUD3 numbers them: start order) as a fresh layer at 0 (its own in-point, length and asset). */
    private fun p1Clip(p: ProjectDocument, k: Int): CompositionLayer? {
        val cl = Mp12Bench.clips(p) { "" }.firstOrNull { it.index == k } ?: return null
        val l = p.composition.layers.first { it.asset?.uri == cl.uri && it.timing.startUs == cl.startUs }
        return l.copy(id = 1, zOrder = 10, timing = l.timing.copy(startUs = 0), muted = false, visible = true)
    }

    /** A layer through the real add path (MediaRepository + EditorViewModel.addMedia from the content URI). */
    private fun added(item: Pair<Uri, String>): CompositionLayer {
        ensureHost(Aud2Bench.project(1920, 1080, 10_000_000, 30, emptyList()).let { p -> p.copy(composition = p.composition.copy(durationUs = null)) }); pump(500)
        val done = java.util.concurrent.CountDownLatch(1); var err: Throwable? = null
        CoroutineScope(Dispatchers.Main).launch {
            try { editor.addMedia(com.fluxx.android.media.MediaRepository(context), item.first, item.second, 0) } catch (t: Throwable) { err = t } finally { done.countDown() }
        }
        check(done.await(120, java.util.concurrent.TimeUnit.SECONDS)) { "add did not return" }
        err?.let { throw it }
        return compose.runOnIdle { editor.state.project.composition.layers.first { it.type == LayerType.VIDEO } }
    }

    // ---- caches (each cache's own files for ONE source; never app data) ---------------------------------------------------
    private fun sha(uri: String, bytes: Int) = java.security.MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).take(bytes).joinToString("") { "%02x".format(it) }
    private fun peakFiles(uri: String) = File(context.cacheDir, PeakCachePolicy.DIR).listFiles().orEmpty().filter { it.name.startsWith(sha(uri, 8) + "-") }
    private fun pcmFiles(uri: String) = File(context.cacheDir, com.fluxx.android.render.PcmCachePolicy.DIR).listFiles().orEmpty().filter { it.name.startsWith(sha(uri, 10) + "-") }
    private fun dropPeaks(uri: String) {
        compose.runOnIdle { provider.cancel(uri); provider.states.remove(uri) }
        peakFiles(uri).forEach { it.delete() }
    }
    private fun dropPcm(uri: String) { SourcePcmCache.get(context).release(uri); pcmFiles(uri).forEach { it.delete() } }
    private fun pcmOf(l: CompositionLayer): SourcePcm = SourcePcmCache.get(context).get(l.asset!!.uri!!, l.asset!!.durationUs ?: (l.timing.sourceInUs + l.timing.durationUs!!))
    /** Decodes [windowUs] of the source into the PCM cache (as playback's background fill would), up to [limitMs]. */
    private fun fillPcm(l: CompositionLayer, windowUs: LongRange, limitMs: Long): Long {
        val pcm = pcmOf(l); val t0 = System.nanoTime()
        pcm.setScope(listOf(windowUs)); pcm.fill()
        val f = UsedRange.framesOf(windowUs, pcm.frames)
        while (!pcm.covers(f.first, f.last) && (System.nanoTime() - t0) / 1_000_000 < limitMs) {
            Thread.sleep(100)
            if (!pcm.decoding) { var x = f.first; while (x < f.last && pcm.covers(x, minOf(f.last, x + 48_000))) x += 48_000; if (x < f.last) pcm.ensure(x) }
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        say("PCM fill ${MediaProbes.key(l.asset!!.uri!!)} %.1f..%.1f s: ${if (pcm.covers(f.first, f.last)) "complete" else "NOT complete"} in $ms ms".format(windowUs.first / 1e6, windowUs.last / 1e6))
        return ms
    }

    private fun load(l: CompositionLayer, fps: FrameRate) {
        val p = ProjectDocument(Composition(width = 1920, height = 1080, durationUs = l.timing.endUs, frameRate = fps, layers = FrozenList.of(listOf(l))))
        ensureHost(p)
        pump(1500)
    }

    // ---- C1 ------------------------------------------------------------------------------------------------------------
    data class Speed(val label: String, val usedS: Double, val mode: String, val firstMs: Long?, val completeMs: Long?, val notes: String)
    private val table get() = TABLE
    companion object { private val TABLE = CopyOnWriteArrayList<Speed>() }

    /** Expands the row at the clip's middle and waits for the first data column and for every clip column of the window. */
    private fun expandAndTime(label: String, l: CompositionLayer, mode: String, limitMs: Long): Speed {
        compose.runOnIdle { editor.setExpanded(emptySet()) }; pump(800)
        val mid = l.timing.startUs + (l.timing.durationUs ?: 0) / 2
        seekTo(mid, 0); pump(800)
        val key = MediaProbes.key(l.asset!!.uri!!)
        events.clear(); drawsSeen.clear()
        val t0 = System.nanoTime()
        compose.runOnIdle { editor.setExpanded(setOf(l.id)) }
        var first: Long? = null; var complete: Long? = null
        while ((System.nanoTime() - t0) / 1_000_000 < limitMs) {
            frame()
            for (d in drawsSeen) if (d[1] == l.id) {
                if (first == null && d[2] > 0) first = (d[0] - t0) / 1_000_000
                if (complete == null && d[3] > 0 && d[2] >= d[3]) complete = (d[0] - t0) / 1_000_000
            }
            if (complete != null) break
        }
        pump(300)
        val all = events.filter { it.second.contains(key) || it.second.startsWith("work-") }
        val chunks = all.filter { it.second.startsWith("peaks-chunk") }
        val mine = (all.filter { !it.second.startsWith("peaks-chunk") } + chunks.take(4)).sortedBy { it.first }.map { (ns, s) -> "+${(ns - t0) / 1_000_000}ms ${s.replace(key, "#")}" } +
            (if (chunks.size > 4) listOf("(${chunks.size} chunks: ${chunks.count { it.second.contains(" pcm ") }} from PCM, ${chunks.count { it.second.contains(" decode ") }} decoded)") else emptyList())
        val lastDraw = drawsSeen.lastOrNull { it[1] == l.id }
        val notes = "window clip columns ${lastDraw?.get(3) ?: -1}, drawn ${lastDraw?.get(2) ?: -1}; " + mine.joinToString(" | ")
        val s = Speed(label, (l.timing.durationUs ?: 0) / 1e6, mode, first, complete, notes)
        say("C1 $label [$mode]: used %.1f s; first pixels ${first ?: "NONE"} ms; complete window ${complete ?: "NOT within $limitMs"} ms; $notes".format(s.usedS))
        table += s
        return s
    }

    /** Cold, then peaks stored only, then PCM only, then both (skipping PCM where the PCM cap cannot hold the used range). */
    private fun speedAllModes(label: String, l: CompositionLayer, fps: FrameRate, limitMs: Long, pcmFits: Boolean = true) {
        val uri = l.asset!!.uri!!
        load(l, fps)
        val window = UsedRange.windowUs(l.timing.sourceInUs, l.timing.durationUs!!, l.asset!!.durationUs ?: Long.MAX_VALUE)
        dropPeaks(uri); dropPcm(uri)
        expandAndTime(label, l, "COLD", limitMs)
        dropPcm(uri)
        expandAndTime(label, l, "WARM peaks only", 30_000)
        if (pcmFits) {
            dropPeaks(uri); fillPcm(l, window, 600_000)
            expandAndTime(label, l, "WARM PCM only", limitMs)
            expandAndTime(label, l, "WARM both", 30_000)
        } else say("C1 $label: PCM modes skipped: the used range (%.0f s) exceeds the PCM cache cap (%d MB = %.0f s)".format(
            (window.last - window.first) / 1e6, com.fluxx.android.render.PcmCachePolicy.CAP_BYTES shr 20, com.fluxx.android.render.PcmCachePolicy.CAP_BYTES / 192_000.0))
        compose.runOnIdle { editor.setExpanded(emptySet()) }; pump(500)
    }

    @Test fun b1TypicalH264() {
        val t = content("%" + Aud2Bench.TYPICAL_ID + "%"); assumeTrue("typical clip not in MediaStore", t != null)
        speedAllModes("typical H.264 60 s", added(t!!), FrameRate(30, 1), 120_000)
    }
    @Test fun b2Vp9() {
        val v = content(Aud1Bench.PREFIX + "%"); assumeTrue("VP9 clip not in MediaStore", v != null)
        speedAllModes("VP9 122.8 s", added(v!!), FrameRate(20, 1), 120_000)
    }
    @Test fun b3Hevc10Minutes() {
        val h = content("Project 3%"); assumeTrue("Project 3 not in MediaStore", h != null)
        val l = added(h!!)
        val trimmed = l.copy(timing = ClipTiming(0, 20 * 60_000_000L, 10 * 60_000_000L))
        speedAllModes("HEVC 10 min used (20:00-30:00)", trimmed, FrameRate(30, 1), 400_000)
    }
    @Test fun b4Hevc50Minutes() {
        val h = content("Project 3%"); assumeTrue("Project 3 not in MediaStore", h != null)
        speedAllModes("HEVC 50 min used (whole)", added(h!!), FrameRate(30, 1), 600_000, pcmFits = false)
    }
    @Test fun b5Project1Clips() {
        val p = project1(); assumeTrue("Project 1 not found", p != null)
        for ((k, name) in listOf(17 to "P1 clip 17 HE-AAC 24k MKV", 14 to "P1 clip 14 MP4 edit list")) {
            val l = p1Clip(p!!, k) ?: run { say("C1 $name: not found"); null } ?: continue
            speedAllModes(name, l, p.composition.frameRate, 120_000)
        }
        checkP1()
    }
    @Test fun b6CollapseCancelsTheWorkAndKeepsWhatWasBuilt() {
        val h = content("Project 3%"); assumeTrue("Project 3 not in MediaStore", h != null)
        val l = added(h!!); val uri = l.asset!!.uri!!
        load(l, FrameRate(30, 1))
        dropPeaks(uri); dropPcm(uri)
        val first = expandAndTime("HEVC 50 min, collapse after the first pixels", l, "COLD then collapse", 60_000).firstMs
        val covered0 = compose.runOnIdle { (provider.states[uri] as? AudioPeakProvider.Peaks.Ready)?.pyramid?.coveredUs.orEmpty() }
        val t0 = System.nanoTime()
        compose.runOnIdle { editor.setExpanded(emptySet()) }
        var stoppedMs = -1L
        while (System.nanoTime() - t0 < 10_000_000_000L) { frame(); if (!compose.runOnIdle { provider.decoding(uri) }) { stoppedMs = (System.nanoTime() - t0) / 1_000_000; break } }
        pump(1500)
        val kept = compose.runOnIdle { (provider.states[uri] as? AudioPeakProvider.Peaks.Ready) }
        val keptS = kept?.pyramid?.coveredUs.orEmpty().sumOf { minOf(it.last, l.timing.endUs!! + 10_000_000) - it.first } / 1e6
        say("C2 cancel: first pixels $first ms; covered when collapsed ${covered0.joinToString { "%.0f..%.0f s".format(it.first / 1e6, minOf(it.last, 1L shl 42) / 1e6) }}; " +
            "work stopped ${if (stoppedMs >= 0) "$stoppedMs ms" else "NOT within 10 s"} after the collapse; kept in memory: %.0f s (complete ${kept?.complete}); stored files ${peakFiles(uri).map { it.length() }}".format(keptS))
        // Expanding again shows what was kept at once (the rest is built after it).
        val again = expandAndTime("HEVC 50 min, expand again", l, "after the collapse", 30_000)
        say("C2 re-expand: first pixels ${again.firstMs} ms")
        compose.runOnIdle { editor.setExpanded(emptySet()) }; pump(800)
        dropPeaks(uri)
        check(stoppedMs >= 0) { "the peak work did not stop after the collapse" }
    }

    @Test fun b9Table() {
        say("C1 TABLE (this invocation's classes run in name order; this line lists what the b* tests above recorded in this process)")
        for (s in table) say("C1 ROW | ${s.label} | %.1f s | ${s.mode} | first ${s.firstMs ?: "-"} ms | complete ${s.completeMs ?: "-"} ms".format(s.usedS))
    }

    // ---- C3 ------------------------------------------------------------------------------------------------------------
    /** One onset: true time [trueUs] (first sample at half the local peak, after a quiet stretch) and its threshold. */
    data class Onset(val trueUs: Double, val th: Float)

    /** The PCM cache's samples of [rangeUs] (48 kHz; mono = the channel of larger magnitude, signed, as the peaks fold). */
    private fun pcmMono(pcm: SourcePcm, rangeUs: LongRange): Pair<Long, FloatArray> {
        val f0 = rangeUs.first * 48 / 1000; val n = ((rangeUs.last - rangeUs.first) * 48 / 1000).toInt()
        val out = FloatArray(n); val dst = ShortArray(48_000 * 2); val scratch = ByteArray(48_000 * 4)
        var i = 0
        while (i < n) {
            val c = minOf(48_000, n - i)
            pcm.read(f0 + i, c, dst, scratch)
            for (k in 0 until c) { val a = dst[2 * k] / 32768f; val b = dst[2 * k + 1] / 32768f; out[i + k] = if (kotlin.math.abs(a) >= kotlin.math.abs(b)) a else b }
            i += c
        }
        return f0 to out
    }

    private fun onsets(f0: Long, x: FloatArray, max: Int = 60): List<Onset> {
        val w = 48; val m = x.size / w
        val env = FloatArray(m) { i -> var v = 0f; for (k in i * w until (i + 1) * w) { val a = kotlin.math.abs(x[k]); if (a > v) v = a }; v }
        data class C(val i: Int, val ratio: Float)
        val cands = ArrayList<C>()
        // A 5 ms rise to at least 3x the loudest of the 40 ms just before it (and at least -30 dBFS).
        for (i in 41 until m - 11) {
            var pre = 0f; for (k in i - 40 until i) if (env[k] > pre) pre = env[k]
            var cur = 0f; for (k in i until i + 5) if (env[k] > cur) cur = env[k]
            if (cur >= 0.03f && cur >= 3f * pre) cands += C(i, cur / maxOf(pre, 1e-4f))
        }
        val picked = ArrayList<C>()
        for (c in cands.sortedByDescending { it.ratio }) { if (picked.none { kotlin.math.abs(it.i - c.i) < 150 }) picked += c; if (picked.size >= max) break }
        return picked.sortedBy { it.i }.map { c ->
            var local = 0f; for (k in c.i * w until minOf(x.size, (c.i + 10) * w)) local = maxOf(local, kotlin.math.abs(x[k]))
            val th = local / 2
            // The first sample at half the local peak (the 40 ms before it are all below a third of the peak).
            var n = c.i * w; while (n < x.size && kotlin.math.abs(x[n]) < th) n++
            Onset((f0 + n) * 1e6 / 48_000, th)
        }
    }

    /**
     * The onset's bucket-centre time at [level] of [p]: of the buckets within 150 ms that reach the threshold after 20 ms of
     * buckets below it (an onset in the peaks), the one NEAREST the true onset; null if none.
     */
    private fun peakOnsetUs(p: PeakPyramid, level: Int, o: Onset): Double? {
        val bu = p.bucketUs(level); val (mn, mx) = p.levels[level]
        val from = ((o.trueUs - 150_000) / bu).toInt().coerceAtLeast(0); val to = ((o.trueUs + 150_000) / bu).toInt().coerceAtMost(mn.size - 1)
        val quiet = maxOf(1, (20_000 / bu).toInt())
        var best: Double? = null
        for (b in from..to) {
            if (WaveformMapping.envelope(mn[b], mx[b]) < o.th) continue
            var pre = 0f; for (k in maxOf(0, b - quiet) until b) pre = maxOf(pre, WaveformMapping.envelope(mn[k], mx[k]))
            if (pre >= o.th && bu <= 20_000) continue
            val c = b * bu + bu / 2
            if (best == null || kotlin.math.abs(c - o.trueUs) < kotlin.math.abs(best - o.trueUs)) best = c
        }
        return best
    }

    /** A stored peak file of ANY version from 4 on (the v4 layout is v5's): the BEFORE build's peaks, read by this test only. */
    private fun readAnyVersion(file: File): PeakPyramid? = runCatching {
        java.io.DataInputStream(file.inputStream().buffered()).use { inp ->
            val version = inp.readInt(); if (version < 4) return null
            val rate = inp.readInt(); val dur = inp.readLong(); val size = inp.readInt(); val ranges = inp.readInt()
            val mn = FloatArray(size); val mx = FloatArray(size); val covered = ArrayList<LongRange>(ranges)
            repeat(ranges) { covered += inp.readLong()..inp.readLong(); val from = inp.readInt(); val count = inp.readInt()
                for (i in from until from + count) { mn[i] = inp.readFloat(); mx[i] = inp.readFloat() } }
            PeakPyramid(rate, dur, PeakBuilder.levels(mn, mx), covered)
        }
    }.getOrNull()

    private fun stats(v: List<Double>) = if (v.isEmpty()) "n 0" else v.sorted().let { s -> "n ${s.size} median %+.2f worst %+.2f (min %+.2f max %+.2f)".format(s[s.size / 2], s.maxBy { kotlin.math.abs(it) }, s.first(), s.last()) }

    private fun freshPeaks(l: CompositionLayer): PeakPyramid? {
        val uri = l.asset!!.uri!!
        dropPeaks(uri)
        compose.runOnIdle { provider.request(uri, l.timing.sourceInUs, l.timing.durationUs!!, l.asset!!.durationUs) }
        val t0 = System.nanoTime()
        while (compose.runOnIdle { (provider.states[uri] as? AudioPeakProvider.Peaks.Ready)?.complete != true || provider.decoding(uri) } &&
            compose.runOnIdle { provider.states[uri] !is AudioPeakProvider.Peaks.NoAudio && provider.states[uri] !is AudioPeakProvider.Peaks.Failed } &&
            System.nanoTime() - t0 < 300_000_000_000L) Thread.sleep(100)
        return compose.runOnIdle { (provider.states[uri] as? AudioPeakProvider.Peaks.Ready)?.pyramid }
    }

    private fun timing(label: String, l: CompositionLayer, fps: FrameRate, stored: Pair<List<String>, PeakPyramid?>?) {
        val uri = l.asset!!.uri!!
        load(l, fps)
        val density = compose.density.density
        val inUs = l.timing.sourceInUs; val span = minOf(l.timing.durationUs!!, 120_000_000L)
        val range = inUs..(inUs + span)
        // (1) the peaks STORED on the phone now (any version: in the after run these are the before build's).

        val storedPyr = stored?.second
        say("C3 $label: stored peak files ${stored?.first.orEmpty()} (${if (storedPyr != null) "readable, rate ${storedPyr.sampleRate}" else "none"})")
        // (2) fresh peaks as this build makes them: cold (no PCM: decoded), then with the PCM cache holding the range.
        dropPcm(uri)
        val decoded = freshPeaks(l)
        fillPcm(l, range, 300_000)
        val fromPcm = freshPeaks(l)
        val (f0, x) = pcmMono(pcmOf(l), range)
        val ons = onsets(f0, x)
        val frameMs = 1000.0 * fps.denominator / fps.numerator
        for ((name, p) in listOf("stored" to storedPyr, "fresh, decoded (no PCM)" to decoded, "fresh, PCM cache present" to fromPcm)) {
            if (p == null) { say("C3 $label $name: no peaks"); continue }
            val raw = ons.mapNotNull { o -> peakOnsetUs(p, 0, o)?.let { (it - o.trueUs) / 1000.0 } }
            val parts = ArrayList<String>()
            parts += "source (level 0, bucket %.2f ms, rate ${p.sampleRate}): ${stats(raw)} ms".format(p.bucketUs(0) / 1000)
            for (dps in listOf(120.0, 2400.0)) {
                val usPerPx = 1e6 / (dps * density); val level = p.levelFor(usPerPx)
                // As drawn: the first column whose bucket range reaches the onset bucket; its centre against the true onset.
                val drawn = ons.mapNotNull { o -> peakOnsetUs(p, level, o)?.let { c -> val start = c - p.bucketUs(level) / 2; (start - usPerPx / 2 - o.trueUs) / 1000.0 } }
                val med = drawn.sorted().getOrNull(drawn.size / 2) ?: Double.NaN
                parts += "drawn at %.0f dp/s (level $level, %.2f ms/px): ${stats(drawn)} ms = %+.2f px = %+.3f frame".format(dps, usPerPx / 1000, med * 1000 / usPerPx, med / frameMs)
            }
            say("C3 $label $name (${ons.size} onsets in %.0f s, frame %.2f ms): ".format(span / 1e6, frameMs) + parts.joinToString("; "))
        }
    }

    @Test fun a1TimingProject1AndTypical() {
        val cases = ArrayList<Triple<String, CompositionLayer, FrameRate>>()
        val p = project1()
        if (p != null) for ((k, name) in listOf(1 to "P1 clip 1 AAC-LC MKV", 15 to "P1 clip 15 Opus", 17 to "P1 clip 17 HE-AAC 24k MKV", 14 to "P1 clip 14 MP4 edit list")) {
            val l = p1Clip(p, k) ?: run { say("C3 $name: not found"); null } ?: continue
            cases += Triple(name, l, p.composition.frameRate)
        } else say("C3: Project 1 not found")
        content("%" + Aud2Bench.TYPICAL_ID + "%")?.let { cases += Triple("typical H.264", added(it), FrameRate(30, 1)) } ?: say("C3: typical clip not found")
        sealMp4()?.let { cases += Triple("Seal MP4 (edit list)", added(it), FrameRate(30, 1)) } ?: say("C3: no Seal MP4 with an edit list found")
        // Every stored file is read BEFORE any peaks are requested: this build deletes peak files of older versions on first use.
        val stored = cases.associate { (name, l, _) -> name to peakFiles(l.asset!!.uri!!).let { fs -> fs.map { it.name.substringAfterLast('-') } to fs.firstOrNull()?.let { readAnyVersion(it) } } }
        for ((name, l, fps) in cases) timing(name, l, fps, stored[name])
        checkP1()
    }

    /** One Download/Seal MP4 outside Project 1 whose audio starts before 0 (an edit list). */
    private fun sealMp4(): Pair<Uri, String>? {
        val inP1 = project1()?.composition?.layers?.mapNotNull { it.asset?.uri }?.toSet().orEmpty()
        val cr = context.contentResolver
        for (volume in android.provider.MediaStore.getExternalVolumeNames(context)) {
            val col = android.provider.MediaStore.Video.Media.getContentUri(volume)
            cr.query(col, arrayOf(android.provider.MediaStore.MediaColumns._ID, android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
                "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("Download/Seal/", "%.mp4"),
                "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val uri = android.content.ContentUris.withAppendedId(col, c.getLong(0))
                    if (uri.toString() in inP1) continue
                    val ex = android.media.MediaExtractor()
                    val edit = try { ex.setDataSource(context, uri, null)
                        val t = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)!!.startsWith("audio/") } ?: continue
                        ex.selectTrack(t); ex.sampleTime < 0 } catch (_: Exception) { false } finally { ex.release() }
                    if (edit) return uri to c.getString(1)
                }
            }
        }
        return null
    }
}
