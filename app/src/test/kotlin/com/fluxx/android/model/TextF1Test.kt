package com.fluxx.android.model

import com.fluxx.android.editor.*
import com.google.flatbuffers.FlatBufferBuilder
import org.junit.Assert.*
import org.junit.Test

class TextF1Test {
    private fun track(a: Float, b: Float) = AnimatableProperty1D(true,a,FrozenList.of(listOf(
        Keyframe1D(0,a,EasingPreset.LINEAR),Keyframe1D(1_000_000,b))))
    private fun project(text: TextProperties) = ProjectDocument(Composition(layers = FrozenList.of(listOf(
        CompositionLayer(1,LayerType.TEXT,timing=ClipTiming(durationUs=2_000_000),text=text)))))
    @Test fun requestedStrokeSurvivesSizeDependentClampAndPersistence() {
        val text = TextProperties(size=track(8f,500f),strokeWidth=AnimatableProperty1D(staticValue=20f))
        assertEquals(1.6f,text.effectiveStrokeWidth(0),.0001f)
        assertEquals(20f,text.effectiveStrokeWidth(1_000_000),0f)
        val reopened = ProjectCodec.decode(ProjectCodec.encode(project(text))).composition.layers.first().text
        assertEquals(20f,reopened.strokeWidth.staticValue,0f)
        assertEquals(text,reopened)
        assertEquals(0f,TextSdfLimits.softness(100f,8f,1.6f),.0001f)
        assertEquals(.8f,TextSdfLimits.softness(100f,8f,0f),.0001f)
    }
    @Test fun allF1TracksRoundTripAndRebaseWithHoldOrder() {
        val colour = AnimatableColour(true,0x80ff0000.toInt(),FrozenList.of(listOf(
            ColourKeyframe(0,0x80ff0000.toInt()),ColourKeyframe(1_000_000,0x4000ff00))))
        val text = TextProperties(tracking=track(-100f,100f),leading=track(-20f,30f),baselineShift=track(-8f,12f),
            strokeWidth=track(0f,20f),strokeColour=colour,strokeOverFill=track(0f,1f))
        assertEquals(0,text.paintOrder(999_999)); assertEquals(1,text.paintOrder(1_000_000))
        assertEquals(text,ProjectCodec.decode(ProjectCodec.encode(project(text))).composition.layers.first().text)
        val rebased=text.rebase(500_000)
        assertEquals(text.tracking.evaluate(750_000),rebased.tracking.evaluate(250_000),0f)
        assertEquals(text.strokeColour.evaluate(750_000),rebased.strokeColour.evaluate(250_000))
        assertEquals(text.paintOrder(1_000_000),rebased.paintOrder(500_000))
    }
    @Test fun propertyActionsNavigationMoveFreezeAndResetAreShared() {
        for(property in listOf(AnimPropertyType.TRACKING,AnimPropertyType.LEADING,AnimPropertyType.BASELINE_SHIFT,
            AnimPropertyType.STROKE_WIDTH,AnimPropertyType.STROKE_OVER_FILL,AnimPropertyType.STROKE_COLOUR)) {
            var state=EditorState(project(TextProperties()),1)
            state=EditorReducer.reduce(state,EditorAction.ToggleAnimated(1,property,true,0))
            val action=if(property==AnimPropertyType.STROKE_COLOUR) EditorAction.SetColourKeyframe(1,1_000_000,0x80ff0000.toInt(),property=property)
                else EditorAction.SetKeyframe(1,property,1_000_000,if(property==AnimPropertyType.STROKE_OVER_FILL) 1f else 20f)
            state=EditorReducer.reduce(state,action)
            assertEquals(listOf(0L,1_000_000L),state.selectedLayer!!.propertyKeyTimes(property))
            assertEquals(1_000_000L,ContextualNavigation.findNext(state.project,state.selectedLayer,true,property,0))
            state=EditorReducer.reduce(state,EditorAction.MoveKeyframe(1,property,1_000_000,1_500_000))
            assertEquals(listOf(0L,1_500_000L),state.selectedLayer!!.propertyKeyTimes(property))
            state=EditorReducer.reduce(state,EditorAction.ToggleAnimated(1,property,false,1_500_000))
            assertFalse(state.selectedLayer!!.text.animated(property))
            if(property==AnimPropertyType.STROKE_OVER_FILL) assertEquals(1,state.selectedLayer!!.text.paintOrder(0))
            state=EditorReducer.reduce(state,EditorAction.ResetPropertyAnimation(1,property))
            assertFalse(state.selectedLayer!!.text.animated(property))
        }
    }
    @Test fun tracksRejectInvalidWidthOrderAndFrameCollisions() {
        assertThrows(IllegalArgumentException::class.java) { TextProperties(strokeWidth=track(-1f,20f)) }
        assertThrows(IllegalArgumentException::class.java) { TextProperties(strokeOverFill=track(0f,.5f)) }
        assertThrows(IllegalArgumentException::class.java) { project(TextProperties(tracking=AnimatableProperty1D(true,0f,
            FrozenList.of(listOf(Keyframe1D(0,0f),Keyframe1D(1,1f)))))) }
    }
    @Test fun v9PayloadDefaultsAndV10MalformedTracksAreValidated() {
        fun bytes(version: Int, width: Float? = null, order: Float? = null): ByteArray {
            val b=FlatBufferBuilder(128)
            val w=width?.let { fluxx.schema.AnimatableProperty1D.createAnimatableProperty1D(b,false,it,0) } ?: 0
            val o=order?.let { fluxx.schema.AnimatableProperty1D.createAnimatableProperty1D(b,false,it,0) } ?: 0
            fluxx.schema.TextPayload.startTextPayload(b)
            fluxx.schema.TextPayload.addStrokeWidth(b,w);fluxx.schema.TextPayload.addStrokeOverFill(b,o)
            val text=fluxx.schema.TextPayload.endTextPayload(b)
            fluxx.schema.Layer.startLayer(b);fluxx.schema.Layer.addId(b,1UL);fluxx.schema.Layer.addType(b,3);fluxx.schema.Layer.addTextPayload(b,text)
            val layer=fluxx.schema.Layer.endLayer(b)
            val layers=fluxx.schema.Composition.createLayersVector(b,intArrayOf(layer))
            fluxx.schema.Composition.startComposition(b);fluxx.schema.Composition.addLayers(b,layers)
            val comp=fluxx.schema.Composition.endComposition(b)
            b.finish(fluxx.schema.Project.createProject(b,version,comp));return b.sizedByteArray()
        }
        assertEquals(TextProperties(),ProjectCodec.decode(bytes(9)).composition.layers.first().text)
        for(width in listOf(-1f,Float.NaN,Float.POSITIVE_INFINITY)) assertThrows(IllegalArgumentException::class.java) { ProjectCodec.decode(bytes(10,width)) }
        assertThrows(IllegalArgumentException::class.java) { ProjectCodec.decode(bytes(10,order=2f)) }
        assertThrows(IllegalArgumentException::class.java) { ProjectCodec.decode(bytes(11)) }
    }
    @Test fun clusterTrackingAndLeadingBoundsUsePreparedMembership() {
        // One ligature cluster with two glyph quads followed by another cluster, plus an empty line.
        val metrics=TextLayoutMetrics.fromNative(doubleArrayOf(1000.0,800.0,-200.0,0.0,2.0,3.0,
            1200.0,0.0,
            0.0,0.0,0.0,0.0,700.0,500.0,-700.0,
            0.0,500.0,0.0,0.0,700.0,100.0,-700.0,
            0.0,600.0,0.0,0.0,700.0,600.0,-700.0,
            2.0,0.0,0.0,0.0,1.0),false)
        val text=TextProperties(size=AnimatableProperty1D(staticValue=100f),tracking=AnimatableProperty1D(staticValue=100f),
            leading=AnimatableProperty1D(staticValue=20f),baselineShift=AnimatableProperty1D(staticValue=10f),strokeWidth=AnimatableProperty1D(staticValue=10f))
        val logical=metrics.logical(text,0)
        assertEquals(130f,logical.width,.001f); assertEquals(220f,logical.height,.001f);assertEquals(-80f,logical.top,.001f)
        val ink=metrics.ink(100f,TextAlignment.LEFT,100f,20f,10f,10f)
        assertTrue(ink.left<0);assertTrue(ink.width>130f)
    }
    @Test fun gestureDuplicateClipboardAndSplitKeepEveryF1Track() {
        val text=TextProperties(tracking=track(-100f,100f),leading=track(0f,20f),baselineShift=track(0f,10f),
            strokeWidth=track(0f,40f),strokeOverFill=track(0f,1f),strokeColour=AnimatableColour(staticValue=0x8080ff00.toInt()))
        val editor=EditorViewModel()
        editor.dispatch(EditorAction.Load(project(text)));editor.dispatch(EditorAction.Select(1))
        val original=editor.state.project
        editor.beginGesture()
        for(value in listOf(10f,20f,30f)) editor.previewGesture(EditorAction.Batch(listOf(
            EditorAction.SetTextPayload(1,TextEdits.scalar(editor.state.selectedLayer!!,0,AnimPropertyType.TRACKING,value)))))
        editor.commitGesture();val committed=editor.state.project
        editor.undo();assertEquals(original,editor.state.project);assertFalse(editor.canUndo)
        editor.redo();assertEquals(committed,editor.state.project)
        assertTrue(editor.copyLayer(1));editor.duplicateLayer(1)
        assertEquals(committed.composition.layers.first().text,editor.state.selectedLayer!!.text)
        editor.dispatch(EditorAction.Seek(1_000_000));editor.pasteLayer()
        assertEquals(committed.composition.layers.first().text,editor.state.selectedLayer!!.text)
        val state=EditorReducer.reduce(EditorState(original,1),EditorAction.Split(1,500_000))
        assertEquals(2,state.project.composition.layers.size)
        val right=state.project.composition.layers.first { it.id != 1L }
        assertEquals(text.tracking.evaluate(750_000),right.text.tracking.evaluate(750_000-right.resolvedKeyframeAnchorUs),.0001f)
        assertEquals(text.paintOrder(1_000_000),right.text.paintOrder(1_000_000-right.resolvedKeyframeAnchorUs))
    }
}
