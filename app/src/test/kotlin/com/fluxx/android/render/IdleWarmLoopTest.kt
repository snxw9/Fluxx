package com.fluxx.android.render

import org.junit.Assert.*
import org.junit.Test

/** TEXT PERF 10: the idle prewarm never rasterises while frames are rendering, and resumes where it stopped. */
class IdleWarmLoopTest {
    private val ms = 1_000_000L
    private class Counter { var rasterisations = 0 }
    private fun loop(total: Int, gate: FrameActivityGate, counter: Counter) = IdleWarmLoop(total, gate) { counter.rasterisations++; 100L }

    @Test fun theRasterisationCounterDoesNotMoveDuringSimulatedPlayback() {
        val gate = FrameActivityGate(750 * ms); val counter = Counter(); val loop = loop(2280, gate, counter)
        var now = 0L
        // Idle before playback: the prewarm runs.
        repeat(10) { assertEquals(IdleWarmLoop.Step.WARMED, loop.next(now)); now += ms }
        val warmedBefore = counter.rasterisations
        // 10 s of 60 fps playback; the idle thread gets scheduled many times between frames.
        for (frame in 0 until 600) {
            gate.frame(now)
            repeat(20) { assertEquals(IdleWarmLoop.Step.PAUSED, loop.next(now + it * (16 * ms / 20))) }
            now += 16_667_000L
        }
        assertEquals("no rasterisation while frames render", warmedBefore, counter.rasterisations)
        // Still inside the quiet window after the last frame: paused.
        assertEquals(IdleWarmLoop.Step.PAUSED, loop.next(now + 700 * ms))
        // Quiet: resumes from the same cursor.
        assertEquals(IdleWarmLoop.Step.WARMED, loop.next(now + 760 * ms))
        assertEquals(warmedBefore + 1, counter.rasterisations); assertEquals(warmedBefore + 1, loop.cursor)
    }
    @Test fun scrubbingPausesBetweenSeeksAndTheLoopFinishesWhenIdle() {
        val gate = FrameActivityGate(750 * ms); val counter = Counter(); val loop = loop(50, gate, counter)
        var now = 1_000 * ms
        // Scrubbing: irregular frames 30-400 ms apart.
        val gaps = longArrayOf(30, 120, 400, 60, 250, 90)
        for (i in 0 until 60) { gate.frame(now); assertEquals(IdleWarmLoop.Step.PAUSED, loop.next(now + 10 * ms)); now += gaps[i % gaps.size] * ms }
        assertEquals(0, counter.rasterisations)
        now += 800 * ms
        while (loop.next(now) == IdleWarmLoop.Step.WARMED) now += ms
        assertEquals(50, counter.rasterisations); assertTrue(loop.finished)
        assertEquals(IdleWarmLoop.Step.DONE, loop.next(now))
    }
    @Test fun noFramesYetIsIdle() {
        val gate = FrameActivityGate(750 * ms)
        assertFalse(gate.busy(0)); assertFalse(gate.busy(Long.MAX_VALUE))
        gate.frame(5 * ms); assertTrue(gate.busy(5 * ms)); assertTrue(gate.busy(754 * ms)); assertFalse(gate.busy(756 * ms))
    }
    @Test fun everyRenderedFrameMarksTheGate() {
        val source = java.io.File("src/main/kotlin/com/fluxx/android/render/CompositionRenderer.kt").readText()
        val body = source.substringAfter("private fun renderTarget(").substringBefore("// Export pins")
        assertTrue(body.contains("TextIdlePrewarm.frameRendered(System.nanoTime())"))
        val prewarm = java.io.File("src/main/kotlin/com/fluxx/android/render/TextIdlePrewarm.kt").readText()
        assertTrue("foreground warming goes through the gated loop", prewarm.contains("loop.next(System.nanoTime())"))
    }
}
