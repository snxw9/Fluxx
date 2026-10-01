package com.fluxx.android.model

import com.fluxx.android.render.PreviewResolution
import com.fluxx.android.ui.preview.ViewSettings
import org.junit.Assert.*
import org.junit.Test

class PreviewSettingsTest {
    @Test fun editingTemporarilyOverridesPassiveAnchorSuppression() {
        val settings = ViewSettings(showAnchorPoints = false)
        assertFalse(settings.anchorVisible(false))
        assertTrue(settings.anchorVisible(true))
        assertFalse(settings.showAnchorPoints)
        assertFalse(settings.anchorVisible(false))
    }

    @Test fun qualityDimensionsAreEvenAndNeverZero() {
        assertEquals(1080 to 1920, PreviewResolution.FULL.calculateDimensions(1080, 1920))
        assertEquals(540 to 960, PreviewResolution.HALF.calculateDimensions(1080, 1920))
        assertEquals(360 to 640, PreviewResolution.THIRD.calculateDimensions(1080, 1920))
        assertEquals(270 to 480, PreviewResolution.QUARTER.calculateDimensions(1080, 1920))
        for (quality in PreviewResolution.entries) for ((w, h) in listOf(1081 to 1921, 1 to 1, 3 to 5)) {
            val (width, height) = quality.calculateDimensions(w, h)
            assertTrue(width >= 2 && height >= 2)
            assertEquals(0, width % 2); assertEquals(0, height % 2)
        }
        assertThrows(IllegalArgumentException::class.java) { PreviewResolution.HALF.calculateDimensions(0, 10) }
    }

    @Test fun fitAndActualSizeAreDifferentAndResetPan() {
        val fit = ViewSettings()
        assertEquals(1f, fit.viewportScale(.25f), 0f)
        assertEquals(4f, fit.actualSize().viewportScale(.25f), 0f)
        assertEquals(1.25f, fit.zoomBy(1.25f, .25f).viewportScale(.25f), .00001f)
        val panned = fit.copy(panX = 60f, panY = -30f, zoomScale = 2f)
        assertEquals(fit, panned.fit())
        assertEquals(fit.actualSize(), panned.actualSize())
    }

    @Test fun eyedropperInverseMatchesViewportScaleAndPan() {
        val width = 360f; val height = 280f
        for (zoom in listOf(.125f, .25f, 1f, 2f)) {
            val settings = ViewSettings(zoomScale = zoom, panX = 10f, panY = -20f)
            val scale = settings.viewportScale(.25f)
            for ((x, y) in listOf(0f to 0f, 180f to 140f, 300f to 250f)) {
                val screenX = (x - width / 2) * scale + width / 2 + settings.panX
                val screenY = (y - height / 2) * scale + height / 2 + settings.panY
                val mapped = settings.unzoom(screenX, screenY, width, height, .25f)
                assertEquals(x, mapped.first, .0001f); assertEquals(y, mapped.second, .0001f)
            }
        }
    }
}
