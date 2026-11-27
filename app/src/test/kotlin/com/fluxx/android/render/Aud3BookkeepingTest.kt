package com.fluxx.android.render

import org.junit.Assert.*
import org.junit.Test

/**
 * AUD3: where decoded audio is written. HE-AAC declared at 24 kHz in MKV decodes to 48 kHz in 2048-frame buffers
 * (42.667 ms), but the container stamps them in whole milliseconds (0, 42, 85, 128, 170, 213 ...). Before AUD3 the 48 kHz
 * pass-through wrote each buffer at its own stamp: 2016 / 2064 frames apart, a gap or overlap every 43 ms.
 */
class Aud3BookkeepingTest {
    /** The stamps the probe saw: k x 1024 samples at 24 kHz, in whole ms (floor), then the app's own priming and frame maths. */
    private fun stampsUs(buffers: Int) = (0 until buffers).map { k -> (k * 1024L * 1_000_000L / 24_000) / 1000 * 1000 }
    private val primingUs = AudioTiming.us(AudioTiming.primingSamples("audio/mp4a-latm", null, 0), 48_000)

    private fun placements(rate: Int, frames: Int, stamps: List<Long>): List<Pair<Long, Int>> {
        val rs = Resampler(rate); val out = ArrayList<Pair<Long, Int>>()
        val buf = FloatArray(frames * 2)
        for (ts in stamps) rs.run(buf, frames, AudioTiming.outputFrame(ts, primingUs)) { first, _, count -> out += first to count }
        return out
    }

    @Test fun heAac24kInMkvIsWrittenContiguously() {
        val p = placements(48_000, 2048, stampsUs(200))
        // Every buffer starts exactly where the previous ended (no gap, no overlap), from the first stamp.
        for ((a, b) in p.zipWithNext()) assertEquals("gap/overlap after frame ${a.first}", a.first + a.second, b.first)
        assertEquals(AudioTiming.outputFrame(0, primingUs), p.first().first)
        // And it never drifts from the true timeline by more than a millisecond's rounding.
        for ((k, pl) in p.withIndex()) assertTrue(kotlin.math.abs(pl.first - (k * 2048L + p.first().first)) == 0L)
    }

    @Test fun theStampsReallyWereOffByUpTo32Frames() {
        // What the old rule did: each buffer at its own stamp.
        val starts = stampsUs(200).map { AudioTiming.outputFrame(it, primingUs) }
        val steps = starts.zipWithNext { a, b -> b - a }.toSet()
        assertTrue("steps $steps", steps.any { it != 2048L })
    }

    @Test fun aRealJumpStillResetsThePosition() {
        // A discontinuity over 100 ms (a seek inside one run, an edit list) is honoured, not smeared.
        val rs = Resampler(48_000); val out = ArrayList<Long>()
        val buf = FloatArray(2048 * 2)
        for (f in listOf(0L, 2048L, 48_000L, 50_048L)) rs.run(buf, 2048, f) { first, _, _ -> out += first }
        assertEquals(listOf(0L, 2048L, 48_000L, 50_048L), out)
    }

    @Test fun exactStampsAreUnchanged() {
        // Opus (960 frames, exact 20 ms stamps) and MP4 sources: the same placement as before.
        val p = placements(48_000, 960, (0 until 100).map { it * 20_000L })
        for ((k, pl) in p.withIndex()) assertEquals(AudioTiming.outputFrame(k * 20_000L, primingUs), pl.first)
    }

    @Test fun anMp4EditListIsNotTrimmedTwice() {
        // The Seal MP4s and Project 1 clips 14 / 19 / 24: HE-AAC at 22.05 kHz, the edit list stamps the first sample at
        // -0.161 s (= 3553 samples) AND reports encoder-delay 3553. The container already did the trim: nothing more.
        assertEquals(0, AudioTiming.primingSamples("audio/mp4a-latm", 3553, -161_133))
        assertEquals(0, AudioTiming.primingSamples("audio/mp4a-latm", 2529, -114_694))
        // An encoder delay without a shift is still trimmed; AAC with nothing said keeps the standard priming.
        assertEquals(1024, AudioTiming.primingSamples("audio/mp4a-latm", 1024, 0))
        assertEquals(2048, AudioTiming.primingSamples("audio/mp4a-latm", null, 0))
    }

    @Test fun staleCachesAreRebuilt() {
        assertTrue(PcmCachePolicy.VERSION >= 3)
        assertTrue(PcmCachePolicy.stale("0123456789abcdef0123-10-20-v2.pcm"))
        assertTrue(PcmCachePolicy.stale("0123456789abcdef0123-10-20-v1.pcm"))
    }
}
