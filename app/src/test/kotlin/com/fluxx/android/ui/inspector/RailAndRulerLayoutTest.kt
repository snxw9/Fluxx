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
    // ---- TEXT UI 8: fixed, moderate, consistent control sizes + vertical centring ------------------------------
    private val scales=listOf(1f,1.3f,2f)
    private val picker=com.fluxx.android.ui.common.colorpicker.ColourPickerLayout
    private fun src(path: String)=java.io.File("src/main/kotlin/com/fluxx/android/$path").readText()
    private fun groups(scale: Float)=mapOf(
        "position" to SectionLayout.positionGroup(scale),"rotation" to SectionLayout.dialGroup(),
        "scale-linked" to SectionLayout.scaleGroup(true,scale),"scale-unlinked" to SectionLayout.scaleGroup(false,scale),
        "opacity" to SectionLayout.sliderGroup(1,1,scale),"timing" to SectionLayout.sliderGroup(2,0,scale),
        "timing-video" to SectionLayout.sliderGroup(3,0,scale))

    @Test fun targetSizesAtFontScaleOne() {
        assertEquals(128f,SectionLayout.PAD,0f);assertEquals(40f,SectionLayout.valueBox(1f),0f)
        assertEquals(144f,SectionLayout.DIAL,0f)
        assertEquals(SectionLayout.Ruler(28f,48f,4f),SectionLayout.ruler(1f))
        assertEquals(80f,SectionLayout.scaleGroup(true,1f),0f);assertEquals(2*80f+4f,SectionLayout.scaleGroup(false,1f),0f)
        assertEquals(40f,SectionLayout.text(SectionLayout.BLEND_ROW,1f),0f)
        assertEquals(SectionLayout.SliderRow(20f,6f,20f,48f,72f,40f),SectionLayout.sliderRow(1f))
        assertEquals(40f,SectionLayout.MODE_TILE,0f);assertEquals(40f,SectionLayout.MODE_COLUMN,0f)
        // The readout is titleMedium (never titleLarge) and the ruler takes the final readout height.
        val ruler=src("ui/inspector/ScaleRuler.kt")
        assertTrue(ruler.contains("style = MaterialTheme.typography.titleMedium") && !ruler.contains("titleLarge"))
    }
    @Test fun sizesDoNotDependOnTheSheetHeightOrPreviewState() {
        // No size function takes a content or sheet height: identical at 268 and 328, preview expanded or not.
        for((expanded,scale,height) in sheets) {
            val other=sheets.single {it.first!=expanded && it.second==scale}
            assertNotEquals(height,other.third,0f)
            assertEquals(groups(scale),groups(other.second))
        }
        // Only text-bearing rows grow with font scale; the pad, dial and ruler strip are constant.
        assertEquals(SectionLayout.PAD,SectionLayout.positionGroup(2f)-SectionLayout.valueBox(2f)-SectionLayout.GAP,0f)
        assertEquals(SectionLayout.STRIP,SectionLayout.ruler(2f).strip,0f)
        assertTrue(SectionLayout.ruler(2f).readout>SectionLayout.ruler(1f).readout)
        assertTrue(SectionLayout.sliderRow(2f).label>SectionLayout.sliderRow(1f).label)
        assertEquals(SectionLayout.TRACK,SectionLayout.sliderRow(2f).track,0f);assertEquals(SectionLayout.THUMB,SectionLayout.sliderRow(2f).thumb,0f)
        // The old fill-the-area arithmetic is gone from the pages.
        val sheet=src("ui/inspector/ElementInspectorSheet.kt")
        for(gone in listOf("FittedSquare","maxHeight.value, rows","SectionLayout.ruler(content","valueRow(compact)")) assertFalse(gone,sheet.contains(gone))
    }
    @Test fun groupsAreCentredWithEqualMargins() {
        for((_,scale,height) in sheets) {
            val content=InspectorChromeBudget.contentHeight(height,scale)
            for((name,group) in groups(scale)) {
                val p=SectionLayout.centre(content,group)
                assertTrue("$name fits at $height/$scale",p.fits)
                assertEquals("$name centred at $height/$scale",p.top,p.bottom,1f)
                assertEquals(content,p.top+group+p.bottom,1e-3f)
            }
        }
        // A group taller than its area falls back to top-aligned (scrollable, never clipped, no overlap).
        assertEquals(SectionLayout.Placement(0f,0f,false),SectionLayout.centre(100f,164f))
        // Every Transform mode, Opacity, Timing and Audio go through the centred group.
        val sheet=src("ui/inspector/ElementInspectorSheet.kt")
        for(tag in listOf("transform-group-position","transform-group-rotation","transform-group-scale","opacity-group","timing-group","audio-group"))
            assertTrue(tag,sheet.contains("\"$tag\""))
    }
    @Test fun everythingFitsAtEveryFontScaleAndHorizontally() {
        val col=column(width)
        assertEquals(248f,col,1e-3f)
        for((_,scale,height) in sheets) {
            val content=InspectorChromeBudget.contentHeight(height,scale)
            for((name,group) in groups(scale)) assertTrue("$name fits at $height/$scale",group<=content+1e-3f)
            // The mode column (3 tiles + gaps) fits beside the content.
            assertTrue(3*SectionLayout.MODE_TILE+2*SectionLayout.MODE_GAP<=content)
        }
        // Pad and dial fit between the rail and the mode column on a 360dp window.
        assertTrue(SectionLayout.PAD<=col && SectionLayout.DIAL<=col)
        // The tightest case: video Timing (3 rows) in the 208dp content of a 268dp sheet at font scale 1.
        assertEquals(204f,SectionLayout.sliderGroup(3,0,1f),1e-3f)
    }
    @Test fun touchTargetsAreAtLeast40() {
        for(scale in scales) {
            val row=SectionLayout.sliderRow(scale)
            assertTrue(row.touch>=SectionLayout.MIN_TARGET && row.valueHeight>=SectionLayout.MIN_TARGET && row.valueHeight<=row.touch)
            assertTrue(SectionLayout.valueBox(scale)>=40f && SectionLayout.text(SectionLayout.BLEND_ROW,scale)>=40f)
            assertTrue(SectionLayout.STRIP>=40f && SectionLayout.MODE_TILE>=40f && SectionLayout.PAD>=40f && SectionLayout.DIAL>=40f)
        }
    }
    @Test fun editSolidPickerIsNotStretched() {
        for((_,scale,height) in sheets) {
            val h=InspectorChromeBudget.contentHeight(height,scale)
            val m=picker.full(320f,h)
            // The controls row is the square (capped by SQUARE_MAX), not the page; leftover height centres the picker.
            assertTrue(m.square<=picker.SQUARE_MAX)
            assertEquals(maxOf(m.square,picker.ROWS*picker.ROW_MIN),m.controls,0f)
            assertTrue(m.height<=h+1e-3f)
            assertEquals(h-m.height,2*m.top(h),1e-3f)
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
