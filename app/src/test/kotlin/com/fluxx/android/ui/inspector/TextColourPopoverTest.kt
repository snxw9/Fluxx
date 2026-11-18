package com.fluxx.android.ui.inspector

import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test

/** Colour popover state: target toggle, hex and alpha entry, live apply and exactly one undo entry per interaction. */
class TextColourPopoverTest {
    private val layer=CompositionLayer(1,LayerType.TEXT,text=TextProperties(source=AnimatableString(staticValue="Text"),fill=AnimatableColour(staticValue=0xFFFF0000.toInt())))
    private class Sink: TextDraftSink {
        val events=mutableListOf<String>();val previews=mutableListOf<TextProperties>()
        override fun begin() {events+="begin"}
        override fun preview(payload: TextProperties) {events+="preview";previews+=payload}
        override fun finish() {events+="finish"}
        override fun cancel() {events+="cancel"}
    }

    @Test fun targetToggleSwitchesBetweenFillAndStroke() {
        val s=TextColourSession(Sink())
        assertEquals(TextControl.FILL,s.target)
        s.target(TextControl.STROKE);assertEquals(TextControl.STROKE,s.target)
        s.target(TextControl.PAINT);assertEquals(TextControl.FILL,s.target)
    }
    @Test fun hexEntryKeepsOrReplacesAlphaAndRejectsInvalidText() {
        val current=0x80FF0000.toInt()
        assertEquals(0x8000FF00.toInt(),TextColourEdit.hex("00FF00",current))
        assertEquals(0x8000FF00.toInt(),TextColourEdit.hex("#00ff00",current))
        assertEquals(0x4000FF00,TextColourEdit.hex("4000FF00",current))
        for(bad in listOf("","zzz","12345","#1234567")) assertNull(bad,TextColourEdit.hex(bad,current))
        assertEquals("FF0000FF",TextColourEdit.hexText(0xFF0000FF.toInt()));assertEquals("800000FF",TextColourEdit.hexText(0x800000FF.toInt()))
    }
    @Test fun alphaEntryAndSwatchesKeepTheColourChannels() {
        assertEquals(100f,TextColourEdit.alphaPercent(-1),0f)
        assertEquals(0x80123456.toInt(),TextColourEdit.withAlphaPercent(0xFF123456.toInt(),50.2f))
        assertEquals(0x00123456,TextColourEdit.withAlphaPercent(0xFF123456.toInt(),-5f))
        assertEquals(0xFF123456.toInt(),TextColourEdit.withAlphaPercent(0x00123456,500f))
        // A swatch keeps the current alpha; a fully transparent colour becomes opaque.
        assertEquals(0x800000FF.toInt(),TextColourEdit.swatch(0x80FF0000.toInt(),0xFF0000FF.toInt()))
        assertEquals(0xFF0000FF.toInt(),TextColourEdit.swatch(0x00FF0000,0xFF0000FF.toInt()))
        // HSV from the square/hue bar keeps the current alpha.
        assertEquals(0x80FF0000.toInt(),TextColourEdit.hsv(0f,1f,1f,0x80000000.toInt()))
    }
    @Test fun aDragAppliesLiveAndIsOneTransaction() {
        val sink=Sink();val s=TextColourSession(sink)
        s.begin(layer,0,TextEditScope(null));s.begin(layer,0,TextEditScope(null)) // a second begin inside a drag is ignored
        for(v in listOf(0xFF00FF00.toInt(),0xFF0000FF.toInt(),0xFF000000.toInt())) s.preview(v)
        s.finish();s.finish()
        assertEquals(listOf("begin","preview","preview","preview","finish"),sink.events)
        assertEquals(0xFF000000.toInt(),sink.previews.last().fill.staticValue)
        assertFalse(s.active)
    }
    @Test fun oneShotEditsAndCancelledDragsNeverLeakTransactions() {
        val sink=Sink();val s=TextColourSession(sink)
        s.target(TextControl.STROKE)
        s.set(layer,0,TextEditScope(null),0xFF336699.toInt())
        assertEquals(listOf("begin","preview","finish"),sink.events)
        assertEquals(0xFF336699.toInt(),sink.previews.single().strokeColour.staticValue)
        sink.events.clear()
        s.begin(layer,0,TextEditScope(null));s.preview(1);s.cancel();s.preview(2)
        assertEquals(listOf("begin","preview","cancel"),sink.events)
        // A selection edits the run override, not the layer paint.
        val runSink=Sink()
        TextColourSession(runSink).set(layer,0,TextEditScope(TextSelectionRange(0,2)),0xFF00FF00.toInt())
        val payload=runSink.previews.single()
        assertEquals(0xFF00FF00.toInt(),payload.source.sourceDocument.runAt(0)!!.style.fill)
        assertEquals(0xFFFF0000.toInt(),payload.fill.staticValue)
    }
    @Test fun oneInteractionIsOneUndoEntryInTheEditor() {
        val original=ProjectDocument(Composition(layers=FrozenList.of(listOf(layer))))
        val editor=EditorViewModel();editor.dispatch(EditorAction.Load(original))
        val sink=Sink();val s=TextColourSession(sink)
        s.begin(layer,0,TextEditScope(null));s.preview(0xFF00FF00.toInt());s.preview(0xFF0000FF.toInt());s.finish()
        // The editor receives the last live preview once at finish: one undo entry restores the original.
        editor.dispatch(EditorAction.SetTextPayload(1,sink.previews.last()))
        assertEquals(0xFF0000FF.toInt(),editor.state.project.composition.layers.single().text.fill.staticValue)
        editor.undo();assertEquals(original,editor.state.project);assertFalse(editor.canUndo)
    }
}
