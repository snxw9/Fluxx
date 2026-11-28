package com.fluxx.android.ui

import android.content.ContentUris
import android.os.Bundle
import android.provider.MediaStore
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.engine.NativeStage
import com.fluxx.android.engine.RenderBridge
import com.fluxx.android.export.CompositionExporter
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import com.fluxx.android.render.CompositionRenderer
import com.fluxx.android.render.PreviewResolution
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * MP2.2 B3: where the render path's frame time goes, per layer count. Measurement only: nothing here changes the app.
 *
 * Setups (composition 1920x1080 @ 30 fps, the typical H.264 file read in place through its MediaStore content URI):
 * - distinct decoders: N video layers of one source at in-points several seconds apart (one decoder per layer, up to the
 *   preview cap and memory budget, which stay); shared decoder: N layers at the SAME source time (one decoder), which
 *   isolates the compositing cost. N = 1, 2, 4, 6, 8. Preview (one long-lived surface renderer, as the app keeps one;
 *   frames rendered back to back with playing = true) and export (the real exporter, its own renderer per export).
 * - a cut sequence (8 one-second clips back to back, paced at 30 fps, warm-ups on) for per-cut costs;
 * - the VP9 worst case (Calc - Is There Someone Else), preview, distinct, N = 1, 2, 4;
 * - the flush upper bound: the test-only switch skips only the flush before each video frameAt (distinct, N <= 4: no
 *   decoder advances twice in a batch), alternated off / on / off / on within the run, with a byte-identity check.
 * Every per-frame sample is buffered and reported after its case (no IPC while measuring).
 */
