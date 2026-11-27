package com.fluxx.android.ui

import com.fluxx.android.editor.EditorAction
import com.fluxx.android.model.ClipTiming
import com.fluxx.android.model.CompositionLayer
import org.junit.Test
import java.io.File

/**
 * AUD2 A5: simultaneous video layers. The typical H.264 clip on 1, 2, 3, 4 and 6 overlapping layers, each under its own
 * URI spelling so each gets its OWN decoder (the renderer pools decoders by URI), 20 s each from the middle; then the VP9
 * worst case plus 2 H.264 layers; then 3 layers sharing ONE URI with staggered in-points (what an edit of one source on
 * several layers does today). Per run: preview pacing, skipped frames, A/V offset, underruns, UI pacing, memory before /
 * during (dumpsys meminfo, read-only), decoder opens / failures / evictions, frames served per decoder. Numbers only.
 */
class Aud2LayersTest : Aud2Base() {
    private val pkg get() = context.packageName

    @Test fun a5Layers() {
        val f = file("typical H.264", Aud2Bench.TYPICAL); val m = Aud1Bench.media(f)
        val beats = Aud1Bench.beats(Aud1Bench.decodeEnergy(f))
        rec.start()
        val (w, h) = Aud2Bench.canvas(m)
        host(Aud2Bench.project(w, h, m.durationUs, m.fps, listOf(Aud2Bench.layer(1, f, m))))
        for (k in 0 until 6) warmPcm(Aud2Bench.distinctUri(f, k), m.durationUs)
        report("A5 baseline memory (1 layer, paused): " + Aud2Bench.mem(Aud2Bench.meminfo(pkg)))
        for (n in listOf(1, 2, 3, 4, 6)) {
            val layers = (0 until n).map { k -> Aud2Bench.layer(1L + k, f, m, uri = Aud2Bench.distinctUri(f, k), z = 10 + k, opacity = if (k == 0) 1f else .5f) }
            run("$n H.264 layer(s), separate decoders", Aud2Bench.project(w, h, m.durationUs, m.fps, layers), m.durationUs / 2 - 10_000_000, 1_000_000L / m.fps, beats)
        }
        // Mixed: the VP9 worst case plus 2 H.264 layers (the composition is the H.264 length).
        Aud2Bench.find(context, Aud2Bench.VP9)?.let { v ->
            val vm = Aud1Bench.media(v)
            warmPcm(android.net.Uri.fromFile(v).toString(), vm.durationUs)
            val layers = listOf(Aud2Bench.layer(1, v, vm, z = 10, timing = ClipTiming(0, 0, m.durationUs))) +
                (0 until 2).map { k -> Aud2Bench.layer(2L + k, f, m, uri = Aud2Bench.distinctUri(f, k), z = 11 + k, opacity = .5f) }
            run("VP9 + 2 H.264 layers", Aud2Bench.project(1920, 1080, m.durationUs, m.fps, layers), 20_000_000, 1_000_000L / m.fps, emptyList())
        } ?: report("A5 mixed run skipped: VP9 file not found (${Aud2Bench.lookupLog})")
        // Shared decoder: one URI on 3 layers, in-points 10 s apart (the source time differs per layer on every frame).
        val shared = (0 until 3).map { k -> Aud2Bench.layer(1L + k, f, m, z = 10 + k, opacity = if (k == 0) 1f else .5f,
            timing = ClipTiming(0, k * 10_000_000L, m.durationUs - 20_000_000)) }
        run("3 layers sharing ONE decoder, in-points 0/10/20 s", Aud2Bench.project(w, h, m.durationUs - 20_000_000, m.fps, shared), 10_000_000, 1_000_000L / m.fps, emptyList())
    }

    private fun run(label: String, project: com.fluxx.android.model.ProjectDocument, fromUs: Long, frameUs: Long, beats: List<Long>) {
        compose.runOnIdle { editor.dispatch(EditorAction.Load(project)); c.update(editor.state.project, fromUs, false) }
        Thread.sleep(2500)
        seekTo(fromUs, 1500)
        val before = Aud2Bench.meminfo(pkg)
        rec.clear()
        var during: Map<String, Long> = emptyMap()
        val t0 = System.nanoTime()
        val ui = uiFrames(compose.activity.window) {
            toggle(); Thread.sleep(10_000); during = Aud2Bench.meminfo(pkg); Thread.sleep(10_000)
        }
        val stillPlaying = playing
        val status = c.state.value.message
        if (stillPlaying) toggle()
        val frames = rec.frames.toList().filter { it[2] >= t0 }
        val firstSound = rec.firstSoundNs() ?: t0
        val steady = frames.filter { it[2] > firstSound + 500_000_000 }
        val intervals = steady.zipWithNext { a, b -> (b[2] - a[2]) / 1e6 }
        val skipped = steady.zipWithNext { a, b -> ((b[0] - a[0]) / frameUs - 1).coerceAtLeast(0) }.sum()
        val offsets = beatOffsets(beats.filter { it > fromUs + 1_000_000 && it < fromUs + 19_000_000 }, frameUs)
        val ev = rec.decoder.toList()
        val opens = ev.count { it.second.startsWith("open ") }; val fails = ev.filter { it.second.startsWith("open-failed") }
        val closes = ev.count { it.second.startsWith("close") }
        val perKey = ev.filter { it.second.startsWith("frame") }.groupingBy { it.second.split(' ')[1] }.eachCount()
        val seeks = ev.count { it.second.startsWith("frame") && !it.second.contains("seek=-1") }
        val slow = ev.filter { it.second.startsWith("frame") }.mapNotNull { Regex("us=(\\d+)").find(it.second)?.groupValues?.get(1)?.toDouble()?.div(1000) }
        report("A5 $label: preview frames ${frames.size} in 20 s (target ${20_000_000 / frameUs}); interval ${Aud2Bench.stats(intervals)} ms; skipped $skipped; A/V offset median %+.1f ms (${offsets.size} beats); underruns ${rec.underruns}; still playing at the end: $stillPlaying; status '${status ?: ""}'".format(Aud2Bench.pct(offsets, .5)))
        report("A5 $label: UI frames ${Aud2Bench.stats(ui)} ms; decoders opened $opens, closed $closes, open FAILURES ${fails.size}${fails.take(2).joinToString("") { " [${it.second}]" }}; flush+seeks $seeks; frameAt ${Aud2Bench.stats(slow)} ms; frames served per decoder ${perKey.values.joinToString("/")}")
        report("A5 $label memory: before ${Aud2Bench.mem(before)}; during ${Aud2Bench.mem(during)}")
        Thread.sleep(800)
    }
}
