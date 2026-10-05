package com.fluxx.android.model

import org.junit.Assert.*
import org.junit.Test

class PreparedTextAnimationTest {
    private fun scalar(v: Float)=AnimatableProperty1D(staticValue=v)
    /** Two unligated characters, one line, 600-unit advances; actual production metadata ABI. */
    private fun metrics(): DoubleArray = doubleArrayOf(1000.0,800.0,-200.0,0.0,1.0,2.0,1200.0,
        0.0,0.0,0.0,0.0,700.0,500.0,-700.0,
        0.0,600.0,0.0,0.0,700.0,500.0,-700.0,
        2.0,0.0,1.0,
        271828.0,2.0,2.0,1.0,
        0.0,1.0,0.0,0.0,0.0,600.0,1.0,2.0,0.0,0.0,600.0,600.0,
        0.0,0.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,1.0)
    private fun text(vararg properties: TextPropertyOverride)=TextProperties(size=scalar(100f),animators=FrozenList.of(listOf(
        TextAnimator(1,selectors=FrozenList.of(listOf(TextRangeSelector(2))),properties=FrozenList.of(properties.toList())))))
    private fun evaluate(text: TextProperties)=PreparedTextAnimation(text,metrics(),SelectorEaseTable.shared).also { it.evaluate(0) }
    @Test fun anchorOnlyMovesWholeGlyphAndBounds() {
        val plain=evaluate(text());val anchored=evaluate(text(TextPropertyOverride(AnimatorProperty.ANCHOR,vector=AnimatableProperty2D(staticX=10f,staticY=20f))))
        assertEquals(-10f,anchored.modifiers.getFloat(16),0f);assertEquals(-20f,anchored.modifiers.getFloat(20),0f)
        assertEquals(plain.inkBounds[0]-10,anchored.inkBounds[0],.001f)
        assertEquals(plain.inkBounds[1]-20,anchored.inkBounds[1],.001f)
    }
    @Test fun trackingAndLineAnchorShiftWholeLineWithoutRecursiveWidth() {
        for(anchor in listOf(0f,50f,100f)) {
            val animation=evaluate(text(TextPropertyOverride(AnimatorProperty.TRACKING,scalar=scalar(100f)),
                TextPropertyOverride(AnimatorProperty.LINE_ANCHOR,scalar=scalar(anchor))))
            assertEquals(-anchor/100*10,animation.modifiers.getFloat(24),.0001f)
            assertEquals(10-anchor/100*10,animation.modifiers.getFloat(PreparedTextAnimation.STRIDE+24),.0001f)
        }
    }
    @Test fun orderedMatricesGroupingAndNegativeSpatialInfluence() {
        val position=TextPropertyOverride(AnimatorProperty.POSITION,vector=AnimatableProperty2D(staticX=10f,staticY=0f))
        val base=text(position);val first=base.animators[0].copy(selectors=FrozenList.of(listOf(TextRangeSelector(2,mode=scalar(1f)))))
        val animation=evaluate(base.copy(animators=FrozenList.of(listOf(first))))
        assertEquals(-10f,animation.modifiers.getFloat(16),.001f)
        val scale=TextAnimator(3,anchorGrouping=scalar(3f),selectors=FrozenList.of(listOf(TextRangeSelector(4))),
            properties=FrozenList.of(listOf(TextPropertyOverride(AnimatorProperty.SCALE,vector=AnimatableProperty2D(staticX=200f,staticY=100f)))))
        val ordered=evaluate(base.copy(animators=FrozenList.of(listOf(base.animators[0],scale))))
        assertEquals(2f,ordered.modifiers.getFloat(0),0f);assertEquals(-40f,ordered.modifiers.getFloat(16),.001f)
    }
    @Test fun pivotsIncludeBaselineShiftAndSoftnessGetsRemainingBudget() {
        val scaled=text(TextPropertyOverride(AnimatorProperty.SCALE,vector=AnimatableProperty2D(staticX=100f,staticY=200f)))
        val base=evaluate(scaled);val shifted=evaluate(scaled.copy(baselineShift=scalar(20f)))
        assertEquals(base.inkBounds[1]-20,shifted.inkBounds[1],.001f)
        val soft=evaluate(text(TextPropertyOverride(AnimatorProperty.STROKE_WIDTH,scalar=scalar(10f)),
            TextPropertyOverride(AnimatorProperty.BLUR,scalar=scalar(50f))))
        assertEquals(10f,soft.modifiers.getFloat(76),0f);assertEquals(5f,soft.modifiers.getFloat(80),0f)
    }
    @Test fun hostPreparedEvaluationAllocatesZeroAfterWarmup() {
        val bean=java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported);bean.isThreadAllocatedMemoryEnabled=true
        val keys=FrozenList.of(listOf(Keyframe1D(0,-10f,EasingPreset.LINEAR),Keyframe1D(1_000_000,10f,EasingPreset.EASY_EASE)))
        val overrides=AnimatorProperty.entries.take(13).map { p -> when(p.kind) {
            2 -> TextPropertyOverride(p,vector=AnimatableProperty2D(staticX=if(p==AnimatorProperty.SCALE) 101f else 1f,staticY=if(p==AnimatorProperty.SCALE) 99f else 1f))
            3 -> TextPropertyOverride(p,colour=AnimatableColour(staticValue=0x80ff0000.toInt()))
            else -> TextPropertyOverride(p,scalar=scalar(if(p==AnimatorProperty.OPACITY) 90f else 1f))
        } }
        val animator=TextAnimator(1,selectors=FrozenList.of(listOf(TextRangeSelector(2,offset=AnimatableProperty1D(true,0f,keys),
            shape=scalar(3f),easeLow=scalar(20f),easeHigh=scalar(-20f),randomizeOrder=scalar(1f)))),properties=FrozenList.of(overrides))
        val prepared=evaluate(TextProperties(size=scalar(100f),animators=FrozenList.of(listOf(animator))))
        for(i in 0 until 10_000) prepared.evaluate((i%600)*1667L)
        val id=Thread.currentThread().id;val before=bean.getThreadAllocatedBytes(id)
        for(i in 0 until 600) prepared.evaluate(i*1667L)
        val delta=bean.getThreadAllocatedBytes(id)-before
        assertEquals("Host JVM allocation proof does not replace the ART/native device counters",0L,delta)
    }
    @Test fun finiteRequestsThatOverflowGeometryRejectInsteadOfEmittingInfinity() {
        val source=text(TextPropertyOverride(AnimatorProperty.SCALE,vector=AnimatableProperty2D(staticX=Float.MAX_VALUE,staticY=Float.MAX_VALUE)))
            .copy(size=scalar(500f))
        try { evaluate(source);fail("Finite requests cannot emit nonfinite geometry") } catch(expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("finite"))
        }
    }
    @Test fun strokeBlurColourAndOpacityStayPerGlyphAndShareBudget() {
        val base=text(TextPropertyOverride(AnimatorProperty.STROKE_WIDTH,scalar=scalar(50f)),
            TextPropertyOverride(AnimatorProperty.BLUR,scalar=scalar(50f)),
            TextPropertyOverride(AnimatorProperty.FILL,colour=AnimatableColour(staticValue=0x800000ff.toInt())),
            TextPropertyOverride(AnimatorProperty.OPACITY,scalar=scalar(50f)))
        val a=base.animators[0].copy(selectors=FrozenList.of(listOf(TextRangeSelector(2,units=scalar(1f),end=scalar(1f),smoothness=scalar(0f)))))
        val animation=evaluate(base.copy(animators=FrozenList.of(listOf(a))))
        assertEquals(20f,animation.modifiers.getFloat(76),0f);assertEquals(0f,animation.modifiers.getFloat(80),0f)
        assertEquals(.5f,animation.modifiers.getFloat(72),0f);assertEquals(128/255f,animation.modifiers.getFloat(44),.0001f)
        assertEquals(0f,animation.modifiers.getFloat(96+76),0f);assertEquals(1f,animation.modifiers.getFloat(96+72),0f)
        assertEquals(50f,base.animators[0].properties[0].scalar!!.staticValue,0f)
    }
    @Test fun preparedEvaluationIsDeterministicUnderReverseSeekingAndDisabledGroups() {
        val p=AnimatableProperty1D(true,0f,FrozenList.of(listOf(Keyframe1D(0,0f,EasingPreset.LINEAR),Keyframe1D(1_000_000,20f,EasingPreset.LINEAR))))
        val source=text(TextPropertyOverride(AnimatorProperty.ROTATION,scalar=p))
        val animation=evaluate(source);animation.evaluate(500_000)
        val expected=ByteArray(animation.modifiers.capacity());animation.modifiers.duplicate().get(expected)
        animation.evaluate(1_000_000);animation.evaluate(0);animation.evaluate(500_000)
        val actual=ByteArray(expected.size);animation.modifiers.duplicate().get(actual);assertArrayEquals(expected,actual)
        val disabled=evaluate(source.copy(animators=FrozenList.of(listOf(source.animators[0].copy(enabled=scalar(0f))))))
        assertEquals(1f,disabled.modifiers.getFloat(0),0f);assertEquals(0L,disabled.coverageBuilds)
        val parsed=TextLayoutMetrics.fromNative(metrics(),false)
        assertTrue(parsed.bounds(source,500_000).width>0)
    }
}
