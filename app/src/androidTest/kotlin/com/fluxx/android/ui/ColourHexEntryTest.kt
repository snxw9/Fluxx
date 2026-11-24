package com.fluxx.android.ui

import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.fluxx.android.ui.common.colorpicker.ColourPickerVariant
import com.fluxx.android.ui.common.colorpicker.FluxxColourPicker
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** GRAPH G2.1 §2 on the A16: the shared picker's hex field takes 6 digits (keeps alpha), 3 digits and 8 digits; 4 digits revert. */
class ColourHexEntryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sixDigitsKeepAlphaThreeAndEightWorkFourReverts() {
        val colour = mutableIntStateOf(0x80FF0000.toInt()) // red, 50% alpha
        var commits = 0
        compose.setContent { MaterialTheme {
            FluxxColourPicker(ColourPickerVariant.COMPACT, colour.intValue, onBegin = {}, onPreview = { colour.intValue = it }, onFinish = { commits++ }, onCancel = {},
                modifier = Modifier.requiredWidth(320.dp))
        } }
        compose.waitForIdle()
        fun type(text: String) {
            val f = compose.onNodeWithTag("colour-hex", useUnmergedTree = true)
            f.performClick(); compose.waitForIdle()
            f.performTextReplacement(text); f.performImeAction(); compose.waitForIdle()
        }
        type("000000")
        assertEquals("black, alpha still 50%", 0x80000000.toInt(), colour.intValue)
        type("#fff")
        assertEquals(0x80FFFFFF.toInt(), colour.intValue)
        type("40Ff0000")
        assertEquals(0x40FF0000, colour.intValue)
        val before = colour.intValue; val n = commits
        // Four digits are invalid: the typed text reverts and nothing is kept.
        type("0000")
        assertEquals(before, colour.intValue); assertEquals(n, commits)
    }
}
