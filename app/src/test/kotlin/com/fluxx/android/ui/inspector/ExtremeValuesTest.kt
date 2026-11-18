package com.fluxx.android.ui.inspector

import com.fluxx.android.model.*
import com.fluxx.android.render.LayerGeometry
import org.junit.Assert.*
import org.junit.Test

/** Font size 1000 and layer scale up to ±500x: no NaN, overflow or unbounded SDF stroke. JVM arithmetic only. */
class ExtremeValuesTest {
    @Test fun fontSizeThousandIsValidAndClampedBeyond() {
        TextLimits.validateSize(1000f)
        assertThrows(IllegalArgumentException::class.java) {TextLimits.validateSize(1000.5f)}
        assertEquals(1000f,TextLimits.decodeSize(5000f),0f)
        val layer=CompositionLayer(1,LayerType.TEXT,text=TextProperties(source=AnimatableString(staticValue="W")))
        val d=TextNumericDraft(layer,0,FrameRate(),TextEditScope(null),TextControl.SIZE)
        assertEquals(1000f,d.payload(d.scrubValue(1e9f)).size.staticValue,0f)
        // The SDF stroke budget scales with size; it stays finite and never exceeds the hard cap of the request.
        val stroke=TextSdfLimits.strokeWidth(PropertyLimits.STROKE_WIDTH.hardMax,1000f)
        assertTrue(stroke.isFinite() && stroke<=PropertyLimits.STROKE_WIDTH.hardMax)
        assertTrue(TextSdfLimits.softness(10f,1000f,stroke).isFinite())
        assertEquals(1000f/48f,TextSdfLimits.aaPadding(1000f),1e-3f)
    }
    @Test fun layerScaleFiveHundredKeepsGeometryFinite() {
        val bounds=TextBounds(-500f,-900f,12000f,1200f) // a long line at 1000px
        val out=FloatArray(16)
        for(scale in listOf(500f,-500f,.0005f)) for(rotation in listOf(0f,36000f,-36000f,45f)) {
            val t=Transform(positionX=10f,positionY=-10f,scaleX=scale,scaleY=scale,rotationDegrees=rotation)
            LayerGeometry.textMatrix(out,t,bounds,1920,1080)
            assertTrue("$scale $rotation",out.all {it.isFinite()})
            LayerGeometry.textContains(out,bounds,0f,0f) // must not throw
            val moved=LayerGeometry.compensateTextAnchor(t,bounds,.5f,.5f,0f,1f,1920,1080)
            assertTrue(moved.positionX.isFinite() && moved.positionY.isFinite())
        }
        // Hit testing still resolves at a large scale.
        LayerGeometry.textMatrix(out,Transform(scaleX=500f,scaleY=500f),TextBounds(-1f,-1f,2f,2f),1920,1080)
        assertTrue(LayerGeometry.textContains(out,TextBounds(-1f,-1f,2f,2f),out[12],out[13]))
    }
    @Test fun rulerAndTypedCapsHoldAtTheExtremes() {
        assertTrue(ScaleRulerMath.drag(500f,10f).isFinite())
        assertTrue(ScaleRulerMath.drag(-500f,-10f).isFinite())
        assertEquals(500f,PropertyLimits.LAYER_SCALE.typed(1e12f)/100f,0f)
        assertEquals(36000f,PropertyLimits.ROTATION.typed(Float.MAX_VALUE),0f)
        assertEquals(-19200f,PropertyLimits.position(1920).hard(-Float.MAX_VALUE),0f)
        // Rotation from the dial is never wrapped: 5 turns stays 1800 degrees.
        assertEquals(1800f,PropertyLimits.ROTATION.hard(1800f),0f)
    }
}
