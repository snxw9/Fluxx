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

    fun request(uri: String) {
        if (states[uri] != null && states[uri] !is Peaks.Failed) return
        states[uri] = Peaks.Loading
        jobs[uri] = scope.launch {
            val result = try { withContext(Dispatchers.IO) { load(uri) } } catch (c: CancellationException) { throw c } catch (_: Exception) { Peaks.Failed }
            states[uri] = result; jobs.remove(uri)
        }
    }

    /** A row left while its peaks were still decoding: stop the work (it restarts on the next request). */
    fun cancel(uri: String) {
        val job = jobs.remove(uri) ?: return
        job.cancel()
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

    private suspend fun load(uriText: String): Peaks {
        val uri = Uri.parse(uriText)
        val (size, modified) = meta(uri)
        val file = File(dir(), PeakCachePolicy.fileName(uriText, size, modified))
        if (file.exists()) PeakPyramid.read(file)?.let { file.setLastModified(System.currentTimeMillis()); return Peaks.Ready(it) }
        val pyramid = decode(uri) ?: return Peaks.NoAudio
        currentCoroutineContext().ensureActive()
        val tmp = File(dir(), file.name + ".tmp"); pyramid.write(tmp); tmp.renameTo(file)
        val files = dir().listFiles().orEmpty().filter { it.name.endsWith(".peaks") }
        for (name in PeakCachePolicy.evict(files.map { Triple(it.name, it.length(), it.lastModified()) })) File(dir(), name).delete()
        return Peaks.Ready(pyramid)
    }

    /** Mono (channel average) peaks of the first audio track; null when the source has none. */
    private suspend fun decode(uri: Uri): PeakPyramid? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val c = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec = c; c.configure(format, null, null, 0); c.start()
            var builder = PeakBuilder(rate)
            val info = MediaCodec.BufferInfo()
            var inputEnded = false; var ended = false
            while (!ended) {
                currentCoroutineContext().ensureActive()
                if (!inputEnded) {
                    val i = c.dequeueInputBuffer(0)
                    if (i >= 0) {
                        val n = extractor.readSampleData(requireNotNull(c.getInputBuffer(i)), 0)
                        if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                        else { c.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 2000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = c.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = c.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (builder.samples == 0L) builder = PeakBuilder(rate)
                } else if (o >= 0) {
                    val buffer = requireNotNull(c.getOutputBuffer(o)).order(ByteOrder.LITTLE_ENDIAN)
                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    val frames = info.size / (channels * 2)
                    for (f in 0 until frames) {
                        var sum = 0f
                        for (ch in 0 until channels) sum += buffer.short / 32768f
                        builder.add(sum / channels)
                    }
                    ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    c.releaseOutputBuffer(o, false)
                }
            }
            return builder.build()
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release(); extractor.release()
        }
    }

    companion object {
        @Volatile private var instance: AudioPeakProvider? = null
        fun get(context: Context): AudioPeakProvider = instance ?: synchronized(this) {
            instance ?: AudioPeakProvider(context.applicationContext).also { instance = it }
        }
    }
}
