package com.fluxx.android.render

import android.content.Intent
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.engine.TextDebugBridge
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * TEXT PERF 10: per-frame stage profile of the TEXT FIX 9d F2 fixtures (same project builder), in three modes:
 * a static frame (same time every frame), scrubbing (deterministic random seeks) and playback (consecutive frames).
 * Reports p50/p95 per stage. Kotlin stages come from CompositionRenderer, native stages and GPU time (timestamp
 * queries) from VulkanRenderer. A profiling harness, not a pass/fail limit: every run writes JSON under
 * files/text/<textRunLabel>/perf10-*.json and logs one summary line per mode (tag FluxxPerf10).
 */
@RunWith(AndroidJUnit4::class)
class TextPerfProfileTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val kotlinNames = listOf("frame", "tiers", "evaluate", "prepareText", "textLayerMesh", "finish")
    private val nativeNames = listOf("workFenceWait", "batchRecord", "batchSubmitWait", "presentFenceWait", "acquire",
        "presentRecord", "presentSubmit", "presentIdleWait", "gpuBatch", "gpuPresent", "batches", "frames")

    @Test fun representativeStages() = profile("representative", false)
    @Test fun maximumStages() = profile("maximum", true, warm = 10, frames = 40)
    @Test fun richRepresentativeStages() = profile("rich-representative", false, rich = true)
    @Test fun wrappedRepresentativeStages() = profile("wrapped-representative", false, rich = true, wrapped = true)
    @Test fun characterRepresentativeStages() = profile("character-representative", false, rich = true, wrapped = true, advanced = true, characters = true)

    private fun directory(): File {
        val label = requireNotNull(InstrumentationRegistry.getArguments().getString("textRunLabel"))
        require(label.matches(Regex("[A-Za-z0-9_-]+")))
        return File(context.getExternalFilesDir(null), "text/$label").also { it.mkdirs() }
    }
    private fun profile(name: String, maximum: Boolean, warm: Int = 120, frames: Int = 300, rich: Boolean = false,
                        wrapped: Boolean = false, advanced: Boolean = false, characters: Boolean = false) {
        val out = directory()
        val modes = (InstrumentationRegistry.getArguments().getString("perfModes") ?: "static,scrub,playback").split(',')
        val host = instrumentation.startActivitySync(Intent(context, TextProofActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TextProofActivity
        val worker = Executors.newSingleThreadExecutor()
        try {
            val surface = host.awaitSurface()
            worker.submit {
                CompositionRenderer(context, MediaRepository(context), surface, 1080, 1920).use { renderer ->
                    val project = TextF2AcceptanceTest().fixture(maximum, rich, null, wrapped, advanced, characters)
                    val power = context.getSystemService(PowerManager::class.java)
                    val kotlin = LongArray(CompositionRenderer.STAGES); val native = LongArray(TextDebugBridge.STAGES)
                    for (mode in modes) {
                        val random = java.util.Random(0x5eedL)
                        fun time(frame: Int) = when (mode) {
                            "static" -> 5_000_000L
                            "scrub" -> random.nextInt(600) * 16_667L
                            else -> (frame % 600) * 16_667L
                        }
                        repeat(warm) { renderer.renderPreview(project, time(it), PreviewResolution.FULL) }
                        renderer.frameStages(kotlin); TextDebugBridge.frameStages(renderer.session, native)
                        val samplesK = Array(kotlin.size) { LongArray(frames) }; val samplesN = Array(native.size) { LongArray(frames) }
                        val thermalBefore = power.currentThermalStatus; val headroomBefore = power.getThermalHeadroom(0)
                        val began = System.nanoTime()
                        for (frame in 0 until frames) {
                            renderer.renderPreview(project, time(warm + frame), PreviewResolution.FULL)
                            renderer.frameStages(kotlin); TextDebugBridge.frameStages(renderer.session, native)
                            for (s in kotlin.indices) samplesK[s][frame] = kotlin[s]
                            for (s in native.indices) samplesN[s][frame] = native[s]
                        }
                        val wall = (System.nanoTime() - began) / 1e6
                        val report = JSONObject().put("case", name).put("mode", mode).put("frames", frames).put("warmFrames", warm)
                            .put("wallMs", wall).put("thermalStatusBefore", thermalBefore).put("thermalStatusAfter", power.currentThermalStatus)
                            .put("thermalHeadroomBefore", headroomBefore.toDouble()).put("thermalHeadroomAfter", power.getThermalHeadroom(0).toDouble())
                        val kotlinJson = JSONObject(); val nativeJson = JSONObject()
                        kotlinNames.forEachIndexed { i, n -> kotlinJson.put(n, percentiles(samplesK[i])) }
                        nativeNames.forEachIndexed { i, n -> nativeJson.put(n, if (n == "batches" || n == "frames") samplesN[i].average() else percentiles(samplesN[i])) }
                        report.put("kotlinStagesMs", kotlinJson).put("nativeStagesMs", nativeJson)
                        report.put("textStats", JSONObject(com.fluxx.android.engine.RenderBridge.textStats(renderer.session)))
                        File(out, "perf10-$name-$mode.json").writeText(report.toString(2))
                        android.util.Log.i("FluxxPerf10", "$name/$mode: frame p50=${p(samplesK[0], 50)} p95=${p(samplesK[0], 95)} ms; " +
                            "evaluate p50=${p(samplesK[2], 50)} tiers p50=${p(samplesK[1], 50)} mesh p50=${p(samplesK[4], 50)} " +
                            "finish p50=${p(samplesK[5], 50)} gpuBatch p50=${p(samplesN[8], 50)} gpuPresent p50=${p(samplesN[9], 50)} " +
                            "batchWait p50=${p(samplesN[2], 50)} acquire p50=${p(samplesN[4], 50)} idleWait p50=${p(samplesN[7], 50)} " +
                            "thermal=$thermalBefore->${power.currentThermalStatus}")
                    }
                }
            }.get(30, TimeUnit.MINUTES)
        } finally { worker.shutdownNow(); instrumentation.runOnMainSync { host.finish() } }
    }
    private fun p(values: LongArray, percentile: Int): String {
        val sorted = values.copyOf().also { it.sort() }
        return "%.2f".format(sorted[((sorted.size - 1) * percentile) / 100] / 1e6)
    }
    private fun percentiles(values: LongArray): JSONObject {
        val sorted = values.copyOf().also { it.sort() }
        fun at(q: Int) = sorted[((sorted.size - 1) * q) / 100] / 1e6
        return JSONObject().put("p50", at(50)).put("p95", at(95)).put("max", sorted.last() / 1e6)
            .put("mean", sorted.average() / 1e6)
    }
}
