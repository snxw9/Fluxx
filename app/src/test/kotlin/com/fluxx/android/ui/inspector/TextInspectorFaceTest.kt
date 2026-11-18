package com.fluxx.android.ui.inspector

import com.fluxx.android.ui.inspector.TextControl.*
import org.junit.Assert.*
import org.junit.Test

/** Source arithmetic for the Edit Text faces at 360dp. Not a device layout measurement. */
class TextInspectorFaceTest {
    private val width=360f

    @Test fun characterFaceIsRow1Row2StyleGridAndBottomBar() {
        assertEquals(listOf(PAINT,STROKE_WIDTH,ORDER),TextInspectorLayout.characterRow1)
        assertEquals(listOf(TRACKING,LEADING,H_SCALE,SCALE_LINK,V_SCALE,BASELINE),TextInspectorLayout.characterRow2)
        assertEquals(listOf(listOf(BOLD,ITALIC,UNDERLINE),listOf(STRIKE,SUPER,SUB),listOf(ALL_CAPS,LOWERCASE,LIGATURES)),TextInspectorLayout.styleGrid)
        assertEquals(listOf(FONT,SIZE,SOURCE),TextInspectorLayout.bottomBar)
        val rows=TextInspectorLayout.faceRows(TextTab.CHARACTER)
        assertEquals(5,rows.size)
        assertEquals(listOf(BOLD,ITALIC,UNDERLINE,SMALL_CAPS,KERNING),rows[2])
    }
    @Test fun paragraphFaceKeepsAlignmentsAndAnIconGrid() {
        assertEquals(listOf(listOf(ALIGN_LEFT,ALIGN_CENTRE,ALIGN_RIGHT,JUSTIFY),listOf(INDENT_LEFT,INDENT_RIGHT,INDENT_FIRST,BEFORE,AFTER),
            listOf(BOX_MODE,BOX,DIRECTION)),TextInspectorLayout.faceRows(TextTab.PARAGRAPH))
        assertTrue(TextInspectorLayout.hasBottomBar(TextTab.PARAGRAPH))
        assertTrue(TextInspectorLayout.faceRows(TextTab.ANIMATE).isEmpty());assertFalse(TextInspectorLayout.hasBottomBar(TextTab.ANIMATE))
    }
    @Test fun everyPreviousCapabilityStillHasATileOrMenuEntry() {
        val reachable=TextTab.entries.flatMap {TextInspectorLayout.inventory(it)}.toSet()+setOf(MANUAL_KERNING,RELATIVE_SIZE) // header ⋮
        for(c in listOf(SOURCE,FONT,SIZE,PAINT,STROKE_WIDTH,ORDER,TRACKING,LEADING,BASELINE,H_SCALE,V_SCALE,KERNING,BOLD,ITALIC,
            ALL_CAPS,LOWERCASE,SMALL_CAPS,SUPER,SUB,LIGATURES,ALIGN_LEFT,ALIGN_CENTRE,ALIGN_RIGHT,JUSTIFY,INDENT_LEFT,INDENT_RIGHT,INDENT_FIRST,
            BEFORE,AFTER,BOX,BOX_MODE,DIRECTION,MANUAL_KERNING,RELATIVE_SIZE)) assertTrue("$c",c in reachable)
        // Every numeric tile has a limit (slider popover); every tile is distinct.
        for(tab in TextTab.entries) {val inv=TextInspectorLayout.inventory(tab);assertEquals(inv.size,inv.toSet().size)}
        for(c in listOf(SIZE,TRACKING,LEADING,BASELINE,STROKE_WIDTH,H_SCALE,V_SCALE,INDENT_LEFT,INDENT_RIGHT,INDENT_FIRST,BEFORE,AFTER,BOX,MANUAL_KERNING,RELATIVE_SIZE))
            assertNotNull("$c",PropertyLimits.forControl(c,false))
    }
    @Test fun rowsFitTheContentWidthAt360dpWithStandardTargets() {
        val content=TextInspectorBudget.contentWidth(width)
        assertEquals(292f,content,1e-3f)
        val pitch=TextInspectorBudget.pitch(compact=false)
        for(tab in listOf(TextTab.CHARACTER,TextTab.PARAGRAPH)) for(row in TextInspectorLayout.faceRows(tab)) {
            val separator=if(row.size>3 && tab==TextTab.CHARACTER && row.first()==BOLD) 9f else 0f
            assertTrue("$tab $row",row.size*pitch+separator<=content)
        }
        // Bottom bar: font chip (2 pitches) + size + a text field of at least 96dp.
        assertTrue(3*pitch+96f<=content)
        // Row 1: two tiles plus the labelled switch (≈ 90dp label + 52dp switch).
        assertTrue(2*pitch+90f+52f+6f<=content)
        assertTrue(TextInspectorBudget.pitch(true)>=TextInspectorBudget.MIN_TARGET)
        assertEquals(48f,TextInspectorBudget.pitch(false),0f)
    }
    @Test fun largeFontFallbackKeepsEveryControlInTheSameOrder() {
        for(tab in listOf(TextTab.CHARACTER,TextTab.PARAGRAPH)) {
            assertEquals(TextInspectorLayout.faceRows(tab).flatten(),TextInspectorLayout.labelledRows(tab).flatten().mapNotNull {it.control})
            for(row in TextInspectorLayout.labelledRows(tab)) assertEquals(3f,row.sumOf {it.weight.toDouble()}.toFloat(),1e-3f)
        }
        assertEquals(3,TextInspectorLayout.columns(1.3f));assertEquals(6,TextInspectorLayout.columns(1f))
    }
    @Test fun shortWindowsPageWholeRowsAndNeverDropAControl() {
        for(capacity in 0..8) for(tab in listOf(TextTab.CHARACTER,TextTab.PARAGRAPH)) {
            val rows=TextInspectorLayout.labelledRows(tab)
            val pages=TextInspectorLayout.pages(rows,capacity)
            assertTrue(pages.all {it.size<=capacity.coerceAtLeast(1)});assertEquals(rows,pages.flatten())
        }
    }
    @Test fun strokeClampAppearsOnlyWhenClamped() {
        assertNull(TextFeedback.strokeClamp(24f,null))
        assertEquals("Requested 24 px | Effective 6.0 px | SDF limit",TextFeedback.strokeClamp(24f,"Apply · clamp 6.0–6.0 px"))
        assertEquals("Requested 24.5 px | Effective 2.1–6.0 px | SDF spread / size limit",TextFeedback.strokeClampFull(24.5f,"Apply · clamp 2.1–6.0 px"))
        assertEquals("boom",TextFeedback.line("boom","msg",null,"stroke"))
    }
    @Test fun tileCaptionsStayTiny() {
        assertEquals("72",tileCaption(PropertyLimits.FONT_SIZE,72f))
        assertEquals("1k",tileCaption(PropertyLimits.FONT_SIZE,1000f))
        assertEquals("-500",tileCaption(PropertyLimits.TRACKING,-500f))
        assertEquals("2.5",tileCaption(PropertyLimits.STROKE_WIDTH,2.5f))
        for(v in listOf(-1000f,-999.5f,0f,12.25f,999.99f,20000f)) assertTrue("$v",tileCaption(PropertyLimits.BOX_WIDTH,v).length<=5)
    }
}
