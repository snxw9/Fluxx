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
 * the work per request is bounded and MP1 B1's floor rule applies ([ScrubFloor]): the frame shown is the nearest decoded
 * frame at or before the target, never a later one; the exact frame follows when asked.
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
    /**
     * MP2.1: the stream's first sample time, whether the current run started there (a fresh decoder, or a seek back to
     * it), and the pts of the stream's first picture once decoded. A request before that picture (a cut at in-point 0 of
     * a file whose first frame lies after 0) is answered by it, as a straight decode answers it, without a re-seek.
     */
    private var streamStartUs = Long.MIN_VALUE
    private var runFromStart = true
    private var firstFramePts = -1L
    private var track = -1
    private var syncProbe: MediaExtractor? = null
    val rotation: Int
    val pixelAspect: Float
    val width: Int
    val height: Int
    /** MP2.1 B2: the track's MIME type ("video/avc"), with the size the decoder class a warm-up is measured in. */
    val mime: String
    val decoderClass: String get() = "$mime ${width}x$height"
    /** The number of decoded frames kept (bounded by memory). */
    val cacheFrames: Int
    private val cache: FrameCache<Image>
    /** The last [frameAt] returned a frame that is not the exact nearest frame (scrubbing): ask again without approximate. */
    var lastApproximate = false; private set
    private val probeKey = uri.toString().hashCode().toUInt().toString(16)
    /** MP2.3: the track's nominal frame duration (us), 33 333 without a frame rate (decode-behind's reach). */
    private var frameUs = 33_333L

    init {
        val openStart = SystemClock.elapsedRealtimeNanos()
        var frames = 3
        var made: FrameCache<Image>? = null
        try {
            extractor.setDataSource(context, uri, null)
            track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("This file has no video track")
            extractor.selectTrack(track)
            streamStartUs = extractor.sampleTime
            val format = extractor.getTrackFormat(track)
            mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            width = format.getInteger(MediaFormat.KEY_WIDTH)
            height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val geometry = VideoGeometry.from(format)
            rotation = geometry.rotation
            pixelAspect = geometry.pixelAspect
            frames = DecoderPlan.cacheFrames(width, height)
            frameUs = format.takeIf { it.containsKey(MediaFormat.KEY_FRAME_RATE) }?.let { f ->
                runCatching { f.getNumber(MediaFormat.KEY_FRAME_RATE)!!.toDouble() }.getOrNull() }?.takeIf { it > 1 }?.let { (1e6 / it).toLong() } ?: 33_333L
            made = FrameCache(frames) { it.close() }
            // Rotation is applied explicitly by the shared geometry contract.
            format.setInteger(MediaFormat.KEY_ROTATION, 0)
            // The cached frames plus one being acquired plus one spare.
            reader = ImageReader.newInstance(width, height, ImageFormat.PRIVATE, frames + 2, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
            MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME))).also {
                codec = it
                it.configure(format, reader!!.surface, null, 0); it.start()
            }
            testEvents?.invoke("open $probeKey ${width}x$height cache=$frames codec=${codec?.name} openus=${(SystemClock.elapsedRealtimeNanos() - openStart) / 1000}")
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
        var seekNs = 0L
        var decoded = 0
        var how = "decode"
        lastApproximate = false; held = false
        // MP1 A3: shown= is the pts of the frame returned (the scrub display rule compares it with the target t=).
        fun log(kind: String, shown: Image? = null) = probe?.invoke("$kind $probeKey t=$timeUs from=$probeFrom seek=$probeSeek decoded=$decoded how=$how us=${(SystemClock.elapsedRealtimeNanos() - probeStart) / 1000} seekus=${seekNs / 1000} shown=${shown?.let { it.timestamp / 1000 } ?: -1}")
        try {
            if (approximate) {
                // MP1 B1: scrubbing shows the nearest decoded frame AT OR BEFORE the target (bounded work), never a later one.
                scrubCancel = cancelled; scrubDecoded = 0; scrubSeek = -1L; scrubTarget = timeUs
                val (img, exactFloor) = ScrubFloor.frame(timeUs, cache, scrubSource, DecoderPlan.APPROX_DECODE_FRAMES)
                decoded = scrubDecoded; probeSeek = scrubSeek
                // Not the exact (nearest-rule) frame: the preview renders that once the scrub settles.
                lastApproximate = !(exactFloor && cache.exact(timeUs) === img)
                how = if (exactFloor) "floor" else "floor-approx"; log("frame", img); return img
            }
            firstFrame(timeUs)?.let { how = "first"; log("frame", it); return it }
            cache.exact(timeUs)?.let { hit ->
                // Playback: the hit is the look-ahead frame of the previous request; decode the next one now, so every
                // frame costs one decode (without this, calls alternated 0 / 2 decodes and the pacing got uneven).
                if (!approximate && !outputEnded && hit.timestamp / 1000 == outPos) { decodeNext(cancelled, timeUs); decoded++ }
                how = "cached"; log("frame", hit); return hit
            }
            var sync = Long.MIN_VALUE
            val action = DecoderPlan.decide(timeUs, false, outPos, outputEnded) { prevSync(timeUs).also { sync = it } }
            if (action == DecoderPlan.Action.CACHED) {
                how = "ended"
                return (cache.exact(timeUs) ?: requireNotNull(cache.nearest(timeUs)) { "The decoder returned no video frames" }.second).also { log("frame", it) }
            }
            if (action == DecoderPlan.Action.SEEK) {
                val seekStart = if (probe != null) SystemClock.elapsedRealtimeNanos() else 0L
                if (sync == Long.MIN_VALUE) sync = prevSync(timeUs)
                seek(sync); probeSeek = sync
                if (probe != null) seekNs = SystemClock.elapsedRealtimeNanos() - seekStart
            }
            // Decode forward until a frame after the target exists (the nearest-frame rule needs both neighbours).
            // MP1.2: also when nothing is cached yet: a target before the first frame (Project 1 clip 17: first frame at
            // 0.105 s, the cut asks for 0.003 s) seeks to that first sync frame, already past the target.
            while (!outputEnded && (outPos <= timeUs || cache.size == 0)) {
                decodeNext(cancelled, timeUs) ?: break
                decoded++
            }
            val exact = cache.exact(timeUs)
            // MP1.2 H6: a held last picture IS the answer (no picture exists for this time yet): not approximate.
            if (exact == null) { lastApproximate = !outputEnded && !held; how = if (held) "held" else if (lastApproximate) "approx" else how }
            return (exact ?: requireNotNull(cache.nearest(timeUs)) { "The decoder returned no video frames" }.second).also { log("frame", it) }
        } catch (e: CancellationException) {
            log("cancelled")
            throw e
        }
    }

    /**
     * MP2.1 B2: whether a request for [timeUs] is cheap here: the cache proves its frame, or the decoder stands at most
     * [DecoderPlan.CHEAP_FORWARD_US] before it (decodes on, no seek). A warm decoder positioned for a cut answers yes for
     * its target; the previous clip's decoder of the same source, standing elsewhere, answers no (it would seek).
     */
    fun cheapFor(timeUs: Long): Boolean = cache.exact(timeUs) != null || firstFrame(timeUs) != null ||
        (outPos >= 0 && !outputEnded && timeUs >= outPos && timeUs - outPos <= DecoderPlan.CHEAP_FORWARD_US)

    /**
     * MP2.3 B2: decode behind the paused playhead. When the cache cannot answer the [cacheFrames] - 1 frames before
     * [timeUs] (a backward step would flush, seek to the sync frame and decode the whole GOP up to it), seek to the sync
     * frame before them and decode forward up to [timeUs] (not past it): the cache then holds [timeUs] and the frames
     * just before it, one run, so the first backward steps are cache hits. Frames are the decoder's own output, served by
     * the cache's exact rule: the pictures are those a straight decode gives. Cancellable like [frameAt] (the latest
     * request wins); returns the frames decoded (0: nothing to do).
     */
    fun decodeBehind(timeUs: Long, cancelled: () -> Boolean): Int {
        val probe = testEvents
        val start = SystemClock.elapsedRealtimeNanos()
        val want = cacheFrames - 1
        val have = cache.runBefore(timeUs)
        // Enough frames cached already, or the run reaches the stream's first picture (nothing lies before it).
        if (have >= want || (have > 0 && firstFramePts >= 0 && cache.firstPts() <= firstFramePts)) return 0
        var sync = prevSync(timeUs)
        // Fewer frames than wanted between that sync frame and the playhead: start one GOP earlier.
        if (timeUs - sync < want * frameUs && sync > streamStartUs.coerceAtLeast(0)) sync = prevSync(sync - 1)
        lastApproximate = false; held = false
        var decoded = 0
        try {
            seek(sync)
            while (!outputEnded && outPos < timeUs) { decodeNext(cancelled, timeUs) ?: break; decoded++ }
        } catch (e: CancellationException) {
            probe?.invoke("behind-cancelled $probeKey t=$timeUs sync=$sync decoded=$decoded us=${(SystemClock.elapsedRealtimeNanos() - start) / 1000}")
            throw e
        }
        probe?.invoke("behind $probeKey t=$timeUs sync=$sync had=$have decoded=$decoded cached=${cache.runBefore(timeUs)} us=${(SystemClock.elapsedRealtimeNanos() - start) / 1000}")
        return decoded
    }

    /** MP2.1: the stream's first picture, when [timeUs] lies before it and it is cached (the nearest-frame answer). */
    private fun firstFrame(timeUs: Long): Image? = if (firstFramePts >= 0 && timeUs < firstFramePts) cache.exact(firstFramePts) else null

    // MP1 B1: the scrub rule (ScrubFloor) over this decoder's own seek / sync / decode steps.
    private var scrubCancel: () -> Boolean = { false }
    private var scrubDecoded = 0
    private var scrubSeek = -1L
    private var scrubTarget = Long.MAX_VALUE
    private val scrubSource = object : ScrubFloor.Source {
        override val outPos: Long get() = this@VideoDecoder.outPos
        override val ended: Boolean get() = outputEnded
        override fun prevSync(t: Long): Long = this@VideoDecoder.prevSync(t)
        override fun seek(sync: Long) { this@VideoDecoder.seek(sync); scrubSeek = sync }
        override fun decodeNext(): Boolean = (this@VideoDecoder.decodeNext(scrubCancel, scrubTarget) != null).also { if (it) scrubDecoded++ }
    }

    /** Flush and seek to [sync]; the decoder then stands just before it (a later target continues forward from there). */
    private fun seek(sync: Long) {
        // Every rendered output was acquired synchronously before this flush; no pending callbacks.
        codec!!.flush()
        extractor.seekTo(sync, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        inputEnded = false; outputEnded = false; inputsSinceOutput = 0
        cache.breakRun()
        outPos = extractor.sampleTime.let { if (it >= 0) it - 1 else -1 }
        runFromStart = extractor.sampleTime == streamStartUs
    }

    /** The sync frame at or before [t] (a second extractor, so the decoding one keeps its position). */
    private fun prevSync(t: Long): Long {
        val p = syncProbe ?: MediaExtractor().also { it.setDataSource(app, uri, null); it.selectTrack(track); syncProbe = it }
        p.seekTo(t, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        return p.sampleTime.coerceAtLeast(0)
    }

    // MP1.2 H6: inputs the codec took since its last picture (see DecoderPlan.holdLastPicture).
    private var inputsSinceOutput = 0
    private var firstInputSinceOutput = 0L
    private var lastInputPts = -1L
    private var held = false

    /** Decodes the next frame into the cache; null at the end of the stream, or (MP1.2) when the last picture is held for [targetUs]. */
    private fun decodeNext(cancelled: () -> Boolean, targetUs: Long = Long.MAX_VALUE): Image? {
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
                        val pts = extractor.sampleTime
                        decoder.queueInputBuffer(index,0,size,pts,0); extractor.advance()
                        if (inputsSinceOutput++ == 0) firstInputSinceOutput = pts
                        lastInputPts = maxOf(lastInputPts, pts)
                    }
                }
            }
            val index = decoder.dequeueOutputBuffer(info, 2000)
            if (index < 0 && DecoderPlan.holdLastPicture(inputsSinceOutput, firstInputSinceOutput, lastInputPts, targetUs) && cache.size > 0) {
                held = true
                testEvents?.invoke("hold $probeKey t=$targetUs inputs=$inputsSinceOutput span=${lastInputPts - firstInputSinceOutput}")
                return null
            }
            if (index >= 0) {
                inputsSinceOutput = 0
                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val hasImage = info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                decoder.releaseOutputBuffer(index, hasImage)
                if (hasImage) {
                    // Always acquire the output just released (and cache it under its own pts), even if the request
                    // became obsolete: it can never masquerade as a later seek result.
                    while (SystemClock.elapsedRealtime() < deadline) {
                        reader!!.acquireNextImage()?.let { img ->
                            val pts = img.timestamp / 1000
                            if (runFromStart && firstFramePts < 0) firstFramePts = pts
                            runFromStart = false
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
