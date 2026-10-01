package com.fluxx.android.render

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.engine.RenderBridge
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.Composition
import com.fluxx.android.model.ProjectDocument
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** User-run only. Missing Vulkan validation is a failed prerequisite, never a skipped pass. */
@RunWith(AndroidJUnit4::class)
class TextGpuProofTest {
    @Test fun rendersThroughCompositionRendererWithValidation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        TextFixtureProvider.fixtures = listOf(TextFixture(1, source = "AV office", x = -200f))
        try {
            CompositionRenderer(context, MediaRepository(context)).use { renderer ->
                renderer.render(ProjectDocument(Composition(width = 640, height = 360)), 0)
                val stats = JSONObject(RenderBridge.textStats(renderer.session))
                assertTrue("Install/enable VK_LAYER_KHRONOS_validation; see TEXT_LAYER.md", stats.getBoolean("validationEnabled"))
                assertEquals(0L, stats.getLong("validationErrors"))
                assertEquals(1L, stats.getLong("images"))
                assertEquals(1L, stats.getLong("draws"))
                assertEquals(1L, stats.getLong("uploads"))
                val rasterizations = stats.getLong("rasterizations")
                renderer.render(ProjectDocument(Composition(width = 640, height = 360)), 0)
                assertEquals(rasterizations, JSONObject(RenderBridge.textStats(renderer.session)).getLong("rasterizations"))
            }
        } finally { TextFixtureProvider.fixtures = emptyList() }
    }
}
