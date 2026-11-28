package com.fluxx.android.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Process
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateMapOf
import com.fluxx.android.model.CompositionLayer
import com.fluxx.android.model.LayerType
import com.fluxx.android.model.PeakBuilder
import com.fluxx.android.model.PeakCachePolicy
import com.fluxx.android.model.PeakChunks
import com.fluxx.android.model.PeakPyramid
import com.fluxx.android.render.AudioTiming
import com.fluxx.android.render.MediaProbes
import com.fluxx.android.render.MediaWork
import com.fluxx.android.render.SourcePcm
import com.fluxx.android.render.SourcePcmCache
import com.fluxx.android.render.UsedRange
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * TL3 B5: audio peaks for the twirl-down's audio row, OUTSIDE the render path (`AudioMixer` and the evaluator are not
 * touched): mono min / max at several zoom levels ([PeakPyramid]), cached on disk under the app cache directory keyed by
 * (uri hash, size, lastModified, format version), LRU-capped at [PeakCachePolicy.CAP_BYTES]. Lazy: only a row that is
 * shown requests it; a row that leaves cancels the work.
 *
 * TLV1.1 C2 (measured: the whole used range was decoded from the source at background priority before ANY pixel showed,
 * so the first pixels took as long as the whole range):
 * - the used range is built in 5 s source chunks ([PeakChunks]): the chunks the row shows FIRST, then outward; partial
 *   peaks are published as chunks land ([Peaks.Ready.complete] false until the range is done), so the first pixels never
 *   wait for the whole range;
 * - a chunk the PCM cache already holds (48 kHz, decoded for playback with its priming rule) is READ from it; otherwise
 *   that chunk is decoded from the source (one decoder, continuing without a seek while chunks are adjacent);
 * - the shown chunks are foreground work (normal thread priority, not paused); the rest is background work as before
 *   (background priority, paused while playing or scrubbing: MP1 B5);
 * - peaks are kept on the PCM cache's 48 kHz timeline (format version 5), so both sources line up sample for sample;
 * - a cancelled job keeps (and stores) what it built: re-expanding shows it at once and builds only the rest.
 * MP1 B2 unchanged: nothing outside the used range plus [UsedRange.MARGIN_US] is read or decoded.
 */
class AudioPeakProvider private constructor(private val context: Context) {
    sealed interface Peaks {
        data object Loading : Peaks
        data object NoAudio : Peaks
        data object Failed : Peaks
        /** [complete]: every wanted chunk is built (false while a progressive build is still filling in). */
        class Ready(val pyramid: PeakPyramid, val complete: Boolean = true) : Peaks
    }

    /** Observable per-uri state (snapshot state: rows redraw when peaks arrive). Written on the main thread only. */
    val states = mutableStateMapOf<String, Peaks>()
    private val jobs = HashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** TLV1.1 C2: the source µs each uri's row shows now (any thread reads; the row writes). */
    private val focus = ConcurrentHashMap<String, LongRange>()

    /** Capability: the layer carries audio (a video with an audio track; a source found to have none drops out). */
    fun hasAudio(layer: CompositionLayer): Boolean {
        val uri = layer.asset?.uri ?: return false
        return layer.type == LayerType.VIDEO && states[uri] !is Peaks.NoAudio
    }

    /** MP1 B2: the windows (source µs) the job in flight for a uri builds. Main thread. */
    private val inFlight = HashMap<String, List<LongRange>>()

    /** TLV1.1 C2: the source range the row shows now (ordering only: the shown chunks are built first). */
    fun focus(uri: String, sourceUs: LongRange?) { if (sourceUs == null) focus.remove(uri) else focus[uri] = sourceUs }

