package com.fluxx.android.ui

import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.fluxx.android.model.*
import com.fluxx.android.render.PreviewController
import java.io.File
import java.nio.ByteOrder

/**
 * AUD1 benchmark helpers. The benchmark file stays where it is on the phone (read in place; never copied). Documents are
 * in memory only (never a user project).
 */
object Aud1Bench {
    const val PREFIX = "Calc - Is There Someone Else"

    /** What the last [file] lookup saw, for the skip message. */
    var lookupLog = ""

    /**
     * The benchmark file, found by glob on its prefix under every storage volume's Download/Seal (the volumes come from
     * StorageManager: listing /storage itself is not allowed under scoped storage); null when absent or unreadable.
     */
    fun file(context: android.content.Context): File? {
        val log = StringBuilder()
        val sm = context.getSystemService(android.os.storage.StorageManager::class.java)
        val volumes = sm.storageVolumes.mapNotNull { it.directory } + File("/storage").listFiles().orEmpty()
        for (v in volumes.distinct()) {
            val dir = File(v, "Download/Seal")
            val names = dir.listFiles()
            log.append(dir.path).append(": ").append(names?.size?.toString() ?: "unlistable").append("; ")
            val f = names?.firstOrNull { it.name.startsWith(PREFIX) } ?: continue
            val readable = runCatching { f.inputStream().use { it.read() } >= 0 }.getOrDefault(false)
            log.append("found ").append(f.length()).append(" bytes, readable=").append(readable).append("; ")
            if (readable) { lookupLog = log.toString(); return f }
        }
        lookupLog = log.toString()
        return null
    }

    data class Media(val durationUs: Long, val width: Int, val height: Int, val fps: Int, val audioFormat: MediaFormat)

