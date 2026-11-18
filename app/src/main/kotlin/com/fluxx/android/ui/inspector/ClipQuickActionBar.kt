package com.fluxx.android.ui.inspector

import androidx.compose.material3.Icon
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fluxx.android.editor.ClipQuickAction
import com.fluxx.android.editor.ClipQuickActions
import com.fluxx.android.editor.EditorAction
import com.fluxx.android.model.CompositionLayer
import com.fluxx.android.ui.theme.Border
import com.fluxx.android.ui.theme.SurfaceHigh
import com.fluxx.android.ui.theme.Highlight
import com.fluxx.android.ui.theme.TextDisabled
import com.fluxx.android.ui.theme.TextPrimary

@Composable
internal fun ClipQuickActionBar(layer: CompositionLayer, playheadUs: Long, compositionDurationUs: Long?,
    onAction: (EditorAction) -> Unit) {
    Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        ClipQuickActions.available(layer, playheadUs).forEach { kind ->
            val action = ClipQuickActions.action(kind, layer, playheadUs, compositionDurationUs)
            val color = if (action == null) TextDisabled else if (kind == ClipQuickAction.SPLIT) Highlight else TextPrimary
            Box(Modifier.size(44.dp, 36.dp).clip(RoundedCornerShape(8.dp))
                .background(SurfaceHigh).border(.5.dp, Border, RoundedCornerShape(8.dp))
                .clickable(enabled = action != null, role = Role.Button, onClickLabel = kind.label) {
                    action?.let(onAction)
                }.semantics { contentDescription = kind.label },
                contentAlignment = Alignment.Center) {
                ClipQuickVectorIcon(kind, color)
            }
        }
    }
}

/** Seven glyphs from the shared [InspectorIcons] family; OUT variants are exact mirrors of IN. */
@Composable
private fun ClipQuickVectorIcon(kind: ClipQuickAction, color: Color) {
    Icon(InspectorIcons.icon(kind.name), null, Modifier.size(InspectorIcons.SIZE), tint = color)
}
