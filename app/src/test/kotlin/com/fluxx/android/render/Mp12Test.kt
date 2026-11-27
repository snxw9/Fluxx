package com.fluxx.android.render

import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test

/** MP1.2 JVM tests: decoders of clips that are over close at the cut while playing; the decoder memory model and budget. */
class Mp12Test {
    private fun clip(id: Long, uri: String, start: Long, dur: Long, visible: Boolean = true) = CompositionLayer(id, LayerType.VIDEO, timing = ClipTiming(start, 0, dur),
        visible = visible, asset = AssetReference("a$id", uri, MediaAccess.AVAILABLE, dur))
    /** 30 different files in sequence, 4 s each. */
    private val seq = (0 until 30).map { clip(it + 1L, "u$it", it * 4_000_000L, 4_000_000L) }
    private fun near(t: Long, layers: List<CompositionLayer> = seq) = HashSet<String>().also { DecoderRetention.nearUris(layers, t, Culling.WARM_LOOKAHEAD_US, it) }

    @Test fun playingClosesTheEndedClipsDecoderAtTheCut() {
        // At the cut to clip 6 (20 s) the pool still holds clips 3..5 (as before MP1.2): all three close now, before clip 6 opens.
        val held = listOf("u2", "u3", "u4")
        assertEquals(setOf("u5"), near(20_000_000))
        assertEquals(held, DecoderRetention.closeNow(held, near(20_000_000), playing = true))
        // Simulated playthrough: at most one decoder is ever open (the current clip's), at every cut.
        val pool = ArrayList<String>(); var peak = 0
        var t = 0L
        while (t < 120_000_000L) {
            DecoderRetention.closeNow(pool.toList(), near(t), playing = true).forEach { pool.remove(it) }
            val uri = seq.first { it.timing.isActive(t) }.asset!!.uri!!
            if (uri !in pool) pool += uri
            peak = maxOf(peak, pool.size); t += 50_000
        }
        assertEquals(1, peak)
    }

    @Test fun aClipAboutToStartOrStillActiveKeepsItsDecoder() {
        // The same file again 0.3 s after the cut (within the warm window) and a long clip under the cut stay open.
        val layers = listOf(clip(1, "a", 0, 4_000_000), clip(2, "b", 4_000_000, 4_000_000), clip(3, "a", 4_300_000, 2_000_000), clip(4, "c", 0, 60_000_000))
        val n = near(4_000_000, layers)
        assertEquals(setOf("a", "b", "c"), n)
        assertTrue(DecoderRetention.closeNow(listOf("a", "c"), n, playing = true).isEmpty())
        // A hidden layer's source is not near (the 3 s hidden release still applies to it).
        assertFalse("h" in near(0, listOf(clip(9, "h", 0, 4_000_000, visible = false))))
    }

    @Test fun pausedKeepsOnlyTheMostRecentIdleDecoder() {
        // LRU order, oldest first; paused at 20 s: u2, u3 close now, u4 (the clip just left) waits for the delayed release.
        val held = listOf("u2", "u3", "u4", "u5")
        assertEquals(listOf("u2", "u3"), DecoderRetention.closeNow(held, near(20_000_000), playing = false))
        assertEquals(listOf("u2", "u3", "u4"), DecoderRetention.closeNow(held, near(20_000_000), playing = false, trimmed = true))
        // Per-time keys of one source follow their URI.
        assertEquals(listOf("u1#1"), DecoderRetention.closeNow(listOf("u1#1", "u5", "u5#1"), near(20_000_000), playing = true))
    }

    @Test fun aCodecThatTakesInputButGivesNoPictureHoldsTheLastOne() {
        val hold = com.fluxx.android.engine.DecoderPlan::holdLastPicture
        // The VOGUE file: real frames to 16.70 s, then 8-byte samples every 50 ms the codec takes silently.
        assertTrue(hold(60, 16_750_000, 19_750_000, 17_000_000))
        // Not before the input has reached the request (keep feeding the codec first).
        assertFalse(hold(60, 16_750_000, 19_750_000, 20_000_000))
        // A normal deep pipeline (VP9 on the A16 holds ~31 frames, 1.5 s at 20 fps) never qualifies.
        assertFalse(hold(31, 0, 1_550_000, 0))
        // Many inputs but a short span (60 fps start-up) does not either.
        assertFalse(hold(60, 0, 1_000_000, 0))
    }

    @Test fun aTargetBeforeTheFirstFrameStillGetsThatFrame() {
        // Project 1 clip 17: the first video frame is at 0.105 s; the cut asks for 0.003 s. Before MP1.2 the seek to the
        // first sync frame left the decoder already past the target, nothing was decoded and the preview stopped.
        val pts = LongArray(20) { 105_000L + it * 50_000L }
        val cache = com.fluxx.android.engine.FrameCache<Long>(4) { }
        val src = object : com.fluxx.android.engine.ScrubFloor.Source {
            var next = 0
            override var outPos = -1L
            override var ended = false
            override fun prevSync(t: Long) = pts[0]
            override fun seek(sync: Long) { next = 0; cache.breakRun(); outPos = sync - 1 }
            override fun decodeNext(): Boolean { if (next >= pts.size) { ended = true; return false }; val p = pts[next++]; cache.add(p, p); outPos = p; return true }
        }
        val (shown, _) = com.fluxx.android.engine.ScrubFloor.frame(3_000, cache, src, 4)
        assertEquals(105_000L, shown)
    }

    @Test fun decoderMemoryModelAndBudget() {
        // 1920x1440 with its 3-frame cache: ~204 MB (the A16 measured ~250 MB of dmabuf); the old guess said 22 MB.
        val mb = DecoderKeys.decoderBytes(1920, 1440, 3) / 1048576.0
        assertTrue("$mb", mb in 190.0..260.0)
        // A16 (3.74 GB): ~730 MB budget: three 1440p decoders fit, a fourth does not; four 1080p ones fit (the AUD2 layer tests).
        val budget = DecoderKeys.memoryBudget(3_740_844L * 1024)
        assertTrue(3 * DecoderKeys.decoderBytes(1920, 1440, 3) <= budget)
        assertTrue(4 * DecoderKeys.decoderBytes(1920, 1440, 3) > budget)
        assertTrue(4 * DecoderKeys.decoderBytes(1920, 1080, 5) <= budget)
    }
}
