package com.fluxx.android.ui.inspector

import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test

/** Keyframe badge on property tiles: none / hollow (keyed) / filled (key at the playhead), from the rail's key model. */
class KeyframeBadgeTest {
    private fun editorWith(layer: CompositionLayer)=EditorViewModel().apply {
        dispatch(EditorAction.Load(ProjectDocument(Composition(layers=FrozenList.of(listOf(layer))))))
    }
    private fun EditorViewModel.layer()=state.project.composition.layers.single()

    @Test fun stateFollowsKeysAndThePlayhead() {
        assertEquals(KeyBadge.NONE,KeyBadges.state(emptyList(),0))
        assertEquals(KeyBadge.ANIMATED,KeyBadges.state(listOf(0L,500_000L),250_000))
        assertEquals(KeyBadge.AT_PLAYHEAD,KeyBadges.state(listOf(0L,500_000L),500_000))
        assertEquals("KEY",KeyBadges.icon(KeyBadge.ANIMATED));assertEquals("KEY_ON",KeyBadges.icon(KeyBadge.AT_PLAYHEAD))
        // Hollow vs filled are the shared family's glyphs (KEY_ON is the filled state glyph).
        assertTrue("KEY_ON" in InspectorIcons.FILLED && "KEY" !in InspectorIcons.FILLED)
    }
    @Test fun badgesAppearAndDisappearWithKeyframes() {
        val editor=editorWith(CompositionLayer(1,LayerType.SOLID))
        assertEquals(KeyBadge.NONE,KeyBadges.of(editor.layer(),AnimPropertyType.OPACITY,0))
        editor.dispatch(EditorAction.ToggleAnimated(1,AnimPropertyType.OPACITY,true,0))
        val local0=0-editor.layer().resolvedKeyframeAnchorUs
        assertEquals(KeyBadge.AT_PLAYHEAD,KeyBadges.of(editor.layer(),AnimPropertyType.OPACITY,local0))
        assertEquals(KeyBadge.ANIMATED,KeyBadges.of(editor.layer(),AnimPropertyType.OPACITY,local0+40_000))
        // Other properties stay unbadged.
        assertEquals(KeyBadge.NONE,KeyBadges.of(editor.layer(),AnimPropertyType.POSITION,local0))
        editor.dispatch(EditorAction.ToggleAnimated(1,AnimPropertyType.OPACITY,false,0))
        assertEquals(KeyBadge.NONE,KeyBadges.of(editor.layer(),AnimPropertyType.OPACITY,local0))
    }
    @Test fun editTextTilesMapToTheirLayerProperty() {
        val editor=editorWith(CompositionLayer(1,LayerType.TEXT,text=TextProperties(source=AnimatableString(staticValue="Ab"))))
        editor.dispatch(EditorAction.ToggleAnimated(1,AnimPropertyType.STROKE_COLOUR,true,0))
        editor.dispatch(EditorAction.ToggleAnimated(1,AnimPropertyType.TRACKING,true,100_000))
        val l=editor.layer();val t0=0-l.resolvedKeyframeAnchorUs
        // Fill + Stroke share the paint tile: the strongest state wins.
        assertEquals(KeyBadge.AT_PLAYHEAD,KeyBadges.text(l,TextControl.PAINT,t0))
        assertEquals(KeyBadge.ANIMATED,KeyBadges.text(l,TextControl.TRACKING,t0))
        assertEquals(KeyBadge.AT_PLAYHEAD,KeyBadges.text(l,TextControl.TRACKING,100_000-l.resolvedKeyframeAnchorUs))
        assertEquals(KeyBadge.NONE,KeyBadges.text(l,TextControl.SIZE,t0))
        // Style tiles are keyed through Source Text: that badge lives on the text field, not on each style tile.
        assertTrue(KeyBadges.textProperties(TextControl.BOLD).isEmpty())
        assertEquals(listOf(AnimPropertyType.SOURCE_TEXT),KeyBadges.textProperties(TextControl.SOURCE))
        for(c in listOf(TextControl.SIZE,TextControl.STROKE_WIDTH,TextControl.TRACKING,TextControl.LEADING,TextControl.BASELINE,TextControl.ORDER,TextControl.LIGATURES))
            assertEquals(c.name,TextEditScope(null).owner(c),KeyBadges.textProperties(c).single())
    }
    @Test fun accessibilityStateCarriesTheBadge() {
        assertNull(KeyBadges.description(KeyBadge.NONE))
        assertEquals("keyframes active",KeyBadges.description(KeyBadge.ANIMATED))
        assertEquals("keyframe at playhead",KeyBadges.description(KeyBadge.AT_PLAYHEAD))
        assertEquals("On, keyframe at playhead",KeyBadges.withState("On",KeyBadge.AT_PLAYHEAD))
        assertEquals("On",KeyBadges.withState("On",KeyBadge.NONE))
        assertEquals(KeyBadge.AT_PLAYHEAD,KeyBadges.combine(listOf(KeyBadge.NONE,KeyBadge.AT_PLAYHEAD,KeyBadge.ANIMATED)))
        assertEquals(KeyBadge.NONE,KeyBadges.combine(emptyList()))
        // About 10dp drawn with a 3dp inset: it fits a 36dp compact tile's corner without reaching the bottom caption.
        assertEquals(10f,KeyBadges.DRAWN,0f);assertEquals(3f,KeyBadges.INSET,0f)
        assertTrue(KeyBadges.DRAWN+2*KeyBadges.INSET<TextInspectorBudget.TILE_COMPACT/2)
    }
}
