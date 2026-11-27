package com.fluxx.android.engine

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.*
import android.net.Uri
import android.os.SystemClock
import java.io.Closeable
import java.util.concurrent.CancellationException

/**
 * Pull decoder, no decoder-owned playback clock or global staging slot.
 *
 * AUD2 B2: it keeps a small cache of the frames it decoded last ([FrameCache], [DecoderPlan.cacheFrames] frames from a
 * [DecoderPlan.CACHE_BYTES] budget), so a request one or a few frames back (Pause re-rendering the frame the UI last saw,
 * a clock that steps back a frame at Resume, a backward scrub step) is served from memory instead of a flush, a seek to
 * the previous sync frame and a forward decode. It only seeks forward when the target's sync frame lies PAST what is
 * already decoded (decoding on is never slower than seeking back to the same sync frame), and a request after a
 * cancelled one continues from where that one stopped (the latest target wins; no flush). With [approximate] (scrubbing)
 * the work per request is bounded: a miss shows the nearest cached or sync frame, and the exact frame follows when asked.
 */
class VideoDecoder(context: Context, private val uri: Uri) : Closeable {
    private val app = context.applicationContext
    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private var reader: ImageReader? = null
    private val info = MediaCodec.BufferInfo()
    private var inputEnded = false
    private var outputEnded = false
    /** The pts (us) of the last frame decoded in the current run; -1 before any (or just before the sync frame of a seek). */
    private var outPos = -1L
    private var track = -1
    private var syncProbe: MediaExtractor? = null
    val rotation: Int
    val pixelAspect: Float
    val width: Int
    val height: Int
    /** The number of decoded frames kept (bounded by memory). */
    val cacheFrames: Int
    private val cache: FrameCache<Image>
    /** The last [frameAt] returned a frame that is not the exact nearest frame (scrubbing): ask again without approximate. */
    var lastApproximate = false; private set
    private val probeKey = uri.toString().hashCode().toUInt().toString(16)

