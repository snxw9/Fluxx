package com.fluxx.android.render

/**
 * TEXT PERF 10: renderer activity for idle work. Every rendered frame (preview playback, scrubbing, export) marks the
 * gate; idle work treats the renderer as busy until [quietNs] pass without a frame. Marking is one volatile store with
 * no allocation, so it is safe on the render thread.
 */
class FrameActivityGate(private val quietNs: Long) {
    @Volatile private var lastFrameNs = Long.MIN_VALUE
    @Volatile var frames = 0L; private set
    fun frame(nowNs: Long) { lastFrameNs = nowNs; frames++ }
    fun busy(nowNs: Long): Boolean = lastFrameNs != Long.MIN_VALUE && nowNs - lastFrameNs < quietNs
}

/**
 * One step of the bundled-glyph idle prewarm (TextIdlePrewarm.foreground). The prewarm used to run continuously after
 * a layout preparation and took one core for the whole of playback (TEXT PERF 10 profile: 48-66% of app CPU). It also
 * moved the shared catalog's rasterisation counter inside the F1 measurement window. It now pauses while frames are
 * rendering and resumes from the same cursor once the renderer has been quiet for the gate's window.
 */
class IdleWarmLoop(private val total: Int, private val gate: FrameActivityGate, private val warm: (Int) -> Long) {
    enum class Step { WARMED, PAUSED, DONE }
    var cursor = 0; private set
    var bytes = 0L; private set
    val finished get() = cursor >= total
    fun next(nowNs: Long): Step {
        if (cursor >= total) return Step.DONE
        if (gate.busy(nowNs)) return Step.PAUSED
        bytes += warm(cursor); cursor++
        return Step.WARMED
    }
}
