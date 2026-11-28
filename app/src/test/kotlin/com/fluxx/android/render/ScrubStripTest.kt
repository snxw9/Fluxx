package com.fluxx.android.render

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executor

/** MP2.3 B1: the scrub strip's keying, lookup rule, scope, coverage, persistence, budgets and builder (fake decoder). */
class ScrubStripTest {
    private val i = StripPolicy.INTERVAL_US
    private fun tmp(): File = kotlin.io.path.createTempDirectory("strip").toFile().also { it.deleteOnExit() }

    // ---- keying ---------------------------------------------------------------------------------------------------------
    @Test fun keyedLikeThePcmCacheAndInvalidatedByVersion() {
        val a = StripPolicy.fileBase("content://media/1", 1000, 2000)
        assertEquals(a, StripPolicy.fileBase("content://media/1", 1000, 2000))
        assertNotEquals(a, StripPolicy.fileBase("content://media/2", 1000, 2000))
        assertNotEquals(a, StripPolicy.fileBase("content://media/1", 1001, 2000))
        assertNotEquals(a, StripPolicy.fileBase("content://media/1", 1000, 2001))
        assertTrue(a.endsWith("-v${StripPolicy.VERSION}"))
        assertFalse(StripPolicy.stale("$a.strip")); assertFalse(StripPolicy.stale("$a.sidx"))
        val old = a.replace("-v${StripPolicy.VERSION}", "-v${StripPolicy.VERSION - 1}")
        assertTrue(StripPolicy.stale("$old.strip")); assertTrue(StripPolicy.stale("$old.sidx")); assertTrue(StripPolicy.stale("$old.tmp"))
        assertFalse(StripPolicy.stale("something-else.pcm"))
    }

    // ---- the at-or-before rule ------------------------------------------------------------------------------------------
    @Test fun aLookupNeverReturnsALaterPicture() {
        val idx = StripIndex(StripPolicy.slots(20_000_000))
        // 24 fps pictures, slots 20..120 covered (2 s - 12 s), first picture of each slot kept.
        val frame = 41_667L
        var t = 0L; val pics = ArrayList<Long>()
        while (t < 20_000_000) { pics += t; t += frame }
        for (k in 20..120) { idx.cover(k); pics.firstOrNull { StripPolicy.slotOf(it) == k }?.let { idx.put(k, it, k * 100L, 10) } }
        val rnd = java.util.Random(7)
        repeat(5000) {
            val q = (rnd.nextDouble() * 14_000_000).toLong()
            val s = idx.atOrBefore(q)
            if (s >= 0) {
                assertTrue("picture ${idx.pts(s)} after $q", idx.pts(s) <= q)
                assertTrue("lag", q - idx.pts(s) < 2 * i + frame)
            } else assertTrue("miss inside the covered span at $q", q < 2_000_000 + frame || q >= 12_100_000)
        }
        assertEquals(-1, idx.atOrBefore(1_000_000))     // not covered
        assertEquals(-1, idx.atOrBefore(13_000_000))    // not covered
        // Monotone: a sweep backward never shows a later picture than the one before it.
        var prev = Long.MAX_VALUE
        var q = 12_000_000L
        while (q > 2_100_000) { val s = idx.atOrBefore(q); assertTrue(s >= 0); assertTrue(idx.pts(s) <= prev); prev = idx.pts(s); q -= 7_919 }
    }

    @Test fun emptyCoveredSlotsWalkBackBoundedAndTheFirstPictureAnswersBeforeIt() {
        val idx = StripIndex(200)
        for (k in 0..199) idx.cover(k)
        idx.put(1, 105_000, 0, 5)                     // the stream's first picture at 0.105 s
        idx.firstPts = 105_000
        idx.put(3, 300_000, 5, 5)
        assertEquals(1, idx.atOrBefore(0))             // before the first picture: that picture
        assertEquals(1, idx.atOrBefore(104_999))
        assertEquals(1, idx.atOrBefore(299_999))
        assertEquals(3, idx.atOrBefore(300_000))
        assertEquals(3, idx.atOrBefore(300_000 + (StripPolicy.LOOKBACK_SLOTS) * i))
        assertEquals(-1, idx.atOrBefore(300_000 + (StripPolicy.LOOKBACK_SLOTS + 2) * i)) // too far behind: a miss
        val noFirst = StripIndex(10); for (k in 0..9) noFirst.cover(k); noFirst.put(2, 250_000, 0, 1)
        assertEquals(-1, noFirst.atOrBefore(100_000)) // the first picture is not known: no answer before it
    }

