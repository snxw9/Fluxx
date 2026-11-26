package com.fluxx.android.render

/**
 * AUD1 pure audio timing (JVM tested): the priming trim, the per-source PCM cache's key / LRU / chunk coverage, the
 * streaming window and the A/V clock. Android-free.
 */
object AudioTiming {
    /** The preview / export output rate (48 kHz stereo 16-bit), as before. */
    const val RATE = 48_000
    /** AAC-LC encoder delay when the container does not say (the standard 2048 priming samples). */
    const val AAC_PRIMING = 2048

    /**
     * The samples of decoder output that come BEFORE the stream's true start, in the source's own samples. B4:
     * - the format's encoder delay ("encoder-delay"), where present;
     * - 0 when the container already shifts the priming before 0 (the first sample's pts is negative: those samples are
     *   dropped as "before 0" by [outputFrame]);
     * - otherwise the known AAC-LC priming for AAC, 0 for other codecs.
     */
    fun primingSamples(mime: String?, encoderDelay: Int?, firstPtsUs: Long): Int = when {
        encoderDelay != null -> encoderDelay.coerceAtLeast(0)
        firstPtsUs < 0 -> 0
        mime == "audio/mp4a-latm" -> AAC_PRIMING
        else -> 0
    }

    /** Microseconds of [samples] at [rate]. */
    fun us(samples: Int, rate: Int): Long = samples * 1_000_000L / rate

    /** The output (48 kHz) frame of a decoded sample with presentation time [ptsUs], the priming trimmed. Negative: before 0. */
    fun outputFrame(ptsUs: Long, primingUs: Long): Long = Math.floorDiv((ptsUs - primingUs) * RATE + 500_000, 1_000_000L)

    /** 48 kHz frames of [us] (floor). */
    fun frames(us: Long): Long = Math.floorDiv(us * RATE, 1_000_000L)
}

/** AUD1 B2: the decoded-PCM cache's key and size policy (pure). One file per source: 48 kHz stereo 16-bit. */
object PcmCachePolicy {
    const val DIR = "aud1-pcm"
    const val VERSION = 1
    const val CAP_BYTES = 256L * 1024 * 1024
    /** 1 s chunks: the unit of decode coverage. */
    const val CHUNK_FRAMES = AudioTiming.RATE

    fun fileName(uri: String, size: Long, lastModified: Long): String =
        "%s-%x-%x-v%d.pcm".format(java.security.MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).take(10)
            .joinToString("") { "%02x".format(it) }, size, lastModified, VERSION)

    /** A cache file of another format version (rebuilt, so deleted). */
    fun stale(name: String) = (name.endsWith(".pcm") || name.endsWith(".cov")) && !name.substringBeforeLast('.').endsWith("-v$VERSION")

    /** Files to delete (least recently used first) so the rest fit in [cap]; [keep] (in use) never goes. */
    fun evict(files: List<Triple<String, Long, Long>>, keep: Set<String>, cap: Long = CAP_BYTES): List<String> {
        var total = files.sumOf { it.second }
        val out = ArrayList<String>()
        for (f in files.sortedBy { it.third }) { if (total <= cap) break; if (f.first in keep) continue; out += f.first; total -= f.second }
        return out
    }

    fun bytes(frames: Long) = frames * 4
    fun chunks(frames: Long) = ((frames + CHUNK_FRAMES - 1) / CHUNK_FRAMES).toInt()
    fun chunkOf(frame: Long) = (frame / CHUNK_FRAMES).toInt()
}

/**
 * AUD1 B1: chunk coverage of one source's decoded PCM (pure; the caller synchronises). A decode run that started at
 * [runStart] (a chunk boundary) and has written everything below [writtenTo] covers every chunk inside that span.
 */
class ChunkCoverage(val chunks: Int, initial: java.util.BitSet? = null) {
    private val bits = initial?.clone() as java.util.BitSet? ?: java.util.BitSet(chunks)
    fun covered(chunk: Int) = chunk in 0 until chunks && bits[chunk]
    /** Every chunk overlapping [from, to) frames is covered. */
    fun covers(from: Long, to: Long): Boolean {
        if (to <= from) return true
        for (c in PcmCachePolicy.chunkOf(from)..PcmCachePolicy.chunkOf(to - 1)) if (c < chunks && !bits[c]) return false
        return true
    }
    /** The first uncovered chunk at or after [chunk], or -1. */
    fun firstGap(chunk: Int): Int { val g = bits.nextClearBit(chunk.coerceAtLeast(0)); return if (g >= chunks) -1 else g }
    /** A run from [runStartFrame] (chunk aligned) wrote up to [writtenTo]: mark the fully written chunks. Returns newly covered. */
    fun advance(runStartFrame: Long, writtenTo: Long, totalFrames: Long): Int {
        var n = 0
        var c = PcmCachePolicy.chunkOf(runStartFrame)
        while (c < chunks) {
            val end = minOf((c + 1L) * PcmCachePolicy.CHUNK_FRAMES, totalFrames)
            if (writtenTo < end) break
            if (!bits[c]) { bits[c] = true; n++ }
            c++
        }
        return n
    }
    val complete get() = bits.cardinality() >= chunks
    fun snapshot(): java.util.BitSet = bits.clone() as java.util.BitSet
}

/**
 * AUD1 B5: the playback clock is the AUDIO clock as it is HEARD (pure). With an AudioTimestamp (frame [tsFrame] presented
 * at [tsNanoTime]) the position is the timestamp extrapolated to now; without one (the first ~100 ms, or a platform that
 * does not report) the head minus the output latency. Never ahead of the head, never before the start.
 */
object AvClock {
    fun positionFrames(startFrame: Long, headFrames: Long, nowNs: Long, tsFrame: Long?, tsNanoTime: Long?, latencyFrames: Long): Long {
        val heard = if (tsFrame != null && tsNanoTime != null && tsNanoTime > 0)
            tsFrame + (nowNs - tsNanoTime) * AudioTiming.RATE / 1_000_000_000L
        else headFrames - latencyFrames
        return startFrame + heard.coerceIn(0L, headFrames.coerceAtLeast(0L))
    }
    fun us(frames: Long): Long = frames * 1_000_000L / AudioTiming.RATE
}

/**
 * AUD1 B1: the streaming start (pure): playback starts once [READY_US] of every active source is decoded at the start
 * position; "Preparing audio" shows only when that takes longer than [STATUS_AFTER_MS].
 */
object StreamingWindow {
    const val READY_US = 500_000L
    const val STATUS_AFTER_MS = 300L
    /** 20 ms mix blocks. */
    const val BLOCK_FRAMES = 960
    fun showStatus(waitedMs: Long, ready: Boolean) = !ready && waitedMs > STATUS_AFTER_MS
}
