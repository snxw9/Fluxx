package com.fluxx.android.render

import com.fluxx.android.render.UpcomingUse.Clip
import com.fluxx.android.render.UpcomingUse.Input
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** MP2.1 B2: the warm pool between the planner and the renderer's decoder pool (fake decoders, a real worker thread). */
class WarmPoolTest {
    private class Fake(val uri: String) { var at = -1L; var closed = false }
    private val opened = ArrayList<String>()
    private val closed = ArrayList<String>()
    @Volatile private var hold: CountDownLatch? = null
    private val ops = object : WarmPool.Ops<Fake> {
        override fun open(uri: String): Fake { synchronized(opened) { opened += uri }; return Fake(uri) }
        override fun position(decoder: Fake, sourceUs: Long, cancelled: () -> Boolean) {
            hold?.let { while (!it.await(5, TimeUnit.MILLISECONDS)) if (cancelled()) throw java.util.concurrent.CancellationException() }
            if (cancelled()) throw java.util.concurrent.CancellationException()
            decoder.at = sourceUs
        }
        override fun close(decoder: Fake) { decoder.closed = true; synchronized(closed) { closed += decoder.uri } }
        override fun bytes(decoder: Fake) = 100L
        override fun decoderClass(decoder: Fake) = "avc 1920x1080"
    }
    private val worker = Executors.newSingleThreadExecutor()
    private var clock = 0L
    @After fun stop() { worker.shutdownNow() }

    private fun pool(export: Boolean = false, planner: (Input) -> UpcomingUse.Plan = UpcomingUse::plan) =
        WarmPool(ops, export, worker, { clock }, planner)
    private fun clips() = listOf(Clip(1, "a", 0, 2_000_000, 0, "avc 1920x1080", 100), Clip(2, "b", 2_000_000, 4_000_000, 7_000_000, "avc 1920x1080", 100),
        Clip(3, "c", 2_200_000, 4_000_000, 0, "avc 1920x1080", 100))
    private fun input(now: Long, playing: Boolean = true) = Input(now, playing, clips(), budgetBytes = 1000, cap = 4)
    private fun settle(p: WarmPool<Fake>) { worker.submit {}.get(2, TimeUnit.SECONDS) }

    @Test fun exportNeverConsultsThePlanner() {
        var asked = 0
        val p = pool(export = true) { asked++; error("the planner must not run in export") }
        for (t in 0L until 4_000_000L step 33_333L) p.frame(true, { asked++; input(t) }) { null }
        assertEquals(0, asked)
        assertEquals(0, p.count)
        assertTrue(opened.isEmpty())
    }

    @Test fun aWarmUpOpensPositionsAndIsTakenAtTheCut() {
        val p = pool()
        p.frame(true, { input(1_000_000) }) { null }
        settle(p)
        assertEquals(listOf("b"), opened)                 // one at a time: clip 3 waits for the next frame
        assertEquals(1, p.count); assertEquals(100L, p.bytes)
        assertNotNull(p.costUs("avc 1920x1080"))          // measured into the horizon
        p.frame(true, { input(1_033_333) }) { null }; settle(p)
        assertEquals(listOf("b", "c"), opened)
        val d = p.take(2) { it.at == 7_000_000L }
        assertNotNull(d); assertEquals(7_000_000L, d!!.at); assertFalse(d.closed)
        assertFalse(p.has(2)); assertEquals(1, p.count)
        assertEquals(1L, p.counts[2])
    }

    @Test fun aStaleWarmDecoderIsReleasedWhenItDoesNotFitTheRequest() {
        val p = pool()
        p.frame(true, { input(1_000_000) }) { null }; settle(p)
        assertNull(p.take(2) { false })
        assertEquals(listOf("b"), closed)
        assertEquals(0, p.count)
    }

    @Test fun pausedTheUnusedWarmDecoderGoesAfterThreeSecondsAndAtOnceOnTrim() {
        val p = pool()
        p.frame(true, { input(1_000_000) }) { null }; settle(p)
        clock = 0; p.frame(false, { error("not while paused") }) { null }
        clock = 2_900; p.frame(false, { error("not while paused") }) { null }
        assertTrue(closed.isEmpty())
        clock = 3_000; p.frame(false, { error("not while paused") }) { null }
        assertEquals(listOf("b"), closed)
        // Trim: unwanted ones go at once (no delay).
        val q = pool()
        q.frame(true, { input(1_000_000) }) { null }; settle(q)
        q.frame(false, { error("") }) { null }
        q.trim()
        assertEquals(listOf("b", "b"), closed)
    }

    @Test fun dropForRoomFreesTheUnwantedFirstThenTheOneDueLast() {
        val p = pool()
        p.frame(true, { input(1_000_000) }) { null }; settle(p)
        p.frame(true, { input(1_033_333) }) { null }; settle(p)
        assertEquals(2, p.count)
        assertTrue(p.dropForRoom())
        assertEquals(listOf("c"), closed)                 // clip 3 starts last
        assertTrue(p.dropForRoom()); assertFalse(p.dropForRoom())
        assertEquals(0, p.count)
    }

    @Test fun aFrameThatNeedsARunningWarmUpWaitsForIt() {
        hold = CountDownLatch(1)
        val p = pool()
        p.frame(true, { input(1_000_000) }) { null }
        Thread { Thread.sleep(150); hold!!.countDown() }.start()
        val t0 = System.nanoTime()
        val d = p.take(2) { true }
        assertNotNull(d)
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 100)
        assertEquals(1L, p.counts[7])
    }

    @Test fun aCancelledRunningWarmUpClosesItselfAndNeverBecomesReady() {
        hold = CountDownLatch(1)
        val p = pool()
        p.frame(true, { input(1_000_000) }) { null }
        clock = 0; p.frame(false, { error("") }) { null }
        clock = 3_000; p.frame(false, { error("") }) { null }   // unwanted for 3 s: cancelled while running
        settle(p)
        assertEquals(listOf("b"), closed)
        assertNull(p.take(2) { true })
        assertEquals(0, p.count)
    }

    @Test fun aReEntryRePositionsThePooledDecoderInsteadOfOpeningOne() {
        val pooled = Fake("m")
        var asked: String? = null
        val p = pool()
        val clips = listOf(Clip(5, "m", 0, 30_000_000, 2_000_000, "avc 1920x1080", 100))
        p.frame(true, { Input(10_000_000, true, clips, returnsAtUs = mapOf(5L to 11_000_000L), open = listOf(UpcomingUse.Open("m", "m", 100, false)),
            budgetBytes = 1000, cap = 4) }) { key -> asked = key; pooled }
        settle(p)
        assertEquals("m", asked)
        assertTrue(opened.isEmpty())
        assertSame(pooled, p.take(5) { true })
        assertEquals(12_500_000L, pooled.at)              // source time at 11.0 - 0.5 s
        assertEquals(setOf("m"), p.lastPlan.keepUris)
    }
}
