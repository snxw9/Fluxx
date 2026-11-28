package com.fluxx.android.render

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.BitSet

/**
 * MP2.3 B1: the scrub preview strip (pure, JVM tested). For each video source a low-resolution picture every
 * [INTERVAL_US] of SOURCE time over its USED range only (in-point to out-point plus [UsedRange.MARGIN_US], as the PCM
 * cache, the peaks and the thumbnails), kept on disk under cacheDir/[DIR] and read while the finger sweeps fast, so the
 * preview follows it in order without a decoder seek per target. Preview only: export never builds or reads it.
 *
 * - Slot k is the interval [k * I, (k + 1) * I). Its entry is the FIRST picture of the stream whose pts lies in it.
 * - A slot is COVERED when every picture of it has been seen (the builder decoded through it from a point at or before its
 *   start, or from the stream's start), whether it holds a picture or none (a gap in a variable-rate stream).
 * - [StripIndex.atOrBefore] never returns a picture later than the requested time ([com.fluxx.android.engine.ScrubFloor]'s
 *   rule), except before the stream's first picture, which is answered by that picture (as a straight decode answers it).
 */
object StripPolicy {
    const val DIR = "mp23-strip"
    /** 1: JPEG, 0.1 s, long edge 480 (any change of the format constants below bumps it: the files are rebuilt). */
    const val VERSION = 1
    const val INTERVAL_US = 100_000L
    const val LONG_EDGE = 480
    const val JPEG_QUALITY = 70
    /** The disk cache's byte budget, counted in ALLOCATED bytes (as the PCM cache). */
    const val CAP_BYTES = 256L * 1024 * 1024
    /** The renderer's in-RAM cache of uploaded strip pictures (GPU buffers, RGBA). */
    const val RAM_BYTES = 6L * 1024 * 1024
    /** One builder run: this much source time around the playhead, then the next run is planned (visible first). */
    const val RUN_US = 10_000_000L
    /** A lookup walks back at most this many covered slots without a picture (then it is a miss). */
    const val LOOKBACK_SLOTS = 64
    /** The builder's decoder closes when its work has been paused this long (playing, scrubbing, foreground). */
    const val PAUSED_CLOSE_MS = 3_000L
    /** A run continues from where the builder's decoder stands when the next run starts at most this far ahead. */
    const val CONTINUE_US = 2_000_000L
    /** The index is written after this many new pictures (and at the end of every run). */
    const val PERSIST_EVERY = 32

    val runSlots: Int get() = (RUN_US / INTERVAL_US).toInt()

    fun fileBase(uri: String, size: Long, lastModified: Long): String =
        "%s-%x-%x-v%d".format(java.security.MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).take(10)
            .joinToString("") { "%02x".format(it) }, size, lastModified, VERSION)

    /** A strip file of another format version (rebuilt, so deleted). */
    fun stale(name: String) = (name.endsWith(".strip") || name.endsWith(".sidx") || name.endsWith(".tmp")) &&
        !name.substringBeforeLast('.').endsWith("-v$VERSION")

    fun slotOf(us: Long): Int = (us.coerceAtLeast(0) / INTERVAL_US).toInt()
    fun slotStart(slot: Int): Long = slot.toLong() * INTERVAL_US
    fun slots(durationUs: Long): Int = slotOf(durationUs.coerceAtLeast(0)) + 1

    /**
     * The slots of the used [windows] (source µs, merged), clamped to the source: the slot holding a window's first µs to
     * the slot holding its last (a window's edge inside a slot takes the whole slot: at most one interval more).
     */
    fun scopeSlots(windows: List<LongRange>, durationUs: Long): List<IntRange> {
        val last = slots(durationUs) - 1
        val out = ArrayList<IntRange>()
        for (w in UsedRange.merge(windows)) {
            val a = slotOf(w.first).coerceIn(0, last); val b = slotOf(minOf(w.last, durationUs)).coerceIn(0, last)
            if (b < a) continue
            val prev = out.lastOrNull()
            if (prev != null && a <= prev.last + 1) out[out.size - 1] = prev.first..maxOf(prev.last, b) else out += a..b
        }
        return out
    }
    fun inScope(scope: List<IntRange>, slot: Int) = scope.any { slot in it }

    /** The strip picture size for a [width] x [height] crop: long edge [edge] (never upscaled), even sides. */
    fun size(width: Int, height: Int, edge: Int = LONG_EDGE): Pair<Int, Int> {
        val long = maxOf(width, height).coerceAtLeast(1)
        val scale = minOf(1.0, edge.toDouble() / long)
        fun even(v: Double) = (Math.round(v / 2) * 2).toInt().coerceAtLeast(2)
        return even(width * scale) to even(height * scale)
    }

    /** Files to delete (least recently used first) so the rest fit in [cap]; [keep] (in use) never goes. Sizes are ALLOCATED bytes. */
    fun evict(files: List<Triple<String, Long, Long>>, keep: Set<String>, cap: Long = CAP_BYTES): List<String> {
        var total = files.sumOf { it.second }
        val out = ArrayList<String>()
        for (f in files.sortedBy { it.third }) { if (total <= cap) break; if (f.first in keep) continue; out += f.first; total -= f.second }
        return out
    }
}

