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

    private fun fling(v: Float) {
        val ctl = TimelineProbe.control ?: error("no timeline probe")
        var job: kotlinx.coroutines.Job? = null
        main.runOnMainSync { job = ctl.fling(v) }
        val t0 = System.nanoTime()
        while (job?.isActive == true && System.nanoTime() - t0 < 30_000_000_000L) Thread.sleep(20)
    }

    /** The test's window must be in front (another app on top draws no frames for it and nothing recomposes). */
    private fun front(label: String) {
        var focus = false; main.runOnMainSync { focus = compose.activity.hasWindowFocus() }
        if (!focus) report("$label: SKIPPED: the test window is not in front (another app is on top)")
        assumeTrue("TLV1 $label: the test window is not in front; skipping", focus)
    }

    private fun measure(label: String, project: ProjectDocument) {
        host(project)
        Thread.sleep(2500)
        front(label)
        val dur = project.composition.resolvedDurationUs
        val (n0, w0) = nodes()
        report("$label: ${project.composition.layers.size} layers, %.1f s; default zoom %.1f dp/s; layout nodes $n0, widest node $w0 px; content width %.0f px"
            .format(dur / 1e6, TimelineProbe.control?.zoomDpPerSecond?.invoke() ?: -1f, TimelineProbe.control?.contentWidthPx?.invoke() ?: -1.0))
        // 5 s of playback follow from 0 (the first source's sound decoded first, so Play starts at once).
        project.composition.layers.filter { it.type == LayerType.VIDEO && it.timing.startUs == 0L }.mapNotNull { it.asset }.distinctBy { it.uri }
            .forEach { a -> warmPcm(a.uri!!, a.durationUs ?: 10_000_000, 90_000) }
        seekTo(0, 1500)
        TimelineProbe.reset()
        var ui = emptyList<Double>()
        val (mainMs, mainWorst) = mainThreadTime { ui = uiFrames(compose.activity.window) { main.runOnMainSync { c.play() }; Thread.sleep(5000); main.runOnMainSync { c.pause() } } }
        report("$label FOLLOW 5 s: timeline recompositions ${TimelineProbe.viewCompositions.get()}, clip-row recompositions ${TimelineProbe.rowCompositions.get()}; UI frames ${ui.size} p50 %.1f p95 %.1f p99 %.1f max %.1f ms; main thread %.0f ms busy, worst message %.1f ms; centre now %.2f s"
            .format(Aud2Bench.pct(ui, .5), Aud2Bench.pct(ui, .95), Aud2Bench.pct(ui, .99), ui.maxOrNull() ?: 0.0, mainMs, mainWorst, (TimelineProbe.control?.centreUs?.invoke() ?: 0) / 1e6))
        Thread.sleep(1000)
        // A programmatic fling forward from the middle (the timeline's own fling behaviour).
        front("$label fling"); seekTo(dur / 2, 1500)
        TimelineProbe.reset()
        val from = TimelineProbe.control?.centreUs?.invoke() ?: 0
        val (fMain, fWorst) = mainThreadTime { ui = uiFrames(compose.activity.window) { fling(8000f) } }
        report("$label FLING 8000 px/s: moved %.2f s; timeline recompositions ${TimelineProbe.viewCompositions.get()}, clip-row recompositions ${TimelineProbe.rowCompositions.get()}; UI frames ${ui.size} p50 %.1f p95 %.1f p99 %.1f max %.1f ms; main thread %.0f ms busy, worst %.1f ms"
            .format(((TimelineProbe.control?.centreUs?.invoke() ?: 0) - from) / 1e6, Aud2Bench.pct(ui, .5), Aud2Bench.pct(ui, .95), Aud2Bench.pct(ui, .99), ui.maxOrNull() ?: 0.0, fMain, fWorst))
        // The highest zoom asked for.
        front("$label zoom"); main.runOnMainSync { TimelineProbe.control?.setZoom?.invoke(com.fluxx.android.editor.TimelineZoom.MAX_DP_PER_SECOND) }
        Thread.sleep(2000)
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
}
