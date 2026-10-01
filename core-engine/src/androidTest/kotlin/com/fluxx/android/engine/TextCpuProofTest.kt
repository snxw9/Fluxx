package com.fluxx.android.engine

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** User-run JNI parity and CPU diagnostics. Never constructs a renderer/Vulkan device. */
@RunWith(AndroidJUnit4::class)
class TextCpuProofTest {
    @Test fun shapingMatchesPinnedHbShape() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.targetContext.assets
        val reference = instrumentation.context.assets.open("text/shaping-reference.json").bufferedReader().use { JSONObject(it.readText()) }
        val fonts = mapOf("fluxx.sans" to "Inter-Regular.ttf", "fluxx.serif" to "NotoSerif-Regular.ttf", "fluxx.mono" to "JetBrainsMono-Regular.ttf")
        fonts.forEach { (id, name) ->
            val bytes = assets.open("fonts/$name").use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals("Pinned font changed: $id", reference.getJSONObject("font_sha256").getString(id), digest)
        }
        FontSession(assets).use { session ->
            val cases = reference.getJSONArray("fixtures")
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val actual = JSONObject(session.shape(case.getString("font"), case.getString("text")))
                assertEquals(case.getInt("upem"), actual.getInt("upem"))
                val expectedGlyphs = case.getJSONArray("glyphs")
                val actualGlyphs = actual.getJSONArray("glyphs")
                assertEquals(case.getString("name"), expectedGlyphs.length(), actualGlyphs.length())
                for (glyph in 0 until expectedGlyphs.length()) {
                    for (key in listOf("g", "cl", "line", "ax", "ay", "dx", "dy")) {
                        assertEquals("${case.getString("name")} glyph $glyph $key", expectedGlyphs.getJSONObject(glyph).getInt(key), actualGlyphs.getJSONObject(glyph).getInt(key))
                    }
                }
            }
            assertEquals(session.shape("fluxx.sans", "AV"), session.shape("unknown.font", "AV"))
            assertEquals(0, JSONObject(session.shape("fluxx.sans", "")).getJSONArray("lines").length())
        }
    }

    @Test fun dumpSdfAndMeasureAtlasCapacity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "text-e1bc")
        FontSession(context.assets).use { session -> TextDebugBridge.dump(session, output) }
        val report = JSONObject(File(output, "report.json").readText())
        assertEquals(0, report.getJSONArray("rasterErrors").length())
        assertEquals(0L, report.getLong("repackRasterizations"))
        val capacities = report.getJSONArray("capacity")
        assertTrue(capacities.getJSONObject(0).getInt("packed") > 0)
        assertTrue(capacities.getJSONObject(1).getInt("packed") >= capacities.getJSONObject(0).getInt("packed"))
        for (size in listOf(1024, 2048)) {
            val bitmap = requireNotNull(BitmapFactory.decodeFile(File(output, "latin-atlas-$size.png").absolutePath))
            assertEquals(size, bitmap.width)
            assertEquals(size, bitmap.height)
            bitmap.recycle()
        }
        for (size in listOf(12, 14, 16, 72)) {
            assertTrue(File(output, "samples/ligature-$size-overlaps.png").length() > 0)
        }
    }
}