/**
 * MP2.3 B1: one source's strip index: per slot, coverage and the picture (pts, byte offset and length in the data file).
 * Not synchronised; [StripFile] guards it.
 */
class StripIndex(val slots: Int) {
    private val covered = BitSet(slots)
    private val pts = LongArray(slots) { -1L }
    private val offset = LongArray(slots)
    private val length = IntArray(slots)
    /** The stream's first picture, once a run that started at the stream's start has decoded it (-1: not known). */
    var firstPts = -1L
    var pictures = 0; private set

    fun covered(slot: Int) = slot in 0 until slots && covered[slot]
    fun has(slot: Int) = slot in 0 until slots && pts[slot] >= 0
    fun cover(slot: Int) { if (slot in 0 until slots) covered.set(slot) }
    fun pts(slot: Int) = pts[slot]
    fun offset(slot: Int) = offset[slot]
    fun length(slot: Int) = length[slot]
    val coveredCount get() = covered.cardinality()
    /** The end of the data the index refers to (bytes). */
    fun dataEnd(): Long { var e = 0L; for (k in 0 until slots) if (pts[k] >= 0) e = maxOf(e, offset[k] + length[k]); return e }

    fun put(slot: Int, pictureUs: Long, at: Long, len: Int) {
        require(slot in 0 until slots && pictureUs >= 0 && len > 0)
        if (pts[slot] < 0) pictures++
        pts[slot] = pictureUs; offset[slot] = at; length[slot] = len
    }

    /** Forgets [slot] (its picture and coverage): a picture whose bytes did not survive. */
    fun drop(slot: Int) { if (pts[slot] >= 0) pictures--; pts[slot] = -1; covered.clear(slot) }

    /** Slots of [range] covered (for reports and the test probe). */
    fun coveredIn(range: IntRange): Int { var n = 0; for (k in range.first.coerceAtLeast(0)..range.last.coerceAtMost(slots - 1)) if (covered[k]) n++; return n }

    /**
     * The slot whose picture is the one to show for [t]: the latest picture at or before [t] when every slot from there to
     * [t]'s slot is covered; before the stream's first picture, that picture; -1 (a miss) otherwise.
     */
    fun atOrBefore(t: Long): Int {
        if (t < 0) return -1
        var k = minOf(StripPolicy.slotOf(t), slots - 1)
        var walked = 0
        while (k >= 0 && walked <= StripPolicy.LOOKBACK_SLOTS) {
            if (!covered[k]) return -1
            if (pts[k] in 0..t) return k
            k--; walked++
        }
        if (k < 0 && firstPts >= 0) { val f = StripPolicy.slotOf(firstPts); if (f < slots && pts[f] == firstPts) return f }
        return -1
    }

    fun encode(): ByteArray {
        val bits = covered.toByteArray()
        val b = ByteBuffer.allocate(4 + 4 + 8 + 4 + 8 + 4 + bits.size + 4 + pictures * 24)
        b.putInt(MAGIC).putInt(StripPolicy.VERSION).putLong(StripPolicy.INTERVAL_US).putInt(slots).putLong(firstPts)
        b.putInt(bits.size).put(bits).putInt(pictures)
        for (k in 0 until slots) if (pts[k] >= 0) b.putInt(k).putLong(pts[k]).putLong(offset[k]).putInt(length[k])
        return b.array()
    }

    companion object {
        private const val MAGIC = 0x46535450 // "FSTP"
        /** The index in [bytes], or null when it is of another format, interval or source length (then it is rebuilt). */
        fun decode(bytes: ByteArray, slots: Int): StripIndex? = runCatching {
            val b = ByteBuffer.wrap(bytes)
            if (b.int != MAGIC || b.int != StripPolicy.VERSION || b.long != StripPolicy.INTERVAL_US || b.int != slots) return null
            val idx = StripIndex(slots)
            idx.firstPts = b.long
            val bits = ByteArray(b.int).also { b.get(it) }
            BitSet.valueOf(bits).let { set -> var k = set.nextSetBit(0); while (k in 0 until slots) { idx.covered.set(k); k = set.nextSetBit(k + 1) } }
            repeat(b.int) { val k = b.int; val p = b.long; val o = b.long; val l = b.int; if (k in 0 until slots && p >= 0 && o >= 0 && l > 0) idx.put(k, p, o, l) }
            idx
        }.getOrNull()
    }
}

