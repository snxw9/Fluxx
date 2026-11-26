package com.fluxx.android.ui

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * TL3.1 C / D fixtures, generated in the TEST app's cache (never the user's media): AAC-in-MP4 audio with tone bursts at
 * known times, and small H.264 + AAC videos of a given length.
 */
object Tl31Fixtures {
    private class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)

    /** PCM 16-bit interleaved: a 440 Hz tone at [amp] inside [bursts] (seconds), silence elsewhere. */
    private fun pcm(rate: Int, channels: Int, seconds: Double, bursts: List<Pair<Double, Double>>, amp: Double): ShortArray {
        val n = (rate * seconds).toInt()
        val out = ShortArray(n * channels)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val on = bursts.any { t >= it.first && t < it.second }
            val v = if (on) (sin(2 * PI * 440 * t) * amp * 32767).toInt().toShort() else 0
            for (c in 0 until channels) out[i * channels + c] = v
        }
        return out
    }

    private fun encodeAac(rate: Int, channels: Int, pcm: ShortArray): Pair<MediaFormat, List<Sample>> {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
        val out = ArrayList<Sample>(); var outFormat: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        var pos = 0; var inputDone = false; var done = false
        while (!done) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(5000)
                if (i >= 0) {
                    val buf = requireNotNull(codec.getInputBuffer(i)).order(ByteOrder.LITTLE_ENDIAN)
                    val frames = minOf(buf.remaining() / (2 * channels), 1024, (pcm.size - pos) / channels)
                    if (frames <= 0) { codec.queueInputBuffer(i, 0, 0, pos.toLong() / channels * 1_000_000 / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else {
                        for (k in 0 until frames * channels) buf.putShort(pcm[pos + k])
                        codec.queueInputBuffer(i, 0, frames * channels * 2, pos.toLong() / channels * 1_000_000 / rate, 0); pos += frames * channels
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 5000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outFormat = codec.outputFormat
            else if (o >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val b = requireNotNull(codec.getOutputBuffer(o)); b.position(info.offset); b.limit(info.offset + info.size)
                    out += Sample(ByteArray(info.size).also { b.get(it) }, info.presentationTimeUs, info.flags)
                }
                done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(o, false)
            }
        }
        codec.stop(); codec.release()
        return requireNotNull(outFormat) to out
    }

    /** A tiny H.264 video (grey frames, [fps] frames a second, all keyframes once a second) of [seconds]. */
    private fun encodeVideo(seconds: Double, fps: Int = 10, w: Int = 160, h: Int = 96): Pair<MediaFormat, List<Sample>> {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 200_000); setInteger(MediaFormat.KEY_FRAME_RATE, fps); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
        val total = (seconds * fps).toInt()
        val out = ArrayList<Sample>(); var outFormat: MediaFormat? = null
        val info = MediaCodec.BufferInfo(); var frame = 0; var inputDone = false; var done = false
        while (!done) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(5000)
                if (i >= 0) {
                    val pts = frame.toLong() * 1_000_000 / fps
                    if (frame >= total) { codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else {
                        val image = requireNotNull(codec.getInputImage(i))
                        val luma = (64 + (frame * 7) % 128).toByte()
                        for ((p, plane) in image.planes.withIndex()) {
                            val buf = plane.buffer; val v: Byte = if (p == 0) luma else 128.toByte()
                            while (buf.hasRemaining()) buf.put(v)
                        }
                        codec.queueInputBuffer(i, 0, w * h * 3 / 2, pts, 0); frame++
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 5000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outFormat = codec.outputFormat
            else if (o >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val b = requireNotNull(codec.getOutputBuffer(o)); b.position(info.offset); b.limit(info.offset + info.size)
                    out += Sample(ByteArray(info.size).also { b.get(it) }, info.presentationTimeUs, info.flags)
                }
                done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(o, false)
            }
        }
        codec.stop(); codec.release()
        return requireNotNull(outFormat) to out
    }

    private fun mux(file: File, tracks: List<Pair<MediaFormat, List<Sample>>>) {
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val ids = tracks.map { muxer.addTrack(it.first) }
        muxer.start()
        val info = MediaCodec.BufferInfo()
        // Interleaved by time.
        val all = tracks.flatMapIndexed { t, (_, samples) -> samples.map { t to it } }.sortedBy { it.second.ptsUs }
        for ((t, s) in all) {
            info.set(0, s.data.size, s.ptsUs, if (s.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            muxer.writeSampleData(ids[t], ByteBuffer.wrap(s.data), info)
        }
        muxer.stop(); muxer.release()
    }

    /** AAC (LC) in MP4 with tone bursts at known times. */
    fun aacBursts(context: Context, name: String, rate: Int, channels: Int, seconds: Double, bursts: List<Pair<Double, Double>>, amp: Double = .5): File {
        val f = File(context.cacheDir, name); if (f.exists()) f.delete()
        mux(f, listOf(encodeAac(rate, channels, pcm(rate, channels, seconds, bursts, amp))))
        return f
    }

    /** A video with an audio track (bursts every second), [seconds] long. */
    fun videoWithAudio(context: Context, name: String, seconds: Double): File {
        val f = File(context.cacheDir, name); if (f.exists()) f.delete()
        val bursts = (0 until seconds.toInt()).map { it + .2 to it + .5 }
        mux(f, listOf(encodeVideo(seconds), encodeAac(48_000, 2, pcm(48_000, 2, seconds, bursts, .4))))
        return f
    }
}