    // ---- scope ----------------------------------------------------------------------------------------------------------
    @Test fun scopeSlotsRoundAtTheRangeEdgesAndClampToTheSource() {
        assertEquals(listOf(9..20), StripPolicy.scopeSlots(listOf(950_000L..2_050_000L), 60_000_000))
        assertEquals(listOf(10..19), StripPolicy.scopeSlots(listOf(1_000_000L..1_999_999L), 60_000_000))
        // Touching windows merge; far ones stay apart; clamped to the source's last slot.
        assertEquals(listOf(0..30, 100..600), StripPolicy.scopeSlots(listOf(0L..2_000_000L, 2_000_001L..3_050_000L, 10_000_000L..90_000_000L), 60_000_000))
        assertEquals(StripPolicy.slots(60_000_000) - 1, StripPolicy.scopeSlots(listOf(59_000_000L..70_000_000L), 60_000_000).single().last)
        // The used range of a 10-minute clip at 20:00 of a 50-minute file, margin 10 s: 19:50 to 30:10 only.
        val w = UsedRange.windowUs(1_200_000_000L, 600_000_000L, 3_018_300_000L)
        val s = StripPolicy.scopeSlots(listOf(w), 3_018_300_000L).single()
        assertEquals(StripPolicy.slotOf(1_190_000_000L), s.first); assertEquals(StripPolicy.slotOf(1_810_000_000L), s.last)
        assertEquals(1280 to 720, StripPolicy.size(1920, 1080, 1280)); assertEquals(480 to 270, StripPolicy.size(1920, 1080))
        assertEquals(480 to 360, StripPolicy.size(1920, 1440)); assertEquals(270 to 480, StripPolicy.size(1080, 1920))
        assertEquals(320 to 240, StripPolicy.size(320, 240)) // never upscaled
    }

    // ---- coverage bookkeeping -------------------------------------------------------------------------------------------
    @Test fun aRunCoversOnlyWholeSlotsInScopeAndKeepsEachSlotsFirstPicture() {
        val idx = StripIndex(StripPolicy.slots(10_000_000))
        val run = StripRun(idx, listOf(10..25))
        run.seeked(false)
        // A sync frame at 1.05 s (inside slot 10): slot 10 is not seen whole.
        var t = 1_050_000L; val kept = HashMap<Int, Long>()
        while (t <= 3_000_000) { val k = run.picture(t); if (k >= 0) { idx.put(k, t, 0, 1); kept[k] = t }; t += 33_333 }
        assertFalse(idx.covered(10)); assertFalse(idx.has(10))
        for (k in 11..25) { assertTrue("slot $k", idx.covered(k)); assertTrue(idx.has(k)); assertEquals(k, StripPolicy.slotOf(kept[k]!!))
            assertTrue("first picture of slot $k", kept[k]!! - StripPolicy.slotStart(k) < 33_333) }
        assertFalse(idx.covered(26)); assertFalse(idx.has(28)) // outside the scope: decoded, neither kept nor covered
        // From the stream's start: slot 0 counts whole although the first picture is at 0.105 s.
        val idx2 = StripIndex(50); val r2 = StripRun(idx2, listOf(0..49)); r2.seeked(true)
        t = 105_000L; while (t < 500_000) { val k = r2.picture(t); if (k >= 0) idx2.put(k, t, 0, 1); t += 40_000 }
        assertEquals(105_000L, idx2.firstPts); assertTrue(idx2.covered(0)); assertFalse(idx2.has(0)); assertTrue(idx2.has(1))
        assertEquals(StripPolicy.slotOf(105_000), idx2.atOrBefore(10_000))
        r2.ended(); assertTrue(idx2.covered(49))   // the end of the stream: every remaining slot is seen
        // Out-of-order output is ignored.
        val r3 = StripRun(StripIndex(50), listOf(0..49)); r3.seeked(true); assertEquals(0, r3.picture(0)); assertEquals(-1, r3.picture(-5))
    }

