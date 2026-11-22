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
    /**
     * TEXT F3.2: the animated tracking total of a line is shifted by the alignment factor (0 left, .5 centre, 1 right),
     * like the Character tab's static tracking: centred text spreads ±total/2, left grows right, right grows left.
     * Line anchor stays an additional shift on top.
     */
    @Test fun trackingFollowsAlignmentAndLineAnchorShiftsWholeLine() {
        for(alignment in TextAlignment.entries) for(anchor in listOf(0f,50f,100f)) {
            val align=alignment.ordinal/2f
            val animation=evaluate(text(TextPropertyOverride(AnimatorProperty.TRACKING,scalar=scalar(100f)),
                TextPropertyOverride(AnimatorProperty.LINE_ANCHOR,scalar=scalar(anchor))).copy(alignment=alignment))
            assertEquals("$alignment anchor $anchor first",-anchor/100*10-align*10,animation.modifiers.getFloat(24),.0001f)
            assertEquals("$alignment anchor $anchor last",10-anchor/100*10-align*10,animation.modifiers.getFloat(PreparedTextAnimation.STRIDE+24),.0001f)
        }
    }
    @Test fun centredTrackingSpreadsSymmetricallyFromTheCentre() {
        for(amount in listOf(100f,-100f)) {
            val plain=evaluate(text())
            val tracked=evaluate(text(TextPropertyOverride(AnimatorProperty.TRACKING,scalar=scalar(amount))))
            val total=amount/10 // 100/1000 em at size 100, one inter-glyph boundary
            assertEquals(-total/2,tracked.modifiers.getFloat(24),.0001f)
            assertEquals(total/2,tracked.modifiers.getFloat(PreparedTextAnimation.STRIDE+24),.0001f)
            assertEquals(plain.inkBounds[0]+plain.inkBounds[2]/2,tracked.inkBounds[0]+tracked.inkBounds[2]/2,.001f)
            assertEquals(plain.inkBounds[2]+total,tracked.inkBounds[2],.001f)
        }
    }
    @Test fun animatedTrackingThroughARangeSelectorStaysAligned() {
        val keyed=AnimatableProperty1D(true,0f,FrozenList.of(listOf(Keyframe1D(0,0f,EasingPreset.LINEAR),Keyframe1D(1_000_000,100f,EasingPreset.LINEAR))))
        val base=text(TextPropertyOverride(AnimatorProperty.TRACKING,scalar=keyed))
        // Amount 50% weights the per-glyph tracking; the shift uses the same weighted total at that time.
        val half=base.copy(animators=FrozenList.of(listOf(base.animators[0].copy(selectors=FrozenList.of(listOf(TextRangeSelector(2,amount=scalar(50f))))))))
        for((text,weight) in listOf(base to 1f,half to .5f)) for(time in listOf(0L,250_000L,500_000L,1_000_000L)) {
            val a=PreparedTextAnimation(text,metrics(),SelectorEaseTable.shared).also { it.evaluate(time) }
            val total=time/1_000_000f*10*weight
            assertEquals("w $weight t $time",-total/2,a.modifiers.getFloat(24),.0001f)
            assertEquals("w $weight t $time",total/2,a.modifiers.getFloat(PreparedTextAnimation.STRIDE+24),.0001f)
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
        val overrides=AnimatorProperty.entries.take(23).map { p -> when(p.kind) {
            2 -> TextPropertyOverride(p,vector=AnimatableProperty2D(staticX=if(p==AnimatorProperty.SCALE) 101f else 1f,staticY=if(p==AnimatorProperty.SCALE) 99f else 1f))
            3 -> TextPropertyOverride(p,colour=AnimatableColour(staticValue=0x80ff0000.toInt()))
            else -> TextPropertyOverride(p,scalar=scalar(if(p==AnimatorProperty.OPACITY) 90f else 1f))
        } }
        val animator=TextAnimator(1,selectors=FrozenList.of(listOf(TextRangeSelector(2,offset=AnimatableProperty1D(true,0f,keys),
            shape=scalar(3f),easeLow=scalar(20f),easeHigh=scalar(-20f),randomizeOrder=scalar(1f),kind=scalar(1f),lockDimensions=scalar(0f)))),properties=FrozenList.of(overrides))
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
    @Test fun fillAndStrokeOpacityAndHsbRemainIndependent() {
        val source=text(TextPropertyOverride(AnimatorProperty.FILL_OPACITY,scalar=scalar(50f)),
            TextPropertyOverride(AnimatorProperty.STROKE_OPACITY,scalar=scalar(25f)),
            TextPropertyOverride(AnimatorProperty.FILL_HUE,scalar=scalar(120f)),
            TextPropertyOverride(AnimatorProperty.STROKE_HUE,scalar=scalar(120f))).copy(
                fill=AnimatableColour(staticValue=0x80ff0000.toInt()),strokeColour=AnimatableColour(staticValue=0x800000ff.toInt()))
        val prepared=evaluate(source)
        assertEquals(0f,prepared.modifiers.getFloat(32),0f);assertEquals(1f,prepared.modifiers.getFloat(36),0f)
        assertEquals(64/255f,prepared.modifiers.getFloat(44),0f)
        assertEquals(1f,prepared.modifiers.getFloat(48),0f);assertEquals(0f,prepared.modifiers.getFloat(52),0f)
        assertEquals(32/255f,prepared.modifiers.getFloat(60),0f)
        assertEquals(0x80ff0000.toInt(),source.fill.staticValue)
    }
    @Test fun groupingAlignmentOffsetsScalePivotAndAnchorIdentityRemainsMeaningful() {
        val base=text(TextPropertyOverride(AnimatorProperty.SCALE,vector=AnimatableProperty2D(staticX=200f,staticY=100f)),
            TextPropertyOverride(AnimatorProperty.GROUPING_ALIGNMENT,vector=AnimatableProperty2D(staticX=50f,staticY=0f)))
        val all=base.copy(animators=FrozenList.of(listOf(base.animators[0].copy(anchorGrouping=scalar(3f)))))
        assertEquals(-120f,evaluate(all).modifiers.getFloat(16),.001f)
    }
    @Test fun unlockedWigglyDimensionsDriveDifferentPositionComponents() {
        val base=text(TextPropertyOverride(AnimatorProperty.POSITION,vector=AnimatableProperty2D(staticX=10f,staticY=20f)))
        val selector=TextRangeSelector(2,kind=scalar(1f),lockDimensions=scalar(0f),correlation=scalar(0f),seed=42)
        val prepared=evaluate(base.copy(animators=FrozenList.of(listOf(base.animators[0].copy(selectors=FrozenList.of(listOf(selector)))))))
        assertNotEquals(prepared.modifiers.getFloat(16)/10,prepared.modifiers.getFloat(20)/20,.0001f)
        prepared.evaluate(500000);val expected=prepared.modifiers.getFloat(20)
        prepared.evaluate(1000000);prepared.evaluate(0);prepared.evaluate(500000)
        assertEquals(expected,prepared.modifiers.getFloat(20),0f)
    }
}
