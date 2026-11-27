package com.fluxx.android.render

import com.fluxx.android.engine.FrameCache
import com.fluxx.android.engine.ScrubFloor
import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** MP1 JVM tests: the scrub floor rule, used-range scope, cache accounting, culling predicates, the scheduler, export decoders. */
class Mp1Test {
    // ---- B1: the floor-frame rule -----------------------------------------------------------------------------------

    /** A synthetic stream: frame pts and sync flags; decoding is sequential from the last seek. */
    private class Stream(val pts: LongArray, val sync: BooleanArray, val cache: FrameCache<Long>) : ScrubFloor.Source {
        var next = 0                     // index of the next frame to decode
        override var outPos = -1L; private set
        override var ended = false; private set
        override fun prevSync(t: Long): Long { var i = pts.indexOfLast { it <= t }.coerceAtLeast(0); while (!sync[i]) i--; return pts[i] }
        override fun seek(sync: Long) { next = pts.indexOfFirst { it == sync }; ended = false; cache.breakRun(); outPos = sync - 1 }
        override fun decodeNext(): Boolean {
            if (next >= pts.size) { ended = true; cache.markEnded(); return false }
            val p = pts[next++]; cache.add(p, p); outPos = p
            if (next >= pts.size) { ended = true; cache.markEnded() }
            return true
        }
    }

    private fun stream(rnd: Random, frames: Int): Pair<LongArray, BooleanArray> {
        val frameUs = listOf(33_333L, 50_000L, 41_708L)[rnd.nextInt(3)]
        val pts = LongArray(frames) { it * frameUs + if (it > 0) rnd.nextLong(0, 40) else 0 }
        val gop = listOf(1, 8, 30, 90, 150)[rnd.nextInt(5)]
        val sync = BooleanArray(frames) { it == 0 || (it % gop == 0) || rnd.nextInt(60) == 0 }
        return pts to sync
    }

    @Test fun floorRuleNeverShowsAFrameLaterThanTheTarget() {
        val rnd = Random(7)
        repeat(60) { run ->
            val (pts, sync) = stream(rnd, 400)
            val cache = FrameCache<Long>(5) { }
            val s = Stream(pts, sync, cache)
            var t = pts[rnd.nextInt(pts.size)]
            repeat(300) {
                // A random walk: mostly small steps either way (a finger), sometimes a jump.
                t = (t + if (rnd.nextInt(10) == 0) rnd.nextLong(-5_000_000, 5_000_000) else rnd.nextLong(-200_000, 200_000)).coerceIn(0, pts.last() + 40_000)
                val (shown, exact) = ScrubFloor.frame(t, cache, s, 4)
                assertTrue("run $run: shown $shown later than target $t", shown <= t)
                // The floor rule: when it reports exact, no stream frame lies between the shown frame and the target.
                if (exact) assertEquals(pts.last { it <= t }, shown)
            }
        }
    }

    @Test fun floorPicksTheNearestCachedFrameAtOrBefore() {
        val c = FrameCache<String>(6) { }
        for (p in listOf(100L, 133L, 166L)) c.add(p, "a$p")
        c.breakRun(); for (p in listOf(400L, 433L)) c.add(p, "b$p")
        assertEquals(133L, c.floor(150)!!.first)
        assertEquals(166L, c.floor(399)!!.first)
        assertEquals(400L, c.floor(400)!!.first)
        assertNull(c.floor(99))
        // Exact floor: the next cached frame of the same run lies after the target.
        assertEquals("a133", c.floorExact(150))
        assertNull("166 ends its run: a later stream frame may exist before 399", c.floorExact(399))
        assertEquals("a frame exactly at the target is its own floor", "b433", c.floorExact(433))
    }

    // ---- B2: used-range scope --------------------------------------------------------------------------------------