    /**
     * Peaks for the layer's USED range: [sourceInUs] for [durationUs], plus [UsedRange.MARGIN_US] each side (MP1 B2: never
     * the whole track). A widened trim or a moved clip asks again and only the missing part is built. [assetDurationUs]:
     * the source length the playback cache is opened with (null: the PCM cache is not read). The default is the whole source.
     */
    fun request(uri: String, sourceInUs: Long = 0, durationUs: Long = Long.MAX_VALUE / 4, assetDurationUs: Long? = null) {
        val current = states[uri]
        if (current is Peaks.NoAudio) return
        val want = UsedRange.windowUs(sourceInUs, durationUs, Long.MAX_VALUE / 2)
        val have = (current as? Peaks.Ready)?.pyramid?.coveredUs.orEmpty()
        if (UsedRange.covers(have, want)) return
        val running = jobs[uri]?.isActive == true
        if (running && UsedRange.covers(inFlight[uri].orEmpty(), want)) return
        // A restarted job keeps the windows the cancelled one was building (another row of the same source).
        val wants = UsedRange.merge(listOf(want) + if (running) inFlight[uri].orEmpty() else emptyList())
        jobs.remove(uri)?.cancel()
        inFlight[uri] = wants
        if (current == null || current is Peaks.Failed) states[uri] = Peaks.Loading
        MediaProbes.events?.invoke("peaks-request ${MediaProbes.key(uri)} in=$sourceInUs dur=$durationUs")
        val job = scope.launch {
            val result = try { withContext(Dispatchers.IO) { load(uri, wants, assetDurationUs) } } catch (c: CancellationException) { throw c } catch (_: Exception) { Peaks.Failed }
            // A failed extension keeps the peaks already shown.
            if (!(result is Peaks.Failed && states[uri] is Peaks.Ready)) states[uri] = result
        }
        jobs[uri] = job
        job.invokeOnCompletion { scope.launch { if (jobs[uri] === job) { jobs.remove(uri); inFlight.remove(uri) } } }
    }

    /** MP1: a peak build for [uri] is in flight (the benchmarks' runaway guard). Main thread. */
    fun decoding(uri: String): Boolean = jobs[uri]?.isActive == true

    /** A row left while its peaks were still building: stop the work (what was built stays; the rest restarts on the next request). */
    fun cancel(uri: String) {
        val job = jobs.remove(uri) ?: return
        job.cancel(); inFlight.remove(uri)
        if (states[uri] is Peaks.Loading) states.remove(uri)
    }

    private val peakDir by lazy { File(context.cacheDir, PeakCachePolicy.DIR).apply { mkdirs()
        // TLV1.1 C2: peak files of an older format are never read again.
        listFiles().orEmpty().filter { PeakCachePolicy.stale(it.name) }.forEach { it.delete() } } }
    private fun dir() = peakDir.apply { mkdirs() }