/**
 * MP2.3 B1: what one decode run covers (pure): fed the pictures a decoder outputs in order after a seek, it says which
 * picture to keep for which slot and marks the slots whose every picture has been seen. Only slots in [scope] are covered
 * or kept: a pre-roll from a sync frame before the used range decodes, but adds nothing outside it.
 */
class StripRun(private val index: StripIndex, @Volatile var scope: List<IntRange>) {
    /** The first pts seen since the seek (Long.MIN_VALUE: the run started at the stream's start; MAX_VALUE: none yet). */
    private var seenFrom = Long.MAX_VALUE
    var lastPts = -1L; private set
    /** The next slot that may become covered (all slots before it are handled). */
    private var coverFrom = 0
    val positioned get() = seenFrom != Long.MAX_VALUE || fromStart
    private var fromStart = false

    /** After a seek; [fromStreamStart]: the decoder starts at the stream's first sample. */
    fun seeked(fromStreamStart: Boolean) {
        fromStart = fromStreamStart; seenFrom = if (fromStreamStart) Long.MIN_VALUE else Long.MAX_VALUE; lastPts = -1L; coverFrom = 0
    }

    /** Whether the decoder stands where a run from [startUs] can continue without a seek. */
    fun continuesAt(startUs: Long): Boolean = seenFrom != Long.MAX_VALUE && seenFrom <= startUs && lastPts >= 0 &&
        lastPts < startUs + StripPolicy.INTERVAL_US && startUs - lastPts <= StripPolicy.CONTINUE_US

    private fun firstWholeSlot(): Int = when (seenFrom) {
        Long.MIN_VALUE -> 0
        Long.MAX_VALUE -> Int.MAX_VALUE
        else -> ((seenFrom + StripPolicy.INTERVAL_US - 1) / StripPolicy.INTERVAL_US).toInt()
    }

    /** Whether the picture at [pts] would be kept (no state change): a slot seen whole, in scope, without a picture yet. */
    fun wants(pts: Long): Boolean {
        if (pts < 0 || pts < lastPts) return false
        val from = if (seenFrom == Long.MAX_VALUE) ((pts + StripPolicy.INTERVAL_US - 1) / StripPolicy.INTERVAL_US).toInt() else firstWholeSlot()
        val k = StripPolicy.slotOf(pts)
        return k >= from && k < index.slots && !index.has(k) && StripPolicy.inScope(scope, k)
    }

    /**
     * A picture at [pts] was decoded. Covers the slots before its slot that are now seen whole; returns the slot to keep
     * it in (the caller stores it, then the slot is covered once a later picture is seen) or -1.
     */
    fun picture(pts: Long): Int {
        if (pts < 0 || pts < lastPts) return -1
        val keep = wants(pts)
        if (seenFrom == Long.MAX_VALUE) seenFrom = pts
        if (seenFrom == Long.MIN_VALUE && index.firstPts < 0) index.firstPts = pts
        coverUpTo(StripPolicy.slotOf(pts) - 1)
        lastPts = pts
        return if (keep) StripPolicy.slotOf(pts) else -1
    }

    /** The stream ended after the last picture: every slot from the last picture's slot to the end is seen whole. */
    fun ended() { if (seenFrom != Long.MAX_VALUE) coverUpTo(index.slots - 1) }

    private fun coverUpTo(last: Int) {
        val from = maxOf(coverFrom, firstWholeSlot())
        if (last < from) return
        for (k in from..minOf(last, index.slots - 1)) if (StripPolicy.inScope(scope, k)) index.cover(k)
        coverFrom = last + 1
    }
}

/** MP2.3 B1: which run the builder decodes next (pure): the uncovered part of the scope nearest the playhead, outward. */
object StripPlan {
    /**
     * The next run (slots) over [scope], given what [covered], nearest [focusSlot]: inside a gap that holds the focus,
     * forward from the focus; a gap after it from its start; a gap before it, its last [runSlots] (expanding outward from
     * the playhead, as the peaks fill visible-first). Null when the scope is covered.
     */
    fun nextRun(scope: List<IntRange>, covered: (Int) -> Boolean, focusSlot: Int, runSlots: Int = StripPolicy.runSlots): IntRange? {
        var best: IntRange? = null; var bestDistance = Long.MAX_VALUE
        for (r in scope) {
            var k = r.first
            while (k <= r.last) {
                if (covered(k)) { k++; continue }
                var e = k; while (e + 1 <= r.last && !covered(e + 1)) e++
                // The gap [k, e].
                val run = when {
                    focusSlot in k..e -> focusSlot..minOf(e, focusSlot + runSlots - 1)
                    k > focusSlot -> k..minOf(e, k + runSlots - 1)
                    else -> maxOf(k, e - runSlots + 1)..e
                }
                val distance = when { focusSlot in k..e -> 0L; k > focusSlot -> (k - focusSlot).toLong(); else -> (focusSlot - e).toLong() }
                // Ties: forward first (playback and most scrubs start forward of what is behind).
                if (distance < bestDistance || (distance == bestDistance && best != null && run.first > best.first)) { best = run; bestDistance = distance }
                k = e + 1
            }
        }
        return best
    }
}

