package com.fluxx.android.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.fluxx.android.model.ColorPalette
import com.fluxx.android.ui.common.colorpicker.PaletteBrowser
import com.fluxx.android.ui.theme.FluxxTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PaletteBrowserTest {
    @get:Rule val compose=createComposeRule()

    @Test fun renameCommitsOnDoneOrBackAndNeverPerKeystroke() {
        val renames=mutableListOf<String?>()
        compose.setContent { FluxxTheme {
            PaletteBrowser(true,{},listOf(ColorPalette("p","Default",emptyList())),"p",false,
                onSelectPalette={},onCreatePalette={},onDeletePalette={},onRenamePalette={_,name -> renames.add(name)},
                onAddSwatch={_,_ ->},onRemoveSwatch={_,_ ->},onReplaceSwatch={_,_,_ ->})
        } }
        compose.onNodeWithContentDescription("Edit Default").performClick()
        val name=compose.onNodeWithContentDescription("Palette name (blank means Untitled)")
        name.performTextReplacement("Renamed")
        compose.runOnIdle { assertEquals(emptyList<String?>(),renames) }
        name.performImeAction()
        compose.runOnIdle { assertEquals(listOf("Renamed"),renames) }
        name.performTextReplacement("Changed")
        compose.onNodeWithContentDescription("Back to palettes").performClick()
        compose.runOnIdle { assertEquals(listOf("Renamed","Changed"),renames) }
    }
}
