package com.fluxx.android.render

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Process
import com.fluxx.android.model.*
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * AUD1 B1-B4: one source's decoded audio, 48 kHz stereo 16-bit, cached on disk (decoded ONCE per source; edits only re-mix).
 * A worker decodes progressively in 1 s chunks from wherever it is needed (a seek into a not-yet-decoded region restarts
 * it there). Bulk conversion (no per-sample ByteBuffer calls; reused primitive arrays), resampled only when the rate
 * differs, the AAC priming trimmed ([AudioTiming.primingSamples]) so audio starts at its true time.
 */
class SourcePcm internal constructor(private val context: Context, val uri: String, val file: File, durationUs: Long) : Closeable {
    val frames: Long = AudioTiming.frames(durationUs) + AudioTiming.RATE / 10
    private val coverageFile = File(file.path.removeSuffix(".pcm") + ".cov")
    private val lock = Object()
    private val coverage: ChunkCoverage
    @Volatile var noAudio = false; private set
    @Volatile private var closed = false
    private val wanted = AtomicLong(-1)          // the frame a reader needs next (restart target), -1 = none
    private val runFrom = AtomicLong(-1)          // the current decode run's start frame
    private val runAt = AtomicLong(-1)            // how far the current run has written
    private var worker: Thread? = null
    private val reader: RandomAccessFile
    @Volatile var primingUs = 0L; private set

    init {
        val fresh = !file.exists() || file.length() != PcmCachePolicy.bytes(frames)
        if (fresh) { RandomAccessFile(file, "rw").use { it.setLength(PcmCachePolicy.bytes(frames)) }; coverageFile.delete() }
        val saved = if (!fresh && coverageFile.exists()) runCatching { java.util.BitSet.valueOf(coverageFile.readBytes()) }.getOrNull() else null
        coverage = ChunkCoverage(PcmCachePolicy.chunks(frames), saved)
        reader = RandomAccessFile(file, "r")
        file.setLastModified(System.currentTimeMillis())
    }

    val complete get() = synchronized(lock) { coverage.complete }
    fun covers(from: Long, to: Long) = synchronized(lock) { coverage.covers(from.coerceAtLeast(0), to.coerceAtMost(frames)) }

    /** Make sure the region from [frame] gets decoded (restarting the worker there if it is elsewhere). */
    fun ensure(frame: Long) {
        if (noAudio || closed) return
        val chunk = PcmCachePolicy.chunkOf(frame.coerceIn(0, frames - 1))
        val gap = synchronized(lock) { coverage.firstGap(chunk) }
        if (gap < 0) return
        val gapFrame = gap.toLong() * PcmCachePolicy.CHUNK_FRAMES
        val from = runFrom.get(); val at = runAt.get()
        // A run already heading there (started at or before it and not more than 2 s short of it) is fine.
        if (worker?.isAlive == true && from in 0..gapFrame && gapFrame - at <= 2L * PcmCachePolicy.CHUNK_FRAMES) return
        wanted.set(gapFrame)
        synchronized(this) { if (worker?.isAlive != true) startWorker() }
    }

    /** Reads [count] frames from [frame] into [dst] (interleaved stereo); uncovered frames read as silence. Returns covered. */
    fun read(frame: Long, count: Int, dst: ShortArray, scratch: ByteArray): Boolean {
        java.util.Arrays.fill(dst, 0, count * 2, 0)
        if (frame >= frames || frame + count <= 0) return true
        val from = frame.coerceAtLeast(0); val to = minOf(frame + count, frames)
        val ok = covers(from, to)
        if (!ok) { ensure(from); return false }
        val n = (to - from).toInt()
        synchronized(reader) { reader.seek(from * 4); reader.readFully(scratch, 0, n * 4) }
        ByteBuffer.wrap(scratch, 0, n * 4).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(dst, ((from - frame) * 2).toInt(), n * 2)
        return true
    }

