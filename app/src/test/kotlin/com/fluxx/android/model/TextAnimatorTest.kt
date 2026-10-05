package com.fluxx.android.model

import com.fluxx.android.editor.*
import com.google.flatbuffers.FlatBufferBuilder
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class TextAnimatorTest {
    private fun p(v: Float)=AnimatableProperty1D(staticValue=v)
    private fun track(a: Float,b: Float,ease: EasingPreset=EasingPreset.LINEAR)=AnimatableProperty1D(true,a,
        FrozenList.of(listOf(Keyframe1D(0,a,ease),Keyframe1D(1_000_000,b,ease))))
    private val ease=SelectorEaseTable()
    private fun membership(n: Int=4)=SelectorMembership(Array(4) { IntArray(n) { it } },IntArray(4) { n },IntArray(n) { it },IntArray(n) { it+1 })
    private fun evaluate(s: TextRangeSelector,n: Int=4,time: Long=0): DoubleArray {
        val out=DoubleArray(n);PreparedSelectorEngine(TextAnimator(1,selectors=FrozenList.of(listOf(s))),membership(n),ease).evaluate(time,out);return out
    }
    private fun project(a: TextAnimator)=ProjectDocument(Composition(durationUs=2_000_000,layers=FrozenList.of(listOf(
        CompositionLayer(1,LayerType.TEXT,timing=ClipTiming(durationUs=2_000_000),text=TextProperties(animators=FrozenList.of(listOf(a))))))))

    @Test fun ligatureHoldPropertyAndTrackingZeroCrossingSelectPreparedModes() {
        val text=TextProperties(tracking=track(-100f,100f),ligatures=track(1f,0f))
        assertFalse(text.effectiveLigatures(0));assertTrue(text.effectiveLigatures(500_000));assertFalse(text.effectiveLigatures(500_001))
        assertFalse(text.effectiveLigatures(1_000_000));assertEquals(1f,text.ligatures.hold(999_999),0f)
        val doc=ProjectDocument(Composition(durationUs=2_000_000,layers=FrozenList.of(listOf(CompositionLayer(1,LayerType.TEXT,
            timing=ClipTiming(durationUs=2_000_000),text=text)))))
        assertEquals(doc,ProjectCodec.decode(ProjectCodec.encode(doc)))
        val split=EditorReducer.reduce(EditorState(doc,1),EditorAction.Split(1,500_000)).project.composition.layers.first { it.id!=1L }
        assertTrue(split.text.effectiveLigatures(0));assertFalse(split.text.effectiveLigatures(1))
        val editor=EditorViewModel();editor.dispatch(EditorAction.Load(doc));editor.dispatch(EditorAction.Select(1))
        assertTrue(editor.copyLayer(1));editor.duplicateLayer(1);assertEquals(text.ligatures,editor.state.selectedLayer!!.text.ligatures)
        editor.dispatch(EditorAction.Seek(500_000));editor.pasteLayer();assertEquals(text.ligatures,editor.state.selectedLayer!!.text.ligatures)
        assertThrows(IllegalArgumentException::class.java) { TextProperties(ligatures=p(.5f)) }
        // Animator tracking never participates in effective layout mode.
        val animator=TextAnimator(7,properties=FrozenList.of(listOf(TextPropertyOverride(AnimatorProperty.TRACKING,scalar=p(100f)))))
        assertTrue(TextProperties(animators=FrozenList.of(listOf(animator))).effectiveLigatures(0))
    }

    @Test fun allMembershipVariantsAndSingleMemberUseTheirOwnDomains() {
        val maps=arrayOf(intArrayOf(0,1,2,3),intArrayOf(0,-1,1,2),intArrayOf(0,-1,1,1),intArrayOf(0,0,1,1))
        val m=SelectorMembership(maps,intArrayOf(4,3,2,3),intArrayOf(0,1,2,3),intArrayOf(1,2,3,4)) // trailing empty line counts
        for(v in 0..3) {
            val a=TextAnimator(1,selectors=FrozenList.of(listOf(TextRangeSelector(2,basedOn=p(v.toFloat()),units=p(1f),end=p(1f),smoothness=p(0f)))))
            val out=DoubleArray(4);PreparedSelectorEngine(a,m,ease).evaluate(0,out)
            assertArrayEquals(DoubleArray(4) { if(maps[v][it]==0) 1.0 else 0.0 },out,0.0)
        }
        assertArrayEquals(doubleArrayOf(1.0),evaluate(TextRangeSelector(2),n=1),0.0)
    }

    @Test fun splitMixKnownVectorsAndSeedBitsAreStable() {
        val rng=SplitMix64(0)
        assertEquals(0xE220A8397B1DCDAFuL,rng.next())
        assertEquals(0x6E789E6AA1B965F4uL,rng.next())
        assertEquals(0x06C45D188009454FuL,rng.next())
        assertArrayEquals(intArrayOf(2,6,0,3,4,1,5,7),SelectorMath.permutation(8,0))
        assertArrayEquals(SelectorMath.permutation(64,Long.MIN_VALUE),SelectorMath.permutation(64,Long.MIN_VALUE))
        assertNotEquals(SelectorMath.permutation(64,0).toList(),SelectorMath.permutation(64,1).toList())
        assertEquals((0 until 64).toList(),SelectorMath.permutation(64,0).sorted())
        assertArrayEquals(intArrayOf(),SelectorMath.permutation(0,0));assertArrayEquals(intArrayOf(0),SelectorMath.permutation(1,0))
    }
    @Test fun boundariesUnitsOffsetsAndEmptyRanges() {
        val s=TextRangeSelector(2,smoothness=p(0f),end=p(25f))
        assertArrayEquals(doubleArrayOf(1.0,0.0,0.0,0.0),evaluate(s),0.0)
        assertArrayEquals(evaluate(s),evaluate(s.copy(units=p(1f),end=p(1f))),0.0)
        assertArrayEquals(doubleArrayOf(0.0,1.0,0.0,0.0),evaluate(s.copy(offset=p(25f))),0.0)
        assertArrayEquals(DoubleArray(4),evaluate(s.copy(start=p(100f),end=p(0f))),0.0)
        assertArrayEquals(DoubleArray(4),evaluate(s.copy(start=p(25f))),0.0)
        assertArrayEquals(DoubleArray(4),evaluate(s.copy(offset=p(200f))),0.0)
        assertArrayEquals(doubleArrayOf(.5,0.0,0.0,0.0),evaluate(s.copy(end=p(12.5f),smoothness=p(100f))),1e-12)
        assertEquals(0,evaluate(s,n=0).size)
    }
    @Test fun everyShapeHasSpecifiedProfileAndBoundaries() {
        val s=TextRangeSelector(2,smoothness=p(0f))
        for(shape in SelectorShape.entries) {
            val values=evaluate(s.copy(shape=p(shape.ordinal.toFloat())))
            for(i in values.indices) assertEquals(SelectorMath.shape(shape,(i+.5)/4),values[i],1e-12)
        }
        assertEquals(0.0,SelectorMath.shape(SelectorShape.TRIANGLE,0.0),0.0)
        assertEquals(1.0,SelectorMath.shape(SelectorShape.ROUND,.5),0.0)
        assertEquals(0.0,SelectorMath.shape(SelectorShape.SMOOTH,1.0),0.0)
    }
    @Test fun easeIdentityAndLookupApproximationAreBounded() {
        var maximum=0.0
        for(low in listOf(-100.0,-73.5,0.0,41.25,100.0)) for(high in listOf(-100.0,-62.5,0.0,58.75,100.0)) {
            assertEquals(0.0,ease.evaluate(0.0,low,high),0.0);assertEquals(1.0,ease.evaluate(1.0,low,high),0.0)
            for(i in 1..999) {
                val t=i/1000.0;val a=t.pow(2.0.pow(low/100));val b=(1-t).pow(2.0.pow(high/100))
                maximum=maxOf(maximum,kotlin.math.abs(ease.evaluate(t,low,high)-a/(a+b)))
                if(low==0.0 && high==0.0) assertEquals(t,ease.evaluate(t,low,high),0.0)
            }
        }
        // Near-zero/one square-root endpoints need a declared absolute approximation tolerance.
        assertTrue("LUT absolute error $maximum",maximum<.017)
    }
    @Test fun modesIncludeIntendedFirstSubtractAndSignedAmounts() {
        val expected=doubleArrayOf(.75,.25,.125,.25,.5,.25)
        for(mode in SelectorMode.entries) assertEquals(expected[mode.ordinal],SelectorMath.combine(.5,.25,mode),0.0)
        assertEquals(-1.0,evaluate(TextRangeSelector(2,mode=p(1f)))[0],0.0)
        assertEquals(-.5,evaluate(TextRangeSelector(2,amount=p(-50f)))[0],0.0)
        assertEquals(0.0,evaluate(TextRangeSelector(2,amount=p(0f)))[0],0.0)
    }
    @Test fun crossingMinMaxAreAcceptedAndNormalizedAtEvaluation() {
        val s=TextRangeSelector(2,minAmount=track(49f,100f),maxAmount=track(50f,100f,EasingPreset.EASY_EASE))
        assertTrue(s.minAmount.evaluate(200_000)>s.maxAmount.evaluate(200_000))
        val expected=maxOf(s.minAmount.evaluate(200_000),s.maxAmount.evaluate(200_000))/100.0
        assertEquals(expected,evaluate(s,time=200_000)[0],1e-7)
        assertEquals(s,ProjectCodec.decode(ProjectCodec.encode(project(TextAnimator(1,selectors=FrozenList.of(listOf(s)))))).composition.layers[0].text.animators[0].selectors[0])
    }
    @Test fun ligatureProjectionCombinesBeforeAveragingAndExcludesSpaces() {
        // Three source characters share one ligature, fourth is whitespace.
        val m=SelectorMembership(arrayOf(intArrayOf(0,1,2,3),intArrayOf(0,1,2,-1),intArrayOf(0,0,0,-1),intArrayOf(0,0,0,0)),
            intArrayOf(4,3,1,1),intArrayOf(0,3),intArrayOf(3,4))
        val a=TextAnimator(1,selectors=FrozenList.of(listOf(TextRangeSelector(2,units=p(1f),end=p(1f),smoothness=p(0f)))))
        val out=DoubleArray(2);PreparedSelectorEngine(a,m,ease).evaluate(0,out)
        assertArrayEquals(doubleArrayOf(1.0/3,0.0),out,1e-12)
        PreparedSelectorEngine(a.copy(selectors=FrozenList.of(listOf(TextRangeSelector(2,basedOn=p(1f))))),m,ease).evaluate(0,out)
        assertArrayEquals(doubleArrayOf(1.0,0.0),out,1e-12)
    }
    @Test fun keyedMembershipAndUnitsUsePreparedVariantsWithoutPermutationRebuilds() {
        val s=TextRangeSelector(2,basedOn=track(0f,3f),units=track(0f,1f),randomizeOrder=p(1f),end=p(1f),smoothness=p(0f))
        val engine=PreparedSelectorEngine(TextAnimator(1,selectors=FrozenList.of(listOf(s))),membership(),ease)
        val out=DoubleArray(4);engine.evaluate(999_999,out);assertEquals(0.0,out.sum(),0.0)
        engine.evaluate(1_000_000,out);assertEquals(1.0,out.sum(),1e-12)
        val again=DoubleArray(4);engine.evaluate(1_000_000,again);assertArrayEquals(out,again,0.0)
    }
    @Test fun sparseCoverageCachesAndDisabledGroupsDoNoWork() {
        val a=TextAnimator(1,selectors=FrozenList.of(listOf(TextRangeSelector(2))))
        val engine=PreparedSelectorEngine(a,membership(),ease);val out=DoubleArray(4)
        engine.evaluate(0,out);engine.evaluate(1_000_000,out);assertEquals(1L,engine.coverageBuilds)
        val disabled=PreparedSelectorEngine(a.copy(enabled=p(0f)),membership(),ease)
        disabled.evaluate(0,out);assertEquals(0L,disabled.coverageBuilds);assertEquals(0.0,out.sum(),0.0)
    }
    @Test fun anchorOnlyAnimationMovesGlyphAndLineAnchorFixesExpansionPivot() {
        val anchor=track(0f,20f);val out=DoubleArray(6)
        SelectorMath.affine(out,1.0,0.0,0.0,100.0,20.0,anchor.evaluate(500_000).toDouble(),4.0,0.0,100.0,100.0)
        assertArrayEquals(doubleArrayOf(1.0,0.0,0.0,1.0,-10.0,-4.0),out,1e-12)
        for(percent in listOf(0.0,50.0,100.0)) assertEquals(-percent/100*40,SelectorMath.lineShift(percent,40.0),0.0)
    }
    @Test fun allPropertyKindsAndSeedBitsRoundTripAndV10LoadsPermanently() {
        val properties=AnimatorProperty.entries.map { type -> when(type.kind) {
            1 -> TextPropertyOverride(type,scalar=track(0f,50f))
            2 -> TextPropertyOverride(type,vector=AnimatableProperty2D(true,0f,0f,FrozenList.of(listOf(Keyframe2D(0,0f,0f),Keyframe2D(1_000_000,10f,-20f)))))
            else -> TextPropertyOverride(type,colour=AnimatableColour(true,-1,FrozenList.of(listOf(ColourKeyframe(0,-1),ColourKeyframe(1_000_000,0x80ff0000.toInt())))))
        } }
        val a=TextAnimator(7,selectors=FrozenList.of(listOf(TextRangeSelector(8,seed=-1))),properties=FrozenList.of(properties))
        val bytes=ProjectCodec.encode(project(a));assertEquals(project(a),ProjectCodec.decode(bytes))
        val rebased=a.rebase(500_000);assertEquals(-500_000L,rebased.properties[0].keyTimes()[0]);assertEquals(-1L,rebased.selectors[0].seed)
        val legacy=ProjectCodec.encode(ProjectDocument())
        val buffer=java.nio.ByteBuffer.wrap(legacy).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val root=buffer.getInt(0);val table=root-buffer.getInt(root)
        buffer.putInt(root+buffer.getShort(table+4).toInt(),10)
        assertEquals(ProjectDocument(),ProjectCodec.decode(legacy))
    }
    @Test fun malformedValuesEnumsTypesCountsAndAmbiguousSelectorsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { TextRangeSelector(1,start=p(Float.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { TextRangeSelector(1,basedOn=p(4f)) }
        assertThrows(IllegalArgumentException::class.java) { TextRangeSelector(1,units=p(.5f)) }
        assertThrows(IllegalArgumentException::class.java) { TextRangeSelector(1,amount=p(101f)) }
        assertThrows(IllegalArgumentException::class.java) { TextPropertyOverride(AnimatorProperty.POSITION,scalar=p(0f)) }
        assertThrows(IllegalArgumentException::class.java) { TextAnimator(1,selectors=FrozenList.of((1..9).map { TextRangeSelector(it.toLong()) })) }
        val b=FlatBufferBuilder(256);val old=fluxx.schema.TextRangeSelector.createTextRangeSelector(b,1u,0u,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0u)
        val vector=fluxx.schema.TextAnimator.createSelectorsVector(b,intArrayOf(old))
        val a=fluxx.schema.TextAnimator.createTextAnimator(b,2u,old,0,1u,0,0,vector,0)
        val av=fluxx.schema.TextPayload.createTextAnimatorsVector(b,intArrayOf(a))
        b.finish(fluxx.schema.TextPayload.createTextPayload(b,0,0,0,0,1,av,0,0,0,0,0,0))
        val payload=fluxx.schema.TextPayload.getRootAsTextPayload(java.nio.ByteBuffer.wrap(b.sizedByteArray()))
        assertThrows(IllegalArgumentException::class.java) { TextAnimatorCodec.decode(payload,b.sizedByteArray().size,11) }
        assertThrows(IllegalArgumentException::class.java) { TextAnimatorCodec.decode(payload,b.sizedByteArray().size,10) }
    }
    @Test fun editActionsUndoCopyPasteReorderSplitAndNavigationShareExistingProtocol() {
        val a=TextAnimator(7,selectors=FrozenList.of(listOf(TextRangeSelector(8,seed=-1),TextRangeSelector(9))),
            properties=FrozenList.of(listOf(TextPropertyOverride(AnimatorProperty.TRACKING,scalar=track(0f,50f)))))
        val editor=EditorViewModel();editor.dispatch(EditorAction.Load(project(a)));editor.dispatch(EditorAction.Select(1))
        val base=editor.state.project
        val address=AnimatorTrackAddress(7,8,SelectorField.START)
        editor.dispatch(EditorAction.EditTextAnimator(1,AnimatorEdit.Key(address,AnimatorKeyOperation.SET,1_000_000,value=20f)))
        assertEquals(1_000_000L,TextAnimatorActions.nextKey(editor.state.selectedLayer!!,address,0,true))
        val keyed=editor.state.project;editor.undo();assertEquals(base,editor.state.project);editor.redo();assertEquals(keyed,editor.state.project)
        editor.dispatch(EditorAction.EditTextAnimator(1,AnimatorEdit.ReorderSelector(7,9,0)))
        assertEquals(9L,editor.state.selectedLayer!!.text.animators[0].selectors[0].id)
        assertTrue(editor.copyLayer(1));editor.duplicateLayer(1)
        val copy=editor.state.selectedLayer!!.text.animators[0]
        assertNotEquals(7L,copy.id);assertNotEquals(8L,copy.selectors.last().id)
        assertEquals(-1L,copy.selectors.last().seed);assertEquals(a.properties,copy.properties)
        editor.dispatch(EditorAction.Seek(500_000));editor.pasteLayer()
        assertEquals(-1L,editor.state.selectedLayer!!.text.animators[0].selectors.last().seed)
        val split=EditorReducer.reduce(EditorState(keyed,1),EditorAction.Split(1,500_000))
        val right=split.project.composition.layers.first { it.id!=1L }
        assertEquals(500_000L,right.text.animators[0].selectors[0].start.keyframes[0].timeUs)
        assertEquals(-500_000L,right.text.animators[0].properties[0].keyTimes()[0])
    }
    @Test fun timelineAggregationAndBulkMoveIncludeAnimatorTracksInOneUndo() {
        val a=TextAnimator(7,selectors=FrozenList.of(listOf(TextRangeSelector(8,start=track(0f,10f)))),
            properties=FrozenList.of(listOf(TextPropertyOverride(AnimatorProperty.TRACKING,scalar=track(0f,50f)))))
        val editor=EditorViewModel();editor.dispatch(EditorAction.Load(project(a)));editor.dispatch(EditorAction.Select(1))
        val original=editor.state.project
        assertEquals(listOf(0L,1_000_000L),editor.state.selectedLayer!!.aggregateKeyTimes())
        editor.dispatch(EditorAction.MoveKeyframe(1,oldTimeUs=1_000_000,newTimeUs=500_000))
        val layer=editor.state.selectedLayer!!
        assertEquals(listOf(0L,500_000L),layer.aggregateKeyTimes())
        assertEquals(listOf(0L,500_000L),layer.text.animators[0].properties[0].keyTimes())
        editor.undo();assertEquals(original,editor.state.project);assertFalse(editor.canUndo)
    }
    @Test fun persistedMalformedVersionsEnumsNonfiniteSeedsAndKeysAreValidated() {
        val a=TextAnimator(7,selectors=FrozenList.of(listOf(TextRangeSelector(8,start=track(0f,10f),seed=Long.MIN_VALUE))),
            properties=FrozenList.of(listOf(TextPropertyOverride(AnimatorProperty.TRACKING,scalar=p(0f)))))
        val original=ProjectCodec.encode(project(a))
        fun field(b: java.nio.ByteBuffer,t: Int,slot: Int): Int {
            val v=t-b.getInt(t);val offset=b.getShort(v+4+slot*2).toInt();require(offset!=0);return t+offset
        }
        fun child(b: java.nio.ByteBuffer,t: Int,slot: Int): Int { val f=field(b,t,slot);return f+b.getInt(f) }
        fun item(b: java.nio.ByteBuffer,t: Int,slot: Int,index: Int=0): Int {
            val v=child(b,t,slot);val f=v+4+index*4;return f+b.getInt(f)
        }
        fun malformed(edit: (java.nio.ByteBuffer,Int,Int)->Unit) {
            val bytes=original.copyOf();val b=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val root=b.getInt(0);val comp=child(b,root,1);val layer=item(b,comp,0)
            val text=child(b,layer,31);val group=item(b,text,5);val selector=item(b,group,6)
            edit(b,group,selector)
            assertThrows(IllegalArgumentException::class.java) { ProjectCodec.decode(bytes) }
        }
        malformed { b,g,_ -> b.putInt(field(b,g,0),3) }
        malformed { b,_,s -> b.putInt(field(b,s,0),99) }
        malformed { b,g,_ -> b.putLong(field(b,g,3),0) }
        malformed { b,_,s -> b.putLong(field(b,s,1),0) }
        malformed { b,_,s -> val track=child(b,s,4);b.putFloat(field(b,track,1),Float.NaN) }
        malformed { b,_,s -> val track=child(b,s,3);val key=item(b,track,2,1);b.putLong(field(b,key,0),0) }
        malformed { b,g,_ -> val property=item(b,g,2);b.putInt(field(b,property,1),99) }
        val valid=ProjectCodec.decode(original).composition.layers[0].text.animators[0]
        assertEquals(Long.MIN_VALUE,valid.selectors[0].seed)
    }
    @Test fun keyEditOperationsPreserveTypesAndResetIdentity() {
        val a=TextAnimator(7,properties=FrozenList.of(listOf(TextPropertyOverride(AnimatorProperty.SCALE,vector=AnimatableProperty2D(staticX=100f,staticY=100f)))))
        var state=EditorState(project(a),1)
        val address=AnimatorTrackAddress(7,property=AnimatorProperty.SCALE)
        fun edit(operation: AnimatorKeyOperation,time: Long,value: Float?=null,y: Float?=null,new: Long=time) {
            state=EditorReducer.reduce(state,EditorAction.EditTextAnimator(1,AnimatorEdit.Key(address,operation,time,new,value,y)))
        }
        edit(AnimatorKeyOperation.ENABLE,0)
        edit(AnimatorKeyOperation.SET,1_000_000,200f,50f)
        edit(AnimatorKeyOperation.MOVE,1_000_000,new=1_500_000)
        assertEquals(listOf(0L,1_500_000L),state.selectedLayer!!.text.animators[0].properties[0].keyTimes())
        edit(AnimatorKeyOperation.FREEZE,1_500_000)
        assertEquals(200f,state.selectedLayer!!.text.animators[0].properties[0].vector!!.staticX,0f)
        edit(AnimatorKeyOperation.RESET,1_500_000)
        assertEquals(100f,state.selectedLayer!!.text.animators[0].properties[0].vector!!.staticX,0f)
        // Imported keys may be frame-unique without having exact frame timestamps.
        state=EditorReducer.reduce(state,EditorAction.EditTextAnimator(1,AnimatorEdit.PutProperty(7,
            TextPropertyOverride(AnimatorProperty.SCALE,vector=AnimatableProperty2D(true,100f,100f,
                FrozenList.of(listOf(Keyframe2D(1_000_001,200f,50f))))))))
        edit(AnimatorKeyOperation.MOVE,1_000_001,new=1_500_000)
        assertEquals(listOf(1_500_000L),state.selectedLayer!!.text.animators[0].properties[0].keyTimes())
        edit(AnimatorKeyOperation.REMOVE,1_500_000)
        assertTrue(state.selectedLayer!!.text.animators[0].properties[0].keyTimes().isEmpty())
    }
}
