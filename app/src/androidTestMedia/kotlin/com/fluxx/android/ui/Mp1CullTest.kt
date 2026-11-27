package com.fluxx.android.ui

import com.fluxx.android.editor.EditorAction
import com.fluxx.android.model.*
import org.junit.Test

/**
 * MP1 A3 / B3: culling. Four video layers of the typical clip (separate decoders) tiled 2x2 and visible, versus the same
 * four moved fully outside the frame; 10 s of playback from the middle each: preview pacing, frames, decoder requests and
 * decode time. B3 adds a layer that animates out of the frame for 1 s and back in during playback: the first frames after
 * it re-enters (no frame later than 2 frame intervals). Numbers only.
 */
class Mp1CullTest : Aud2Base() {
    override val tag = "MP1"

    @Test fun cullOutsideVsVisible() {
        val f = file("typical H.264", Aud2Bench.TYPICAL); val m = Aud1Bench.media(f)
        rec.start()
        host(Aud2Bench.project(1920, 1080, m.durationUs, m.fps, listOf(Aud2Bench.layer(1, f, m))))
        for (k in 0 until 4) warmPcm(Aud2Bench.distinctUri(f, k), m.durationUs)
        val tiles = listOf(-.5f to -.5f, .5f to -.5f, -.5f to .5f, .5f to .5f)
        fun four(outside: Boolean) = tiles.mapIndexed { k, (x, y) ->
            Mp1Bench.placed(1L + k, f, m, Aud2Bench.distinctUri(f, k), 10 + k, ClipTiming(0, 0, m.durationUs), if (outside) x + 4f else x, y, .5f) }
        run("4 visible (2x2)", four(false), m)
        run("4 fully outside the frame", four(true), m)
        // B3 re-entry: one visible layer plus one that leaves the frame for 1 s and comes back (position keys).
        val rate = FrameRate(m.fps, 1); val mid = m.durationUs / 2
        val anchor = 0L
        fun key(t: Long, x: Float) = Keyframe2D(t - anchor, x, 0f, EasingPreset.HOLD)
        val moving = Mp1Bench.placed(2, f, m, Aud2Bench.distinctUri(f, 1), 11, ClipTiming(0, 0, m.durationUs), .5f, 0f, .5f).let { l ->
            l.copy(animTransform = l.animTransform.copy(position = AnimatableProperty2D(true, keyframes = FrozenList.of(listOf(
                key(mid + 3_000_000, .5f), key(mid + 4_000_000, 5f), key(mid + 5_000_000, .5f))))))
        }
        val still = Mp1Bench.placed(1, f, m, Aud2Bench.distinctUri(f, 0), 10, ClipTiming(0, 0, m.durationUs), -.5f, 0f, .5f)
        val frames = run("re-entry: 1 still + 1 out for 1 s at +4 s", listOf(still, moving), m)
        val entry = rate.frameTimeUs(rate.nearestFrame(mid + 5_000_000))
        val after = frames.filter { it[0] >= entry - 200_000 && it[0] <= entry + 400_000 }
        val iv = after.zipWithNext { a, b -> (b[2] - a[2]) / 1e6 }
        report("B3 re-entry (layer back in frame at +5 s): intervals around the entry ${iv.joinToString(" ") { "%.0f".format(it) }} ms; max %.0f ms; frames later than 2 intervals ${iv.count { it > 2 * 1000.0 / m.fps }}".format(iv.maxOrNull() ?: Double.NaN))
    }

    private fun run(label: String, layers: List<CompositionLayer>, m: Aud1Bench.Media): List<LongArray> {
        val project = Aud2Bench.project(1920, 1080, m.durationUs, m.fps, layers)
        val from = m.durationUs / 2
        compose.runOnIdle { editor.dispatch(EditorAction.Load(project)); c.update(editor.state.project, from, false) }
        Thread.sleep(2500); seekTo(from, 1500)
        rec.clear()
        val t0 = System.nanoTime()
        toggle(); Thread.sleep(10_000); if (playing) toggle()
        val frames = rec.frames.toList().filter { it[2] >= t0 }
        val steady = frames.drop(5)
        val iv = steady.zipWithNext { a, b -> (b[2] - a[2]) / 1e6 }
        val ev = rec.decoder.toList().filter { it.first >= t0 && it.second.startsWith("frame ") }
        val us = ev.mapNotNull { Mp1Bench.field(it.second, "us")?.toDoubleOrNull()?.div(1000) }
        val opens = rec.decoder.count { it.first >= t0 && it.second.startsWith("open ") }; val closes = rec.decoder.count { it.first >= t0 && it.second.startsWith("close") }
        val render = steady.map { (it[2] - it[1]) / 1e6 }
        report("A3 cull $label: ${frames.size} frames in 10 s (target ${10 * m.fps}); interval ${Aud2Bench.stats(iv)} ms; render ${Aud2Bench.stats(render)} ms; decoder requests ${ev.size} (frameAt ${Aud2Bench.stats(us)} ms, total %.1f s); opened $opens closed $closes; underruns ${rec.underruns}".format(us.sum() / 1000))
        Thread.sleep(800)
        return frames
    }
}