    private fun startWorker() {
        worker = Thread({ runCatching { decodeLoop() } }, "FluxxAudioDecode").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = true; start() }
    }

    /** Decodes from [wanted] forward until everything is covered (or a new target arrives, which restarts the run). */
    private fun decodeLoop() {
        while (!closed) {
            val target = wanted.getAndSet(-1).takeIf { it >= 0 } ?: synchronized(lock) { coverage.firstGap(0) }.let {
                if (it < 0) { persist(); return } else it.toLong() * PcmCachePolicy.CHUNK_FRAMES }
            decodeRun(target)
            if (noAudio) return
        }
    }

    private fun decodeRun(startFrame: Long) {
        val ex = MediaExtractor(); var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, Uri.parse(uri), null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            if (track == null) { noAudio = true; return }
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val delay = if (fmt.containsKey(MediaFormat.KEY_ENCODER_DELAY)) fmt.getInteger(MediaFormat.KEY_ENCODER_DELAY) else null
            val firstPts = ex.sampleTime
            primingUs = AudioTiming.us(AudioTiming.primingSamples(mime, delay, firstPts), rate)
            // Start a little before the chunk (decoder pre-roll), at the sync sample at or before it.
            val startUs = (startFrame * 1_000_000L / AudioTiming.RATE + primingUs - 100_000).coerceAtLeast(0)
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val c = MediaCodec.createDecoderByType(requireNotNull(mime)); codec = c
            c.configure(fmt, null, null, 0); c.start()
            runFrom.set(startFrame); runAt.set(startFrame)
            val writer = RandomAccessFile(file, "rw")
            val info = MediaCodec.BufferInfo()
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var shorts = ShortArray(0); var floats = FloatArray(0); var stereo = FloatArray(0)
            var out = ShortArray(0); var bytes = ByteArray(0)
            var resampler = Resampler(rate)
            var inputEnded = false; var ended = false
            writer.use {
                while (!ended && !closed) {
                    // A reader needs a region this run will not reach soon: restart there.
                    val w = wanted.get()
                    if (w >= 0 && (w < runFrom.get() || w - runAt.get() > 2L * PcmCachePolicy.CHUNK_FRAMES)) return
                    while (!inputEnded) {
                        val i = c.dequeueInputBuffer(0); if (i < 0) break
                        val n = ex.readSampleData(requireNotNull(c.getInputBuffer(i)), 0)
                        if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                        else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                    val o = c.dequeueOutputBuffer(info, 5_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = c.outputFormat
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        primingUs = AudioTiming.us(AudioTiming.primingSamples(mime, delay, firstPts), rate)
                        resampler = Resampler(rate)
                    } else if (o >= 0) {
                        ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val buf = requireNotNull(c.getOutputBuffer(o)).order(ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val isFloat = encoding == AudioFormat.ENCODING_PCM_FLOAT
                        val n = info.size / (channels * if (isFloat) 4 else 2)
                        if (n > 0) {
                            // Bulk: the whole buffer into a primitive array, then a stereo fold on arrays.
                            if (stereo.size < n * 2) stereo = FloatArray(n * 2)
                            if (isFloat) { if (floats.size < n * channels) floats = FloatArray(n * channels); buf.asFloatBuffer().get(floats, 0, n * channels)
                                AudioFold.stereo(floats, n, channels, stereo) }
                            else { if (shorts.size < n * channels) shorts = ShortArray(n * channels); buf.asShortBuffer().get(shorts, 0, n * channels)
                                AudioFold.stereo(shorts, n, channels, stereo) }
                            val firstOut = AudioTiming.outputFrame(info.presentationTimeUs, primingUs)
                            val produced = resampler.run(stereo, n, firstOut) { o48, data, count ->
                                // Clip to the file, drop what lies before 0.
                                // Nothing before 0, and nothing before this run's start (a decoder's post-seek pre-roll must
                                // not overwrite good audio decoded by an earlier run).
                                var from = o48; var off = 0; var cnt = count
                                val floor = maxOf(0L, runFrom.get())
                                if (from < floor) { off = (floor - from).toInt().coerceAtMost(cnt); cnt -= off; from = floor }
                                if (from + cnt > frames) cnt = (frames - from).toInt().coerceAtLeast(0)
                                if (cnt > 0) {
                                    if (out.size < cnt * 2) out = ShortArray(cnt * 2)
                                    AudioFold.toShorts(data, off, cnt, out)
                                    if (bytes.size < cnt * 4) bytes = ByteArray(cnt * 4)
                                    ByteBuffer.wrap(bytes, 0, cnt * 4).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(out, 0, cnt * 2)
                                    it.seek(from * 4); it.write(bytes, 0, cnt * 4)
                                    if (from + cnt > runAt.get()) runAt.set(from + cnt)
                                }
                            }
                            if (produced > 0) synchronized(lock) {
                                if (coverage.advance(runFrom.get(), runAt.get(), frames) > 0) Unit
                            }
                        }
                        c.releaseOutputBuffer(o, false)
                    }
                }
                if (ended) synchronized(lock) { coverage.advance(runFrom.get(), frames, frames) }
            }
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release(); ex.release()
            persist()
        }
    }

    private fun persist() { runCatching { val b = synchronized(lock) { coverage.snapshot() }; coverageFile.writeBytes(b.toByteArray()) } }

    override fun close() { closed = true; runCatching { synchronized(reader) { reader.close() } }; persist() }
}

