package com.fluxx.android.render

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * TEXT FIX 9d: the per-frame tier path is allocation-free (the A16 F1 window measured ~90 objects per frame from it:
 * HashMap/ArrayList/Request objects, Int boxing, `filter`/`lastOrNull`, FrameRate arithmetic and a default lambda).
 * The primitive forms must decide exactly like the original API, which keeps its signature for existing callers.
 */
class PerFrameTierPathTest {
    private val ppems = intArrayOf(48, 96, 192, 384)
    private fun src(path: String) = File(path).readText()

    @Test fun selectTierEqualsSelectForEveryInput() {
        val currents = listOf<Int?>(null, 48, 96, 192, 384, 7)
        for (px in listOf(-1f, 0f, Float.NaN, 10f, 72f, 73f, 83f, 90f, 144f, 166f, 250f, 288f, 331f, 600f, 1000f, 5000f))
            for (current in currents) for (distance in listOf(0f, .02f, .05f, .1f, .2f))
                assertEquals("px=$px current=$current d=$distance", SdfAtlasTiers.select(px, current, distance),
                    SdfAtlasTiers.selectTier(px, current ?: SdfAtlasTiers.NO_TIER, distance))
    }
    @Test fun loopFormsMatchTheTableDefinitions() {
        for (p in listOf(1, 48, 50, 96, 100, 192, 300, 384, 1000))
            assertEquals(ppems.lastOrNull { it < p } ?: 48, SdfAtlasTiers.lower(p))
        for (px in listOf(0f, 50f, 72f, 72.5f, 144f, 145f, 288f, 289f, 576f, 577f, 10_000f))
            assertEquals(ppems.firstOrNull { px <= SdfAtlasTiers.sharpLimit(it) } ?: 384, SdfAtlasTiers.ideal(px))
        for (d in listOf(0f, .05f, .07f, .1f, .156f, .166f, .5f))
            assertEquals(ppems.filter { SdfAtlasTiers.encodableEm(it) >= d + SdfAtlasTiers.AA_MARGIN_EM }.maxOrNull() ?: 48, SdfAtlasTiers.maxTierFor(d))
    }
    @Test fun degradeInPlaceMatchesDegrade() {
        val cases = listOf(
            listOf(384 to 400, 192 to 300, 96 to 50),
            listOf(384 to 4000, 384 to 4000),
            listOf(48 to 100000),
            listOf(192 to 10, 96 to 10))
        for (lowRam in listOf(false, true)) for (case in cases) {
            val requests = case.mapIndexed { i, (ppem, glyphs) -> SdfAtlasTiers.Request(i.toLong() + 1, ppem, glyphs) }
            val expected = SdfAtlasTiers.degrade(requests, SdfAtlasTiers.capBytes(lowRam))
            val ppemsIn = IntArray(case.size) { case[it].first }
            SdfAtlasTiers.degradeInPlace(ppemsIn, IntArray(case.size) { case[it].second }, case.size, SdfAtlasTiers.capBytes(lowRam))
            assertEquals(case.indices.associate { it.toLong() + 1 to ppemsIn[it] }, expected)
        }
    }
    @Test fun theFramePathUsesOnlyPreparedState() {
        val renderer = src("src/main/kotlin/com/fluxx/android/render/CompositionRenderer.kt")
        val body = renderer.substringAfter("private fun applyTextTiers(").substringBefore("\n    private var previewQuality")
        for (allocating in listOf("HashMap", "ArrayList", "SdfAtlasTiers.Request(", ".toSet()", "filterNot", "takeIf", ".map {", "toLongArray", "IntArray(", "textTierByLayer"))
            assertFalse("per-frame tier path must not use $allocating", body.contains(allocating))
        assertTrue(body.contains("SdfAtlasTiers.degradeInPlace(") && body.contains("SdfAtlasTiers.selectTier("))
        assertTrue("native call only when a handle's tier changes", body.contains("if (!tierHandleTiers.contentEquals(tierSent))"))
        assertTrue("no imported-tier JNI call while every layer is on the base tier", body.contains("if (!sharp) return"))
        // The settle check caches the frame span; render defaults share one lambda.
        assertTrue(renderer.contains("if (comp.frameRate != streamingRate)"))
        assertFalse(renderer.contains("cancelled: () -> Boolean = { false }"))
        // JNI hands back null (not an empty array) when nothing is requested.
        assertTrue(src("../core-engine/src/main/cpp/jni_bridge.cpp").contains("if(bytes.empty()) return nullptr;"))
        assertTrue(src("../core-engine/src/main/kotlin/com/fluxx/android/engine/RenderBridge.kt").contains("external fun textTierRequests(session: Long): ByteArray?"))
    }
}
