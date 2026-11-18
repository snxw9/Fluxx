package com.fluxx.android.ui.inspector

import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test

/** Action-bar Prev/Next keyframe buttons follow the focused text property (last touched, or the open popover). */
class TextFocusNavigationTest {
    private val layer=CompositionLayer(1,LayerType.TEXT,timing=ClipTiming(0,0,10_000_000),text=TextProperties(source=AnimatableString(staticValue="Text"),
        size=AnimatableProperty1D(true,staticValue=72f,keyframes=FrozenList.of(listOf(Keyframe1D(1_000_000,60f),Keyframe1D(5_000_000,80f)))),
        tracking=AnimatableProperty1D(true,keyframes=FrozenList.of(listOf(Keyframe1D(3_000_000,0f),Keyframe1D(7_000_000,50f))))))
    private fun editor()=EditorViewModel().apply {
        dispatch(EditorAction.Load(ProjectDocument(Composition(durationUs=10_000_000,layers=FrozenList.of(listOf(layer))))))
        dispatch(EditorAction.Select(1))
    }

    @Test fun focusIsTheOpenPopoverOtherwiseTheLastTouchedControl() {
        val whole=TextEditScope(null)
        assertEquals(AnimPropertyType.TRACKING,TextFocus.owner(whole,TextTab.CHARACTER,TextControl.TRACKING,null))
        assertEquals(AnimPropertyType.FONT_SIZE,TextFocus.owner(whole,TextTab.CHARACTER,TextControl.TRACKING,TextControl.SIZE))
        // The colour popover follows its Fill/Stroke target, recorded as the last touched control.
        assertEquals(AnimPropertyType.STROKE_COLOUR,TextFocus.owner(whole,TextTab.CHARACTER,TextControl.STROKE,TextControl.PAINT))
        assertEquals(AnimPropertyType.FILL_COLOUR,TextFocus.owner(whole,TextTab.CHARACTER,TextControl.FILL,TextControl.PAINT))
        // Document-backed controls point to Source Text; selections and Animate have no owner.
        assertEquals(AnimPropertyType.SOURCE_TEXT,TextFocus.owner(whole,TextTab.CHARACTER,TextControl.BOLD,null))
        assertNull(TextFocus.owner(TextEditScope(TextSelectionRange(0,2)),TextTab.CHARACTER,TextControl.SIZE,null))
        assertNull(TextFocus.owner(whole,TextTab.ANIMATE,TextControl.SIZE,null))
    }
    @Test fun prevNextFollowTheFocusedTextProperty() {
        val e=editor()
        val tracking=TextFocus.owner(TextEditScope(null),TextTab.CHARACTER,TextControl.TRACKING,null)
        assertEquals(3_000_000L,e.navigateBoundary(true,true,tracking))
        assertEquals(7_000_000L,e.navigateBoundary(true,true,tracking))
        // Opening the Size popover moves keyframe navigation to Size immediately.
        val size=TextFocus.owner(TextEditScope(null),TextTab.CHARACTER,TextControl.TRACKING,TextControl.SIZE)
        assertEquals(5_000_000L,e.navigateBoundary(false,true,size))
        assertEquals(1_000_000L,e.navigateBoundary(false,true,size))
    }
}
