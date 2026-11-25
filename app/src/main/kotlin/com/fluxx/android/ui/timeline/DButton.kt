package com.fluxx.android.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.fluxx.android.editor.DZone
import com.fluxx.android.model.CompositionLayer
import com.fluxx.android.ui.inspector.GraphLayout
import com.fluxx.android.ui.inspector.InspectorIcons

/** TLP T1: the D-button's geometry (one row tall; the row height never changes). */
internal object DButtonLayout {
    /** Two 28dp zones (eye, arrow): 56dp, over the 48dp minimum across. */
    const val WIDTH_DP = 56f
    const val ZONE_DP = 28f
    /** The rounded edge faces the timeline; the flat edge sits on the screen's left edge. */
    const val RADIUS_DP = TRACK_ROW_HEIGHT_DP / 2f
    const val ICON_DP = 16f
    /**
     * The fade on the rounded edge reuses the Graph library icon's scrim (GRAPH G2 A2): the same alpha stops
     * (0.9 → 0.82 → 0) ending [GraphLayout.SCRIM_EXTENT] dp past the icons, so the layer content shows underneath.
     */
    fun fadeStops(widthDp: Float = WIDTH_DP): Array<Pair<Float, Float>> {
        val solid = (widthDp - GraphLayout.SCRIM_EXTENT) / widthDp
        return arrayOf(0f to .9f, solid * .8f to .82f, 1f to 0f)
    }
}

/**
 * TLP T1: the D-button, the layer row's existing eye turned into a one-sided rounded rectangle holding the eye and an
 * arrow. Neutral when the layer is not selected; filled with the theme accent ([MaterialTheme] `primary`, icons in
 * `onPrimary`) when it is. The row itself is not highlighted. The arrow is drawn disabled (TL3 builds the expansion).
 */
@Composable
internal fun DButton(layer: CompositionLayer, selected: Boolean, selectionMode: Boolean,
    onTap: (DZone) -> Unit, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val fill = if (selected) scheme.primary else scheme.surfaceVariant
    val ink = if (selected) scheme.onPrimary else scheme.onSurfaceVariant
    val stops = DButtonLayout.fadeStops()
    val brush = Brush.horizontalGradient(*stops.map { (at, alpha) -> at to fill.copy(alpha = fill.alpha * alpha) }.toTypedArray())
    val haptic = LocalHapticFeedback.current
    val tap by rememberUpdatedState(onTap); val hold by rememberUpdatedState(onLongPress)
    val name = layer.name.ifBlank { "layer ${layer.id}" }
    Box(modifier.width(DButtonLayout.WIDTH_DP.dp).fillMaxHeight()
        .clip(RoundedCornerShape(topEnd = DButtonLayout.RADIUS_DP.dp, bottomEnd = DButtonLayout.RADIUS_DP.dp))
        .background(brush)
        .testTag("d-button-${layer.id}")
        .pointerInput(layer.id) {
            detectTapGestures(
                onTap = { p -> tap(if (p.x < DButtonLayout.ZONE_DP.dp.toPx()) DZone.EYE else DZone.ARROW) },
                onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); hold() })
        }
        .semantics(mergeDescendants = false) {
            this.selected = selected
            stateDescription = if (selected) "Selected" else "Not selected"
            onLongClick(if (!selectionMode) "Select layers, starting with $name" else if (selected) "Remove $name from the selection" else "Add $name to the selection") { hold(); true }
        }) {
        Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(DButtonLayout.ZONE_DP.dp, TRACK_ROW_HEIGHT_DP.dp).testTag("d-eye-${layer.id}").semantics {
                role = Role.Button
                contentDescription = when {
                    selectionMode && !selected -> "Add $name to the selection"
                    selectionMode -> if (layer.visible) "Hide the selected layers" else "Show the selected layers"
                    else -> if (layer.visible) "Hide $name" else "Show $name"
                }
                onClick { tap(DZone.EYE); true }
            }, contentAlignment = Alignment.Center) {
                Icon(InspectorIcons.icon(if (layer.visible) "EYE" else "EYE_OFF"), null, Modifier.size(DButtonLayout.ICON_DP.dp),
                    tint = if (layer.visible) ink else ink.copy(alpha = .6f))
            }
            // The arrow: drawn, disabled this pass (bulk expand for TL3). In selection mode an unselected layer's arrow adds it.
            val arrowAdds = selectionMode && !selected
            Box(Modifier.size(DButtonLayout.ZONE_DP.dp, TRACK_ROW_HEIGHT_DP.dp).testTag("d-arrow-${layer.id}").semantics {
                role = Role.Button
                contentDescription = if (arrowAdds) "Add $name to the selection" else "Expand $name (not available yet)"
                if (arrowAdds) onClick { tap(DZone.ARROW); true } else disabled()
            }, contentAlignment = Alignment.Center) {
                Icon(InspectorIcons.icon("ARROW"), null, Modifier.size(DButtonLayout.ICON_DP.dp), tint = ink.copy(alpha = .38f))
            }
        }
    }
}

