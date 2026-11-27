package com.fluxx.android.ui

import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.ProjectDocument
import com.fluxx.android.render.PreviewFrames
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList

/**
 * MP1.2: plays the document in the editor from 0 to its end through the normal preview path (EditorScreen, PreviewController,
 * Play as the transport runs it) and traces memory at every clip change and every 2 s ([Mp12Bench.Trace]). The document
 * is in memory only; nothing is ever saved (the host has no save path).
 */
abstract class Mp12Base : Aud2Base() {
    override val tag = "MP12"
    protected fun say(line: String) = Mp12Bench.say("MP12", line)

    data class Outcome(val label: String, val reachedEnd: Boolean, val lastClip: Int, val clips: Int, val playedS: Double, val wallS: Double,
                       val peakPss: Double, val peakDmabuf: Double, val peakGraphics: Double, val maxDecoders: Int, val message: String?)

    protected fun info(repo: MediaRepository, uri: String): String = runCatching {
        val v = repo.video(uri); "${v.mime.removePrefix("video/")} ${v.width}x${v.height} ${v.frameRate?.let { "%.2f".format(it) } ?: "?"} fps"
    }.getOrDefault("?")

    protected fun playThrough(label: String, project: ProjectDocument, limitMs: Long): Outcome {
        val repo = MediaRepository(context)
        val clips = Mp12Bench.clips(project) { info(repo, it) }
        val durationUs = project.composition.resolvedDurationUs
        say("$label: ${clips.size} clips, %.1f s, composition ${project.composition.width}x${project.composition.height} @ ${project.composition.frameRate}".format(durationUs / 1e6))
        for (cl in clips) say("$label: clip ${cl.index} %.2f-%.2f s ${cl.info} '${cl.name.take(40)}'".format(cl.startUs / 1e6, cl.endUs / 1e6))
        val dec = Mp12Bench.DecoderTrack().also { it.start() }
        val trace = Mp12Bench.Trace(context, label, dec)
        val presented = CopyOnWriteArrayList<LongArray>()
        PreviewFrames.testObserver = { t, _, p -> presented += longArrayOf(t, p) }
        val main = InstrumentationRegistry.getInstrumentation()
        try {
            say("MP12CSV header ${Mp12Bench.Row.HEADER}")
            seekTo(0, 1500)
            trace.sample("start", 0, 0)
            val w0 = System.nanoTime()
            main.runOnMainSync { c.play() }
            var lastClip = 0; var lastSample = System.nanoTime(); var lastT = 0L; var stoppedAt = 0L
            while ((System.nanoTime() - w0) / 1_000_000 < limitMs) {
                Thread.sleep(100)
                val t = presented.lastOrNull()?.get(0) ?: 0L; lastT = maxOf(lastT, t)
                val clip = Mp12Bench.clipAt(clips, t)
                val now = System.nanoTime()
                if (clip != lastClip) { trace.sample("clip", clip, t); lastClip = clip; lastSample = System.nanoTime() }
                else if (now - lastSample >= 2_000_000_000L) { trace.sample("2s", clip, t); lastSample = System.nanoTime() }
                var playing = true; main.runOnMainSync { playing = c.state.value.playing }
                if (!playing) { if (stoppedAt == 0L) stoppedAt = now; if (now - stoppedAt > 1_000_000_000L) break } else stoppedAt = 0L
            }
            main.runOnMainSync { if (c.state.value.playing) c.pause() }
            val wall = (System.nanoTime() - w0) / 1e9
            Thread.sleep(1000)
            trace.sample("end", lastClip, lastT)
            var message: String? = null; main.runOnMainSync { message = c.state.value.message }
            val frameUs = project.composition.frameRate.let { it.frameTimeUs(1) - it.frameTimeUs(0) }
            val reached = lastT >= durationUs - 3 * frameUs
            // Frames and the worst presentation gap around each cut (+/- 500 ms).
            val fr = presented.toList()
            for (cl in clips.drop(1)) {
                val win = fr.filter { it[0] in (cl.startUs - 500_000)..(cl.startUs + 500_000) }
                val gap = win.zipWithNext { a, b -> (b[1] - a[1]) / 1e6 }.maxOrNull()
                val entry = trace.rows.firstOrNull { it.why == "clip" && it.clip == cl.index }
                say("$label: cut to clip ${cl.index} (${cl.info}): worst gap ${gap?.let { "%.0f ms".format(it) } ?: "no frames"}; at entry PSS ${entry?.let { "%.0f".format(it.pssMb) } ?: "?"} MB, dmabuf ${entry?.let { "%.0f".format(it.dmabufMb) } ?: "?"} MB, decoders ${entry?.decoders ?: "?"} (${entry?.decoderSizes ?: "?"})")
            }
            // Mid-clip pacing (Snxw sees lag inside clips too): presentation intervals more than 500 ms away from any cut.
            val frameMs = frameUs / 1000.0
            val allMid = ArrayList<Double>(); var lateAll = 0
            for (cl in clips) {
                val win = fr.filter { it[0] >= cl.startUs + 500_000 && it[0] < minOf(cl.endUs, durationUs) - 500_000 }
                val iv = win.zipWithNext { a, b -> (b[1] - a[1]) / 1e6 }
                if (iv.size < 4) continue
                allMid += iv
                val late = iv.count { it > 2 * frameMs }; lateAll += late
                val shownFps = (win.size - 1) * 1e9 / (win.last()[1] - win.first()[1]).coerceAtLeast(1)
                say("$label: mid-clip ${cl.index} (${cl.info}): ${win.size} frames, %.1f fps shown (composition %.0f), interval p50 %.0f p95 %.0f max %.0f ms, late (> 2 frames) $late"
                    .format(shownFps, 1000.0 / frameMs, Aud2Bench.pct(iv, .5), Aud2Bench.pct(iv, .95), iv.maxOrNull() ?: 0.0))
            }
            say("$label: mid-clip overall: ${allMid.size + clips.size} frames, interval p50 %.0f p95 %.0f p99 %.0f max %.0f ms, late (> 2 frames) $lateAll"
                .format(Aud2Bench.pct(allMid, .5), Aud2Bench.pct(allMid, .95), Aud2Bench.pct(allMid, .99), allMid.maxOrNull() ?: 0.0))
            val rows = trace.rows.toList()
            val o = Outcome(label, reached, lastClip, clips.size, lastT / 1e6, wall, rows.maxOf { it.pssMb }, rows.maxOf { it.dmabufMb },
                rows.maxOf { it.graphicsMb }, rows.maxOf { it.decoders }, message)
            // Slope of PSS and dmabuf per clip change after the 4th clip (warm-up: the decoder pool fills).
            fun slope(sel: (Mp12Bench.Row) -> Double): Double {
                val pts = rows.filter { it.why == "clip" && it.clip > 4 }.map { it.clip.toDouble() to sel(it) }
                if (pts.size < 2) return Double.NaN
                val mx = pts.map { it.first }.average(); val my = pts.map { it.second }.average()
                return pts.sumOf { (it.first - mx) * (it.second - my) } / pts.sumOf { (it.first - mx) * (it.first - mx) }.coerceAtLeast(1e-9)
            }
            say("$label: RESULT ${if (reached) "played to the end" else "STOPPED at clip $lastClip/${clips.size} (%.1f s)".format(lastT / 1e6)} in %.1f s wall; peak PSS %.0f MB, peak dmabuf %.0f MB, peak graphics %.0f MB, decoders open max ${o.maxDecoders}; slope after clip 4: PSS %+.1f MB/clip, dmabuf %+.1f MB/clip; decoders opened ${dec.opened} closed ${dec.closed} failed ${dec.failed}; trims ${trace.trims.map { it.second }}; MemAvailable min %.0f MB; status '${message ?: ""}'"
                .format(wall, o.peakPss, o.peakDmabuf, o.peakGraphics, slope { it.pssMb }, slope { it.dmabufMb }, rows.minOf { it.memAvailMb }))
            return o
        } finally {
            PreviewFrames.testObserver = null
            dec.stop(); trace.close()
        }
    }
}
