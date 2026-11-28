package com.fluxx.android.render

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * MP2.3 B1: builds the scrub strips of a preview's sources ([StripPolicy]), one decoder at a time, in the background.
 * - Planned on the render thread ([tick], while the preview is paused and idle): the sources in order of their distance
 *   from the playhead, each over its USED range only ([StripPolicy.scopeSlots]), the part nearest the playhead first
 *   ([StripPlan.nextRun]); never a whole-source decode.
 * - Its decoder counts against the renderer's decoder cap and memory budget like any other: [tick] reserves the room
 *   ([holding], [reservedBytes], [reservedMime]) only when [room] says it fits (else it records why and waits); the
 *   renderer drops it first when a frame needs the room ([dropForRoom]).
 * - Runs only while [mayRun] (nothing plays or scrubs, the preview has been idle for 300 ms: [MediaWorkPolicy]); paused
 *   otherwise, and its decoder closes once paused for [StripPolicy.PAUSED_CLOSE_MS] or when done. A Play press or
 *   onTrimMemory ([cancel], [trim]) stops it at its next picture. A source removed from the project (or trimmed so the
 *   run is outside its used range) stops it; what is covered stays valid.
 * - [export]: never plans, opens or reads anything (export never builds or reads the strip).
 */
class StripBuilder<D>(private val ops: Ops<D>, private val export: Boolean, private val executor: Executor,
                      private val mayRun: () -> Boolean, private val clockMs: () -> Long,
                      private val sleep: (Long) -> Unit = { Thread.sleep(it) },
                      private val events: () -> ((String) -> Unit)? = { null }, private val label: (String) -> String = MediaProbes::key) {
    interface Ops<D> {
        /** The strip of [uri] (opened, its index loaded), or null when it cannot be had. */
        fun strip(uri: String, durationUs: Long): StripFile?
        /** A CPU-output decoder of [uri] for building. */
        fun open(uri: String): D
        /** Seeks to the sync frame at or before [us]; true when that is the stream's first sample. */
        fun seek(decoder: D, us: Long): Boolean
        /** The next picture in output order: its pts and, when [want] says so, its encoded bytes; null at the end of the stream. */
        fun next(decoder: D, want: (Long) -> Boolean): Picture?
        fun close(decoder: D)
        /** After each run (the disk budget): false stops building (this source alone fills the cache). */
        fun afterRun(file: StripFile): Boolean = true
    }
    class Picture(val pts: Long, val bytes: ByteArray?, val length: Int = bytes?.size ?: 0)
    /** A source to build: its used [windows] (source µs), the source time nearest the playhead, and its decoder's cost. */
    data class Source(val uri: String, val windows: List<LongRange>, val focusUs: Long, val durationUs: Long, val bytes: Long, val mime: String)

    private class Job(val source: Source, val file: StripFile, @Volatile var scope: List<IntRange>, @Volatile var focusUs: Long) {
        @Volatile var cancel = false
        val released = CountDownLatch(1)
    }
    @Volatile private var job: Job? = null
    private val failed = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var trimmed = false

    /** The builder holds (or is about to open) a decoder: it counts as one against the cap. */
    val holding: Boolean get() = job != null
    @Volatile var reservedBytes = 0L; private set
    @Volatile var reservedMime: String? = null; private set
    /** More work is planned or running: the paused preview keeps ticking. */
    @Volatile var wantsTick = false; private set
    /** Runs, pictures stored, seeks, blocked (cap / budget / instances), failed, closed paused, cancelled (counters). */
    val counts = LongArray(7)

    /** The render thread, paused and idle: start (or update) the work for [sources] (in priority order). */
    fun tick(sources: List<Source>, room: (Source) -> String?) {
        if (export) return
        val j = job
        if (j != null) {
            val s = sources.firstOrNull { it.uri == j.source.uri }
            if (s == null) { j.cancel = true; emit("strip-cancel key=${label(j.source.uri)} why=removed") }
            else { j.scope = StripPolicy.scopeSlots(s.windows, s.durationUs); j.focusUs = s.focusUs }
            wantsTick = true; return
        }
        trimmed = false
        var work = false
        for (s in sources) {
            if (s.uri in failed) continue
            val file = ops.strip(s.uri, s.durationUs) ?: continue
            val scope = StripPolicy.scopeSlots(s.windows, s.durationUs)
            file.withIndex { idx -> StripPlan.nextRun(scope, idx::covered, StripPolicy.slotOf(s.focusUs)) } ?: continue
            work = true
            if (!mayRun()) break
            val why = room(s)
            if (why != null) {
                counts[3]++; emit("strip-blocked key=${label(s.uri)} why=$why")
                break
            }
            start(s, file, scope); break
        }
        wantsTick = work
    }

    private fun start(s: Source, file: StripFile, scope: List<IntRange>) {
        val j = Job(s, file, scope, s.focusUs)
        reservedBytes = s.bytes; reservedMime = s.mime; job = j
        try { executor.execute { build(j) } } catch (e: Exception) { release(j) }
    }

    /** Stops the running work at its next picture (a Play press). Does not wait. */
    fun cancel() { job?.let { if (!it.cancel) { it.cancel = true; counts[6]++ } } }
    /** onTrimMemory: stop now (the next [tick] may start again). */
    fun trim() { trimmed = true; cancel() }

    /** The render thread needs the room: stop the work and wait (bounded) until its decoder is closed. */
    fun dropForRoom(timeoutMs: Long = 300): Boolean {
        val j = job ?: return false
        j.cancel = true
        return runCatching { j.released.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    }

    fun close() { val j = job ?: return; j.cancel = true; runCatching { j.released.await(1000, TimeUnit.MILLISECONDS) } }

    /** Blocks while the work may not run; the reason to stop (cancelled, trim, paused for 3 s) or null to go on. */
    private fun gate(j: Job): String? {
        var pausedAt = -1L
        while (true) {
            if (j.cancel) return "cancelled"
            if (trimmed) return "trim"
            if (mayRun()) return null
            if (pausedAt < 0) pausedAt = clockMs()
            if (clockMs() - pausedAt >= StripPolicy.PAUSED_CLOSE_MS) return "paused"
            sleep(20)
        }
    }

    private fun build(j: Job) {
        val uri = j.source.uri; val file = j.file
        val run = StripRun(file.index, j.scope)
        var decoder: D? = null
        var why = "done"
        val t0 = clockMs(); var activeMs = 0L; var decoded = 0; var stored = 0; var seeks = 0; var runs = 0
        var firstUs = Long.MAX_VALUE; var lastUs = -1L
        try {
            loop@ while (true) {
                gate(j)?.let { why = it; break@loop }
                val scope = j.scope
                file.withIndex { run.scope = scope }
                if (scope.isEmpty()) { why = "out-of-scope"; break }
                val next = file.withIndex { idx -> StripPlan.nextRun(scope, idx::covered, StripPolicy.slotOf(j.focusUs)) } ?: break
                val coveredBefore = file.withIndex { it.coveredCount }
                val a = clockMs()
                val d = decoder ?: ops.open(uri).also { decoder = it; emit("strip-open key=${label(uri)}") }
                val startUs = StripPolicy.slotStart(next.first)
                if (!file.withIndex { run.continuesAt(startUs) }) {
                    val fromStart = ops.seek(d, startUs)
                    file.withIndex { run.seeked(fromStart) }
                    seeks++; counts[2]++
                    emit("strip-seek key=${label(uri)} us=$startUs run=${next.first}..${next.last}")
                }
                activeMs += clockMs() - a
                val endUs = StripPolicy.slotStart(next.last + 1)
                // Never decodes past the used range: a run ends within its scope range (plus the slot that proves it whole).
                val scopeEndUs = StripPolicy.slotStart((scope.firstOrNull { next.last in it }?.last ?: next.last) + 2)
                while (true) {
                    gate(j)?.let { why = it; break@loop }
                    val b = clockMs()
                    val p = ops.next(d) { pts -> file.withIndex { run.wants(pts) } }
                    if (p == null) { file.withIndex { run.ended() }; activeMs += clockMs() - b; break }
                    decoded++
                    firstUs = minOf(firstUs, p.pts); lastUs = maxOf(lastUs, p.pts)
                    val slot = file.withIndex { run.picture(p.pts) }
                    if (slot >= 0 && p.bytes != null && p.length > 0) { file.put(slot, p.pts, p.bytes, p.length); stored++; counts[1]++ }
                    activeMs += clockMs() - b
                    if (p.pts >= endUs || p.pts >= scopeEndUs) break
                }
                file.persist(); runs++; counts[0]++
                if (file.withIndex { it.coveredCount } <= coveredBefore) { why = "stalled"; failed += uri; break }
                if (!ops.afterRun(file)) { why = "cap"; break }
            }
        } catch (e: Exception) {
            why = "failed ${e.javaClass.simpleName}"; failed += uri; counts[4]++
        } finally {
            decoder?.let { d -> runCatching { ops.close(d) } }
            runCatching { file.persist() }
            if (why == "paused") counts[5]++
            val cov = file.withIndex { idx -> j.scope.sumOf { idx.coveredIn(it) } } to j.scope.sumOf { it.last - it.first + 1 }
            emit("strip-stop key=${label(uri)} why=${why.replace(' ', '_')} runs=$runs seeks=$seeks decoded=$decoded stored=$stored activems=$activeMs wallms=${clockMs() - t0} " +
                "decodedus=${if (lastUs >= 0) firstUs else -1}..$lastUs covered=${cov.first}/${cov.second} bytes=${file.allocatedBytes()}")
            release(j)
        }
    }

    private fun release(j: Job) {
        if (job === j) { job = null; reservedBytes = 0L; reservedMime = null }
        j.released.countDown()
    }

    private fun emit(e: String) { events()?.invoke(e) }
}

/**
 * MP2.3 B2: the renderer's in-RAM strip pictures (uploaded, ready to draw), looked up by source time. [export]: never
 * touches the store (export never reads the strip). [decode] turns a picture's bytes into a drawable (null on failure).
 */
class StripFrames<V : Any>(private val export: Boolean, private val store: (String) -> StripFile?, capBytes: Long,
                           private val decode: (ByteArray) -> V?, sizeOf: (V) -> Long, onEvict: (V) -> Unit) {
    class Hit<V>(val picture: V, val slot: Int, val pts: Long, val cached: Boolean)
    private val ram = ByteLru<Long, V>(capBytes, sizeOf, onEvict)
    private val ids = HashMap<String, Int>()

    /** The picture to show for [uri] at source time [t] (never later than [t]), or null (not covered: fall back). */
    fun frame(uri: String, t: Long): Hit<V>? {
        if (export) return null
        val file = store(uri) ?: return null
        val (slot, pts) = file.lookup(t) ?: return null
        // A long key without allocation: the source's small id in the top bits, the slot below.
        val key = (ids.getOrPut(uri) { ids.size }.toLong() shl 32) or slot.toLong()
        ram.get(key)?.let { return Hit(it, slot, pts, true) }
        val bytes = file.read(slot) ?: return null
        val v = decode(bytes) ?: return null
        ram.put(key, v)
        return Hit(v, slot, pts, false)
    }
    val bytes get() = ram.bytes
    fun clear() = ram.clear()
}
