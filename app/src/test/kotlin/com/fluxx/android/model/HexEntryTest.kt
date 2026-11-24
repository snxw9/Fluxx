package com.fluxx.android.model

import com.fluxx.android.ui.common.colorpicker.ColourValues
import com.fluxx.android.ui.inspector.TextColourEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** GRAPH G2.1 §2: one hex parser behind every typed colour entry; 3, 6 and 8 digits. */
class HexEntryTest {
    private val half = 0x80FF0000.toInt() // red at 50% alpha

    @Test fun acceptedForms() {
        val table = listOf(
            "000000" to 0x80000000.toInt(),
            "#000000" to 0x80000000.toInt(),
            "  000000 " to 0x80000000.toInt(),
            "#fff" to 0x80FFFFFF.toInt(),
            "FFF" to 0x80FFFFFF.toInt(),
            "1aF" to 0x8011AAFF.toInt(),
            "00ff00" to 0x8000FF00.toInt(),
            "00Ff00" to 0x8000FF00.toInt(),
            "80ff0000" to 0x80FF0000.toInt(),
            "#FF0000FF" to 0xFF0000FF.toInt(),
            "00000000" to 0x00000000,
            "4000Ff00" to 0x4000FF00,
        )
        for ((text, expected) in table) {
            assertEquals(text, expected, FluxxColor.parseHexEntry(text, half))
            assertEquals(text, expected, ColourValues.hex(text, half))
            assertEquals(text, expected, TextColourEdit.hex(text, half))
        }
    }

    @Test fun sixAndThreeDigitsKeepTheExactAlphaByte() {
        for (alpha in listOf(0x00, 0x01, 0x7F, 0x80, 0xFE, 0xFF)) {
            val current = (alpha shl 24) or 0x123456
            assertEquals(alpha, FluxxColor.parseHexEntry("000000", current)!! ushr 24)
            assertEquals(alpha, FluxxColor.parseHexEntry("#abc", current)!! ushr 24)
        }
    }

    @Test fun rejectedForms() {
        for (bad in listOf("", " ", "#", "0", "00", "0000", "00000", "0000000", "000000000", "#1234567", "GG0000", "ggg",
                "12 34 56", "#-12345", "+12345", "0x123456", "##123456", "00FF00FF00")) {
            assertNull(bad, FluxxColor.parseHexEntry(bad, half))
            assertNull(bad, ColourValues.hex(bad, half))
            assertNull(bad, TextColourEdit.hex(bad, half))
        }
    }
}
