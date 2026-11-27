package com.fluxx.android.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import org.junit.Test
import java.io.File

/**
 * AUD2 A1: the timeline (and the audio row's waveform) against what plays, at rest, while playback auto-scrolls and while
 * scrubbing, collapsed and with the layer's twirl-down (audio row) open, on the VP9 worst case.
 *
 * Per LAYOUT pass (the position a frame is drawn at): the scrolled content's left edge, hence (d) the scroll offset and
 * the time under the playhead line; against the time HEARD at that moment (AudioTimestamp) and the preview frame on
 * screen (latest presented). With the audio row open also (b): the row's own placement and the range its draw read, in
 * the same frame. Plus the positions the preview posts to the editor (to split preview -> UI latency from the timeline).
 * Numbers only (no speed assertion).
 */
class Aud2WaveformTest : Aud2Base() {
    private lateinit var f: File
    private lateinit var m: Aud1Bench.Media
    private var lineX = 0f
    /** Root x of the content's left edge when the scroll offset is 0 (calibrated at rest: left edge + offset). */
    private var x0 = Double.NaN
    private var ppu = Double.NaN

    @Test fun a1WaveformAtRestPlayingScrubbing() {
        f = file("VP9 worst-case", Aud2Bench.VP9); m = Aud1Bench.media(f)
        val beats = Aud1Bench.beats(Aud1Bench.decodeEnergy(f))
        rec.start()
        val (w, h) = Aud2Bench.canvas(m)
        host(Aud2Bench.project(w, h, m.durationUs, m.fps, listOf(Aud2Bench.layer(1, f, m))))
        warmPcm(android.net.Uri.fromFile(f).toString(), m.durationUs)
        lineX = compose.onNodeWithTag("timeline-playhead-line", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
        report("A1 setup: playhead line at root x %.1f; %d beats in the file".format(lineX, beats.size))
        for (expanded in listOf(false, true)) {
            val mode = if (expanded) "audio row open" else "collapsed"
            if (expanded) {
                compose.runOnIdle { editor.setExpanded(setOf(1L)) }
                compose.waitUntil(60_000) { compose.onAllNodesWithContentDescription("Audio waveform", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                Thread.sleep(800)
            }
            for (start in listOf(0L, m.durationUs / 2, 90_000_000L)) {
                rec.clear(); seekTo(start); Thread.sleep(600)
                rest("$mode, at rest at ${start / 1_000_000}s")
                rec.clear()
                val ui = uiFrames(compose.activity.window) { toggle(); Thread.sleep(8_000); toggle() }
                report("A1 $mode, playing from ${start / 1_000_000}s: UI frames ${Aud2Bench.stats(ui)} ms; playback ${rec.playback.joinToString { it.second }}")
                moving("$mode, playing from ${start / 1_000_000}s", beats, playing = true)
                Thread.sleep(600)
            }
            // Scrubbing: the finger drags the timeline (no playback).
            rec.clear(); seekTo(m.durationUs / 2); Thread.sleep(300); rest("$mode, at rest before the scrub")
            rec.clear()
            val area = compose.onNodeWithTag("timeline-playhead-line", useUnmergedTree = true)
            area.performTouchInput { down(Offset(width / 2f + 60.dp.toPx(), height * .6f)) }
            repeat(60) { area.performTouchInput { moveBy(Offset(-4.dp.toPx(), 0f)) }; Thread.sleep(16) }
            area.performTouchInput { up() }
            Thread.sleep(1500)
            moving("$mode, scrubbing forward", beats, playing = false)
        }
    }

    private fun ms(us: Double) = us / 1000.0
    private fun timeAt(rootX: Double) = (x0 - rootX) / ppu          // us under the line for a content left edge

    /** At rest nothing moves: read the offset from the timeline's horizontal-scroll semantics and calibrate [x0]. */
    private fun rest(label: String) {
        val node = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
        val s = node?.config?.get(SemanticsProperties.HorizontalScrollAxisRange)?.value?.invoke()?.toDouble()
        rec.scrolls.lastOrNull()?.let { ppu = it[2] }
        if (s != null && !rec.lastRoot.isNaN()) x0 = rec.lastRoot + s
        val playhead = compose.runOnIdle { editor.state.playheadUs }
        val shown = rec.videoUs(System.nanoTime())
        report("A1 $label: scroll offset %s px; content left edge %.1f; time under the line %s ms; playhead %.1f ms; preview frame on screen %s ms".format(
            s?.let { "%.0f".format(it) } ?: "?", rec.lastRoot, if (s != null && !ppu.isNaN()) "%.1f".format(s / ppu / 1000) else "?",
            playhead / 1000.0, shown?.let { "%.1f".format(it / 1000.0) } ?: "-"))
    }

    private fun moving(label: String, beats: List<Long>, playing: Boolean) {
        val place = rec.scrolls.toList()
        if (place.isEmpty() || x0.isNaN()) { report("A1 $label: no layout passes recorded (x0 $x0)"); return }
        ppu = place.last()[2]
        // Preview pacing and decoder work in the same window (a preview stall versus a timeline stall).
        val pres = rec.frames.toList()
        val dec = rec.decoder.toList().filter { it.second.startsWith("frame") || it.second.startsWith("cancelled") }
        val seeks = dec.filter { !it.second.contains("seek=-1") }
        report("A1 $label: preview presented ${pres.size}; present interval ${Aud2Bench.stats(pres.zipWithNext { a, b -> (b[2] - a[2]) / 1e6 })} ms; step ${Aud2Bench.stats(pres.zipWithNext { a, b -> (b[0] - a[0]) / 1000.0 })} ms; frameAt ${Aud2Bench.stats(dec.mapNotNull { Regex("us=(\\d+)").find(it.second)?.groupValues?.get(1)?.toDouble()?.div(1000) })} ms; decoder flush+seeks ${seeks.size} " +
            seeks.take(5).joinToString(" ") { "[" + it.second.substringAfter(' ').substringAfter(' ') + "]" })
        val firstSound = rec.firstSoundNs()
        val steady = if (!playing) place else place.filter { firstSound != null && it[0] > firstSound + 300_000_000 }
        // The timeline per layout pass.
        val vsHeard = steady.mapNotNull { p -> rec.heardUs(p[0].toLong())?.let { ms(timeAt(p[1]) - it) } }
        val vsVideo = steady.mapNotNull { p -> rec.videoUs(p[0].toLong())?.let { ms(timeAt(p[1]) - it) } }
        report("A1 $label: ${place.size} layout passes; interval ${Aud2Bench.stats(steady.zipWithNext { a, b -> (b[0] - a[0]) / 1e6 })} ms; step ${Aud2Bench.stats(steady.zipWithNext { a, b -> b[1] - a[1] }.map { -it })} px (1 px = %.2f ms)".format(1 / ppu / 1000) +
            (if (playing) "; time under the line minus HEARD ${Aud2Bench.stats(vsHeard)} median %+.1f ms".format(Aud2Bench.pct(vsHeard, .5)) else "") +
            "; minus the PREVIEW frame on screen ${Aud2Bench.stats(vsVideo)} median %+.1f ms".format(Aud2Bench.pct(vsVideo, .5)))
        // Preview -> editor -> timeline latency: presented frame -> position posted -> first layout pass showing it.
        val posts = rec.positions.toList().filter { !playing || firstSound == null || it[0] > firstSound + 300_000_000 }
        val presToPost = posts.mapNotNull { p -> pres.lastOrNull { it[0] == p[1] && it[2] <= p[0] }?.let { (p[0] - it[2]) / 1e6 } }
        val halfPx = 0.6 / ppu
        val postToLayout = posts.mapNotNull { p -> place.firstOrNull { it[0] >= p[0] && timeAt(it[1]) >= p[1] - halfPx }?.let { (it[0] - p[0]) / 1e6 } }
        val shownPosts = posts.count { p -> place.any { it[0] >= p[0] && kotlin.math.abs(timeAt(it[1]) - p[1]) <= halfPx } }
        report("A1 $label: positions posted ${posts.size}, interval ${Aud2Bench.stats(posts.zipWithNext { a, b -> (b[0] - a[0]) / 1e6 })} ms; presented -> posted ${Aud2Bench.stats(presToPost)} ms; posted -> first layout at or past it ${Aud2Bench.stats(postToLayout)} ms; posts ever laid out exactly: $shownPosts")
        // (b) vs (d): the audio row in the same frame (its left edge against the content's; the range its draw read).
        val rows = rec.placed.toList(); val draws = rec.draws.toList()
        if (rows.isNotEmpty()) {
            val rowVsContent = rows.mapNotNull { r -> place.minByOrNull { kotlin.math.abs(it[0] - r[0]) }?.takeIf { kotlin.math.abs(it[0] - r[0]) < 8_000_000 }?.let { r[1] - it[1] } }
            val drawVsLayout = draws.mapNotNull { d -> place.lastOrNull { it[0] <= d[0] }?.let { ms(d[1] / d[2] - timeAt(it[1])) } }
            val base = Aud2Bench.pct(rowVsContent, .5)
            report("A1 $label: audio row (b): its left edge minus the content's, same frame: ${Aud2Bench.stats(rowVsContent.map { it - base })} px around %.1f (the leading spacer); its draw-phase range centre minus the laid-out time under the line ${Aud2Bench.stats(drawVsLayout)} ms (${rows.size} placements, ${draws.size} row draws)".format(base))
        }
        if (!playing) return
        // Per beat: when the laid-out timeline brings the beat to the line, against when it is heard and when its frame is presented.
        val perBeat = beats.filter { b -> steady.size > 2 && b > timeAt(steady.first()[1]) + 100_000 && b < timeAt(steady.last()[1]) }.mapNotNull { b ->
            val at = steady.firstOrNull { timeAt(it[1]) >= b - halfPx } ?: return@mapNotNull null
            val heard = heardNs(b) ?: return@mapNotNull null
            val video = pres.firstOrNull { it[0] >= b }?.get(2) ?: return@mapNotNull null
            doubleArrayOf((at[0] - heard) / 1e6, (at[0] - video) / 1e6, (video - heard) / 1e6)
        }
        if (perBeat.isNotEmpty()) report("A1 $label per beat (${perBeat.size}): at the line minus heard (+ = timeline late) ${Aud2Bench.stats(perBeat.map { it[0] })} median %+.1f; minus its preview frame presented ${Aud2Bench.stats(perBeat.map { it[1] })} median %+.1f; preview frame minus heard median %+.1f; first: ".format(
            Aud2Bench.pct(perBeat.map { it[0] }, .5), Aud2Bench.pct(perBeat.map { it[1] }, .5), Aud2Bench.pct(perBeat.map { it[2] }, .5)) + perBeat.take(12).joinToString(" ") { "%+.0f".format(it[0]) })
    }

    private fun heardNs(us: Long): Long? {
        val ts = rec.stamps.toList().filter { it[0] > 0 }
        val start = ts.firstOrNull()?.get(2) ?: return null
        val frame = us * 48_000 / 1_000_000 - start
        val s = ts.lastOrNull { it[0] <= frame } ?: return null
        return s[1] + (frame - s[0]) * 1_000_000_000L / 48_000
    }
}
