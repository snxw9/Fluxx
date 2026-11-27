package com.fluxx.android.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateMapOf
import com.fluxx.android.model.CompositionLayer
import com.fluxx.android.model.LayerType
import com.fluxx.android.model.PeakBuilder
import com.fluxx.android.model.PeakCachePolicy
import com.fluxx.android.model.PeakPyramid
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteOrder

/**
 * TL3 B5: audio peaks for the twirl-down's audio row, OUTSIDE the render path (`AudioMixer` and the evaluator are not
 * touched). Decodes a source's audio off the main thread into a [PeakPyramid] (mono min / max at several zoom levels),
 * caches it on disk under the app cache directory keyed by (uri hash, size, lastModified, format version), LRU-capped at
 * [PeakCachePolicy.CAP_BYTES]. Lazy: only a row that is shown (an expanded, visible layer) requests it; cancellable (a row
 * that leaves while decoding cancels its job). The decoder follows `AudioMixer`'s extractor / codec pattern (there is no
 * shared decoder utility to reuse).
 */
class AudioPeakProvider private constructor(private val context: Context) {
    sealed interface Peaks {
        data object Loading : Peaks
        data object NoAudio : Peaks
        data object Failed : Peaks
        class Ready(val pyramid: PeakPyramid) : Peaks
    }

    /** Observable per-uri state (snapshot state: rows redraw when peaks arrive). Written on the main thread only. */
    val states = mutableStateMapOf<String, Peaks>()
    private val jobs = HashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Capability: the layer carries audio (a video with an audio track; a source found to have none drops out). */
    fun hasAudio(layer: CompositionLayer): Boolean {
        val uri = layer.asset?.uri ?: return false
        return layer.type == LayerType.VIDEO && states[uri] !is Peaks.NoAudio
    }

    /** MP1 B2: the windows (source µs) the job in flight for a uri decodes. Main thread. */
    private val inFlight = HashMap<String, List<LongRange>>()

    /**
     * Peaks for the layer's USED range: [sourceInUs] for [durationUs], plus [UsedRange.MARGIN_US] each side (MP1 B2: never
     * the whole track). A widened trim or a moved clip asks again and only the missing part is decoded. The work runs at
     * background priority and pauses while playing or scrubbing (MP1 B5). The default (no range) is the whole source.
     */
    fun request(uri: String, sourceInUs: Long = 0, durationUs: Long = Long.MAX_VALUE / 4) {
        val current = states[uri]
        if (current is Peaks.NoAudio) return
        val want = com.fluxx.android.render.UsedRange.windowUs(sourceInUs, durationUs, Long.MAX_VALUE / 2)
        val have = (current as? Peaks.Ready)?.pyramid?.coveredUs.orEmpty()
        if (com.fluxx.android.render.UsedRange.covers(have, want)) return
        val running = jobs[uri]?.isActive == true
        if (running && com.fluxx.android.render.UsedRange.covers(inFlight[uri].orEmpty(), want)) return
        // A restarted job keeps the windows the cancelled one was decoding (another row of the same source).
        val wants = com.fluxx.android.render.UsedRange.merge(listOf(want) + if (running) inFlight[uri].orEmpty() else emptyList())
        jobs.remove(uri)?.cancel()
        inFlight[uri] = wants
        if (current == null || current is Peaks.Failed) states[uri] = Peaks.Loading
        jobs[uri] = scope.launch {
            val result = try { withContext(Dispatchers.IO) { load(uri, wants) } } catch (c: CancellationException) { throw c } catch (_: Exception) { Peaks.Failed }
            // A failed extension keeps the peaks already shown.
            if (!(result is Peaks.Failed && states[uri] is Peaks.Ready)) states[uri] = result
            jobs.remove(uri); inFlight.remove(uri)
        }
    }

    /** MP1: a peak decode for [uri] is in flight (the benchmarks' runaway guard). Main thread. */
    fun decoding(uri: String): Boolean = jobs[uri]?.isActive == true

    /** A row left while its peaks were still decoding: stop the work (it restarts on the next request). */
    fun cancel(uri: String) {
        val job = jobs.remove(uri) ?: return
        job.cancel(); inFlight.remove(uri)
        if (states[uri] is Peaks.Loading) states.remove(uri)
    }

    private fun dir() = File(context.cacheDir, PeakCachePolicy.DIR).apply { mkdirs() }

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

    private suspend fun load(uriText: String, wants: List<LongRange>): Peaks {
        val uri = Uri.parse(uriText)
        val (size, modified) = meta(uri)
        val file = File(dir(), PeakCachePolicy.fileName(uriText, size, modified))
        val probe = com.fluxx.android.render.MediaProbes.events
        val key = com.fluxx.android.render.MediaProbes.key(uriText)
        val cached = if (file.exists()) PeakPyramid.read(file) else null
        if (cached != null && wants.all { com.fluxx.android.render.UsedRange.covers(cached.coveredUs, it) }) {
            file.setLastModified(System.currentTimeMillis()); probe?.invoke("peaks-cached $key"); return Peaks.Ready(cached) }
        val t0 = System.nanoTime()
        probe?.invoke("peaks-start $key want=${wants.joinToString { "${it.first}..${it.last}" }}")
        val pyramid = decode(uri, wants, cached) ?: return Peaks.NoAudio
        probe?.invoke("peaks-done $key ms=${(System.nanoTime() - t0) / 1_000_000} sourceUs=${pyramid.durationUs} buckets=${pyramid.levels[0].first.size} covered=${pyramid.coveredUs.joinToString { "${it.first}..${it.last}" }}")
        currentCoroutineContext().ensureActive()
        val tmp = File(dir(), file.name + ".tmp"); pyramid.write(tmp); tmp.renameTo(file)
        val files = dir().listFiles().orEmpty().filter { it.name.endsWith(".peaks") }
        for (name in PeakCachePolicy.evict(files.map { Triple(it.name, it.length(), it.lastModified()) })) File(dir(), name).delete()
        return Peaks.Ready(pyramid)
    }

