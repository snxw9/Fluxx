package com.fluxx.android.fonts

import org.junit.Assert.*
import org.junit.Test

class FontMetadataTest {
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
