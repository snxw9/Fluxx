package com.fluxx.android.render

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.engine.RenderBridge
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * TEXT PERF 11d: the idle sharp-tier warm-up runs while the preview is quiet and not while frames render. A layer
 * keyframed 30 -> 300 px opens paused at 30 px (base tier): within the idle window its 96/192/384 ppem glyphs are
 * generated in the background, so seeking to 300 px settles without waiting for tiers. While frames render back to back
 * (playback) nothing is warmed.
 */
@RunWith(AndroidJUnit4::class)
class TierWarmUpTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun stats(renderer: CompositionRenderer) = JSONObject(RenderBridge.textStats(renderer.session))

    @Test fun idleWarmUpPreparesTheTiersASizeAnimationReaches() {
        // Glyphs the tier catalog has not seen in this process (the catalog is retained across renderers).
        val source = "Warm QZXJK 789 wq"
        val text = TextProperties(fontId = "fluxx.mono", source = AnimatableString(staticValue = source),
            size = AnimatableProperty1D(true, 30f, FrozenList.of(listOf(Keyframe1D(0, 30f, EasingPreset.LINEAR), Keyframe1D(3_000_000, 300f, EasingPreset.LINEAR)))))
        val project = ProjectDocument(Composition(width = 1080, height = 1920, durationUs = 4_000_000,
            layers = FrozenList.of(listOf(CompositionLayer(1, LayerType.TEXT, timing = ClipTiming(durationUs = 4_000_000), text = text)))))
        val host = instrumentation.startActivitySync(Intent(context, TextProofActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TextProofActivity
        val worker = Executors.newSingleThreadExecutor()
        try {
            val surface = host.awaitSurface()
            worker.submit {
                CompositionRenderer(context, MediaRepository(context), surface, 1080, 1920).use { renderer ->
                    renderer.renderPreview(project, 0, PreviewResolution.FULL) // Opens at 30 px: base tier.
                    // Playback-like frames: nothing may be warmed while frames render back to back.
                    for (frame in 1..30) renderer.renderPreview(project, frame * 1_000L, PreviewResolution.FULL)
                    val busy = stats(renderer)
                    assertEquals("no warm-up while frames render", 0L, busy.getLong("tierWarmed"))
                    assertTrue("warm-up was queued", busy.getLong("tierWarmQueued") > 0)
                    // Quiet: the tier thread drains the warm queue (384 ppem glyphs take > 100 ms each on the A16).
                    val quiet = System.nanoTime(); var idle = stats(renderer)
                    while (idle.getLong("tierWarmed") < idle.getLong("tierWarmQueued") && System.nanoTime() - quiet < 20_000_000_000L) {
                        Thread.sleep(250); idle = stats(renderer)
                    }
                    android.util.Log.i("FluxxPerf11", "tier warm-up: queued=${idle.getLong("tierWarmQueued")} warmed=${idle.getLong("tierWarmed")} " +
                        "in ${(System.nanoTime() - quiet) / 1_000_000} ms of idle")
                    assertTrue("warm glyphs generated in idle time", idle.getLong("tierWarmed") > 0)
                    val start = System.nanoTime()
                    renderer.renderPreview(project, 3_000_000, PreviewResolution.FULL) // Seek to 300 px (384 ppem tier).
                    val settleMs = (System.nanoTime() - start) / 1e6
                    val pending = RenderBridge.awaitTextTiers(renderer.session, 0)
                    android.util.Log.i("FluxxPerf11", "tier warm-up: seek to 300 px settled in ${"%.1f".format(settleMs)} ms, pending=$pending")
                    assertEquals(0, pending)
                    assertTrue("the seek did not wait for tier generation ($settleMs ms)", settleMs < 400)
                }
            }.get(2, TimeUnit.MINUTES)
        } finally { worker.shutdownNow(); instrumentation.runOnMainSync { host.finish() } }
    }
}
