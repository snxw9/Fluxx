package com.fluxx.android.render

import com.fluxx.android.engine.DecoderPlan
import com.fluxx.android.engine.DecoderPlan.Action
import com.fluxx.android.engine.FrameCache
import com.fluxx.android.ui.timeline.TimelineFollow
import org.junit.Assert.*
import org.junit.Test

/**
 * AUD2: the decoder's frame cache and seek rules (warm pause / resume, retarget, no forward re-seek spiral), the clock's
 * monotonic rule and fallback, the scrub policy and the timeline's follow mapping.
 */
class Aud2Test {
    private val f = 50_000L   // a 20 fps frame
    private fun cache(cap: Int, evicted: MutableList<String> = ArrayList()) = FrameCache<String>(cap) { evicted += it }

    @Test fun frameCacheIsBoundedAndEvictsTheFrameDecodedLongestAgo() {
        val evicted = ArrayList<String>()
        val c = cache(3, evicted)
        for (i in 0..4) c.add(i * f, "f$i")
        assertEquals(3, c.size)
        assertEquals(listOf("f0", "f1"), evicted)
        assertEquals(listOf(2 * f, 3 * f, 4 * f), c.ptsList())
        // After a seek back the new run's frames are kept and the OLDEST DECODED ones (not the earliest pts) go.
        c.breakRun(); c.add(0, "s0")
        assertEquals("f2", evicted.last())
        assertEquals(listOf(0L, 3 * f, 4 * f), c.ptsList())
        c.clear(); assertEquals(0, c.size)
        assertTrue(evicted.containsAll(listOf("s0", "f3", "f4")))
    }

    @Test fun cacheMemoryBoundFromTheFrameSize() {
        assertEquals(5, DecoderPlan.cacheFrames(1920, 1080))     // 3.1 MB each, 16 MB budget
        assertEquals(4, DecoderPlan.cacheFrames(1920, 1440))     // 4.1 MB each
        assertEquals(3, DecoderPlan.cacheFrames(3840, 2160))     // never below 3 (one frame back, current, look-ahead)
        assertEquals(6, DecoderPlan.cacheFrames(640, 360))       // never above 6
        assertTrue(DecoderPlan.cacheFrames(1920, 1080) * 1920L * 1080 * 3 / 2 <= DecoderPlan.CACHE_BYTES)
    }

    @Test fun anExactAnswerNeedsTwoConsecutiveFramesOfOneRun() {
        val c = cache(6)
        for (i in 10..13) c.add(i * f, "f$i")               // 0.50 .. 0.65 s, one run
        assertEquals("f11", c.exact(11 * f))                 // on a frame
        assertEquals("f11", c.exact(11 * f + f / 2))         // a tie takes the earlier frame (as Media3)
        assertEquals("f12", c.exact(11 * f + f / 2 + 1))
        assertNull(c.exact(13 * f + 1))                      // nothing after the last frame is known yet
        assertNull(c.exact(9 * f))                           // before the run
        // A frame from another run beside it does not prove adjacency.
        c.breakRun(); c.add(20 * f, "f20")
        assertNull(c.exact(15 * f))
        // The end of the stream proves nothing follows the last frame.
        c.add(21 * f, "f21"); c.markEnded()
        assertEquals("f21", c.exact(30 * f))
        // Nearest (approximate) ignores adjacency; the earlier on a tie.
        assertEquals(13 * f, c.nearest(16 * f)!!.first)
        assertEquals(20 * f, c.nearest(17 * f)!!.first)
    }

    @Test fun pauseAndResumeOneFrameBackAreServedFromMemory() {
        // Playback decoded up to 64.70 s (and its look-ahead); Pause re-renders 64.65 s (the UI's last position); the clock
        // at Resume steps back one frame: both are cache hits, so the decoder never flushes (the AUD2 A2 cause).
        val c = cache(5)
        for (t in listOf(64_550_000L, 64_600_000L, 64_650_000L, 64_700_000L, 64_750_000L)) c.add(t, "$t")
        val outPos = 64_750_000L
        for (t in listOf(64_650_000L, 64_700_000L, 64_600_000L)) {
            val hit = c.exact(t) != null
            assertTrue(hit)
            assertEquals(Action.CACHED, DecoderPlan.decide(t, hit, outPos, false) { error("no sync query on a hit") })
        }
    }

