package com.fluxx.android.model

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

/** Independent JVM arithmetic oracle; production calls the bounded native borrowed-buffer kernel. */
internal object ParagraphOracle: ParagraphWrapKernel {
    override fun wrap(clusters: ByteBuffer,paragraphs: ByteBuffer,positions: ByteBuffer,lines: ByteBuffer,
        count: Int,paragraphCount: Int,width: Double,step: Double,lineCapacity: Int): Int {
        var used=0;var top=0.0
        for(p in 0 until paragraphCount) {
            val pr=p*56;val end=paragraphs.getInt(pr+4);var start=paragraphs.getInt(pr);var first=true
            if(p>0) top+=paragraphs.getDouble(pr+40)
            do {
                require(used<lineCapacity) { "256 lines" }
                val left=paragraphs.getDouble(pr+16)+if(first) paragraphs.getDouble(pr+32) else 0.0
                val available=maxOf(0.0,width-left-paragraphs.getDouble(pr+24))
                var stop=end;var accumulated=0.0;var candidate=start
                if(width>0) for(i in start until end) {
                    accumulated+=clusters.getDouble(i*24)+if(i>start) clusters.getDouble((i-1)*24+16) else 0.0
                    if(accumulated>available && clusters.getInt(i*24+8)==0) { stop=if(candidate>start) candidate else if(i>start) i else i+1;break }
                    if(clusters.getInt(i*24+12)!=0) candidate=i+1
                }
                var ink=stop;while(ink>start&&clusters.getInt((ink-1)*24+8)!=0) ink--
                var advance=0.0;var spaces=0
                for(i in start until ink) { advance+=clusters.getDouble(i*24)+if(i>start) clusters.getDouble((i-1)*24+16) else 0.0;if(clusters.getInt(i*24+8)!=0) spaces++ }
                val a=paragraphs.getInt(pr+8)
                val justified=width>0&&spaces>0&&(a==6||(stop!=end&&a>=3))
                val expansion=if(justified) maxOf(0.0,(available-advance)/spaces) else 0.0
                advance+=spaces*expansion
                val align=if(a>=3) if(a==6) 0 else a-3 else a
                val origin=left+if(width>0&&!justified) (available-advance)*(if(align==1) .5 else if(align==2) 1.0 else 0.0) else 0.0
                var x=origin
                for(i in start until stop) {
                    positions.putDouble(i*8,x)
                    x+=clusters.getDouble(i*24)+if(i+1<stop) clusters.getDouble(i*24+16) else 0.0
                    if(i<ink&&clusters.getInt(i*24+8)!=0) x+=expansion
                }
                val r=used++*48
                lines.putInt(r,start);lines.putInt(r+4,stop);lines.putInt(r+8,p)
                lines.putDouble(r+16,origin);lines.putDouble(r+24,advance);lines.putDouble(r+32,top);lines.putDouble(r+40,expansion)
                start=stop;top+=step;first=false
            } while(start<end)
            top+=paragraphs.getDouble(pr+48)
        }
        return used
    }
}

