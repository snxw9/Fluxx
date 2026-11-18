package com.fluxx.android.ui.common.colorpicker

import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import com.fluxx.android.ui.inspector.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The shared picker family: one component, COMPACT + FULL, target toggle, live apply and one undo per interaction. */
class ColourPickerFamilyTest {
    private val events=mutableListOf<String>();private val previews=mutableListOf<Int>()
    private fun tx()=ColourTransaction({events+="begin"},{events+="preview";previews+=it},{events+="finish"},{events+="cancel"})
    private fun src(path: String)=File("src/main/kotlin/com/fluxx/android/$path").readText()

    @Test fun bothVariantsComeFromOneComponentAndEverySurfaceUsesIt() {
        assertEquals(listOf("COMPACT","FULL"),ColourPickerVariant.entries.map {it.name})
        // The old picker and its forked pieces are gone; every colour surface uses the shared component.
        for(gone in listOf("ui/common/colorpicker/FluxxColorPicker.kt","ui/common/colorpicker/HexColorInput.kt","ui/common/colorpicker/AlphaSlider.kt"))
            assertFalse(gone,File("src/main/kotlin/com/fluxx/android/$gone").exists())
        val surfaces=mapOf(
            "ui/inspector/TextInspectorPanel.kt" to "ColourPickerVariant.COMPACT",
            "ui/inspector/ElementInspectorSheet.kt" to "ColourPickerVariant.FULL",
            "ui/browser/AddContentSheet.kt" to "ColourPickerVariant.FULL",
            "ui/timeline/MarkerPopover.kt" to "ColourPickerVariant.COMPACT",
            "ui/common/colorpicker/PaletteBrowser.kt" to "ColourPickerVariant.COMPACT")
        for((file,variant) in surfaces) {
            val text=src(file)
            assertTrue("$file uses $variant",text.contains("FluxxColourPicker(") && text.contains(variant))
            assertFalse("$file still uses the old picker",text.contains("FluxxColorPicker("))
        }
        // No colour maths fork: the shared component builds on SaturationValueBox, HueStrip and FluxxColor.
        val picker=src("ui/common/colorpicker/FluxxColourPicker.kt")
        for(piece in listOf("SaturationValueBox(","HueStrip(","FluxxColor.fromHsv","FluxxColor.parseHex")) assertTrue(piece,picker.contains(piece))
    }
    @Test fun targetToggleOnlyWithSeveralTargets() {
        assertEquals(listOf(TextControl.FILL,TextControl.STROKE),TEXT_COLOUR_TARGETS)
        val picker=src("ui/common/colorpicker/FluxxColourPicker.kt")
        assertTrue(picker.contains("if (targets.size > 1) targets.forEachIndexed"))
        // Text colour session: toggling retargets Fill/Stroke without leaking a transaction.
        val s=TextColourSession(object: TextDraftSink {override fun begin() {};override fun preview(payload: TextProperties) {};override fun finish() {};override fun cancel() {}})
        s.target(TEXT_COLOUR_TARGETS[1]);assertEquals(TextControl.STROKE,s.target);assertFalse(s.active)
    }
    @Test fun anInteractionAppliesLiveAndIsOneTransaction() {
        val t=tx()
        t.begin();t.begin()
        for(v in listOf(1,2,3)) t.preview(v)
        t.finish();t.finish()
        assertEquals(listOf("begin","preview","preview","preview","finish"),events)
        assertEquals(listOf(1,2,3),previews);assertFalse(t.active)
        events.clear();t.preview(9);assertTrue(events.isEmpty())
        t.set(0xFF336699.toInt());assertEquals(listOf("begin","preview","finish"),events)
        events.clear();t.begin();t.preview(4);t.cancel();t.cancel()
        assertEquals(listOf("begin","preview","cancel"),events)
    }
    @Test fun recentColoursRecordFinishedInteractions() {
        ColourRecents.colours.clear()
        val t=ColourTransaction({},{},{},{},ColourRecents::add)
        t.begin();t.preview(1);t.preview(2);t.finish()
        t.begin();t.preview(5);t.cancel()
        t.set(2)
        assertEquals(listOf(2),ColourRecents.colours.toList())
        for(i in 10..40) ColourRecents.add(i)
        assertEquals(ColourRecents.MAX,ColourRecents.colours.size);assertEquals(40,ColourRecents.colours.first())
        ColourRecents.colours.clear()
    }
    @Test fun oneInteractionIsOneUndoEntryInTheEditor() {
        val layer=CompositionLayer(1,LayerType.TEXT,text=TextProperties(source=AnimatableString(staticValue="Text"),fill=AnimatableColour(staticValue=0xFFFF0000.toInt())))
        val original=ProjectDocument(Composition(layers=FrozenList.of(listOf(layer))))
        val editor=EditorViewModel();editor.dispatch(EditorAction.Load(original))
        var last: TextProperties?=null
        val session=TextColourSession(object: TextDraftSink {
            override fun begin() {};override fun preview(payload: TextProperties) {last=payload}
            override fun finish() {editor.dispatch(EditorAction.SetTextPayload(1,last!!))};override fun cancel() {last=null}
        })
        // The picker's transaction drives the text session: SV drag of three moves = one undo entry.
        val t=ColourTransaction({session.begin(layer,0,TextEditScope(null))},session::preview,session::finish,session::cancel)
        t.begin();t.preview(0xFF00FF00.toInt());t.preview(0xFF0000FF.toInt());t.finish()
        assertEquals(0xFF0000FF.toInt(),editor.state.project.composition.layers.single().text.fill.staticValue)
        editor.undo();assertEquals(original,editor.state.project);assertFalse(editor.canUndo)
    }
    @Test fun valuesParseHexAlphaAndChannels() {
        val current=0x80FF0000.toInt()
        // RRGGBB keeps alpha; AARRGGBB replaces it; # optional; any case.
        assertEquals(0x8000FF00.toInt(),ColourValues.hex("00FF00",current))
        assertEquals(0x8000FF00.toInt(),ColourValues.hex("#00ff00",current))
        assertEquals(0x4000FF00,ColourValues.hex("4000ff00",current))
        for(bad in listOf("","0","00FF0","#1234567","GG0000","00FF00FF00")) assertNull(bad,ColourValues.hex(bad,current))
        assertEquals("80FF0000",ColourValues.hexText(current))
        assertEquals(0x00FF0000,ColourValues.alpha("0",current))
        assertEquals(0xFFFF0000.toInt(),ColourValues.alpha("100%",current))
        for(bad in listOf("","-1","101","abc")) assertNull(bad,ColourValues.alpha(bad,current))
        assertEquals("50",ColourValues.alphaText(current))
        assertEquals(0x80FF7F00.toInt(),ColourValues.channel("127",current,8))
        assertNull(ColourValues.channel("256",current,8));assertNull(ColourValues.channel("x",current,0))
        assertEquals("255",ColourValues.channelText(current,16))
        assertEquals(0x800000FF.toInt(),ColourValues.swatch(current,0xFF0000FF.toInt()))
        assertEquals(0xFF0000FF.toInt(),ColourValues.swatch(0x00FF0000,0xFF0000FF.toInt()))
    }
    @Test fun fullVariantScalesUpAndNeverOverflowsTheSectionPages() {
        for(sheet in listOf(268f,328f)) for(scale in listOf(1f,1.3f,2f)) {
            val h=InspectorChromeBudget.contentHeight(InspectorSheetBudget.height(sheet==268f,scale),scale)
            val m=ColourPickerLayout.full(320f,h)
            assertTrue("fits $sheet/$scale",m.header+m.controls+ColourPickerLayout.GAP+m.palette<=h+1e-3f)
            // Larger than the compact 96dp square, palette below the controls.
            assertTrue("square $sheet/$scale",m.square>96f)
            assertTrue(m.row>=ColourPickerLayout.ROW_MIN && m.row*ColourPickerLayout.ROWS<=m.square+1e-3f)
        }
        // A tall page (the Add Solid tab) gets the recents row too.
        assertTrue(ColourPickerLayout.full(360f,420f).recents)
        assertFalse(ColourPickerLayout.full(360f,208f).recents)
    }
    @Test fun markerColourIsAnAnchoredPopoverNotACentredPicker() {
        val marker=src("ui/timeline/MarkerPopover.kt")+src("ui/timeline/TimelineView.kt")
        assertTrue(marker.contains("AnchoredPopover(markerPopoverOpen") && marker.contains("MARKER_SWATCHES"))
        assertFalse(marker.contains("Modifier.height(208.dp)"))
        assertEquals(0xFFF44336.toInt(),MARKER_SWATCHES[0]);assertEquals(0xFFFF9800.toInt(),MARKER_SWATCHES[1])
        // Anchored above its control and clamped to the window with 8dp margins (never centred).
        val margin=8;val w=1080;val h=2400
        val left=PopoverPlacer.place(0,1500,200,1548,800,600,w,h,h,margin,4)
        assertEquals(margin,left.x);assertTrue(left.above);assertEquals(1500-4-600,left.y)
        val right=PopoverPlacer.place(1000,1500,1080,1548,800,600,w,h,h,margin,4)
        assertEquals(w-800-margin,right.x)
        val top=PopoverPlacer.place(400,20,600,68,800,600,w,h,h,margin,4)
        assertFalse(top.above);assertEquals(68+4,top.y)
        val centred=(w-800)/2
        assertNotEquals(centred,left.x);assertNotEquals(centred,right.x)
    }
}