/**
 * MP2.3 B1: one source's strip on disk: an append-only data file of encoded pictures ([base].strip) and the index
 * ([base].sidx, written whole and renamed into place, so a crash leaves the previous index). On open, pictures whose bytes
 * are missing are dropped and the data file is cut back to what the index refers to (partial coverage survives restarts).
 * Thread-safe: the builder appends, the renderer reads.
 */
class StripFile(dir: File, val base: String, slots: Int, private val allocated: (File) -> Long = { it.length() }) : java.io.Closeable {
    val data = File(dir, "$base.strip")
    val indexFile = File(dir, "$base.sidx")
    private val tmp = File(dir, "$base.tmp")
    val index: StripIndex
    private val raf: RandomAccessFile
    private var dirty = 0
    @Volatile var closed = false; private set

    init {
        val loaded = if (indexFile.isFile) StripIndex.decode(indexFile.readBytes(), slots) else null
        index = loaded ?: StripIndex(slots)
        if (loaded == null) { data.delete(); indexFile.delete() }
        raf = RandomAccessFile(data, "rw")
        val len = raf.length()
        for (k in 0 until slots) if (index.has(k) && index.offset(k) + index.length(k) > len) index.drop(k)
        val end = index.dataEnd()
        if (len > end) raf.setLength(end)
        data.setLastModified(System.currentTimeMillis())
    }

    /** Appends a picture for [slot] at [pts] (the builder). */
    @Synchronized fun put(slot: Int, pts: Long, bytes: ByteArray, len: Int = bytes.size) {
        if (closed) return
        val at = raf.length()
        raf.seek(at); raf.write(bytes, 0, len)
        index.put(slot, pts, at, len)
        if (++dirty >= StripPolicy.PERSIST_EVERY) persist()
    }

    /** Writes the index (whole, then renamed over the previous one). */
    @Synchronized fun persist() {
        if (closed) return
        dirty = 0
        tmp.writeBytes(index.encode())
        if (!tmp.renameTo(indexFile)) { indexFile.delete(); tmp.renameTo(indexFile) }
    }

    @Synchronized fun <T> withIndex(block: (StripIndex) -> T): T = block(index)

    /** The slot to show for [t] (see [StripIndex.atOrBefore]) with its pts, or null. */
    @Synchronized fun lookup(t: Long): Pair<Int, Long>? = index.atOrBefore(t).takeIf { it >= 0 }?.let { it to index.pts(it) }

    /** The encoded picture of [slot], or null. */
    @Synchronized fun read(slot: Int): ByteArray? {
        if (closed || !index.has(slot)) return null
        val out = ByteArray(index.length(slot))
        raf.seek(index.offset(slot)); raf.readFully(out)
        return out
    }

    /** Bytes the strip occupies on disk (allocated). */
    fun allocatedBytes(): Long = allocated(data) + (if (indexFile.isFile) allocated(indexFile) else 0L)

    @Synchronized override fun close() { if (closed) return; if (dirty > 0) persist(); closed = true; raf.close() }
}

/** MP2.3 B1: a byte-bounded LRU (pure): least recently used entries go when the total passes [capBytes]. */
class ByteLru<K, V>(private val capBytes: Long, private val sizeOf: (V) -> Long, private val onEvict: (V) -> Unit) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    var bytes = 0L; private set
    val size get() = map.size
    fun get(key: K): V? = map[key]
    fun put(key: K, value: V) {
        map.remove(key)?.let { bytes -= sizeOf(it); if (it !== value) onEvict(it) }
        map[key] = value; bytes += sizeOf(value)
        val it = map.entries.iterator()
        while (bytes > capBytes && map.size > 1 && it.hasNext()) {
            val e = it.next(); if (e.key == key) continue
            it.remove(); bytes -= sizeOf(e.value); onEvict(e.value)
        }
    }
    fun clear() { map.values.forEach(onEvict); map.clear(); bytes = 0 }
}