    // ---- persistence ----------------------------------------------------------------------------------------------------
    @Test fun partialCoveragePersistsAndSurvivesATornWrite() {
        val dir = tmp(); val base = StripPolicy.fileBase("u", 1, 2); val slots = 100
        StripFile(dir, base, slots).use { f ->
            for (k in 10..19) { f.withIndex { it.cover(k) }; f.put(k, k * i + 5, ByteArray(100) { k.toByte() }) }
            f.withIndex { it.firstPts = 12 }
            f.persist()
        }
        StripFile(dir, base, slots).use { f ->
            assertEquals(10, f.withIndex { it.coveredCount }); assertEquals(12L, f.withIndex { it.firstPts })
            assertEquals(15 to (15 * i + 5), f.lookup(15 * i + 50))
            assertArrayEquals(ByteArray(100) { 15 }, f.read(15))
            // A picture appended but never indexed (the process died before the index was written).
            f.put(30, 30 * i, ByteArray(50))
        }
        // put() above was persisted by close(); now tear the data file behind the index's back.
        java.io.RandomAccessFile(File(dir, "$base.strip"), "rw").use { it.setLength(it.length() - 20) }
        StripFile(dir, base, slots).use { f ->
            assertFalse("a picture whose bytes are gone is dropped", f.withIndex { it.has(30) })
            assertEquals(1000L, File(dir, "$base.strip").length())  // cut back to what the index refers to
            assertNotNull(f.read(19))
        }
        // An index of another source length (or version / interval) is rebuilt from nothing.
        StripFile(dir, base, slots + 1).use { f -> assertEquals(0, f.withIndex { it.coveredCount }); assertEquals(0L, File(dir, "$base.strip").length()) }
    }

    // ---- budgets --------------------------------------------------------------------------------------------------------
    @Test fun diskLruCountsAllocatedBytesAndKeepsFilesInUse() {
        val files = listOf(Triple("a", 100L, 1L), Triple("b", 100L, 2L), Triple("c", 100L, 3L), Triple("d", 100L, 4L))
        assertEquals(listOf("a", "b"), StripPolicy.evict(files, emptySet(), 250))
        assertEquals(listOf("b", "c"), StripPolicy.evict(files, setOf("a"), 250))
        assertTrue(StripPolicy.evict(files, emptySet(), 400).isEmpty())
        val evicted = ArrayList<Int>()
        val ram = ByteLru<Int, Int>(10, { 3L }) { evicted += it }
        for (k in 1..5) ram.put(k, k)
        assertTrue(ram.bytes <= 10); assertEquals(listOf(1, 2), evicted)
        ram.get(3); ram.put(6, 6); assertEquals(4, evicted.last()) // 3 was used: 4 goes
        ram.clear(); assertEquals(0L, ram.bytes)
    }

    // ---- planning -------------------------------------------------------------------------------------------------------
    @Test fun runsStartAtThePlayheadAndExpandOutward() {
        val covered = java.util.BitSet()
        val scope = listOf(0..999)
        assertEquals(500..599, StripPlan.nextRun(scope, covered::get, 500, 100))
        covered.set(500, 600)
        assertEquals(400..499, StripPlan.nextRun(scope, covered::get, 500, 100)) // just behind the playhead (nearer)
        covered.set(400, 500)
        assertEquals(600..699, StripPlan.nextRun(scope, covered::get, 500, 100)) // then ahead (100 slots vs 101)
        covered.set(600, 700); covered.set(301, 400)
        assertEquals(700..799, StripPlan.nextRun(scope, covered::get, 500, 100)) // a tie (200 behind, 200 ahead): forward
        covered.clear(); covered.set(500, 600)
        assertEquals(600..699, StripPlan.nextRun(listOf(500..999), covered::get, 550, 100))
        covered.set(0, 1000)
        assertNull(StripPlan.nextRun(scope, covered::get, 500, 100))
        assertEquals(2000..2099, StripPlan.nextRun(listOf(2000..3000), java.util.BitSet()::get, 10, 100))
    }

