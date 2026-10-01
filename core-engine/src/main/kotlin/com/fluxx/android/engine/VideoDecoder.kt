package com.fluxx.android.engine

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.*
import android.net.Uri
import android.os.SystemClock
import java.io.Closeable
import java.util.concurrent.CancellationException

/** Pull decoder. Two retained images per layer, no decoder-owned playback clock or global staging slot. */
class VideoDecoder(context: Context, uri: Uri) : Closeable {
    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private var reader: ImageReader? = null
    private val info = MediaCodec.BufferInfo()
    private var inputEnded = false
    private var outputEnded = false
    private var previous: Image? = null
    private var next: Image? = null
    private var lastRequest = -1L
    val rotation: Int
    val pixelAspect: Float
    val width: Int
    val height: Int

    init {
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("This file has no video track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            width = format.getInteger(MediaFormat.KEY_WIDTH)
            height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val geometry = VideoGeometry.from(format)
            rotation = geometry.rotation
            pixelAspect = geometry.pixelAspect
            // Rotation is applied explicitly by the shared geometry contract.
            format.setInteger(MediaFormat.KEY_ROTATION, 0)
            reader = ImageReader.newInstance(width, height, ImageFormat.PRIVATE, 4, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
            MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME))).also {
                codec = it
                it.configure(format, reader!!.surface, null, 0); it.start()
            }
        } catch (e: Exception) { close(); throw e }
    }

    fun frameAt(timeUs: Long, cancelled: () -> Boolean): Image {
        if (timeUs < lastRequest || (lastRequest >= 0 && timeUs - lastRequest > 500_000)) {
            // Every rendered output was acquired synchronously before this flush; no pending callbacks.
            previous?.close(); previous = null; next?.close(); next = null
            codec!!.flush()
            extractor.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            inputEnded = false; outputEnded = false
        } else if (lastRequest < 0 && timeUs > 0) extractor.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        lastRequest = timeUs
        if (next == null && !outputEnded) next = decodeImage(cancelled)
        while (next != null && next!!.timestamp / 1000 <= timeUs) {
            previous?.close(); previous = next; next = null
            next = decodeImage(cancelled)
        }
        val before = previous
        val after = next
        // Match Media3's nearest-frame resampling, choosing the earlier timestamp on a tie.
        return when {
            before == null -> after ?: error("The decoder returned no video frames")
            after == null -> before
            timeUs - before.timestamp / 1000 <= after.timestamp / 1000 - timeUs -> before
            else -> after
        }
    }

    private fun decodeImage(cancelled: () -> Boolean): Image? {
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
                    // Always acquire the output just released, even if the request became obsolete.
                    // Returning early here could let that image masquerade as a later seek result.
                    while (SystemClock.elapsedRealtime() < deadline) {
                        reader!!.acquireNextImage()?.let { return it }
                        Thread.sleep(1)
                    }
                    error("Timed out waiting for a decoded image")
                }
            }
        }
        return null
    }

    override fun close() {
        previous?.close(); previous = null; next?.close(); next = null
        try { codec?.stop() } catch (_: Exception) { }
        codec?.release(); codec = null
        reader?.close(); reader = null
        extractor.release()
    }
}