    /**
     * Peaks of the first audio track; null when the source has none. TL3.1 C:
     * - the decoder's ACTUAL output format (after INFO_OUTPUT_FORMAT_CHANGED: rate, channels, 16-bit or float PCM), not the
     *   container's (HE-AAC SBR / PS can double the rate or change the channel count);
     * - each sample placed by its PRESENTATION TIME (buffer pts + i / rate), as the mixer places them, so AAC priming and
     *   any start offset land where the mixer puts them; samples before 0 are dropped;
     * - channels downmixed to the channel of largest magnitude (a signed peak: out-of-phase stereo cannot cancel out).
     * MP1 B2: only [want] (the used range plus margin), minus what [cached] already covers; a range that reaches the end of
     * the source is stored as open-ended. MP1 B5: background thread priority, paused while playing or scrubbing.
     */
    private suspend fun decode(uri: Uri, wants: List<LongRange>, cached: PeakPyramid?): PeakPyramid? = com.fluxx.android.render.MediaWork.background {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return@background null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = android.media.AudioFormat.ENCODING_PCM_16BIT
            // AUD1 B4: the SAME priming trim as playback (AudioTiming), so the waveform still matches what plays.
            val mime = format.getString(MediaFormat.KEY_MIME)
            val delay = if (format.containsKey(MediaFormat.KEY_ENCODER_DELAY)) format.getInteger(MediaFormat.KEY_ENCODER_DELAY) else null
            val firstPts = extractor.sampleTime
            var primingUs = com.fluxx.android.render.AudioTiming.us(com.fluxx.android.render.AudioTiming.primingSamples(mime, delay, firstPts), rate)
            val sourceUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else Long.MAX_VALUE / 2
            // The window clamped to the source; one reaching the end is open-ended (so a later request there is covered).
            fun clamp(r: LongRange) = r.first.coerceAtMost(sourceUs)..(if (r.last >= sourceUs || r.last >= OPEN_END / 2) OPEN_END else r.last)
            val have = cached?.coveredUs.orEmpty()
            val todo = com.fluxx.android.render.UsedRange.merge(wants.flatMap { subtract(clamp(it), have) })
            val c = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec = c; c.configure(format, null, null, 0); c.start()
            var builder: PeakBuilder? = null
            val info = MediaCodec.BufferInfo()
            val active = currentCoroutineContext()
            for ((k, range) in todo.withIndex()) {
                if (k > 0) c.flush()
                if (range.first > 0) extractor.seekTo(range.first, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var inputEnded = false; var ended = false
                while (!ended) {
                    active.ensureActive()
                    com.fluxx.android.render.MediaWork.checkpoint({ !active.isActive })
                    // TL3.1 D: keep every free input buffer filled, and only wait for output once the input has ended (one
                    // input per loop plus a 2 ms wait starved the decoder: the decode was poll-bound, not decode-bound).
                    while (!inputEnded) {
                        val i = c.dequeueInputBuffer(0)
                        if (i < 0) break
                        val n = extractor.readSampleData(requireNotNull(c.getInputBuffer(i)), 0)
                        // MP1 B2: the input stops past the range (plus a little for the decoder's delay).
                        if (n < 0 || extractor.sampleTime > range.last + 200_000) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                        else { c.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
                    }
                    val o = c.dequeueOutputBuffer(info, if (inputEnded) 2000 else 500)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val out = c.outputFormat
                        rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        primingUs = com.fluxx.android.render.AudioTiming.us(com.fluxx.android.render.AudioTiming.primingSamples(mime, delay, firstPts), rate)
                    } else if (o >= 0) {
                        val b = builder ?: PeakBuilder(rate).also { nb -> builder = nb; if (cached != null && cached.sampleRate == rate) nb.seed(cached) }
                        val buffer = requireNotNull(c.getOutputBuffer(o)).order(ByteOrder.LITTLE_ENDIAN)
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        val float = encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        val frames = info.size / (channels * if (float) 4 else 2)
                        val first = Math.round((info.presentationTimeUs - primingUs) * rate / 1_000_000.0)
                        val lo = range.first * rate / 1_000_000; val hi = if (range.last >= OPEN_END) Long.MAX_VALUE else range.last * rate / 1_000_000
                        for (f in 0 until frames) {
                            var peak = 0f
                            for (ch in 0 until channels) {
                                val v = if (float) buffer.float else buffer.short / 32768f
                                if (kotlin.math.abs(v) > kotlin.math.abs(peak)) peak = v
                            }
                            if (first + f in lo..hi) b.addAt(first + f, peak)
                        }
                        ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        c.releaseOutputBuffer(o, false)
                    }
                }
            }
            val covered = com.fluxx.android.render.UsedRange.merge(have + todo)
            (builder ?: PeakBuilder(rate).also { nb -> if (cached != null && cached.sampleRate == rate) nb.seed(cached) }).build(covered)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release(); extractor.release()
        }
    }

    /** The parts of [r] not inside [have]. */
    private fun subtract(r: LongRange, have: List<LongRange>): List<LongRange> {
        var parts = listOf(r)
        for (h in have) parts = parts.flatMap { p ->
            if (h.last < p.first || h.first > p.last) listOf(p)
            else listOfNotNull((p.first..h.first - 1).takeIf { !it.isEmpty() }, (h.last + 1..p.last).takeIf { !it.isEmpty() })
        }
        return parts
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
