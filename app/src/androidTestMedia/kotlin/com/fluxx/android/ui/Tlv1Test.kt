package com.fluxx.android.ui

import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.ProjectManager
import com.fluxx.android.editor.EditorAction
import com.fluxx.android.model.*
import com.fluxx.android.ui.timeline.TimelineProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TLV1: the timeline's horizontal axis, measured the same way before and after the virtualisation (one class, one
 * invocation). For a short project (30 clips, 60 s), Project 1 (read-only, 1078.7 s) and an in-memory 3-hour
 * composition: the layout nodes on screen and the WIDEST node, the timeline's and its clip rows' recompositions during
 * 5 s of playback follow and during a programmatic fling, timeline UI frame times and main-thread message time, and the
 * zoom actually reached when the highest zoom is asked for (px per frame, content width). Files are read in place.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class Tlv1Test : Aud2Base() {
    override val tag = "TLV1"
    private val main get() = InstrumentationRegistry.getInstrumentation()

    @Before fun probeOn() { TimelineProbe.enabled = true }
    @After fun probeOff() { TimelineProbe.enabled = false; TimelineProbe.control = null; TimelineProbe.coverage = null }

    // ---- layout nodes (reflection over the Compose owner's LayoutNode tree; read-only) -------------------------------
    private fun composeView(v: android.view.View): android.view.View? {
        if (v.javaClass.name.contains("AndroidComposeView")) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) composeView(v.getChildAt(i))?.let { return it }
        return null
    }
    /** (layout nodes, widest node px). */
    private fun nodes(): Pair<Int, Int> = runCatching {
        var count = 0; var widest = 0
        main.runOnMainSync {
            val view = composeView(compose.activity.window.decorView) ?: return@runOnMainSync
            val root = view.javaClass.methods.first { it.name == "getRoot" && it.parameterCount == 0 }.invoke(view)
            fun walk(n: Any) {
                count++
                val w = n.javaClass.methods.firstOrNull { it.name == "getWidth" && it.parameterCount == 0 }?.invoke(n) as? Int ?: 0
                if (w > widest) widest = w
                val children = n.javaClass.methods.firstOrNull { it.name.startsWith("getChildren") && it.parameterCount == 0 && List::class.java.isAssignableFrom(it.returnType) }?.invoke(n) as? List<*>
                children?.forEach { c -> if (c != null) walk(c) }
            }
            walk(root)
        }
        count to widest
    }.getOrDefault(-1 to -1)

    private fun mainThreadTime(block: () -> Unit): Pair<Double, Double> {
        var total = 0L; var worst = 0L; var began = 0L
        android.os.Looper.getMainLooper().setMessageLogging { line ->
            if (line.startsWith(">>>>>")) began = System.nanoTime()
            else if (line.startsWith("<<<<<") && began > 0) { val d = System.nanoTime() - began; total += d; if (d > worst) worst = d; began = 0 }
        }
        try { block() } finally { android.os.Looper.getMainLooper().setMessageLogging(null) }
        return total / 1e6 to worst / 1e6
    }

    /**
     * The Compose test rule drives the UI on a VIRTUAL frame clock: while the test thread sleeps no frame runs (nothing
     * recomposes, no fling advances, the window draws nothing). [pump] advances it one frame per 16 ms of real time, so the
     * timeline runs at a real cadence while it is measured (the window draws real frames for FrameMetrics).
     */
    private fun frame() { compose.mainClock.advanceTimeByFrame(); Thread.sleep(16) }
    private fun pump(ms: Long) { val end = System.nanoTime() + ms * 1_000_000; while (System.nanoTime() < end) frame() }
    private fun seek(us: Long, ms: Long) { seekTo(us, 0); pump(ms) }

    private fun fling(v: Float) {
        val ctl = TimelineProbe.control ?: error("no timeline probe")
        var job: kotlinx.coroutines.Job? = null
        main.runOnMainSync { job = ctl.fling(v) }
        val t0 = System.nanoTime()
        while (job?.isActive == true && System.nanoTime() - t0 < 30_000_000_000L) frame()
    }

    /** The test's window must be in front (another app on top draws no frames for it and nothing recomposes). */
    private fun front(label: String) {
        var focus = false; main.runOnMainSync { focus = compose.activity.hasWindowFocus() }
        if (!focus) report("$label: SKIPPED: the test window is not in front (another app is on top)")
        assumeTrue("TLV1 $label: the test window is not in front; skipping", focus)
    }

    private fun measure(label: String, project: ProjectDocument) {
        host(project)
        pump(2500)
        front(label)
        val dur = project.composition.resolvedDurationUs
        val (n0, w0) = nodes()
        report("$label: ${project.composition.layers.size} layers, %.1f s; default zoom %.1f dp/s; layout nodes $n0, widest node $w0 px; content width %.0f px"
            .format(dur / 1e6, TimelineProbe.control?.zoomDpPerSecond?.invoke() ?: -1f, TimelineProbe.control?.contentWidthPx?.invoke() ?: -1.0))
        // 5 s of playback follow from 0 (the first source's sound decoded first, so Play starts at once).
        project.composition.layers.filter { it.type == LayerType.VIDEO && it.timing.startUs == 0L }.mapNotNull { it.asset }.distinctBy { it.uri }
            .forEach { a -> warmPcm(a.uri!!, a.durationUs ?: 10_000_000, 90_000) }
        seek(0, 1500)
        TimelineProbe.reset()
        var ui = emptyList<Double>()
        val (mainMs, mainWorst) = mainThreadTime { ui = uiFrames(compose.activity.window) { main.runOnMainSync { c.play() }; pump(5000); main.runOnMainSync { c.pause() } } }
        report("$label FOLLOW 5 s: timeline recompositions ${TimelineProbe.viewCompositions.get()}, clip-row recompositions ${TimelineProbe.rowCompositions.get()}; UI frames ${ui.size} p50 %.1f p95 %.1f p99 %.1f max %.1f ms; main thread %.0f ms busy, worst message %.1f ms; centre now %.2f s"
            .format(Aud2Bench.pct(ui, .5), Aud2Bench.pct(ui, .95), Aud2Bench.pct(ui, .99), ui.maxOrNull() ?: 0.0, mainMs, mainWorst, (TimelineProbe.control?.centreUs?.invoke() ?: 0) / 1e6))
        pump(1000)
        // A programmatic fling forward from the middle (the timeline's own fling behaviour).
        front("$label fling"); seek(dur / 2, 1500)
        TimelineProbe.reset()
        val from = TimelineProbe.control?.centreUs?.invoke() ?: 0
        val (fMain, fWorst) = mainThreadTime { ui = uiFrames(compose.activity.window) { fling(8000f) } }
        report("$label FLING 8000 px/s: moved %.2f s; timeline recompositions ${TimelineProbe.viewCompositions.get()}, clip-row recompositions ${TimelineProbe.rowCompositions.get()}; UI frames ${ui.size} p50 %.1f p95 %.1f p99 %.1f max %.1f ms; main thread %.0f ms busy, worst %.1f ms"
            .format(((TimelineProbe.control?.centreUs?.invoke() ?: 0) - from) / 1e6, Aud2Bench.pct(ui, .5), Aud2Bench.pct(ui, .95), Aud2Bench.pct(ui, .99), ui.maxOrNull() ?: 0.0, fMain, fWorst))
        // The highest zoom asked for.
        front("$label zoom"); main.runOnMainSync { TimelineProbe.control?.setZoom?.invoke(com.fluxx.android.editor.TimelineZoom.MAX_DP_PER_SECOND) }
        pump(2000)
        val z = TimelineProbe.control?.zoomDpPerSecond?.invoke() ?: -1f
        val fps = project.composition.frameRate.let { it.numerator.toDouble() / it.denominator }
        val (n1, w1) = nodes()
        report("$label (timeline recompositions so far ${TimelineProbe.viewCompositions.get()})")
        report("$label HIGHEST ZOOM asked %.0f dp/s: got %.1f dp/s = %.2f px per frame (%.2f dp per frame); content width %.0f px; layout nodes $n1, widest node $w1 px"
            .format(com.fluxx.android.editor.TimelineZoom.MAX_DP_PER_SECOND, z, z * compose.density.density / fps, z / fps, TimelineProbe.control?.contentWidthPx?.invoke() ?: -1.0))
    }

    private fun typical(): Pair<File, Aud1Bench.Media> { val f = file("typical H.264", Aud2Bench.TYPICAL); return f to Aud1Bench.media(f) }
    private fun clips(n: Int, clipUs: Long, f: File, m: Aud1Bench.Media) = (0 until n).map { k ->
        Aud2Bench.layer(1L + k, f, m, z = 10, timing = ClipTiming(k * clipUs, (k * clipUs) % (m.durationUs - clipUs).coerceAtLeast(1), clipUs)) }

    @Test fun a1Short30Clips() { val (f, m) = typical(); measure("short 30 x 2 s", Aud2Bench.project(1920, 1080, 60_000_000, 30, clips(30, 2_000_000, f, m))) }

    @Test fun a2Project1() {
        val file = File(context.filesDir, "projects/Project 1.fluxx")
        assumeTrue("TLV1: Project 1 not found; skipping", file.isFile)
        val before = file.length() to file.lastModified()
        try {
            val p = kotlinx.coroutines.runBlocking { ProjectManager(context).loadProject(file) }.getOrThrow()
            measure("Project 1", p)
        } finally {
            val after = file.length() to file.lastModified()
            report("Project 1 stat: before ${before.first}/${before.second}, after ${after.first}/${after.second} -> ${if (after == before) "UNCHANGED" else "CHANGED"}")
            check(after == before) { "Project 1 changed on disk" }
        }
    }

    @Test fun a3ThreeHours() { val (f, m) = typical(); measure("3 h (180 x 60 s)", Aud2Bench.project(1920, 1080, 10_800_000_000, 30, clips(180, 60_000_000, f, m))) }

    // ---- B5 (after the virtualisation) ----------------------------------------------------------------------------------
    private val viewport get() = compose.activity.resources.displayMetrics.widthPixels
    private fun topZoom(label: String, fps: Double): Float {
        main.runOnMainSync { TimelineProbe.control?.setZoom?.invoke(com.fluxx.android.editor.TimelineZoom.MAX_DP_PER_SECOND) }
        pump(2000)
        val z = TimelineProbe.control?.zoomDpPerSecond?.invoke() ?: -1f
        val (n, w) = nodes()
        report("$label TOP ZOOM: %.1f dp/s = %.2f px per frame; layout nodes $n, widest node $w px (viewport $viewport, window ${viewport + 2 * (viewport / 2)})"
            .format(z, z * compose.density.density / fps))
        assertTrue("$label: widest node $w px must not exceed the window", w <= viewport + 2 * (viewport / 2) + 2)
        return z
    }

    @Test fun b1ThreeHoursAtTopZoomStartMiddleEnd() {
        val (f, m) = typical()
        val p = Aud2Bench.project(1920, 1080, 10_800_000_000, 30, clips(180, 60_000_000, f, m))
        host(p); pump(2500); front("b1")
        topZoom("b1 3 h", 30.0)
        for ((name, t) in listOf("start" to 0L, "middle" to 5_400_000_000L, "end" to 10_800_000_000L)) {
            seek(t, 2000)
            val (n, w) = nodes()
            val centre = TimelineProbe.control?.centreUs?.invoke() ?: -1
            report("b1 3 h at the $name: centre %.3f s (asked %.3f s); layout nodes $n, widest $w px".format(centre / 1e6, t / 1e6))
            assertTrue("b1 $name: the playhead reached ${t / 1e6} s", kotlin.math.abs(centre - t) <= 34_000)
            assertTrue("b1 $name: widest node $w", w <= viewport + 2 * (viewport / 2) + 2)
        }
    }

    /** A MediaStore video row whose DISPLAY_NAME matches [like] (as the picker returns it). */
    private fun content(like: String): Pair<android.net.Uri, String>? {
        val cr = context.contentResolver
        for (col in listOf(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, android.provider.MediaStore.Files.getContentUri("external")))
            cr.query(col, arrayOf(android.provider.MediaStore.MediaColumns._ID, android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
                "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf(like), null)?.use { c ->
                if (c.moveToFirst()) return android.content.ContentUris.withAppendedId(col, c.getLong(0)) to c.getString(1) }
        return null
    }
    private fun add(items: List<Pair<android.net.Uri, String>>, placement: com.fluxx.android.editor.EditorViewModel.BulkPlacement) {
        val done = java.util.concurrent.CountDownLatch(1); var err: Throwable? = null
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val repo = com.fluxx.android.media.MediaRepository(context)
                if (items.size == 1) editor.addMedia(repo, items[0].first, items[0].second, 0) else editor.addMediaBatch(repo, items, 0, placement)
                c.update(editor.state.project, 0, false)
            } catch (t: Throwable) { err = t } finally { done.countDown() }
        }
        check(done.await(120, java.util.concurrent.TimeUnit.SECONDS)) { "add did not return" }
        err?.let { throw it }
    }

    @Test fun b2FiftyMinuteImport() {
        val long = content("Project 3%")
        assumeTrue("TLV1: Project 3 (the 50-minute HEVC) not found in MediaStore; skipping", long != null)
        host(Aud2Bench.project(1920, 1080, 10_000_000, 30, emptyList()).let { p -> p.copy(composition = p.composition.copy(durationUs = null)) })
        pump(1500); front("b2")
        add(listOf(long!!), com.fluxx.android.editor.EditorViewModel.BulkPlacement.AT_PLAYHEAD)
        pump(2500)
        val d = compose.runOnIdle { editor.state.project.composition.resolvedDurationUs }
        report("b2 the 50-minute import: composition %.1f s".format(d / 1e6))
        val z = topZoom("b2 50 min", 30.0)
        assertEquals("b2: the top zoom at 50 minutes is the short-project maximum", com.fluxx.android.editor.TimelineZoom.MAX_DP_PER_SECOND, z, 0f)
        seek(d / 2, 2000); val (n, w) = nodes(); report("b2 at the middle: layout nodes $n, widest $w px")
    }

    @Test fun b3SequenceThirtyBySixty() {
        val t = content("%" + Aud2Bench.TYPICAL_ID + "%")
        assumeTrue("TLV1: the typical clip not found in MediaStore; skipping", t != null)
        host(Aud2Bench.project(1920, 1080, 10_000_000, 30, emptyList()).let { p -> p.copy(composition = p.composition.copy(durationUs = null)) })
        pump(1500); front("b3")
        add(List(30) { t!! }, com.fluxx.android.editor.EditorViewModel.BulkPlacement.SEQUENCE)
        pump(3000)
        val d = compose.runOnIdle { editor.state.project.composition.resolvedDurationUs }
        report("b3 Sequence of 30 x 60 s: composition %.1f s, ${compose.runOnIdle { editor.state.project.composition.layers.size }} layers".format(d / 1e6))
        topZoom("b3 Sequence", 30.0)
        for (at in listOf(0L, d / 2, d)) { seek(at, 1500); val (n, w) = nodes(); report("b3 at %.1f s: layout nodes $n, widest $w px".format(at / 1e6)) }
    }

    @Test fun b5FlingAcrossThreeHours() {
        val (f, m) = typical()
        host(Aud2Bench.project(1920, 1080, 10_800_000_000, 30, clips(180, 60_000_000, f, m))); pump(2500); front("b5")
        // A low zoom so a run of flings crosses all 3 hours (at 4 dp/s, 3 h is ~121,500 px).
        main.runOnMainSync { TimelineProbe.control?.setZoom?.invoke(4f) }; pump(1500)
        seek(0, 1500)
        val frames = CopyOnWriteArrayList<LongArray>()
        TimelineProbe.coverage = { ws, we, vs, ve -> frames += longArrayOf(ws, we, vs, ve) }
        var ui = emptyList<Double>(); var flings = 0
        val t0 = System.nanoTime()
        ui = uiFrames(compose.activity.window) {
            while (flings < 60 && (TimelineProbe.control?.centreUs?.invoke() ?: 0) < 10_800_000_000L - 1) { fling(30_000f); flings++ }
        }
        TimelineProbe.coverage = null
        val f0 = frames.toList()
        val blank = f0.count { (ws, we, vs, ve) -> ws > vs + 1 || we < ve - 1 }
        val end = TimelineProbe.control?.centreUs?.invoke() ?: -1
        report("b5 FLING across 3 h: $flings flings in %.1f s wall, reached %.1f s; ${f0.size} drawn frames, frames whose window did not cover the screen: $blank; UI frames ${ui.size} p50 %.1f p95 %.1f p99 %.1f max %.1f ms"
            .format((System.nanoTime() - t0) / 1e9, end / 1e6, Aud2Bench.pct(ui, .5), Aud2Bench.pct(ui, .95), Aud2Bench.pct(ui, .99), ui.maxOrNull() ?: 0.0))
        assertEquals("b5: no blank edge", 0, blank)
        assertTrue("b5: the flings crossed the whole composition", end >= 10_800_000_000L - 1_000_000)
    }
}