/** AUD1 B3: array-level helpers (no per-sample buffer calls, no allocation). */
internal object AudioFold {
    /** Interleaved [channels] 16-bit to stereo floats (-1..1): mono duplicates; more channels fold with the mixer's weights. */
    fun stereo(src: ShortArray, n: Int, channels: Int, dst: FloatArray) {
        val k = 1f / 32768f
        when (channels) {
            1 -> for (i in 0 until n) { val v = src[i] * k; dst[i * 2] = v; dst[i * 2 + 1] = v }
            2 -> for (i in 0 until n * 2) dst[i] = src[i] * k
            else -> for (i in 0 until n) { var l = 0f; var r = 0f; val b = i * channels
                for (c in 0 until channels) { if (c % 2 == 0) l += src[b + c] * k else r += src[b + c] * k }
                val w = 2f / channels; dst[i * 2] = l * w; dst[i * 2 + 1] = r * w }
        }
    }
    fun stereo(src: FloatArray, n: Int, channels: Int, dst: FloatArray) {
        when (channels) {
            1 -> for (i in 0 until n) { val v = src[i]; dst[i * 2] = v; dst[i * 2 + 1] = v }
            2 -> System.arraycopy(src, 0, dst, 0, n * 2)
            else -> for (i in 0 until n) { var l = 0f; var r = 0f; val b = i * channels
                for (c in 0 until channels) { if (c % 2 == 0) l += src[b + c] else r += src[b + c] }
                val w = 2f / channels; dst[i * 2] = l * w; dst[i * 2 + 1] = r * w }
        }
    }
    fun toShorts(src: FloatArray, offFrames: Int, frames: Int, dst: ShortArray) {
        val base = offFrames * 2
        for (i in 0 until frames * 2) dst[i] = (src[base + i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
    }
}

/**
 * AUD1 B3: stereo linear resampler to 48 kHz with continuous state across buffers; a pass-through copy when the source is
 * already 48 kHz. [emit] receives (first 48 kHz frame, stereo floats, frame count) with reused arrays.
 */
internal class Resampler(private val rate: Int) {
    private var out = FloatArray(0)
    private var prevL = 0f; private var prevR = 0f; private var havePrev = false
    private var phase = 0.0     // the next output frame's position, in source frames relative to the current buffer
    private var nextOut = Long.MIN_VALUE
    fun run(src: FloatArray, n: Int, firstOut: Long, emit: (Long, FloatArray, Int) -> Unit): Int {
        if (rate == AudioTiming.RATE) { emit(firstOut, src, n); return n }
        val step = rate.toDouble() / AudioTiming.RATE
        if (nextOut == Long.MIN_VALUE || kotlin.math.abs(firstOut - nextOut) > AudioTiming.RATE / 10) { nextOut = firstOut; phase = 0.0; havePrev = false }
        val cap = (n / step).toInt() + 2
        if (out.size < cap * 2) out = FloatArray(cap * 2)
        var count = 0
        while (phase < n) {
            val i = phase.toInt(); val f = (phase - i).toFloat()
            val l0 = if (i == 0) (if (havePrev) prevL else src[0]) else src[(i - 1) * 2]
            val r0 = if (i == 0) (if (havePrev) prevR else src[1]) else src[(i - 1) * 2 + 1]
            val l1 = src[i * 2]; val r1 = src[i * 2 + 1]
            out[count * 2] = l0 + (l1 - l0) * f; out[count * 2 + 1] = r0 + (r1 - r0) * f
            count++; phase += step
        }
        phase -= n
        prevL = src[(n - 1) * 2]; prevR = src[(n - 1) * 2 + 1]; havePrev = true
        val first = nextOut; nextOut += count
        emit(first, out, count)
        return count
    }
}

/** AUD1 B2: the decoded-PCM cache (one [SourcePcm] per source; LRU-capped files under cacheDir/aud1-pcm). */
class SourcePcmCache private constructor(private val context: Context) {
    private val open = HashMap<String, SourcePcm>()
    private val dir = File(context.cacheDir, PcmCachePolicy.DIR).apply {
        mkdirs()
        listFiles().orEmpty().filter { PcmCachePolicy.stale(it.name) }.forEach { it.delete() }
        // The old whole-file mixer's temporary mixes (left behind when its process died) are no longer used.
        context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("preview_audio_") && it.name.endsWith(".pcm") }.forEach { it.delete() }
    }

    @Synchronized fun get(uri: String, durationUs: Long): SourcePcm {
        open[uri]?.let { return it }
        val u = Uri.parse(uri)
        val (size, modified) = if (u.scheme == "file") File(requireNotNull(u.path)).let { it.length() to it.lastModified() } else meta(u)
        val f = File(dir, PcmCachePolicy.fileName(uri, size, modified))
        val keep = open.values.map { it.file.name }.toSet() + f.name
        val files = dir.listFiles().orEmpty().filter { it.name.endsWith(".pcm") }
        for (name in PcmCachePolicy.evict(files.map { Triple(it.name, it.length(), it.lastModified()) }, keep)) {
            File(dir, name).delete(); File(dir, name.removeSuffix(".pcm") + ".cov").delete()
        }
        return SourcePcm(context, uri, f, durationUs).also { open[uri] = it }
    }

    private fun meta(u: Uri): Pair<Long, Long> {
        var size = -1L; var modified = -1L
        runCatching { context.contentResolver.query(u, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(android.provider.OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
                c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 }?.let { modified = c.getLong(it) }
            } } }
        return size to modified
    }

