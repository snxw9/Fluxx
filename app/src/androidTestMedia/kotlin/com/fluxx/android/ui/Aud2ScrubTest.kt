package com.fluxx.android.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.fluxx.android.editor.EditorAction
import com.fluxx.android.model.FrameRate
import org.junit.Test
import java.io.File

/**
 * AUD2 A3: scrubbing (the same cases as Aud1ScrubTest) on the typical H.264 clip, and once on the VP9 file for the
 * "after" comparison; plus both files' sync-frame spacing read from the extractor. Numbers only.
 */
class Aud2ScrubTest : Aud2Base() {
    @Test fun a3KeyframeSpacing() {
        for ((tag, match) in listOf("VP9" to Aud2Bench.VP9, "H.264" to Aud2Bench.TYPICAL)) {
            val f = Aud2Bench.find(context, match)
            if (f == null) { report("A3 $tag: file not found (${Aud2Bench.lookupLog}); skipped"); continue }
            val g = Aud2Bench.gop(f)
            report("A3 $tag keyframes: ${g.frames} frames, ${g.syncs} sync frames; sync interval ${Aud2Bench.stats(g.intervalsMs)} ms; min %.0f ms".format(g.intervalsMs.minOrNull() ?: Double.NaN))
        }
    }
    @Test fun a3ScrubTypicalCollapsed() = scrubAll("H.264 collapsed,", file("typical H.264", Aud2Bench.TYPICAL), false)
    @Test fun a3ScrubTypicalThreeTwirlDowns() = scrubAll("H.264 3 expanded,", file("typical H.264", Aud2Bench.TYPICAL), true)
    @Test fun a3ScrubVp9Collapsed() = scrubAll("VP9 collapsed,", file("VP9 worst-case", Aud2Bench.VP9), false)

    private lateinit var m: Aud1Bench.Media

    private fun scrubAll(tag: String, f: File, expanded: Boolean) {
        m = Aud1Bench.media(f)
        rec.start()
        val (w, h) = Aud2Bench.canvas(m)
        host(Aud1Bench.project(f, m, extra = 2).let { p -> Aud2Bench.project(w, h, m.durationUs, m.fps, p.composition.layers.toList()) })
        warmPcm(android.net.Uri.fromFile(f).toString(), m.durationUs)
        if (expanded) { compose.runOnIdle { editor.setExpanded(setOf(1L, 10L, 11L)) }; Thread.sleep(800) }
        val mid = m.durationUs / 2; val nearEnd = m.durationUs - 8_000_000
        case("$tag control (no playback) at the middle") { seekTo(mid, 1500) }
        case("$tag after Pause at the start") { playFor(2_000_000, 3000) }
        case("$tag after Pause at the middle") { playFor(mid, 3000) }
        case("$tag after Pause near the end") { playFor(nearEnd, 3000) }
        case("$tag after playback ran to the end") { seekTo(m.durationUs - 2_500_000, 1500); toggle(); Thread.sleep(4500); if (playing) toggle() }
        case("$tag after a tap seek") { seekTo(mid + 7_000_000, 1500) }
    }

    private fun playFor(fromUs: Long, ms: Long) { seekTo(fromUs, 1500); toggle(); Thread.sleep(ms); toggle(); Thread.sleep(300) }

    data class Result(val firstFrameMs: Double, val caughtUpMs: Double, val longestGapMs: Double, val maxRenderMs: Double, val seeks: Int, val cancelled: Int)

    /** One scrub over the track area ([dir] -1 = forward in time, +1 = backward), 20 moves 16 ms apart (as AUD1). */
    private fun scrub(dir: Float): Result {
        val area = compose.onNodeWithTag("timeline-playhead-line", useUnmergedTree = true)
        rec.clear()
        area.performTouchInput { down(Offset(width / 2f + 60.dp.toPx(), height * .6f)) }
        val firstMove = System.nanoTime()
        repeat(20) { area.performTouchInput { moveBy(Offset(dir * 8.dp.toPx(), 0f)) }; Thread.sleep(16) }
        val lastMove = System.nanoTime()
        area.performTouchInput { up() }
        val target = FrameRate(m.fps, 1).let { r -> compose.runOnIdle { r.frameTimeUs(r.nearestFrame(editor.state.playheadUs)) } }
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline && rec.frames.none { it[0] == target }) Thread.sleep(5)
        Thread.sleep(200)
        val frames = rec.frames.toList().filter { it[2] >= firstMove }
        val first = frames.firstOrNull()?.let { (it[2] - firstMove) / 1e6 } ?: Double.NaN
        val caught = frames.firstOrNull { it[0] == target }?.let { (it[2] - lastMove) / 1e6 } ?: Double.NaN
        val gaps = (listOf(firstMove) + frames.map { it[2] }).zipWithNext { a, b -> (b - a) / 1e6 }
        val ev = rec.decoder.toList()
        return Result(first, caught, gaps.maxOrNull() ?: Double.NaN, frames.maxOfOrNull { (it[2] - it[1]) / 1e6 } ?: Double.NaN,
            ev.count { it.second.startsWith("frame") && !it.second.contains("seek=-1") }, ev.count { it.second.startsWith("cancelled") })
    }

    private fun case(label: String, setup: () -> Unit) {
        val out = LinkedHashMap<String, MutableList<Result>>()
        repeat(2) {
            for ((name, dir) in listOf("forward" to -1f, "backward" to 1f)) {
                setup()
                out.getOrPut(name) { ArrayList() } += scrub(dir)
            }
        }
        for ((name, rs) in out) report("A3 $label, $name: first frame after the first move ${rs.joinToString("/") { "%.0f".format(it.firstFrameMs) }}ms; caught up after the last move ${rs.joinToString("/") { "%.0f".format(it.caughtUpMs) }}ms; longest gap without a new frame ${rs.joinToString("/") { "%.0f".format(it.longestGapMs) }}ms; slowest frame render ${rs.joinToString("/") { "%.0f".format(it.maxRenderMs) }}ms; decoder flush+seeks ${rs.joinToString("/") { "${it.seeks}" }}, cancelled ${rs.joinToString("/") { "${it.cancelled}" }}")
    }
}
