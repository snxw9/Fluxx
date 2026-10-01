package com.fluxx.android.model

import org.junit.Assert.*
import org.junit.Test

class CompositionCreationTest {
    @Test fun oddDimensionsAreRejectedWithoutRounding() {
        val draft = CompositionDraft(width = "1081", height = "1920")
        assertTrue(runCatching { draft.request() }.isFailure)
        assertEquals("1081", draft.width)
    }

    @Test fun dimensionsAndDurationMustFitBackendContracts() {
        listOf(
            CompositionDraft(width = "0"), CompositionDraft(height = "-2"),
            CompositionDraft(width = "1920.0"), CompositionDraft(width = "3840", height = "2160"),
            CompositionDraft(duration = "0"), CompositionDraft(duration = "NaN"),
            CompositionDraft(duration = "9223372036854775807"), CompositionDraft(fps = 121)
        ).forEach { assertTrue("Should reject $it", runCatching { it.request() }.isFailure) }
    }

    @Test fun portraitPresetAndMicrosecondDurationAreExact() {
        val result = CompositionDraft(duration = "0.000001", fps = 60).withAspect("9:16").request()
        assertEquals(1080, result.width)
        assertEquals(1920, result.height)
        assertEquals(1L, result.durationUs)
        assertEquals(60, result.fps)
    }

    @Test fun builtinPresetsRemainWithinExportAreaAtEveryResolution() {
        for (ratio in listOf("16:9", "9:16", "1:1", "4:3", "4:5")) {
            for (resolution in listOf(1080, 720, 540)) {
                val result = CompositionDraft().withAspect(ratio, resolution).request()
                assertEquals(0, result.width % 2)
                assertEquals(0, result.height % 2)
                assertTrue(result.width.toLong() * result.height <= 1920L * 1080)
            }
        }
    }
}