    companion object {
        @Volatile private var instance: SourcePcmCache? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: SourcePcmCache(context.applicationContext).also { instance = it } }
    }
}

/**
 * AUD1 B1 / B2: mixes the composition's audio block by block, straight from the per-source caches (no whole-file mix; an
 * edit only changes what is mixed next). Same layers, gains and headroom as before ([AudioMixPolicy], the visible,
 * unmuted video layers), mapped start + (t - in) at 1x.
 */
class BlockMixer(context: Context, project: ProjectDocument) {
    private class Voice(val pcm: SourcePcm, val startFrame: Long, val endFrame: Long, val inFrame: Long, val gain: Float)
    private val duration = project.composition.resolvedDurationUs
    val totalFrames = AudioTiming.frames(duration)
    private val voices: List<Voice>
    private val tmp = ShortArray(StreamingWindow.BLOCK_FRAMES * 2 * 8)
    private val scratch = ByteArray(StreamingWindow.BLOCK_FRAMES * 4 * 8)
    private val acc = FloatArray(StreamingWindow.BLOCK_FRAMES * 2 * 8)
    init {
        val cache = SourcePcmCache.get(context)
        val headroom = AudioMixPolicy.headroom(project)
        voices = project.composition.layers.filter { it.visible && it.type == LayerType.VIDEO && !it.muted && it.audioGain > 0 && it.timing.endUs != null && it.asset?.uri != null }
            .map { l -> Voice(cache.get(l.asset!!.uri!!, l.asset.durationUs ?: (l.timing.sourceInUs + l.timing.durationUs!!)),
                AudioTiming.frames(l.timing.startUs), AudioTiming.frames(minOf(l.timing.endUs!!, duration)), AudioTiming.frames(l.timing.sourceInUs), l.audioGain * headroom) }
    }
    val hasAudio get() = voices.isNotEmpty()

    /** Every active source has [ahead] frames decoded from [frame] (and is asked to decode them otherwise). */
    fun ready(frame: Long, ahead: Long): Boolean = voices.all { v ->
        val a = maxOf(frame, v.startFrame); val b = minOf(frame + ahead, v.endFrame)
        if (b <= a || v.pcm.noAudio) true else { val s = v.inFrame + (a - v.startFrame); val e = v.inFrame + (b - v.startFrame)
            v.pcm.covers(s, e).also { ok -> if (!ok) v.pcm.ensure(s) } }
    }

