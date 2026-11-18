package com.fluxx.android.ui.inspector

import com.fluxx.android.ui.inspector.TextControl.*
import org.junit.Assert.*
import org.junit.Test

/** Source arithmetic for the Edit Text faces (top row, two 3×3 blocks, bottom bar). Not a device layout measurement. */
class TextInspectorFaceTest {
    private val content=TextInspectorBudget.contentWidth(360f)

    @Test fun characterFaceIsTopRowTwoThreeByThreeBlocksAndBottomBar() {
        val f=TextInspectorLayout.face(TextTab.CHARACTER)!!
        assertEquals(listOf(EXPLODE,PAINT,ORDER),f.top)
        assertEquals(listOf(listOf(STROKE_WIDTH,TRACKING,LEADING),listOf(H_SCALE,SCALE_LINK,V_SCALE),listOf(BASELINE,KERNING)),f.left)
        assertEquals(listOf(listOf(BOLD,ITALIC,UNDERLINE),listOf(STRIKE,SUPER,SUB),listOf(ALL_CAPS,LOWERCASE,LIGATURES)),f.right)
        assertEquals(listOf(FONT,SIZE,SOURCE),TextInspectorLayout.bottomBar)
        assertTrue(f.left.all {it.size<=3} && f.right.all {it.size<=3} && f.rows==3)
        // The short last row (Baseline, Kerning) is centred, never padded with filler.
        assertEquals(.5f,TextInspectorLayout.rowOffset(2),0f);assertEquals(0f,TextInspectorLayout.rowOffset(3),0f)
    }
    @Test fun paragraphFaceUsesTheSameSkeleton() {
        val f=TextInspectorLayout.face(TextTab.PARAGRAPH)!!
        assertEquals(listOf(ALIGN_LEFT,ALIGN_CENTRE,ALIGN_RIGHT,JUSTIFY),f.top)
        assertEquals(listOf(listOf(INDENT_LEFT,INDENT_RIGHT,INDENT_FIRST),listOf(BEFORE,AFTER)),f.left)
        assertEquals(listOf(listOf(BOX_MODE,BOX,DIRECTION)),f.right)
        assertTrue(TextInspectorLayout.hasBottomBar(TextTab.PARAGRAPH))
        assertNull(TextInspectorLayout.face(TextTab.ANIMATE));assertFalse(TextInspectorLayout.hasBottomBar(TextTab.ANIMATE))
    }
    @Test fun smallCapsIsGoneAndEveryOtherCapabilityIsReachable() {
        assertTrue(TextControl.entries.none {it.name=="SMALL_CAPS"})
        val reachable=TextTab.entries.flatMap {TextInspectorLayout.inventory(it)}.toSet()+setOf(MANUAL_KERNING) // rail ⋮
        for(c in listOf(SOURCE,FONT,SIZE,PAINT,STROKE_WIDTH,ORDER,TRACKING,LEADING,BASELINE,H_SCALE,V_SCALE,KERNING,BOLD,ITALIC,
            ALL_CAPS,LOWERCASE,SUPER,SUB,LIGATURES,ALIGN_LEFT,ALIGN_CENTRE,ALIGN_RIGHT,JUSTIFY,INDENT_LEFT,INDENT_RIGHT,INDENT_FIRST,
            BEFORE,AFTER,BOX,BOX_MODE,DIRECTION,MANUAL_KERNING,EXPLODE)) assertTrue("$c",c in reachable)
        for(tab in TextTab.entries) {val inv=TextInspectorLayout.inventory(tab);assertEquals(inv.size,inv.toSet().size)}
        for(c in listOf(SIZE,TRACKING,LEADING,BASELINE,STROKE_WIDTH,H_SCALE,V_SCALE,INDENT_LEFT,INDENT_RIGHT,INDENT_FIRST,BEFORE,AFTER,BOX,MANUAL_KERNING))
            assertNotNull("$c",PropertyLimits.forControl(c,false))
    }
    @Test fun compactAndStandardHeightsFitExactly() {
        val compact=TextInspectorBudget.metrics(268f,content)
        assertTrue(compact.compact)
        assertEquals(212f,compact.body,0f);assertEquals(36f,compact.tile,0f);assertEquals(116f,compact.middle,0f);assertEquals(36f,compact.bottom,0f)
        assertEquals(44f,compact.top,0f)
        // Fill/Stroke tile 28 high with the 16dp switch stacked directly under it.
        assertEquals(28f,compact.paintTile,0f);assertEquals(16f,compact.switchHeight,0f);assertEquals(0f,compact.switchGap,0f);assertFalse(compact.switchBeside)
        val standard=TextInspectorBudget.metrics(328f,content)
        assertFalse(standard.compact)
        assertEquals(272f,standard.body,0f);assertEquals(44f,standard.tile,0f);assertEquals(140f,standard.middle,0f);assertEquals(44f,standard.bottom,0f)
        assertEquals(72f,standard.top,0f)
        assertEquals(44f,standard.paintTile,0f);assertEquals(4f,standard.switchGap,0f);assertEquals(24f,standard.switchHeight,0f)
        for(m in listOf(compact,standard)) {
            assertEquals(m.body,m.topBand+m.middleBand+m.bottomBand,1e-3f)
            assertEquals(m.middleBand,m.middleRows.sum(),1e-3f)
            assertEquals(4f,m.gap,0f);assertEquals(8f,m.groupGap,0f)
            assertTrue(m.fits)
        }
    }
    @Test fun noOverflowAndEveryCellIsAFullTouchTargetAtEveryHeightAndFontScale() {
        for(sheet in listOf(268f,328f)) for(scale in listOf(1f,1.3f,2f)) for(width in listOf(320f,360f,412f)) {
            val height=InspectorSheetBudget.height(sheet==268f,scale)
            val cw=TextInspectorBudget.contentWidth(width)
            if(TextInspectorBudget.large(scale)) {
                // Labelled 3-column reflow: whole rows page above the bottom bar, inside the scaled sheet.
                val capacity=TextInspectorBudget.labelledCapacity(height,scale)
                for(tab in listOf(TextTab.CHARACTER,TextTab.PARAGRAPH)) {
                    val pages=TextInspectorLayout.pages(TextInspectorLayout.labelledRows(tab),capacity)
                    assertEquals(TextInspectorLayout.labelledRows(tab),pages.flatten())
                    for(p in pages) assertTrue("$sheet $scale",p.size*TextInspectorBudget.LABELLED_PITCH+TextInspectorBudget.LABELLED_PITCH<=TextInspectorBudget.bodyHeight(height,scale)+1e-3f)
                }
                assertTrue((cw-TextInspectorBudget.BLOCK_SPACER)/6f>=TextInspectorBudget.MIN_TARGET)
            } else {
                val m=TextInspectorBudget.metrics(height,cw,scale)
                assertTrue("$sheet $scale $width fits",m.fits)
                assertTrue(m.pitchV>=40f && m.pitchH>=40f && m.middleRows.min()>=40f && m.bottomBand>=40f)
                assertEquals(cw,6*m.cell+TextInspectorBudget.BLOCK_SPACER,1e-3f)
                // Blocks and the bottom row share column edges: font chip = 2 cells, size = 1, text field = 3.
                assertEquals(m.blockWidth,3*m.cell-m.gap,1e-3f)
            }
        }
    }
    @Test fun switchMovesBesideTheTileOnlyWhenStackingCannotFit() {
        val tight=TextInspectorBudget.forBody(206f,content,true) // top = 38 < 28 + 16
        assertTrue(tight.switchBeside);assertEquals(0f,tight.switchGap,0f)
        assertFalse(TextInspectorBudget.forBody(212f,content,true).switchBeside)
    }
    @Test fun largeFontFallbackKeepsEveryControlInTheSameOrder() {
        for(tab in listOf(TextTab.CHARACTER,TextTab.PARAGRAPH)) {
            assertEquals(TextInspectorLayout.face(tab)!!.controls,TextInspectorLayout.labelledRows(tab).flatten().mapNotNull {it.control})
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
