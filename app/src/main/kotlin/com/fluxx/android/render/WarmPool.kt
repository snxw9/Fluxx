package com.fluxx.android.render

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * MP2.1 B2: the decoders warmed for an upcoming cut or re-entry, between [UpcomingUse] and the renderer's pool (JVM tested
 * with fake decoders). The renderer drives it on its own thread once per preview frame ([frame]) and takes a warm decoder
 * when its layer needs one ([take]); the opening and positioning run on [executor] (one at a time, background priority).
 *
 * - A warm-up opens the decoder (or takes the pooled one the plan re-uses), decodes up to and including the first frame
 *   for its target source time, then holds it (nothing more is decoded) until the layer takes it.
 * - The work polls [gate] between decode steps: it pauses while the current frame is late unless its deadline is near
 *   ([MediaWorkPolicy.warmMayRun]); a frame that needs a warm-up still running waits for it (with its thread raised).
 * - A warm decoder the plan no longer wants (pause, scrub, a jump, the clip edited away) is released after the 3 s delay,
 *   or at once when room is needed ([dropForRoom]: the current frame always wins) or memory is trimmed ([trim]).
 * - Export never warms: with [export] the planner is never consulted.
 * - Each warm-up's open + seek + first frame is measured per decoder class (an exponential average, nothing persisted);
 *   the planner's horizon comes from it.
 */
