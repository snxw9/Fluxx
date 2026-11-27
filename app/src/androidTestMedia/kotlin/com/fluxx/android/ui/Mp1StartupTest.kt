package com.fluxx.android.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Test

/**
 * MP1 B4: what the baseline profile changes. In the fresh process an instrumented run starts: process start -> the
 * editor's first composed frame and first preview frame (an in-memory document of the typical clip), then the first scrub
 * (first new frame after the first move), then the first Play (first frame, first sound). Run once per benchmark build
 * (with / without the app's baseline profile). The ART compile state of the package is read from dumpsys by the host.
 */
class Mp1StartupTest : Aud2Base() {
    override val tag = "MP1"

    @Test fun startupScrubPlay() {
        val procStart = android.os.Process.getStartUptimeMillis()
        val t0 = android.os.SystemClock.uptimeMillis()
        val f = file("typical H.264", Aud2Bench.TYPICAL); val m = Aud1Bench.media(f)
        rec.start()
        val h0 = System.nanoTime()
        host(Aud2Bench.project(1920, 1080, m.durationUs, m.fps, listOf(Aud2Bench.layer(1, f, m))))   // includes 1.5 s of settle
        val firstFrame = rec.frames.firstOrNull()?.let { (it[2] - h0) / 1e6 } ?: Double.NaN
        report("B4 startup: process start -> test start %d ms; host -> first preview frame %.0f ms".format(t0 - procStart, firstFrame))
        // First scrub: first new frame after the first move (backward, as AUD2).
        val area = compose.onNodeWithTag("timeline-playhead-line", useUnmergedTree = true)
        seekTo(m.durationUs / 2, 1200); rec.clear()
        area.performTouchInput { down(Offset(width / 2f + 60.dp.toPx(), height * .6f)) }
        val s0 = System.nanoTime()
        repeat(10) { area.performTouchInput { moveBy(Offset(8.dp.toPx(), 0f)) }; Thread.sleep(16) }
        area.performTouchInput { up() }
        Thread.sleep(800)
        val sf = rec.frames.toList().filter { it[2] >= s0 }
        report("B4 first scrub: first frame %.0f ms after the first move; %d frames; slowest render %.0f ms".format(
            sf.firstOrNull()?.let { (it[2] - s0) / 1e6 } ?: Double.NaN, sf.size, sf.maxOfOrNull { (it[2] - it[1]) / 1e6 } ?: Double.NaN))
        // First Play.
        seekTo(0, 1200); rec.clear()
        val p0 = System.nanoTime(); toggle()
        while (System.nanoTime() - p0 < 10_000_000_000L && rec.firstSoundNs() == null) Thread.sleep(5)
        Thread.sleep(3000)
        val pf = rec.frames.toList().filter { it[2] >= p0 }
        val iv = pf.drop(3).zipWithNext { a, b -> (b[2] - a[2]) / 1e6 }
        report("B4 first play: first frame %.0f ms, first sound %.0f ms after Play; pacing over 3 s ${Aud2Bench.stats(iv)} ms".format(
            pf.firstOrNull()?.let { (it[2] - p0) / 1e6 } ?: Double.NaN, rec.firstSoundNs()?.let { (it - p0) / 1e6 } ?: Double.NaN))
        if (playing) toggle()
    }
}
