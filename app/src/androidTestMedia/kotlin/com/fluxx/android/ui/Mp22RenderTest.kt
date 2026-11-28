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

    @Test fun measure() {
        val state = TestDeviceState.record(javaClass.simpleName)
        report("device: $state; build ${if (com.fluxx.android.BuildConfig.DEBUG) "DEBUG (debuggable)" else "benchmark (not debuggable)"}; API ${android.os.Build.VERSION.SDK_INT}")
        val repo = MediaRepository(context)
        val typical = source(repo, "%${Aud2Bench.TYPICAL_ID}%")
        assumeTrue("MP22: the typical H.264 file was not found", typical != null)
        val s = typical!!
        report("typical: '${s.name.take(40)}' ${s.info.mime} ${s.info.width}x${s.info.height} ${s.info.frameRate} fps ${(s.info.durationUs ?: 0) / 1e6} s")
        val vp9 = source(repo, "${Aud1Bench.PREFIX}%")
        val (surf, size) = surface()
        report("surface ${size.first}x${size.second}")
        val worker = Executors.newSingleThreadExecutor { Thread(it, "mp22-render") }
        // -e mp22part preview | export: one part per run (the A16 restarted once when the export part followed the
        // preview part in one run: its hardware encoder hung). Default: both.
        val part = InstrumentationRegistry.getArguments().getString("mp22part") ?: "all"
        report("part: $part")
        CompositionRenderer.testProbe = true
        try {
            if (part != "export") worker.submit {
                val wall0 = System.nanoTime()
                CompositionRenderer(context, repo, surf, size.first, size.second).use { r ->
                    // Pipeline cold start: the first frames of this process's first renderer.
                    val cold = scene(s, 1, true, 8_000_000L)
                    val coldRows = ArrayList<Row>()
                    RenderBridge.probe(r.session, true); r.playing = true
                    for (i in 0 until 5) { val t0 = System.nanoTime(); r.renderPreview(cold, rate.frameTimeUs(i.toLong()), PreviewResolution.FULL); coldRows += sample(r, System.nanoTime() - t0) }
                    val times = LongArray(16); RenderBridge.pipelineTimes(r.session, times)
                    report("cold start (fresh process, first preview renderer): first 5 frames total ms ${coldRows.joinToString(",") { f1(it.ms[0]) }}; pipeline ms ${coldRows.joinToString(",") { f1(it.ms[3]) }}; pipelines ${coldRows.sumOf { it.counts[7] }}; pipeline creation times ${times.filter { it >= 0 }.joinToString(",") { "%.2f".format(it / 1e6) }} ms")
                    report("temperature ${temp()}")
                    for (distinct in listOf(true, false)) for (count in listOf(1, 2, 4, 6, 8)) {
                        cool()
                        preview(r, "preview ${if (distinct) "distinct" else "shared"} N=$count [${temp()}]", scene(s, count, distinct, 8_000_000L))
                    }
                    // Flush upper bound: the per-video flush skipped (test-only switch), distinct, N <= 4, alternated.
                    for (count in listOf(1, 2, 4)) {
                        cool()
                        val pj = scene(s, count, true, 13_000_000L)
                        val blocks = ArrayList<Pair<Boolean, List<Row>>>()
                        var from = 0
                        for ((b, skip) in listOf(false, true, false, true).withIndex()) {
                            CompositionRenderer.testSkipVideoFlush = skip
                            blocks += skip to preview(r, "fub", pj, warm = if (b == 0) 30 else 10, frames = 75, from = from, settle = b == 0, keep = false)
                            from += (if (b == 0) 30 else 10) + 75
                        }
                        CompositionRenderer.testSkipVideoFlush = false
                        val off = blocks.filter { !it.first }.flatMap { it.second }; val on = blocks.filter { it.first }.flatMap { it.second }
                        summary("preview flush-bound N=$count per-video flush ON (as today)", off, false)
                        summary("preview flush-bound N=$count per-video flush SKIPPED", on, false)
                        val a = pct(off.map { it.ms[0] }, .5); val b = pct(on.map { it.ms[0] }, .5)
                        val a95 = pct(off.map { it.ms[0] }, .95); val b95 = pct(on.map { it.ms[0] }, .95)
                        report("preview flush-bound N=$count: total p50 ${f1(a)} -> ${f1(b)} ms (${f1(a - b)} ms, ${"%.1f".format(100 * (a - b) / a)}%), p95 ${f1(a95)} -> ${f1(b95)} ms; blocks p50 ${blocks.joinToString(" ") { (sk, rw) -> (if (sk) "skip " else "on ") + f1(pct(rw.map { it.ms[0] }, .5)) }}; [${temp()}]")
                    }
                    // Cut sequence: 8 one-second clips back to back (in-points 5 s apart), paced at 30 fps, warm-ups on.
                    cool()
                    val cuts = project(8_000_000L, (0 until 8).map { c -> CompositionLayer(101L + c, LayerType.VIDEO, name = "cut-$c", zOrder = 10,
                        timing = ClipTiming(c * 1_000_000L, 2_000_000L + c * 5_000_000L, 1_000_000L),
                        asset = AssetReference("mp22-cut-$c", s.uri, access = MediaAccess.AVAILABLE, durationUs = s.info.durationUs)) })
                    val warmBefore = r.warmCounts()?.copyOf()
                    val cutRows = preview(r, "preview cut sequence (all frames)", cuts, warm = 0, frames = 239, from = 0, paced = true)
                    val warmAfter = r.warmCounts()
                    val cutAt = (1 until 8).map { it * fps }.toSet()
                    summary("preview cut sequence: the 7 cut frames", cutRows.filterIndexed { i, _ -> i in cutAt }, false)
                    summary("preview cut sequence: the frame after each cut", cutRows.filterIndexed { i, _ -> i - 1 in cutAt }, false)
                    summary("preview cut sequence: other frames", cutRows.filterIndexed { i, _ -> i !in cutAt && i - 1 !in cutAt }, false)
                    report("preview cut sequence: warm counts (started, ready, used, released unused, failed, blocked budget, blocked cap, waits) ${warmBefore?.joinToString(",")} -> ${warmAfter?.joinToString(",")}")
                    // VP9 worst case: preview, distinct, N = 1, 2, 4.
                    if (vp9 != null) {
                        report("vp9: '${vp9.name.take(40)}' ${vp9.info.mime} ${vp9.info.width}x${vp9.info.height} ${(vp9.info.durationUs ?: 0) / 1e6} s")
                        for (count in listOf(1, 2, 4)) { cool(); preview(r, "preview VP9-worst distinct N=$count [${temp()}]", scene(vp9, count, true, 8_000_000L)) }
                    } else report("vp9 worst case SKIPPED: file not found")
                    RenderBridge.pipelineTimes(r.session, times)
                    report("preview renderer pipeline creation times over the run (first 16) ${times.filter { it >= 0 }.joinToString(",") { "%.2f".format(it / 1e6) }} ms")
                }
                report("preview part wall ${"%.0f".format((System.nanoTime() - wall0) / 1e9)} s")
            }.get(40, TimeUnit.MINUTES)
            if (part == "preview") return

            // Export: the real exporter (its own renderer, offscreen readback, encoder), 30 frames (fewer where slow).
            val wall1 = System.nanoTime()
            for (distinct in listOf(true, false)) for (count in listOf(1, 2, 4, 6, 8)) {
                cool()
                val frames = if (distinct && count >= 6) 15 else 30
                export("export ${if (distinct) "distinct" else "shared"} N=$count ($frames frames) [${temp()}]", scene(s, count, distinct, frames * 1_000_000L / fps))
            }
            // Flush upper bound in export: the export renderer (offscreen render + copyYuv, as the exporter runs them, but
            // without the encoder: fewer hardware encoder sessions after the A16's encoder hang), alternated blocks of 15
            // frames within one renderer, and byte identity on the export renderer's readback.
            for (count in listOf(1, 2, 4)) {
                cool()
                val pj = scene(s, count, true, 3_000_000L)
                val off = ArrayList<Row>(); val on = ArrayList<Row>()
                val y = ByteBuffer.allocateDirect(1920 * 1080); val u = ByteBuffer.allocateDirect(1920 * 1080 / 4); val v = ByteBuffer.allocateDirect(1920 * 1080 / 4)
                CompositionRenderer(context, repo).use { er ->
                    for (i in 0 until 10) { er.render(pj, rate.frameTimeUs(i.toLong())); RenderBridge.copyYuv(er.session, y, u, v, 1920, 960, 960, 1, 1) }
                    drain(er); RenderBridge.probe(er.session, true)
                    var frame = 10
                    for (skip in listOf(false, true, false, true)) {
                        CompositionRenderer.testSkipVideoFlush = skip
                        repeat(15) {
                            val t0 = System.nanoTime(); er.render(pj, rate.frameTimeUs((frame++).toLong())); val t1 = System.nanoTime()
                            check(RenderBridge.copyYuv(er.session, y, u, v, 1920, 960, 960, 1, 1)) { "readback failed" }
                            (if (skip) on else off).add(sample(er, t1 - t0, longArrayOf(t1 - t0, System.nanoTime() - t1, 0, 0)))
                        }
                    }
                }
                CompositionRenderer.testSkipVideoFlush = false
                summary("export-renderer flush-bound N=$count per-video flush ON (as today) [no encoder]", off, true)
                summary("export-renderer flush-bound N=$count per-video flush SKIPPED [no encoder]", on, true)
                val a = pct(off.map { it.ms[0] }, .5); val b = pct(on.map { it.ms[0] }, .5)
                report("export flush-bound N=$count: render p50 ${f1(a)} -> ${f1(b)} ms (${f1(a - b)} ms, ${"%.1f".format(100 * (a - b) / a)}%), p95 ${f1(pct(off.map { it.ms[0] }, .95))} -> ${f1(pct(on.map { it.ms[0] }, .95))}; flushes/frame ${f1(off.map { it.counts[0].toDouble() }.average())} -> ${f1(on.map { it.counts[0].toDouble() }.average())}")
                val w = 640; val h = 360
                val small = scene(s, count, true, 1_000_000L).let { it.copy(composition = it.composition.copy(width = w, height = h)) }
                var same = 0; var total = 0
                CompositionRenderer(context, repo).use { ra -> CompositionRenderer(context, repo).use { rb ->
                    for (i in 0 until 20) {
                        val t = rate.frameTimeUs(i.toLong())
                        CompositionRenderer.testSkipVideoFlush = false; ra.render(small, t); val da = readback(ra, w, h)
                        CompositionRenderer.testSkipVideoFlush = true; rb.render(small, t); val db = readback(rb, w, h)
                        CompositionRenderer.testSkipVideoFlush = false
                        total++; if (da == db) same++
                    }
                } }
                report("flush-bound identity N=$count: $same of $total frames byte-identical (640x360 export-renderer readback, flush on vs skipped)")
            }
            report("export part wall ${"%.0f".format((System.nanoTime() - wall1) / 1e9)} s; end temperature ${temp()}")
        } finally {
            CompositionRenderer.testSkipVideoFlush = false; CompositionRenderer.testProbe = false; CompositionExporter.testFrame = null
            worker.shutdownNow()
        }
    }
}