    // ---- the builder ----------------------------------------------------------------------------------------------------
    /** A fake 30 fps stream with a sync frame every 2 s; records what it decodes. */
    private class Fake(val durationUs: Long) { var pos = 0L; var closed = false }
    private inner class FakeOps(val file: () -> StripFile?, val durationUs: Long) : StripBuilder.Ops<Fake> {
        val decoded = ArrayList<Long>(); val seeks = ArrayList<Long>(); var opened = 0; var closedCount = 0
        var onNext: () -> Unit = {}
        override fun strip(uri: String, durationUs: Long) = file()
        override fun open(uri: String): Fake { opened++; return Fake(durationUs) }
        override fun seek(decoder: Fake, us: Long): Boolean { decoder.pos = us / 2_000_000 * 2_000_000; seeks += decoder.pos; return decoder.pos == 0L }
        override fun next(decoder: Fake, want: (Long) -> Boolean): StripBuilder.Picture? {
            onNext()
            if (decoder.pos >= decoder.durationUs) return null
            val p = decoder.pos; decoder.pos += 33_333; decoded += p
            return StripBuilder.Picture(p, if (want(p)) ByteArray(8) else null)
        }
        override fun close(decoder: Fake) { decoder.closed = true; closedCount++ }
    }
    private val direct = Executor { it.run() }

    @Test fun theBuilderStaysInsideTheUsedRangeOfALongSource() {
        val duration = 3_018_300_000L
        val dir = tmp()
        val file = StripFile(dir, "long", StripPolicy.slots(duration))
        val ops = FakeOps({ file }, duration)
        val b = StripBuilder(ops, false, direct, { true }, { 0L })
        val window = UsedRange.windowUs(1_200_000_000L, 600_000_000L, duration)
        val src = StripBuilder.Source("u", listOf(window), 1_200_000_000L, duration, 100, "video/hevc")
        b.tick(listOf(src)) { null }
        val scope = StripPolicy.scopeSlots(listOf(window), duration).single()
        val startUs = StripPolicy.slotStart(scope.first); val endUs = StripPolicy.slotStart(scope.last + 2)
        assertTrue(ops.seeks.isNotEmpty())
        assertTrue("seeks inside the range (or its sync frame): ${ops.seeks.min()}", ops.seeks.min() >= startUs / 2_000_000 * 2_000_000)
        assertTrue("never decodes before the range's sync frame", ops.decoded.min() >= startUs / 2_000_000 * 2_000_000)
        assertTrue("never decodes past the range: ${ops.decoded.max()}", ops.decoded.max() < endUs + 33_334)
        assertEquals(scope.last - scope.first + 1, file.withIndex { it.coveredIn(scope) })
        assertEquals(scope.last - scope.first + 1, file.withIndex { it.coveredCount })
        // The first run started at the playhead (the in-point), not at the range's start.
        assertEquals(1_200_000_000L, ops.seeks.first())
        assertEquals(1, ops.opened); assertEquals(1, ops.closedCount); assertFalse(b.holding); assertEquals(0L, b.reservedBytes)
        // Nothing left: a second tick plans nothing.
        b.tick(listOf(src)) { null }
        assertEquals(1, ops.opened); assertFalse(b.wantsTick)
        file.close()
    }

