package com.fluxx.android.ui.inspector

import org.junit.Assert.*
import org.junit.Test

/**
 * Pure measurement arithmetic for the Transform page at both shared heights and large font scales: the compact rail
 * stack and the scale ruler (which must never measure to zero height again). Mirrors the constants the sheet uses.
 */
class RailAndRulerLayoutTest {
    private val sheets=listOf(true,false).flatMap {e->listOf(1f,1.3f,2f).map {s->Triple(e,s,InspectorSheetBudget.height(e,s))}}

    @Test fun transformContentHeightAtTheOriginalSheetHeights() {
        // 268: 268 − 16 frame − 20 handle − 40 name row − 30 tabs − 4 = 158; 328: 328 − 16 − 20 − 48 − 40 − 4 = 200.
        assertEquals(158f,InspectorChromeBudget.contentHeight(268f,1f),1e-3f)
        assertEquals(200f,InspectorChromeBudget.contentHeight(328f,1f),1e-3f)
        for((_,scale,height) in sheets) assertTrue(InspectorChromeBudget.contentHeight(height,scale)>0f)
    }
    @Test fun railIsATightTopAlignedStackOf40dpTiles() {
        assertEquals(40f,RailBudget.TILE,0f);assertEquals(6f,RailBudget.GAP,0f)
        // Text pages: diamond, Graph, ⋮ (no 3D) = 132dp, always full size.
        assertEquals(132f,RailBudget.full(3),0f)
        assertEquals(40f,RailBudget.disabledHeight(212f,2,1),0f)
        // Transform/Opacity keep the disabled 3D: 4 × 40 + 3 × 6 = 178 > 158 at 268, so only the two disabled
        // placeholders shrink (to 30dp); the diamond and ⋮ stay 40dp touch targets.
        assertEquals(178f,RailBudget.full(4),0f)
        val content=InspectorChromeBudget.contentHeight(268f,1f)
        val shrunk=RailBudget.disabledHeight(content,2,2)
        assertEquals(30f,shrunk,1e-3f)
        assertTrue(2*RailBudget.TILE+2*shrunk+3*RailBudget.GAP<=content+1e-3f)
        assertEquals(40f,RailBudget.disabledHeight(InspectorChromeBudget.contentHeight(328f,1f),2,2),0f)
        for((_,scale,height) in sheets) {
            val h=InspectorChromeBudget.contentHeight(height,scale)
            val d=RailBudget.disabledHeight(h,2,2)
            assertTrue("rail fits at $height/$scale",2*RailBudget.TILE+2*d+3*RailBudget.GAP<=h+1e-3f)
        }
    }
    @Test fun scaleRulerAlwaysHasANonZeroStripAndFitsBothHeights() {
        assertEquals(48f,ScaleRulerMath.STRIP_DP,0f)
        assertEquals(76f,ScaleRulerMath.rulerHeight(1f),0f)
        assertEquals(156f,ScaleRulerMath.controllerHeight(linked=false,1f),0f)
        for((_,scale,height) in sheets) for(linked in listOf(true,false)) {
            val content=InspectorChromeBudget.contentHeight(height,scale)
            assertTrue("ruler fits at $height/$scale linked=$linked",ScaleRulerMath.controllerHeight(linked,scale)<=content+1e-3f)
        }
    }
    @Test fun dragRightDecreasesAndDragLeftIncreases() {
        val m=ScaleRulerMath
        assertTrue(m.drag(1f,m.travel(40f,0f))<1f)
        assertTrue(m.drag(1f,m.travel(-40f,0f))>1f)
        assertTrue(m.drag(2f,m.travel(400f,1500f))<2f)
        // Inertia continues in the direction of the fling.
        assertTrue(m.inertiaStep(1500f,200f)<0f && m.inertiaStep(-1500f,200f)>0f)
    }
}
