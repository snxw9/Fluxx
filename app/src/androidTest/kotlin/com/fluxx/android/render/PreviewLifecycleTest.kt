package com.fluxx.android.render

import android.content.Intent
import android.os.SystemClock
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.model.ProjectDocument
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * TEXT PERF 11 Part 2: Home during playback, then return. The preview is wired exactly as MainActivity wires it
 * (pause on onPause, close the controller when the surface is destroyed, a new controller at the editor's playhead
 * when the surface returns, resize on surfaceChanged). Home is a real `input keyevent KEYCODE_HOME`; the return
 * brings the same task to the front. No UI is tapped. Reports the playhead across the stopped interval and the
 * playback cadence (position jumps, gaps between presented positions) before Home and after the return.
 */
@RunWith(AndroidJUnit4::class)
class PreviewLifecycleTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private class Sample(val wallMs: Long, val positionUs: Long)

    @Test fun homeDuringPlaybackThenReturn() {
        val label = requireNotNull(InstrumentationRegistry.getArguments().getString("textRunLabel"))
        require(label.matches(Regex("[A-Za-z0-9_-]+")))
        val out = File(context.getExternalFilesDir(null), "text/$label").also { it.mkdirs() }
        val kind = TextMultiLayerFixture.Kind.valueOf(InstrumentationRegistry.getArguments().getString("lifecycleKind") ?: "SIZE")
        val stacked = InstrumentationRegistry.getArguments().getString("lifecycleLayout") == "stacked"
        val project = TextMultiLayerFixture.project(context, 30, true, kind, stacked)
        val samples = CopyOnWriteArrayList<Sample>()
        val closer = Executors.newSingleThreadExecutor()
        var preview: PreviewController? = null // Main thread only, like MainActivity.preview.
        var displayedUs = 0L
        var surface: Surface? = null; var width = 1; var height = 1
        fun refresh() {
            val s = surface ?: return
            if (preview == null) preview = PreviewController(context, s, width, height,
                onPosition = { displayedUs = it; samples.add(Sample(SystemClock.uptimeMillis(), it)) })
            preview?.update(project, displayedUs, false)
        }
        val host = instrumentation.startActivitySync(Intent(context, TextProofActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TextProofActivity
        try {
            val first = host.awaitSurface()
            instrumentation.runOnMainSync {
                host.lifecycle = object : TextProofActivity.Lifecycle {
                    override fun paused() { preview?.pause() }
                    override fun resumed() { if (surface != null && preview == null) refresh() }
                    override fun surfaceCreated(surface0: Surface, width0: Int, height0: Int) { surface = surface0; width = width0; height = height0; refresh() }
                    override fun surfaceChanged(width0: Int, height0: Int) {
                        val changed = width != width0 || height != height0; width = width0; height = height0
                        if (preview == null) refresh() else if (changed) preview?.resize(width0, height0)
                    }
                    override fun surfaceDestroyed() {
                        surface = null; val old = preview ?: return; preview = null
                        closer.execute { runCatching { runBlocking { old.close() } } }
                    }
                }
                surface = first; width = host.window.decorView.width.coerceAtLeast(1); height = host.window.decorView.height.coerceAtLeast(1)
                refresh()
            }
            fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
                val until = SystemClock.uptimeMillis() + timeoutMs
                while (SystemClock.uptimeMillis() < until && !condition()) SystemClock.sleep(20)
            }
            waitFor(20_000) { samples.isNotEmpty() }
            SystemClock.sleep(3_000) // Settle (tiers, idle prewarm window).
            samples.clear()
            val playBefore = SystemClock.uptimeMillis()
            instrumentation.runOnMainSync { preview?.play() }
            SystemClock.sleep(2_500)
            val beforeHome = samples.toList()
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            waitFor(10_000) { instrumentation.runOnMainSync { }; preview == null }
            var playheadAtStop = 0L; instrumentation.runOnMainSync { playheadAtStop = displayedUs }
            val stoppedMs = 4_000L
            SystemClock.sleep(stoppedMs)
            var playheadWhileStopped = 0L; instrumentation.runOnMainSync { playheadWhileStopped = displayedUs }
            samples.clear()
            val returned = SystemClock.uptimeMillis()
            context.startActivity(Intent(context, TextProofActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            waitFor(20_000) { samples.isNotEmpty() }
            val firstFrameAfterReturnMs = samples.firstOrNull()?.let { it.wallMs - returned } ?: -1
            val playheadAfterReturn = samples.firstOrNull()?.positionUs ?: -1
            samples.clear()
            val playAfter = SystemClock.uptimeMillis()
            instrumentation.runOnMainSync { preview?.play() }
            SystemClock.sleep(3_000)
            val afterReturn = samples.toList()
            // Then from the start, as a user replaying the clip does: the rebuilt renderer needs every sharp tier again.
            instrumentation.runOnMainSync { preview?.seek(0) }
            SystemClock.sleep(1_500)
            samples.clear()
            val playFromStart = SystemClock.uptimeMillis()
            instrumentation.runOnMainSync { preview?.play() }
            SystemClock.sleep(3_000)
            val fromStartAfterReturn = samples.toList()
            instrumentation.runOnMainSync { preview?.pause() }
            val report = JSONObject().put("kind", kind.name).put("layers", 30).put("stacked", stacked)
                .put("beforeHome", cadence(beforeHome, playBefore)).put("afterReturn", cadence(afterReturn, playAfter))
                .put("fromStartAfterReturn", cadence(fromStartAfterReturn, playFromStart))
                .put("playheadAtStopUs", playheadAtStop).put("playheadWhileStoppedUs", playheadWhileStopped)
                .put("playheadAfterReturnUs", playheadAfterReturn).put("stoppedMs", stoppedMs)
                .put("firstFrameAfterReturnMs", firstFrameAfterReturnMs)
            File(out, "perf11-lifecycle-${kind.name.lowercase()}${if (stacked) "-stacked" else ""}.json").writeText(report.toString(2))
            android.util.Log.i("FluxxPerf11", "lifecycle ${kind.name}: $report".replace('\n', ' '))
            assertTrue("Playhead advanced while stopped: $playheadAtStop -> $playheadWhileStopped -> $playheadAfterReturn",
                playheadWhileStopped == playheadAtStop && playheadAfterReturn == playheadAtStop)
        } finally {
            instrumentation.runOnMainSync { host.lifecycle = null; val old = preview; preview = null; if (old != null) closer.execute { runCatching { runBlocking { old.close() } } } }
            closer.shutdown(); closer.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
            instrumentation.runOnMainSync { host.finish() }
        }
    }

    /** Presented positions after play(): count, first-frame delay, largest playhead jump and largest wall gap. */
    private fun cadence(samples: List<Sample>, playMs: Long): JSONObject {
        val jumps = samples.zipWithNext { a, b -> b.positionUs - a.positionUs }
        val gaps = samples.zipWithNext { a, b -> b.wallMs - a.wallMs }
        return JSONObject().put("frames", samples.size)
            .put("firstFrameMs", samples.firstOrNull()?.let { it.wallMs - playMs } ?: -1)
            .put("firstPositionUs", samples.firstOrNull()?.positionUs ?: -1).put("lastPositionUs", samples.lastOrNull()?.positionUs ?: -1)
            .put("maxJumpUs", jumps.maxOrNull() ?: 0).put("maxGapMs", gaps.maxOrNull() ?: 0)
            .put("jumpsOver100ms", jumps.count { it > 100_000 })
            .put("positionsUs", JSONArray(samples.take(400).map { it.positionUs }))
            .put("wallMs", JSONArray(samples.take(400).map { it.wallMs - playMs }))
    }
}
