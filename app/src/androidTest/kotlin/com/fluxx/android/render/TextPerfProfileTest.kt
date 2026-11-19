package com.fluxx.android.render

import android.content.Intent
import android.os.Debug
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
 * TEXT PERF 10/11: per-frame stage profile in three modes: a static frame (same time every frame), scrubbing
 * (deterministic random seeks) and playback (consecutive 60 fps frames). Reports p50/p95 per stage. Kotlin stages come
 * from CompositionRenderer, native stages and GPU time (timestamp queries) from VulkanRenderer.
 *
 * PERF 10 cases run the TEXT FIX 9d F2 fixtures. PERF 11 cases ([TextMultiLayerFixture]) split the 30-line layer into
 * 30 or 60 separate layers, without animators, with the F2 animator stack, and with font size keyframed 30→300 px over
 * 3 s; each is compared with the single-layer baseline of the same text. A profiling harness, not a pass/fail limit:
 * every run writes JSON under files/text/<textRunLabel>/perf1x-*.json and logs one summary line per mode
 * (tags FluxxPerf10 / FluxxPerf11). `-e perfModes static,scrub,playback` and `-e perfCases a,b` narrow a run.
 */
@RunWith(AndroidJUnit4::class)
class TextPerfProfileTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val kotlinNames = listOf("frame", "tiers", "evaluate", "prepareText", "textLayerMesh", "finish", "layers", "flushes", "tierJni")
    private val kotlinCounts = setOf("layers", "flushes")
    private val nativeNames = listOf("workFenceWait", "batchRecord", "batchSubmitWait", "presentFenceWait", "acquire",
        "presentRecord", "presentSubmit", "presentIdleWait", "gpuBatch", "gpuPresent", "batches", "frames",
        "nativeTextLayers", "nativeMeshBuild", "collectPack", "collects", "atlasUpload", "tierCalls", "placementLookups")
    private val nativeCounts = setOf("batches", "frames", "nativeTextLayers", "collects", "tierCalls", "placementLookups")

    @Test fun representativeStages() = f2("representative", false)
    @Test fun maximumStages() = f2("maximum", true, warm = 10, frames = 40)
    @Test fun richRepresentativeStages() = f2("rich-representative", false, rich = true)
    @Test fun wrappedRepresentativeStages() = f2("wrapped-representative", false, rich = true, wrapped = true)
    @Test fun characterRepresentativeStages() = f2("character-representative", false, rich = true, wrapped = true, advanced = true, characters = true)

    /** TEXT PERF 11: one 30-line layer against 30 and 60 one-line layers (same text, same glyph count per line). */
    @Test fun multiLayerStages() {
        val wanted = InstrumentationRegistry.getArguments().getString("perfCases")?.split(',')?.toSet()
        for (kind in TextMultiLayerFixture.Kind.entries) for ((rows, separate) in listOf(30 to false, 30 to true, 60 to true)) {
            val name = "${if (separate) "layers$rows" else "single$rows"}-${kind.name.lowercase()}"
            if (wanted != null && name !in wanted) continue
            val project = TextMultiLayerFixture.project(rows, separate, kind)
            val sized = kind == TextMultiLayerFixture.Kind.SIZE
            profile(name, project, tag = "FluxxPerf11", prefix = "perf11",
                span = if (sized) (TextMultiLayerFixture.SIZE_SPAN_US / 16_667L).toInt() else 600,
                staticUs = if (sized) TextMultiLayerFixture.SIZE_SPAN_US / 2 else 5_000_000L)
        }
    }

    /**
     * TEXT PERF 11 Part 4: the first playback after a project opens. A fresh renderer draws the settled paused frame
     * at 0 (as the editor does on open), stays idle for 2 s (the user reaching for Play), then renders 90 consecutive
     * playback frames. Reports each of the first frames, so a first-play hitch shows up instead of averaging away.
     */
    @Test fun firstPlayStages() {
        val wanted = InstrumentationRegistry.getArguments().getString("perfCases")?.split(',')?.toSet()
        val out = directory()
        for (kind in listOf(TextMultiLayerFixture.Kind.ANIMATED, TextMultiLayerFixture.Kind.SIZE)) for ((rows, separate) in listOf(30 to false, 30 to true)) {
            val name = "${if (separate) "layers$rows" else "single$rows"}-${kind.name.lowercase()}"
            if (wanted != null && name !in wanted) continue
            val project = TextMultiLayerFixture.project(rows, separate, kind)
            val host = instrumentation.startActivitySync(Intent(context, TextProofActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TextProofActivity
            val worker = Executors.newSingleThreadExecutor()
            try {
                val surface = host.awaitSurface()
                worker.submit {
                    CompositionRenderer(context, MediaRepository(context), surface, 1080, 1920).use { renderer ->
                        val opened = System.nanoTime()
                        renderer.renderPreview(project, 0, PreviewResolution.FULL)
                        val openMs = (System.nanoTime() - opened) / 1e6
                        Thread.sleep(2_000)
                        val frames = LongArray(90)
                        for (frame in frames.indices) {
                            val began = System.nanoTime()
                            renderer.renderPreview(project, frame * 16_667L, PreviewResolution.FULL)
                            frames[frame] = System.nanoTime() - began
                        }
                        val steady = frames.copyOfRange(30, 90)
                        val report = JSONObject().put("case", name).put("openSettledFrameMs", openMs)
                            .put("firstFramesMs", org.json.JSONArray(frames.take(15).map { it / 1e6 }))
                            .put("first30", percentiles(frames.copyOfRange(0, 30))).put("steady30to90", percentiles(steady))
                        File(out, "perf11-firstplay-$name.json").writeText(report.toString(2))
                        android.util.Log.i("FluxxPerf11", "firstplay $name: open=${"%.1f".format(openMs)} first15=${frames.take(15).joinToString(",") { "%.1f".format(it / 1e6) }} " +
                            "first30 max=${"%.1f".format(frames.copyOfRange(0, 30).max() / 1e6)} steady p50=${p(steady, 50)} p95=${p(steady, 95)}")
                    }
                }.get(10, TimeUnit.MINUTES)
            } finally { worker.shutdownNow(); instrumentation.runOnMainSync { host.finish() } }
        }
    }

    private fun directory(): File {
        val label = requireNotNull(InstrumentationRegistry.getArguments().getString("textRunLabel"))
        require(label.matches(Regex("[A-Za-z0-9_-]+")))
        return File(context.getExternalFilesDir(null), "text/$label").also { it.mkdirs() }
    }
    private fun f2(name: String, maximum: Boolean, warm: Int = 120, frames: Int = 300, rich: Boolean = false,
                   wrapped: Boolean = false, advanced: Boolean = false, characters: Boolean = false) =
        profile(name, TextF2AcceptanceTest().fixture(maximum, rich, null, wrapped, advanced, characters), warm, frames)

    /** [span] frames of the timeline are visited (playback wraps, scrubbing seeks inside it); static stays at [staticUs]. */
    @Suppress("DEPRECATION")
    private fun profile(name: String, project: ProjectDocument, warm: Int = 120, frames: Int = 300, tag: String = "FluxxPerf10",
                        prefix: String = "perf10", span: Int = 600, staticUs: Long = 5_000_000L) {
        val out = directory()
        val modes = (InstrumentationRegistry.getArguments().getString("perfModes") ?: "static,scrub,playback").split(',')
        val host = instrumentation.startActivitySync(Intent(context, TextProofActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TextProofActivity
        val worker = Executors.newSingleThreadExecutor()
        try {
            val surface = host.awaitSurface()
            worker.submit {
                CompositionRenderer(context, MediaRepository(context), surface, 1080, 1920).use { renderer ->
                    val power = context.getSystemService(PowerManager::class.java)
                    val kotlin = LongArray(CompositionRenderer.STAGES); val native = LongArray(TextDebugBridge.STAGES)
                    Debug.startAllocCounting()
                    try {
                        for (mode in modes) {
                            val random = java.util.Random(0x5eedL)
                            fun time(frame: Int) = when (mode) {
                                "static" -> staticUs
                                "scrub" -> random.nextInt(span) * 16_667L
                                else -> (frame % span) * 16_667L
                            }
                            val coldStart = System.nanoTime()
                            renderer.renderPreview(project, time(0), PreviewResolution.FULL)
                            val coldMs = (System.nanoTime() - coldStart) / 1e6
                            repeat(warm - 1) { renderer.renderPreview(project, time(it + 1), PreviewResolution.FULL) }
                            renderer.frameStages(kotlin); TextDebugBridge.frameStages(renderer.session, native)
                            val samplesK = Array(kotlin.size) { LongArray(frames) }; val samplesN = Array(native.size) { LongArray(frames) }
                            val allocK = LongArray(frames); val allocN = LongArray(frames)
                            val thermalBefore = power.currentThermalStatus; val headroomBefore = power.getThermalHeadroom(0)
                            val statsBefore = JSONObject(com.fluxx.android.engine.RenderBridge.textStats(renderer.session))
                            val began = System.nanoTime()
                            for (frame in 0 until frames) {
                                val k0 = Debug.getThreadAllocCount(); val n0 = TextDebugBridge.allocationCount()
                                renderer.renderPreview(project, time(warm + frame), PreviewResolution.FULL)
                                allocN[frame] = TextDebugBridge.allocationCount() - n0; allocK[frame] = (Debug.getThreadAllocCount() - k0).toLong()
                                renderer.frameStages(kotlin); TextDebugBridge.frameStages(renderer.session, native)
                                for (s in kotlin.indices) samplesK[s][frame] = kotlin[s]
                                for (s in native.indices) samplesN[s][frame] = native[s]
                            }
                            val wall = (System.nanoTime() - began) / 1e6
                            val layers = samplesK[CompositionRenderer.STAGE_LAYERS].average()
                            val report = JSONObject().put("case", name).put("mode", mode).put("frames", frames).put("warmFrames", warm)
                                .put("wallMs", wall).put("firstFrameMs", coldMs).put("textLayersPerFrame", layers)
                                .put("thermalStatusBefore", thermalBefore).put("thermalStatusAfter", power.currentThermalStatus)
                                .put("thermalHeadroomBefore", headroomBefore.toDouble()).put("thermalHeadroomAfter", power.getThermalHeadroom(0).toDouble())
                                .put("kotlinAllocationsPerFrame", counts(allocK)).put("nativeAllocationsPerFrame", counts(allocN))
                            val kotlinJson = JSONObject(); val nativeJson = JSONObject(); val perLayer = JSONObject()
                            kotlinNames.forEachIndexed { i, n -> kotlinJson.put(n, if (n in kotlinCounts) counts(samplesK[i]) else percentiles(samplesK[i])) }
                            nativeNames.forEachIndexed { i, n -> nativeJson.put(n, if (n in nativeCounts) counts(samplesN[i]) else percentiles(samplesN[i])) }
                            if (layers > 0) {
                                // Mean per-layer cost (stage mean / layers drawn): what each extra layer adds to a frame.
                                fun per(values: LongArray) = values.average() / layers / 1e6
                                perLayer.put("evaluateMs", per(samplesK[CompositionRenderer.STAGE_EVALUATE]))
                                    .put("textLayerJniMs", per(samplesK[CompositionRenderer.STAGE_MESH]))
                                    .put("nativeMeshMs", per(samplesN[13])).put("batchSubmitWaitMs", per(samplesN[2]))
                                    .put("batchRecordMs", per(samplesN[1])).put("workFenceWaitMs", per(samplesN[0]))
                                    .put("tiersMs", per(samplesK[CompositionRenderer.STAGE_TIERS]))
                                    // Frame minus tiers, prepareText, textLayer JNI and finish: the per-layer Kotlin loop
                                    // (document/transform/property evaluation, matrices) plus begin().
                                    .put("otherKotlinMs", (samplesK[0].average() - samplesK[1].average() - samplesK[3].average() -
                                        samplesK[4].average() - samplesK[5].average()) / layers / 1e6)
                                    .put("batchesPerLayer", samplesN[10].average() / layers)
                            }
                            report.put("kotlinStagesMs", kotlinJson).put("nativeStagesMs", nativeJson).put("perLayerMean", perLayer)
                            val statsAfter = JSONObject(com.fluxx.android.engine.RenderBridge.textStats(renderer.session))
                            val statsDelta = JSONObject()
                            for (key in listOf("rasterizations", "shapingCpuNs", "rasterCpuNs", "uploads", "uploadBytes", "generation", "draws",
                                    "tierEvictions", "tierDegrades", "arrayRecreations", "tierExternalRequested", "atlasUploadCpuNs", "lockWaitNs"))
                                if (statsAfter.has(key)) statsDelta.put(key, statsAfter.getLong(key) - statsBefore.optLong(key))
                            report.put("textStats", statsAfter).put("textStatsWindowDelta", statsDelta)
                            File(out, "$prefix-$name-$mode.json").writeText(report.toString(2))
                            android.util.Log.i(tag, "$name/$mode: frame p50=${p(samplesK[0], 50)} p95=${p(samplesK[0], 95)} ms; first=${"%.1f".format(coldMs)} " +
                                "layers=${"%.0f".format(layers)} batches=${"%.1f".format(samplesN[10].average())} " +
                                "evaluate p50=${p(samplesK[2], 50)} tiers p50=${p(samplesK[1], 50)} prepare p50=${p(samplesK[3], 50)} mesh p50=${p(samplesK[4], 50)} " +
                                "finish p50=${p(samplesK[5], 50)} batchWait p50=${p(samplesN[2], 50)} p95=${p(samplesN[2], 95)} " +
                                "gpuBatch p50=${p(samplesN[8], 50)} gpuPresent p50=${p(samplesN[9], 50)} collectPack p95=${p(samplesN[14], 95)} " +
                                "collects=${samplesN[15].sum()} upload p95=${p(samplesN[16], 95)} allocK=${allocK.sum()} allocN=${allocN.sum()} " +
                                "thermal=$thermalBefore->${power.currentThermalStatus}")
                        }
                    } finally { Debug.stopAllocCounting() }
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
    private fun counts(values: LongArray): JSONObject {
        val sorted = values.copyOf().also { it.sort() }
        return JSONObject().put("mean", sorted.average()).put("p50", sorted[(sorted.size - 1) / 2]).put("max", sorted.last())
            .put("total", sorted.sum()).put("nonZeroFrames", sorted.count { it != 0L })
    }
}
