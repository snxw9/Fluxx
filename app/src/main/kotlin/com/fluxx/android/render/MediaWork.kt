package com.fluxx.android.render

import android.os.Process

/**
 * MP1 B5: media work priorities (pure, JVM tested).
 * - PLAYBACK: the audio feeding thread, active decoders, and the first 0.5 s of audio at a start position (any PCM decode
 *   a reader is waiting for, plus [PcmScope.LEAD_FRAMES] ahead of it).
 * - SCRUB: scrub frames and their exact follow-up (the preview worker).
 * - BACKGROUND: PCM cache filling beyond that, waveform peaks, timeline thumbnails. These pause at their next checkpoint
 *   while playback or a scrub is active and resume after [RESUME_IDLE_NS] of idle; jobs outside the used range are cancelled.
 */
object MediaWorkPolicy {
    enum class Priority { PLAYBACK, SCRUB, BACKGROUND }
    const val RESUME_IDLE_NS = 300_000_000L

    fun backgroundMayRun(playing: Boolean, scrubbing: Boolean, lastForegroundNs: Long, nowNs: Long): Boolean =
        !playing && !scrubbing && nowNs - lastForegroundNs >= RESUME_IDLE_NS

    /** A background job over [job] (source µs or frames) is cancelled when it no longer overlaps the used [scope]. */
    fun cancel(job: LongRange, scope: List<LongRange>): Boolean = scope.none { job.first <= it.last && it.first <= job.last }

    /** A decode at [runAt] is PLAYBACK work while a reader needs it: within [leadFrames] of [demandFrame] (-1: no reader). */
    fun urgent(runAt: Long, demandFrame: Long, leadFrames: Long): Boolean = demandFrame >= 0 && runAt < demandFrame + leadFrames
}

/**
 * MP1 B5: the one scheduler for media jobs (process-wide). The preview reports playback and scrubbing; background jobs call
 * [checkpoint] between units of work and run at [Process.THREAD_PRIORITY_BACKGROUND] ([background]).
 */
object MediaWork {
    @Volatile private var playing = false
    @Volatile private var scrubUntilNs = 0L
    @Volatile private var lastForegroundNs = Long.MIN_VALUE / 2

    /** The preview's playback state (any thread). Stopping counts as the last foreground moment. */
    fun playback(active: Boolean) { if (active || playing) lastForegroundNs = System.nanoTime(); playing = active }
    /** A scrub seek arrived (it stays a scrub for [ScrubPolicy.GAP_NS]). */
    fun scrub() { val now = System.nanoTime(); scrubUntilNs = now + ScrubPolicy.GAP_NS; lastForegroundNs = now }

    fun mayRunBackground(now: Long = System.nanoTime()): Boolean =
        MediaWorkPolicy.backgroundMayRun(playing, now < scrubUntilNs, if (playing) now else lastForegroundNs, now)

    /**
     * A background job's checkpoint: blocks while playback or a scrub is active (and for 300 ms after). Returns early when
     * the work became [urgent] (true) or was [cancelled] (false).
     */
    fun checkpoint(cancelled: () -> Boolean = { false }, urgent: () -> Boolean = { false }): Boolean {
        var waited = false
        while (!mayRunBackground()) {
            if (cancelled()) return false
            if (urgent()) return true
            if (!waited) { waited = true; MediaProbes.events?.invoke("work-paused ${Thread.currentThread().name}") }
            Thread.sleep(20)
        }
        if (waited) MediaProbes.events?.invoke("work-resumed ${Thread.currentThread().name}")
        return !cancelled()
    }

    /** Runs [block] at background thread priority, restoring the thread's priority after (pool threads are shared). */
    inline fun <T> background(block: () -> T): T {
        val tid = Process.myTid(); val old = Process.getThreadPriority(tid)
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try { return block() } finally { Process.setThreadPriority(tid, old) }
    }
}
