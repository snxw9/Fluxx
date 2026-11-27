package com.fluxx.android.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.export.CompositionExporter
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import com.fluxx.android.render.CompositionRenderer
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * MP1 A0: does export leave a layer out? Six overlapping video layers of the typical clip (each its own URI spelling, so
 * six decoders are needed; in-points 7 s apart) tiled 3x2 by position and scale over a MAGENTA solid, so a missing layer
 * is a magenta hole. 2 s are exported through the app's exporter (to this app's cache, deleted after), the exported file
 * is decoded and every tile centre is checked on 13 sampled frames. Also reports what the export renderer itself says
 * (its layers-left-out counter). ASSERTS that every layer is present.
 */
@UnstableApi
class Mp1ExportTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun report(line: String) {
        android.util.Log.i("MP1", line)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("MP1", line) })
    }

    @Test fun a0SixLayerExport() {
        TestDeviceState.record(javaClass.simpleName)
        val f = Aud2Bench.find(context, Aud2Bench.TYPICAL)
        assumeTrue("MP1: the typical H.264 file was not found (${Aud2Bench.lookupLog}); skipping", f != null)
        val m = Aud1Bench.media(f!!)
        val dur = 2_000_000L
        val cells = (0 until 6).map { k -> (-2f / 3 + (k % 3) * 2f / 3) to (if (k < 3) -.5f else .5f) }
        val layers = listOf(Mp1Bench.solid(100, 0xffff00ff.toInt(), dur)) + cells.mapIndexed { k, (x, y) ->
            Mp1Bench.placed(1L + k, f, m, Aud2Bench.distinctUri(f, k), 10 + k, ClipTiming(0, 5_000_000L + k * 7_000_000L, dur), x, y, 1f / 3) }
        val project = Aud2Bench.project(1920, 1080, dur, 30, layers)

        // What the export renderer does with six distinct sources (the same CompositionRenderer, offscreen, as export).
        CompositionRenderer(context, MediaRepository(context)).use { r ->
            val left = listOf(0L, 1_000_000L, 1_966_666L).map { t -> r.render(project, t); r.layersWithoutDecoder }
            report("A0 export renderer (offscreen CompositionRenderer, as CompositionExporter uses it): video layers left out per frame at 0 / 1.0 / 1.97 s: ${left.joinToString(" / ")}")
        }

        val out = File(context.cacheDir, "mp1-a0-export.mp4")
        try {
            val t0 = System.nanoTime()
            kotlinx.coroutines.runBlocking { CompositionExporter(context).export(project, out) { } }
            report("A0 exported 2 s, 6 layers, in %.1f s; %d bytes".format((System.nanoTime() - t0) / 1e9, out.length()))
            val mmr = MediaMetadataRetriever(); mmr.setDataSource(out.path)
            val count = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toInt() ?: 0
            val samples = ((0 until count step 5) + (count - 1)).distinct()
            var missing = 0; val perLayer = IntArray(6); var gapIsBackground = 0
            for (i in samples) {
                val bmp = mmr.getFrameAtIndex(i) ?: continue
                val w = bmp.width; val h = bmp.height
                cells.forEachIndexed { k, (x, y) ->
                    val cx = ((x + 1) / 2 * w).toInt(); val cy = ((y + 1) / 2 * h).toInt()
                    if (magenta(bmp, cx, cy, 20)) { missing++; perLayer[k]++ }
                }
                // Sanity: the band above the top-left tile's picture (inside its 640x540 cell) shows the background.
                // (the cell is h/2 tall, the 16:9 picture at 1/3 scale is h/3 tall and centred: the band is y < h/12).
                if (magenta(bmp, w / 6, h / 24, 4)) gapIsBackground++
                bmp.recycle()
            }
            mmr.release()
            report("A0 export check: $count frames, ${samples.size} sampled x 6 tiles: tiles MISSING $missing; per layer (bottom-left z10 first) ${perLayer.joinToString("/")}; background visible in the gap on $gapIsBackground/${samples.size} samples (detector sanity)")
            report("A0 result: ${if (missing == 0) "PASS (all 6 layers present on every sampled frame)" else "FAIL (layers left out of the export)"}")
            assertEquals("tiles missing in the export", 0, missing)
        } finally { out.delete() }
    }

    private fun magenta(b: Bitmap, cx: Int, cy: Int, r: Int): Boolean {
        var rs = 0L; var gs = 0L; var bs = 0L; var n = 0
        for (y in (cy - r).coerceAtLeast(0)..(cy + r).coerceAtMost(b.height - 1)) for (x in (cx - r).coerceAtLeast(0)..(cx + r).coerceAtMost(b.width - 1)) {
            val p = b.getPixel(x, y); rs += (p shr 16) and 255; gs += (p shr 8) and 255; bs += p and 255; n++ }
        if (n == 0) return false
        return rs / n > 190 && gs / n < 70 && bs / n > 190
    }
}
