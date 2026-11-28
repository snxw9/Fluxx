package com.fluxx.android.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * MP2.3 B1: the strip builder's decoder: MediaCodec with CPU output (flexible YUV 4:2:0, read through getOutputImage),
 * no surface and no frame cache. A picture the builder keeps is downscaled ([StripPixels]) and encoded as JPEG
 * ([StripPolicy.JPEG_QUALITY], long edge [StripPolicy.LONG_EDGE]); the others are released without conversion.
 * Background priority work: it runs on the builder's thread only.
 */
internal class StripDecoder(context: Context, uri: Uri) : Closeable {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private val streamStartUs: Long
    private var inputEnded = false
    private var outputEnded = false
    private var fed = false
    private val bt601: Boolean
    private val fullRange: Boolean
    private var pixels = IntArray(0)
    private var bitmap: Bitmap? = null
    private val jpeg = ByteArrayOutputStream(64 * 1024)

    init {
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: error("This file has no video track")
            extractor.selectTrack(track)
            streamStartUs = extractor.sampleTime
            val format = extractor.getTrackFormat(track)
            bt601 = format.containsKey(MediaFormat.KEY_COLOR_STANDARD) && format.getInteger(MediaFormat.KEY_COLOR_STANDARD).let {
                it == MediaFormat.COLOR_STANDARD_BT601_NTSC || it == MediaFormat.COLOR_STANDARD_BT601_PAL }
            fullRange = format.containsKey(MediaFormat.KEY_COLOR_RANGE) && format.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            format.setInteger(MediaFormat.KEY_ROTATION, 0)
            format.setInteger(MediaFormat.KEY_PRIORITY, 1) // best effort: never ahead of the preview's own decoders
            codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            try { codec.configure(format, null, null, 0); codec.start() } catch (e: Exception) { codec.release(); throw e }
        } catch (e: Exception) { extractor.release(); throw e }
    }

    /** Flushes and seeks to the sync frame at or before [us]; true when that is the stream's first sample. */
    fun seek(us: Long): Boolean {
        if (fed) codec.flush()
        extractor.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        inputEnded = false; outputEnded = false
        return extractor.sampleTime == streamStartUs
    }

    /** The next picture (in output order); its JPEG when [want] says so. Null at the end of the stream. */
    fun next(want: (Long) -> Boolean): StripBuilder.Picture? {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!outputEnded) {
            check(SystemClock.elapsedRealtime() < deadline) { "Strip decoding timed out" }
            if (!inputEnded) {
                val index = codec.dequeueInputBuffer(0)
                if (index >= 0) {
                    val buffer = requireNotNull(codec.getInputBuffer(index))
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                    else { codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
                    fed = true
                }
            }
            val index = codec.dequeueOutputBuffer(info, 5000)
            if (index < 0) continue
            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            val picture = info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
            if (!picture) { codec.releaseOutputBuffer(index, false); continue }
            val pts = info.presentationTimeUs
            var bytes: ByteArray? = null
            try { if (want(pts)) bytes = encode(index) } finally { codec.releaseOutputBuffer(index, false) }
            return StripBuilder.Picture(pts, bytes)
        }
        return null
    }

    private fun encode(index: Int): ByteArray? {
        val image = codec.getOutputImage(index) ?: return null
        image.use { img ->
            val crop = img.cropRect
            val (w, h) = StripPolicy.size(crop.width(), crop.height())
            if (pixels.size != w * h) { pixels = IntArray(w * h); bitmap?.recycle(); bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
            val p = img.planes
            if (p.size < 3) return null
            // 8-bit 4:2:0 (YUV_420_888) or 10-bit P010 (16-bit samples: the high byte is read).
            val high = when (img.format) { ImageFormat.YUV_420_888 -> 0; 0x36 /* YCBCR_P010 */ -> 1; else -> return null }
            StripPixels.toArgb(p[0].buffer, p[0].rowStride, p[0].pixelStride, p[1].buffer, p[2].buffer, p[1].rowStride, p[1].pixelStride,
                crop.left, crop.top, crop.width(), crop.height(), w, h, pixels, high, bt601, fullRange)
            val bmp = bitmap!!
            bmp.setPixels(pixels, 0, w, 0, 0, w, h)
            jpeg.reset()
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, StripPolicy.JPEG_QUALITY, jpeg)) return null
            return jpeg.toByteArray()
        }
    }

    override fun close() {
        runCatching { codec.stop() }; codec.release(); extractor.release()
        bitmap?.recycle(); bitmap = null
    }
}

