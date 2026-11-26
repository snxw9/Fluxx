package com.fluxx.android.model

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * TL3 B5: min/max audio peaks at several zoom levels (pure, JVM tested). Level 0 holds one (min, max) pair per [BASE]
 * source samples (mono downmix); each next level merges [FACTOR] buckets of the one below. Values are raw sample
 * amplitude (-1..1): the waveform has a FIXED scale, no auto-gain.
 */
class PeakPyramid(val sampleRate: Int, val durationUs: Long, val levels: List<Pair<FloatArray, FloatArray>>) {
    /** Source microseconds covered by one bucket of [level]. */
    fun bucketUs(level: Int): Double = BASE.toDouble() * Math.pow(FACTOR.toDouble(), level.toDouble()) * 1_000_000.0 / sampleRate

    /** The coarsest level whose bucket is still at most [usPerPx] (so one pixel column covers at least one bucket). */
    fun levelFor(usPerPx: Double): Int {
        var l = 0
        while (l + 1 < levels.size && bucketUs(l + 1) <= usPerPx) l++
        return l
    }

    /** The bucket of [level] at source time [sourceUs], or -1 outside. */
    fun bucketAt(level: Int, sourceUs: Long): Int {
        val i = (sourceUs / bucketUs(level)).toInt()
        return if (sourceUs < 0 || i >= levels[level].first.size) -1 else i
    }

    fun write(file: File) = DataOutputStream(file.outputStream().buffered()).use { out ->
        out.writeInt(FORMAT_VERSION); out.writeInt(sampleRate); out.writeLong(durationUs); out.writeInt(levels.size)
        for ((mn, mx) in levels) { out.writeInt(mn.size); for (i in mn.indices) { out.writeFloat(mn[i]); out.writeFloat(mx[i]) } }
    }

    companion object {
        const val BASE = 256
        const val FACTOR = 4
        /** Bumped whenever the file layout or the analysis changes: it is part of the cache key. */
        const val FORMAT_VERSION = 1

        fun read(file: File): PeakPyramid? = runCatching {
            DataInputStream(file.inputStream().buffered()).use { inp ->
                if (inp.readInt() != FORMAT_VERSION) return null
                val rate = inp.readInt(); val dur = inp.readLong(); val n = inp.readInt()
                PeakPyramid(rate, dur, (0 until n).map { val size = inp.readInt(); val mn = FloatArray(size); val mx = FloatArray(size)
                    for (i in 0 until size) { mn[i] = inp.readFloat(); mx[i] = inp.readFloat() }; mn to mx })
            }
        }.getOrNull()
    }
}

/** TL3 B5: builds a [PeakPyramid] while samples stream in (no whole-file PCM in memory); cancellation is the caller's. */
class PeakBuilder(private val sampleRate: Int) {
    private val mins = ArrayList<Float>(); private val maxs = ArrayList<Float>()
    private var count = 0; private var lo = Float.MAX_VALUE; private var hi = -Float.MAX_VALUE
    var samples = 0L; private set

    fun add(sample: Float) {
        if (sample < lo) lo = sample; if (sample > hi) hi = sample
        samples++
        if (++count == PeakPyramid.BASE) flush()
    }
    private fun flush() { if (count == 0) return; mins += lo; maxs += hi; count = 0; lo = Float.MAX_VALUE; hi = -Float.MAX_VALUE }

    fun build(): PeakPyramid {
        flush()
        val levels = ArrayList<Pair<FloatArray, FloatArray>>()
        var mn = mins.toFloatArray(); var mx = maxs.toFloatArray()
        levels += mn to mx
        while (mn.size > 1) {
            val n = (mn.size + PeakPyramid.FACTOR - 1) / PeakPyramid.FACTOR
            val nmn = FloatArray(n) { Float.MAX_VALUE }; val nmx = FloatArray(n) { -Float.MAX_VALUE }
            for (i in mn.indices) { val j = i / PeakPyramid.FACTOR; nmn[j] = minOf(nmn[j], mn[i]); nmx[j] = maxOf(nmx[j], mx[i]) }
            mn = nmn; mx = nmx; levels += mn to mx
        }
        return PeakPyramid(sampleRate, samples * 1_000_000L / sampleRate, levels)
    }
}

/**
 * TL3 B5: the peak cache's key and its LRU cap (pure policy; the provider owns the files). The key is (source uri hash,
 * size, lastModified, format version), so a changed file or a new analysis never reuses old peaks.
 */
object PeakCachePolicy {
    const val CAP_BYTES = 64L * 1024 * 1024
    const val DIR = "tl3-peaks"

    fun fileName(uri: String, size: Long, lastModified: Long): String =
        "%s-%x-%x-v%d.peaks".format(java.security.MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).take(8)
            .joinToString("") { "%02x".format(it) }, size, lastModified, PeakPyramid.FORMAT_VERSION)

    /** Files to delete (least recently used first) so the rest fit in [cap]; [files] are (name, bytes, lastUsed). */
    fun evict(files: List<Triple<String, Long, Long>>, cap: Long = CAP_BYTES): List<String> {
        var total = files.sumOf { it.second }
        val out = ArrayList<String>()
        for (f in files.sortedBy { it.third }) { if (total <= cap) break; out += f.first; total -= f.second }
        return out
    }
}

/**
 * TL3 B5: the waveform's time mapping, the SAME as `AudioMixer` uses: source = sourceInUs + (t − startUs) for t in the
 * clip [startUs, endUs) (speed 1×; the model has no speed, loop or remap, so none are left out).
 */
object WaveformMapping {
    fun sourceUs(layer: CompositionLayer, compositionUs: Long): Long? {
        val t = layer.timing
        val end = t.endUs ?: return null
        if (compositionUs < t.startUs || compositionUs >= end) return null
        return t.sourceInUs + (compositionUs - t.startUs)
    }
    /** Dimmed when the layer cannot be heard (muted or volume 0). */
    fun dimmed(layer: CompositionLayer) = layer.muted || layer.audioGain <= 0f
}