    @Test fun usedRangeWindowWithMarginAndExtension() {
        val src = 3_018_000_000L
        val w = UsedRange.windowUs(sourceInUs = 1_500_000_000, durationUs = 5_000_000, sourceDurationUs = src)
        assertEquals(1_490_000_000L, w.first); assertEquals(1_515_000_000L, w.last)
        assertEquals(0L, UsedRange.windowUs(2_000_000, 5_000_000, src).first)
        assertEquals(src, UsedRange.windowUs(src - 1_000_000, 1_000_000, src).last)
        // Two clips of one source: merged when they overlap, kept apart otherwise (no whole-file hull).
        val merged = UsedRange.merge(listOf(0L..15_000_000L, 10_000_000L..30_000_000L, 2_900_000_000L..2_925_000_000L))
        assertEquals(listOf(0L..30_000_000L, 2_900_000_000L..2_925_000_000L), merged)
        // Widening the trim / moving the clip: the window follows; a demand (playhead) outside it extends it.
        assertTrue(UsedRange.covers(merged, 5_000_000L..6_000_000L))
        assertFalse(UsedRange.covers(merged, 40_000_000L..41_000_000L))
        val ext = UsedRange.extend(merged, 40_000_000L, 3_000_000L)
        assertTrue(UsedRange.covers(ext, 40_000_000L..43_000_000L))
        assertEquals(3, ext.size)
    }

    @Test fun pcmTargetsOnlyInsideTheScope() {
        val chunks = 3018
        val cov = ChunkCoverage(chunks)
        val scope = listOf(UsedRange.framesOf(1_490_000_000L..1_515_000_000L, chunks.toLong() * PcmCachePolicy.CHUNK_FRAMES))
        val first = PcmScope.nextTarget(cov, scope)
        assertEquals(1490L * PcmCachePolicy.CHUNK_FRAMES, first)
        cov.advance(first, 1516L * PcmCachePolicy.CHUNK_FRAMES, chunks.toLong() * PcmCachePolicy.CHUNK_FRAMES)
        assertEquals("scope covered: nothing more to decode (no whole-file decode)", -1L, PcmScope.nextTarget(cov, scope))
        // The run stops at the end of the scope's last chunk unless a reader needs more.
        assertTrue(PcmScope.runMayContinue(1510L * 48_000, scope, demandFrame = -1))
        assertFalse(PcmScope.runMayContinue(1516L * 48_000, scope, demandFrame = -1))
        assertTrue(PcmScope.runMayContinue(1516L * 48_000, scope, demandFrame = 1517L * 48_000))
        // MP1 device finding: a scope ending exactly at a chunk start (0..15 s) must let the run complete that chunk
        // (stopping at its first frame left it undecoded and the worker restarted there forever).
        val atChunk = listOf(0L..15L * 48_000)
        val cov2 = ChunkCoverage(3018)
        cov2.advance(0, 15L * 48_000, 3018L * 48_000)
        assertEquals(15L * 48_000, PcmScope.nextTarget(cov2, atChunk))
        assertTrue(PcmScope.runMayContinue(15L * 48_000 + 4096, atChunk, -1))
        assertFalse(PcmScope.runMayContinue(16L * 48_000, atChunk, -1))
        cov2.advance(15L * 48_000, 16L * 48_000, 3018L * 48_000)
        assertEquals(-1L, PcmScope.nextTarget(cov2, atChunk))
    }

    @Test fun cacheAccountingForASourceLargerThanTheCap() {
        val mb = 1024L * 1024
        // name, ALLOCATED bytes (sparse files: only decoded chunks), last used.
        val files = listOf(Triple("long.pcm", 5 * mb, 30L), Triple("a.pcm", 30 * mb, 10L), Triple("b.pcm", 25 * mb, 20L))
        assertEquals("580 MB logical but 5 MB on disk: nothing to evict", emptyList<String>(), PcmCachePolicy.evict(files, setOf("long.pcm")))
        val big = listOf(Triple("long.pcm", 250 * mb, 30L), Triple("a.pcm", 30 * mb, 10L), Triple("b.pcm", 25 * mb, 20L))
        assertEquals(listOf("a.pcm", "b.pcm"), PcmCachePolicy.evict(big, setOf("long.pcm")))
        // Background filling stops before one source alone passes the cap (partial coverage); demand still decodes.
        assertTrue(PcmScope.backgroundAllowed(100 * mb))
        assertFalse(PcmScope.backgroundAllowed(PcmCachePolicy.CAP_BYTES))
    }

