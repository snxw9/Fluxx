package com.fluxx.android.ui.inspector

import org.junit.Assert.*
import org.junit.Test

/**
 * Pure measurement arithmetic for the section pages (Transform, Blending & Opacity, Timing) at both shared heights
 * and font scales 1 / 1.3 / 2: the single header row, the freed height, the compact rail, the scale ruler (which must
 * never measure to zero), the position pad, the dial and the slider rows. Mirrors the constants the sheet uses.
 */
class RailAndRulerLayoutTest {
    private val sheets=listOf(true,false).flatMap {e->listOf(1f,1.3f,2f).map {s->Triple(e,s,InspectorSheetBudget.height(e,s))}}
    private val width=360f-InspectorSheetBudget.FRAME_H
    private fun column(width: Float)=width-RailBudget.TILE-2*TextInspectorBudget.RAIL_GAP-SectionLayout.MODE_COLUMN

    @Test fun oneHeaderRowAndTheContentAreaGrewByTheRemovedRows() {
        // 268: 268 − 16 frame − 40 header − 4 = 208 (was 158); 328: 328 − 16 − 40 − 4 = 268 (was 200).
        assertEquals(208f,InspectorChromeBudget.contentHeight(268f,1f),1e-3f)
        assertEquals(268f,InspectorChromeBudget.contentHeight(328f,1f),1e-3f)
        assertEquals(158f,InspectorChromeBudget.legacyContentHeight(268f,1f),1e-3f)
        assertEquals(200f,InspectorChromeBudget.legacyContentHeight(328f,1f),1e-3f)
        // The header is exactly Edit Text's header.
        for(s in listOf(1f,1.3f,2f)) assertEquals(TextInspectorBudget.header(s),InspectorChromeBudget.header(s),0f)
        for((_,scale,height) in sheets) {
            val grown=InspectorChromeBudget.contentHeight(height,scale)-InspectorChromeBudget.legacyContentHeight(height,scale)
            val compact=InspectorSheetBudget.compact(height)
            // Freed = handle + name row + old tab row − the new header row.
            val freed=InspectorChromeBudget.HANDLE+InspectorChromeBudget.nameRow(compact)+InspectorChromeBudget.legacyTabRow(compact,scale)-InspectorChromeBudget.header(scale)
            assertEquals("freed at $height/$scale",freed,grown,1e-3f)
            assertTrue("content grew at $height/$scale",grown>0f)
            // At font scale 1 the content grows by at least the whole removed name row.
            if(scale==1f) assertTrue(grown>=InspectorChromeBudget.nameRow(compact))
        }
    }
    @Test fun railIsATightTopAlignedStackOf40dpTiles() {
        assertEquals(40f,RailBudget.TILE,0f);assertEquals(6f,RailBudget.GAP,0f)
        assertEquals(132f,RailBudget.full(3),0f)
        assertEquals(40f,RailBudget.disabledHeight(212f,2,1),0f)
        // With the freed height the full four-item Transform/Opacity rail (178dp) now fits at 268 without shrinking.
        assertEquals(178f,RailBudget.full(4),0f)
        assertEquals(40f,RailBudget.disabledHeight(InspectorChromeBudget.contentHeight(268f,1f),2,2),0f)
        assertEquals(30f,RailBudget.disabledHeight(158f,2,2),1e-3f)
        for((_,scale,height) in sheets) {
            val h=InspectorChromeBudget.contentHeight(height,scale)
            val d=RailBudget.disabledHeight(h,2,2)
            assertTrue("rail fits at $height/$scale",2*RailBudget.TILE+2*d+3*RailBudget.GAP<=h+1e-3f)
        }
    }
    @Test fun scaleRulerUsesTheFreedHeightAndAlwaysFits() {
        // Defaults unchanged for callers without a budget.
        assertEquals(48f,ScaleRulerMath.STRIP_DP,0f);assertEquals(76f,ScaleRulerMath.rulerHeight(1f),0f)
        val content=InspectorChromeBudget.contentHeight(268f,1f)
        // 268, linked: larger readout and the tallest strip; unlinked Width + Height both at 64dp too (204 ≤ 208).
        assertEquals(SectionLayout.Ruler(32f,64f,4f,1f),SectionLayout.ruler(content,true,1f))
        assertEquals(SectionLayout.Ruler(32f,64f,4f,1f),SectionLayout.ruler(content,false,1f))
        assertEquals(204f,SectionLayout.scaleController(content,false,1f),1e-3f)
        for((_,scale,height) in sheets) for(linked in listOf(true,false)) {
            val h=InspectorChromeBudget.contentHeight(height,scale)
            val r=SectionLayout.ruler(h,linked,scale)
            assertTrue("strip never shrinks below 48 at $height/$scale",r.strip>=SectionLayout.STRIP_MIN)
            assertTrue("strip larger than TEXT UI 6 at $height/$scale",r.strip>=ScaleRulerMath.STRIP_DP)
            assertTrue("ruler fits at $height/$scale linked=$linked",SectionLayout.scaleController(h,linked,scale)<=h+1e-3f)
        }
    }
    @Test fun positionPadAndDialAreLargerAndFit() {
        val content=InspectorChromeBudget.contentHeight(268f,1f)
        val col=column(width)
        // Pad below a 40dp value row and an 8dp gap: 160dp at 268 (was about 118); dial 208dp (was 158).
        assertEquals(160f,SectionLayout.padSide(content,col,true),1e-3f)
        assertEquals(minOf(col,208f),SectionLayout.dialSide(content,col),1e-3f)
        for((_,scale,height) in sheets) {
            val h=InspectorChromeBudget.contentHeight(height,scale);val compact=InspectorSheetBudget.compact(height)
            val pad=SectionLayout.padSide(h,col,compact)
            assertTrue(pad>0f && SectionLayout.valueRow(compact)+SectionLayout.GAP+pad<=h+1e-3f && pad<=col)
            assertTrue(SectionLayout.dialSide(h,col)<=minOf(h,col)+1e-3f)
            // The mode column (3 x value row + gaps) is top-aligned and fits beside the content.
            assertTrue(3*SectionLayout.valueRow(compact)+2*SectionLayout.MODE_GAP<=h)
        }
    }
    @Test fun sliderRowsAreLargerAndNeverOverflow() {
        val content=InspectorChromeBudget.contentHeight(268f,1f)
        val opacity=SectionLayout.sliderRow(content,1,1,true,1f)
        assertEquals(SectionLayout.TOUCH_MAX,opacity.touch,0f)
        assertEquals(SectionLayout.TRACK_THICK,opacity.track,0f);assertEquals(SectionLayout.THUMB_LARGE,opacity.thumb,0f)
        for((_,scale,height) in sheets) {
            val h=InspectorChromeBudget.contentHeight(height,scale);val compact=InspectorSheetBudget.compact(height)
            for((rows,fixed) in listOf(1 to 1,2 to 0,3 to 0)) {
                val r=SectionLayout.sliderRow(h,rows,fixed,compact,scale)
                assertTrue("touch row ≥ 40 at $height/$scale",r.touch>=SectionLayout.MIN_TARGET)
                assertTrue("rows fit at $height/$scale ($rows+$fixed)",SectionLayout.sliderRowsHeight(r,rows,fixed,compact)<=h+1e-3f)
            }
        }
    }
    @Test fun dragRightDecreasesAndDragLeftIncreases() {
        val m=ScaleRulerMath
        assertTrue(m.drag(1f,m.travel(40f,0f))<1f)
        assertTrue(m.drag(1f,m.travel(-40f,0f))>1f)
        assertTrue(m.drag(2f,m.travel(400f,1500f))<2f)
        assertTrue(m.inertiaStep(1500f,200f)<0f && m.inertiaStep(-1500f,200f)>0f)
    }
    @Test fun tickLabelsFadeSmoothlyNearTheNeedle() {
        val m=ScaleRulerMath
        assertEquals(12f,m.NEEDLE_CLEAR_DP,0f)
        assertEquals(0f,m.needleFade(0f),0f);assertEquals(0f,m.needleFade(-5f),0f)
        assertEquals(1f,m.needleFade(12f),0f);assertEquals(1f,m.needleFade(40f),0f)
        assertEquals(.5f,m.needleFade(6f),1e-4f)
        // Smooth: monotonic with no jump (a pop would be a step).
        var last=0f
        for(i in 0..120) {val v=m.needleFade(i/10f);assertTrue(v>=last && v-last<.03f);last=v}
        // The label drawn under the needle at 100% (gap < 0) is fully hidden; behaviour/maths unchanged.
        assertEquals(1f,m.drag(1f,0f),0f)
    }
}
