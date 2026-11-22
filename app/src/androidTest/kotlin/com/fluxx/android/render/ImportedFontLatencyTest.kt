package com.fluxx.android.render

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.fonts.FontLibraryRepository
import com.fluxx.android.fonts.FontWorkerClient
import com.fluxx.android.fonts.ImportedTextPreparation
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * TEXT PERF 11 Part 4: where the wait for an imported font goes, against a bundled font. For a static face (the bundled
 * JetBrains Mono file imported as a user font) and a variable face (Source Serif 4), measures: library resolve (file
 * hash), the worker request (bind→connected, send→reply, in-worker shaping/raster/encode), the editor's apply path
 * (TextMetricsRepository.measure, what the canvas waits for after a tap in the Fonts page), and the first rendered
 * frame (base tier, then the 300 px sharp tier fetched from the worker). Cold = the first request in this process;
 * expired = after the worker's idle expiry. Fonts the library did not already hold are deleted again afterwards.
 */
@RunWith(AndroidJUnit4::class)
class ImportedFontLatencyTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun ms(ns: Long) = ns / 1e6

    @Test fun importedFontLatency() {
        val label = requireNotNull(InstrumentationRegistry.getArguments().getString("textRunLabel"))
        require(label.matches(Regex("[A-Za-z0-9_-]+")))
        val out = File(context.getExternalFilesDir(null), "text/$label").also { it.mkdirs() }
        val idleMs = (InstrumentationRegistry.getArguments().getString("expiryWaitMs") ?: "35000").toLong()
        val library = FontLibraryRepository.getInstance(context)
        val added = ArrayList<String>()
        val report = JSONObject()
        val worker = Executors.newSingleThreadExecutor()
        try {
            runBlocking(Dispatchers.IO) { library.load() }
            fun import(file: File): Pair<FontLibraryRepository, com.fluxx.android.fonts.FontLibraryEntry> = runBlocking(Dispatchers.IO) {
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
                val existing = library.state.value.find { it.sha256 == digest }
                library to (existing ?: library.import(android.net.Uri.fromFile(file)).also { added.add(it.sha256) })
            }
            val cases = listOf(
                "static" to copy("jetbrains-static", context.assets.open("fonts/JetBrainsMono-Regular.ttf")),
                "variable" to copy("sourceserif-variable", instrumentation.context.assets.open("SourceSerif4Variable-Roman.otf")))
            // Bundled reference: the in-process apply path for a bundled face.
            val bundled = JSONObject()
            run {
                val start = System.nanoTime()
                runBlocking { TextMetricsRepository.get(context).measure("fluxx.mono", TextSourceDocument("Bundled apply AV gy 019"), true) }
                bundled.put("applyMeasureMs", ms(System.nanoTime() - start))
            }
            report.put("bundled", bundled)
            for ((name, file) in cases) {
                val result = JSONObject()
                val importStart = System.nanoTime()
                val (_, entry) = import(file)
                result.put("importOrLookupMs", ms(System.nanoTime() - importStart))
                val reference = entry.faces.first().reference(entry.sha256)
                val resolveStart = System.nanoTime(); val input = requireNotNull(library.resolve(reference)); result.put("resolveHashMs", ms(System.nanoTime() - resolveStart))
                result.put("fontBytes", input.file.length())
                fun document(text: String) = TextSourceDocument(text).format(0, 0) { it.copy(font = reference) }
                fun prepare(tag: String, text: String) = worker.submit<JSONObject> {
                    val start = System.nanoTime()
                    ImportedTextPreparation.prepare(context, "fluxx.sans", document(text), true)
                    val total = System.nanoTime() - start
                    val t = FontWorkerClient.lastTimings
                    JSONObject().put("totalMs", ms(total)).put("bindToConnectedMs", ms(t[1])).put("sendToReplyMs", ms(t[2]))
                        .put("workerShapingMs", ms(t[3])).put("workerRasterMs", ms(t[4])).put("workerEncodeMs", ms(t[5]))
                }.get(2, TimeUnit.MINUTES).also { result.put(tag, it) }
                prepare("prepareCold", "$name cold AV office gy 0123456789")
                prepare("prepareWarm", "$name warm AV office gy 0123456789 xyz")
                val cachedStart = System.nanoTime()
                worker.submit { ImportedTextPreparation.prepare(context, "fluxx.sans", document("$name warm AV office gy 0123456789 xyz"), true) }.get()
                result.put("preparePacketCacheHitMs", ms(System.nanoTime() - cachedStart))
                // Editor apply: the Fonts page applies the face; the canvas waits for metrics of the layer text.
                val applyStart = System.nanoTime()
                runBlocking { TextMetricsRepository.get(context).measure("fluxx.sans", document("$name apply AV office gy 0123456789\nsecond line"), true) }
                result.put("applyMeasureMs", ms(System.nanoTime() - applyStart))
                // First frames with the face: base tier, then the sharp tier from the worker (300 px).
                val host = instrumentation.startActivitySync(Intent(context, TextProofActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TextProofActivity
                try {
                    val surface = host.awaitSurface()
                    worker.submit {
                        CompositionRenderer(context, MediaRepository(context), surface, 1080, 1920).use { renderer ->
                            for ((tag, size) in listOf("frame40px" to 40f, "frame300px" to 300f)) {
                                val text = TextProperties(fontId = "fluxx.sans", source = AnimatableString(staticValue = "$name AV gy 019",
                                    document = document("$name AV gy 019")), size = AnimatableProperty1D(staticValue = size))
                                val project = ProjectDocument(Composition(width = 1080, height = 1920, durationUs = 1_000_000,
                                    layers = FrozenList.of(listOf(CompositionLayer(1, LayerType.TEXT, timing = ClipTiming(durationUs = 1_000_000), text = text)))))
                                val start = System.nanoTime()
                                renderer.renderPreview(project, 0, PreviewResolution.FULL) // Waits (≤1.5 s) for sharp tiers, then redraws.
                                result.put(tag + "SettledMs", ms(System.nanoTime() - start))
                                result.put(tag + "TierPending", com.fluxx.android.engine.RenderBridge.awaitTextTiers(renderer.session, 0))
                            }
                        }
                    }.get(2, TimeUnit.MINUTES)
                } finally { instrumentation.runOnMainSync { host.finish() } }
                report.put(name, result)
            }
            // After the worker's idle expiry: what the next apply costs when the user comes back to the Fonts page later.
            SystemClock.sleep(idleMs)
            for ((name, _) in cases) {
                val entry = library.state.value.first { e -> e.faces.isNotEmpty() && (report.getJSONObject(name).getLong("fontBytes") == e.bytes) }
                val reference = entry.faces.first().reference(entry.sha256)
                val start = System.nanoTime()
                worker.submit { ImportedTextPreparation.prepare(context, "fluxx.sans",
                    TextSourceDocument("$name after idle ${System.nanoTime()}").format(0, 0) { it.copy(font = reference) }, true) }.get(2, TimeUnit.MINUTES)
                val t = FontWorkerClient.lastTimings
                report.getJSONObject(name).put("afterIdle${idleMs / 1000}s", JSONObject().put("totalMs", ms(System.nanoTime() - start))
                    .put("bindToConnectedMs", ms(t[1])).put("sendToReplyMs", ms(t[2])))
            }
            File(out, "perf11-imported-font-latency.json").writeText(report.toString(2))
            android.util.Log.i("FluxxPerf11", "imported font latency: ${report.toString().replace('\n', ' ')}")
        } finally {
            worker.shutdownNow()
            runBlocking(Dispatchers.IO) { for (hash in added) runCatching { library.delete(hash) } }
        }
    }
    private fun copy(name: String, input: java.io.InputStream): File =
        File(context.cacheDir, "perf11-$name.font").also { file -> input.use { stream -> file.outputStream().use { stream.copyTo(it) } } }
}