    @Test fun theBuilderPausesWhileTheForegroundIsBusyAndClosesAfterThreeSeconds() {
        val duration = 60_000_000L
        val file = StripFile(tmp(), "p", StripPolicy.slots(duration))
        val ops = FakeOps({ file }, duration)
        var clock = 0L; var may = true
        val b = StripBuilder(ops, false, direct, { may }, { clock }, sleep = { clock += it })
        // The foreground starts after 50 pictures (scrubbing / playing): the builder waits, then closes its decoder.
        var n = 0
        ops.onNext = { if (++n == 50) may = false }
        b.tick(listOf(StripBuilder.Source("u", listOf(0L..duration), 0, duration, 100, "video/avc"))) { null }
        assertEquals(1, ops.closedCount); assertFalse(b.holding)
        assertTrue("paused for 3 s before closing", clock >= StripPolicy.PAUSED_CLOSE_MS)
        assertEquals(1L, b.counts[5])
        val partial = file.withIndex { it.coveredCount }
        assertTrue(partial in 1 until StripPolicy.slots(duration))
        // Idle again: the next tick continues from what is covered (nothing redone).
        may = true; ops.onNext = {}
        val before = ops.decoded.size
        b.tick(listOf(StripBuilder.Source("u", listOf(0L..duration), 0, duration, 100, "video/avc"))) { null }
        assertEquals(StripPolicy.slots(duration), file.withIndex { it.coveredCount })
        assertTrue(ops.decoded.size - before < duration / 33_333 - partial * 3 + 200)
        file.close()
    }

    @Test fun roomIsCheckedAndTheReservationIsHeldWhileBuilding() {
        val duration = 20_000_000L
        val file = StripFile(tmp(), "r", StripPolicy.slots(duration))
        val ops = FakeOps({ file }, duration)
        val b = StripBuilder(ops, false, direct, { true }, { 0L })
        b.tick(listOf(StripBuilder.Source("u", listOf(0L..duration), 0, duration, 100, "video/avc"))) { "budget" }
        assertEquals(0, ops.opened); assertEquals(1L, b.counts[3]); assertTrue(b.wantsTick)
        var heldDuring = false; var bytesDuring = 0L
        ops.onNext = { heldDuring = b.holding; bytesDuring = b.reservedBytes }
        b.tick(listOf(StripBuilder.Source("u", listOf(0L..duration), 0, duration, 123, "video/avc"))) { null }
        assertTrue(heldDuring); assertEquals(123L, bytesDuring); assertFalse(b.holding)
        file.close()
    }

