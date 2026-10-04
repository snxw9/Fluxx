package com.fluxx.android.model

import org.junit.Assert.*
import org.junit.Test

class TextSourceDocumentTest {
    @Test fun boundariesKeepSurrogatesAndCombiningMarksIntact() {
        val d=TextSourceDocument("a\u0301\uD83D\uDE00b")
        assertArrayEquals(intArrayOf(0,2,4,5),d.boundaries)
        assertThrows(IllegalArgumentException::class.java) {
            d.copy(runs=FrozenList.of(listOf(TextStyleRun(0,1),TextStyleRun(1,5))))
        }
        val formatted=d.format(1,3) { it.copy(fauxBold=true) }
        assertEquals(4,formatted.runs.first().end)
    }
    @Test fun selectionFormattingInsertionDeletionAndMergePreserveStyles() {
        val d=TextSourceDocument("abcd").format(1,3) { it.copy(sizeFactor=2f) }
        assertEquals(listOf(1,3,4),d.runs.map { it.end })
        val inserted=d.replace("abXcd")
        assertEquals(2f,inserted.runAt(2)!!.style.sizeFactor,0f)
        assertEquals(d,inserted.replace("abcd"))
        assertEquals(1,d.format(0,0) { TextRunStyle() }.runs.size)
        assertTrue(d.replace("").runs.isEmpty())
    }
    @Test fun holdEvaluationReturnsPreparedDocumentWithoutRebuildingIt() {
        val a=TextSourceDocument("office")
        val b=a.format(0,0) { it.copy(caps=TextCaps.ALL) }
        val p=AnimatableString(true,a.text,FrozenList.of(listOf(StringKeyframe(0,a.text,a),StringKeyframe(10,b.text,b))),a)
        assertSame(a,p.evaluateDocument(9));assertSame(b,p.evaluateDocument(10))
    }
    @Test fun richCodecRoundTripKeepsUnknownFontsAndInactiveFormatting() {
        val style=TextRunStyle(font=TextFontRef("unknown.requested",TextFaceStyle.BOLD),fill=0x80445566.toInt(),
            strokeDelta=2f,caps=TextCaps.SMALL,script=TextScriptPosition.SUPER,kerning=TextKerning.OFF,
            manualKerning=FrozenList.of(listOf(TextManualKerning(0,30f))))
        val d=TextSourceDocument("ab",FrozenList.of(listOf(TextStyleRun(0,2,style))))
        val layer=CompositionLayer(1,LayerType.TEXT,0,text=TextProperties(source=AnimatableString(staticValue=d.text,document=d)))
        val project=ProjectDocument(Composition(layers=FrozenList.of(listOf(layer))))
        assertEquals(project,ProjectCodec.decode(ProjectCodec.encode(project)))
    }
    @Test fun invalidCountsAndNumbersAreRejectedBeforePreparation() {
        assertThrows(IllegalArgumentException::class.java) { TextRunStyle(strokeDelta=Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { TextSourceDocument("a",boxWidth=-1f) }
        assertThrows(IllegalArgumentException::class.java) { TextFontRef(sha256="bad") }
        assertThrows(IllegalArgumentException::class.java) { TextSourceDocument("a\nb",paragraphs=FrozenList.of(listOf(TextParagraphStyle()))) }
        assertThrows(IllegalArgumentException::class.java) { TextFontAxis("wght",AnimatableProperty1D(isAnimated=true)) }
        val s="a".repeat(257)
        assertThrows(IllegalArgumentException::class.java) { TextSourceDocument(s,FrozenList.of(List(257) { TextStyleRun(it,it+1) })) }
    }
}