class Mp22RenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = compose.activity
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun report(line: String) {
        android.util.Log.i("MP22", line)
        instrumentation.sendStatus(0, Bundle().apply { putString("MP22", line) })
    }
    private fun raw(line: String) { instrumentation.sendStatus(0, Bundle().apply { putString("MP22RAW", line) }) }

    private data class Src(val uri: String, val name: String, val info: MediaRepository.VideoInfo)

    /** A benchmark file by DISPLAY_NAME (LIKE [pattern]) through MediaStore; its content URI (read in place, never copied). */
    private fun source(repo: MediaRepository, pattern: String): Src? {
        val log = StringBuilder()
        runCatching {
            context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf(pattern), null)?.use { c ->
                log.append("rows ").append(c.count).append("; ")
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)).toString()
                    val info = runCatching { repo.video(uri) }.getOrNull() ?: continue
                    return Src(uri, c.getString(1), info)
                }
            }
        }.onFailure { log.append("query failed ").append(it.javaClass.simpleName) }
        report("source '$pattern' not found: $log")
        return null
    }

    // ---- scenes ------------------------------------------------------------------------------------------------------
    private val fps = 30
    private val rate = FrameRate(fps, 1)
    private fun tile(id: Long, s: Src, n: Int, k: Int, timing: ClipTiming): CompositionLayer {
        val cols = kotlin.math.ceil(kotlin.math.sqrt(n.toDouble())).toInt(); val rows = (n + cols - 1) / cols
        val x = -1f + (2f * (k % cols) + 1f) / cols; val y = -1f + (2f * (k / cols) + 1f) / rows
        val scale = 1f / maxOf(cols, rows)
        return CompositionLayer(id, LayerType.VIDEO, name = "mp22-$id", zOrder = 10 + k, timing = timing,
            transform = Transform(positionX = x, positionY = y, scaleX = scale, scaleY = scale),
            asset = AssetReference("mp22-$id", s.uri, access = MediaAccess.AVAILABLE, durationUs = s.info.durationUs))
    }
    private fun project(durUs: Long, layers: List<CompositionLayer>) = Aud2Bench.project(1920, 1080, durUs, fps, layers)
    /** N layers over [durUs]: distinct (in-points [spacing] apart, from 2 s) or shared (all at 10 s, or the middle). */
    private fun scene(s: Src, n: Int, distinct: Boolean, durUs: Long): ProjectDocument {
        val srcDur = s.info.durationUs ?: 60_000_000L
        val spacing = minOf(7_000_000L, (srcDur - durUs - 3_000_000L) / 8).coerceAtLeast(1_000_000L)
        val shared = minOf(10_000_000L, (srcDur - durUs) / 2)
        return project(durUs, (0 until n).map { k -> tile(1L + k, s, n, k, ClipTiming(0, if (distinct) 2_000_000L + k * spacing else shared, durUs)) })
    }

    // ---- samples -----------------------------------------------------------------------------------------------------
    /** One frame: ms metrics then counts (see [MS] and [COUNTS]). */
    private class Row(val ms: DoubleArray, val counts: LongArray)
    private val MS = listOf("total", "decode", "import", "pipeline", "layerCall", "record", "submit", "fence", "gpu", "flushes", "finish", "present", "gpuPresent", "readback", "yuv", "encodeWait")
    private val COUNTS = listOf("flushes", "videoFlushes", "submits", "imports", "sameBuffer", "videoDrawn", "rasterDraws", "pipelines", "conversions",
        "slotEvictions", "transitions", "idSamples", "hit4", "hit8", "hit16", "hit32", "hitAll", "distinctIds", "decodersOpen", "withoutDecoder")
    private val k = LongArray(CompositionRenderer.STAGES); private val p = LongArray(CompositionRenderer.PROBE_COUNT); private val n = LongArray(NativeStage.COUNT)
    private fun drain(r: CompositionRenderer) { r.frameStages(k); r.probeCounters(p); RenderBridge.frameStages(r.session, n) }
    private fun sample(r: CompositionRenderer, totalNs: Long?, export: LongArray? = null): Row {
        drain(r)
        val ms = { ns: Long -> ns / 1e6 }
        val present = n[NativeStage.PRESENT_FENCE_WAIT] + n[NativeStage.ACQUIRE] + n[NativeStage.PRESENT_RECORD] + n[NativeStage.PRESENT_SUBMIT]
        val m = doubleArrayOf(ms(totalNs ?: k[CompositionRenderer.STAGE_FRAME]), ms(p[CompositionRenderer.PROBE_DECODE_NS]), ms(n[NativeStage.IMPORT_NS]),
            ms(n[NativeStage.PIPELINE_NS]), ms(p[CompositionRenderer.PROBE_LAYER_NS]), ms(n[NativeStage.BATCH_RECORD]), ms(n[NativeStage.QUEUE_SUBMIT_NS]),
            ms(n[NativeStage.FENCE_WAIT_NS] + n[NativeStage.WORK_FENCE_WAIT]), ms(n[NativeStage.GPU_BATCH]), ms(p[CompositionRenderer.PROBE_FLUSH_NS]),
            ms(p[CompositionRenderer.PROBE_FINISH_NS]), ms(present), ms(n[NativeStage.GPU_PRESENT]), ms(n[NativeStage.READBACK_NS]), ms(n[NativeStage.YUV_NS]),
            ms((export?.get(2) ?: 0L) + (export?.get(3) ?: 0L)))
        val c = longArrayOf(k[CompositionRenderer.STAGE_FLUSHES], p[CompositionRenderer.PROBE_VIDEO_FLUSHES], n[NativeStage.BATCHES], n[NativeStage.IMPORTS],
            n[NativeStage.SAME_BUFFER], p[CompositionRenderer.PROBE_VIDEO_DRAWN], n[NativeStage.RASTER_DRAWS], n[NativeStage.PIPELINE_CREATES],
            n[NativeStage.CONVERSIONS], n[NativeStage.SLOT_EVICTIONS], n[NativeStage.TRANSITIONS], n[NativeStage.ID_SAMPLES], n[NativeStage.HIT4],
            n[NativeStage.HIT8], n[NativeStage.HIT16], n[NativeStage.HIT32], n[NativeStage.HIT_ALL], n[NativeStage.IDS_DISTINCT],
            r.decodersOpen.toLong(), r.layersWithoutDecoder.toLong())
        return Row(m, c)
    }
    private fun pct(v: List<Double>, q: Double): Double { if (v.isEmpty()) return Double.NaN; val s = v.sorted(); return s[((s.size - 1) * q).toInt().coerceIn(0, s.size - 1)] }
    private fun f1(d: Double) = "%.2f".format(d)
    private fun summary(case: String, rows: List<Row>, export: Boolean) {
        if (rows.isEmpty()) { report("$case: no frames"); return }
        val used = if (export) MS else MS.filter { it !in setOf("readback", "yuv", "encodeWait") }
        report("$case: ${rows.size} frames; p50/p95 ms " + used.joinToString(" ") { name -> val i = MS.indexOf(name); val v = rows.map { it.ms[i] }; "$name=${f1(pct(v, .5))}/${f1(pct(v, .95))}" })
        val mean = { name: String -> val i = COUNTS.indexOf(name); rows.sumOf { it.counts[i] }.toDouble() / rows.size }
        val sum = { name: String -> val i = COUNTS.indexOf(name); rows.sumOf { it.counts[i] } }
        report("$case: per frame (mean) " + COUNTS.filter { it !in setOf("idSamples", "hit4", "hit8", "hit16", "hit32", "hitAll", "distinctIds") }.joinToString(" ") { "$it=${f1(mean(it))}" })
        val samples = sum("idSamples")
        val distinct = rows.last().counts[COUNTS.indexOf("distinctIds")]
        if (samples > 0) report("$case: per-buffer import cache: $samples imports, $distinct distinct AHardwareBuffer ids; hit rate LRU4 ${pctOf(sum("hit4"), samples)} LRU8 ${pctOf(sum("hit8"), samples)} LRU16 ${pctOf(sum("hit16"), samples)} LRU32 ${pctOf(sum("hit32"), samples)} unbounded ${pctOf(sum("hitAll"), samples)} (needs $distinct entries)")
        else report("$case: per-buffer import cache: no id samples (AHardwareBuffer_getId unavailable or no imports)")
        for (chunk in rows.indices.chunked(30)) raw("$case frames ${chunk.first()}..${chunk.last()} total/decode/import/record/fence/gpu/present/flushes: " +
            chunk.joinToString(" ") { i -> val r = rows[i]; listOf(0, 1, 2, 5, 7, 8, 11).joinToString("/") { "%.1f".format(r.ms[it]) } + "/" + r.counts[0] })
    }
    private fun pctOf(a: Long, b: Long) = "%.1f%%".format(100.0 * a / b)
    private fun temp(): String { val (t, plugged) = Mp1Bench.battery(context); return "%.1fC%s".format(t, if (plugged) "" else " ON BATTERY") }
    private fun cool() { Mp1Bench.coolEnough(context, 42.0, 180_000) { report("thermal: $it") } }

    // ---- preview -----------------------------------------------------------------------------------------------------
    private fun preview(r: CompositionRenderer, case: String, project: ProjectDocument, warm: Int = 30, frames: Int = 150, from: Int = 0, paced: Boolean = false,
        settle: Boolean = true, keep: Boolean = true): List<Row> {
        if (settle) { Thread.sleep(3_500); r.renderPreview(project, rate.frameTimeUs(from.toLong()), PreviewResolution.FULL) } // lets the 3 s release drop the last case's decoders
        r.playing = true
        for (i in from until from + warm) r.renderPreview(project, rate.frameTimeUs(i.toLong()), PreviewResolution.FULL)
        drain(r); RenderBridge.probe(r.session, true)
        val rows = ArrayList<Row>(frames)
        val t0 = System.nanoTime()
        for (i in from + warm until from + warm + frames) {
            if (paced) { val due = t0 + (i - from - warm) * 1_000_000_000L / fps; val wait = due - System.nanoTime(); if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt()) }
            r.renderPreview(project, rate.frameTimeUs(i.toLong()), PreviewResolution.FULL)
            rows += sample(r, null)
        }
        RenderBridge.probe(r.session, false)
        if (keep) summary(case, rows, false)
        return rows
    }

    // ---- export ------------------------------------------------------------------------------------------------------
    private fun export(case: String, project: ProjectDocument): List<Row> {
        val rows = ArrayList<Row>(); var first: Row? = null; var session = 0L
        val times = LongArray(16)
        CompositionExporter.testFrame = { r, t ->
            if (session == 0L) { session = r.session; first = sample(r, t[0], t); RenderBridge.probe(r.session, true) }
            else rows += sample(r, t[0], t)
            RenderBridge.pipelineTimes(r.session, times)
        }
        val out = File(context.cacheDir, "mp22-export.mp4")
        val t0 = System.nanoTime()
        try { kotlinx.coroutines.runBlocking { CompositionExporter(context).export(project, out) { } } }
        finally { CompositionExporter.testFrame = null; out.delete() }
        Thread.sleep(2_000) // lets the encoder session release fully before the next (the A16's encoder hung once)
        val wall = (System.nanoTime() - t0) / 1e6
        first?.let { f -> report("$case: first frame (fresh export renderer) total ${f1(f.ms[0])} ms decode ${f1(f.ms[1])} import ${f1(f.ms[2])} pipeline ${f1(f.ms[3])} ms, pipelines ${f.counts[7]}; export wall ${"%.0f".format(wall)} ms; pipeline creation ns (this session, first 16) ${times.filter { it >= 0 }.joinToString(",") { "%.2f".format(it / 1e6) }} ms") }
        summary("$case (frames 2..)", rows, true)
        return rows
    }

    private fun readback(r: CompositionRenderer, w: Int, h: Int): String {
        val y = ByteBuffer.allocateDirect(w * h); val u = ByteBuffer.allocateDirect(w * h / 4); val v = ByteBuffer.allocateDirect(w * h / 4)
        check(RenderBridge.copyYuv(r.session, y, u, v, w, w / 2, w / 2, 1, 1)) { "readback failed" }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        for (b in listOf(y, u, v)) { b.rewind(); val a = ByteArray(b.remaining()); b.get(a); md.update(a) }
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun surface(): Pair<android.view.Surface, Pair<Int, Int>> {
        val latch = CountDownLatch(1); var s: android.view.Surface? = null; var size = 0 to 0
        compose.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx -> SurfaceView(ctx).apply { holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {}
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { if (s == null) { s = holder.surface; size = width to height; latch.countDown() } }
                override fun surfaceDestroyed(holder: SurfaceHolder) {}
            }) } })
        }
        check(latch.await(10, TimeUnit.SECONDS)) { "no surface" }
        return s!! to size
    }


    /** Alternated blocks: flush-always (the behaviour before MP2.2 B1) against the narrowed flush. */
    private fun alternate(case: String, run: (Int, Boolean) -> List<Row>, export: Boolean) {
        val old = ArrayList<Row>(); val narrow = ArrayList<Row>(); val blocks = ArrayList<String>()
        for ((b, always) in listOf(true, false, true, false).withIndex()) {
            CompositionRenderer.testFlushAlways = always
            val rows = run(b, always)
            (if (always) old else narrow).addAll(rows); blocks += (if (always) "always " else "narrowed ") + f1(pct(rows.map { it.ms[0] }, .5))
        }
        CompositionRenderer.testFlushAlways = false
        summary("$case flush-always (before B1)", old, export)
        summary("$case narrowed (B1)", narrow, export)
        val a = pct(old.map { it.ms[0] }, .5); val b = pct(narrow.map { it.ms[0] }, .5)
        report("$case: total p50 ${f1(a)} -> ${f1(b)} ms (${f1(a - b)} ms, ${"%.1f".format(100 * (a - b) / a)}%), p95 ${f1(pct(old.map { it.ms[0] }, .95))} -> ${f1(pct(narrow.map { it.ms[0] }, .95))}; flushes/frame ${f1(old.map { it.counts[0].toDouble() }.average())} -> ${f1(narrow.map { it.counts[0].toDouble() }.average())}; blocks p50 ${blocks.joinToString(" ")}")
    }

    @Test fun measure() {
        val state = TestDeviceState.record(javaClass.simpleName)
        report("device: $state; build ${if (com.fluxx.android.BuildConfig.DEBUG) "DEBUG (debuggable)" else "benchmark (not debuggable)"}; API ${android.os.Build.VERSION.SDK_INT}")
        // -e mp22part preview | export | cold (each in its own process); cold also takes -e mp22cache ignore | use.
        val args = InstrumentationRegistry.getArguments()
        val part = args.getString("mp22part") ?: "preview"
        val ignoreCache = args.getString("mp22cache") == "ignore"
        report("part: $part${if (part == "cold") " (saved pipeline cache ${if (ignoreCache) "IGNORED" else "used"})" else ""}")
        // B3 cold start: set before the first session of this process.
        if (part == "cold") RenderBridge.testIgnoreSavedPipelineCache = ignoreCache
        val cacheFile = File(context.cacheDir, RenderBridge.PIPELINE_CACHE_FILE)
        report("pipeline cache file before: ${if (cacheFile.exists()) "${cacheFile.length()} bytes" else "none"}")
        val repo = MediaRepository(context)
        val typical = source(repo, "%${Aud2Bench.TYPICAL_ID}%")
        assumeTrue("MP22: the typical H.264 file was not found", typical != null)
        val s = typical!!
        report("typical: '${s.name.take(40)}' ${s.info.mime} ${s.info.width}x${s.info.height} ${s.info.frameRate} fps ${(s.info.durationUs ?: 0) / 1e6} s")
        val worker = Executors.newSingleThreadExecutor { Thread(it, "mp22-render") }
        CompositionRenderer.testProbe = true
        try {
            when (part) {
                "cold" -> {
                    val (surf, size) = surface()
                    worker.submit {
                        val info = LongArray(5); val times = LongArray(16)
                        val t0 = System.nanoTime()
                        val r = CompositionRenderer(context, repo, surf, size.first, size.second)
                        val created = System.nanoTime() - t0
                        RenderBridge.pipelineCacheInfo(r.session, info)
                        val project = scene(s, 1, true, 4_000_000L)
                        r.playing = true
                        val t1 = System.nanoTime(); r.renderPreview(project, 0, PreviewResolution.FULL); val first = System.nanoTime() - t1
                        val firstRow = sample(r, first)
                        RenderBridge.pipelineTimes(r.session, times)
                        report("cold preview session: created in ${f1(created / 1e6)} ms (native init ${f1(info[4] / 1e6)} ms, warm-up ${f1(info[3] / 1e6)} ms); cache load state ${info[1]} (0 no file, 1 used, 2 bad header, 3 too big, 4 ignored, 5 rejected), loaded ${info[0]} bytes; first frame ${f1(first / 1e6)} ms (decode ${f1(firstRow.ms[1])}, import ${f1(firstRow.ms[2])}, of which pipeline ${f1(firstRow.ms[3])}); pipeline creation times ms ${times.filter { it >= 0 }.joinToString(",") { "%.2f".format(it / 1e6) }} (the first is the RGBA warm-up)")
                        val later = (1 until 5).map { i -> val ti = System.nanoTime(); r.renderPreview(project, rate.frameTimeUs(i.toLong()), PreviewResolution.FULL); (System.nanoTime() - ti) / 1e6 }
                        report("cold preview: frames 2-5 ms ${later.joinToString(",") { f1(it) }}")
                        r.close()
                        report("pipeline cache file after the preview session closed: ${if (cacheFile.exists()) "${cacheFile.length()} bytes" else "none"}")
                        // An export renderer (offscreen) in the same process: its own device, the file as saved now.
                        val e0 = System.nanoTime()
                        CompositionRenderer(context, repo).use { er ->
                            val ec = System.nanoTime() - e0
                            RenderBridge.pipelineCacheInfo(er.session, info)
                            val e1 = System.nanoTime(); er.render(project, 0); val ef = System.nanoTime() - e1
                            RenderBridge.pipelineTimes(er.session, times)
                            report("cold export renderer (same process, after the preview session): created ${f1(ec / 1e6)} ms (warm-up ${f1(info[3] / 1e6)} ms), cache load state ${info[1]}, loaded ${info[0]} bytes; first frame ${f1(ef / 1e6)} ms; pipeline creation times ms ${times.filter { it >= 0 }.joinToString(",") { "%.2f".format(it / 1e6) }}")
                        }
                        report("pipeline cache file at the end: ${if (cacheFile.exists()) "${cacheFile.length()} bytes" else "none"}")
                    }.get(5, TimeUnit.MINUTES)
                }
                "preview" -> {
                    val (surf, size) = surface()
                    worker.submit {
                        CompositionRenderer(context, repo, surf, size.first, size.second).use { r ->
                            for ((count, distinct) in listOf(2 to true, 4 to true, 4 to false)) {
                                cool()
                                val pj = scene(s, count, distinct, 13_000_000L)
                                var from = 0
                                alternate("preview ${if (distinct) "distinct" else "shared (control)"} N=$count [${temp()}]", { b, _ ->
                                    val warm = if (b == 0) 30 else 10
                                    preview(r, "block", pj, warm = warm, frames = 75, from = from, settle = b == 0, keep = false).also { from += warm + 75 }
                                }, false)
                            }
                        }
                    }.get(20, TimeUnit.MINUTES)
                }
                "export" -> {
                    // Export renderer (render + copyYuv as the exporter runs them, no encoder): distinct N=4, alternated.
                    cool()
                    val pj = scene(s, 4, true, 4_000_000L)
                    val y = ByteBuffer.allocateDirect(1920 * 1080); val u = ByteBuffer.allocateDirect(1920 * 1080 / 4); val v = ByteBuffer.allocateDirect(1920 * 1080 / 4)
                    CompositionRenderer(context, repo).use { er ->
                        for (i in 0 until 10) { er.render(pj, rate.frameTimeUs(i.toLong())); RenderBridge.copyYuv(er.session, y, u, v, 1920, 960, 960, 1, 1) }
                        report("readback memory: ${RenderBridge.memoryInfo(er.session)}")
                        drain(er); RenderBridge.probe(er.session, true)
                        var frame = 10
                        alternate("export-renderer distinct N=4 [no encoder] [${temp()}]", { _, _ ->
                            (0 until 15).map {
                                val t0 = System.nanoTime(); er.render(pj, rate.frameTimeUs((frame++).toLong())); val t1 = System.nanoTime()
                                check(RenderBridge.copyYuv(er.session, y, u, v, 1920, 960, 960, 1, 1)) { "readback failed" }
                                sample(er, t1 - t0, longArrayOf(t1 - t0, System.nanoTime() - t1, 0, 0))
                            }
                        }, true)
                    }
                    // Real exports: readback and conversion (shared N=1, 4), and the 8-distinct control (15 frames).
                    for ((count, distinct, frames) in listOf(Triple(1, false, 30), Triple(4, false, 30), Triple(8, true, 15))) {
                        cool()
                        export("export ${if (distinct) "distinct" else "shared"} N=$count ($frames frames) [${temp()}]", scene(s, count, distinct, frames * 1_000_000L / fps))
                    }
                }
            }
        } finally {
            CompositionRenderer.testFlushAlways = false; CompositionRenderer.testProbe = false; CompositionExporter.testFrame = null
            RenderBridge.testIgnoreSavedPipelineCache = false
            worker.shutdownNow()
        }
    }
}