class WarmPool<D : Any>(
    private val ops: Ops<D>,
    private val export: Boolean,
    private val executor: Executor,
    releaseClock: () -> Long,
    private val planner: (UpcomingUse.Input) -> UpcomingUse.Plan = UpcomingUse::plan,
    /** Blocks (between decode steps) while the warm-up must not run yet ([MediaWorkPolicy.warmMayRun]). */
    private val gate: (UpcomingUse.Warm, () -> Boolean) -> Unit = { _, _ -> },
    private val tid: () -> Int = { 0 },
    private val boost: (Int, Boolean) -> Unit = { _, _ -> },
    private val events: () -> ((String) -> Unit)? = { null },
    private val label: (String) -> String = { it },
) {
    interface Ops<D> {
        fun open(uri: String): D
        /** Decodes up to and including the first frame for [sourceUs] ([cancelled] is polled between steps). */
        fun position(decoder: D, sourceUs: Long, cancelled: () -> Boolean)
        fun close(decoder: D)
        fun bytes(decoder: D): Long
        fun decoderClass(decoder: D): String
    }
    enum class State { RUNNING, READY, GONE }
    private class Entry<D>(val warm: UpcomingUse.Warm, var decoder: D?, val reused: Boolean) {
        @Volatile var state = State.RUNNING
        @Volatile var cancelled = false
        @Volatile var tid = 0
        @Volatile var bytes = warm.bytes
        val done = CountDownLatch(1)
    }
    private val entries = LinkedHashMap<Long, Entry<D>>()
    private val unwanted = DelayedRelease<Long>(clock = releaseClock)
    private val costs = HashMap<String, Long>()
    /** The last plan (empty while not playing): [UpcomingUse.Plan.keepUris] guards the renderer's hidden release. */
    var lastPlan = UpcomingUse.Plan.NONE; private set
    private val blockedNow = HashSet<Long>()
    /** Counters since creation: started, ready, used, released unused, failed, blocked (budget), blocked (cap), waits. */
    val counts = LongArray(8)

    @Synchronized fun costUs(decoderClass: String): Long? = costs[decoderClass]
    /** Feeds one measured open + seek + first frame (us) of [decoderClass] into its average. */
    @Synchronized fun measured(decoderClass: String, us: Long) {
        costs[decoderClass] = costs[decoderClass]?.let { (it * 7 + us * 3) / 10 } ?: us
    }

    /** Decoders held (running or ready), and their memory: the renderer counts them against its cap and budget. */
    val count: Int get() = entries.values.count { it.state != State.GONE }
    val bytes: Long get() = entries.values.filter { it.state != State.GONE }.sumOf { it.bytes }
    fun warming(): List<UpcomingUse.Warming> = entries.values.filter { it.state != State.GONE }.map { UpcomingUse.Warming(it.warm.layerId, it.bytes) }
    fun has(layerId: Long): Boolean = entries[layerId]?.let { it.state != State.GONE } == true

    /**
     * Once per preview frame. [input] is built only when the planner runs (preview playback). [takePooled] removes a
     * pooled decoder the plan re-uses from the renderer's pool and returns it (null: it is gone).
     */
    fun frame(playing: Boolean, input: () -> UpcomingUse.Input, takePooled: (String) -> D?) {
        prune()
        val plan = if (UpcomingUse.enabled(export, playing)) planner(input()) else UpcomingUse.Plan.NONE
        lastPlan = plan
        for ((w, why) in plan.blocked) if (blockedNow.add(w.layerId)) {
            counts[if (why == UpcomingUse.Blocked.BUDGET) 5 else 6]++
            emit("warm-blocked layer=${w.layerId} key=${label(w.uri)} why=$why kind=${w.kind} deadline=${w.deadlineUs}")
        }
        blockedNow.retainAll(plan.blocked.mapTo(HashSet()) { it.first.layerId })
        for (id in unwanted.due(entries.keys.toList(), plan.wanted)) release(id, "unwanted")
        if (entries.values.any { it.state == State.RUNNING }) return
        val next = plan.due.firstOrNull { !has(it.layerId) } ?: return
        start(next, next.reuseKey?.let(takePooled))
    }

    private fun start(w: UpcomingUse.Warm, pooled: D?) {
        val e = Entry(w, pooled, pooled != null)
        if (pooled != null) e.bytes = ops.bytes(pooled)
        entries[w.layerId] = e
        counts[0]++
        emit("warm-start layer=${w.layerId} key=${label(w.uri)} kind=${w.kind} src=${w.sourceUs} deadline=${w.deadlineUs} reuse=${pooled != null}")
        executor.execute {
            e.tid = tid()
            val t0 = System.nanoTime()
            var d: D? = pooled
            try {
                if (d == null) {
                    gate(w) { e.cancelled }
                    if (e.cancelled) throw java.util.concurrent.CancellationException("warm-up cancelled")
                    d = ops.open(w.uri)
                    synchronized(e) { e.decoder = d; e.bytes = ops.bytes(d) }
                }
                ops.position(d, w.sourceUs) { gate(w) { e.cancelled }; e.cancelled }
                val us = (System.nanoTime() - t0) / 1000
                val ok = synchronized(e) { if (e.cancelled) false else { e.state = State.READY; true } }
                if (ok) {
                    counts[1]++
                    if (!e.reused) measured(ops.decoderClass(d), us)
                    emit("warm-ready layer=${w.layerId} key=${label(w.uri)} us=$us reuse=${e.reused}")
                } else closeGone(e, "cancelled")
            } catch (t: Throwable) {
                if (t !is java.util.concurrent.CancellationException) { counts[4]++; emit("warm-failed layer=${w.layerId} key=${label(w.uri)} ${t.javaClass.simpleName}") }
                synchronized(e) { if (e.decoder == null) e.decoder = d }
                closeGone(e, if (t is java.util.concurrent.CancellationException) "cancelled" else "failed")
            } finally { e.done.countDown() }
        }
    }

    private fun closeGone(e: Entry<D>, why: String) {
        val d = synchronized(e) { val d = e.decoder; e.decoder = null; e.state = State.GONE; d }
        if (d != null) runCatching { ops.close(d) }
        if (why != "used") emit("warm-release layer=${e.warm.layerId} key=${label(e.warm.uri)} why=$why")
    }

    private fun prune() { entries.values.removeAll { it.state == State.GONE && it.done.count == 0L } }

    /** Releases [layerId]'s warm decoder now (a running one is cancelled and closes itself when it stops). */
    private fun release(layerId: Long, why: String) {
        val e = entries[layerId] ?: return
        val running = synchronized(e) { if (e.state == State.RUNNING) { e.cancelled = true; true } else false }
        if (!running && e.state == State.READY) { counts[3]++; closeGone(e, why) }
        else if (running) counts[3]++
    }

    /**
     * The warm decoder of [layerId] if it is ready and [accept]s the request (positioned for it); one still running is
     * waited for (its thread raised) up to [waitMs]. A ready one that does not fit is released (it is stale).
     */
    fun take(layerId: Long, waitMs: Long = 1500, accept: (D) -> Boolean): D? {
        val e = entries[layerId] ?: return null
        if (e.state == State.RUNNING) {
            counts[7]++
            val t0 = System.nanoTime()
            val worker = e.tid
            if (worker != 0) boost(worker, true)
            try { e.done.await(waitMs, TimeUnit.MILLISECONDS) } finally { if (worker != 0) boost(worker, false) }
            emit("warm-wait layer=$layerId key=${label(e.warm.uri)} ms=${(System.nanoTime() - t0) / 1_000_000} state=${e.state}")
        }
        if (e.state != State.READY) return null
        val d = e.decoder ?: return null
        if (!accept(d)) { release(layerId, "stale"); return null }
        synchronized(e) { e.decoder = null; e.state = State.GONE }
        entries.remove(layerId)
        counts[2]++
        emit("warm-used layer=$layerId key=${label(e.warm.uri)}")
        return d
    }

    /** Drops [layerId]'s warm decoder at once (its layer no longer needs it: the open decoder serves it). */
    fun discard(layerId: Long) { if (has(layerId)) release(layerId, "not-needed") }

    /**
     * Frees one warm decoder for the current frame: an unwanted one first, then the one due last. A running one is
     * cancelled and waited for (bounded). False when none is held.
     */
    fun dropForRoom(waitMs: Long = 1500): Boolean {
        val live = entries.values.filter { it.state != State.GONE }
        val victim = live.firstOrNull { it.warm.layerId !in lastPlan.wanted } ?: live.maxByOrNull { it.warm.deadlineUs } ?: return false
        release(victim.warm.layerId, "room")
        if (victim.state == State.RUNNING || victim.done.count > 0) victim.done.await(waitMs, TimeUnit.MILLISECONDS)
        return true
    }

    /** onTrimMemory: every warm decoder the plan does not want now is released at once. */
    fun trim() { for (id in entries.keys.toList()) if (id !in lastPlan.wanted) release(id, "trim") }

    fun close() {
        for (id in entries.keys.toList()) release(id, "close")
        for (e in entries.values) e.done.await(2000, TimeUnit.MILLISECONDS)
        for (e in entries.values.toList()) if (e.state == State.READY) closeGone(e, "close")
        entries.clear()
    }

    private fun emit(line: String) { events()?.invoke(line) }
}
