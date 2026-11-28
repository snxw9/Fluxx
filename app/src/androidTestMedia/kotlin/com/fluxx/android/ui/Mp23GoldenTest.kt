package com.fluxx.android.ui

import android.os.Bundle
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.engine.RenderBridge
import com.fluxx.android.engine.VideoDecoder
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import com.fluxx.android.render.CompositionRenderer
import com.fluxx.android.render.MediaProbes
import com.fluxx.android.render.ScrubStrips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * MP2.3 B2: the scrub strip / decode-behind DIFFERENTIAL golden test (as [Mp21GoldenTest]). Two offscreen renderers over
 * the same document: ON (strip and decode-behind) and OFF (neither, today's renderer). ON runs a scripted session:
 * playback frames, backward and forward sweeps (approximate requests: strip pictures where covered, otherwise the bounded
 * decode), the exact frame where the finger stops, decode-behind at the pause and single steps back from it, then
 * playback again. Every frame that is NOT a scrub frame (playback, exact frames after a sweep, steps) is rendered by OFF
 * too and the readbacks must be BYTE-IDENTICAL. Strip pictures themselves are low-resolution by design (not compared).
 * ON's probes prove the strip and decode-behind were really used (a pass is never vacuous).
 */
class Mp23GoldenTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun report(line: String) { android.util.Log.i("MP23", line); InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("MP23", line) }) }
    private val w = 640; private val h = 360

    private fun content(like: String): Pair<String, Long>? {
        for (volume in MediaStore.getExternalVolumeNames(context)) {
            val collection = MediaStore.Video.Media.getContentUri(volume)
            context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf(like), null)?.use { c ->
                if (c.moveToFirst()) { val uri = android.content.ContentUris.withAppendedId(collection, c.getLong(0)).toString()
                    return uri to (MediaRepository(context).video(uri).durationUs ?: return null) }
            }
        }
        return null
    }

    @Test fun stripAndDecodeBehindArePixelIdentical() {
        TestDeviceState.record(javaClass.simpleName)
        val typical = content("%" + Aud2Bench.TYPICAL_ID + "%")
        assumeTrue("MP23: the typical clip was not found", typical != null)
        val vp9 = content(Aud1Bench.PREFIX + "%")
        // Two layers: the typical H.264 clip full frame, and the VP9 file (long GOP) in a corner from 30 s of its source.
        val rate = FrameRate(30, 1)
        val dur = 20_000_000L
        val layers = ArrayList<CompositionLayer>()
        layers += CompositionLayer(1, LayerType.VIDEO, name = "h264", zOrder = 10, timing = ClipTiming(0, 20_000_000L, dur),
            asset = AssetReference("mp23-golden-1", typical!!.first, access = MediaAccess.AVAILABLE, durationUs = typical.second))
        if (vp9 != null) layers += CompositionLayer(2, LayerType.VIDEO, name = "vp9", zOrder = 20, timing = ClipTiming(0, 30_000_000L, dur),
            transform = Transform(positionX = .3f, positionY = .3f, scaleX = .4f, scaleY = .4f),
            asset = AssetReference("mp23-golden-2", vp9.first, access = MediaAccess.AVAILABLE, durationUs = vp9.second))
        val project = ProjectDocument(Composition(width = w, height = h, durationUs = dur, frameRate = rate, layers = FrozenList.of(layers)))
        val repo = MediaRepository(context)
        val events = CopyOnWriteArrayList<String>()
        MediaProbes.events = { e -> events += e }
        VideoDecoder.testEvents = { e -> if (e.startsWith("behind")) events += e }
        var compared = 0; var differ = 0
        try {
            CompositionRenderer(context, repo, warmUp = false, scrubStrip = true).use { on ->
                CompositionRenderer(context, repo, warmUp = false, scrubStrip = false).use { off ->
                    fun compare(t: Long, what: String) {
                        on.approximateFrames = false
                        on.render(project, t); val a = read(on)
                        off.render(project, t); val b = read(off)
                        compared++
                        if (!(a.first == b.first && a.second == b.second && a.third == b.third)) { differ++; if (differ <= 5) report("MP23 golden: $what at ${t / 1000} ms DIFFERS") }
                    }
                    fun scrub(t: Long) { on.approximateFrames = true; on.render(project, t); on.approximateFrames = false }
                    // The strip of both sources over the used range (the builder runs while this renderer is idle).
                    on.render(project, 0)
                    val t0 = System.nanoTime()
                    while ((System.nanoTime() - t0) / 1_000_000 < 240_000) {
                        on.stripTick(project, 0)
                        val done = layers.all { l -> ScrubStrips.testCoverage(context, l.asset!!.uri!!, l.timing.sourceInUs, l.timing.sourceInUs + dur, l.asset!!.durationUs!!) >= 0.999 }
                        if (done) break
                        Thread.sleep(500)
                    }
                    val builtS = (System.nanoTime() - t0) / 1e9
                    val coverage = layers.joinToString { l -> String.format(java.util.Locale.ROOT, "%.1f", 100 * ScrubStrips.testCoverage(context, l.asset!!.uri!!, l.timing.sourceInUs, l.timing.sourceInUs + dur, l.asset!!.durationUs!!)) + " percent" }
                    report("MP23 golden: strips built in " + String.format(java.util.Locale.ROOT, "%.1f", builtS) + " s; coverage " + coverage)
                    on.playing = true
                    for (k in 0 until 60L) compare(rate.frameTimeUs(k), "playback")
                    on.playing = false
                    // A backward sweep from 12 s to 4 s (every 2nd frame), stops every 40 steps: the exact frame there.
                    var k = 360L; var n = 0
                    while (k > 120) { scrub(rate.frameTimeUs(k)); if (++n % 40 == 0) compare(rate.frameTimeUs(k), "exact after a backward sweep"); k -= 2 }
                    compare(rate.frameTimeUs(k), "exact after a backward sweep")
                    // A fast backward sweep (8x: 8 frames per step) and a forward one, then their exact frames.
                    k = 540; while (k > 200) { scrub(rate.frameTimeUs(k)); k -= 8 }; compare(rate.frameTimeUs(k), "exact after a fast backward sweep")
                    while (k < 420) { scrub(rate.frameTimeUs(k)); k += 4 }; compare(rate.frameTimeUs(k), "exact after a forward sweep")
                    // A pause right on a sync frame of the H.264 source after a jump (only it and the next frame are cached, so
                    // decode-behind decodes the GOP before it), and two ordinary pauses; then steps back (cache hits on ON,
                    // decodes on OFF) and playback again.
                    val syncPause = syncFrame(typical.first, 20_000_000L + 15_000_000L, 20_000_000L + 18_000_000L)?.let { rate.nearestFrame(it - 20_000_000L) }
                    if (syncPause != null) { compare(rate.frameTimeUs(syncPause - 150), "jump"); compare(rate.frameTimeUs(syncPause), "paused frame on a sync frame") }
                    for (pause in listOfNotNull(syncPause, 300L, 451L)) {
                        compare(rate.frameTimeUs(pause), "paused frame")
                        on.decodeBehind(project, rate.frameTimeUs(pause)) { false }
                        for (s in 1..6) compare(rate.frameTimeUs(pause - s), "step back after decode-behind")
                        on.playing = true
                        for (p in 0 until 30L) compare(rate.frameTimeUs(pause - 6 + p), "playback after the steps")
                        on.playing = false
                    }
                    val strips = events.count { it.startsWith("strip-frame") }
                    val behind = events.filter { it.startsWith("behind ") }
                    val behindDecoded = behind.sumOf { Mp1Bench.field(it, "decoded")?.toIntOrNull() ?: 0 }
                    report("MP23 golden: $compared frames compared (playback, exact frames after sweeps, steps back after decode-behind), $differ differ; " +
                        "strip pictures shown $strips, decode-behind calls ${behind.size} (frames decoded $behindDecoded); layers ${layers.size}${if (vp9 == null) " (VP9 file missing)" else ""}")
                    assertTrue("the strip path was not exercised", strips > 0)
                    assertTrue("decode-behind was not exercised", behind.isNotEmpty())
                    assertEquals("the strip / decode-behind changed pixels", 0, differ)
                }
            }
        } finally { MediaProbes.events = null; VideoDecoder.testEvents = null }
    }

    /** The first sync sample time of [uri]'s video in [fromUs, toUs] whose sample before it is not a sync sample, or null. */
    private fun syncFrame(uri: String, fromUs: Long, toUs: Long): Long? {
        val ex = android.media.MediaExtractor()
        try {
            ex.setDataSource(context, android.net.Uri.parse(uri), null)
            val t = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)!!.startsWith("video/") }
            ex.selectTrack(t); ex.seekTo(fromUs, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var prevSync = true
            while (ex.sampleTime in 0..toUs) {
                val sync = ex.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC != 0
                if (sync && !prevSync && ex.sampleTime >= fromUs && ex.sampleTime % 33_333L < 1000) return ex.sampleTime
                prevSync = sync; ex.advance()
            }
            return null
        } finally { ex.release() }
    }

    private fun read(r: CompositionRenderer): Triple<ByteBuffer, ByteBuffer, ByteBuffer> {
        val y = ByteBuffer.allocateDirect(w * h); val u = ByteBuffer.allocateDirect(w * h / 4); val v = ByteBuffer.allocateDirect(w * h / 4)
        check(RenderBridge.copyYuv(r.session, y, u, v, w, w / 2, w / 2, 1, 1)) { "readback failed" }
        return Triple(y, u, v)
    }
}
