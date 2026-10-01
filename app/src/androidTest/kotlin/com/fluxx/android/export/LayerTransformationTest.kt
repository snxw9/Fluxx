package com.fluxx.android.export

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fluxx.android.Layer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class LayerTransformationTest {
    @Test fun landscapeIsLetterboxedInPortraitComposition() {
        val effect = LayerTransformation(Layer())
        val size = effect.configure(1920, 1080)
        assertEquals(1080, size.width)
        assertEquals(1920, size.height)
        val m = effect.getGlMatrixArray(0)
        assertEquals(1f, m[0], 0.0001f)
        assertEquals(0.31640625f, m[5], 0.0001f)
    }

    @Test fun portraitFillsCompositionWithoutDistortion() {
        val effect = LayerTransformation(Layer())
        effect.configure(1080, 1920)
        val m = effect.getGlMatrixArray(0)
        assertEquals(1f, m[0], 0.0001f)
        assertEquals(1f, m[5], 0.0001f)
    }

    @Test fun positionAndRotationMatchDownwardPreviewCoordinates() {
        val effect = LayerTransformation(Layer(positionX = 0.25f, positionY = 0.5f, rotationDegrees = 90f))
        effect.configure(1080, 1920)
        val m = effect.getGlMatrixArray(0)
        assertEquals(0.25f, m[12], 0.0001f)
        assertEquals(-0.5f, m[13], 0.0001f)
        assertEquals(-1080f / 1920f, m[1], 0.0001f)
        assertEquals(1920f / 1080f, m[4], 0.0001f)
    }
}
