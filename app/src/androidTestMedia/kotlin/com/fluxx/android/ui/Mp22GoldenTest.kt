package com.fluxx.android.ui

import android.content.ContentUris
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.engine.RenderBridge
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import com.fluxx.android.render.CompositionRenderer
import com.fluxx.android.render.PreviewResolution
import com.fluxx.android.render.StagedDecoders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * MP2.2 B1: the narrowed flush DIFFERENTIAL golden test. Every scene is rendered by two renderers at the same times, one
 * with the test-only flush-always switch (the behaviour before MP2.2) and one with the narrowed flush, and the composited
 * output (the renderer's own readback) must be BYTE-IDENTICAL. Preview renderers (surface-backed, playing = true, so the
 * playback look-ahead decode runs) and export renderers (offscreen). The staged-image check is on throughout: no decoder
 * may decode, seek or close while it has an image staged and unflushed.
 */
class Mp22GoldenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = compose.activity
    private fun report(line: String) {
        android.util.Log.i("MP22", line)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("MP22", line) })
    }
    private val w = 640; private val h = 360
    private val rate = FrameRate(30, 1)

    private data class Src(val uri: String, val info: MediaRepository.VideoInfo)
    private fun source(repo: MediaRepository, pattern: String): Src? = runCatching {
        context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf(pattern), null)?.use { c ->
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)).toString()
                runCatching { repo.video(uri) }.getOrNull()?.let { return@runCatching Src(uri, it) }
            }
            null
        }
    }.getOrNull()

    private fun video(id: Long, s: Src, inUs: Long, durUs: Long, x: Float, y: Float, scale: Float, opacity: Float = 1f, z: Int = 10) =
        CompositionLayer(id, LayerType.VIDEO, name = "v$id", zOrder = z, timing = ClipTiming(0, inUs, durUs),
            transform = Transform(x, y, scale, scale, 0f, opacity),
            asset = AssetReference("mp22g-$id", s.uri, access = MediaAccess.AVAILABLE, durationUs = s.info.durationUs))
    private fun tiles(s: Src, n: Int, distinct: Boolean, durUs: Long): List<CompositionLayer> {
        val cols = kotlin.math.ceil(kotlin.math.sqrt(n.toDouble())).toInt(); val rows = (n + cols - 1) / cols
        return (0 until n).map { k ->
            video(1L + k, s, if (distinct) 2_000_000L + k * 5_000_000L else 10_000_000L, durUs,
                -1f + (2f * (k % cols) + 1f) / cols, -1f + (2f * (k / cols) + 1f) / rows, 1f / maxOf(cols, rows), z = 10 + k)
        }
    }
    private fun project(durUs: Long, layers: List<CompositionLayer>) =
        ProjectDocument(Composition(width = w, height = h, durationUs = durUs, frameRate = rate, layers = FrozenList.of(layers)))

    private fun readback(r: CompositionRenderer): String {
        val y = ByteBuffer.allocateDirect(w * h); val u = ByteBuffer.allocateDirect(w * h / 4); val v = ByteBuffer.allocateDirect(w * h / 4)
        check(RenderBridge.copyYuv(r.session, y, u, v, w, w / 2, w / 2, 1, 1)) { "readback failed" }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        for (b in listOf(y, u, v)) { b.rewind(); val a = ByteArray(b.remaining()); b.get(a); md.update(a) }
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun surfaces(): List<android.view.Surface> {
        val latch = CountDownLatch(2); val out = arrayOfNulls<android.view.Surface>(2)
        compose.setContent {
            Column {
                for (i in 0 until 2) AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { ctx -> SurfaceView(ctx).apply { holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {}
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { if (out[i] == null) { out[i] = holder.surface; latch.countDown() } }
                    override fun surfaceDestroyed(holder: SurfaceHolder) {}
                }) } })
            }
        }
        check(latch.await(10, TimeUnit.SECONDS)) { "no surfaces" }
        return out.map { it!! }
    }

    /** One renderer of the pair renders [t] in its mode; returns its readback digest. */
    private fun render(r: CompositionRenderer, project: ProjectDocument, t: Long, always: Boolean, preview: Boolean, cancelled: () -> Boolean = { false }): String {
        CompositionRenderer.testFlushAlways = always
        try { if (preview) r.renderPreview(project, t, PreviewResolution.FULL, cancelled) else r.render(project, t, cancelled) }
        finally { CompositionRenderer.testFlushAlways = false }
        return readback(r)
    }

    private var total = 0; private var different = 0

    private fun compare(kind: String, old: CompositionRenderer, new: CompositionRenderer, name: String, project: ProjectDocument, frames: List<Long>, preview: Boolean) {
        var bad = 0
        if (preview) { old.playing = true; new.playing = true }
        for (t in frames) { if (render(old, project, t, true, preview) != render(new, project, t, false, preview)) bad++ }
        total += frames.size; different += bad
        report("golden $kind '$name': ${frames.size} frames, ${if (bad == 0) "byte-identical" else "$bad DIFFERENT"}; staged-image violations so far ${StagedDecoders.violations}")
    }

    /** A frame cancelled mid-way (after some draws are staged), then the next frame: compared with a clean render. */
    private fun cancelled(kind: String, a: CompositionRenderer, b: CompositionRenderer, project: ProjectDocument, preview: Boolean) {
        if (preview) { a.playing = true; b.playing = true }
        render(a, project, rate.frameTimeUs(0), false, preview); render(b, project, rate.frameTimeUs(0), false, preview)
        // Cancel at the first check after two video layers are staged (counted through the test-only probe).
        var calls = 0; var wasCancelled = false; var drawn = 0L; val probe = LongArray(CompositionRenderer.PROBE_COUNT)
        CompositionRenderer.testProbe = true; a.probeCounters(probe)
        try { render(a, project, rate.frameTimeUs(1), false, preview) { calls++; a.probeCounters(probe); drawn += probe[CompositionRenderer.PROBE_VIDEO_DRAWN]; drawn >= 2 } }
        catch (e: CancellationException) { wasCancelled = true } finally { CompositionRenderer.testProbe = false }
        val t = rate.frameTimeUs(2)
        val same = render(a, project, t, false, preview) == render(b, project, t, false, preview)
        total++; if (!same) different++
        report("golden $kind 'cancelled frame, then the next': ${if (wasCancelled) "cancelled after $calls checks, $drawn video layers staged" else "NOT cancelled"}; next frame ${if (same) "byte-identical" else "DIFFERENT"} to a clean render")
        assertTrue("the frame was not cancelled", wasCancelled)
    }

    @Test fun narrowedFlushIsByteIdentical() {
        val state = TestDeviceState.record(javaClass.simpleName)
        report("device: $state")
        val repo = MediaRepository(context)
        val s = source(repo, "%${Aud2Bench.TYPICAL_ID}%")
        assumeTrue("MP22 golden: the typical H.264 file was not found", s != null)
        val vp9 = source(repo, "${Aud1Bench.PREFIX}%")
        val dur = 3_000_000L
        val frames = (0L until 4L).map { rate.frameTimeUs(it) }
        // Mixed scene: an image, a solid, text, opacity, two layers of one source 40 ms apart and one distinct.
        val png = File(context.cacheDir, "mp22-golden.png")
        Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { for (x in 0 until 64) for (y in 0 until 48) setPixel(x, y, (0xff shl 24) or (x * 4 shl 16) or (y * 5 shl 8) or 0x80) }
            .also { b -> png.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
        val image = CompositionLayer(50, LayerType.IMAGE, name = "img", zOrder = 30, timing = ClipTiming(0, 0, dur), transform = Transform(.5f, .5f, .4f, .4f, 15f, .8f),
            asset = AssetReference("mp22g-img", android.net.Uri.fromFile(png).toString(), access = MediaAccess.AVAILABLE))
        val solid = CompositionLayer(51, LayerType.SOLID, name = "solid", zOrder = 5, timing = ClipTiming(0, 0, dur), solidColorArgb = 0xff2050a0.toInt(),
            transform = Transform(0f, 0f, 1f, 1f, 0f, 1f))
        val text = CompositionLayer(52, LayerType.TEXT, name = "text", zOrder = 40, timing = ClipTiming(0, 0, dur), transform = Transform(-.3f, -.6f),
            text = TextProperties(source = AnimatableString(staticValue = "MP2.2 golden"), strokeWidth = AnimatableProperty1D(staticValue = 2f)))
        val near = listOf(video(1, s!!, 10_000_000L, dur, -.5f, 0f, .5f), video(2, s, 10_040_000L, dur, .5f, 0f, .5f, opacity = .5f, z = 11))
        val scenes = ArrayList<Triple<String, ProjectDocument, List<Long>>>()
        for (n in listOf(1, 2, 4, 6, 8)) scenes += Triple("distinct N=$n", project(dur, tiles(s, n, true, dur)), if (n >= 6) frames.take(2) else frames)
        for (n in listOf(1, 2, 4, 6, 8)) scenes += Triple("shared N=$n", project(dur, tiles(s, n, false, dur)), frames)
        scenes += Triple("two layers of one source 40 ms apart, with a backward seek", project(dur, near),
            listOf(0L, 1L, 2L, 3L, 1L, 0L, 5L, 2L).map { rate.frameTimeUs(it) })
        scenes += Triple("mixed: image, solid, text, opacity, shared pair and a distinct layer", project(dur, near + video(3, s, 20_000_000L, dur, 0f, .5f, .4f, z = 12) + image + solid + text), frames.take(3))
        if (vp9 != null) {
            scenes += Triple("VP9 1440p distinct N=2", project(dur, tiles(vp9, 2, true, dur)), frames.take(3))
            scenes += Triple("VP9 1440p shared N=2 (40 ms apart)", project(dur, listOf(video(1, vp9, 10_000_000L, dur, -.5f, 0f, .5f), video(2, vp9, 10_040_000L, dur, .5f, 0f, .5f, z = 11))), frames.take(3))
        } else report("golden: the VP9 file was not found; its scenes are skipped")

        val t0 = System.nanoTime()
        val (sa, sb) = surfaces()
        CompositionRenderer.testCheckStaged = true
        val worker = Executors.newSingleThreadExecutor { Thread(it, "mp22-golden") }
        try {
            worker.submit {
                // Export renderers (offscreen), then preview renderers (surface-backed, playing).
                CompositionRenderer(context, repo).use { old -> CompositionRenderer(context, repo).use { new ->
                    for ((name, p, ts) in scenes) compare("export", old, new, name, p, ts, false)
                } }
                CompositionRenderer(context, repo).use { a -> CompositionRenderer(context, repo).use { b ->
                    cancelled("export", a, b, project(dur, tiles(s, 4, false, dur)), false)
                } }
                CompositionRenderer(context, repo, sa, w, h).use { old -> CompositionRenderer(context, repo, sb, w, h).use { new ->
                    for ((name, p, ts) in scenes) compare("preview", old, new, name, p, ts, true)
                } }
                CompositionRenderer(context, repo, sa, w, h).use { a -> CompositionRenderer(context, repo, sb, w, h).use { b ->
                    cancelled("preview", a, b, project(dur, near + tiles(s, 2, false, dur).map { it.copy(id = it.id + 10) }), true)
                } }
            }.get(15, TimeUnit.MINUTES)
        } finally {
            val violations = StagedDecoders.violations; val first = StagedDecoders.firstViolation
            CompositionRenderer.testCheckStaged = false; CompositionRenderer.testFlushAlways = false
            worker.shutdownNow(); png.delete()
            report("golden result: $total frames compared, $different different; staged-image violations $violations${first?.let { " (first: $it)" } ?: ""}; ${"%.0f".format((System.nanoTime() - t0) / 1e9)} s: ${if (different == 0 && violations == 0L) "PASS" else "FAIL"}")
            assertEquals("staged-image violations: $first", 0L, violations)
        }
        assertEquals("frames that differ (flush-always vs narrowed)", 0, different)
        assertTrue(total > 0)
    }
}
