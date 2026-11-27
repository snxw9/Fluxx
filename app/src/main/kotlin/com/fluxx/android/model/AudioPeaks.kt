package com.fluxx.android.model

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * TL3 B5: min/max audio peaks at several zoom levels (pure, JVM tested). Level 0 holds one (min, max) pair per [BASE]
 * source samples (mono downmix); each next level merges [FACTOR] buckets of the one below. Values are raw sample
 * amplitude (-1..1): the waveform has a FIXED scale, no auto-gain.
 */
class PeakPyramid(val sampleRate: Int, val durationUs: Long, val levels: List<Pair<FloatArray, FloatArray>>,
    /** MP1 B2: the source µs ranges actually decoded (the used range plus margin); buckets elsewhere are silent. */
    val coveredUs: List<LongRange> = listOf(0L..durationUs)) {
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

    /** MP1 B2: the level-0 bucket span of source range [r] (clamped to what exists). */
    private fun buckets(r: LongRange): IntRange {
        val n = levels[0].first.size
        val b0 = (r.first / bucketUs(0)).toInt().coerceIn(0, n)
        val b1 = if (r.last >= durationUs) n - 1 else kotlin.math.ceil(r.last / bucketUs(0)).toInt().coerceAtMost(n - 1)
        return b0..b1
    }

    /** v4 (MP1 B2): the covered ranges and only their level-0 buckets; the coarser levels are rebuilt on read. */
    fun write(file: File) = DataOutputStream(file.outputStream().buffered()).use { out ->
        out.writeInt(FORMAT_VERSION); out.writeInt(sampleRate); out.writeLong(durationUs)
        val (mn, mx) = levels[0]
        out.writeInt(mn.size); out.writeInt(coveredUs.size)
        for (r in coveredUs) {
            out.writeLong(r.first); out.writeLong(r.last)
            val b = buckets(r); out.writeInt(b.first); out.writeInt(if (b.isEmpty()) 0 else b.last - b.first + 1)
            for (i in b) { out.writeFloat(mn[i]); out.writeFloat(mx[i]) }
        }
    }

    companion object {
        const val BASE = 256
        const val FACTOR = 4
        /** Bumped whenever the file layout or the analysis changes: it is part of the cache key. */
        /** TL3.1 C: 2 = peaks placed by presentation time from the decoder's real output format (stale v1 peaks rebuild). */
        /** AUD1: 3 = the priming trimmed like playback (stale peaks rebuild). */
        /** MP1 B2: 4 = partial coverage (the used range plus margin), only covered buckets stored. */
        const val FORMAT_VERSION = 4

        fun read(file: File): PeakPyramid? = runCatching {
            DataInputStream(file.inputStream().buffered()).use { inp ->
                if (inp.readInt() != FORMAT_VERSION) return null
                val rate = inp.readInt(); val dur = inp.readLong(); val size = inp.readInt(); val ranges = inp.readInt()
                val mn = FloatArray(size); val mx = FloatArray(size); val covered = ArrayList<LongRange>(ranges)
                repeat(ranges) {
                    covered += inp.readLong()..inp.readLong()
                    val from = inp.readInt(); val count = inp.readInt()
                    for (i in from until from + count) { mn[i] = inp.readFloat(); mx[i] = inp.readFloat() }
                }
                PeakPyramid(rate, dur, PeakBuilder.levels(mn, mx), covered)
            }
        }.getOrNull()
    }
}

/** TL3 B5: builds a [PeakPyramid] while samples stream in (no whole-file PCM in memory); cancellation is the caller's. */
class PeakBuilder(private val sampleRate: Int) {
    // Primitive arrays (grown by doubling): no boxing per sample.
    private var mins = FloatArray(1024) { Float.MAX_VALUE }; private var maxs = FloatArray(1024) { -Float.MAX_VALUE }
    private var used = 0
    /** One past the last sample index written. */
    var samples = 0L; private set

    /** The next sample (sample index = the count so far). */
    fun add(sample: Float) = addAt(samples, sample)

    /**
     * TL3.1 C: a sample at its ABSOLUTE index (presentation time x the true output rate), so the peaks sit at the same
     * source times as the mixer's samples (it places decoded samples by presentation time too). Before 0: dropped (AAC
     * priming before the stream's start). Gaps stay silent.
     */
    fun addAt(index: Long, sample: Float) {
        if (index < 0) return
        val b = (index / PeakPyramid.BASE).toInt()
        if (b >= mins.size) {
            val n = maxOf(b + 1, mins.size * 2)
            mins = mins.copyOf(n).also { for (i in mins.size until n) it[i] = Float.MAX_VALUE }
            maxs = maxs.copyOf(n).also { for (i in maxs.size until n) it[i] = -Float.MAX_VALUE }
        }
        if (b + 1 > used) used = b + 1
        if (sample < mins[b]) mins[b] = sample
        if (sample > maxs[b]) maxs[b] = sample
        if (index + 1 > samples) samples = index + 1
    }
    private fun flush() { for (i in 0 until used) if (mins[i] == Float.MAX_VALUE) { mins[i] = 0f; maxs[i] = 0f } }

    /** MP1 B2: starts from an earlier pyramid's level 0 (a widened used range decodes only what is missing). */
    fun seed(previous: PeakPyramid) {
        val (mn, mx) = previous.levels[0]
        if (mn.isEmpty()) return
        addAt(mn.size.toLong() * PeakPyramid.BASE - 1, 0f)
        for (i in mn.indices) { mins[i] = mn[i]; maxs[i] = mx[i] }
        samples = maxOf(samples, previous.durationUs * sampleRate / 1_000_000L)
    }

    fun build(covered: List<LongRange>? = null): PeakPyramid {
        flush()
        val duration = samples * 1_000_000L / sampleRate
        return PeakPyramid(sampleRate, duration, levels(mins.copyOf(used), maxs.copyOf(used)), covered ?: listOf(0L..duration))
    }

    companion object {
        /** Level 0 and each coarser level (FACTOR buckets merged), as stored and as rebuilt on read. */
        fun levels(level0Min: FloatArray, level0Max: FloatArray): List<Pair<FloatArray, FloatArray>> {
            val levels = ArrayList<Pair<FloatArray, FloatArray>>()
            var mn = level0Min; var mx = level0Max
            levels += mn to mx
            while (mn.size > 1) {
                val n = (mn.size + PeakPyramid.FACTOR - 1) / PeakPyramid.FACTOR
                val nmn = FloatArray(n) { Float.MAX_VALUE }; val nmx = FloatArray(n) { -Float.MAX_VALUE }
                for (i in mn.indices) { val j = i / PeakPyramid.FACTOR; nmn[j] = minOf(nmn[j], mn[i]); nmx[j] = maxOf(nmx[j], mx[i]) }
                mn = nmn; mx = nmx; levels += mn to mx
            }
            return levels
        }
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
    /** TL3.1 B6: the rectified envelope of one column (0..1, fixed scale): max(|min|, |max|). */
    fun envelope(min: Float, max: Float): Float = maxOf(kotlin.math.abs(min), kotlin.math.abs(max)).coerceIn(0f, 1f)
    /** Dimmed when the layer cannot be heard (muted or volume 0). */
    fun dimmed(layer: CompositionLayer) = layer.muted || layer.audioGain <= 0f
}