    /** Mixes [count] (<= 8 blocks) frames from [frame] into [out] (interleaved 16-bit stereo). */
    fun mix(frame: Long, count: Int, out: ShortArray) {
        java.util.Arrays.fill(acc, 0, count * 2, 0f)
        for (v in voices) {
            val a = maxOf(frame, v.startFrame); val b = minOf(frame + count, v.endFrame)
            if (b <= a || v.pcm.noAudio) continue
            val n = (b - a).toInt()
            v.pcm.read(v.inFrame + (a - v.startFrame), n, tmp, scratch)
            val off = ((a - frame) * 2).toInt(); val g = v.gain / 32768f
            for (i in 0 until n * 2) acc[off + i] += tmp[i] * g
        }
        for (i in 0 until count * 2) out[i] = (acc[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
    }
}

/**
 * AUD1 B1 / B5 / B6: streaming playback. A feeding thread at audio priority mixes 20 ms blocks from the caches into the
 * AudioTrack, starting once [StreamingWindow.READY_US] is decoded at the start (and the first frame is presented: the
 * [PlaybackGate]). The clock is the audio as HEARD ([AvClock]: AudioTimestamp, else head minus latency).
 */
internal class StreamingPlayback(context: Context, project: ProjectDocument, startUs: Long,
    private val gate: PlaybackGate = PlaybackGate({ PreviewFrames.count }, System::nanoTime)) : Closeable {
    val startFrame = AudioTiming.frames(startUs)
    private val mixer = BlockMixer(context, project)
    private val minBuffer = AudioTrack.getMinBufferSize(AudioTiming.RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
    /** B6: 2x the platform minimum (measured: no underruns with the UI busy); the latency it adds is compensated by the clock. */
    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(AudioTiming.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
        .setBufferSizeInBytes(maxOf(minBuffer * 2, StreamingWindow.BLOCK_FRAMES * 4 * 4))
        .setTransferMode(AudioTrack.MODE_STREAM).build()
    private val running = AtomicBoolean(true)
    @Volatile var error: Throwable? = null; private set
    @Volatile private var written = 0L
    @Volatile var ready = !mixer.hasAudio || mixer.ready(startFrame, AudioTiming.frames(StreamingWindow.READY_US)); private set
    private var playing = false
    private val stamp = AudioTimestamp()
    private val latencyFrames: Long = runCatching { AudioTrack::class.java.getMethod("getLatency").invoke(track) as Int }.getOrNull()
        ?.let { it.toLong() * AudioTiming.RATE / 1000 } ?: (track.bufferSizeInFrames.toLong())
    private val thread = Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            val block = ShortArray(StreamingWindow.BLOCK_FRAMES * 2)
            while (running.get() && !ready) { ready = mixer.ready(startFrame, AudioTiming.frames(StreamingWindow.READY_US)); if (!ready) Thread.sleep(5) }
            var frame = startFrame
            while (running.get() && frame < mixer.totalFrames) {
                val n = minOf(StreamingWindow.BLOCK_FRAMES.toLong(), mixer.totalFrames - frame).toInt()
                // Keep the decoder ahead of the play position (no-op when cached).
                mixer.ready(frame, AudioTiming.frames(2_000_000))
                mixer.mix(frame, n, block)
                var off = 0
                while (running.get() && off < n * 2) { val w = track.write(block, off, n * 2 - off, AudioTrack.WRITE_BLOCKING); check(w >= 0) { "Audio output failed" }; off += w }
                frame += n; written += n
            }
        } catch (e: Exception) { if (running.get()) error = e }
    }, "FluxxAudio").also { check(track.state == AudioTrack.STATE_INITIALIZED); it.start() }

    /** Read on the preview worker only (it also starts the track once audio is ready and the gate opens). */
    val positionUs: Long get() {
        if (!playing && ready && written > 0 && gate.poll()) { track.play(); playing = true; PreviewFrames.testPlayback?.invoke("track-play") }
        if (!playing) return AvClock.us(startFrame)
        val head = track.playbackHeadPosition.toLong() and 0xffffffffL
        val ts = if (track.getTimestamp(stamp)) stamp else null
        val now = System.nanoTime()
        val us = AvClock.us(AvClock.positionFrames(startFrame, head, now, ts?.framePosition, ts?.nanoTime, latencyFrames))
        PreviewFrames.testClock?.invoke(now, head, ts != null && ts.nanoTime > 0, us)
        return us
    }
    val underruns get() = track.underrunCount
    /** AUD2 B1: the track runs (the gate opened): [positionUs] now moves with what is heard. Preview worker only. */
    val started get() = playing

    init { PreviewFrames.testPlayback?.invoke("playback-create start=$startUs") }

    override fun close() {
        PreviewFrames.testPlayback?.invoke("playback-close")
        running.set(false)
        runCatching { track.pause(); track.flush() }
        thread.join(500)
        runCatching { track.release() }
    }
}
