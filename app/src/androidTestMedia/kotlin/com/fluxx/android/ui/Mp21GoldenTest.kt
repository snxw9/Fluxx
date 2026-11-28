package com.fluxx.android.ui

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.engine.RenderBridge
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import com.fluxx.android.render.CompositionRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * MP2.1 B2: the warm-up DIFFERENTIAL golden test (as [Mp1CullGoldenTest]). A deterministic sequence of cuts (one file at
 * non-contiguous in-points, alternating with the VP9 file when present, plus a layer that leaves the frame and comes
 * back) is rendered frame by frame, offscreen, by a renderer with warm-ups ON (playing) and one with them OFF, and the
 * readbacks must be BYTE-IDENTICAL for every frame: warm-up changes timing, never pixels. The ON renderer's counters
 * prove the warm decoders were really used (a pass is never vacuous).
 */
class Mp21GoldenTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun report(line: String) {
        android.util.Log.i("MP21", line)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("MP21", line) })
    }
    private val w = 640; private val h = 360

    @Test fun warmUpIsPixelIdentical() {
        TestDeviceState.record(javaClass.simpleName)
        val f = Aud2Bench.find(context, Aud2Bench.TYPICAL) ?: run { assumeTrue("MP21: typical clip not found (${Aud2Bench.lookupLog})", false); return }
        val m = Aud1Bench.media(f)
        val v = Aud2Bench.find(context, Aud2Bench.VP9)
        val clip = 1_000_000L
        val layers = ArrayList<CompositionLayer>()
        for (k in 0 until 12) {
            val vp9 = v != null && k % 3 == 2
            val file = if (vp9) v!! else f
            val media = if (vp9) Aud1Bench.media(v!!) else m
            layers += Aud2Bench.layer(1L + k, file, media, z = 10, timing = ClipTiming(k * clip, if (vp9) k * 3_900_000L else ((k * 13) % 29) * 2_000_000L, clip))
        }
        // A small layer that leaves the frame for 1.2 s (hold keys) and comes back: a re-entry warm-up.
        val moving = Mp1Bench.placed(50, f, m, Aud2Bench.distinctUri(f, 1), 20, ClipTiming(0, 0, 12 * clip), .5f, .5f, .3f).let { l ->
            l.copy(animTransform = l.animTransform.copy(position = AnimatableProperty2D(true, keyframes = FrozenList.of(listOf(
                Keyframe2D(0, .5f, .5f, EasingPreset.HOLD), Keyframe2D(4_000_000, 5f, .5f, EasingPreset.HOLD), Keyframe2D(5_200_000, .5f, .5f, EasingPreset.HOLD)))))) }
        val project = ProjectDocument(Composition(width = w, height = h, durationUs = 12 * clip, frameRate = FrameRate(30, 1), layers = FrozenList.of(layers + moving)))
        val rate = FrameRate(30, 1)
        val frames = (0 until 360).map { rate.frameTimeUs(it.toLong()) }
        val repo = MediaRepository(context)
        var compared = 0; var differ = 0
        CompositionRenderer(context, repo, warmUp = true).use { on ->
            CompositionRenderer(context, repo, warmUp = false).use { off ->
                on.playing = true
                for (t in frames) {
                    on.render(project, t); val a = read(on)
                    off.render(project, t); val b = read(off)
                    compared++
                    if (!(a.first == b.first && a.second == b.second && a.third == b.third)) { differ++; if (differ <= 5) report("MP21 golden: frame at ${t / 1000} ms DIFFERS") }
                    Thread.sleep(8) // a little time between frames for the warm thread (the order of events still varies)
                }
                val c = requireNotNull(on.warmCounts())
                report("MP21 golden: $compared frames compared (12 cuts incl. ${if (v != null) "4 to VP9" else "no VP9 (file missing)"}, 1 re-entry), $differ differ; warm-ups started ${c[0]}, ready ${c[1]}, used ${c[2]}, released unused ${c[3]}, failed ${c[4]}, blocked ${c[5] + c[6]}, frame waited ${c[7]}")
                assertTrue("the warm-up path was not exercised", c[2] > 0)
                assertEquals("warm-up changed pixels", 0, differ)
            }
        }
    }

    private fun read(r: CompositionRenderer): Triple<ByteBuffer, ByteBuffer, ByteBuffer> {
        val y = ByteBuffer.allocateDirect(w * h); val u = ByteBuffer.allocateDirect(w * h / 4); val v = ByteBuffer.allocateDirect(w * h / 4)
        check(RenderBridge.copyYuv(r.session, y, u, v, w, w / 2, w / 2, 1, 1)) { "readback failed" }
        return Triple(y, u, v)
    }
}
