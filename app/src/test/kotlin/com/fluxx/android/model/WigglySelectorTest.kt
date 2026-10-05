package com.fluxx.android.model

import org.junit.Assert.*
import org.junit.Test
import com.fluxx.android.editor.*

class WigglySelectorTest {
    private fun p(value: Float)=AnimatableProperty1D(staticValue=value)
    private fun membership()=SelectorMembership(Array(4) { intArrayOf(0,1,2,3) },intArrayOf(4,4,4,4),intArrayOf(0,1,2,3),intArrayOf(1,2,3,4))
    private fun selector()=TextRangeSelector(2,kind=p(1f),minAmount=p(-100f),maxAmount=p(100f),seed=-1)
    private fun project(s: TextRangeSelector)=ProjectDocument(composition=Composition(layers=FrozenList.of(listOf(
        CompositionLayer(id=1,type=LayerType.TEXT,text=TextProperties(animators=FrozenList.of(listOf(TextAnimator(1,selectors=FrozenList.of(listOf(s)))))))
    ))))
    @Test fun randomAccessReverseAndZeroFrequencyAreDeterministic() {
        val s=selector();val engine=PreparedSelectorEngine(TextAnimator(1,selectors=FrozenList.of(listOf(s))),membership(),SelectorEaseTable.shared)
        val out=DoubleArray(4);engine.evaluate(456789,out);val expected=out.copyOf()
        for(t in longArrayOf(Long.MIN_VALUE,0,9_000_000,-5_000_000)) engine.evaluate(t,out)
        engine.evaluate(456789,out);assertArrayEquals(expected,out,0.0)
        val a=WigglySelectorMath.value(-1,3,0,0.0,40.0,12.0,13.0,1,false)
        assertEquals(a,WigglySelectorMath.value(-1,3,999999999,0.0,40.0,12.0,13.0,1,false),0.0)
        assertTrue(expected.all { it in -1.0..1.0 })
    }
    @Test fun correlationAndDimensionLockHaveExplicitEndpoints() {
        assertEquals(.9674077267653753,WigglySelectorMath.value(42,0,0,2.0,0.0,0.0,0.0,0,true),0.0)
        fun value(member: Int,dimension: Int,locked: Boolean,correlation: Double)=
            WigglySelectorMath.value(42,member,456789,3.0,correlation,0.0,20.0,dimension,locked)
        assertEquals(value(0,0,false,100.0),value(7,0,false,100.0),0.0)
        assertEquals(value(7,0,true,0.0),value(7,2,true,0.0),0.0)
        assertNotEquals(value(7,0,false,0.0),value(7,2,false,0.0),0.0)
        val t=500000L;val before=WigglySelectorMath.value(42,2,t-1,2.0,0.0,0.0,0.0,0,true)
        val after=WigglySelectorMath.value(42,2,t+1,2.0,0.0,0.0,0.0,0,true)
        assertEquals(before,after,1e-9)
    }
    @Test fun seedBitsKeysCodecRebaseAndNavigationRoundTrip() {
        val s=selector().copy(seedKeyframes=FrozenList.of(listOf(TextSeedKeyframe(0,Long.MIN_VALUE),TextSeedKeyframe(1_000_000,-1))))
        val original=project(s);assertEquals(original,ProjectCodec.decode(ProjectCodec.encode(original)))
        assertEquals(Long.MIN_VALUE,s.seedAt(999999));assertEquals(-1L,s.seedAt(1000000))
        assertEquals(-500000L,s.rebase(500000).seedKeyframes[0].timeUs)
        val editor=EditorViewModel();editor.dispatch(EditorAction.Load(original))
        val address=AnimatorTrackAddress(1,2,seed=true)
        editor.dispatch(EditorAction.EditTextAnimator(1,AnimatorEdit.Key(address,AnimatorKeyOperation.SET,2_000_000,seedValue=13)))
        assertTrue(TextAnimatorActions.keyTimes(editor.state.project.composition.layers[0],address).contains(2_000_000))
        editor.undo()
        assertEquals(original,editor.state.project)
    }
    @Test fun inactiveValuesDuplicateSeedKeysAndInvalidEnumsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { selector().copy(correlation=p(101f)) }
        assertThrows(IllegalArgumentException::class.java) { selector().copy(wigglesPerSecond=p(-1f)) }
        assertThrows(IllegalArgumentException::class.java) { selector().copy(kind=p(2f)) }
        assertThrows(IllegalArgumentException::class.java) { selector().copy(lockDimensions=p(.5f)) }
        assertThrows(IllegalArgumentException::class.java) { selector().copy(temporalPhase=p(Float.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { selector().copy(seedKeyframes=FrozenList.of(listOf(TextSeedKeyframe(0,1),TextSeedKeyframe(0,2)))) }
    }
    @Test fun warmedWigglyEvaluationAllocatesZeroOver600Frames() {
        val engine=PreparedSelectorEngine(TextAnimator(1,selectors=FrozenList.of(listOf(selector()))),membership(),SelectorEaseTable.shared)
        val out=DoubleArray(4)
        val bean=java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled=true
        for(i in 0 until 10000) engine.evaluate(i*1667L,out)
        val thread=Thread.currentThread().id;val before=bean.getThreadAllocatedBytes(thread)
        for(i in 0 until 600) engine.evaluate(i*1667L,out)
        assertEquals(0L,bean.getThreadAllocatedBytes(thread)-before)
    }
    @Test fun originalV11RangeTablesLoadWithoutNewFields() {
        val b=com.google.flatbuffers.FlatBufferBuilder(512)
        fun track(v: Float)=fluxx.schema.AnimatableProperty1D.createAnimatableProperty1D(b,false,v,0)
        val z=track(0f);val hundred=track(100f);val one=track(1f)
        val s=fluxx.schema.TextRangeSelector.createTextRangeSelector(b,2u,2u,one,z,hundred,z,z,z,z,hundred,z,z,z,hundred,z,hundred,z,ULong.MAX_VALUE)
        val selectors=fluxx.schema.TextAnimator.createSelectorsVector(b,intArrayOf(s));val name=b.createString("Animator")
        val a=fluxx.schema.TextAnimator.createTextAnimator(b,2u,0,0,1u,name,one,selectors,z)
        val groups=fluxx.schema.TextPayload.createTextAnimatorsVector(b,intArrayOf(a))
        b.finish(fluxx.schema.TextPayload.createTextPayload(b,0,0,0,0,1,groups,0,0,0,0,0,0))
        val bytes=b.sizedByteArray();val payload=fluxx.schema.TextPayload.getRootAsTextPayload(java.nio.ByteBuffer.wrap(bytes))
        val decoded=TextAnimatorCodec.decode(payload,bytes.size,11)[0].selectors[0]
        assertEquals(-1L,decoded.seed);assertEquals(0f,decoded.kind.staticValue,0f);assertTrue(decoded.seedKeyframes.isEmpty())
    }
}
