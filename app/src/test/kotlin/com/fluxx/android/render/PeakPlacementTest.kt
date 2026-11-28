package com.fluxx.android.render

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TLV1.1 C3: the waveform's samples sit at the frames playback uses. Peaks now run decoded audio through the PCM cache's own
 * [Resampler] (one per decode run), so a buffer whose stamp is a little off the contiguous position (an HE-AAC v2 MP4 with
 * an edit list: its second buffer stamped ~21 ms after where the first one ends) lands where the PCM cache writes it, not at
 * its stamp. A jump of more than 100 ms (a seek inside a run, an edit list) is still honoured.
 */
class PeakPlacementTest {
    private fun firsts(rate: Int, stampsUs: List<Long>, frames: Int, primingUs: Long = 0): List<Long> {
        val r = Resampler(rate); val out = ArrayList<Long>()
        val src = FloatArray(frames * 2)
        for (pts in stampsUs) r.run(src, frames, AudioTiming.outputFrame(pts, primingUs)) { first, _, _ -> out += first }
        return out
    }

    @Test fun aBufferStampedJustOffTheContiguousPositionIsPlacedContiguously() {
        // 44.1 kHz, 1100-frame first buffer at -161 ms (edit list), then 2048-frame buffers stamped by the input frames.
        val r = Resampler(44_100); val placed = ArrayList<Pair<Long, Int>>()
        val first = FloatArray(1100 * 2); val next = FloatArray(2048 * 2)
        r.run(first, 1100, AudioTiming.outputFrame(-161_000, 0)) { f, _, c -> placed += f to c }
        r.run(next, 2048, AudioTiming.outputFrame(-161_000 + 46_440, 0)) { f, _, c -> placed += f to c }
        val (f0, c0) = placed[0]; val (f1, _) = placed[1]
        assertEquals("the second buffer follows the first (not its stamp, ~21 ms later)", f0 + c0, f1)
        val stamp1 = AudioTiming.outputFrame(-161_000 + 46_440, 0)
        assertEquals("the stamp would have put it ~21 ms later", 21.4, (stamp1 - f1) * 1000.0 / AudioTiming.RATE, 0.6)
    }

    @Test fun at48kHzMillisecondStampsAreWrittenBackToBack() {
        // 2048-frame HE-AAC buffers at 48 kHz in MKV: stamped 0, 43, 85, 128 ms (42.667 ms each, rounded).
        val f = firsts(48_000, listOf(0, 43_000, 85_000, 128_000), 2048)
        assertEquals(listOf(0L, 2048L, 4096L, 6144L), f)
    }

    @Test fun aJumpOfMoreThan100msIsHonoured() {
        val f = firsts(48_000, listOf(0, 42_667, 1_000_000), 2048)
        assertEquals(48_000L, f[2])
    }
}
