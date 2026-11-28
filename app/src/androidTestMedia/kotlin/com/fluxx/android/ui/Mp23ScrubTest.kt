package com.fluxx.android.ui

import android.media.MediaExtractor
import android.media.MediaFormat
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.editor.EditorAction
import com.fluxx.android.model.*
import com.fluxx.android.render.MediaProbes
import com.fluxx.android.render.PreviewFrames
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.util.concurrent.CopyOnWriteArrayList

/**
 * MP2.3: scrubbing, per codec, through the real preview path (EditorScreen, PreviewController; the Compose test clock
 * pumped one frame per 16 ms as in [Mp21Test]). The same class runs BEFORE (B0) and AFTER (B3) the scrub strip and the
 * decode-behind; every line is printed for comparison. One in-memory document per file (a single video layer over the
 * file's used range), files read in place through MediaStore content URIs (never copied); a missing file skips its test.
 *
 * Per file: the sync-frame spacing inside the used range; 1. a slow backward scrub (1 frame per step, 100 ms apart);
 * 2. fast backward sweeps at 1x / 2x / 4x / 8x real time (media seconds per second) for 3 s of finger movement each;
 * 3. the same forward; 4. the first backward step after a pause (play 2 s, pause, step one frame back, then five more
 * frames 40 ms apart), twice; 5. back-and-forth (direction every 400 ms for 4 s at 2x).
 * For every presented frame: the shown frame's media time (the decoder's `shown=` or the strip's), against the frame's
 * target. Measures: shown frames, out of order (later than the previous shown frame while the finger moves backward;
 * earlier when forward), ahead of the target, worst lag behind the target and behind the finger, the shown-frame rate,
 * the first frame after the first move, and the exact frame after the last move.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class Mp23ScrubTest : Mp12Base() {
    override val tag = "MP23"
    private val main get() = InstrumentationRegistry.getInstrumentation()
    private val probes = CopyOnWriteArrayList<Pair<Long, String>>()
    private val renders = CopyOnWriteArrayList<Triple<Long, String, Long>>()

    private fun out(line: String) = Mp12Bench.say("MP23", line)
    private fun raw(line: String) = Mp12Bench.say("MP23RAW", line)
    private fun frame() { compose.mainClock.advanceTimeByFrame(); Thread.sleep(16) }
    private fun pump(ms: Long) { val end = System.nanoTime() + ms * 1_000_000; while (System.nanoTime() < end) frame() }
    private fun seek(us: Long) = main.runOnMainSync { editor.dispatch(EditorAction.Seek(us)); c.seek(editor.state.playheadUs) }
    private fun clearAll() { rec.clear(); probes.clear(); renders.clear() }

    // ---- the files ----------------------------------------------------------------------------------------------------
    data class Src(val label: String, val uri: String, val name: String, val codec: String, val width: Int, val height: Int,
                   val fps: Double, val durationUs: Long, val inUs: Long, val useUs: Long)

    /** A MediaStore video by DISPLAY_NAME pattern (LIKE), read in place; null when missing or unreadable. */
    private fun find(label: String, like: String, inUs: Long = 0, useUs: Long = Long.MAX_VALUE): Src? {
        val cr = context.contentResolver
        for (volume in MediaStore.getExternalVolumeNames(context)) {
            val collection = MediaStore.Video.Media.getContentUri(volume)
            cr.query(collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf(like), "${MediaStore.MediaColumns.DISPLAY_NAME} ASC")?.use { cur ->
                while (cur.moveToNext()) {
                    val uri = android.content.ContentUris.withAppendedId(collection, cur.getLong(0))
                    val ex = MediaExtractor()
                    try {
                        ex.setDataSource(context, uri, null)
                        val t = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: continue
                        val f = ex.getTrackFormat(t)
                        val dur = f.getLong(MediaFormat.KEY_DURATION)
                        var fps = if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) f.getNumber(MediaFormat.KEY_FRAME_RATE)!!.toDouble() else 0.0
                        if (fps <= 0) { ex.selectTrack(t); val ts = ArrayList<Long>(); while (ex.sampleTime >= 0 && ts.size < 121) { ts += ex.sampleTime; ex.advance() }
                            ts.sort(); fps = if (ts.size > 2) (ts.size - 1) * 1e6 / (ts.last() - ts.first()) else 30.0 }
                        val use = minOf(useUs, dur - inUs)
                        return Src(label, uri.toString(), cur.getString(1), f.getString(MediaFormat.KEY_MIME)!!.removePrefix("video/"),
                            f.getInteger(MediaFormat.KEY_WIDTH), f.getInteger(MediaFormat.KEY_HEIGHT), fps, dur, inUs, use)
                    } catch (_: Exception) { } finally { ex.release() }
                }
            }
        }
        return null
    }
    private fun need(s: Src?, what: String): Src { assumeTrue("MP23: $what not found in MediaStore; skipping", s != null); return s!! }

    @Test fun b01H264Typical() = matrix(need(find("H.264 1080p30 (typical)", "%" + Aud2Bench.TYPICAL_ID + "%"), "the typical H.264 clip"))
    @Test fun b02H264Long24() = matrix(need(find("H.264 1080p24 (Seal)", "ALL THE MONEY - NARUTO%"), "the Seal H.264 24 fps clip"))
    @Test fun b03Vp9Worst() = matrix(need(find("VP9 1920x1440 20 fps (worst case)", Aud1Bench.PREFIX + "%"), "the VP9 worst-case file"))
    @Test fun b04Vp91080() = matrix(need(find("VP9 1080p (Seal)", "DECONSTRUCTION%"), "the Seal VP9 1080p clip"))
    /** The 50-minute HEVC file: a clip cut from it (in-point 20:00, 60 s); the used range is 19:50-21:10 only. */
    @Test fun b05HevcLong() = matrix(need(find("HEVC 1080p30 (50-minute file, 60 s at 20:00)", Mp1Bench.LONG_PREFIX + "%", inUs = 1_200_000_000L, useUs = 60_000_000L), "the 50-minute HEVC file"))
    @Test fun b06HevcSeal() = matrix(need(find("HEVC 1440x1080 30 fps (Seal)", "Video by gusa.ae%"), "the Seal HEVC clip"))
    @Test fun b07Av1Small() = matrix(need(find("AV1 640x480 30 fps (Seal)", "Brent Rambo%"), "the Seal AV1 640x480 clip"))
    @Test fun b08Av11080() = matrix(need(find("AV1 1080p30 (Seal)", "bzpy - %"), "the Seal AV1 1080p clip"))

    // ---- GOP ------------------------------------------------------------------------------------------------------------
    /** Sync-frame spacing over the used range (in-point to out-point), from the sample table (flags only). */
    private fun gop(s: Src): String {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, android.net.Uri.parse(s.uri), null)
            val t = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            ex.selectTrack(t); ex.seekTo(s.inUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val syncs = ArrayList<Long>(); var frames = 0
            while (ex.sampleTime >= 0 && ex.sampleTime <= s.inUs + s.useUs) {
                if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) syncs += ex.sampleTime
                frames++; ex.advance()
            }
            val iv = syncs.sorted().zipWithNext { a, b -> (b - a) / 1000.0 }
            return "${syncs.size} sync frames in $frames samples; sync interval p50 %.0f max %.0f ms (%.1f frames p50)".format(
                Aud2Bench.pct(iv, .5), iv.maxOrNull() ?: Double.NaN, Aud2Bench.pct(iv, .5) * s.fps / 1000)
        } catch (e: Exception) { return "GOP not read (${e.javaClass.simpleName})" } finally { ex.release() }
    }

    // ---- the strip (after builds only; looked up by reflection so the class runs on the baseline too) -----------------
    private fun stripCoverage(s: Src): Double? = runCatching {
        Class.forName("com.fluxx.android.render.ScrubStrips").getMethod("testCoverage", android.content.Context::class.java,
            String::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .invoke(null, context, s.uri, s.inUs, s.inUs + s.useUs, s.durationUs) as Double
    }.getOrNull()

    private fun waitStrip(s: Src) {
        val first = stripCoverage(s) ?: run { out("${s.label}: strip: not in this build"); return }
        val t0 = System.nanoTime()
        var cov = first
        while (cov < 0.999 && (System.nanoTime() - t0) / 1_000_000 < 240_000) { pump(500); cov = stripCoverage(s) ?: 0.0 }
        val built = probes.filter { it.second.startsWith("strip-stop") }
        out("${s.label}: strip coverage of the used range %.1f %% -> %.1f %% after %.1f s idle; builder runs ${built.size}: ${built.joinToString(" | ") { it.second }}".format(
            first * 100, cov * 100, (System.nanoTime() - t0) / 1e9))
    }

    // ---- one file -------------------------------------------------------------------------------------------------------
    private lateinit var s: Src
    private var frameUs = 33_333L
    private var rate = FrameRate(30, 1)
    private val key get() = MediaProbes.key(s.uri)

    private fun matrix(src: Src) {
        s = src
        out("${s.label}: '${s.name.take(32)}' ${s.codec} ${s.width}x${s.height} %.2f fps, %.1f s; clip: source %.1f-%.1f s".format(s.fps, s.durationUs / 1e6, s.inUs / 1e6, (s.inUs + s.useUs) / 1e6))
        out("${s.label}: state ${TestDeviceState.read()}")
        out("${s.label}: GOP ${gop(s)}")
        val fps = Math.round(s.fps).toInt().coerceIn(10, 60)
        rate = FrameRate(fps, 1); frameUs = rate.frameTimeUs(1) - rate.frameTimeUs(0)
        val (w, h) = if (s.width * 3 == s.height * 4) 1440 to 1080 else 1920 to 1080
        val layer = CompositionLayer(1, LayerType.VIDEO, name = "mp23", zOrder = 10, timing = ClipTiming(0, s.inUs, s.useUs),
            asset = AssetReference("mp23-bench", s.uri, access = MediaAccess.AVAILABLE, durationUs = s.durationUs))
        rec.start()
        PreviewFrames.testRender = { r, t -> renders += Triple(System.nanoTime(), r, t) }
        MediaProbes.events = { e -> probes += System.nanoTime() to e }
        try {
            host(Aud2Bench.project(w, h, s.useUs, fps, listOf(layer)))
            pump(2500)
            waitStrip(s)
            val d = s.useUs
            val endPos = rate.snap(d - 1_000_000); val startPos = rate.snap(1_000_000)
            // 1. slow backward: 30 steps of one frame, 100 ms apart.
            report("slow backward (1 frame / 100 ms)", run(endPos, -1) { i, _ -> if (i >= 30) null else endPos - i * frameUs to 100L })
            for (x in listOf(1, 2, 4, 8)) {
                val span = minOf(3_000L, (d - 2_000_000) / 1000 / x)
                report("backward ${x}x (${span} ms)", sweep(endPos, -x.toDouble(), span))
                report("forward ${x}x (${span} ms)", sweep(startPos, x.toDouble(), span))
            }
            pauseStep(rate.snap(d / 2)); pauseStep(rate.snap(d * 3 / 4))
            val mid = rate.snap(d / 2); val bfSpan = minOf(4_000L, (d / 2 - 1_000_000) / 1000 / 2 * 2)
            report("back-and-forth 2x every 400 ms (${bfSpan} ms)", run(mid, 0) { _, el ->
                if (el > bfSpan) null else {
                    // A triangle wave: back 0.8 s of media in 400 ms, then forward again.
                    val seg = el / 400; val inSeg = el % 400
                    val pos = mid - (if (seg % 2 == 0L) inSeg else 400 - inSeg) * 2_000L
                    pos.coerceIn(0, d - frameUs) to 16L } })
        } finally {
            PreviewFrames.testRender = null; MediaProbes.events = null
        }
    }

    // ---- sweeps ---------------------------------------------------------------------------------------------------------
    private class Shown(val frameUs: Long, val startNs: Long, val presentNs: Long, val target: Long, val shown: Long, val kind: String)
    private class Run(val t0: Long, val lastMove: Long, val steps: List<LongArray>, val frames: List<Shown>, val dir: Int)

    /** A finger moving at [x] times real time from [fromUs] for [ms]: one seek per UI frame (16 ms). */
    private fun sweep(fromUs: Long, x: Double, ms: Long): Run = run(fromUs, if (x < 0) -1 else 1) { _, el ->
        if (el > ms) null else (fromUs + (x * el * 1000).toLong()).coerceIn(0, s.useUs - frameUs) to 16L }

    /**
     * Seeks from [plan] (step index and elapsed ms -> composition time and the wait after it, null: done), then waits
     * until the exact frame of the last target is presented (at most 8 s; long-GOP backward decodes take seconds).
     */
    private fun run(fromUs: Long, dir: Int, settled: Boolean = true, plan: (Int, Long) -> Pair<Long, Long>?): Run {
        if (settled) { seek(fromUs); pump(1500); clearAll() }
        val steps = ArrayList<LongArray>()
        val t0 = System.nanoTime()
        var i = 0
        while (true) {
            val (t, wait) = plan(i++, (System.nanoTime() - t0) / 1_000_000) ?: break
            seek(t); steps += longArrayOf(System.nanoTime(), rate.snap(t))
            val end = System.nanoTime() + wait * 1_000_000
            while (System.nanoTime() < end) frame()
        }
        val lastMove = steps.last()[0]
        val finalUs = steps.last()[1]
        // Settled: the exact frame of the last target, or its proven floor frame with nothing newer for 400 ms (a forward
        // sweep's floor frame is the exact one: no settle render follows it). At most 8 s.
        val until = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < until) {
            val fr = shown(lastMove).filter { it.frameUs == finalUs }
            if (fr.any { it.kind == "exact" }) break
            val lastPresent = rec.frames.lastOrNull()?.get(2) ?: 0L
            if (fr.any { it.kind == "floor" } && System.nanoTime() - lastPresent > 400_000_000L) break
            pump(100)
        }
        pump(300)
        // The first step repeats the start position (the redraw of the frame already on screen): not a new frame.
        return Run(t0, lastMove, steps, shown(t0).dropWhile { it.frameUs == steps.first()[1] && steps.size > 1 }, dir)
    }

    /** Every presented frame since [t0] with the frame the layer showed (decoder `shown=` or strip `shown=`). */
    private fun shown(t0: Long): List<Shown> {
        val dec = rec.decoder.toList().filter { it.second.startsWith("frame $key ") }
        val strip = probes.toList().filter { it.second.startsWith("strip-frame ") && it.second.contains(" key=$key ") }
        val ev = (dec + strip).sortedBy { it.first }
        return rec.frames.toList().filter { it[2] >= t0 }.mapNotNull { f ->
            val target = s.inUs + f[0]
            val e = ev.lastOrNull { (ns, line) -> ns in f[1]..f[2] && Mp1Bench.field(line, "t")?.toLongOrNull() == target } ?: return@mapNotNull null
            val how = Mp1Bench.field(e.second, "how") ?: ""
            // floor: the proven floor frame (ScrubFloor's exact floor); approx: a nearer cached / sync frame (bounded decode).
            val kind = if (e.second.startsWith("strip-frame")) "strip" else if (how == "floor") "floor" else if (how.startsWith("floor")) "approx" else "exact"
            Shown(f[0], f[1], f[2], target, Mp1Bench.field(e.second, "shown")?.toLongOrNull() ?: -1, kind)
        }
    }

    private fun f0(v: Double) = if (v.isNaN()) "-" else "%.0f".format(v)

    private fun report(case: String, r: Run) {
        val moving = r.frames.filter { it.presentNs <= r.lastMove + 40_000_000 }
        // The finger's direction at a frame's request (back-and-forth: from the steps around it).
        fun dirAt(ns: Long): Int {
            if (r.dir != 0) return r.dir
            val k = r.steps.indexOfLast { it[0] <= ns }.coerceAtLeast(1)
            return java.lang.Long.signum(r.steps[k][1] - r.steps[k - 1][1]).toInt()
        }
        var ooo = 0
        moving.zipWithNext { a, b -> val d = dirAt(b.startNs); if ((d < 0 && b.shown > a.shown) || (d > 0 && b.shown < a.shown)) ooo++ }
        val approx = moving.filter { it.kind != "exact" }
        val ahead = approx.count { it.shown > it.target }
        val aheadExact = moving.filter { it.kind == "exact" }.count { it.shown > it.target }
        val lag = moving.maxOfOrNull { (it.target - it.shown) / 1000.0 } ?: Double.NaN
        // Behind the finger: the finger's position when the frame was presented, minus the frame shown (absolute).
        val finger = moving.maxOfOrNull { f -> val st = r.steps.lastOrNull { it[0] <= f.presentNs } ?: r.steps.first()
            kotlin.math.abs(s.inUs + st[1] - f.shown) / 1000.0 } ?: Double.NaN
        val spanS = (r.lastMove - r.t0) / 1e9
        val firstMove = r.steps.getOrNull(1)?.get(0) ?: r.t0
        val first = r.frames.firstOrNull()?.let { (it.presentNs - firstMove) / 1e6 } ?: Double.NaN
        val finalUs = r.steps.last()[1]
        val atFinal = r.frames.filter { it.presentNs >= r.lastMove && it.frameUs == finalUs }
        // The exact frame, or (no settle render followed it) the proven floor frame shown last at the final target.
        val exactFrame = atFinal.firstOrNull { it.kind == "exact" } ?: atFinal.lastOrNull { it.kind == "floor" }
        val exact = exactFrame?.let { (it.presentNs - r.lastMove) / 1e6 } ?: Double.NaN
        val kinds = moving.groupingBy { it.kind }.eachCount()
        out("${s.label} | $case: ${r.steps.size} moves, ${moving.size} frames shown (strip ${kinds["strip"] ?: 0}, floor ${kinds["floor"] ?: 0}, approx ${kinds["approx"] ?: 0}, exact ${kinds["exact"] ?: 0}); " +
            "out of order $ooo; ahead of target $ahead (exact-rule frames ahead $aheadExact); worst lag behind target ${f0(lag)} ms, behind finger ${f0(finger)} ms; " +
            "rate %.1f fps; first frame ${f0(first)} ms after the first move; exact frame ${f0(exact)} ms${if (exactFrame?.kind == "floor") " (floor)" else ""} after the last move; strip share %.0f %%".format(
                if (spanS > 0) moving.size / spanS else Double.NaN, if (moving.isEmpty()) 0.0 else 100.0 * (kinds["strip"] ?: 0) / moving.size))
        raw("${s.label} | $case S first move %.0f ms, last move %.0f ms, final target ${finalUs / 1000} ms".format((r.steps.getOrNull(1)?.get(0)?.minus(r.t0) ?: 0) / 1e6, (r.lastMove - r.t0) / 1e6))
        r.frames.chunked(30).forEach { ch -> raw("${s.label} | $case F " + ch.joinToString(" ") { "${it.frameUs / 1000}/${it.kind[0]}/${(it.shown - s.inUs) / 1000}/${"%.0f".format((it.presentNs - r.t0) / 1e6)}" }) }
    }

    // ---- the first backward step after a pause --------------------------------------------------------------------------
    /**
     * Play (once the frames really move, 2 s more), pause (the app's toggle: rest on the floor frame), wait until the
     * decoder is idle (600 ms without a request); step one frame back (the exact frame's time);
     * then a short backward sweep at 1x for 1 s from there: the first new frame after its first move and the next 5.
     */
    private fun pauseStep(atUs: Long) {
        seek(atUs); pump(1500)
        clearAll()
        main.runOnMainSync { editor.dispatch(EditorAction.SelectMarker(null)); c.play() }
        // Real playback: wait (at most 10 s) until the presented frames have moved 1 s past the start, then play 2 s more.
        val playUntil = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < playUntil && (rec.frames.lastOrNull()?.get(0) ?: 0) < atUs + 1_000_000) pump(100)
        pump(2000)
        clearAll()
        main.runOnMainSync {
            if (c.state.value.playing) c.pause()
            val shownUs = rate.frameTimeUs(rate.floorFrame(editor.state.playheadUs))
            if (shownUs != editor.state.playheadUs) { editor.dispatch(EditorAction.Seek(shownUs)); c.seek(editor.state.playheadUs) }
        }
        // Idle: no decoder request for 600 ms (the paused frame settled, decode-behind done), at most 8 s.
        val idleUntil = System.nanoTime() + 8_000_000_000L
        pump(400)
        while (System.nanoTime() < idleUntil && System.nanoTime() - (rec.decoder.lastOrNull()?.first ?: 0L) < 600_000_000L) pump(100)
        val behind = rec.decoder.toList().filter { it.second.startsWith("behind") } + probes.toList().filter { it.second.startsWith("behind") }
        var paused = 0L; main.runOnMainSync { paused = editor.state.playheadUs }
        raw("${s.label} | pause trail " + (rec.decoder.toList() + probes.toList().filter { it.second.startsWith("behind") || it.second.startsWith("strip-") })
            .sortedBy { it.first }.filter { !it.second.contains("how=cached") }.joinToString(" | ") { it.second.take(160) })
        raw("${s.label} | pause renders " + renders.toList().joinToString(" ") { "${it.second}@${it.third / 1000}" })
        clearAll()
        val stepAt = System.nanoTime(); val stepUs = rate.frameTimeUs(rate.nearestFrame(paused) - 1)
        seek(stepUs)
        val until = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < until && shown(stepAt).none { it.frameUs == stepUs && it.kind == "exact" }) frame()
        val step = shown(stepAt).firstOrNull { it.frameUs == stepUs }
        val exactMs = shown(stepAt).firstOrNull { it.frameUs == stepUs && it.kind == "exact" }?.let { (it.presentNs - stepAt) / 1e6 } ?: Double.NaN
        out("${s.label} | first backward step after a pause at ${paused / 1000} ms: one frame back -> exact frame ${f0(exactMs)} ms (first shown ${step?.kind ?: "-"}); " +
            "decode-behind at the pause: ${behind.size}${behind.joinToString("") { " [" + it.second.substringAfter(' ').substringAfter(' ') + "]" }}")
        raw("${s.label} | pause step D " + rec.decoder.toList().filter { it.first >= stepAt && it.second.startsWith("frame $key ") }.take(6).joinToString(" | ") { "${"%.1f".format((it.first - stepAt) / 1e6)} ${it.second.substringAfter(' ').substringAfter(' ')}" })
        pump(400)
        clearAll()
        val r = run(stepUs, -1, settled = false) { _, el -> if (el > 1000) null else (stepUs - el * 1000).coerceAtLeast(0) to 16L }
        report("short backward sweep 1x (1 s) after the pause at ${paused / 1000} ms", r)
        out("${s.label} | short sweep after the pause: first 6 new frames (ms after the first move / kind / lag behind target ms): " +
            r.frames.take(6).joinToString(", ") { "${f0((it.presentNs - (r.steps.getOrNull(1)?.get(0) ?: r.t0)) / 1e6)}/${it.kind}/${f0((it.target - it.shown) / 1000.0)}" })
    }
}
