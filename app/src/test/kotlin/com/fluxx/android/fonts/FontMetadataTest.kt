package com.fluxx.android.fonts

import org.junit.Assert.*
import org.junit.Test
import com.fluxx.android.model.*
import com.fluxx.android.editor.*
import kotlinx.coroutines.*

class FontMetadataTest {
    @Test fun instanceChoiceReplacesCustomAxesPreparesImmediatelyAndIsOnePersistedUndo()=runBlocking {
        val metadata=face().copy(axes=listOf(weight,FontAxisMetadata("wdth","Width",75f,100f,125f)),
            instances=listOf(FontNamedInstance(7,"Bold condensed",listOf(700f,80f))))
        val custom=metadata.reference(hash,requested=mapOf("wght" to 550f,"wdth" to 120f))
        val originalDoc=TextSourceDocument("Text").format(0,0) { it.copy(font=custom) }
        val layer=CompositionLayer(1,LayerType.TEXT,text=TextProperties(source=AnimatableString(staticValue="Text",document=originalDoc)))
        val original=ProjectDocument(Composition(layers=FrozenList.of(listOf(layer))))
        val editor=EditorViewModel();editor.dispatch(EditorAction.Load(original));editor.dispatch(EditorAction.Select(1))
        val prepared=mutableListOf<TextFontRef>()
        val metrics=TextLayoutMetrics.fromNative(doubleArrayOf(1000.0,800.0,-200.0,0.0,1.0,0.0,2400.0),false)
        val controller=TextEditController(editor,this,{_,_->metrics},measureDocument={_,doc,_->
            prepared+=requireNotNull(doc.runs[0].style.font);metrics })
        val selected=metadata.selectInstance(custom,7)
        controller.format(1,0,0) {it.copy(font=selected)}
        coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals(7,selected.namedInstance)
        assertEquals(listOf(700f,80f),selected.axes.map {it.value.staticValue})
        assertEquals(listOf(custom,selected),prepared) // old/new geometry for anchor compensation
        val committed=editor.state.project
        val changedDoc=committed.composition.layers[0].text.source.sourceDocument
        assertNotEquals(StyledTextPreparation.shapingDocument(originalDoc),StyledTextPreparation.shapingDocument(changedDoc))
        assertEquals(selected,changedDoc.runs[0].style.font)
        assertEquals(committed,ProjectCodec.decode(ProjectCodec.encode(committed)))
        editor.undo();assertEquals(original,editor.state.project);assertFalse(editor.canUndo)
        editor.redo();assertEquals(committed,editor.state.project)
        assertEquals(listOf(400f,100f),metadata.selectInstance(selected,-1).axes.map {it.value.staticValue})
    }
    private val hash="ab".repeat(32)
    private val weight=FontAxisMetadata("wght","Weight",100f,400f,900f)
    private fun face()=FontFaceMetadata(0,"Example","Regular","Example-Regular",400,5,0f,
        listOf(weight),listOf(FontNamedInstance(0,"Bold",listOf(700f))))
    private fun rejected(block: ()->Unit) {
        try { block();fail("Expected invalid font metadata to be rejected") } catch(_: IllegalArgumentException) {}
    }
    @Test fun staticCoordinatesUseDefaultsNamedValuesAndClamps() {
        val metadata=face()
        assertEquals(400f,metadata.reference(hash).axes.single().value.staticValue,0f)
        assertEquals(700f,metadata.reference(hash,0).axes.single().value.staticValue,0f)
        assertEquals(900f,metadata.reference(hash,0,mapOf("wght" to 10000f)).axes.single().value.staticValue,0f)
        assertEquals(100f,metadata.reference(hash,requested=mapOf("wght" to -1f)).axes.single().value.staticValue,0f)
        assertFalse(metadata.reference(hash).axes.single().value.isAnimated)
    }
    @Test fun requestedIdentityIsNotRewritten() {
        val reference=face().reference(hash,0)
        assertEquals(hash,reference.sha256);assertEquals(0,reference.faceIndex);assertEquals(0,reference.namedInstance)
        assertEquals("Example",reference.family);assertEquals("Example-Regular",reference.postScriptName)
        assertEquals("Bold",reference.styleName)
    }
    @Test fun importedDisplayStylePersistsWithoutAFontLibrary() {
        val reference=face().reference(hash,0)
        val doc=com.fluxx.android.model.TextSourceDocument("a",com.fluxx.android.model.FrozenList.of(listOf(
            com.fluxx.android.model.TextStyleRun(0,1,com.fluxx.android.model.TextRunStyle(font=reference)))))
        val layer=com.fluxx.android.model.CompositionLayer(1,com.fluxx.android.model.LayerType.TEXT,0,
            text=com.fluxx.android.model.TextProperties(source=com.fluxx.android.model.AnimatableString(staticValue=doc.text,document=doc)))
        val project=com.fluxx.android.model.ProjectDocument(com.fluxx.android.model.Composition(layers=com.fluxx.android.model.FrozenList.of(listOf(layer))))
        assertEquals(project,com.fluxx.android.model.ProjectCodec.decode(com.fluxx.android.model.ProjectCodec.encode(project)))
    }
    @Test fun axisAndInstanceValidationRejectsNonfiniteUnknownAndMalformedValues() {
        rejected { weight.copy(minimum=Float.NaN) };rejected { weight.copy(maximum=399f) }
        rejected { weight.copy(tag="bad") };rejected { face().copy(axes=listOf(weight,weight)) }
        rejected { face().copy(instances=listOf(FontNamedInstance(0,"Invalid",listOf(1000f)))) }
        rejected { face().copy(instances=listOf(FontNamedInstance(0,"Invalid",emptyList()))) }
        rejected { face().reference(hash,1) };rejected { face().reference(hash,requested=mapOf("opsz" to 12f)) }
        rejected { face().reference(hash,requested=mapOf("wght" to Float.POSITIVE_INFINITY)) }
    }
    @Test fun libraryLimitsAndCollectionIdentitiesAreValidated() {
        rejected { FontLibraryEntry("../font",1,listOf(face())) }
        rejected { FontLibraryEntry(hash,0,listOf(face())) }
        rejected { FontLibraryEntry(hash,16L*1024*1024+1,listOf(face())) }
        rejected { FontLibraryEntry(hash,1,emptyList()) }
        rejected { FontLibraryEntry(hash,1,listOf(face(),face())) }
        assertEquals(2,FontLibraryEntry(hash,1,listOf(face(),face().copy(index=1))).faces.size)
    }
    @Test fun malformedFaceMetadataDoesNotBecomeAProjectReference() {
        rejected { face().copy(index=256) };rejected { face().copy(weight=0) };rejected { face().copy(width=10) }
        rejected { face().copy(family="") };rejected { face().copy(postScriptName="a\u0000b") }
        rejected { face().copy(slant=Float.NEGATIVE_INFINITY) }
    }
}