    @Test fun peaksStoreOnlyTheUsedRangeAndExtend() {
        // 20 s at 48 kHz with only 5..8 s decoded (a trimmed clip's window), then widened by 8..10 s.
        val rate = 48_000
        val b = PeakBuilder(rate)
        for (i in 5L * rate until 8L * rate) b.addAt(i, if (i % 100 == 0L) .5f else .1f)
        val p = b.build(listOf(5_000_000L..8_000_000L))
        val f = java.io.File.createTempFile("mp1", ".peaks"); try {
            p.write(f)
            val back = PeakPyramid.read(f)!!
            assertEquals(listOf(5_000_000L..8_000_000L), back.coveredUs)
            assertEquals(p.levels.size, back.levels.size)
            for (l in p.levels.indices) { assertArrayEquals(p.levels[l].first, back.levels[l].first, 0f); assertArrayEquals(p.levels[l].second, back.levels[l].second, 0f) }
            // The file holds the covered buckets only (~3 s of 256-sample buckets), not the source from 0.
            assertTrue("file ${f.length()} bytes", f.length() < 5_000)
            // Widening: seeded from the stored level 0, only 8..10 s is new.
            val w = PeakBuilder(rate); w.seed(back)
            for (i in 8L * rate until 10L * rate) w.addAt(i, .2f)
            val p2 = w.build(UsedRange.merge(back.coveredUs + listOf(8_000_001L..10_000_000L)))
            assertEquals(listOf(5_000_000L..10_000_000L), p2.coveredUs)
            assertEquals(.5f, p2.levels[0].second[(5L * rate / PeakPyramid.BASE).toInt()], 0f)
            assertEquals(.2f, p2.levels[0].second[(9L * rate / PeakPyramid.BASE).toInt()], 0f)
        } finally { f.delete() }
    }

    // ---- B3: culling predicates ------------------------------------------------------------------------------------

    private fun videoMatrix(x: Float, y: Float, sx: Float, sy: Float, rot: Float = 0f) = FloatArray(16).also {
        LayerGeometry.matrix(it, x, y, sx, sy, rot, 1920, 1080, 1920, 1080) }

    @Test fun cullingBoundsFromTheRendererMatrix() {
        assertFalse(Culling.quadOutside(videoMatrix(0f, 0f, 1f, 1f), 0f, 0f))
        assertTrue(Culling.quadOutside(videoMatrix(3f, 0f, 1f, 1f), 0f, 0f))
        assertTrue(Culling.quadOutside(videoMatrix(0f, -2.6f, .5f, .5f), 0f, 0f))
        // Exactly touching the frame edge is NOT culled (conservative), partly outside is drawn.
        assertFalse(Culling.quadOutside(videoMatrix(2f, 0f, 1f, 1f), 0f, 0f))
        assertFalse(Culling.quadOutside(videoMatrix(1.5f, 0f, 1f, 1f), 0f, 0f))
        // Rotation 90 degrees keeps a centred layer visible.
        assertFalse(Culling.quadOutside(videoMatrix(0f, 0f, 1f, 1f, 90f), 0f, 0f))
        // Padding (a stroke, an effect) keeps a layer just outside the frame.
        assertTrue(Culling.quadOutside(videoMatrix(2.05f, 0f, 1f, 1f), 0f, 0f))
        assertFalse(Culling.quadOutside(videoMatrix(2.05f, 0f, 1f, 1f), .1f, .1f))
        // Zero area: one axis or both.
        assertTrue(Culling.zeroArea(videoMatrix(0f, 0f, 0f, 1f)))
        assertTrue(Culling.zeroArea(videoMatrix(0f, 0f, 0f, 0f)))
        assertFalse(Culling.zeroArea(videoMatrix(0f, 0f, .01f, 1f)))
    }

    @Test fun cullDecisionOpacityTextAndDependencies() {
        val m = videoMatrix(3f, 0f, 1f, 1f)
        assertEquals(Culling.Decision.CULLED, Culling.decide(opacity = 0f, matrix = videoMatrix(0f, 0f, 1f, 1f), positionProvable = true, areaProvable = true, padX = 0f, padY = 0f, needed = false))
        assertEquals(Culling.Decision.CULLED, Culling.decide(1f, m, true, true, 0f, 0f, false))
        // Text with animators: bounds cannot be proven, so no position cull (opacity / zero area still apply).
        assertEquals(Culling.Decision.DRAW, Culling.decide(1f, m, positionProvable = false, areaProvable = true, padX = 0f, padY = 0f, needed = false))
        // A stroke adds area: no zero-area cull.
        assertEquals(Culling.Decision.DRAW, Culling.decide(1f, videoMatrix(0f, 0f, 0f, 1f), true, areaProvable = false, padX = 0f, padY = 0f, needed = false))
        // A layer another layer needs (RenderDependencies) is never culled.
        assertEquals(Culling.Decision.DRAW, Culling.decide(1f, m, true, true, 0f, 0f, needed = true))
        assertEquals(emptySet<Long>(), RenderDependencies.needed(ProjectDocument()))
    }

