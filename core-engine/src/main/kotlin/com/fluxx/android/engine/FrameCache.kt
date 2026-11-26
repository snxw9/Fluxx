package com.fluxx.android.engine

/**
 * AUD2 B2: the decoded frames a [VideoDecoder] keeps (pure, JVM tested). Bounded by [capacity] (sized from a memory
 * budget by [DecoderPlan.cacheFrames]); when full, the frame decoded LONGEST AGO is evicted (so the frames around the
 * current position stay). Frames decoded one after another without a seek form a RUN; a cached answer is EXACT only when
 * the two frames around the target are consecutive in one run (no frame of the stream can lie between them), so a cache
 * hit is always the frame a straight decode would give (nearest frame, the earlier one on a tie, as before).
 */
class FrameCache<T>(val capacity: Int, private val onEvict: (T) -> Unit) {
    private class Entry<T>(val pts: Long, val seq: Long, val run: Long, val item: T)
    private val entries = ArrayList<Entry<T>>(capacity)   // sorted by pts
    private var seq = 0L
    private var run = 0L
    /** The run of the last frame added and whether the stream ended after it (then nothing follows it). */
    private var endedRun = -1L
    init { require(capacity >= 2) }

    val size get() = entries.size
    /** Starts a new run: the next frame added does not follow the previous one (after a seek / flush). */
    fun breakRun() { run++ }
    /** The stream ended after the last frame of the current run. */
    fun markEnded() { endedRun = run }

    /** Adds a decoded frame (evicting the oldest-decoded when full). Returns false if [pts] is already cached (item evicted). */
    fun add(pts: Long, item: T): Boolean {
        if (entries.any { it.pts == pts }) {
            // The same frame decoded again (a re-seek): keep the cached copy's slot, refresh its run position.
            val i = entries.indexOfFirst { it.pts == pts }
            onEvict(entries[i].item)
            entries[i] = Entry(pts, seq++, run, item)
            return true
        }
        if (entries.size >= capacity) {
            val oldest = entries.minByOrNull { it.seq }!!
            entries.remove(oldest); onEvict(oldest.item)
        }
        val e = Entry(pts, seq++, run, item)
        val at = entries.indexOfFirst { it.pts > pts }.let { if (it < 0) entries.size else it }
        entries.add(at, e)
        return true
    }

    /** The exact nearest frame for [t] when the cache can prove it; else null. */
    fun exact(t: Long): T? {
        entries.firstOrNull { it.pts == t }?.let { return it.item }
        val after = entries.indexOfFirst { it.pts > t }
        val before = if (after < 0) entries.lastIndex else after - 1
        if (before < 0) return null
        val b = entries[before]
        if (after < 0) return if (b.run == endedRun && b.seq == lastSeqOfRun(b.run)) b.item else null
        val a = entries[after]
        if (a.run != b.run || a.seq != b.seq + 1) return null
        return if (t - b.pts <= a.pts - t) b.item else a.item
    }

    /** The cached frame nearest [t] (the earlier on a tie), exact or not; null when empty. With its distance in [distanceOut]. */
    fun nearest(t: Long): Pair<Long, T>? = entries.minWithOrNull(compareBy<Entry<T>> { kotlin.math.abs(it.pts - t) }.thenBy { it.pts })?.let { it.pts to it.item }

    /** Whether the newest frame of the current run lies at or after [t] (a forward decode has reached it). */
    fun lastPts(): Long = entries.filter { it.run == run }.maxByOrNull { it.seq }?.pts ?: Long.MIN_VALUE

    fun clear() { entries.forEach { onEvict(it.item) }; entries.clear(); run++ }
    fun ptsList(): List<Long> = entries.map { it.pts }

    private fun lastSeqOfRun(r: Long): Long = entries.filter { it.run == r }.maxOf { it.seq }
}

/** AUD2 B2: the seek decisions of [VideoDecoder] (pure, JVM tested). */
object DecoderPlan {
    enum class Action { CACHED, FORWARD, SEEK }
    /** A forward request further ahead than this seeks, but only when its sync frame lies past what is decoded. */
    const val SEEK_AHEAD_US = 500_000L
    /** Per-decoder frame-cache memory budget (a 1080p frame is ~3.1 MB, 1440p ~4.1 MB). */
    const val CACHE_BYTES = 16L * 1024 * 1024
    /** While scrubbing ([approximate]), at most this many frames are decoded per request (the rest continues next time). */
    const val APPROX_DECODE_FRAMES = 4

    fun cacheFrames(width: Int, height: Int): Int = (CACHE_BYTES / (width.toLong() * height * 3 / 2).coerceAtLeast(1)).toInt().coerceIn(3, 6)

    /**
     * What a request for [target] needs. [exactCached]: the cache can prove the exact frame. [outPos]: the last decoded
     * frame's pts (-1: none). [ended]: the stream ended at [outPos]. [prevSync]: the sync frame at or before [target].
     * - Cached: serve from memory (no codec work).
     * - Forward: keep decoding from where the decoder is (also after a cancelled request: the latest target wins, no flush).
     * - Seek: flush and seek to the previous sync frame (backward past the cache, or far ahead into a later GOP).
     */
    fun decide(target: Long, exactCached: Boolean, outPos: Long, ended: Boolean, prevSync: () -> Long): Action = when {
        exactCached -> Action.CACHED
        outPos < 0 -> if (target > 0) Action.SEEK else Action.FORWARD
        target >= outPos -> when {
            ended -> Action.CACHED
            target - outPos > SEEK_AHEAD_US && prevSync() > outPos -> Action.SEEK
            else -> Action.FORWARD
        }
        else -> Action.SEEK
    }
}
