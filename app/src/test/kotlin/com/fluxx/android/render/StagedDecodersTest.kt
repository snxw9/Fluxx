package com.fluxx.android.render

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MP2.2 B1: the narrowed flush rule and the test-only staged-image check. */
class StagedDecodersTest {
    @After fun off() { StagedDecoders.checking = false; StagedDecoders.resetViolations() }

    @Test fun nothingStagedNeverFlushes() {
        val s = StagedDecoders()
        assertFalse(s.mustFlushBefore("a", staged = 0, flushAlways = false))
        assertFalse(s.mustFlushBefore("a", staged = 0, flushAlways = true))
    }

    @Test fun distinctDecodersNeedNoFlush() {
        val s = StagedDecoders()
        s.staged("a", Any())
        assertFalse(s.mustFlushBefore("b", staged = 1, flushAlways = false))
        s.staged("b", Any())
        assertFalse(s.mustFlushBefore("c", staged = 2, flushAlways = false))
    }

    @Test fun aSharedDecoderWithAStagedImageFlushes() {
        val s = StagedDecoders()
        s.staged("a", Any())
        assertTrue(s.mustFlushBefore("a", staged = 1, flushAlways = false))
    }

    @Test fun otherStagedDrawsAloneDoNotFlush() {
        // A raster, solid, text or strip draw is staged, but no image of this decoder: no flush.
        val s = StagedDecoders()
        assertFalse(s.mustFlushBefore("a", staged = 3, flushAlways = false))
    }

    @Test fun flushAlwaysRestoresTheOldRule() {
        val s = StagedDecoders()
        assertTrue(s.mustFlushBefore("a", staged = 1, flushAlways = true))
        s.staged("b", Any())
        assertTrue(s.mustFlushBefore("c", staged = 1, flushAlways = true))
    }

    @Test fun clearForgetsEveryKey() {
        val s = StagedDecoders()
        s.staged("a", Any()); s.staged("b", Any())
        s.clear()
        assertTrue(s.isEmpty)
        assertFalse("a" in s)
        assertFalse(s.mustFlushBefore("a", staged = 1, flushAlways = false))
    }

    @Test fun theCheckCountsOnlyStagedDecoders() {
        StagedDecoders.checking = true; StagedDecoders.resetViolations()
        val s = StagedDecoders(); val staged = Any(); val other = Any()
        s.staged("a", staged)
        StagedDecoders.violation(other, "decode")
        assertEquals(0L, StagedDecoders.violations)
        StagedDecoders.violation(staged, "decode")
        assertEquals(1L, StagedDecoders.violations)
        assertNotNull(StagedDecoders.firstViolation)
        s.clear()
        StagedDecoders.violation(staged, "close")
        assertEquals("cleared: no longer staged", 1L, StagedDecoders.violations)
    }

    @Test fun theCheckSeesDecodersOfEveryRenderer() {
        StagedDecoders.checking = true; StagedDecoders.resetViolations()
        val first = StagedDecoders(); val second = StagedDecoders(); val d = Any()
        first.staged("a", d)
        second.clear() // another renderer's flush does not unstage this one's decoder
        StagedDecoders.violation(d, "seek")
        assertEquals(1L, StagedDecoders.violations)
        first.clear()
    }

    @Test fun theCheckIsOffByDefault() {
        val s = StagedDecoders(); val d = Any()
        s.staged("a", d)
        StagedDecoders.violation(d, "decode")
        assertEquals(0L, StagedDecoders.violations)
    }
}
