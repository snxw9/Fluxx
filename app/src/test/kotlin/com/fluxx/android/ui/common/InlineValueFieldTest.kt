package com.fluxx.android.ui.common

import com.fluxx.android.ui.common.colorpicker.ColourValues
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** In-place hex / alpha / RGB entry: faded previous value, live apply, revert, one undo per session, no nested popup. */
class InlineValueFieldTest {
    private val events=mutableListOf<String>()
    private var colour=0x80FF0000.toInt()
    private var before=colour
    private val sink=object: ValueSink {
        override fun begin() {events+="begin";before=colour}
        override fun preview(value: Int) {events+="preview";colour=value}
        override fun finish() {events+="finish"}
        override fun cancel() {events+="cancel";colour=before}
    }
    private fun hexSession()=InlineEntrySession(ColourValues::hex,sink)
    private fun alphaSession()=InlineEntrySession(ColourValues::alpha,sink)

    @Test fun clearingShowsTheFadedPreviousValue() {
        val s=hexSession()
        s.focus(ColourValues.hexText(colour),colour)
        assertFalse(s.placeholderVisible)
        s.edit("")
        assertTrue(s.placeholderVisible);assertEquals("80FF0000",s.previous)
        assertEquals(.4f,PLACEHOLDER_ALPHA,0f)
        // Typing replaces the faded value.
        s.edit("0");assertFalse(s.placeholderVisible)
    }
    @Test fun leavingItEmptyChangesNothing() {
        val s=hexSession();val original=colour
        s.focus(ColourValues.hexText(colour),colour);s.edit("")
        assertFalse(s.end())
        assertTrue(events.isEmpty());assertEquals(original,colour);assertEquals("80FF0000",s.text)
    }
    @Test fun aValidHexAppliesLiveOnceAndIsOneUndo() {
        val s=hexSession()
        s.focus(ColourValues.hexText(colour),colour)
        for(prefix in listOf("0","00","00F","00FF","00FF0")) s.edit(prefix)
        assertTrue("incomplete text applies nothing",events.isEmpty())
        s.edit("00FF00");s.edit("00FF00")
        assertEquals(listOf("begin","preview"),events);assertEquals(0x8000FF00.toInt(),colour)
        assertTrue(s.end())
        assertEquals(listOf("begin","preview","finish"),events)
        // A second session is a second (separate) undo entry; a blur without a session does nothing.
        assertFalse(s.end());assertEquals(3,events.size)
    }
    @Test fun invalidTextRevertsOnBlur() {
        val s=hexSession();val original=colour
        s.focus(ColourValues.hexText(colour),colour)
        s.edit("00FF00");s.edit("00FF00Z")
        assertFalse(s.end())
        assertEquals(listOf("begin","preview","cancel"),events);assertEquals(original,colour)
        events.clear()
        s.focus("80FF0000",colour);s.edit("nope");s.end()
        assertTrue(events.isEmpty())
    }
    @Test fun rrggbbKeepsAlphaAndAarrggbbReplacesIt() {
        var s=hexSession()
        s.focus("80FF0000",colour);s.edit("#0000ff");s.end()
        assertEquals(0x800000FF.toInt(),colour)
        s=hexSession();s.focus("800000FF",colour);s.edit("FF00FF00");s.end()
        assertEquals(0xFF00FF00.toInt(),colour)
    }
    @Test fun alphaAcceptsZeroToHundredAndEscapeCancels() {
        var s=alphaSession()
        s.focus("100",colour);s.edit("5");s.edit("50");s.end()
        assertEquals(listOf("begin","preview","preview","finish"),events)
        assertEquals(0x80,colour ushr 24)
        events.clear()
        s=alphaSession();s.focus("50",colour);s.edit("101");s.end()
        assertTrue(events.isEmpty())
        s=alphaSession();val kept=colour;s.focus("50",colour);s.edit("20");s.cancel()
        assertEquals(listOf("begin","preview","cancel"),events);assertEquals(kept,colour)
    }
    @Test fun theFieldNeverUsesANestedPopupOrDialog() {
        val field=File("src/main/kotlin/com/fluxx/android/ui/common/InlineValueField.kt").readText()
        val picker=File("src/main/kotlin/com/fluxx/android/ui/common/colorpicker/FluxxColourPicker.kt").readText()
        for(text in listOf(field,picker)) for(popup in listOf("Popup(","Dialog(","AnchoredPopover(","InspectorValueField(","DropdownMenu("))
            assertFalse(popup,text.contains(popup))
        // Hex, alpha and R/G/B all go through the reusable in-place field.
        assertEquals(3,Regex("""InlineValueField\(""").findAll(picker).count())
    }
}
