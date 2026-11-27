package com.fluxx.android.ui

import com.fluxx.android.editor.EditorAction
import com.fluxx.android.model.ClipTiming
import org.junit.Test
import java.io.File

/**
 * AUD2 A2: Pause then Resume (the app's Play / Pause toggle) at several SOURCE positions, on both benchmark files: the time
 * from Resume to the first presented frame and the first frame that moves, the largest jump between consecutive presented
 * frames, skipped and late frames, the first sound, what the video decoder does at Pause and at Resume, and the clock at
 * Resume (fallback head minus latency until the first AudioTimestamp, the jump when it becomes valid). Numbers only.
 */
class Aud2ResumeTest : Aud2Base() {
    @Test fun a2ResumeVp9() = run("VP9", file("VP9 worst-case", Aud2Bench.VP9))
    @Test fun a2ResumeTypical() = run("H.264", file("typical H.264", Aud2Bench.TYPICAL))

    private lateinit var m: Aud1Bench.Media
    private var frameUs = 50_000L

    private fun run(tag: String, f: File) {
        m = Aud1Bench.media(f); frameUs = 1_000_000L / m.fps
        rec.start()
        val (w, h) = Aud2Bench.canvas(m)
        val full = Aud2Bench.project(w, h, m.durationUs, m.fps, listOf(Aud2Bench.layer(1, f, m)))
        host(full)
        warmPcm(android.net.Uri.fromFile(f).toString(), m.durationUs)
        val mid = m.durationUs / 2; val nearEnd = m.durationUs - 8_000_000
        report("A2 $tag file: %.1f s %dx%d %d fps".format(m.durationUs / 1e6, m.width, m.height, m.fps))
        repeat(2) { n ->
            case("$tag try ${n + 1}, Play at 0 (never played; paused at frame 0)") { seekTo(0) }
            case("$tag try ${n + 1}, paused after 3 s from 0 (source ~3 s)") { seekTo(0); toggle(); Thread.sleep(3000) ; pauseThen() }
            case("$tag try ${n + 1}, paused at the middle (source ~${mid / 1_000_000 + 3} s)") { seekTo(mid); toggle(); Thread.sleep(3000); pauseThen() }
            case("$tag try ${n + 1}, paused near the end (source ~${nearEnd / 1_000_000 + 3} s)") { seekTo(nearEnd); toggle(); Thread.sleep(3000); pauseThen() }
            case("$tag try ${n + 1}, after Play ran to the end (Play restarts at 0)") {
                seekTo(m.durationUs - 2_500_000); toggle(); Thread.sleep(4500)
                if (playing) toggle()
                Thread.sleep(800)
            }
        }
        // Trimmed: the in-point is mid-file and the clip starts at timeline 0 (the source position follows the in-point).
        val trimmed = Aud2Bench.project(w, h, m.durationUs - mid, m.fps, listOf(Aud2Bench.layer(1, f, m, timing = ClipTiming(0, mid, m.durationUs - mid))))
        compose.runOnIdle { editor.dispatch(EditorAction.Load(trimmed)); c.update(editor.state.project, 0, false) }
        Thread.sleep(1500)
        repeat(2) { n ->
            case("$tag try ${n + 1}, trimmed (in-point ${mid / 1_000_000} s, at timeline 0), Play at timeline 0") { seekTo(0) }
            case("$tag try ${n + 1}, trimmed, paused after 3 s (source ~${mid / 1_000_000 + 3} s)") { seekTo(0); toggle(); Thread.sleep(3000); pauseThen() }
        }
    }

    /** Marks where the pause window starts: the case's Pause is measured from here. */
    private var pauseAt = 0L
    private fun pauseThen() { rec.clear(); pauseAt = System.nanoTime(); toggle(); Thread.sleep(1000) }