    init {
        var frames = 3
        var made: FrameCache<Image>? = null
        try {
            extractor.setDataSource(context, uri, null)
            track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("This file has no video track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            width = format.getInteger(MediaFormat.KEY_WIDTH)
            height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val geometry = VideoGeometry.from(format)
            rotation = geometry.rotation
            pixelAspect = geometry.pixelAspect
            frames = DecoderPlan.cacheFrames(width, height)
            made = FrameCache(frames) { it.close() }
            // Rotation is applied explicitly by the shared geometry contract.
            format.setInteger(MediaFormat.KEY_ROTATION, 0)
            // The cached frames plus one being acquired plus one spare.
            reader = ImageReader.newInstance(width, height, ImageFormat.PRIVATE, frames + 2, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
            MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME))).also {
                codec = it
                it.configure(format, reader!!.surface, null, 0); it.start()
            }
            testEvents?.invoke("open $probeKey ${width}x$height cache=$frames codec=${codec?.name}")
        } catch (e: Exception) {
            testEvents?.invoke("open-failed $probeKey ${e.javaClass.simpleName}: ${e.message}")
            releaseAll(made); throw e
        }
        cacheFrames = frames
        cache = made!!
    }

    fun frameAt(timeUs: Long, cancelled: () -> Boolean, approximate: Boolean = false): Image {
        val probe = testEvents
        val probeStart = if (probe != null) SystemClock.elapsedRealtimeNanos() else 0L
        val probeFrom = outPos
        var probeSeek = -1L
        var decoded = 0
        var how = "decode"
        lastApproximate = false
        // MP1 A3: shown= is the pts of the frame returned (the scrub display rule compares it with the target t=).
        fun log(kind: String, shown: Image? = null) = probe?.invoke("$kind $probeKey t=$timeUs from=$probeFrom seek=$probeSeek decoded=$decoded how=$how us=${(SystemClock.elapsedRealtimeNanos() - probeStart) / 1000} shown=${shown?.let { it.timestamp / 1000 } ?: -1}")
        try {
            cache.exact(timeUs)?.let { hit ->
                // Playback: the hit is the look-ahead frame of the previous request; decode the next one now, so every
                // frame costs one decode (without this, calls alternated 0 / 2 decodes and the pacing got uneven).
                if (!approximate && !outputEnded && hit.timestamp / 1000 == outPos) { decodeNext(cancelled); decoded++ }
                how = "cached"; log("frame", hit); return hit
            }
            var sync = Long.MIN_VALUE
            val action = DecoderPlan.decide(timeUs, false, outPos, outputEnded) { prevSync(timeUs).also { sync = it } }
            if (action == DecoderPlan.Action.CACHED) {
                how = "ended"
                return (cache.exact(timeUs) ?: requireNotNull(cache.nearest(timeUs)) { "The decoder returned no video frames" }.second).also { log("frame", it) }
            }
            if (action == DecoderPlan.Action.SEEK) {
                if (sync == Long.MIN_VALUE) sync = prevSync(timeUs)
                if (approximate) {
                    // Scrubbing: the nearer of a frame already in memory and the sync frame (one decode), never a GOP decode.
                    val near = cache.nearest(timeUs)
                    if (near != null && kotlin.math.abs(near.first - timeUs) <= kotlin.math.abs(sync - timeUs)) {
                        lastApproximate = true; how = "approx-cached"; log("frame", near.second); return near.second
                    }
                }
                seek(sync); probeSeek = sync
            }
            // Decode forward until a frame after the target exists (the nearest-frame rule needs both neighbours).
            while (!outputEnded && outPos <= timeUs) {
                if (approximate && decoded >= DecoderPlan.APPROX_DECODE_FRAMES) break
                decodeNext(cancelled) ?: break
                decoded++
            }
            val exact = cache.exact(timeUs)
            if (exact == null) { lastApproximate = !outputEnded; if (lastApproximate) how = "approx" }
            return (exact ?: requireNotNull(cache.nearest(timeUs)) { "The decoder returned no video frames" }.second).also { log("frame", it) }
        } catch (e: CancellationException) {
            log("cancelled")
            throw e
        }
    }

    /** Flush and seek to [sync]; the decoder then stands just before it (a later target continues forward from there). */
    private fun seek(sync: Long) {
        // Every rendered output was acquired synchronously before this flush; no pending callbacks.
        codec!!.flush()
        extractor.seekTo(sync, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        inputEnded = false; outputEnded = false
        cache.breakRun()
        outPos = extractor.sampleTime.let { if (it >= 0) it - 1 else -1 }
    }

    /** The sync frame at or before [t] (a second extractor, so the decoding one keeps its position). */
    private fun prevSync(t: Long): Long {
        val p = syncProbe ?: MediaExtractor().also { it.setDataSource(app, uri, null); it.selectTrack(track); syncProbe = it }
        p.seekTo(t, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        return p.sampleTime.coerceAtLeast(0)
    }

    /** Decodes the next frame into the cache; null at the end of the stream. */
    private fun decodeNext(cancelled: () -> Boolean): Image? {
        if (outputEnded) return null
        val deadline = SystemClock.elapsedRealtime() + 5000
        val decoder = requireNotNull(codec)
        while (!outputEnded) {
            if (cancelled()) throw CancellationException("Superseded preview request")
            check(SystemClock.elapsedRealtime() < deadline) { "Video decoding timed out for this source. Retry or relink the media." }
            if (!inputEnded) {
                val index = decoder.dequeueInputBuffer(0)
                if (index >= 0) {
                    val data = requireNotNull(decoder.getInputBuffer(index))
                    val size = extractor.readSampleData(data, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true
                    } else {
                        decoder.queueInputBuffer(index,0,size,extractor.sampleTime,0); extractor.advance()
                    }
                }
            }
            val index = decoder.dequeueOutputBuffer(info, 2000)
            if (index >= 0) {
                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val hasImage = info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                decoder.releaseOutputBuffer(index, hasImage)
                if (hasImage) {
                    // Always acquire the output just released (and cache it under its own pts), even if the request
                    // became obsolete: it can never masquerade as a later seek result.
                    while (SystemClock.elapsedRealtime() < deadline) {
                        reader!!.acquireNextImage()?.let { img ->
                            val pts = img.timestamp / 1000
                            cache.add(pts, img); outPos = pts
                            if (outputEnded) cache.markEnded()
                            return img
                        }
                        Thread.sleep(1)
                    }
                    error("Timed out waiting for a decoded image")
                }
                if (outputEnded) cache.markEnded()
            }
        }
        return null
    }

    private fun releaseAll(made: FrameCache<Image>?) {
        runCatching { made?.clear() }
        try { codec?.stop() } catch (_: Exception) { }
        codec?.release(); codec = null
        reader?.close(); reader = null
        extractor.release()
        syncProbe?.release(); syncProbe = null
    }

    override fun close() {
        testEvents?.invoke("close $probeKey")
        releaseAll(cache)
    }

    companion object {
        /**
         * AUD2: a TEST-ONLY observer of decoder lifecycle and seeks ("open", "frame ... seek= decoded= how= us=",
         * "cancelled", "close"). Null in the app (nothing is built then); the benchmarks log flushes and re-creations.
         */
        @Volatile @JvmStatic var testEvents: ((String) -> Unit)? = null
    }
}
