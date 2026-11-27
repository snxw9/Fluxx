package com.fluxx.android.render

import org.junit.Assert.*
import org.junit.Test

/** AUD1: the pure audio timing, cache policy, chunk coverage, streaming window and A/V clock. */
class Aud1Test {
    @Test fun primingIsTheEncoderDelayElseNothingWhenTheContainerShiftsItElseAacLcPriming() {
        assertEquals(1024, AudioTiming.primingSamples("audio/mp4a-latm", 1024, 0))
        assertEquals(0, AudioTiming.primingSamples("audio/mp4a-latm", null, -46_000))      // the container already shifts it before 0
        assertEquals(2048, AudioTiming.primingSamples("audio/mp4a-latm", null, 0))        // AAC with nothing said: the known priming
        assertEquals(0, AudioTiming.primingSamples("audio/opus", null, 0))
        assertEquals(46_439, AudioTiming.us(2048, 44_100))
        // A sample at pts = priming lands on output frame 0; earlier samples are before 0.
        assertEquals(0L, AudioTiming.outputFrame(46_439, 46_439))
        assertEquals(-48L, AudioTiming.outputFrame(46_439 - 1000, 46_439))
        assertEquals(48_000L, AudioTiming.outputFrame(1_046_439, 46_439))
    }

    @Test fun peaksAndPlaybackPlaceTheSameSampleAtTheSameTime() {
        // The peak decoder indexes at (pts - priming) x its rate; playback at outputFrame(pts, priming) at 48 kHz: the same time.
        val priming = AudioTiming.us(AudioTiming.primingSamples("audio/mp4a-latm", null, 0), 44_100)
        for (pts in listOf(46_440L, 500_000L, 61_000_000L, 121_999_000L)) {
            val peakSeconds = Math.round((pts - priming) * 44_100 / 1_000_000.0) / 44_100.0
            val playSeconds = AudioTiming.outputFrame(pts, priming) / 48_000.0
            assertEquals("pts $pts", peakSeconds, playSeconds, 1.0 / 44_100 + 1.0 / 48_000)
        }
    }

    @Test fun theCacheKeyChangesWithTheSourceAndVersionAndTheLruKeepsWhatIsInUse() {
        val k = PcmCachePolicy.fileName("file:///storage/x/a.mkv", 52_833_831, 1)
        assertEquals(k, PcmCachePolicy.fileName("file:///storage/x/a.mkv", 52_833_831, 1))
        assertNotEquals(k, PcmCachePolicy.fileName("file:///storage/x/a.mkv", 52_833_832, 1))
        assertNotEquals(k, PcmCachePolicy.fileName("file:///storage/x/a.mkv", 52_833_831, 2))
        assertTrue(k.endsWith("-v${PcmCachePolicy.VERSION}.pcm")); assertFalse(PcmCachePolicy.stale(k))
        assertTrue(PcmCachePolicy.stale("abc-1-2-v0.pcm")); assertTrue(PcmCachePolicy.stale("abc-1-2-v0.cov")); assertFalse(PcmCachePolicy.stale("notes.txt"))
        val files = listOf(Triple("old", 100L, 1L), Triple("inuse", 100L, 2L), Triple("new", 100L, 3L))
        assertEquals(listOf("old", "new"), PcmCachePolicy.evict(files, keep = setOf("inuse"), cap = 100))
        assertEquals(emptyList<String>(), PcmCachePolicy.evict(files, emptySet(), cap = 300))
        assertEquals(256L * 1024 * 1024, PcmCachePolicy.CAP_BYTES)
        // 16-bit stereo halves the old float scratch: 122 s = 23.4 MB.
        assertEquals(23_424_000L, PcmCachePolicy.bytes(AudioTiming.frames(122_000_000)))
    }

    @Test fun chunkCoverageFollowsDecodeRunsFromWhereverTheyStart() {
        val total = 5L * 48_000 + 1000
        val c = ChunkCoverage(PcmCachePolicy.chunks(total))
        assertEquals(6, c.chunks)
        // A run from the middle (chunk 3) that wrote 1.5 chunks covers chunk 3 only.
        assertEquals(1, c.advance(3L * 48_000, 3L * 48_000 + 72_000, total))
        assertTrue(c.covered(3)); assertFalse(c.covered(4)); assertEquals(0, c.firstGap(0)); assertEquals(4, c.firstGap(3))
        assertTrue(c.covers(3L * 48_000, 4L * 48_000)); assertFalse(c.covers(3L * 48_000, 4L * 48_000 + 1))
        // A run from 0 to the end: every chunk, the short last one included.
        c.advance(0, total, total)
        assertTrue(c.complete); assertEquals(-1, c.firstGap(0))
        // Saved and restored.
        assertTrue(ChunkCoverage(6, c.snapshot()).complete)
    }

    @Test fun theClockIsTheHeardAudioWithAHeadMinusLatencyFallback() {
        val start = 48_000L // started at 1 s
        // A timestamp: frame 9600 presented at t = 1 s; 50 ms later the heard position is 9600 + 2400.
        assertEquals(start + 12_000, AvClock.positionFrames(start, headFrames = 20_000, nowNs = 1_050_000_000, tsFrame = 9_600, tsNanoTime = 1_000_000_000, latencyFrames = 10_224))
        // Never ahead of the head (what has been played out).
        assertEquals(start + 11_000, AvClock.positionFrames(start, 11_000, 2_000_000_000, 9_600, 1_000_000_000, 10_224))
        // No timestamp yet: the head minus the output latency (213 ms), never before the start.
        assertEquals(start + (20_000 - 10_224), AvClock.positionFrames(start, 20_000, 0, null, null, 10_224))
        assertEquals(start, AvClock.positionFrames(start, 3_000, 0, null, null, 10_224))
        assertEquals(1_250_000L, AvClock.us(60_000))
    }

    @Test fun preparingAudioShowsOnlyAfterTheShortWait() {
        assertFalse(StreamingWindow.showStatus(200, ready = false))
        assertTrue(StreamingWindow.showStatus(301, ready = false))
        assertFalse(StreamingWindow.showStatus(5_000, ready = true))
        assertEquals(500_000L, StreamingWindow.READY_US); assertEquals(960, StreamingWindow.BLOCK_FRAMES)
    }

    @Test fun theStereoFoldAndShortConversionAreBulkAndExact() {
        val mono = shortArrayOf(16384, -16384)
        val out = FloatArray(4); AudioFold.stereo(mono, 2, 1, out)
        assertArrayEquals(floatArrayOf(.5f, .5f, -.5f, -.5f), out, 1e-6f)
        val shorts = ShortArray(4); AudioFold.toShorts(floatArrayOf(2f, -2f, .5f, 0f), 0, 2, shorts)
        assertArrayEquals(shortArrayOf(32767, -32767, 16383, 0), shorts)
        // The resampler: 44.1 kHz -> 48 kHz yields 48/44.1 as many frames, continuous across buffers.
        val r = Resampler(44_100); var produced = 0; var firsts = ArrayList<Long>()
        val src = FloatArray(441 * 2) { 0.25f }
        repeat(10) { produced += r.run(src, 441, it * 480L) { f, _, n -> firsts += f } }
        assertEquals(4800.0, produced.toDouble(), 2.0)
        assertEquals(0L, firsts.first())
        // 48 kHz passes through untouched.
        assertEquals(441, Resampler(48_000).run(src, 441, 0) { _, d, n -> assertSame(src, d) })
    }
}
