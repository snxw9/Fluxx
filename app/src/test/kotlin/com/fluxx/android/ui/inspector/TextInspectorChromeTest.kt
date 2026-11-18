package com.fluxx.android.ui.inspector

import com.fluxx.android.editor.AnimPropertyType
import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test

/** Back state machine, header menu, rail key state, icon distinctness and the shared sheet height. Source-level only. */
class TextInspectorChromeTest {
    private val layer=CompositionLayer(1,LayerType.TEXT,text=TextProperties(source=AnimatableString(staticValue="Text")))
    private val colour=TextInspectorMode.Colour(TextControl.FILL,layer,0,TextEditScope(null))

    @Test fun backClosesPopoverThenSubPageThenReturnsToTheGrid() {
        val b=TextInspectorBack
        assertEquals(TextBackStep.CLOSE_EXPLODE,b.step(colour,TextControl.SIZE,true,true))
        assertEquals(TextBackStep.CLOSE_CONFIRM,b.step(TextInspectorMode.Browse,null,false,true))
        // A popover always closes first, even on a sub-page.
        for(page in listOf(TextInspectorMode.Browse,TextInspectorMode.Fonts,colour))
            assertEquals(TextBackStep.CLOSE_POPOVER,b.step(page,TextControl.TRACKING,false,false))
        assertEquals(TextBackStep.PARENT_CHOICE,b.step(TextInspectorMode.Browse,TextControl.BASE_ALIGNMENT,false,false))
        assertEquals(TextBackStep.CLOSE_PAGE,b.step(TextInspectorMode.Fonts,null,false,false))
        assertEquals(TextBackStep.CLOSE_PAGE,b.step(colour,null,false,false))
        assertEquals(TextBackStep.FINISH_SOURCE,b.step(TextInspectorMode.Source,null,false,false))
        assertEquals(TextBackStep.RETURN_TO_GRID,b.step(TextInspectorMode.Browse,null,false,false))
    }
    @Test fun backSequenceFromTheDeepestStateTerminatesAtTheGrid() {
        // Axis popover on the Fonts page -> Fonts page -> Character tab -> landing grid.
        var mode: TextInspectorMode=TextInspectorMode.Fonts
        var popover: TextControl?=TextControl.FONT
        val seen=mutableListOf<TextBackStep>()
        while(true) {
            val step=TextInspectorBack.step(mode,popover,false,false);seen+=step
            when(step) {
                TextBackStep.CLOSE_POPOVER->popover=null
                TextBackStep.PARENT_CHOICE->popover=TextControl.JUSTIFY
                TextBackStep.CLOSE_PAGE,TextBackStep.FINISH_SOURCE->mode=TextInspectorMode.Browse
                TextBackStep.RETURN_TO_GRID->break
                else->fail("unexpected $step")
            }
        }
        assertEquals(listOf(TextBackStep.CLOSE_POPOVER,TextBackStep.CLOSE_PAGE,TextBackStep.RETURN_TO_GRID),seen)
        // Base alignment row returns to its Justify parent before closing.
        val chain=mutableListOf<TextBackStep>();popover=TextControl.BASE_ALIGNMENT
        while(popover!=null) {val s=TextInspectorBack.step(TextInspectorMode.Browse,popover,false,false);chain+=s;popover=if(s==TextBackStep.PARENT_CHOICE) TextControl.JUSTIFY else null}
        assertEquals(listOf(TextBackStep.PARENT_CHOICE,TextBackStep.CLOSE_POPOVER),chain)
    }
    @Test fun headerMenuHasExactlyFourItems() {
        assertEquals(listOf(TextMenuItem.EXPLODE,TextMenuItem.RESET_STYLE,TextMenuItem.MANUAL_KERNING,TextMenuItem.RELATIVE_SIZE),TextHeaderMenu.items)
        val labels=TextHeaderMenu.items.map {TextHeaderMenu.label(it,TextTab.CHARACTER,"Whole layer")}
        assertEquals(listOf("Explode Text","Reset Character style · Whole layer","Manual kerning","Relative size"),labels)
        assertEquals("Reset Paragraph style · Selection: 3 characters",TextHeaderMenu.label(TextMenuItem.RESET_STYLE,TextTab.PARAGRAPH,"Selection: 3 characters"))
        assertFalse(TextHeaderMenu.enabled(TextMenuItem.RESET_STYLE,TextTab.ANIMATE))
        for(gone in listOf("Character controls","Paragraph controls","Animate","Done","Clear selection","Layer tools"))
            assertTrue(gone,labels.none {it.contains(gone)})
    }
    @Test fun diamondCarriesKeyStateIncludingHold() {
        assertEquals("KEY",TextKeyState.icon(AnimPropertyType.FONT_SIZE,false,false))
        assertEquals("KEY_ANIM",TextKeyState.icon(AnimPropertyType.FONT_SIZE,true,false))
        assertEquals("KEY_ON",TextKeyState.icon(AnimPropertyType.FONT_SIZE,true,true))
        assertEquals("HOLD_ANIM",TextKeyState.icon(AnimPropertyType.SOURCE_TEXT,true,false))
        assertEquals("HOLD_ON",TextKeyState.icon(AnimPropertyType.LIGATURES,true,true))
        assertEquals("SOURCE_TEXT: on key at 00:00:01, Hold",TextKeyState.description(AnimPropertyType.SOURCE_TEXT,true,true,"00:00:01"))
        assertEquals("FONT_SIZE: animation off",TextKeyState.description(AnimPropertyType.FONT_SIZE,false,false,"x"))
        val states=listOf("KEY","KEY_ANIM","KEY_ON","HOLD_ANIM","HOLD_ON").map {TextInspectorIcons.glyph(it)}
        assertEquals(states.size,states.toSet().size)
    }
    @Test fun noTwoVisibleIconsShareAGlyphUnlessDeliberateAliases() {
        val names=TextInspectorIcons.VISIBLE
        for(i in names.indices) for(j in i+1 until names.size) {
            val a=names[i];val b=names[j]
            if(TextInspectorIcons.ALIASES.any {a in it && b in it}) continue
            assertNotEquals("$a looks identical to $b",TextInspectorIcons.glyph(a),TextInspectorIcons.glyph(b))
        }
        val scales=listOf("SIZE","V_SCALE","H_SCALE").map {TextInspectorIcons.path(it)}
        assertEquals(3,scales.toSet().size);assertTrue(scales.none {it.startsWith("M4,20 L10,4")})
        val alignments=ParagraphAlignment.entries.map {TextInspectorIcons.alignmentPath(it)}
        assertEquals(alignments.size,alignments.toSet().size)
        assertTrue(alignments.none {it==TextInspectorIcons.path("SOURCE")})
        // No ✓ Done, − or + glyph is shown anywhere in the text UI any more.
        for(gone in listOf("DONE","MINUS","PLUS")) assertFalse(gone,gone in names)
        // Every face control resolves to a distinct glyph or an alias.
        for(tab in TextTab.entries) for(c in TextInspectorLayout.inventory(tab)) assertNotNull(TextInspectorIcons.control(c))
    }
    @Test fun editTextUsesTheSharedInspectorHeight() {
        // Same height as Transform: the shared budget is sized for the 6-row Character tab.
        assertEquals(308f,InspectorSheetBudget.EXPANDED,0f);assertEquals(344f,InspectorSheetBudget.STANDARD,0f)
        assertEquals(InspectorSheetBudget.EXPANDED,TextInspectorBudget.pageHeight(compact=true),1e-3f)
        assertEquals(InspectorSheetBudget.STANDARD,TextInspectorBudget.pageHeight(compact=false),1e-3f)
        assertTrue(InspectorSheetBudget.compact(InspectorSheetBudget.EXPANDED));assertFalse(InspectorSheetBudget.compact(InspectorSheetBudget.STANDARD))
        // Old budgets provably cannot hold six rows of 40dp tiles plus the frame and header.
        assertTrue(268f-InspectorSheetBudget.FRAME_V-TextInspectorBudget.HEADER<6*TextInspectorBudget.pitch(true))
        assertTrue(328f-InspectorSheetBudget.FRAME_V-TextInspectorBudget.HEADER<6*TextInspectorBudget.pitch(false))
        assertEquals(6,TextInspectorBudget.bodyRows(InspectorSheetBudget.EXPANDED,1f,true))
        assertEquals(6,TextInspectorBudget.bodyRows(InspectorSheetBudget.STANDARD,1f,false))
        assertEquals(1.3f*344f,InspectorSheetBudget.height(false,1.3f),1e-3f)
    }
}