    @Test fun forwardJumpsSeekOnlyWhenTheirSyncFrameIsPastTheDecoder() {
        // The spiral: decoded to 64.75 s, the clock ran on to 65.35 s; its sync frame is 62.35 s (behind): decode on.
        assertEquals(Action.FORWARD, DecoderPlan.decide(65_350_000, false, 64_750_000, false) { 62_350_000 })
        // A later GOP whose sync frame lies past the decoder: seeking skips work.
        assertEquals(Action.SEEK, DecoderPlan.decide(90_000_000, false, 64_750_000, false) { 87_700_000 })
        // A small step never queries the sync frame (all-intra runs at cuts must not flush every frame).
        assertEquals(Action.FORWARD, DecoderPlan.decide(64_800_000, false, 64_750_000, false) { error("not needed") })
        // Backward past the cache: a seek. Fresh decoder: seek unless the target is 0.
        assertEquals(Action.SEEK, DecoderPlan.decide(60_000_000, false, 64_750_000, false) { 57_200_000 })
        assertEquals(Action.SEEK, DecoderPlan.decide(5_000_000, false, -1, false) { 0 })
        assertEquals(Action.FORWARD, DecoderPlan.decide(0, false, -1, false) { 0 })
        // At the end of the stream nothing more can be decoded: the last frame.
        assertEquals(Action.CACHED, DecoderPlan.decide(130_000_000, false, 122_750_000, true) { 120_000_000 })
    }

    @Test fun aCancelledDecodeIsRetargetedNotRestarted() {
        // A backward scrub to 61.5 s seeked to 57.2 s and was cancelled at 59.0 s; the finger moved on to 61.2 s (same GOP):
        // the decoder continues from 59.0 s (the latest target wins), no flush, no second seek to 57.2 s.
        assertEquals(Action.FORWARD, DecoderPlan.decide(61_200_000, false, 59_000_000, false) { 57_200_000 })
        // Further forward than 0.5 s but still inside that GOP: still forward.
        assertEquals(Action.FORWARD, DecoderPlan.decide(61_900_000, false, 59_000_000, false) { 57_200_000 })
        // Only a target already passed (and not cached) needs a seek.
        assertEquals(Action.SEEK, DecoderPlan.decide(58_500_000, false, 59_000_000, false) { 57_200_000 })
    }

    @Test fun theClockNeverStepsBackAndItsFallbackIsTheOutputPathBehindTheHead() {
        assertEquals(1_000_000L, AvClock.monotonic(1_000_000, 950_000))
        assertEquals(1_050_000L, AvClock.monotonic(1_000_000, 1_050_000))
        // getLatency 341 ms with a 256 ms buffer: 85 ms behind the head (was 341 ms: the start held, then +80 ms).
        assertEquals(4080L, AvClock.fallbackLagFrames(16_368, 12_288))
        assertEquals(0L, AvClock.fallbackLagFrames(12_288, 12_288))
        val start = 48_000L
        assertEquals(start + 7920 - 4080, AvClock.positionFrames(start, 7920, 0, null, null, AvClock.fallbackLagFrames(16_368, 12_288)))
    }

    @Test fun seeksBackToBackAreAScrub() {
        assertTrue(ScrubPolicy.scrubbing(16_000_000))
        assertTrue(ScrubPolicy.scrubbing(ScrubPolicy.GAP_NS - 1))
        assertFalse(ScrubPolicy.scrubbing(ScrubPolicy.GAP_NS))
        assertFalse(ScrubPolicy.scrubbing(-1))                 // the first seek (no previous one)
        assertTrue(DecoderPlan.APPROX_DECODE_FRAMES in 1..8)
    }