    private fun case(label: String, prepare: () -> Unit) {
        pauseAt = 0L
        rec.clear()
        prepare()
        val pausedUs = compose.runOnIdle { editor.state.playheadUs }
        val pauseEvents = rec.decoder.toList()
        val pausePlayback = rec.playback.toList()
        rec.clear()
        val t0 = System.nanoTime()
        toggle()
        Thread.sleep(3500)
        if (playing) toggle()
        val frames = rec.frames.toList().filter { it[2] >= t0 }
        val window = frames.filter { it[2] < t0 + 3_000_000_000L }
        val first = frames.firstOrNull()?.let { (it[2] - t0) / 1e6 } ?: Double.NaN
        val moving = frames.firstOrNull { it[0] > pausedUs }?.let { (it[2] - t0) / 1e6 } ?: Double.NaN
        val jumps = window.zipWithNext { a, b -> Triple(b[0] - a[0], a, b) }
        val maxJump = jumps.maxOfOrNull { it.first } ?: 0L
        val maxJumpLate = jumps.filter { it.second[2] >= t0 + 150_000_000L }.maxOfOrNull { it.first } ?: 0L
        val skipped = jumps.sumOf { ((it.first / frameUs) - 1).coerceAtLeast(0) }
        // Late: presented more than one frame after its time is heard.
        val late = window.count { fr -> heardNs(fr[0])?.let { fr[2] - it > frameUs * 1000 } ?: false }
        val sound = rec.firstSoundNs()?.let { (it - t0) / 1e6 } ?: Double.NaN
        // The clock at Resume: how long it sat at the start, the first valid AudioTimestamp, and the jump when it arrives.
        val clock = rec.clock.toList().filter { it[0] >= t0 }
        val startUs = clock.firstOrNull()?.get(3)
        val leftStart = clock.firstOrNull { startUs != null && it[3] > startUs }?.let { (it[0] - t0) / 1e6 } ?: Double.NaN
        val tsIdx = clock.indexOfFirst { it[2] == 1L }
        val tsAt = if (tsIdx >= 0) (clock[tsIdx][0] - t0) / 1e6 else Double.NaN
        val tsJump = if (tsIdx > 0) { val a = clock[tsIdx - 1]; val b = clock[tsIdx]; ((b[3] - a[3]) - (b[0] - a[0]) / 1000) / 1000.0 } else Double.NaN
        val steps = clock.zipWithNext { a, b -> ((b[3] - a[3]) - (b[0] - a[0]) / 1000) / 1000.0 }
        val worstStep = steps.maxByOrNull { kotlin.math.abs(it) } ?: Double.NaN
        fun dec(ev: List<Pair<Long, String>>): String {
            val opens = ev.count { it.second.startsWith("open ") }; val fails = ev.count { it.second.startsWith("open-failed") }
            val closes = ev.count { it.second.startsWith("close") }
            val seeks = ev.filter { it.second.startsWith("frame") && !it.second.contains("seek=-1") }
            val cancelled = ev.count { it.second.startsWith("cancelled") }
            val decoded = ev.filter { it.second.startsWith("frame") }.sumOf { Regex("decoded=(\\d+)").find(it.second)?.groupValues?.get(1)?.toInt() ?: 0 }
            val worst = ev.filter { it.second.startsWith("frame") }.maxOfOrNull { Regex("us=(\\d+)").find(it.second)?.groupValues?.get(1)?.toLong() ?: 0 } ?: 0
            return "opens $opens${if (fails > 0) " (FAILED $fails)" else ""}, closes $closes, flush+seek ${seeks.size}${seeks.take(2).joinToString("") { " [" + it.second.substringAfter(' ').substringAfter(' ') + "]" }}, cancelled $cancelled, frames decoded $decoded, slowest frameAt %.0f ms".format(worst / 1000.0)
        }
        val resumeDec = rec.decoder.toList().filter { it.first < t0 + 3_000_000_000L }
        report("A2 $label: paused at ${pausedUs / 1000} ms; Resume -> first presented frame %.0f ms, first frame that moves %.0f ms; largest jump in 3 s %.0f ms (%.1f frames), after 150 ms %.0f ms (%.1f frames); skipped %d, late %d of %d; first sound %.0f ms".format(
            first, moving, maxJump / 1000.0, maxJump.toDouble() / frameUs, maxJumpLate / 1000.0, maxJumpLate.toDouble() / frameUs, skipped, late, window.size, sound))
        report("A2 $label: decoder at Pause: ${if (pauseAt == 0L) "(no pause in this case)" else dec(pauseEvents)}; playback at Pause: ${pausePlayback.joinToString { it.second }.ifEmpty { "-" }}")
        report("A2 $label: decoder at Resume (3 s): ${dec(resumeDec)}; first frameAt: ${resumeDec.firstOrNull { it.second.startsWith("frame") || it.second.startsWith("cancelled") }?.second ?: "-"}")
        report("A2 $label: clock at Resume: ${clock.size} reads; left the start position at %.0f ms; first valid AudioTimestamp at %.0f ms, jump then %+.1f ms; largest step error %+.1f ms; playback events: %s".format(
            leftStart, tsAt, tsJump, worstStep, rec.playback.toList().joinToString { "%s@%.0f".format(it.second, (it.first - t0) / 1e6) }))
        Thread.sleep(500)
    }

    private fun heardNs(us: Long): Long? {
        val ts = rec.stamps.toList().filter { it[0] > 0 }
        val start = ts.firstOrNull()?.get(2) ?: return null
        val frame = us * 48_000 / 1_000_000 - start
        val s = ts.lastOrNull { it[0] <= frame } ?: return null
        return s[1] + (frame - s[0]) * 1_000_000_000L / 48_000
    }
}
