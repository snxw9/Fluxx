package com.fluxx.android.ui.inspector

import com.fluxx.android.editor.AnimPropertyType
import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test

/** Back state machine, rail ⋮ menu, Explode pill, key state glyphs and the shared sheet height. Source-level only. */
class TextInspectorChromeTest {
    @Test fun backClosesPopoverThenSubPageThenReturnsToTheGrid() {
        val b=TextInspectorBack
        assertEquals(TextBackStep.CLOSE_EXPLODE,b.step(TextInspectorMode.Fonts,TextControl.SIZE,true,true))
        assertEquals(TextBackStep.CLOSE_CONFIRM,b.step(TextInspectorMode.Browse,null,false,true))
        // A popover (colour included: it has no page of its own any more) always closes first.
        for(page in listOf(TextInspectorMode.Browse,TextInspectorMode.Fonts))
            for(p in listOf(TextControl.TRACKING,TextControl.PAINT,TextControl.MANUAL_KERNING)) assertEquals(TextBackStep.CLOSE_POPOVER,b.step(page,p,false,false))
        assertEquals(TextBackStep.PARENT_CHOICE,b.step(TextInspectorMode.Browse,TextControl.BASE_ALIGNMENT,false,false))
        assertEquals(TextBackStep.CLOSE_PAGE,b.step(TextInspectorMode.Fonts,null,false,false))
        assertEquals(TextBackStep.FINISH_SOURCE,b.step(TextInspectorMode.Source,null,false,false))
        assertEquals(TextBackStep.RETURN_TO_GRID,b.step(TextInspectorMode.Browse,null,false,false))
    }
    @Test fun backSequenceFromTheDeepestStateTerminatesAtTheGrid() {
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
        val chain=mutableListOf<TextBackStep>();popover=TextControl.BASE_ALIGNMENT
        while(popover!=null) {val s=TextInspectorBack.step(TextInspectorMode.Browse,popover,false,false);chain+=s;popover=if(s==TextBackStep.PARENT_CHOICE) TextControl.JUSTIFY else null}
        assertEquals(listOf(TextBackStep.PARENT_CHOICE,TextBackStep.CLOSE_POPOVER),chain)
    }
    @Test fun railOverflowIsTheOnlyMenuWithTheSpecifiedItems() {
        val owner=AnimPropertyType.FONT_SIZE
        assertEquals(listOf(TextRailItem.RESET_VALUE,TextRailItem.ANIMATION,TextRailItem.RESET_STYLE,TextRailItem.MANUAL_SPACING),
            TextRailMenu.items(owner,animated=false,onKey=false,tab=TextTab.CHARACTER))
        assertEquals(listOf(TextRailItem.RESET_VALUE,TextRailItem.ANIMATION,TextRailItem.RESET_ANIMATION,TextRailItem.EASING,TextRailItem.RESET_STYLE,TextRailItem.MANUAL_SPACING),
            TextRailMenu.items(owner,animated=true,onKey=true,tab=TextTab.CHARACTER))
        // Hold owners always offer the (disabled) Hold entry; Paragraph has no manual spacing; Animate has no style reset.
        assertTrue(TextRailItem.EASING in TextRailMenu.items(AnimPropertyType.SOURCE_TEXT,false,false,TextTab.PARAGRAPH))
        assertFalse(TextRailItem.MANUAL_SPACING in TextRailMenu.items(owner,false,false,TextTab.PARAGRAPH))
        assertEquals(listOf(TextRailItem.RESET_VALUE),TextRailMenu.items(null,false,false,TextTab.ANIMATE))
        val labels=TextRailMenu.items(owner,true,true,TextTab.CHARACTER).map {TextRailMenu.label(it,TextTab.CHARACTER,"Whole layer","Font size",true)}
        assertEquals(listOf("Reset Font size value","Freeze at playhead","Reset animation…","Hold / easing","Reset Character style · Whole layer","Manual spacing…"),labels)
        assertEquals("Enable animation",TextRailMenu.label(TextRailItem.ANIMATION,TextTab.CHARACTER,"x","y",false))
        assertEquals("Reset Paragraph style · Selection: 3 characters",TextRailMenu.label(TextRailItem.RESET_STYLE,TextTab.PARAGRAPH,"Selection: 3 characters","",false))
        // Relative size is not a menu item: the Size tile edits relative run size whenever text is selected.
        assertEquals("Relative run size",TextEditScope(TextSelectionRange(0,2)).title(TextControl.SIZE))
        for(gone in listOf("Explode","Relative size","Done")) assertTrue(gone,labels.none {it.contains(gone)})
    }
    @Test fun explodeIsAPillInTheCharacterTopRowOnly() {
        assertEquals(TextControl.EXPLODE,TextInspectorLayout.characterTop.first())
        assertEquals(listOf(TextControl.EXPLODE,TextControl.PAINT,TextControl.ORDER),TextInspectorLayout.face(TextTab.CHARACTER)!!.top)
        assertFalse(TextControl.EXPLODE in TextInspectorLayout.inventory(TextTab.PARAGRAPH))
        assertTrue(TextInspectorLayout.inventory(TextTab.ANIMATE).isEmpty())
    }
    @Test fun diamondCarriesKeyStateIncludingHold() {
        assertEquals("KEY",TextKeyState.icon(AnimPropertyType.FONT_SIZE,false,false))
        assertEquals("KEY_ANIM",TextKeyState.icon(AnimPropertyType.FONT_SIZE,true,false))
        assertEquals("KEY_ON",TextKeyState.icon(AnimPropertyType.FONT_SIZE,true,true))
        assertEquals("HOLD_ANIM",TextKeyState.icon(AnimPropertyType.SOURCE_TEXT,true,false))
        assertEquals("HOLD_ON",TextKeyState.icon(AnimPropertyType.LIGATURES,true,true))
        assertEquals("SOURCE_TEXT: on key at 00:00:01, Hold",TextKeyState.description(AnimPropertyType.SOURCE_TEXT,true,true,"00:00:01"))
        assertEquals("FONT_SIZE: animation off",TextKeyState.description(AnimPropertyType.FONT_SIZE,false,false,"x"))
        val states=listOf("KEY","KEY_ANIM","KEY_ON","HOLD_ANIM","HOLD_ON").map {InspectorIcons.glyph(it)}
        assertEquals(states.size,states.toSet().size)
    }
    @Test fun everyInspectorPageUsesTheOriginalSheetHeights() {
        assertEquals(268f,InspectorSheetBudget.EXPANDED,0f);assertEquals(328f,InspectorSheetBudget.STANDARD,0f)
        assertEquals(268f,InspectorSheetBudget.height(true,1f),0f);assertEquals(328f,InspectorSheetBudget.height(false,.85f),0f)
        assertEquals(1.3f*328f,InspectorSheetBudget.height(false,1.3f),1e-3f)
        assertTrue(InspectorSheetBudget.compact(268f));assertFalse(InspectorSheetBudget.compact(328f))
        // Frame chrome 16 + header 40 = 56: body 212 at 268, 272 at 328.
        assertEquals(212f,TextInspectorBudget.bodyHeight(268f,1f),0f);assertEquals(272f,TextInspectorBudget.bodyHeight(328f,1f),0f)
    }
}
