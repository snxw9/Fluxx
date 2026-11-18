package com.fluxx.android.ui.inspector

import com.fluxx.android.editor.AnimPropertyType
import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test

/** Back state machine, header menu, rail key state and icon distinctness. Source-level, not device acceptance. */
class TextInspectorChromeTest {
    private val layer=CompositionLayer(1,LayerType.TEXT,text=TextProperties(source=AnimatableString(staticValue="Text")))
    private val numeric=TextInspectorMode.Numeric(TextNumericDraft(layer,0,FrameRate(),TextEditScope(null),TextControl.SIZE))
    private val colour=TextInspectorMode.Colour(TextControl.FILL,layer,0,TextEditScope(null))

    @Test fun backLeavesTheInnermostStateFirstThenReturnsToTheGrid() {
        val b=TextInspectorBack
        assertEquals(TextBackStep.CLOSE_EXPLODE,b.step(numeric,true,true))
        for(draft in listOf(numeric,colour,TextInspectorMode.Source)) assertEquals(TextBackStep.CANCEL_DRAFT,b.step(draft,false,false))
        assertEquals(TextBackStep.CLOSE_CONFIRM,b.step(TextInspectorMode.Browse,false,true))
        assertEquals(TextBackStep.PARENT_CHOICE,b.step(TextInspectorMode.Popover(TextControl.BASE_ALIGNMENT),false,false))
        for(choice in listOf(TextControl.CASE,TextControl.JUSTIFY,TextControl.BOX))
            assertEquals(TextBackStep.CLOSE_CHOICE,b.step(TextInspectorMode.Popover(choice),false,false))
        assertEquals(TextBackStep.CLOSE_FONTS,b.step(TextInspectorMode.Fonts,false,false))
        // Nothing open: no intermediate "clear selection" step; selection is cleared on the way to the grid.
        assertEquals(TextBackStep.RETURN_TO_GRID,b.step(TextInspectorMode.Browse,false,false))
    }
    @Test fun backSequenceFromTheDeepestStateTerminatesAtTheGrid() {
        // Base alignment row -> Justify row -> Browse -> layer inspector grid.
        var mode: TextInspectorMode=TextInspectorMode.Popover(TextControl.BASE_ALIGNMENT)
        val seen=mutableListOf<TextBackStep>()
        while(true) {
            val step=TextInspectorBack.step(mode,false,false);seen+=step
            mode=when(step) {TextBackStep.PARENT_CHOICE->TextInspectorMode.Popover(TextControl.JUSTIFY);TextBackStep.RETURN_TO_GRID->break;else->TextInspectorMode.Browse}
        }
        assertEquals(listOf(TextBackStep.PARENT_CHOICE,TextBackStep.CLOSE_CHOICE,TextBackStep.RETURN_TO_GRID),seen)
    }
    @Test fun headerMenuHasExactlyFourItems() {
        assertEquals(listOf(TextMenuItem.EXPLODE,TextMenuItem.RESET_STYLE,TextMenuItem.MANUAL_KERNING,TextMenuItem.RELATIVE_SIZE),TextHeaderMenu.items)
        val labels=TextHeaderMenu.items.map {TextHeaderMenu.label(it,TextTab.CHARACTER,"Whole layer")}
        assertEquals(listOf("Explode Text","Reset Character style · Whole layer","Manual kerning","Relative size"),labels)
        assertEquals("Reset Paragraph style · Selection: 3 characters",TextHeaderMenu.label(TextMenuItem.RESET_STYLE,TextTab.PARAGRAPH,"Selection: 3 characters"))
        assertFalse(TextHeaderMenu.enabled(TextMenuItem.RESET_STYLE,TextTab.ANIMATE))
        for(gone in listOf("Character controls","Paragraph controls","Animate","Done editing","Clear selection","Layer tools","Reset paragraph style ("))
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
        // Size / vertical scale / horizontal scale are three distinct shapes, none is the old A + arrow form.
        val scales=listOf("SIZE","V_SCALE","H_SCALE").map {TextInspectorIcons.path(it)}
        assertEquals(3,scales.toSet().size);assertTrue(scales.none {it.startsWith("M4,20 L10,4")})
        // Source no longer resembles ragged alignment lines.
        val alignments=ParagraphAlignment.entries.map {TextInspectorIcons.alignmentPath(it)}
        assertEquals(alignments.size,alignments.toSet().size)
        assertTrue(alignments.none {it==TextInspectorIcons.path("SOURCE")})
    }
    @Test fun sheetIsSizedForTheFourRowFace() {
        assertEquals(344f,TextInspectorBudget.sheetHeight(1f),1e-3f)
        assertEquals(4,TextInspectorBudget.bodyRows(344f,1f))
    }
}
