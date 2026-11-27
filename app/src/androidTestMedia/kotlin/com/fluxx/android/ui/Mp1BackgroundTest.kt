package com.fluxx.android.ui

import com.fluxx.android.media.AudioPeakProvider
import com.fluxx.android.render.SourcePcmCache
import org.junit.Test

/**
 * MP1 A3 / B5: background media work against playback. The typical clip (warm) plays 15 s from the middle: once alone, and
 * once while a COLD PCM-cache decode and a peak decode run for a never-seen source (the VP9 file under a fresh URI
 * spelling, so its caches are cold; its whole length is the "used range"). Preview and UI frame pacing p50 / p95 / p99,
 * the background job's progress, and the playback's first sound. The background work is stopped at the end.
 */
class Mp1BackgroundTest : Aud2Base() {
    override val tag = "MP1"
    private val probe = Mp1Probe()

    @Test fun backgroundVsPlayback() {
        val f = file("typical H.264", Aud2Bench.TYPICAL); val m = Aud1Bench.media(f)
        val v = file("VP9 worst-case", Aud2Bench.VP9); val vm = Aud1Bench.media(v)
        rec.start(); probe.start()
        host(Aud2Bench.project(1920, 1080, m.durationUs, m.fps, listOf(Aud2Bench.layer(1, f, m))))
        warmPcm(android.net.Uri.fromFile(f).toString(), m.durationUs)
        val warm = play("warm source only", m)
        // A never-seen source: a fresh URI spelling each run (its PCM / peak cache keys are new).
        val cold = Aud2Bench.distinctUri(v, 40 + (System.currentTimeMillis() / 1000 % 50).toInt())
        val pcm = SourcePcmCache.get(context).get(cold, vm.durationUs)
        try {
            // The whole source is the used range: the worker decodes it from 0 (before MP1 B2 it always went on to the end).
            pcm.ensure(0)
            compose.runOnIdle { AudioPeakProvider.get(context).request(cold) }
            Thread.sleep(300)
            val c0 = pcm.coveredChunks
            val busy = play("while a cold PCM + peak decode run", m)
            report("B5 background progress during the 15 s: PCM ${pcm.coveredChunks - c0} s decoded (decoding now: ${pcm.decoding}); peaks ${compose.runOnIdle { AudioPeakProvider.get(context).states[cold]?.javaClass?.simpleName }}")
            fun p(x: List<Double>, q: Double) = Aud2Bench.pct(x, q)
            report("B5 summary: preview interval p95 warm %.1f vs background %.1f ms (%+.0f %%); UI p95 warm %.1f vs background %.1f ms".format(
                p(warm.first, .95), p(busy.first, .95), (p(busy.first, .95) / p(warm.first, .95) - 1) * 100, p(warm.second, .95), p(busy.second, .95)))
        } finally {
            SourcePcmCache.get(context).release(cold)
            compose.runOnIdle { AudioPeakProvider.get(context).cancel(cold) }
            probe.stop()
        }
    }

    /** Plays 15 s from the middle; (preview intervals, UI frame durations). Also reports the first sound. */
    private fun play(label: String, m: Aud1Bench.Media): Pair<List<Double>, List<Double>> {
        seekTo(m.durationUs / 2, 1500)
        rec.clear()
        val t0 = System.nanoTime()
        val ui = uiFrames(compose.activity.window) { toggle(); Thread.sleep(15_000); if (playing) toggle() }
        val frames = rec.frames.toList().filter { it[2] >= t0 }
        val iv = frames.drop(5).zipWithNext { a, b -> (b[2] - a[2]) / 1e6 }
        val sound = rec.firstSoundNs()?.let { (it - t0) / 1e6 } ?: Double.NaN
        report("A3 background, $label: preview ${frames.size} frames, interval ${Aud2Bench.stats(iv)} ms; UI ${Aud2Bench.stats(ui)} ms; first sound %.0f ms after Play; underruns ${rec.underruns}".format(sound))
        Thread.sleep(800)
        return iv to ui
    }
}
