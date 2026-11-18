package com.fluxx.android.ui.inspector

import com.fluxx.android.ui.inspector.TextControl.*
import org.junit.Assert.*
import org.junit.Test

/** Source arithmetic for the Edit Text faces at 360dp. Not a device layout measurement. */
class TextInspectorFaceTest {
    private val width=360f
    private fun controls(rows: List<List<TextGridCell>>)=rows.map {r->r.mapNotNull {it.control}}

    @Test fun characterFaceIsTheApprovedFourRows() {
        assertEquals(listOf(listOf(FONT,SIZE,PAINT),listOf(TRACKING,LEADING,BASELINE,STROKE_WIDTH),
            listOf(H_SCALE,V_SCALE,KERNING,ORDER),listOf(BOLD,ITALIC,CASE,SUPER,SUB,LIGATURES)),controls(TextInspectorLayout.rows(TextTab.CHARACTER,1f)))
    }
    @Test fun paragraphFaceIsTheApprovedThreeRows() {
        assertEquals(listOf(listOf(ALIGN_LEFT,ALIGN_CENTRE,ALIGN_RIGHT,JUSTIFY),listOf(INDENT_LEFT,INDENT_RIGHT,INDENT_FIRST,DIRECTION),
            listOf(BEFORE,AFTER,BOX)),controls(TextInspectorLayout.rows(TextTab.PARAGRAPH,1f)))
        assertTrue(TextInspectorLayout.rows(TextTab.ANIMATE,1f).isEmpty())
    }
    @Test fun everyRowFillsTheColumnsExactly() {
        for(scale in listOf(1f,1.3f,2f)) for(tab in listOf(TextTab.CHARACTER,TextTab.PARAGRAPH)) for(row in TextInspectorLayout.rows(tab,scale))
            assertEquals("$tab $scale",TextInspectorLayout.columns(scale).toFloat(),row.sumOf {it.weight.toDouble()}.toFloat(),1e-3f)
    }
    @Test fun largeFontFallbackKeepsEveryControlInTheSameOrder() {
        for(tab in listOf(TextTab.CHARACTER,TextTab.PARAGRAPH)) for(scale in listOf(1.3f,2f)) {
            assertEquals(controls(TextInspectorLayout.rows(tab,1f)).flatten(),controls(TextInspectorLayout.rows(tab,scale)).flatten())
        }
        assertEquals(6,TextInspectorLayout.rows(TextTab.CHARACTER,2f).size)
        assertEquals(4,TextInspectorLayout.rows(TextTab.PARAGRAPH,2f).size)
    }
    @Test fun touchTargetsAt360dp() {
        for(scale in listOf(1f,1.3f,2f)) {
            val columns=TextInspectorLayout.columns(scale)
            for(tab in listOf(TextTab.CHARACTER,TextTab.PARAGRAPH)) for(row in TextInspectorLayout.rows(tab,scale)) for(cell in row) {
                val c=cell.control ?: continue
                val w=TextInspectorBudget.cellWidth(width,cell.weight,columns)
                when(c) {
                    // Fill and Stroke share one cell: each half must still be a 48dp target.
                    PAINT->assertTrue("swatch $scale ${w/2}",w/2>=TextInspectorBudget.MIN_TARGET)
                    // Numeric cells: documented 36x48dp scrub zone beside a separate >=48dp value target.
                    in setOf(SIZE,TRACKING,LEADING,BASELINE,STROKE_WIDTH,H_SCALE,V_SCALE,INDENT_LEFT,INDENT_RIGHT,INDENT_FIRST,BEFORE,AFTER,BOX)->
                        assertTrue("$c value $scale",w-TextInspectorBudget.SCRUB_ZONE>=TextInspectorBudget.MIN_TARGET)
                    else->assertTrue("$c $scale $w",w>=TextInspectorBudget.MIN_TARGET)
                }
            }
        }
    }
    @Test fun shortWindowsPageWholeRowsAndNeverDropAControl() {
        for(capacity in 1..6) for(tab in listOf(TextTab.CHARACTER,TextTab.PARAGRAPH)) {
            val rows=TextInspectorLayout.rows(tab,1f)
            val pages=TextInspectorLayout.pages(rows,capacity)
            assertTrue(pages.all {it.size<=capacity});assertEquals(rows,pages.flatten())
        }
        assertEquals(0,TextInspectorBudget.bodyRows(100f,1f))
        assertEquals(1,TextInspectorLayout.pages(TextInspectorLayout.rows(TextTab.CHARACTER,1f),0).first().size)
    }
    @Test fun overlaysSitBesideTheirAnchorRow() {
        for(rows in 2..4) for(anchor in 0 until rows) {
            val one=TextInspectorLayout.overlayRow(anchor,1,rows)
            assertNotEquals(anchor,one);assertTrue(one in 0 until rows)
        }
        for(anchor in 0 until 4) {
            val two=TextInspectorLayout.overlayRow(anchor,2,4)
            assertFalse("$anchor",anchor in two..two+1);assertTrue(two+1<4)
        }
        assertEquals(0,TextInspectorLayout.overlayRow(null,2,4))
        assertEquals(1,TextInspectorLayout.overlayRow(TextInspectorLayout.rowOf(TextInspectorLayout.characterRows,CASE),2,4))
    }
    @Test fun strokeClampAppearsOnlyWhenClampedAndFitsOneLine() {
        assertNull(TextFeedback.strokeClamp(24f,null))
        assertEquals("Requested 24 px | Effective 6.0 px | SDF limit",TextFeedback.strokeClamp(24f,"Apply · clamp 6.0–6.0 px"))
        assertEquals("Requested 24.5 px | Effective 2.1–6.0 px | SDF spread / size limit",TextFeedback.strokeClampFull(24.5f,"Apply · clamp 2.1–6.0 px"))
        // Width estimate: 0.52em average advance for labelSmall (11sp). Real glyph widths are asserted by the
        // instrumented TextInspectorLayoutTest; this keeps the envelope honest at source level.
        for(scale in listOf(1f,1.3f,2f)) {
            val worst=requireNotNull(TextFeedback.strokeClamp(999.99f,"Apply · clamp 99.99–99.99 px",scale))
            assertTrue("$scale $worst",worst.length*0.52f*11f*scale<=width-2*TextInspectorBudget.SIDE)
        }
        assertEquals("boom",TextFeedback.line("boom","msg",null,"stroke"))
        assertEquals("stroke",TextFeedback.line(null,null,null,"stroke"))
    }
}