    @Test fun theTimelineFollowsTheHeardTimeAndTheWaveformMapsTimeAndScrollToScreenX() {
        val ppu = 0.000338                                      // 1 px = 2.96 ms (the A1 zoom)
        // TLV1: the follow puts the heard time under the playhead (time, not px), clamped to the composition.
        assertEquals(61_290_000L, TimelineFollow.centreFor(61_290_000, 122_000_000))
        assertEquals(0L, TimelineFollow.centreFor(-5, 122_000_000))
        assertEquals(122_000_000L, TimelineFollow.centreFor(999_000_000, 122_000_000))
        // A time under the line draws AT the line whatever the speed (same scroll -> same x: no drift).
        val line = 540.5f
        val centre = TimelineFollow.centreFor(61_290_000, 122_000_000)
        assertEquals(line, TimelineFollow.screenX(centre, centre, ppu, line), 0.5f)
        assertEquals(line + 100f, TimelineFollow.screenX(centre + Math.round(100 / ppu), centre, ppu, line), 0.5f)
        // Hours into a composition the relative mapping is as exact as at the start.
        assertEquals(line + 100f, TimelineFollow.screenX(3 * 3_600_000_000L + Math.round(100 / ppu), 3 * 3_600_000_000L, ppu, line), 0.5f)
        assertTrue(TimelineFollow.LEAD_NS in 0..33_000_000)
    }

    @Test fun theFirstTimestampIsSlewedInNotJumped() {
        // The clock sat at 64.65 s; the first timestamp says 64.73 s (+80 ms) 4 ms later: at most 1.5 x 4 ms is taken.
        assertEquals(64_656_000L, AvClock.slew(64_650_000, 0, 64_730_000, 4_000_000))
        // Steady playback (1 x real time) passes unchanged; a backward step holds.
        assertEquals(64_670_000L, AvClock.slew(64_650_000, 0, 64_670_000, 20_000_000))
        assertEquals(64_650_000L, AvClock.slew(64_650_000, 0, 64_640_000, 20_000_000))
        // An 80 ms correction is absorbed within ~160 ms of reads every 4 ms (a 1.5-frame step at most at 30 fps).
        var us = 64_650_000L; var ns = 0L; var target = 64_730_000L; var maxStep = 0L
        repeat(60) { ns += 4_000_000; target += 4_000; val next = AvClock.slew(us, ns - 4_000_000, target, ns); maxStep = maxOf(maxStep, next - us); us = next }
        assertEquals(target, us)
        assertTrue(maxStep <= 6_000)
    }

    private fun bench(vararg gains: Float, overlap: Boolean = true): com.fluxx.android.model.ProjectDocument {
        val layers = gains.mapIndexed { i, g -> com.fluxx.android.model.CompositionLayer(i + 1L, com.fluxx.android.model.LayerType.VIDEO,
            timing = com.fluxx.android.model.ClipTiming(if (overlap) 0 else i * 10_000_000L, 0, 10_000_000), audioGain = g,
            asset = com.fluxx.android.model.AssetReference("a$i", "file:///x$i.mp4", access = com.fluxx.android.model.MediaAccess.AVAILABLE, durationUs = 10_000_000)) }
        return com.fluxx.android.model.ProjectDocument(com.fluxx.android.model.Composition(durationUs = 30_000_000, layers = com.fluxx.android.model.FrozenList.of(layers)))
    }

    @Test fun mixedSamplesSaturateNeverWrap() {
        assertEquals(32767.toShort(), AudioGains.saturate(1.0f))
        assertEquals(32767.toShort(), AudioGains.saturate(1.7f))        // three loud layers summed: clamps, no wrap
        assertEquals((-32767).toShort(), AudioGains.saturate(-5f))
        assertEquals(16383.toShort(), AudioGains.saturate(.5f))
        // The slider and a typed value are held to the model range (CompositionLayer requires 0..1: 150 % crashed).
        assertEquals(0f..1f, AudioGains.RANGE)
        assertEquals(1f, AudioGains.clamp(2f)); assertEquals(0f, AudioGains.clamp(-1f)); assertEquals(0f, AudioGains.clamp(Float.NaN))
        com.fluxx.android.model.CompositionLayer(1, com.fluxx.android.model.LayerType.SOLID, audioGain = AudioGains.clamp(1.5f))
    }

