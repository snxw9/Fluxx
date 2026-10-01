package com.fluxx.android.ui.inspector

import androidx.compose.foundation.Canvas
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

/** Seven glyphs share the same rounded 1.3dp stroke; mirroring retains distinct move/extend marks. */
@Composable
private fun ClipQuickVectorIcon(kind: ClipQuickAction, color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val mirror = kind == ClipQuickAction.MOVE_OUT || kind == ClipQuickAction.EXTEND_OUT || kind == ClipQuickAction.TRIM_OUT
        fun point(x: Float, y: Float) = Offset((if (mirror) 24f - x else x) * size.width / 24f, y * size.height / 24f)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, point(x1, y1), point(x2, y2), 1.3.dp.toPx(), StrokeCap.Round)
        fun clipBar(left: Float, right: Float) {
            val path = Path().apply {
                val a = point(left + 1f, 7f); moveTo(a.x, a.y)
                val b = point(right - 1f, 7f); lineTo(b.x, b.y)
                val c = point(right, 7f); val d = point(right, 8f); quadraticTo(c.x, c.y, d.x, d.y)
                val e = point(right, 16f); lineTo(e.x, e.y)
                val f = point(right, 17f); val g = point(right - 1f, 17f); quadraticTo(f.x, f.y, g.x, g.y)
                val h = point(left + 1f, 17f); lineTo(h.x, h.y)
                val i = point(left, 17f); val j = point(left, 16f); quadraticTo(i.x, i.y, j.x, j.y)
                val k = point(left, 8f); lineTo(k.x, k.y)
                val l = point(left, 7f); quadraticTo(l.x, l.y, a.x, a.y); close()
            }
            drawPath(path, color, style = Stroke(1.3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        when (kind) {
            ClipQuickAction.MOVE_IN, ClipQuickAction.MOVE_OUT -> {
                clipBar(15f, 21f)
                line(3f, 12f, 13f, 12f); line(10f, 9f, 13f, 12f); line(10f, 15f, 13f, 12f)
            }
            ClipQuickAction.EXTEND_IN, ClipQuickAction.EXTEND_OUT -> {
                clipBar(12f, 21f); line(12f, 4f, 12f, 20f)
                line(3f, 12f, 11f, 12f); line(3f, 12f, 6f, 9f); line(3f, 12f, 6f, 15f)
            }
            ClipQuickAction.TRIM_IN, ClipQuickAction.TRIM_OUT -> {
                clipBar(4f, 21f); line(9f, 3f, 9f, 21f)
                line(5f, 10f, 7f, 12f); line(5f, 14f, 7f, 16f)
            }
            ClipQuickAction.SPLIT -> {
                drawCircle(color, 2.5.dp.toPx(), point(6f, 7f), style = Stroke(1.3.dp.toPx()))
                drawCircle(color, 2.5.dp.toPx(), point(6f, 17f), style = Stroke(1.3.dp.toPx()))
                line(8f, 9f, 19f, 19f); line(8f, 15f, 19f, 5f)
            }
        }
    }
}
