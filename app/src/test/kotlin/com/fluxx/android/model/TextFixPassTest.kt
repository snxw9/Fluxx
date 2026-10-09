package com.fluxx.android.model

import com.fluxx.android.editor.TextEdits
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import org.junit.Assert.*
import org.junit.Test

class TextFixPassTest {
    @Test fun legacyCaseOrdinalsAndLowercaseRoundTripRemainLossless() {
        assertEquals(0,TextCaps.NONE.ordinal);assertEquals(1,TextCaps.ALL.ordinal);assertEquals(2,TextCaps.SMALL.ordinal)
        val text="Hello Straße İΣ 123!"
        val document=TextSourceDocument(text).format(0,0) {it.copy(caps=TextCaps.LOWER)}
        DataInputStream(ByteArrayInputStream(StyledTextPreparation.encode(document,"fluxx.sans"))).use {input ->
            assertEquals(1,input.readInt());val original=ByteArray(input.readInt());input.readFully(original)
            assertEquals(text,original.toString(Charsets.UTF_8))
            val mapped=IntArray(input.readInt()) {input.readInt()}
            assertArrayEquals(text.codePoints().toArray().map {Character.toLowerCase(it)}.toIntArray(),mapped)
            assertEquals('i'.code,mapped[text.indexOf('İ')]);assertEquals('σ'.code,mapped[text.indexOf('Σ')])
        }
    }
    @Test fun wrappingAndParagraphPaintEditsReuseTheShapingIdentity() {
        val original=TextSourceDocument("Hello World").format(0,5) {it.copy(font=TextFontRef("fluxx.serif"))}
        val edited=original.copy(boxWidth=100f,paragraphs=FrozenList.of(listOf(TextParagraphStyle(
            alignment=ParagraphAlignment.JUSTIFY_ALL,indentLeft=12f,indentRight=7f,indentFirst=4f,spaceBefore=10f,spaceAfter=20f))))
            .format(0,5) {it.copy(fill=0xff00ff00.toInt(),trackingDelta=50f,baselineDelta=3f)}
        assertEquals(StyledTextPreparation.shapingDocument(original),StyledTextPreparation.shapingDocument(edited))
        assertNotEquals(StyledTextPreparation.shapingDocument(original),StyledTextPreparation.shapingDocument(original.format(0,5) {it.copy(caps=TextCaps.LOWER)}))
    }
    @Test fun fillCompositionUsesAbsoluteEvaluatedScaleAndRefusesCollapsedScale() {
        val layer=CompositionLayer(id=1,type=LayerType.TEXT,transform=Transform(scaleX=-2f))
        assertEquals(540f,TextEdits.fillCompositionWidth(layer,0,1080),0f)
        assertThrows(IllegalArgumentException::class.java) {TextEdits.fillCompositionWidth(layer.copy(transform=Transform(scaleX=0f)),0,1080)}
    }
    @Test fun hitAndSelectionRangesKeepLigaturesSurrogatesAndMarksWhole() {
        val document=TextSourceDocument("ffi a\u0301\uD83D\uDE00")
        val geometry=TextPreparedGeometry(document,intArrayOf(0,3,4,6),intArrayOf(3,4,6,8),
            intArrayOf(0,0,1,1),intArrayOf(0,-1,1,1),floatArrayOf(0f,0f,30f,20f,30f,0f,40f,20f,0f,20f,12f,40f,12f,20f,30f,40f),
            FloatArray(8),floatArrayOf(0f,0f,40f,40f))
        assertEquals(TextSelectionRange(0,3),geometry.range(geometry.hit(5f,10f)))
        assertEquals(TextSelectionRange(4,8),geometry.range(2,true))
        assertEquals(TextSelectionRange(0,8),geometry.safeRange(1,7))
        assertEquals(-1,geometry.hit(100f,100f));assertEquals(3,geometry.hit(100f,100f,true))
    }
}
