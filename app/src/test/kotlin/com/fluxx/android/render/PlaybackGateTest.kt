package com.fluxx.android.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEXT PERF 11 Part 2: the playback position across Home (stop) and return (start), with a fake clock standing in for
 * wall time and the AudioTrack head. The head advances only while the track plays, exactly like PcmPlayback: it is
 * filled at once but plays once [PlaybackGate] opens (first frame of the playback presented).
 */
class PlaybackGateTest {
    private var nowNs = 0L
    private var presented = 0L
    private fun advance(ms: Long) { nowNs += ms * 1_000_000 }

    /** PcmPlayback's clock with a fake head: frames = elapsed playing time × sample rate. */
    private inner class FakePlayback(startUs: Long) {
        private val startFrame = startUs * AudioMixer.RATE / 1_000_000
        private val gate = PlaybackGate({ presented }, { nowNs })
        private var playingSinceNs = -1L
        val positionUs: Long get() {
            if (playingSinceNs < 0 && gate.poll()) playingSinceNs = nowNs
            val head = if (playingSinceNs < 0) 0L else (nowNs - playingSinceNs) * AudioMixer.RATE / 1_000_000_000
            return PcmPlayback.position(startFrame, head)
        }
    }
    private fun at(us: Long) = PcmPlayback.position(us * AudioMixer.RATE / 1_000_000, 0)

    @Test fun clockWaitsForTheFirstPresentedFrame() {
        val playback = FakePlayback(1_000_000)
        advance(600) // A cold first frame: renderer rebuilt, tiers generating.
        assertEquals(at(1_000_000), playback.positionUs)
        presented++ // That frame reaches the screen.
        assertEquals(at(1_000_000), playback.positionUs)
        advance(250)
        assertEquals(1_250_000.0, playback.positionUs.toDouble(), 30.0)
    }

    @Test fun playheadDoesNotAdvanceAcrossStopAndStart() {
        // Playing, then Home after 2 s of playback.
        var playback: FakePlayback? = FakePlayback(1_000_000)
        presented++; playback!!.positionUs; advance(2_000)
        val atStop = playback.positionUs
        assertEquals(3_000_000.0, atStop.toDouble(), 30.0)
        // onPause pauses the preview: the playback (and its clock) is closed; the playhead stays where it was.
        var playhead = atStop; playback = null
        // Stopped for a minute: no clock exists, nothing advances.
        advance(60_000)
        assertEquals(atStop, playhead)
        // Return: the editor shows the same playhead (paused). Play: the rebuilt renderer takes 900 ms for its first frame.
        playback = FakePlayback(playhead)
        advance(900)
        assertEquals("No catch-up jump while the first frame after resume is rendering", at(atStop), playback.positionUs)
        presented++
        playhead = playback.positionUs
        assertEquals(at(atStop), playhead)
        advance(100)
        assertEquals(atStop + 100_000.0, playback.positionUs.toDouble(), 30.0)
    }

    @Test fun neverPresentedFrameReleasesTheClockAfterTheHold() {
        val gate = PlaybackGate({ presented }, { nowNs })
        assertFalse(gate.poll())
        advance(PlaybackGate.HOLD_NS / 1_000_000 - 1)
        assertFalse(gate.poll())
        advance(1)
        assertTrue(gate.poll())
        assertTrue("Stays open", gate.poll())
    }

    @Test fun framesPresentedBeforePlayDoNotOpenTheGate() {
        presented = 41 // Paused frames drawn before play().
        val gate = PlaybackGate({ presented }, { nowNs })
        advance(10)
        assertFalse(gate.poll())
        presented++
        assertTrue(gate.poll())
    }
}
