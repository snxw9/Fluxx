package com.fluxx.android.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.fluxx.android.model.FrameRate
import org.junit.Test
import java.io.File

/**
 * MP1 A3 / B1: the scrub DISPLAY rule. The AUD2 scrub (20 moves 16 ms apart over the track area) on the typical clip and
 * the VP9 file, backward twice and forward once per case. For every video frame the decoder returns while the finger
 * moves (and until the exact frame after release), its requested source time (t=) and the shown frame's pts (shown=):
 * how many are LATER than the target, and by how much; plus first frame and the longest gap without a new frame.
 */
class Mp1ScrubTest : Aud2Base() {
    override val tag = "MP1"
    @Test fun scrubDisplayTypical() = all("H.264", file("typical H.264", Aud2Bench.TYPICAL))
    @Test fun scrubDisplayVp9() = all("VP9", file("VP9 worst-case", Aud2Bench.VP9))

    private lateinit var m: Aud1Bench.Media

    private fun all(tag: String, f: File) {
        m = Aud1Bench.media(f)
        rec.start()
        val (w, h) = Aud2Bench.canvas(m)
        host(Aud1Bench.project(f, m, extra = 2).let { p -> Aud2Bench.project(w, h, m.durationUs, m.fps, p.composition.layers.toList()) })
        warmPcm(android.net.Uri.fromFile(f).toString(), m.durationUs)
        val mid = m.durationUs / 2
        case("$tag control at the middle") { seekTo(mid, 1500) }
        case("$tag after Pause at the middle") { seekTo(mid, 1500); toggle(); Thread.sleep(3000); toggle(); Thread.sleep(300) }
        case("$tag after a tap seek") { seekTo(mid + 7_000_000, 1500) }
    }

    class R(val shown: Int, val later: Int, val laterMs: List<Double>, val first: Double, val gap: Double, val caught: Double)

    private fun scrub(dir: Float): R {
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
        // Every frame the (single) video decoder returned for a render during the scrub, until the exact frame landed.
        val endNs = frames.firstOrNull { it[0] == target }?.get(2) ?: System.nanoTime()
        val ev = rec.decoder.toList().filter { it.first in firstMove..endNs && it.second.startsWith("frame ") }
        val pairs = ev.mapNotNull { e -> val t = Mp1Bench.field(e.second, "t")?.toLongOrNull(); val s = Mp1Bench.field(e.second, "shown")?.toLongOrNull()
            if (t != null && s != null && s >= 0) t to s else null }
        val later = pairs.filter { it.second > it.first }
        val gaps = (listOf(firstMove) + frames.map { it[2] }).zipWithNext { a, b -> (b - a) / 1e6 }
        return R(pairs.size, later.size, later.map { (it.second - it.first) / 1000.0 },
            frames.firstOrNull()?.let { (it[2] - firstMove) / 1e6 } ?: Double.NaN, gaps.maxOrNull() ?: Double.NaN,
            frames.firstOrNull { it[0] == target }?.let { (it[2] - lastMove) / 1e6 } ?: Double.NaN)
    }

    private fun case(label: String, setup: () -> Unit) {
        for ((name, dir, tries) in listOf(Triple("backward", 1f, 2), Triple("forward", -1f, 1))) {
            val rs = (0 until tries).map { setup(); scrub(dir) }
            report("A3 scrub display, $label, $name: frames shown ${rs.joinToString("/") { "${it.shown}" }}; LATER than the target ${rs.joinToString("/") { "${it.later}" }}" +
                " (by ms: ${rs.flatMap { it.laterMs }.let { if (it.isEmpty()) "-" else Aud2Bench.stats(it) }}); first frame ${rs.joinToString("/") { "%.0f".format(it.first) }} ms;" +
                " longest gap ${rs.joinToString("/") { "%.0f".format(it.gap) }} ms; exact frame after release ${rs.joinToString("/") { "%.0f".format(it.caught) }} ms")
        }
    }
}