    fun media(f: File): Media {
        val ex = MediaExtractor(); ex.setDataSource(f.path)
        try {
            var dur = 0L; var w = 0; var h = 0; var fps = 20; var audio: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i); val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (fmt.containsKey(MediaFormat.KEY_DURATION)) dur = maxOf(dur, fmt.getLong(MediaFormat.KEY_DURATION))
                if (mime.startsWith("video/")) { w = fmt.getInteger(MediaFormat.KEY_WIDTH); h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                    if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) fps = runCatching { Math.round(fmt.getNumber(MediaFormat.KEY_FRAME_RATE)!!.toFloat()) }.getOrDefault(20) }
                if (mime.startsWith("audio/")) audio = fmt
            }
            return Media(dur, w, h, fps, requireNotNull(audio))
        } finally { ex.release() }
    }

    /** An in-memory document: the file as a VIDEO layer, plus [extra] keyed solids (for the twirl-down cases). */
    fun project(f: File, m: Media, extra: Int = 0): ProjectDocument {
        val rate = FrameRate(m.fps, 1)
        val video = CompositionLayer(1, LayerType.VIDEO, name = "bench", zOrder = 10, timing = ClipTiming(0, 0, m.durationUs),
            asset = AssetReference("aud1-bench", android.net.Uri.fromFile(f).toString(), access = MediaAccess.AVAILABLE, durationUs = m.durationUs))
        val solids = (0 until extra).map { i -> CompositionLayer(10L + i, LayerType.SOLID, name = "S$i", zOrder = i, timing = ClipTiming(0, 0, m.durationUs),
            transform = Transform(opacity = .05f),
            animTransform = AnimatableTransform(rotation = AnimatableProperty1D(true, keyframes = FrozenList.of((0..40).map { k -> Keyframe1D(rate.frameTimeUs(k * 60L), k * 9f) }))))
        }
        // The canvas: 4:3 within the 1080p preview budget.
        return ProjectDocument(Composition(width = 1440, height = 1080, durationUs = m.durationUs, frameRate = rate, layers = FrozenList.of(listOf(video) + solids)))
    }

    /** Decodes the file's audio (test-side, independently of the app) to mono peaks per 10 ms, in decoded (pts) time. */
    class Decoded(val windowUs: Long, val energy: FloatArray, val decodeMs: Long, val firstPtsUs: Long, val format: MediaFormat)

    fun decodeEnergy(f: File): Decoded {
        val ex = MediaExtractor(); ex.setDataSource(f.path)
        val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
        ex.selectTrack(track); val fmt = ex.getTrackFormat(track)
        val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!); c.configure(fmt, null, null, 0); c.start()
        val window = 10_000L; val energy = ArrayList<Float>(16_000)
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var float = false; var firstPts = Long.MIN_VALUE
        val info = MediaCodec.BufferInfo(); var inEnd = false; var end = false
        val t0 = System.nanoTime()
        while (!end) {
            while (!inEnd) { val i = c.dequeueInputBuffer(0); if (i < 0) break
                val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEnd = true } else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() } }
            val o = c.dequeueOutputBuffer(info, 1000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { val of = c.outputFormat; rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                float = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT }
            else if (o >= 0) {
                if (firstPts == Long.MIN_VALUE && info.size > 0) firstPts = info.presentationTimeUs
                val b = c.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN); b.position(info.offset); b.limit(info.offset + info.size)
                val frames = info.size / (ch * if (float) 4 else 2)
                for (k in 0 until frames) {
                    var s = 0f; for (x in 0 until ch) { val v = if (float) b.float else b.short / 32768f; s += v * v }
                    val t = info.presentationTimeUs + k * 1_000_000L / rate
                    if (t < 0) continue
                    val w = (t / window).toInt(); while (energy.size <= w) energy += 0f
                    energy[w] = energy[w] + s / ch
                }
                end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0; c.releaseOutputBuffer(o, false)
            }
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        c.stop(); c.release(); ex.release()
        return Decoded(window, energy.toFloatArray(), ms, firstPts, fmt)
    }

    /** Beat onsets (decoded / pts time, us) by positive energy flux above mean + 2.5 sd, at least 250 ms apart. */
    fun beats(d: Decoded): List<Long> {
        val e = d.energy; if (e.size < 3) return emptyList()
        val flux = FloatArray(e.size) { if (it == 0) 0f else maxOf(0f, e[it] - e[it - 1]) }
        val mean = flux.average(); val sd = kotlin.math.sqrt(flux.map { (it - mean) * (it - mean) }.average())
        val th = mean + 2.5 * sd
        val out = ArrayList<Long>(); var last = -1_000_000L
        for (i in 1 until flux.size - 1) {
            val t = i * d.windowUs
            if (flux[i] > th && flux[i] >= flux[i - 1] && flux[i] >= flux[i + 1] && t - last >= 250_000) { out += t; last = t }
        }
        return out
    }

    // ---- The running playback, read by reflection (no behaviour change) -------------------------------------------------
    fun track(c: PreviewController): AudioTrack? {
        val pb = PreviewController::class.java.getDeclaredField("playback").apply { isAccessible = true }.get(c) ?: return null
        return pb.javaClass.getDeclaredField("track").apply { isAccessible = true }.get(pb) as AudioTrack
    }
    /** The playback's first composition frame (the position Play started from), in 48 kHz frames. */
    fun startFrame(c: PreviewController): Long? {
        val pb = PreviewController::class.java.getDeclaredField("playback").apply { isAccessible = true }.get(c) ?: return null
        return runCatching { pb.javaClass.getDeclaredField("startFrame").apply { isAccessible = true }.getLong(pb) }.getOrNull()
    }
    /** AudioTrack.getLatency() (hidden API) in ms, or null when the platform refuses it. */
    fun latencyMs(t: AudioTrack): Int? = runCatching { AudioTrack::class.java.getMethod("getLatency").invoke(t) as Int }.getOrNull()
    fun timestamp(t: AudioTrack): Pair<Long, Long>? { val ts = AudioTimestamp(); return if (t.getTimestamp(ts)) ts.framePosition to ts.nanoTime else null }

    fun pct(v: List<Double>, p: Double) = if (v.isEmpty()) Double.NaN else v.sorted()[((v.size - 1) * p).toInt()]
    fun stats(v: List<Double>) = "n=%d p50=%.1f p95=%.1f p99=%.1f max=%.1f".format(v.size, pct(v, .5), pct(v, .95), pct(v, .99), v.maxOrNull() ?: Double.NaN)
}