    @Test fun liveGainSplitsIntoTrackVolumeAndRelativeWeightsThatMultiplyBackToTheMix() {
        for (p in listOf(bench(.4f), bench(1f, .5f), bench(.8f, .8f, .2f), bench(.3f, .9f, overlap = false))) {
            val eff = AudioGains.effective(p)
            val (volume, rel) = AudioGains.split(p)
            assertTrue(volume in 0f..1f)
            for ((id, g) in eff) assertEquals(g, volume * rel.getValue(id), 1e-6f)
        }
        // One audible layer: the whole gain is the track volume (heard at once), its weight stays 1.
        val (v, r) = AudioGains.split(bench(.4f))
        assertEquals(.4f, v, 1e-6f); assertEquals(1f, r.getValue(1L), 1e-6f)
    }

    @Test fun onlyAGainChangeIsLive() {
        val a = bench(1f, .5f)
        val gain = a.copy(composition = a.composition.copy(layers = com.fluxx.android.model.FrozenList.of(a.composition.layers.map { if (it.id == 1L) it.copy(audioGain = .3f) else it })))
        assertTrue(AudioGains.onlyGainsChanged(a, gain))
        val muted = a.copy(composition = a.composition.copy(layers = com.fluxx.android.model.FrozenList.of(a.composition.layers.map { if (it.id == 1L) it.copy(muted = true) else it })))
        assertFalse(AudioGains.onlyGainsChanged(a, muted))
        val toZero = a.copy(composition = a.composition.copy(layers = com.fluxx.android.model.FrozenList.of(a.composition.layers.map { if (it.id == 2L) it.copy(audioGain = 0f) else it })))
        assertFalse(AudioGains.onlyGainsChanged(a, toZero))                 // a voice leaves the mix: not live
        val moved = a.copy(composition = a.composition.copy(layers = com.fluxx.android.model.FrozenList.of(a.composition.layers.map { if (it.id == 2L) it.copy(timing = it.timing.copy(startUs = 1)) else it })))
        assertFalse(AudioGains.onlyGainsChanged(a, moved))
    }

    @Test fun decodersAreAssignedTopDownPerSourceTimeAndCappedWithoutEviction() {
        // Six different sources, four decoders: the TOP four get one each, the lowest two are left out (null).
        val six = (1L..6L).map { Triple(it, "file:///s$it.mp4", 30_000_000L) }
        val a = DecoderKeys.assign(six, DecoderKeys.MAX_DECODERS)
        assertEquals(listOf(null, null, "file:///s3.mp4", "file:///s4.mp4", "file:///s5.mp4", "file:///s6.mp4"), (1L..6L).map { a[it] })
        // One source on three layers at in-points 0 / 10 / 20 s: one decoder per source time (no seek per layer request).
        val shared = listOf(Triple(1L, "u", 10_000_000L), Triple(2L, "u", 20_000_000L), Triple(3L, "u", 30_000_000L))
        assertEquals(setOf("u", "u#1", "u#2"), DecoderKeys.assign(shared, 4).values.toSet())
        // The same source at the same time (or within SHARE_US) shares one decoder.
        val same = listOf(Triple(1L, "u", 10_000_000L), Triple(2L, "u", 10_000_000L + DecoderKeys.SHARE_US), Triple(3L, "v", 0L))
        val s = DecoderKeys.assign(same, 4)
        assertEquals(s[1L], s[2L]); assertEquals(2, s.values.toSet().size)
        assertEquals("u", DecoderKeys.uri("u#2"))
        assertEquals(2, DecoderKeys.MAX_DECODERS_LOW_RAM)
    }
}
