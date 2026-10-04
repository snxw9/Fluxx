package com.fluxx.android.model

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import org.junit.Assert.*
import org.junit.Test

class StyledTextPreparationTest {
    @Test fun caretHintsDisambiguateRepeatedTextAndPreserveProgressiveStyles() {
        val document=TextSourceDocument("aaaa").format(1,3) { it.copy(fauxBold=true) }
        val inserted=document.replace("aaaaa",2,2)
        assertTrue(inserted.runAt(2)!!.style.fauxBold)
        val next=inserted.replace("aaaaaa",3,3)
        assertTrue(next.runAt(3)!!.style.fauxBold)
        assertFalse(next.runAt(5)!!.style.fauxBold)
        val deleted=next.replace("aaaa",2,4)
        assertTrue(deleted.runAt(2)!!.style.fauxBold)
        assertEquals("abaa",document.replace("abaa",99,99).text)
    }
    @Test fun uppercaseWireKeepsOriginalUtf8OffsetsAndSharpS() {
        val source="a\u0301ß\uD83D\uDE00b"
        val document=TextSourceDocument(source).format(2,5) { it.copy(font=TextFontRef("fluxx.serif",TextFaceStyle.BOLD),caps=TextCaps.ALL) }
        DataInputStream(ByteArrayInputStream(StyledTextPreparation.encode(document,"fluxx.sans"))).use { input ->
            fun string(): String { val bytes=ByteArray(input.readInt());input.readFully(bytes);return bytes.toString(Charsets.UTF_8) }
            assertEquals(1,input.readInt());assertEquals(source,string())
            val upper=IntArray(input.readInt()) { input.readInt() }
            assertArrayEquals(intArrayOf('A'.code,0x301,0xdf,0x1f600,'B'.code),upper)
            assertEquals(3,input.readInt())
            val ends=ArrayList<Int>();val fonts=ArrayList<String>()
            repeat(3) { input.readInt();ends.add(input.readInt());fonts.add(string());repeat(3) { input.readFloat() };repeat(3) { input.readInt() } }
            assertEquals(listOf(3,9,10),ends);assertEquals("fluxx.serif.bold",fonts[1]);assertEquals(0,input.available())
        }
    }
    @Test fun heldDocumentAlternativesIncludeWidthDirectionAndEnforceTheCap() {
        val documents=List(32) { TextSourceDocument("a",boxWidth=it.toFloat(),paragraphs=FrozenList.of(listOf(TextParagraphStyle(direction=if(it%2==0) ParagraphDirection.LTR else ParagraphDirection.RTL)))) }
        val source=AnimatableString(true,"a",FrozenList.of(documents.mapIndexed { i,d -> StringKeyframe(i.toLong(),d.text,d) }),documents[0])
        assertEquals(32,StyledTextPreparation.alternatives(source).size)
        val extra=TextSourceDocument("b")
        assertThrows(IllegalArgumentException::class.java) {
            StyledTextPreparation.alternatives(source.copy(keyframes=FrozenList.of(source.keyframes+StringKeyframe(32,extra.text,extra))))
        }
    }
    private fun metrics()=doubleArrayOf(1000.0,800.0,-200.0,0.0,1.0,2.0,1200.0,
        0.0,0.0,0.0,0.0,700.0,500.0,-700.0,0.0,600.0,0.0,0.0,700.0,500.0,-700.0,
        2.0,0.0,1.0,271828.0,2.0,2.0,1.0,
        0.0,1.0,0.0,0.0,0.0,600.0,1.0,2.0,0.0,0.0,600.0,600.0,
        0.0,0.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,1.0,314159.0,2.0,2.0,1.0)
    @Test fun spacingPaintBaselineAndDistanceBudgetCombineBeforeAnimators() {
        val document=TextSourceDocument("ab").format(0,1) { it.copy(sizeFactor=2f,fill=0xffff0000.toInt(),baselineDelta=5f,trackingDelta=1000f) }
        val text=TextProperties(size=AnimatableProperty1D(staticValue=100f),strokeWidth=AnimatableProperty1D(staticValue=100f))
        val prepared=PreparedTextAnimation(text,metrics(),SelectorEaseTable.shared,document);prepared.evaluate(0)
        assertEquals(200f,prepared.modifiers.getFloat(PreparedTextAnimation.STRIDE+24),.001f)
        assertEquals(-5f,prepared.modifiers.getFloat(28),0f)
        assertEquals(1f,prepared.modifiers.getFloat(32),0f);assertEquals(0f,prepared.modifiers.getFloat(36),0f)
        assertEquals(40f,prepared.modifiers.getFloat(76),.001f);assertEquals(20f,prepared.modifiers.getFloat(PreparedTextAnimation.STRIDE+76),.001f)
        assertEquals(.3f,prepared.modifiers.getFloat(64),.001f);assertEquals(320f,prepared.logicalBounds[2],.001f)
    }
    @Test fun lastCharacterManualKerningChangesTheAdvanceWithoutMovingEarlierGlyphs() {
        val document=TextSourceDocument("ab").format(1,2) { it.copy(manualKerning=FrozenList.of(listOf(TextManualKerning(1,100f)))) }
        val text=TextProperties(size=AnimatableProperty1D(staticValue=100f))
        val prepared=PreparedTextAnimation(text,metrics(),SelectorEaseTable.shared,document);prepared.evaluate(0)
        assertEquals(130f,prepared.logicalBounds[2],.001f);assertEquals(0f,prepared.modifiers.getFloat(24),0f)
        assertEquals(0f,prepared.modifiers.getFloat(PreparedTextAnimation.STRIDE+24),0f)
    }
    @Test fun richPreparedEvaluationAllocatesZeroAfterWarmup() {
        val document=TextSourceDocument("ab").format(0,1) { it.copy(sizeFactor=2f,trackingDelta=25f,baselineDelta=5f,fill=0x80446688.toInt()) }
        val prepared=PreparedTextAnimation(TextProperties(size=AnimatableProperty1D(staticValue=100f)),metrics(),SelectorEaseTable.shared,document)
        repeat(10000) { prepared.evaluate(it.toLong()) }
        val bean=java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported);bean.isThreadAllocatedMemoryEnabled=true
        val id=Thread.currentThread().id;val before=bean.getThreadAllocatedBytes(id)
        for(i in 0 until 600) prepared.evaluate(i.toLong())
        assertEquals(0L,bean.getThreadAllocatedBytes(id)-before)
    }
}