    @Test fun warmKeepLooksAheadHalfASecond() {
        // Visible from 1.2 s on: culled at 0.5 s (cold), warm from 0.7 s (within 0.5 s of entry).
        val visibleFrom = 1_200_000L
        fun visibleAt(t: Long) = t >= visibleFrom
        assertFalse(Culling.visibleSoon(500_000, 33_333) { visibleAt(it) })
        assertTrue(Culling.visibleSoon(710_000, 33_333) { visibleAt(it) })
        assertTrue(Culling.visibleSoon(1_199_000, 33_333) { visibleAt(it) })
    }

    // ---- B5: the media work scheduler ------------------------------------------------------------------------------

    @Test fun schedulerPausesBackgroundWhilePlayingOrScrubbing() {
        val ms = 1_000_000L
        assertTrue(MediaWorkPolicy.backgroundMayRun(playing = false, scrubbing = false, lastForegroundNs = 0, nowNs = 1000 * ms))
        assertFalse(MediaWorkPolicy.backgroundMayRun(playing = true, scrubbing = false, lastForegroundNs = 0, nowNs = 1000 * ms))
        assertFalse(MediaWorkPolicy.backgroundMayRun(false, scrubbing = true, lastForegroundNs = 0, nowNs = 1000 * ms))
        // Resumes after 300 ms of idle.
        assertFalse(MediaWorkPolicy.backgroundMayRun(false, false, lastForegroundNs = 1000 * ms, nowNs = 1299 * ms))
        assertTrue(MediaWorkPolicy.backgroundMayRun(false, false, lastForegroundNs = 1000 * ms, nowNs = 1300 * ms))
        assertTrue(MediaWorkPolicy.Priority.PLAYBACK < MediaWorkPolicy.Priority.SCRUB)
        assertTrue(MediaWorkPolicy.Priority.SCRUB < MediaWorkPolicy.Priority.BACKGROUND)
        // Jobs outside the used range are cancelled; inside (or touching the margin) they stay.
        val scope = listOf(10_000_000L..30_000_000L)
        assertTrue(MediaWorkPolicy.cancel(40_000_000L..41_000_000L, scope))
        assertFalse(MediaWorkPolicy.cancel(29_000_000L..31_000_000L, scope))
        // The PCM worker: urgent while it is within the lead of what a reader needs, background beyond it.
        assertTrue(MediaWorkPolicy.urgent(runAt = 100, demandFrame = 50, leadFrames = 144_000))
        assertFalse(MediaWorkPolicy.urgent(runAt = 200_000, demandFrame = 50, leadFrames = 144_000))
        assertFalse(MediaWorkPolicy.urgent(runAt = 100, demandFrame = -1, leadFrames = 144_000))
    }

    // ---- A0: export never leaves a layer out -----------------------------------------------------------------------

    @Test fun exportRotatesDecodersInsteadOfLeavingLayersOut() {
        // Six keys drawn bottom to top each frame through four slots: every layer gets its decoder every frame. The decoder
        // closed is the one whose next use is farthest (Belady), so steady state reopens ~N - S = 2 per frame, never all 6.
        val keys = (1..6).map { "k$it" }
        val pool = LinkedHashSet<String>(); var opens = 0
        repeat(30) { frame ->
            val drawn = ArrayList<String>()
            var frameOpens = 0
            for (k in keys) {
                if (k !in pool) {
                    if (pool.size >= 4) pool.remove(DecoderKeys.exportVictim(pool.toList(), drawn, keys))
                    pool += k; frameOpens++
                }
                assertTrue(pool.size <= 4)
                drawn += k
            }
            assertEquals(keys, drawn)
            if (frame > 0) assertTrue("frame $frame reopened $frameOpens", frameOpens <= 3)
            opens += frameOpens
        }
        assertTrue("average reopens per frame ${(opens - 6) / 29.0}", (opens - 6) / 29.0 <= 2.5)
        // The victim is never a decoder this frame still needs when an already drawn one can go.
        assertEquals("k2", DecoderKeys.exportVictim(listOf("k1", "k2", "k5", "k6"), listOf("k1", "k2"), keys))
        // Export assigns every layer a key (no cap at assignment).
        val layers = keys.mapIndexed { i, k -> Triple(i.toLong() + 1, k, 0L) }
        assertTrue(DecoderKeys.assign(layers, Int.MAX_VALUE).values.none { it == null })
    }
}
