package com.fluxx.android.model

import org.junit.Assert.*
import org.junit.Test

/** TLV1.1 C2: the progressive peak build's order, coverage and 48 kHz bookkeeping. */
class PeakChunksTest {
    private val c = PeakChunks.CHUNK_US

    @Test fun chunksStayInsideTheWantedRanges() {
        // A 10-minute used range from 20:00 plus the 10 s margin each side.
        val want = listOf((1_200_000_000L - 10_000_000)..(1_800_000_000L + 10_000_000))
        val chunks = PeakChunks.chunksOf(want)
        assertEquals((1_190_000_000L / c).toInt(), chunks.first())
        assertEquals((1_810_000_000L / c).toInt(), chunks.last())
        assertTrue("no chunk outside the range", chunks.all { k -> PeakChunks.rangeOf(k).let { it.last >= want[0].first && it.first <= want[0].last } })
        // Two disjoint ranges: nothing in between.
        val two = PeakChunks.chunksOf(listOf(0L until 5 * c, 50 * c until 52 * c + 1))
        assertEquals((0..4).toList() + listOf(50, 51, 52), two)
    }

    @Test fun theShownChunksComeFirstFromThePlayheadRightThenLeftThenOutward() {
        val all = (0 until 100).toList()
        val focus = (25 * c)..(31 * c + c / 2)  // chunks 25..31, centre in chunk 28
        val order = ArrayList<Int>(); val missing = all.toMutableSet(); var after: Int? = null
        while (true) { val k = PeakChunks.next(missing, focus, after) ?: break; order += k; missing -= k; after = k }
        assertEquals(listOf(28, 29, 30, 31, 27, 26, 25), order.take(7))
        // Every chunk is built exactly once, and all of them.
        assertEquals(all.toSet(), order.toSet()); assertEquals(order.size, order.distinct().size)
        // Without a focus: from the start, in order.
        assertEquals(0, PeakChunks.next(all, null, null))
    }

    @Test fun aMovedFocusReordersTheRemainingWork() {
        val missing = (0 until 1600).toMutableSet() // 50 minutes
        val focus = (750 * c)..(753 * c)             // 1500..1506 s; centre in chunk 751
        assertEquals(751, PeakChunks.next(missing, focus, after = 3))
        missing -= 751
        assertEquals(752, PeakChunks.next(missing, focus, after = 751))
    }

    @Test fun coverageIsTheBuiltChunksInsideTheWantedRanges() {
        val want = listOf((c / 2)..(11 * c))
        assertEquals(listOf((c / 2) until 3 * c), PeakChunks.covered(listOf(0, 1, 2), want))
        assertEquals(listOf((c / 2) until c, (2 * c) until 3 * c), PeakChunks.covered(listOf(0, 2), want))
        assertTrue(PeakChunks.covered(emptyList(), want).isEmpty())
    }

    @Test fun aProgressiveBuildPublishesWithoutChangingWhatItKeepsBuilding() {
        val b = PeakBuilder(48_000)
        for (i in 0 until 1024) b.addAt(i.toLong(), if (i < 512) .5f else -.25f)
        b.addAt(6000, .1f) // buckets 4..22 exist but are empty at the first publish
        val first = b.build(listOf(0L..10_000L))
        // A bucket with no sample yet reads 0 in the published copy, but keeps collecting afterwards.
        b.addAt(5000, .9f)
        val second = b.build(listOf(0L..200_000L))
        assertEquals(.5f, first.levels[0].second[0], 0f)
        assertEquals(.9f, second.levels[0].second[5000 / PeakPyramid.BASE], 0f)
        assertEquals("the empty bucket's min was not frozen at 0 by the first publish", .9f, second.levels[0].first[5000 / PeakPyramid.BASE], 0f)
        assertTrue(second.covers(150_000)); assertFalse(first.covers(150_000))
    }

    @Test fun theFormatVersionRetiresOlderPeaks() {
        assertEquals(5, PeakPyramid.FORMAT_VERSION)
        assertTrue(PeakCachePolicy.stale("ab-1-2-v4.peaks"))
        assertFalse(PeakCachePolicy.stale(PeakCachePolicy.fileName("content://x", 1, 2)))
        assertFalse(PeakCachePolicy.stale("ab-1-2-v5.peaks.tmp"))
    }
}
