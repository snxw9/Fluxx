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
    // AUD3: 2 (48 kHz sources are now written contiguously; v1 caches of millisecond-timestamped files were wrong).
    const val VERSION = 2
    const val CAP_BYTES = 256L * 1024 * 1024
    /** 1 s chunks: the unit of decode coverage. */
    const val CHUNK_FRAMES = AudioTiming.RATE

    fun fileName(uri: String, size: Long, lastModified: Long): String =
        "%s-%x-%x-v%d.pcm".format(java.security.MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).take(10)
            .joinToString("") { "%02x".format(it) }, size, lastModified, VERSION)

    /** A cache file of another format version (rebuilt, so deleted). */
    fun stale(name: String) = (name.endsWith(".pcm") || name.endsWith(".cov")) && !name.substringBeforeLast('.').endsWith("-v$VERSION")

    /**
     * Files to delete (least recently used first) so the rest fit in [cap]; [keep] (in use) never goes. MP1 B2: the sizes
     * are the bytes ALLOCATED on disk (the files are sparse: a 50-minute source is ~580 MB long but holds only its decoded
     * chunks), so a long source no longer evicts every other cache.
     */
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
 * MP1 B2: the part of a source that is USED (pure, JVM tested): each visible layer's in-point to out-point plus [MARGIN_US]
 * on each side, clamped to the source and merged per source (overlapping windows only: two clips far apart in one long
 * file stay two windows, never the hull). Background decoding (the PCM cache, waveform peaks, thumbnails) stays inside it;
 * a reader's demand outside it (a seek, a widened trim before the next update) extends it on demand.
 */
object UsedRange {
    const val MARGIN_US = 10_000_000L

    fun windowUs(sourceInUs: Long, durationUs: Long, sourceDurationUs: Long, marginUs: Long = MARGIN_US): LongRange =
        (sourceInUs - marginUs).coerceAtLeast(0)..minOf(sourceInUs + durationUs + marginUs, sourceDurationUs).coerceAtLeast(0)

    /** Sorted, overlapping (or touching) ranges joined. */
    fun merge(ranges: List<LongRange>): List<LongRange> {
        val out = ArrayList<LongRange>()
        for (r in ranges.filter { !it.isEmpty() }.sortedBy { it.first }) {
            val last = out.lastOrNull()
            if (last != null && r.first <= last.last + 1) out[out.size - 1] = last.first..maxOf(last.last, r.last) else out += r
        }
        return out
    }

    fun covers(ranges: List<LongRange>, r: LongRange): Boolean = ranges.any { r.first >= it.first && r.last <= it.last }

    /** [ranges] with a demand at [demand] for [lead] more added (a playhead / reader outside the used range). */
    fun extend(ranges: List<LongRange>, demand: Long, lead: Long): List<LongRange> = merge(ranges + listOf(demand..demand + lead))

    /** [us] (a µs range) as 48 kHz output frames within [0, maxFrames). */
    fun framesOf(us: LongRange, maxFrames: Long): LongRange =
        AudioTiming.frames(us.first).coerceIn(0, maxFrames - 1)..AudioTiming.frames(us.last).coerceIn(0, maxFrames - 1)

    /** The used windows (µs) per source URI of the composition's visible video layers. */
    fun of(project: com.fluxx.android.model.ProjectDocument): Map<String, List<LongRange>> = project.composition.layers
        .filter { it.visible && it.type == com.fluxx.android.model.LayerType.VIDEO && it.asset?.uri != null && it.timing.durationUs != null }
        .groupBy({ it.asset!!.uri!! }) { l -> windowUs(l.timing.sourceInUs, l.timing.durationUs!!, l.asset!!.durationUs ?: (l.timing.sourceInUs + l.timing.durationUs)) }
        .mapValues { merge(it.value) }
}

/** MP1 B2 / B5: what the PCM cache worker decodes next and when it may stop (pure, JVM tested). */
object PcmScope {
    /** A reader's demand keeps the worker decoding (at playback priority) this far ahead of it. */
    const val LEAD_FRAMES = 3L * AudioTiming.RATE

    /** The first undecoded chunk start (frames) inside [scope], or -1: nothing left to decode there (never the whole file). */
    fun nextTarget(coverage: ChunkCoverage, scope: List<LongRange>): Long {
        for (r in scope) {
            val g = coverage.firstGap(PcmCachePolicy.chunkOf(r.first))
            if (g >= 0 && g.toLong() * PcmCachePolicy.CHUNK_FRAMES <= r.last) return g.toLong() * PcmCachePolicy.CHUNK_FRAMES
        }
        return -1
    }

    /**
     * A run at [runAt] may go on while it is inside the scope, up to the END of the chunk holding the scope's last frame (a
     * chunk only counts as decoded when it is complete; stopping at the scope's last frame left that chunk open forever and
     * the worker restarted there in a loop), or within [LEAD_FRAMES] of what a reader needs ([demandFrame]).
     */
    fun runMayContinue(runAt: Long, scope: List<LongRange>, demandFrame: Long): Boolean =
        scope.any { runAt >= it.first - PcmCachePolicy.CHUNK_FRAMES && runAt < (PcmCachePolicy.chunkOf(it.last) + 1L) * PcmCachePolicy.CHUNK_FRAMES } ||
            MediaWorkPolicy.urgent(runAt, demandFrame, LEAD_FRAMES)

    /** Background filling stops before one source alone reaches the cache cap (partial coverage); demand still decodes. */
    fun backgroundAllowed(coveredBytes: Long, cap: Long = PcmCachePolicy.CAP_BYTES): Boolean = coveredBytes < cap
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
    /** MP1 B2: covered chunks (no copy). */
    val count: Int get() = bits.cardinality()
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
    /**
     * AUD2 B2: the lag used before the first AudioTimestamp. getLatency() includes the track buffer, which lies AHEAD of the
     * head (not behind it): head - getLatency sat at the start for ~340 ms and then jumped +80 ms. What remains after the
     * buffer is the output path behind the head.
     */
    fun fallbackLagFrames(latencyFrames: Long, bufferFrames: Long): Long = (latencyFrames - bufferFrames).coerceAtLeast(0L)
    /** AUD2 B2: the clock never steps back (a backward step made the video decoder seek); it holds until time catches up. */
    fun monotonic(lastUs: Long, nextUs: Long): Long = maxOf(lastUs, nextUs)
    /**
     * AUD2 B2: a forward correction is caught up at up to [SLEW] x real time instead of in one step. The head does not
     * move until about the first AudioTimestamp, which then reports ~80 ms already heard: a jump of 1.6 frames at 20 fps
     * and 2.4 at 30 fps. Monotonic too (never a step back).
     */
    fun slew(lastUs: Long, lastNs: Long, targetUs: Long, nowNs: Long): Long =
        if (targetUs <= lastUs) lastUs else minOf(targetUs, lastUs + ((nowNs - lastNs).coerceAtLeast(0) / 1000) * SLEW_NUM / SLEW_DEN)
    const val SLEW_NUM = 3L
    const val SLEW_DEN = 2L
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
