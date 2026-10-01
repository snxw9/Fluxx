package com.fluxx.android.render

import android.content.Intent
import android.graphics.Bitmap
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.engine.RenderBridge
import com.fluxx.android.engine.TextDebugBridge
import com.fluxx.android.engine.TextCapacityException
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class TextGpuAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun project() = ProjectDocument(Composition(width = 640, height = 360, layers = FrozenList.of(
        (0 until 3).map { CompositionLayer(id = it + 1L, type = LayerType.SOLID, zOrder = it,
            timing = ClipTiming(durationUs = 1_000_000), solidColorArgb = (0xff203040L + it * 0x101010).toInt(),
            transform = Transform(scaleX = .2f, scaleY = .2f, positionX = -.6f + it * .6f)) }
    )))
    private fun stats(renderer: CompositionRenderer): JSONObject = JSONObject(RenderBridge.textStats(renderer.session)).also {
        assertTrue("Vulkan validation must actually be enabled", it.getBoolean("validationEnabled"))
        assertEquals(0L, it.getLong("validationErrors"))
    }
    private fun resources(): Map<String, Long> = JSONObject(TextDebugBridge.gpuLedger()).let { json ->
        json.keys().asSequence().associateWith { json.getLong(it) }
    }
    private fun directory(name: String): File {
        val label = requireNotNull(InstrumentationRegistry.getArguments().getString("textRunLabel")) { "Set unique textRunLabel" }
        require(label.matches(Regex("[A-Za-z0-9_-]+")))
        return File(context.getExternalFilesDir(null), "text/$label/$name").also {
            check(!it.exists()) { "Archive the previous proof results" }; check(it.mkdirs())
        }
    }
    private fun capture(renderer: CompositionRenderer, directory: File, name: String, width: Int, height: Int) {
        val bytes = ByteBuffer.allocateDirect(width * height * 4)
        assertTrue(TextDebugBridge.pixels(renderer.session, bytes))
        val pixels = IntArray(width * height) { val r = bytes.get().toInt() and 255; val g = bytes.get().toInt() and 255
            val b = bytes.get().toInt() and 255; val a = bytes.get().toInt() and 255
            (a shl 24) or (r shl 16) or (g shl 8) or b }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try { File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        File(directory, "$name.json").writeText(stats(renderer).toString(2))
    }
    private fun host(): TextProofActivity = instrumentation.startActivitySync(Intent(context, TextProofActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TextProofActivity

    @Test fun scaleQualityMatrixSweepMixedOrderAndRepack() {
        val directory = directory("matrix")
        val host = host(); val surface = host.awaitSurface()
        try {
            CompositionRenderer(context, MediaRepository(context), surface, 640, 360).use { renderer ->
                for (scale in listOf(.5f, 1f, 3f)) for (quality in listOf(PreviewResolution.FULL, PreviewResolution.HALF, PreviewResolution.QUARTER)) {
                    TextFixtureProvider.fixtures = listOf(TextFixture(1, scale = scale, x = -200f))
                    renderer.renderPreview(project(), 0, quality)
                    val dimensions = quality.calculateDimensions(640, 360)
                    capture(renderer, directory, "scale-${scale}-quality-${quality}", dimensions.first, dimensions.second)
                }
                for (size in listOf(8f, 12f, 14f, 16f)) for (quality in listOf(PreviewResolution.FULL, PreviewResolution.HALF, PreviewResolution.QUARTER)) {
                    TextFixtureProvider.fixtures = listOf(TextFixture(1, size = size, x = -200f, source = "AV office gy"),
                        TextFixture(2, size = size, x = -200f, y = 50f, font = "fluxx.serif", source = "office AV gy"))
                    renderer.renderPreview(project(), 0, quality)
                    val dimensions = quality.calculateDimensions(640, 360)
                    capture(renderer, directory, "size-${size}-quality-${quality}", dimensions.first, dimensions.second)
                }
                TextFixtureProvider.fixtures = listOf(TextFixture(1, x = -200f, opacity = .5f, rotation = 30f),
                    TextFixture(2, beforeRaster = 1, font = "fluxx.serif", source = "office", y = 50f),
                    TextFixture(3, beforeRaster = 2, flip = true, y = -50f), TextFixture(4, beforeRaster = 3, source = "A\uDBFF\uDFFFV"))
                renderer.renderPreview(project(), 0, PreviewResolution.FULL)
                capture(renderer, directory, "mixed-seven-entries", 640, 360)
                val rasterizations = stats(renderer).getLong("rasterizations")
                repeat(120) { i ->
                    TextFixtureProvider.fixtures = TextFixtureProvider.fixtures.map { it.copy(scale = .5f + i / 119f * 2.5f) }
                    renderer.renderPreview(project(), 0, PreviewResolution.FULL)
                }
                assertEquals("Warm scale sweep cannot rasterize", rasterizations, stats(renderer).getLong("rasterizations"))
                val generation = stats(renderer).getLong("generation")
                TextDebugBridge.repack(renderer.session)
                renderer.renderPreview(project(), 0, PreviewResolution.FULL)
                assertEquals(rasterizations, stats(renderer).getLong("rasterizations"))
                assertTrue(stats(renderer).getLong("generation") > generation)
                capture(renderer, directory, "after-repack", 640, 360)
            }
        } finally { TextFixtureProvider.fixtures = emptyList(); instrumentation.runOnMainSync { host.finish() } }
    }

    @Test fun requiredCorpusCapacityFailureIsExplicitAndRecoverable() {
        val corpus = buildString {
            for (range in listOf(0x20..0x24f, 0x1e00..0x1eff, 0x2000..0x206f)) for (cp in range) append(cp.toChar())
        }
        try {
            CompositionRenderer(context, MediaRepository(context)).use { renderer ->
                TextFixtureProvider.fixtures = listOf("fluxx.sans", "fluxx.serif", "fluxx.mono").mapIndexed { i, font ->
                    TextFixture(i + 1L, source = corpus, font = font)
                }
                try { renderer.render(project(), 0); fail("Combined corpus must exceed E1 proof capacity") }
                catch (_: TextCapacityException) { assertEquals(0L, stats(renderer).getLong("draws")) }
                TextFixtureProvider.fixtures = listOf(TextFixture(1))
                renderer.render(project(), 0); assertEquals(1L, stats(renderer).getLong("draws"))
            }
        } finally { TextFixtureProvider.fixtures = emptyList() }
    }

    @Test fun arrayGrowthUsesRetainedCorpusDuringSweepAndTeardownReturnsLedger() {
        val baseline = resources()
        val corpus = buildString {
            for (range in listOf(0x20..0x24f, 0x1e00..0x1eff, 0x2000..0x206f)) for (cp in range) append(cp.toChar())
        }
        try {
            CompositionRenderer(context, MediaRepository(context)).use { renderer ->
                TextFixtureProvider.fixtures = listOf(TextFixture(1))
                renderer.render(project(), 0)
                assertEquals(1, stats(renderer).getInt("atlasPages"))
                TextFixtureProvider.fixtures = listOf("fluxx.sans", "fluxx.serif", "fluxx.mono").mapIndexed { i, font ->
                    TextFixture(i + 1L, source = corpus, font = font)
                }
                try { renderer.render(project(), 0); fail("E1 single-page fixture must remain constrained") }
                catch (_: TextCapacityException) { }
                val rasterizations = stats(renderer).getLong("rasterizations")
                TextFixtureProvider.fixtures = emptyList()
                val layers = project().composition.layers + listOf("fluxx.sans", "fluxx.serif", "fluxx.mono").mapIndexed { i, font ->
                    CompositionLayer(id = 10L + i, type = LayerType.TEXT, zOrder = i * 2,
                        timing = ClipTiming(durationUs = 1_000_000), text = TextProperties(
                            source = AnimatableString(staticValue = corpus), fontId = font))
                }
                val document = ProjectDocument(project().composition.copy(layers = FrozenList.of(layers)))
                repeat(30) { index ->
                    val scaled = document.copy(composition = document.composition.copy(layers = FrozenList.of(layers.map {
                        it.copy(transform = it.transform.copy(scaleX = .5f + index / 10f, scaleY = .5f + index / 10f))
                    })))
                    renderer.render(scaled, 0)
                    assertEquals("Array recreation cannot rasterize retained corpus", rasterizations, stats(renderer).getLong("rasterizations"))
                    assertEquals(2, stats(renderer).getInt("atlasPages"))
                }
            }
            assertTrue("Array teardown leaked resources", TextProofPolicy.resourcePass(baseline, resources()))
        } finally { TextFixtureProvider.fixtures = emptyList() }
    }

    @Test fun thirtySurfaceAndRendererCyclesReturnResourcesAndBoundHeap() {
        val directory = directory("lifecycle"); val host = host()
        val baseline = resources()
        val surfaceHeap = mutableListOf<Long>(); val rendererHeap = mutableListOf<Long>()
        val ledger = JSONArray()
        TextFixtureProvider.fixtures = listOf(TextFixture(1, x = -200f))
        try {
            var surface = host.awaitSurface()
            CompositionRenderer(context, MediaRepository(context), surface, 640, 360).use { renderer ->
                repeat(TextProofPolicy.WARMUP) { renderer.renderPreview(project(), 0, PreviewResolution.FULL) }
                val resident = resources()
                repeat(TextProofPolicy.CYCLES) { i ->
                    assertTrue(TextDebugBridge.surface(renderer.session, null, 0, 0))
                    instrumentation.runOnMainSync { host.installSurface() }; surface = host.awaitSurface()
                    assertTrue(TextDebugBridge.surface(renderer.session, surface, 640, 360))
                    renderer.renderPreview(project(), 0, PreviewResolution.FULL); stats(renderer)
                    assertTrue("Surface cycle changed resource categories", TextProofPolicy.resourcePass(resident, resources()))
                    surfaceHeap += Debug.getNativeHeapAllocatedSize()
                    ledger.put(JSONObject().put("surfaceCycle", i).put("resources", JSONObject(TextDebugBridge.gpuLedger())))
                }
            }
            assertTrue(TextProofPolicy.resourcePass(baseline, resources()))
            repeat(TextProofPolicy.WARMUP + TextProofPolicy.CYCLES) { i ->
                CompositionRenderer(context, MediaRepository(context), surface, 640, 360).use { renderer ->
                    renderer.renderPreview(project(), 0, PreviewResolution.FULL); stats(renderer)
                }
                assertTrue("Destroyed renderer leaked a resource", TextProofPolicy.resourcePass(baseline, resources()))
                if (i >= TextProofPolicy.WARMUP) rendererHeap += Debug.getNativeHeapAllocatedSize()
            }
            File(directory, "lifecycle.json").writeText(JSONObject().put("surfaceHeap", JSONArray(surfaceHeap))
                .put("rendererHeap", JSONArray(rendererHeap)).put("ledger", ledger).put("baseline", JSONObject(baseline)).toString(2))
            assertTrue("Surface native heap growth", TextProofPolicy.heapPass(surfaceHeap))
            assertTrue("Renderer native heap growth", TextProofPolicy.heapPass(rendererHeap))
        } finally { TextFixtureProvider.fixtures = emptyList(); instrumentation.runOnMainSync { host.finish() } }
    }
}