class PreparedParagraphLayoutTest {
    private fun scalar(v: Float)=AnimatableProperty1D(staticValue=v)
    private fun metrics(count: Int): DoubleArray {
        val out=ArrayList<Double>()
        out.addAll(listOf(1000.0,800.0,-200.0,0.0,1.0,count.toDouble(),count*600.0))
        for(i in 0 until count) out.addAll(listOf(0.0,i*600.0,0.0,0.0,700.0,500.0,-700.0))
        out.add(count.toDouble());for(i in 0 until count) out.add(i.toDouble())
        out.addAll(listOf(271828.0,count.toDouble(),count.toDouble(),1.0))
        for(i in 0 until count) out.addAll(listOf(i.toDouble(),i+1.0,0.0,0.0,i*600.0,600.0))
        for(i in 0 until count) out.addAll(listOf(0.0,0.0,i.toDouble(),0.0))
        for(i in 0 until count) out.add(i.toDouble())
        return out.toDoubleArray()
    }
    @Test fun animatedSizeReflowsBeforeLinesSelectorAndMovesBounds() {
        val source=TextSourceDocument("AB",boxWidth=100f)
        val keys=FrozenList.of(listOf(Keyframe1D(0,50f,EasingPreset.LINEAR),Keyframe1D(10,100f,EasingPreset.LINEAR)))
        val animator=TextAnimator(1,selectors=FrozenList.of(listOf(TextRangeSelector(2,basedOn=scalar(3f),units=scalar(1f),start=scalar(1f),end=scalar(2f),smoothness=scalar(0f)))),
            properties=FrozenList.of(listOf(TextPropertyOverride(AnimatorProperty.POSITION,vector=AnimatableProperty2D(staticX=10f,staticY=0f)))))
        val text=TextProperties(alignment=TextAlignment.LEFT,size=AnimatableProperty1D(true,50f,keys),animators=FrozenList.of(listOf(animator)))
        val prepared=PreparedTextAnimation(text,metrics(2),SelectorEaseTable.shared,source,breaks=intArrayOf(0,2),paragraphKernel=ParagraphOracle)
        prepared.evaluate(0);assertEquals(50f,prepared.logicalBounds[3],.001f);assertEquals(0f,prepared.modifiers.getFloat(96+16),0f)
        prepared.evaluate(10);assertEquals(200f,prepared.logicalBounds[3],.001f)
        assertEquals(-60f,prepared.modifiers.getFloat(96+24),.001f);assertEquals(100f,prepared.modifiers.getFloat(96+28),.001f)
        assertEquals(10f,prepared.modifiers.getFloat(96+16),.001f)
        prepared.evaluate(0);assertEquals(50f,prepared.logicalBounds[3],.001f)
    }
    @Test fun cachedLinePermutationsSurviveMembershipChangesAndShareSeeds() {
        val maps=Array(4) { intArrayOf(0,1) }
        val membership=SelectorMembership(maps,intArrayOf(2,2,2,2),intArrayOf(0,1),intArrayOf(1,2))
        val selector=TextRangeSelector(2,basedOn=scalar(3f),randomizeOrder=scalar(1f),seed=-1)
        val pool=LinePermutationPool()
        val engine=PreparedSelectorEngine(TextAnimator(1,selectors=FrozenList.of(listOf(selector))),membership,SelectorEaseTable.shared,pool,1,3)
        val out=DoubleArray(2);engine.evaluate(0,out);val original=out.copyOf();val builds=pool.builds
        membership.updateLines(intArrayOf(0,0),1);engine.evaluate(0,out)
        membership.updateLines(intArrayOf(0,1),2);engine.evaluate(0,out)
        assertArrayEquals(original,out,0.0);assertEquals(builds,pool.builds)
        pool.prepare(-1,1,3);assertEquals(builds,pool.builds);assertEquals(24L,pool.bytes)
    }
    @Test fun wrappingAndRandomLinesAllocateZeroOver600Frames() {
        val count=200;val source=TextSourceDocument("A".repeat(count),boxWidth=8f)
        val selector=TextRangeSelector(2,basedOn=scalar(3f),randomizeOrder=scalar(1f),seed=17)
        val keys=FrozenList.of(listOf(Keyframe1D(0,10f,EasingPreset.LINEAR),Keyframe1D(1_000_000,11f,EasingPreset.LINEAR)))
        val text=TextProperties(size=AnimatableProperty1D(true,10f,keys),animators=FrozenList.of(listOf(TextAnimator(1,selectors=FrozenList.of(listOf(selector))))))
        val prepared=PreparedTextAnimation(text,metrics(count),SelectorEaseTable.shared,source,breaks=intArrayOf(0,count),paragraphKernel=ParagraphOracle)
        val bean=java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled=true
        for(i in 0 until 10_000) prepared.evaluate((i%600)*1667L)
        val id=Thread.currentThread().id;val before=bean.getThreadAllocatedBytes(id)
        for(i in 0 until 600) prepared.evaluate(i*1667L)
        assertEquals(0L,bean.getThreadAllocatedBytes(id)-before)
    }
    @Test fun paragraphInsertionInheritsCaretRatherThanShiftingSuffixStyles() {
        val source=TextSourceDocument("a\nb\nc").formatParagraphs(2,3) { it.copy(indentLeft=9f) }
        val inserted=source.replace("a\nb\nX\nc",3,3)
        assertEquals(listOf(0f,9f,9f,0f),inserted.paragraphs.map { it.indentLeft })
    }
}
