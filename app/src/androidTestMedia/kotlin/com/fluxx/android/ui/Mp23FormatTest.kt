package com.fluxx.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.render.StripPixels
import com.fluxx.android.render.StripPolicy
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * MP2.3 B1: the strip format, measured on real frames. For each file, 20 s of its used range is decoded (CPU output, as
 * the builder does); every 0.1 s one picture is downscaled to a long edge of 320 and of 480 px and encoded as JPEG q70,
 * JPEG q80 and WebP (lossy) q70. Reported: bytes per picture, MB per minute of source at 0.1 / 0.2 / 0.25 s intervals,
 * the conversion + encode time and the decode + upload-ready (BitmapFactory) time per picture. Numbers only.
 */
class Mp23FormatTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun out(line: String) { android.util.Log.i("MP23", line); InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("MP23", line) }) }

    private fun find(like: String): android.net.Uri? {
        for (volume in MediaStore.getExternalVolumeNames(context)) {
            val collection = MediaStore.Video.Media.getContentUri(volume)
            context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf(like),
                "${MediaStore.MediaColumns.DISPLAY_NAME} ASC")?.use { c -> if (c.moveToFirst()) return android.content.ContentUris.withAppendedId(collection, c.getLong(0)) }
        }
        return null
    }

    private data class Config(val edge: Int, val format: Bitmap.CompressFormat, val quality: Int) {
        val label get() = "${if (format == Bitmap.CompressFormat.JPEG) "JPEG" else "WebP"} q$quality ${edge}px"
    }
    @Suppress("DEPRECATION")
    private val configs = listOf(Config(320, Bitmap.CompressFormat.JPEG, 70), Config(320, Bitmap.CompressFormat.JPEG, 80), Config(320, Bitmap.CompressFormat.WEBP_LOSSY, 70),
        Config(480, Bitmap.CompressFormat.JPEG, 70), Config(480, Bitmap.CompressFormat.JPEG, 80), Config(480, Bitmap.CompressFormat.WEBP_LOSSY, 70))

    @Test fun stripFormats() {
        TestDeviceState.record(javaClass.simpleName)
        val files = listOf("H.264 1080p30 (typical)" to ("%" + Aud2Bench.TYPICAL_ID + "%" to 10_000_000L), "H.264 1080p24 (Seal)" to ("ALL THE MONEY - NARUTO%" to 10_000_000L),
            "VP9 1920x1440 (worst case)" to (Aud1Bench.PREFIX + "%" to 30_000_000L), "HEVC 1080p30 (50-minute file)" to (Mp1Bench.LONG_PREFIX + "%" to 1_200_000_000L),
            "AV1 1080p30 (Seal)" to ("bzpy - %" to 20_000_000L))
        val total = HashMap<String, MutableList<Double>>()
        for ((label, spec) in files) {
            val uri = find(spec.first) ?: run { out("$label: not found; skipped"); null } ?: continue
            val r = runCatching { measure(label, uri, spec.second) }
            r.exceptionOrNull()?.let { out("$label: FAILED ${it.javaClass.simpleName}: ${it.message}") }
            r.getOrNull()?.forEach { (k, v) -> total.getOrPut(k) { ArrayList() } += v }
        }
        for (c in configs) total[c.label]?.let { v -> out("all files, ${c.label}: mean %.1f KB / picture -> %.1f MB/min at 0.1 s".format(v.average() / 1024, v.average() * 600 / 1048576)) }
    }

    /** Bytes per picture per config for one file. */
    private fun measure(label: String, uri: android.net.Uri, fromUs: Long): Map<String, Double> {
        val ex = MediaExtractor(); ex.setDataSource(context, uri, null)
        val t = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        ex.selectTrack(t)
        val fmt = ex.getTrackFormat(t)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        ex.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val endUs = fromUs + 20_000_000L
        val sizes = HashMap<String, MutableList<Int>>(); val encMs = HashMap<String, MutableList<Double>>(); val decMs = HashMap<String, MutableList<Double>>()
        val info = MediaCodec.BufferInfo(); var inputDone = false; var nextSlot = fromUs / StripPolicy.INTERVAL_US; var frames = 0
        val t0 = System.nanoTime(); var imageFormat = 0
        try {
            loop@ while (true) {
                if (!inputDone) { val i = codec.dequeueInputBuffer(0); if (i >= 0) { val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0 || ex.sampleTime > endUs + 1_000_000) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() } } }
                val o = codec.dequeueOutputBuffer(info, 5000)
                if (o < 0) continue
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { codec.releaseOutputBuffer(o, false); break }
                val pts = info.presentationTimeUs
                if (pts > endUs) { codec.releaseOutputBuffer(o, false); break@loop }
                frames++
                if (pts >= fromUs && pts / StripPolicy.INTERVAL_US >= nextSlot) {
                    nextSlot = pts / StripPolicy.INTERVAL_US + 1
                    codec.getOutputImage(o)?.use { img ->
                        imageFormat = img.format
                        val high = if (img.format == ImageFormat.YUV_420_888) 0 else 1
                        val p = img.planes; val crop = img.cropRect
                        for (edge in configs.map { it.edge }.distinct()) {
                            val (w, h) = StripPolicy.size(crop.width(), crop.height(), edge)
                            val px = IntArray(w * h)
                            val a = System.nanoTime()
                            StripPixels.toArgb(p[0].buffer, p[0].rowStride, p[0].pixelStride, p[1].buffer, p[2].buffer, p[1].rowStride, p[1].pixelStride,
                                crop.left, crop.top, crop.width(), crop.height(), w, h, px, high)
                            val convertMs = (System.nanoTime() - a) / 1e6
                            val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
                            for (c in configs.filter { it.edge == edge }) {
                                val bos = ByteArrayOutputStream(); val b = System.nanoTime()
                                bmp.compress(c.format, c.quality, bos)
                                encMs.getOrPut(c.label) { ArrayList() } += convertMs + (System.nanoTime() - b) / 1e6
                                val bytes = bos.toByteArray(); sizes.getOrPut(c.label) { ArrayList() } += bytes.size
                                val d = System.nanoTime()
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.recycle()
                                decMs.getOrPut(c.label) { ArrayList() } += (System.nanoTime() - d) / 1e6
                            }
                            bmp.recycle()
                        }
                    }
                }
                codec.releaseOutputBuffer(o, false)
            }
        } finally { runCatching { codec.stop() }; codec.release(); ex.release() }
        val wall = (System.nanoTime() - t0) / 1e9
        out("$label: ${fmt.getInteger(MediaFormat.KEY_WIDTH)}x${fmt.getInteger(MediaFormat.KEY_HEIGHT)} ${fmt.getString(MediaFormat.KEY_MIME)}, image format 0x${Integer.toHexString(imageFormat)}; " +
            "$frames frames decoded (CPU output, all configs encoded) in %.1f s".format(wall))
        val out = HashMap<String, Double>()
        for (c in configs) {
            val s = sizes[c.label] ?: continue
            val mean = s.average(); out[c.label] = mean
            out("$label, ${c.label}: ${s.size} pictures, mean %.1f KB (p95 %.1f) -> %.1f / %.1f / %.1f MB per minute at 0.1 / 0.2 / 0.25 s; convert+encode p50 %.1f ms, decode p50 %.1f ms".format(
                mean / 1024, Aud2Bench.pct(s.map { it.toDouble() }, .95) / 1024, mean * 600 / 1048576, mean * 300 / 1048576, mean * 240 / 1048576,
                Aud2Bench.pct(encMs[c.label]!!, .5), Aud2Bench.pct(decMs[c.label]!!, .5)))
        }
        return out
    }
}
