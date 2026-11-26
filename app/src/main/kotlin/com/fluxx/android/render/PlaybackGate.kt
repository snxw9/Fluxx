package com.fluxx.android.render

import java.util.concurrent.atomic.AtomicLong

/**
 * TEXT PERF 11: frames the interactive preview has presented (finished and handed to the swapchain), counted by
 * [CompositionRenderer.renderPreview]. One increment per presented frame, no allocation.
 */
object PreviewFrames {
    private val presented = AtomicLong()
    val count: Long get() = presented.get()
    internal fun presented() { presented.incrementAndGet() }
    /**
     * AUD1: a TEST-ONLY observer of presented preview frames: (frame time us, render start ns, presented ns). Null in the
     * app; the benchmark sets it to time video against audio. No behaviour change.
     */
    @Volatile var testObserver: ((Long, Long, Long) -> Unit)? = null
    /** AUD2: TEST-ONLY playback lifecycle events ("playback-create", "track-play", "playback-close", "gain-live"). Null in the app. */
    @Volatile var testPlayback: ((String) -> Unit)? = null
    /** AUD2: TEST-ONLY clock reads: (now ns, head frames, timestamp valid, position us). Null in the app. */
    @Volatile var testClock: ((Long, Long, Boolean, Long) -> Unit)? = null
}

/**
 * TEXT PERF 11: when the playback clock may start. The clock is the AudioTrack playback head ([PcmPlayback]); it used
 * to start the moment playback was created, before the first frame of that playback was rendered. A slow first frame
 * (a renderer rebuilt after the app returns from the background, cold glyph tiers, a large project) therefore let the
 * head run ahead, and the next frame jumped by the whole stall; with several slow frames playback skipped to the end.
 * The gate opens once a frame rendered after the gate was created has been presented (or after [holdNs], so a
 * renderer that never presents cannot hold audio forever). Until then the position stays at the start.
 */
internal class PlaybackGate(private val presented: () -> Long, private val nowNs: () -> Long,
                            private val holdNs: Long = HOLD_NS) {
    private val baseline = presented()
    private val created = nowNs()
    var open = false; private set
    /** Polled by the clock's reader; returns true from the moment the gate opens. */
    fun poll(): Boolean {
        if (!open && (presented() != baseline || nowNs() - created >= holdNs)) open = true
        return open
    }
    companion object { const val HOLD_NS = 2_000_000_000L }
}