    private fun meta(uri: Uri): Pair<Long, Long> {
        if (uri.scheme == "file") { val f = File(requireNotNull(uri.path)); return f.length() to f.lastModified() }
        var size = -1L; var modified = -1L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
                c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 }?.let { modified = c.getLong(it) }
            }
        }
        return size to modified
    }

    private suspend fun publish(uri: String, p: Peaks) = withContext(Dispatchers.Main.immediate + NonCancellable) { states[uri] = p }

    private fun store(file: File, p: PeakPyramid) {
        val tmp = File(dir(), file.name + ".tmp"); p.write(tmp); tmp.renameTo(file)
        val files = dir().listFiles().orEmpty().filter { it.name.endsWith(".peaks") }
        for (name in PeakCachePolicy.evict(files.map { Triple(it.name, it.length(), it.lastModified()) })) File(dir(), name).delete()
    }

    private suspend fun load(uriText: String, wants: List<LongRange>, assetDurationUs: Long?): Peaks {
        val uri = Uri.parse(uriText)
        val (size, modified) = meta(uri)
        val file = File(dir(), PeakCachePolicy.fileName(uriText, size, modified))
        val probe = MediaProbes.events
        val key = MediaProbes.key(uriText)
        val cached = if (file.exists()) PeakPyramid.read(file)?.takeIf { it.sampleRate == AudioTiming.RATE } else null
        if (cached != null && wants.all { UsedRange.covers(cached.coveredUs, it) }) {
            file.setLastModified(System.currentTimeMillis()); probe?.invoke("peaks-cached $key"); return Peaks.Ready(cached) }
        val t0 = System.nanoTime()
        probe?.invoke("peaks-start $key want=${wants.joinToString { "${it.first}..${it.last}" }} cached=${cached?.coveredUs?.joinToString { "${it.first}..${it.last}" } ?: "-"}")
        // What is already stored shows at once (a progressive build fills in the rest).
        if (cached != null) publish(uriText, Peaks.Ready(cached, complete = false))
        val pcm: SourcePcm? = assetDurationUs?.let { d -> runCatching { SourcePcmCache.get(context).get(uriText, d) }.getOrNull() }?.takeIf { !it.noAudio }
        val decoder = Decoder(uri)
        val active = currentCoroutineContext()
        val tid = Process.myTid(); val oldPriority = Process.getThreadPriority(tid)
        val builder = PeakBuilder(AudioTiming.RATE).also { b -> if (cached != null) b.seed(cached) }
        var sourceUs = assetDurationUs ?: Long.MAX_VALUE / 2
        fun finite(r: LongRange) = r.first.coerceAtMost(sourceUs)..minOf(r.last, sourceUs)
        val done = HashSet<Int>()
        var built = 0
        fun covered(): List<LongRange> {
            val fin = wants.map { finite(it) }
            // A range that reaches the end of the source is stored as open-ended (so a later request there is covered).
            val mine = PeakChunks.covered(done, fin).map { if (it.last >= sourceUs - 1) it.first..OPEN_END else it }
            return UsedRange.merge(cached?.coveredUs.orEmpty() + mine)
        }
        try {
            if (assetDurationUs == null) { if (!decoder.open()) return Peaks.NoAudio; sourceUs = decoder.sourceUs }
            val haveBefore = cached?.coveredUs.orEmpty()
            val all = PeakChunks.chunksOf(wants.map { finite(it) })
            // Chunks the stored peaks already cover are not rebuilt.
            done += all.filter { k -> UsedRange.covers(haveBefore, PeakChunks.rangeOf(k)) }
            var after: Int? = null; var lastPublish = System.nanoTime(); var focusDone = false
            var pcmChunks = 0; var decodedChunks = 0; var firstPublishMs = -1L
            while (true) {
                active.ensureActive()
                val missing = all.filter { it !in done }
                val f = focus[uriText]
                val k = PeakChunks.next(missing, f, after) ?: break
                val r = PeakChunks.rangeOf(k)
                val shown = f != null && r.first <= f.last && f.first <= r.last
                // The shown chunks are foreground work; the rest is background work (paused while playing / scrubbing).
                runCatching { Process.setThreadPriority(if (shown) Process.THREAD_PRIORITY_DEFAULT else Process.THREAD_PRIORITY_BACKGROUND) }
                if (!shown) MediaWork.checkpoint({ !active.isActive })
                active.ensureActive()
                val keep = wants.map { finite(it) }.map { AudioTiming.frames(it.first)..AudioTiming.frames(it.last) }
                val a48 = AudioTiming.frames(r.first); val b48 = AudioTiming.frames(r.last + 1)
                val tc = System.nanoTime()
                val fromPcm = pcm != null && runCatching { pcm.covers(a48, minOf(b48, pcm.frames)) && fromPcm(pcm, a48, minOf(b48, pcm.frames), keep, builder) }.getOrDefault(false)
                if (fromPcm) pcmChunks++ else {
                    if (!decoder.opened && !decoder.open()) return Peaks.NoAudio
                    if (assetDurationUs == null) sourceUs = decoder.sourceUs
                    val reachedEnd = decoder.decode(a48, b48, keep, builder, active, !shown)
                    decodedChunks++
                    if (reachedEnd) done += all.filter { it >= k }
                }
                done += k; built++; after = if (fromPcm) null else k
                probe?.invoke("peaks-chunk $key k=$k ${if (fromPcm) "pcm" else "decode"} ms=${(System.nanoTime() - tc) / 1_000_000}${if (shown) " shown" else ""}")
                // Publish: at once while the shown part fills in, then at most every 1.5 s (a publish copies the arrays).
                val now = System.nanoTime()
                val focusNow = focus[uriText]
                val shownLeft = focusNow != null && all.any { it !in done && PeakChunks.rangeOf(it).let { c -> c.first <= focusNow.last && focusNow.first <= c.last } }
                if (shown || (!focusDone && !shownLeft) || now - lastPublish > 1_500_000_000L) {
                    if (!shownLeft) focusDone = true
                    publish(uriText, Peaks.Ready(builder.build(covered()), complete = false)); lastPublish = now
                    if (firstPublishMs < 0) { firstPublishMs = (now - t0) / 1_000_000; probe?.invoke("peaks-first $key ms=$firstPublishMs covered=${covered().joinToString { "${it.first}..${it.last}" }}") }
                }
            }
            val pyramid = builder.build(covered())
            probe?.invoke("peaks-done $key ms=${(System.nanoTime() - t0) / 1_000_000} first=${firstPublishMs}ms chunks pcm=$pcmChunks decoded=$decodedChunks openMs=${decoder.openMs} buckets=${pyramid.levels[0].first.size} covered=${pyramid.coveredUs.joinToString { "${it.first}..${it.last}" }}")
            active.ensureActive()
            store(file, pyramid)
            return Peaks.Ready(pyramid, complete = true)
        } finally {
            decoder.close()
            runCatching { Process.setThreadPriority(tid, oldPriority) }
            // Cancelled part-way: what was built is kept in memory (shown) and stored (a re-expand starts from it).
            if (!active.isActive && built > 0) withContext(NonCancellable) {
                val partial = builder.build(covered())
                runCatching { store(file, partial) }
                publish(uriText, Peaks.Ready(partial, complete = false))
                probe?.invoke("peaks-cancelled $key built=$built covered=${partial.coveredUs.joinToString { "${it.first}..${it.last}" }}")
            }
        }
    }

    /** A chunk read from the PCM cache (48 kHz stereo; mono = the channel of larger magnitude, signed). False if not covered. */
    private fun fromPcm(pcm: SourcePcm, a48: Long, b48: Long, keep: List<LongRange>, b: PeakBuilder): Boolean {
        val block = 48_000; val dst = ShortArray(block * 2); val scratch = ByteArray(block * 4)
        var f = a48
        while (f < b48) {
            val n = minOf(block.toLong(), b48 - f).toInt()
            if (!pcm.covers(f, f + n) || !pcm.read(f, n, dst, scratch)) return false
            for (i in 0 until n) {
                val idx = f + i
                if (keep.none { idx in it }) continue
                val l = dst[2 * i]; val r = dst[2 * i + 1]
                b.addAt(idx, (if (kotlin.math.abs(l.toInt()) >= kotlin.math.abs(r.toInt())) l else r) / 32768f)
            }
            f += n
        }
        return true
    }

    /**
     * The source decoder for chunks the PCM cache does not hold. TL3.1 C (kept): the decoder's ACTUAL output format and the
     * SAME priming trim as playback ([AudioTiming.primingSamples], [AudioTiming.outputFrame]). TLV1.1 C3: then the PCM
     * cache's own stereo fold and 48 kHz resampler ([com.fluxx.android.render.Resampler], whose AUD3 rule writes a buffer
     * that follows the previous one within 100 ms straight after it), and the channel of larger magnitude: the samples and
     * frames playback uses. Before, each buffer sat at its own stamp: on HE-AAC v2 MP4s with an edit list that put the
     * waveform about 21 ms after what plays. A chunk right after the last one continues the same decode (no seek); any
     * other is reached by a seek with 100 ms of pre-roll, output before the chunk dropped (the cache's run rule).
     */
    private inner class Decoder(private val uri: Uri) : Closeable {
        private val extractor = MediaExtractor()
        private var codec: MediaCodec? = null
        var opened = false; private set
        var openMs = 0L; private set
        var sourceUs = Long.MAX_VALUE / 2; private set
        private var mime: String? = null; private var delay: Int? = null; private var firstPts = 0L
        private var rate = 0; private var channels = 0; private var encoding = android.media.AudioFormat.ENCODING_PCM_16BIT
        private var primingUs = 0L
        private var runFloor = -1L; private var pos = -1L; private var inputEnded = false; private var eos = false; private var sourceEnded = false
        private val info = MediaCodec.BufferInfo()
        // TLV1.1 C3: the PCM cache's own fold and resampler (continuous state per run), so a decoded chunk holds the SAME
        // 48 kHz samples at the SAME frames as the cache (AUD3's contiguous placement included), not each buffer at its stamp.
        private var resampler: com.fluxx.android.render.Resampler? = null
        private var shorts = ShortArray(0); private var floats = FloatArray(0); private var stereo = FloatArray(0)

        /** False: the source has no audio track. */
        fun open(): Boolean {
            val t = System.nanoTime()
            extractor.setDataSource(context, uri, null)
            openMs = (System.nanoTime() - t) / 1_000_000
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return false
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            mime = format.getString(MediaFormat.KEY_MIME)
            delay = if (format.containsKey(MediaFormat.KEY_ENCODER_DELAY)) format.getInteger(MediaFormat.KEY_ENCODER_DELAY) else null
            firstPts = extractor.sampleTime
            primingUs = AudioTiming.us(AudioTiming.primingSamples(mime, delay, firstPts), rate)
            if (format.containsKey(MediaFormat.KEY_DURATION)) sourceUs = format.getLong(MediaFormat.KEY_DURATION)
            codec = MediaCodec.createDecoderByType(requireNotNull(mime)).also { it.configure(format, null, null, 0); it.start() }
            opened = true
            return true
        }

        /** Decodes until the output reaches 48 kHz frame [to48], from [from48]; samples in [keep] go to [b]. True: the source ended first. */
        fun decode(from48: Long, to48: Long, keep: List<LongRange>, b: PeakBuilder, active: kotlin.coroutines.CoroutineContext, background: Boolean): Boolean {
            val c = codec ?: return true
            val continuing = runFloor >= 0 && from48 >= runFloor && from48 <= pos && !eos
            if (!continuing) {
                // As the PCM cache's run: from a little before the chunk (pre-roll), output before the chunk dropped; a run at
                // the start begins at the very first sample (as the cache's first run, which does not seek).
                val seekUs = (from48 * 1_000_000L / AudioTiming.RATE + primingUs - 100_000).coerceAtLeast(0)
                if (runFloor >= 0 || seekUs > 0) { c.flush(); extractor.seekTo(if (seekUs > 0) seekUs else firstPts, MediaExtractor.SEEK_TO_PREVIOUS_SYNC) }
                runFloor = from48; pos = from48; inputEnded = false; eos = false; sourceEnded = false
                resampler = com.fluxx.android.render.Resampler(rate)
            }
            // MP1 B2: the input stops shortly past the wanted range that holds this chunk.
            val inputLimitUs = keep.firstOrNull { from48 in it || to48 - 1 in it }?.let { it.last * 1_000_000L / AudioTiming.RATE + primingUs + 200_000 } ?: Long.MAX_VALUE
            while (pos < to48 && !eos) {
                active.ensureActive()
                if (background) MediaWork.checkpoint({ !active.isActive })
                while (!inputEnded) {
                    val i = c.dequeueInputBuffer(0)
                    if (i < 0) break
                    val n = extractor.readSampleData(requireNotNull(c.getInputBuffer(i)), 0)
                    if (n < 0 || extractor.sampleTime > inputLimitUs) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true; sourceEnded = n < 0 }
                    else { c.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
                }
                val o = c.dequeueOutputBuffer(info, if (inputEnded) 2000 else 500)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out = c.outputFormat
                    rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    primingUs = AudioTiming.us(AudioTiming.primingSamples(mime, delay, firstPts), rate)
                    resampler = com.fluxx.android.render.Resampler(rate)
                } else if (o >= 0) {
                    val buffer = requireNotNull(c.getOutputBuffer(o)).order(ByteOrder.LITTLE_ENDIAN)
                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    val isFloat = encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    val n = info.size / (channels * if (isFloat) 4 else 2)
                    if (n > 0) {
                        // Bulk, as the PCM cache: the buffer into a primitive array, the stereo fold, then 48 kHz.
                        if (stereo.size < n * 2) stereo = FloatArray(n * 2)
                        if (isFloat) { if (floats.size < n * channels) floats = FloatArray(n * channels); buffer.asFloatBuffer().get(floats, 0, n * channels)
                            com.fluxx.android.render.AudioFold.stereo(floats, n, channels, stereo) }
                        else { if (shorts.size < n * channels) shorts = ShortArray(n * channels); buffer.asShortBuffer().get(shorts, 0, n * channels)
                            com.fluxx.android.render.AudioFold.stereo(shorts, n, channels, stereo) }
                        val firstOut = AudioTiming.outputFrame(info.presentationTimeUs, primingUs)
                        resampler!!.run(stereo, n, firstOut) { o48, data, count ->
                            for (j in 0 until count) {
                                val idx = o48 + j
                                if (idx < runFloor || keep.none { idx in it }) continue
                                val l = data[2 * j]; val r = data[2 * j + 1]
                                b.addAt(idx, if (kotlin.math.abs(l) >= kotlin.math.abs(r)) l else r)
                            }
                            if (o48 + count > pos) pos = o48 + count
                        }
                    }
                    // An input stopped at the range's end counts as the end too (nothing more is wanted after it).
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                    c.releaseOutputBuffer(o, false)
                }
            }
            // Only the SOURCE ending (not an input stopped at a wanted range's end) means no later chunk has audio.
            return eos && sourceEnded && pos < to48
        }

        override fun close() {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release(); extractor.release()
        }
    }

    companion object {
        /** MP1 B2: a covered range that reaches the end of the source. */
        const val OPEN_END = Long.MAX_VALUE / 2
        @Volatile private var instance: AudioPeakProvider? = null
        fun get(context: Context): AudioPeakProvider = instance ?: synchronized(this) {
            instance ?: AudioPeakProvider(context.applicationContext).also { instance = it }
        }
    }
}
