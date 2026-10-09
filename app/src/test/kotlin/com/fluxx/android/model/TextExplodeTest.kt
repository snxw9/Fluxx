package com.fluxx.android.model

import com.fluxx.android.editor.*
import com.fluxx.android.render.LayerGeometry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TextExplodeTest {
    // Fixed independent geometry fixtures: AB has two equal-width glyph cells, not font shaping.
    private fun geometry(text: TextProperties): TextPreparedGeometry {
        val d=text.source.sourceDocument;val edges=d.boundaries
        val n=edges.size-1
        return TextPreparedGeometry(d,edges.dropLast(1).toIntArray(),edges.drop(1).toIntArray(),IntArray(n),IntArray(n),
            FloatArray(n*4) {i->when(i%4) {0->i/4*12f;1->-10f;2->i/4*12f+12f;else->4f}},
            FloatArray(n*2) {if(it%2==0) it/2*12f else 0f},floatArrayOf(0f,-10f,n*12f,14f))
    }
    private fun layer()=CompositionLayer(1,LayerType.TEXT,timing=ClipTiming(1_000_000,200_000,2_000_000),keyframeAnchorUs=2_000_000,
        text=TextProperties(source=AnimatableString(staticValue="AB")),
        animTransform=AnimatableTransform(position=AnimatableProperty2D(true,keyframes=FrozenList.of(listOf(
            Keyframe2D(-1_000_000,-.5f,.1f),Keyframe2D(1_000_000,.5f,-.1f)))),
            scale=AnimatableProperty2D(true,staticX=1f,staticY=1f,keyframes=FrozenList.of(listOf(Keyframe2D(-1_000_000,-1f,.5f),Keyframe2D(1_000_000,2f,1.5f)))),
            rotation=AnimatableProperty1D(true,keyframes=FrozenList.of(listOf(Keyframe1D(-1_000_000,-45f),Keyframe1D(1_000_000,120f))))))
    private suspend fun plan(layer: CompositionLayer=layer(),options: TextExplodeOptions=TextExplodeOptions())=
        TextExplode.prepare(layer,Composition(),1_500_000,2,options) {geometry(it)}

    @Test fun groupCopiesSignedTracksAndMatchesPositionsAcrossAnimatedTransforms()=runBlocking {
        val source=layer();val plan=plan(source)
        assertEquals(listOf("A","B"),plan.pieces.map {it.text.source.staticValue})
        for(piece in plan.pieces) {
            assertEquals(source.animTransform,piece.animTransform);assertEquals(source.timing,piece.timing)
            assertEquals(source.resolvedKeyframeAnchorUs,piece.resolvedKeyframeAnchorUs)
        }
        for(time in listOf(1_000_000L,1_500_000L,2_500_000L)) {
            val matrix=FloatArray(16)
            LayerGeometry.textMatrix(matrix,source.evaluatedTransform(time),TextBounds(0f,-10f,24f,14f),1080,1920,anchorX=source.anchorX,anchorY=source.anchorY)
            for((n,piece) in plan.pieces.withIndex()) {
                val actual=FloatArray(16)
                LayerGeometry.textMatrix(actual,piece.evaluatedTransform(time),TextBounds(0f,-10f,12f,14f),1080,1920,anchorX=piece.anchorX,anchorY=piece.anchorY)
                assertEquals(matrix[0]*(n*12)+matrix[12],actual[12],1e-6f)
                assertEquals(matrix[1]*(n*12)+matrix[13],actual[13],1e-6f)
            }
        }
    }
    @Test fun atomicUndoRedoCodecClipboardAndHiddenOriginal()=runBlocking {
        val original=layer();val project=ProjectDocument(Composition(layers=FrozenList.of(listOf(original))))
        val editor=EditorViewModel();editor.dispatch(EditorAction.Load(project));editor.dispatch(EditorAction.Select(1))
        val plan=plan(original,TextExplodeOptions(keepOriginal=true))
        editor.dispatch(EditorAction.ExplodeText(plan));val exploded=editor.state.project
        assertFalse(exploded.composition.layers.first {it.id==1L}.visible)
        assertEquals(listOf(1L,2L,3L),exploded.composition.layers.map {it.id})
        assertEquals(exploded,ProjectCodec.decode(ProjectCodec.encode(exploded)))
        editor.undo();assertEquals(project,editor.state.project);assertFalse(editor.canUndo)
        editor.redo();assertEquals(exploded,editor.state.project)
        assertTrue(editor.copyLayer(2));editor.pasteLayer()
        val pasted=editor.state.selectedLayer!!
        assertEquals(plan.pieces[0].text,pasted.text);assertEquals(plan.pieces[0].anchorX,pasted.anchorX,0f)
    }
    @Test fun eachPieceRefusesAnimatedSpatialTracksButRetainsAnimatedOpacity()=runBlocking {
        try {plan(options=TextExplodeOptions(pivot=TextPiecePivot.EACH));fail("Expected Group suggestion")}
        catch(failure: IllegalArgumentException) {assertTrue(failure.message!!.contains("Group"))}
        val opacity=AnimatableProperty1D(true,keyframes=FrozenList.of(listOf(Keyframe1D(0,0f),Keyframe1D(1_000_000,1f))))
        val source=layer().copy(animTransform=AnimatableTransform(opacity=opacity),transform=Transform(rotationDegrees=30f,scaleX=-2f))
        val result=plan(source,TextExplodeOptions(pivot=TextPiecePivot.EACH))
        assertTrue(result.pieces.all {it.anchorX==.5f && it.anchorY==.5f && it.animTransform.opacity==opacity})
    }
    @Test fun sourceAndLayoutTracksFreezeAtInPointOutsideLayer()=runBlocking {
        val source=layer().copy(text=layer().text.copy(size=AnimatableProperty1D(true,staticValue=20f,keyframes=FrozenList.of(listOf(
            Keyframe1D(-1_000_000,20f),Keyframe1D(1_000_000,80f))))))
        val result=TextExplode.prepare(source,Composition(),0,2,TextExplodeOptions()) {geometry(it)}
        assertEquals(source.timing.startUs,result.sampledTime)
        assertTrue(result.pieces.all {!it.text.layoutAnimated && it.text.size.staticValue==20f})
        assertEquals(0f,result.pieces.first().text.source.sourceDocument.boxWidth,0f)
    }
    @Test fun styleSlicesRebaseKerningAndRetainFontAndParagraphBox() {
        val original=TextSourceDocument("AB CD",boxWidth=123f).format(0,0) {it.copy(font=TextFontRef("fluxx.serif"),
            manualKerning=FrozenList.of(listOf(TextManualKerning(3,15f))))}
        val sliced=TextExplode.slice(original,3,5,original.boxWidth)
        assertEquals("CD",sliced.text);assertEquals(123f,sliced.boxWidth,0f)
        assertEquals(TextFontRef("fluxx.serif"),sliced.runs[0].style.font)
        assertEquals(TextManualKerning(0,15f),sliced.runs[0].style.manualKerning[0])
    }
    @Test fun sourceRangesStayLogicalAndGraphemeSafeWhenPositionsAreVisual() {
        val d=TextSourceDocument("a\u0301B")
        val g=TextPreparedGeometry(d,intArrayOf(0,2),intArrayOf(2,3),intArrayOf(1,0),intArrayOf(0,1),FloatArray(8),FloatArray(4),FloatArray(4))
        assertEquals(listOf(TextSelectionRange(0,2),TextSelectionRange(2,3)),TextExplode.ranges(d,g,TextExplodeOptions()))
        assertEquals(listOf(TextSelectionRange(0,2),TextSelectionRange(2,3)),TextExplode.ranges(d,g,TextExplodeOptions(split=TextSplit.LINES)))
        val empty=TextSourceDocument("")
        assertTrue(TextExplode.ranges(empty,geometry(TextProperties()),TextExplodeOptions()).isEmpty())
    }
    @Test fun stalePlanRefusesWithoutPartialCreationAndAnchorsNeverClamp()=runBlocking {
        val plan=plan();val state=EditorState(ProjectDocument(Composition(layers=FrozenList.of(listOf(layer().copy(name="changed"))))))
        assertThrows(IllegalArgumentException::class.java) {EditorReducer.reduce(state,EditorAction.ExplodeText(plan))}
        val outside=layer().copy(anchorX=-12f,anchorY=40f)
        assertEquals(outside,ProjectCodec.decode(ProjectCodec.encode(ProjectDocument(Composition(layers=FrozenList.of(listOf(outside)))))).composition.layers[0])
        assertThrows(IllegalArgumentException::class.java) {outside.copy(anchorX=CompositionLayer.MAX_ANCHOR+1f)}
        Unit
    }
    @Test fun joiningWordsAreIndivisibleInCharacterSplits() {
        val d=TextSourceDocument("AB CD")
        val g=geometry(TextProperties(source=AnimatableString(staticValue=d.text,document=d)))
        assertEquals(listOf(TextSelectionRange(0,2),TextSelectionRange(3,5)),TextExplode.ranges(d,g,TextExplodeOptions(),
            listOf(TextSelectionRange(0,2),TextSelectionRange(3,5))))
    }
    @Test fun linePiecesKeepBoxAndMapNonFinalJustification()=runBlocking {
        val d=TextSourceDocument("AB CD EF",paragraphs=FrozenList.of(listOf(TextParagraphStyle(
            alignment=ParagraphAlignment.JUSTIFY_LAST_RIGHT,indentFirst=5f))),boxWidth=72f)
        val source=layer().copy(text=TextProperties(source=AnimatableString(staticValue=d.text,document=d)))
        val result=TextExplode.prepare(source,Composition(),1_500_000,2,TextExplodeOptions(split=TextSplit.LINES)) {text->
            if(text.source.staticValue!=d.text) geometry(text) else {
                val base=geometry(text)
                TextPreparedGeometry(d,base.starts,base.ends,intArrayOf(0,0,0,0,0,0,1,1),base.words,
                    base.boxes.copyOf().also {for(c in 6..7) {it[c*4]-=72;it[c*4+2]-=72;it[c*4+1]+=24;it[c*4+3]+=24}},
                    base.origins.copyOf().also {for(c in 6..7) {it[c*2]-=72;it[c*2+1]+=24}},floatArrayOf(0f,-10f,72f,38f))
            }
        }
        assertEquals(listOf("AB CD ","EF"),result.pieces.map {it.text.source.staticValue})
        assertTrue(result.pieces.all {it.text.source.sourceDocument.boxWidth==72f})
        assertEquals(ParagraphAlignment.JUSTIFY_ALL,result.pieces[0].text.source.sourceDocument.paragraphs[0].alignment)
        assertEquals(ParagraphAlignment.JUSTIFY_LAST_RIGHT,result.pieces[1].text.source.sourceDocument.paragraphs[0].alignment)
        assertEquals(0f,result.pieces[1].text.source.sourceDocument.paragraphs[0].indentFirst,0f)
    }
}
