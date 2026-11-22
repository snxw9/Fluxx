package com.fluxx.android.render

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.engine.TextDebugBridge
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * TEXT PERF 11b: merging text-only batches into one submit must not change a pixel. Each frame is rendered by two
 * production renderers, one with merged text batches and one submitting every flush separately (the TEXT PERF 10
 * behaviour), and the full RGBA readbacks must be equal. Covers 30 and 60 text layers (plain, F2 animators, size
 * 30→300 px with tier switches) and a project that interleaves translucent solid (raster) layers with text, so a
 * raster entry forces a real submit between merged text batches.
 */
@RunWith(AndroidJUnit4::class)
class TextMergedBatchParityTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun interleaved(): ProjectDocument {
        val text = TextMultiLayerFixture.project(context, 30, true, TextMultiLayerFixture.Kind.ANIMATED).composition.layers
        val layers = ArrayList<CompositionLayer>()
        for ((i, layer) in text.withIndex()) {
            layers.add(layer.copy(id = 1000L + i, zOrder = layers.size))
            if (i % 5 == 2) layers.add(CompositionLayer(2000L + i, LayerType.SOLID, zOrder = layers.size,
                timing = ClipTiming(durationUs = 12_000_000), solidColorArgb = (0x40000000 or (i * 0x0a1b2c and 0xffffff)),
                transform = Transform(scaleX = .3f + i * .01f, scaleY = .2f, positionY = -0.8f + i * .05f, opacity = .6f)))
        }
        return ProjectDocument(Composition(width = 1080, height = 1920, durationUs = 12_000_000, layers = FrozenList.of(layers)))
    }

    @Test fun mergedTextBatchesArePixelIdentical() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            worker.submit {
                CompositionRenderer(context, MediaRepository(context)).use { merged ->
                    CompositionRenderer(context, MediaRepository(context)).use { separate ->
                        TextDebugBridge.mergeTextBatches(merged.session, true)
                        TextDebugBridge.mergeTextBatches(separate.session, false)
                        val a = ByteBuffer.allocateDirect(1080 * 1920 * 4); val b = ByteBuffer.allocateDirect(1080 * 1920 * 4)
                        val projects = listOf(
                            "layers30-plain" to TextMultiLayerFixture.project(context, 30, true, TextMultiLayerFixture.Kind.PLAIN),
                            "layers30-animated" to TextMultiLayerFixture.project(context, 30, true, TextMultiLayerFixture.Kind.ANIMATED),
                            "layers30-size" to TextMultiLayerFixture.project(context, 30, true, TextMultiLayerFixture.Kind.SIZE),
                            "layers60-animated" to TextMultiLayerFixture.project(context, 60, true, TextMultiLayerFixture.Kind.ANIMATED),
                            "interleaved-solids" to interleaved())
                        val stages = LongArray(TextDebugBridge.STAGES)
                        for ((name, project) in projects) for (time in longArrayOf(0, 750_000, 1_500_000, 2_999_000, 5_000_000, 9_000_000)) {
                            merged.render(project, time); separate.render(project, time)
                            a.clear(); b.clear()
                            assertTrue(TextDebugBridge.pixels(merged.session, a)); assertTrue(TextDebugBridge.pixels(separate.session, b))
                            a.rewind(); b.rewind()
                            assertEquals("$name at $time: merged text batches changed pixels", b, a)
                        }
                        // The merged renderer really merged: one submit for 30 text layers, against eight.
                        val project = projects[0].second
                        TextDebugBridge.frameStages(merged.session, stages); merged.render(project, 1_000_000); TextDebugBridge.frameStages(merged.session, stages)
                        assertEquals("merged submits", 1L, stages[10])
                        TextDebugBridge.frameStages(separate.session, stages); separate.render(project, 1_000_000); TextDebugBridge.frameStages(separate.session, stages)
                        assertEquals("separate submits", 8L, stages[10])
                    }
                }
            }.get(10, TimeUnit.MINUTES)
        } finally { worker.shutdownNow() }
    }
}
