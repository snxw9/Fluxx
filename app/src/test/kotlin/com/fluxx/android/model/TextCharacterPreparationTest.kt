package com.fluxx.android.model

import org.junit.Assert.*
import org.junit.Test

class TextCharacterPreparationTest {
    private fun metrics(count: Int): DoubleArray {
        val out=ArrayList<Double>()
        out.addAll(listOf(1000.0,800.0,-200.0,0.0,1.0,count.toDouble(),count*600.0))
        for(i in 0 until count) out.addAll(listOf(0.0,i*600.0,0.0,0.0,700.0,500.0,-700.0))
        out.add(count.toDouble());for(i in 0 until count) out.add(i.toDouble())
        out.addAll(listOf(271828.0,count.toDouble(),count.toDouble(),1.0))
        for(i in 0 until count) out.addAll(listOf(i.toDouble(),i+1.0,0.0,0.0,i*600.0,600.0))
        for(i in 0 until count) out.addAll(listOf(0.0,0.0,i.toDouble(),0.0))
        for(i in 0 until count) out.add(i.toDouble())
        return out.toDoubleArray()
    }
    private fun text(property: AnimatorProperty,value: Float)=TextProperties(animators=FrozenList.of(listOf(
        TextAnimator(1,selectors=FrozenList.of(listOf(TextRangeSelector(2,smoothness=AnimatableProperty1D(staticValue=0f)))),properties=FrozenList.of(listOf(
            TextPropertyOverride(property,scalar=AnimatableProperty1D(staticValue=value))))))))
    @Test fun reachabilityIncludesBothSignedInfluencesAndOriginalMarks() {
        val cps=TextCharacterPreparation.codepoints(text(AnimatorProperty.CHARACTER_OFFSET,2f),arrayOf(TextSourceDocument("A\u0301")))
        for(cp in 63..67) assertTrue(cps.contains(cp))
        assertTrue(cps.contains(0x301))
        assertTrue((1 until cps.size).all { cps[it-1]<cps[it] })
    }
    @Test fun absoluteValueAndInvalidResultsHaveExplicitBehaviour() {
        assertEquals(90,TextCharacterPreparation.resolve(65,66,AnimatorProperty.CHARACTER_VALUE,90.0,.1))
        assertEquals(66,TextCharacterPreparation.resolve(65,66,AnimatorProperty.CHARACTER_VALUE,90.0,0.0))
        for(value in doubleArrayOf(0.0,10.0,0x200d.toDouble(),0xd800.toDouble(),0x110000.toDouble(),Double.NaN))
            assertEquals(65,TextCharacterPreparation.resolve(65,66,AnimatorProperty.CHARACTER_VALUE,value,1.0))
        assertEquals(66,TextCharacterPreparation.resolve(65,65,AnimatorProperty.CHARACTER_OFFSET,1.0,.5))
    }
    @Test fun impossibleAbsoluteValuesDoNotPrepareSpuriousUnicodeEndpoints() {
        val cps=TextCharacterPreparation.codepoints(text(AnimatorProperty.CHARACTER_VALUE,2_000_000f),arrayOf(TextSourceDocument("A")))
        assertArrayEquals(intArrayOf(65),cps)
    }
    @Test fun oversizedReachableCatalogueFailsExplicitly() {
        val failure=assertThrows(IllegalArgumentException::class.java) {
            TextCharacterPreparation.codepoints(text(AnimatorProperty.CHARACTER_OFFSET,10000f),arrayOf(TextSourceDocument("A")))
        }
        assertTrue(failure.message!!.contains("4096"))
    }
    @Test fun preparedCharactersResolveAndAllocateZeroAcrossRandomAccess() {
        val document=TextSourceDocument("AB")
        val text=text(AnimatorProperty.CHARACTER_OFFSET,1f).copy(alignment=TextAlignment.LEFT)
        val cps=TextCharacterPreparation.codepoints(text,arrayOf(document))
        val catalogue=TextCharacterCatalogue(arrayOf(TextFontRef(text.fontId)),cps,arrayOf(TextNominalMetrics(cps,metrics(cps.size))))
        val prepared=PreparedTextAnimation(text,metrics(2),SelectorEaseTable.shared,document,characterCatalogue=catalogue)
        prepared.evaluate(0)
        assertEquals(66,prepared.characters!!.codes.getInt(0))
        assertEquals(67,prepared.characters!!.codes.getInt(4))
        assertEquals(1f,prepared.modifiers.getFloat(84),0f)
        val bean=java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled=true
        for(i in 0 until 10000) prepared.evaluate((i%600)*1667L)
        val id=Thread.currentThread().id;val before=bean.getThreadAllocatedBytes(id)
        for(i in 0 until 600) prepared.evaluate((599-i)*1667L)
        assertEquals(0L,bean.getThreadAllocatedBytes(id)-before)
    }
    @Test fun ligatureMembersShareCoverageAndCombiningMarksStayOriginal() {
        val document=TextSourceDocument("A\u0301B")
        val text=text(AnimatorProperty.CHARACTER_OFFSET,2f)
        val cps=TextCharacterPreparation.codepoints(text,arrayOf(document))
        val catalogue=TextCharacterCatalogue(arrayOf(TextFontRef(text.fontId)),cps,arrayOf(TextNominalMetrics(cps,metrics(cps.size))))
        val prepared=PreparedTextCharacters(document,intArrayOf(0),intArrayOf(2),catalogue,text.fontId)
        prepared.begin();prepared.apply(AnimatorProperty.CHARACTER_OFFSET,2.0,doubleArrayOf(.5))
        assertEquals(66,prepared.codes.getInt(0));assertEquals(67,prepared.codes.getInt(4))
        assertArrayEquals(intArrayOf(0x301),prepared.marks[0])
        prepared.apply(AnimatorProperty.CHARACTER_VALUE,0xd800.toDouble(),doubleArrayOf(1.0))
        assertEquals(65,prepared.codes.getInt(0));assertEquals(66,prepared.codes.getInt(4))
    }
}