    @Test fun aSourceRemovedFromTheProjectStopsTheBuilder() {
        val duration = 60_000_000L
        val file = StripFile(tmp(), "c", StripPolicy.slots(duration))
        val ops = FakeOps({ file }, duration)
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val gate = java.util.concurrent.Semaphore(0)
            ops.onNext = { gate.acquire() }
            val b = StripBuilder(ops, false, pool, { true }, { System.currentTimeMillis() })
            b.tick(listOf(StripBuilder.Source("u", listOf(0L..duration), 0, duration, 100, "video/avc"))) { null }
            gate.release(5)
            val until = System.currentTimeMillis() + 5000
            while (ops.decoded.size < 5 && System.currentTimeMillis() < until) Thread.sleep(5)
            assertEquals(1, ops.opened)
            b.tick(emptyList()) { null }            // the layer was deleted
            gate.release(1000)
            pool.submit {}.get()
            assertFalse(b.holding); assertEquals(1, ops.closedCount)
            assertTrue(file.withIndex { it.coveredCount } < StripPolicy.slots(duration))
        } finally { pool.shutdownNow(); file.close() }
    }

    @Test fun exportNeverBuildsOrReadsTheStrip() {
        var asked = 0
        val ops = object : StripBuilder.Ops<Fake> {
            override fun strip(uri: String, durationUs: Long): StripFile? { asked++; error("export must not open the strip") }
            override fun open(uri: String): Fake { asked++; error("export must not decode for the strip") }
            override fun seek(decoder: Fake, us: Long): Boolean = error("no")
            override fun next(decoder: Fake, want: (Long) -> Boolean): StripBuilder.Picture? = error("no")
            override fun close(decoder: Fake) {}
        }
        val b = StripBuilder(ops, export = true, executor = direct, mayRun = { true }, clockMs = { 0L })
        repeat(10) { b.tick(listOf(StripBuilder.Source("u", listOf(0L..10_000_000L), 0, 10_000_000L, 1, "video/avc"))) { null } }
        assertEquals(0, asked); assertFalse(b.holding); assertFalse(b.wantsTick)
        val frames = StripFrames<String>(export = true, store = { asked++; error("export must not read the strip") }, capBytes = 10,
            decode = { "x" }, sizeOf = { 1L }, onEvict = {})
        for (t in 0L until 10_000_000L step 33_333L) assertNull(frames.frame("u", t))
        assertEquals(0, asked)
    }

    @Test fun stripFramesAreReadOnceAndThenServedFromRam() {
        val f = StripFile(tmp(), "ram", 100)
        for (k in 0..99) { f.withIndex { it.cover(k) }; f.put(k, k * i, byteArrayOf(k.toByte())) }
        var decodes = 0
        val frames = StripFrames(false, { f }, 3, decode = { b: ByteArray -> decodes++; b[0].toInt() }, sizeOf = { 1L }, onEvict = {})
        assertEquals(5, frames.frame("u", 5 * i + 1)!!.picture); assertEquals(1, decodes)
        assertTrue(frames.frame("u", 5 * i + 2)!!.cached); assertEquals(1, decodes)
        f.close()
    }

    // ---- pixels ---------------------------------------------------------------------------------------------------------
    @Test fun yuvPlanesBecomeADownscaledOpaquePicture() {
        val w = 64; val h = 48
        val y = ByteBuffer.allocate(w * h); val u = ByteBuffer.allocate(w * h / 4); val v = ByteBuffer.allocate(w * h / 4)
        for (k in 0 until w * h) y.put(k, 126.toByte()); for (k in 0 until w * h / 4) { u.put(k, 128.toByte()); v.put(k, 128.toByte()) }
        val out = IntArray(16 * 12)
        StripPixels.toArgb(y, w, 1, u, v, w / 2, 1, 0, 0, w, h, 16, 12, out)
        val g = out[0] and 255
        assertTrue(out.all { it == out[0] }); assertEquals(0xff, out[0] ushr 24); assertEquals(128, g, 2)
        // Pure red in BT.709 limited range (Y 63, Cb 102, Cr 240).
        for (k in 0 until w * h) y.put(k, 63.toByte()); for (k in 0 until w * h / 4) { u.put(k, 102.toByte()); v.put(k, 240.toByte()) }
        StripPixels.toArgb(y, w, 1, u, v, w / 2, 1, 0, 0, w, h, 16, 12, out)
        assertTrue((out[5] shr 16 and 255) > 240); assertTrue((out[5] shr 8 and 255) < 16); assertTrue((out[5] and 255) < 16)
        // A crop inside padded planes (row stride 80, interleaved chroma) reads no byte outside them.
        val py = ByteBuffer.allocate(80 * 50); val puv = ByteBuffer.allocate(80 * 25)
        StripPixels.toArgb(py, 80, 1, puv, puv.duplicate().also { it.position(1) }.slice(), 80, 2, 0, 0, 64, 48, 32, 24, IntArray(32 * 24))
        // P010: 16-bit samples, the high byte read.
        val y10 = ByteBuffer.allocate(w * h * 2); for (k in 0 until w * h) y10.put(2 * k + 1, 126.toByte())
        val c10 = ByteBuffer.allocate(w * h); for (k in 0 until w * h / 2) c10.put(2 * k + 1, 128.toByte())
        StripPixels.toArgb(y10, w * 2, 2, c10, c10, w * 2, 4, 0, 0, w, h, 16, 12, out, high = 1)
        assertEquals(128, out[7] and 255, 2)
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) = assertTrue("$actual vs $expected", kotlin.math.abs(expected - actual) <= tolerance)

}