/** MP2.3 B1: 4:2:0 planes to a downscaled ARGB picture (pure, JVM tested). */
object StripPixels {
    /**
     * Writes [outW] x [outH] opaque ARGB pixels of the crop ([cl], [ct], [cw], [ch]) into [out]. Luma is the mean of the
     * four corners of each output pixel's source block (less shimmer than one sample), chroma one sample at its centre.
     * [high] is added to every byte index (1 for P010: the high byte of each 16-bit sample). BT.709 limited range unless
     * [bt601] / [fullRange] (the stream's colour aspects).
     */
    fun toArgb(y: ByteBuffer, yRow: Int, yPix: Int, u: ByteBuffer, v: ByteBuffer, uvRow: Int, uvPix: Int,
               cl: Int, ct: Int, cw: Int, ch: Int, outW: Int, outH: Int, out: IntArray, high: Int = 0,
               bt601: Boolean = false, fullRange: Boolean = false) {
        val ky = if (fullRange) 1f else 255f / 219f; val oy = if (fullRange) 0 else 16
        val kc = if (fullRange) 1f else 255f / 224f
        // R = Y + a*Cr; G = Y - b*Cb - c*Cr; B = Y + d*Cb.
        val a = (if (bt601) 1.402f else 1.5748f) * kc; val d = (if (bt601) 1.772f else 1.8556f) * kc
        val b = (if (bt601) 0.344136f else 0.187324f) * kc; val c = (if (bt601) 0.714136f else 0.468124f) * kc
        val yLimit = y.limit(); val uLimit = u.limit(); val vLimit = v.limit()
        for (oy2 in 0 until outH) {
            val sy0 = ct + (oy2.toLong() * ch / outH).toInt()
            val sy1 = (ct + ((oy2 + 1).toLong() * ch / outH).toInt() - 1).coerceAtLeast(sy0)
            val syc = ct + ((2L * oy2 + 1) * ch / (2L * outH)).toInt()
            for (ox in 0 until outW) {
                val sx0 = cl + (ox.toLong() * cw / outW).toInt()
                val sx1 = (cl + ((ox + 1).toLong() * cw / outW).toInt() - 1).coerceAtLeast(sx0)
                val sxc = cl + ((2L * ox + 1) * cw / (2L * outW)).toInt()
                fun luma(x: Int, row: Int): Int { val i = row * yRow + x * yPix + high; return if (i < yLimit) y.get(i).toInt() and 255 else 0 }
                val l = (luma(sx0, sy0) + luma(sx1, sy0) + luma(sx0, sy1) + luma(sx1, sy1) + 2) shr 2
                val ci = (syc shr 1) * uvRow + (sxc shr 1) * uvPix + high
                val cb = (if (ci < uLimit) u.get(ci).toInt() and 255 else 128) - 128
                val cr = (if (ci < vLimit) v.get(ci).toInt() and 255 else 128) - 128
                val yy = (l - oy) * ky
                val r = (yy + a * cr + .5f).toInt().coerceIn(0, 255)
                val g = (yy - b * cb - c * cr + .5f).toInt().coerceIn(0, 255)
                val bl = (yy + d * cb + .5f).toInt().coerceIn(0, 255)
                out[oy2 * outW + ox] = (255 shl 24) or (r shl 16) or (g shl 8) or bl
            }
        }
    }
}
