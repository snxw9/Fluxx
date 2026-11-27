package com.fluxx.android.ui

import com.fluxx.android.editor.EditorAction
import com.fluxx.android.model.ClipTiming
import com.fluxx.android.model.CompositionLayer
import org.junit.Test

/**
 * MP1 A3: 30 sequential video layers of 2 s (60 s), played from 0 to the end, in three variants:
 *  (i) contiguous source ranges of the typical clip; (ii) non-contiguous in-points of the typical clip;
 *  (iii) alternating the typical clip and the VP9 file.
 * At every cut (29): presentation intervals within +/-300 ms (p50 / p99 / max), late frames (> 2 frame intervals),
 * decoder opens / closes / flush+seeks in that window, and the A/V offset (heard minus shown). Underruns per run.
 * A cut "needs pre-opening" when a frame there is more than 100 ms late (interval - one frame > 100 ms). Numbers only.
 */
class Mp1ClipsTest : Aud2Base() {
    override val tag = "MP1"
    private val clipUs = 2_000_000L

    @Test fun thirtyClips() {
        val f = file("typical H.264", Aud2Bench.TYPICAL); val m = Aud1Bench.media(f)
        val v = Aud2Bench.find(context, Aud2Bench.VP9)
        val tu = android.net.Uri.fromFile(f).toString()
        rec.start()
        host(Aud2Bench.project(1920, 1080, 30 * clipUs, 30, listOf(Aud2Bench.layer(1, f, m, timing = ClipTiming(0, 0, clipUs)))))
        warmPcm(tu, m.durationUs)
        fun clip(k: Int, file: java.io.File, media: Aud1Bench.Media, inUs: Long) = Aud2Bench.layer(1L + k, file, media, z = 10, timing = ClipTiming(k * clipUs, inUs, clipUs))
        run("(i) contiguous ranges, one file", (0 until 30).map { k -> clip(k, f, m, k * clipUs) })
        run("(ii) non-contiguous in-points, one file", (0 until 30).map { k -> clip(k, f, m, ((k * 13) % 29) * clipUs) })
        if (v != null) {
            val vm = Aud1Bench.media(v); warmPcm(android.net.Uri.fromFile(v).toString(), vm.durationUs)
            run("(iii) alternating H.264 / VP9", (0 until 30).map { k -> if (k % 2 == 0) clip(k, f, m, (k % 29) * clipUs) else clip(k, v, vm, k * 3_900_000L) })
        } else report("A3 30 clips (iii) skipped: VP9 file not found (${Aud2Bench.lookupLog})")
    }

    private fun run(label: String, layers: List<CompositionLayer>) {
        val frameUs = 33_333L
        compose.runOnIdle { editor.dispatch(EditorAction.Load(Aud2Bench.project(1920, 1080, 30 * clipUs, 30, layers))); c.update(editor.state.project, 0, false) }
        Thread.sleep(2500)
        seekTo(0, 1500)
        rec.clear()
        val t0 = System.nanoTime()
        toggle()
        val deadline = t0 + 75_000_000_000L
        while (System.nanoTime() < deadline && playing) Thread.sleep(200)
        if (playing) toggle()
        val frames = rec.frames.toList().filter { it[2] >= t0 }
        val ev = rec.decoder.toList()
        val lines = ArrayList<String>(); var worst = 0.0; var needPreopen = 0; var lateTotal = 0
        val all = ArrayList<Double>()
        for (k in 1 until 30) {
            val b = k * clipUs
            val win = frames.filter { it[0] in (b - 300_000)..(b + 300_000) }
            if (win.size < 2) { lines += "#$k: no frames"; continue }
            val iv = win.zipWithNext { a, c -> (c[2] - a[2]) / 1e6 }
            all += iv
            val late = iv.count { it > 2 * frameUs / 1000.0 }; lateTotal += late
            val mx = iv.maxOrNull() ?: 0.0; worst = maxOf(worst, mx); if (mx - frameUs / 1000.0 > 100) needPreopen++
            val e = ev.filter { it.first in win.first()[2]..win.last()[2] }
            val opens = e.count { it.second.startsWith("open ") }; val closes = e.count { it.second.startsWith("close") }
            val seeks = e.count { it.second.startsWith("frame ") && !it.second.contains("seek=-1") }
            val av = win.mapNotNull { fr -> rec.heardUs(fr[2])?.let { (it - fr[0]) / 1000.0 } }
            lines += "#$k p50 %.0f p99 %.0f max %.0f late %d o/c/s %d/%d/%d av %+.0f".format(Aud2Bench.pct(iv, .5), Aud2Bench.pct(iv, .99), mx, late, opens, closes, seeks, Aud2Bench.pct(av, .5))
        }
        val opens = ev.count { it.second.startsWith("open ") }; val closes = ev.count { it.second.startsWith("close") }
        val seeks = ev.count { it.second.startsWith("frame ") && !it.second.contains("seek=-1") }
        val iv = frames.zipWithNext { a, c -> (c[2] - a[2]) / 1e6 }
        report("A3 30 clips $label: ${frames.size} frames in %.1f s; all intervals ${Aud2Bench.stats(iv)} ms; at cuts ${Aud2Bench.stats(all)} ms; worst cut interval %.0f ms; late frames at cuts $lateTotal; cuts with a frame > 100 ms late (pre-open needed) $needPreopen/29; decoders opened $opens closed $closes, flush+seeks $seeks; underruns ${rec.underruns}"
            .format((frames.lastOrNull()?.get(2)?.minus(t0) ?: 0) / 1e9, worst))
        lines.chunked(6).forEach { report("A3 30 clips $label cuts: " + it.joinToString("; ")) }
        Thread.sleep(800)
    }
}
