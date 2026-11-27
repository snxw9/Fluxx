package com.fluxx.android.ui

import com.fluxx.android.editor.EditorAction
import androidx.compose.ui.test.*
import org.junit.Test
import java.io.File

/**
 * AUD2 A4: gain. (1) The crash: the slider's own edit path (what EditorScreen's onAudioChange runs for one slider step),
 * driven from the test at 100 %, 150 %, 200 % and the slider's maximum, stopped and playing; the exception is caught ON
 * the main thread and its stack reported (in the app it is uncaught there). (2) Slider drag: many steps 16 ms apart,
 * stopped and playing: UI frame pacing, the main-thread cost per step and what each step triggers (playback re-creation,
 * re-render, decoder work), then the Resume after a gain change. Numbers only.
 */
class Aud2GainTest : Aud2Base() {
    /**
     * CompactSliderItem in ElementInspectorSheet. Before AUD2 B3: valueRange 0f..2f (its direct entry clamped to 0..2);
     * after: [com.fluxx.android.render.AudioGains.RANGE] (the model's 0..1).
     */
    private val sliderMax = com.fluxx.android.render.AudioGains.RANGE.endInclusive
    private lateinit var m: Aud1Bench.Media
    private lateinit var f: File

    /**
     * One slider event through the app's path. BEFORE the AUD2 fix (commit a24d63f) this was EditorScreen's onAudioChange:
     * a full SetAudio dispatch (its own undo step) then PreviewController.update(.., play = false) for every step. AFTER
     * (B3) it is exactly what the slider calls: [com.fluxx.android.ui.editor.GainEdits] begin / preview / commit.
     */
    private fun gainEvent(phase: Int, gain: Float) = when (phase) {
        0 -> com.fluxx.android.ui.editor.GainEdits.begin(editor)
        1 -> com.fluxx.android.ui.editor.GainEdits.preview(editor, c, 1, false, gain)
        else -> com.fluxx.android.ui.editor.GainEdits.commit(editor, c)
    }

    private fun undoDepth() = (com.fluxx.android.editor.EditorViewModel::class.java.getDeclaredField("undoStack").apply { isAccessible = true }.get(editor) as List<*>).size

    @Test fun a4GainCrashAndDrag() {
        f = file("VP9 worst-case", Aud2Bench.VP9); m = Aud1Bench.media(f)
        rec.start()
        val (w, h) = Aud2Bench.canvas(m)
        host(Aud2Bench.project(w, h, m.durationUs, m.fps, listOf(Aud2Bench.layer(1, f, m))))
        warmPcm(android.net.Uri.fromFile(f).toString(), m.durationUs)
        val mid = m.durationUs / 2

        // (1) The crash, per gain, stopped and playing.
        for (g in listOf(1f, 1.5f, 2f, sliderMax)) for (whilePlaying in listOf(false, true)) {
            seekTo(mid)
            if (whilePlaying) { toggle(); Thread.sleep(1500) }
            var error: Throwable? = null
            compose.runOnUiThread { try { gainEvent(0, g); gainEvent(1, g); gainEvent(2, g) } catch (t: Throwable) { error = t } }
            Thread.sleep(600)
            val stillPlaying = playing
            val gainNow = compose.runOnIdle { editor.state.project.composition.layers.first { it.id == 1L }.audioGain }
            report("A4 crash, gain %.0f%%, %s: %s; layer gain now %.2f; playing after the step: %s; status '%s'".format(g * 100, if (whilePlaying) "playing" else "stopped",
                error?.let { "THREW ${it.javaClass.name}: ${it.message}" } ?: "no exception", gainNow, stillPlaying, c.state.value.message))
            error?.let { e -> report("A4 crash stack (gain %.0f%%): ".format(g * 100) + e.stackTrace.take(14).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }) }
            if (playing) toggle()
            compose.runOnIdle { editor.dispatch(EditorAction.SetAudio(1, false, 1f)); c.update(editor.state.project, editor.state.playheadUs, false) }
            Thread.sleep(300)
        }

