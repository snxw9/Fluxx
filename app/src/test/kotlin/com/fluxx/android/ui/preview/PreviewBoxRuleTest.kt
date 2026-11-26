package com.fluxx.android.ui.preview

import org.junit.Assert.*
import org.junit.Test

/** TLP.2 part 2: one preview box. The red selector box hides the layer handles; blur restores them. */
class PreviewBoxRuleTest {
    @Test fun handlesAreHiddenWhileTheSelectorBoxShows() {
        assertFalse(PreviewBoxRule.handles(showHandlesSetting = true, selectorBoxShown = true))
        assertTrue(PreviewBoxRule.handles(showHandlesSetting = true, selectorBoxShown = false))
        assertFalse("the view setting still wins", PreviewBoxRule.handles(showHandlesSetting = false, selectorBoxShown = false))
    }
    @Test fun stateNamesTheOneBoxDrawn() {
        assertEquals("Selector box", PreviewBoxRule.state(true, PreviewBoxRule.handles(true, true)))
        assertEquals("Layer box with handles", PreviewBoxRule.state(false, PreviewBoxRule.handles(true, false)))
        assertEquals("Layer box", PreviewBoxRule.state(false, false))
    }
}
