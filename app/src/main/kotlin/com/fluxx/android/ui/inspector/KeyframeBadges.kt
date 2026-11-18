package com.fluxx.android.ui.inspector

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fluxx.android.editor.AnimPropertyType
import com.fluxx.android.editor.propertyKeyTimes
import com.fluxx.android.model.CompositionLayer

/** Keyframe badge on a property tile: none, hollow diamond (property is keyed), filled diamond (key at the playhead). */
internal enum class KeyBadge { NONE, ANIMATED, AT_PLAYHEAD }

/**
 * Badge state derived from the same keyframe model the rail diamond uses ([propertyKeyTimes]); no badge state is
 * stored anywhere. Callers derive it per property, keyed by layer + property + playhead, so recomposition is cheap.
 */
internal object KeyBadges {
    /** Drawn size and corner inset (dp). */
    const val DRAWN=10f
    const val INSET=3f
    fun state(times: List<Long>,local: Long): KeyBadge = when {
        times.isEmpty()->KeyBadge.NONE
        local in times->KeyBadge.AT_PLAYHEAD
        else->KeyBadge.ANIMATED
    }
    fun of(layer: CompositionLayer,property: AnimPropertyType,local: Long)=state(layer.propertyKeyTimes(property),local)
    /** Several properties behind one tile (Fill + Stroke): the strongest state wins. */
    fun combine(states: Iterable<KeyBadge>)=states.maxByOrNull {it.ordinal} ?: KeyBadge.NONE
    /**
     * The layer property behind an Edit Text tile. Run and paragraph styles live in the Source Text document, so they
     * are keyed through Source Text: that badge sits on the text field (one badge, not one on every style tile).
     */
    fun textProperties(c: TextControl): List<AnimPropertyType> = when(c) {
        TextControl.SIZE->listOf(AnimPropertyType.FONT_SIZE)
        TextControl.PAINT->listOf(AnimPropertyType.FILL_COLOUR,AnimPropertyType.STROKE_COLOUR)
        TextControl.FILL->listOf(AnimPropertyType.FILL_COLOUR)
        TextControl.STROKE->listOf(AnimPropertyType.STROKE_COLOUR)
        TextControl.STROKE_WIDTH->listOf(AnimPropertyType.STROKE_WIDTH)
        TextControl.TRACKING->listOf(AnimPropertyType.TRACKING)
        TextControl.LEADING->listOf(AnimPropertyType.LEADING)
        TextControl.BASELINE->listOf(AnimPropertyType.BASELINE_SHIFT)
        TextControl.ORDER->listOf(AnimPropertyType.STROKE_OVER_FILL)
        TextControl.LIGATURES->listOf(AnimPropertyType.LIGATURES)
        TextControl.SOURCE->listOf(AnimPropertyType.SOURCE_TEXT)
        else->emptyList()
    }
    fun text(layer: CompositionLayer,c: TextControl,local: Long)=combine(textProperties(c).map {of(layer,it,local)})
    fun icon(badge: KeyBadge)=if(badge==KeyBadge.AT_PLAYHEAD) "KEY_ON" else "KEY"
    /** Accessibility state appended to the tile's own state. */
    fun description(badge: KeyBadge): String? = when(badge) {
        KeyBadge.NONE->null
        KeyBadge.ANIMATED->"keyframes active"
        KeyBadge.AT_PLAYHEAD->"keyframe at playhead"
    }
    fun withState(state: String?,badge: KeyBadge)=description(badge)?.let {d->if(state.isNullOrEmpty()) d else "$state, $d"} ?: state
}

/** The drawn badge: the shared KEY / KEY_ON glyph at [KeyBadges.DRAWN]dp, in the tile's ink. */
@Composable internal fun KeyBadgeGlyph(badge: KeyBadge,tint: Color,modifier: Modifier=Modifier) {
    if(badge==KeyBadge.NONE) return
    Icon(InspectorIcons.icon(KeyBadges.icon(badge)),null,modifier.padding(KeyBadges.INSET.dp).size(KeyBadges.DRAWN.dp)
        .testTag("key-badge-${badge.name}"),tint=tint)
}