        // (2) Plain playback (the UI pacing reference), then slider drags stopped and playing.
        seekTo(mid); rec.clear()
        val plain = uiFrames(compose.activity.window) { toggle(); Thread.sleep(5000); toggle() }
        report("A4 plain playback UI frames: ${Aud2Bench.stats(plain)} ms")
        // Open the inspector's Audio page (timeline selection opens the sheet), so the slider and its readout are on screen
        // and recompose with every step, as in the app.
        val opened = runCatching {
            // At timeline 0 the clip starts under the playhead: tap a point of it that is on screen (its centre is not).
            seekTo(0)
            compose.onNodeWithTag("timeline-clip-hit-1", useUnmergedTree = true).performTouchInput { click(androidx.compose.ui.geometry.Offset(40f, height / 2f)) }
            compose.waitForIdle(); Thread.sleep(600)
            compose.onAllNodesWithText("Audio", useUnmergedTree = true).onFirst().performClick(); compose.waitForIdle(); Thread.sleep(600)
            compose.onAllNodesWithText("Gain", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        report("A4 inspector Audio page open: ${opened.getOrElse { "failed: ${it.message}" }}")
        for (whilePlaying in listOf(false, true)) {
            seekTo(mid)
            if (whilePlaying) { toggle(); Thread.sleep(2000) }
            val undoBefore = compose.runOnIdle { undoDepth() }
            rec.clear()
            val stepMs = ArrayList<Double>()
            val stepAt = ArrayList<Long>()
            val steps = 60
            val ui = uiFrames(compose.activity.window) {
                compose.runOnUiThread { gainEvent(0, 1f) }
                for (i in 1..steps) {
                    val g = 1f - 0.9f * i / steps
                    compose.runOnUiThread { val t = System.nanoTime(); stepAt += t; gainEvent(1, g); stepMs += (System.nanoTime() - t) / 1e6 }
                    Thread.sleep(16)
                }
                compose.runOnUiThread { gainEvent(2, 0.1f) }
                Thread.sleep(800)
            }
            val label = if (whilePlaying) "playing" else "stopped"
            val ev = rec.playback.toList()
            val dec = rec.decoder.toList()
            report("A4 drag ($label, $steps steps 1.0 -> 0.1): UI frames ${Aud2Bench.stats(ui)} ms; main-thread time per step ${Aud2Bench.stats(stepMs)} ms")
            report("A4 drag ($label): playback created ${ev.count { it.second.startsWith("playback-create") }}, closed ${ev.count { it.second == "playback-close" }}, track started ${ev.count { it.second == "track-play" }}, live gain updates ${ev.count { it.second.startsWith("gain-") }}; preview frames presented ${rec.frames.size}; decoder events ${dec.size} (flush+seek ${dec.count { it.second.startsWith("frame") && !it.second.contains("seek=-1") }}, frames decoded ${dec.filter { it.second.startsWith("frame") }.sumOf { Regex("decoded=(\\d+)").find(it.second)?.groupValues?.get(1)?.toInt() ?: 0 }}); still playing after the drag: $playing; undo entries added by the drag: ${compose.runOnIdle { undoDepth() } - undoBefore}")
            // Live gain: from the step to the gain reaching the output (track volume: immediate; per-layer weights from the
            // next block), and how much audio was already queued at that moment (heard later at the old weights).
            val live = ev.filter { it.second.startsWith("gain-live") }
            if (live.isNotEmpty()) {
                val applied = stepAt.mapNotNull { s -> live.firstOrNull { it.first >= s }?.let { (it.first - s) / 1e6 } }
                val queued = live.mapNotNull { e -> val w = Regex("written=(\\d+)").find(e.second)?.groupValues?.get(1)?.toLong() ?: return@mapNotNull null
                    val start = rec.stamps.firstOrNull()?.get(2) ?: return@mapNotNull null
                    val heard = rec.heardUs(e.first) ?: return@mapNotNull null
                    w / 48.0 - (heard - start * 1_000_000 / 48_000) / 1000.0 }
                val lag = Aud1Bench.track(c)?.let { t -> Aud1Bench.latencyMs(t)?.minus(t.bufferSizeInFrames / 48) }
                report("A4 drag ($label): step -> gain applied (track volume set) ${Aud2Bench.stats(applied)} ms; audio already queued at that moment ${Aud2Bench.stats(queued)} ms; output path after the mixer (getLatency - buffer) ${lag ?: "?"} ms")
            }
            // Resume after the gain change: what the next Play pays.
            if (playing) toggle()
            Thread.sleep(600)
            rec.clear(); val t0 = System.nanoTime(); val at = compose.runOnIdle { editor.state.playheadUs }
            toggle(); Thread.sleep(2500)
            val frames = rec.frames.toList().filter { it[2] >= t0 }
            val moving = frames.firstOrNull { it[0] > at }?.let { (it[2] - t0) / 1e6 } ?: Double.NaN
            val firstFrame = frames.firstOrNull()?.let { (it[2] - t0) / 1e6 } ?: Double.NaN
            val jumps = frames.filter { it[2] >= t0 + 150_000_000 }.zipWithNext { a, b -> b[0] - a[0] }
            report("A4 resume after the $label drag: first frame %.0f ms, first moving frame %.0f ms, first sound %.0f ms, largest jump after 150 ms %.0f ms".format(
                firstFrame, moving, rec.firstSoundNs()?.let { (it - t0) / 1e6 } ?: Double.NaN, (jumps.maxOrNull() ?: 0L) / 1000.0))
            if (playing) toggle()
            compose.runOnIdle { editor.dispatch(EditorAction.SetAudio(1, false, 1f)); c.update(editor.state.project, editor.state.playheadUs, false) }
            Thread.sleep(500)
        }
    }
}
