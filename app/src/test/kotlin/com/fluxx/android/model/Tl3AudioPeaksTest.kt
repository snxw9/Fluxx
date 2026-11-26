package com.fluxx.android.model

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** TL3 B5: peaks from synthetic PCM, the cache key and its LRU cap, and the mixer's time mapping. */
class Tl3AudioPeaksTest {
    @get:Rule val tmp = TemporaryFolder()

    /** 48 kHz: silence, then one bucket-aligned burst of +0.5 / -0.25 at samples 1024..1279, then silence. */
    private fun pyramid(): PeakPyramid {
        val b = PeakBuilder(48_000)
        for (i in 0 until 48_000) b.add(if (i in 1024 until 1280) (if (i % 2 == 0) .5f else -.25f) else 0f)
        return b.build()
    }

    @Test fun knownPeaksAtEveryLevel() {
        val p = pyramid()
        assertEquals(1_000_000L, p.durationUs)
        val (mn0, mx0) = p.levels[0]
        assertEquals(48_000 / PeakPyramid.BASE + 1, mn0.size) // 187.5 buckets: the last partial one is kept
        assertEquals(.5f, mx0[4]); assertEquals(-.25f, mn0[4])
        assertEquals(0f, mx0[3]); assertEquals(0f, mx0[5])
        // Level 1 merges 4: the burst is in bucket 1 (level-0 buckets 4..7); level 2 bucket 0.
        assertEquals(.5f, p.levels[1].second[1]); assertEquals(0f, p.levels[1].second[0])
        assertEquals(.5f, p.levels[2].second[0]); assertEquals(-.25f, p.levels[2].first[0])
        assertEquals(1, p.levels.last().first.size)
        // Level choice: one pixel column covers at least one bucket.
        assertEquals(0, p.levelFor(p.bucketUs(0)))
        assertEquals(1, p.levelFor(p.bucketUs(1) * 1.5))
        assertEquals(4, p.bucketAt(0, (p.bucketUs(0) * 4.5).toLong()))
        assertEquals(-1, p.bucketAt(0, 2_000_000))
    }

    @Test fun peaksRoundTripTheCacheFileAndTheKeyChangesWithTheSource() {
        val p = pyramid()
        val f = tmp.newFile("a.peaks"); p.write(f)
        val back = PeakPyramid.read(f)!!
        assertEquals(p.levels.size, back.levels.size)
        assertArrayEquals(p.levels[0].second, back.levels[0].second, 0f)
        val k = PeakCachePolicy.fileName("content://media/1", 1000, 5)
        assertEquals(k, PeakCachePolicy.fileName("content://media/1", 1000, 5))
        assertNotEquals(k, PeakCachePolicy.fileName("content://media/2", 1000, 5))
        assertNotEquals(k, PeakCachePolicy.fileName("content://media/1", 1001, 5)) // size
        assertNotEquals(k, PeakCachePolicy.fileName("content://media/1", 1000, 6)) // lastModified
        assertTrue(k.endsWith("-v${PeakPyramid.FORMAT_VERSION}.peaks"))
        // LRU: the least recently used go first until the rest fit.
        val files = listOf(Triple("old", 40L, 1L), Triple("mid", 40L, 2L), Triple("new", 40L, 3L))
        assertEquals(listOf("old", "mid"), PeakCachePolicy.evict(files, cap = 50))
        assertEquals(emptyList<String>(), PeakCachePolicy.evict(files, cap = 120))
    }

    @Test fun theWaveformUsesTheMixersTimeMappingWithTheInPoint() {
        val layer = CompositionLayer(1, LayerType.VIDEO, timing = ClipTiming(startUs = 2_000_000, sourceInUs = 500_000, durationUs = 1_000_000),
            asset = AssetReference("a", "content://video/1", durationUs = 10_000_000))
        assertNull(WaveformMapping.sourceUs(layer, 1_999_999))
        assertEquals(500_000L, WaveformMapping.sourceUs(layer, 2_000_000))
        assertEquals(1_250_000L, WaveformMapping.sourceUs(layer, 2_750_000))
        assertNull(WaveformMapping.sourceUs(layer, 3_000_000)) // end exclusive, like the mixer
        assertFalse(WaveformMapping.dimmed(layer))
        // TL3.1 B6: the upper-half envelope.
        assertEquals(.5f, WaveformMapping.envelope(-.5f, .25f)); assertEquals(.75f, WaveformMapping.envelope(-.1f, .75f)); assertEquals(1f, WaveformMapping.envelope(-3f, 0f))
        assertTrue(WaveformMapping.dimmed(layer.copy(muted = true))); assertTrue(WaveformMapping.dimmed(layer.copy(audioGain = 0f)))
    }

    @Test fun tl31SamplesArePlacedByPresentationTimeNotByCount() {
        val b = PeakBuilder(48_000)
        // A decoder whose first output starts 20 ms in (pts 20 000 us = sample 960): a burst at 100 ms of SOURCE time
        // must land in the bucket of 100 ms, not 80 ms.
        for (i in 0 until 4800) b.addAt(960L + i, 0f)                   // 20..120 ms silence
        for (i in 0 until 256) b.addAt(4800L + i, if (i % 2 == 0) .8f else -.8f) // the burst at 100 ms
        b.addAt(-5, 1f)                                                   // priming before 0: dropped
        val p = b.build()
        val bucket = (100_000.0 / p.bucketUs(0)).toInt()
        assertEquals(.8f, p.levels[0].second[bucket], 0f)
        assertEquals(0f, p.levels[0].second[0], 0f)                      // before the first output: silent, not the dropped priming
        assertEquals(0f, p.levels[0].second[bucket - 1], 0f)
        assertEquals(PeakPyramid.FORMAT_VERSION, 2)
    }
}
