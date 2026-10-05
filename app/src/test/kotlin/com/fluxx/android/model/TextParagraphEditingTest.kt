package com.fluxx.android.model

import org.junit.Assert.*
import org.junit.Test

class TextParagraphEditingTest {
    @Test fun selectionEndDoesNotTouchFollowingParagraph() {
        val d=TextSourceDocument("one\ntwo\n")
        val edited=d.formatParagraphs(0,4) { it.copy(indentLeft=12f) }
        assertEquals(listOf(12f,0f,0f),edited.paragraphs.map { it.indentLeft })
        assertEquals(d.runs,edited.runs)
        assertEquals(listOf(0f,9f,0f),d.formatParagraphs(4,7) { it.copy(spaceAfter=9f) }.paragraphs.map { it.spaceAfter })
    }
    @Test fun collapsedSelectionFormatsAllIncludingEmptyParagraphs() {
        val d=TextSourceDocument("a\n\nb")
        assertEquals(listOf(4f,4f,4f),d.formatParagraphs(2,2) { it.copy(indentFirst=4f) }.paragraphs.map { it.indentFirst })
        assertEquals(1,TextSourceDocument("").formatParagraphs(0,0) { it.copy(spaceAfter=3f) }.paragraphs.size)
    }
    @Test fun heldDocumentRoundTripPreservesParagraphAndBoxTogether() {
        val d=TextSourceDocument("a\nb",boxWidth=80f).formatParagraphs(2,3) {
            it.copy(alignment=ParagraphAlignment.JUSTIFY_LAST_RIGHT,indentRight=7f,spaceBefore=5f)
        }
        val builder=com.google.flatbuffers.FlatBufferBuilder()
        builder.finish(TextSourceDocumentCodec.encode(builder,d))
        val encoded=builder.sizedByteArray()
        val bytes=java.nio.ByteBuffer.wrap(encoded).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(d,TextSourceDocumentCodec.decode(fluxx.schema.TextSourceDocument.getRootAsTextSourceDocument(bytes),d.text,12,encoded.size) {})
    }
}
