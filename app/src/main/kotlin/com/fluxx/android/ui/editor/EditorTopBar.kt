package com.fluxx.android.ui.editor

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxx.android.ui.theme.*

/**
 * Stage 4: Persistent Top Bar for Editor.
 * - 48dp height, 1dp bottom border (#2A2A2A)
 * - #121212 background
 * - Left: Back deselects the layer first, then leaves the editor.
 * - Center: Editable project name or selected layer name in one stable slot.
 * - Right: Export button (export action) + Settings gear button
 */
@Composable
fun EditorTopBar(
    projectName: String,
    onProjectNameChange: (String) -> Unit,
    onExitClick: () -> Unit,
    onCompSettingsClick: () -> Unit,
    onExportClick: () -> Unit,
    onSaveClick: () -> Unit = {},
    onLoadClick: () -> Unit = {},
    selectedLayerId: Long? = null,
    selectedLayerName: String = "",
    onLayerNameChange: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    Surface(
        color = Background,
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(48.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Back icon (left): ~24dp visible / 44dp touch target
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .semantics { contentDescription = if (selectedLayerId == null) "Back to projects" else "Deselect layer" }
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onExitClick() },
                contentAlignment = Alignment.Center
            ) {
                BackArrowVectorIcon(color = TextPrimary)
            }

            Spacer(Modifier.width(8.dp))

            // One stable title slot; the selected layer's name is owned by EditorState.
            Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.Center) {
                key(selectedLayerId) {
                    val title = if (selectedLayerId == null) projectName else selectedLayerName
                    BasicTextField(
                        value = title,
                        onValueChange = if (selectedLayerId == null) onProjectNameChange else onLayerNameChange,
                        singleLine = true,
                        textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(Highlight),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp).semantics {
                            contentDescription = if (selectedLayerId == null) "Project name" else "Layer name"
                        },
                        decorationBox = { field ->
                            Box {
                                if (title.isBlank()) Text(if (selectedLayerId == null) "Project 1" else "Layer $selectedLayerId",
                                    color = TextSecondary, fontSize = 15.sp)
                                field()
                            }
                        }
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
            }
            Spacer(Modifier.width(8.dp))
            // Reserve the same action width in both contexts so shared controls never move.
            if (selectedLayerId == null) {
                // 4. Settings icon (gear): ~24dp visible / 44dp touch target
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onCompSettingsClick() },
                    contentAlignment = Alignment.Center
                ) {
                    GearVectorIcon(color = TextPrimary)
                }

                // 5. Export icon (share): ~24dp visible / 44dp touch target
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onExportClick() },
                    contentAlignment = Alignment.Center
                ) {
                    ExportArrowVectorIcon(color = TextPrimary)
                }
            } else Spacer(Modifier.width(88.dp))
        }
    }
}

@Preview(showBackground = true)
@Preview(name = "Dark Mode", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun EditorTopBarPreview() {
    FluxxTheme {
        EditorTopBar(
            projectName = "My Fluxx Project",
            onProjectNameChange = {},
            onExitClick = {},
            onCompSettingsClick = {},
            onExportClick = {}
        )
    }
}

@Composable
private fun BackArrowVectorIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.8.dp.toPx()
        drawLine(
            color = color,
            start = Offset(w * 0.18f, h * 0.5f),
            end = Offset(w * 0.82f, h * 0.5f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        val head = Path().apply {
            moveTo(w * 0.44f, h * 0.24f)
            lineTo(w * 0.18f, h * 0.5f)
            lineTo(w * 0.44f, h * 0.76f)
        }
        drawPath(head, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun GearVectorIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val center = Offset(w / 2f, h / 2f)
        val stroke = 1.6.dp.toPx()
        val outerRadius = w * 0.40f
        val innerRadius = w * 0.28f
        val holeRadius = w * 0.14f
        val numTeeth = 6
        val path = Path()
        for (i in 0 until numTeeth) {
            val angleMid = i * 360f / numTeeth
            val angleStart = angleMid - 16f
            val angleEnd = angleMid + 16f
            val radStart = Math.toRadians(angleStart.toDouble())
            val radEnd = Math.toRadians(angleEnd.toDouble())
            val radT1 = Math.toRadians((angleMid - 9f).toDouble())
            val radT2 = Math.toRadians((angleMid + 9f).toDouble())
            val p0 = Offset(center.x + (innerRadius * kotlin.math.cos(radStart)).toFloat(), center.y + (innerRadius * kotlin.math.sin(radStart)).toFloat())
            val p1 = Offset(center.x + (outerRadius * kotlin.math.cos(radT1)).toFloat(), center.y + (outerRadius * kotlin.math.sin(radT1)).toFloat())
            val p2 = Offset(center.x + (outerRadius * kotlin.math.cos(radT2)).toFloat(), center.y + (outerRadius * kotlin.math.sin(radT2)).toFloat())
            val p3 = Offset(center.x + (innerRadius * kotlin.math.cos(radEnd)).toFloat(), center.y + (innerRadius * kotlin.math.sin(radEnd)).toFloat())
            if (i == 0) path.moveTo(p0.x, p0.y) else path.lineTo(p0.x, p0.y)
            path.lineTo(p1.x, p1.y)
            path.lineTo(p2.x, p2.y)
            path.lineTo(p3.x, p3.y)
        }
        path.close()
        drawPath(path, color, style = Stroke(width = stroke, join = StrokeJoin.Round))
        drawCircle(color = color, radius = holeRadius, center = center, style = Stroke(width = stroke))
    }
}

@Composable
private fun ExportArrowVectorIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.8.dp.toPx()
        val tray = Path().apply {
            moveTo(w * 0.18f, h * 0.44f)
            lineTo(w * 0.18f, h * 0.84f)
            lineTo(w * 0.82f, h * 0.84f)
            lineTo(w * 0.82f, h * 0.44f)
        }
        drawPath(tray, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(
            color = color,
            start = Offset(w * 0.5f, h * 0.64f),
            end = Offset(w * 0.5f, h * 0.16f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        val head = Path().apply {
            moveTo(w * 0.28f, h * 0.36f)
            lineTo(w * 0.5f, h * 0.16f)
            lineTo(w * 0.72f, h * 0.36f)
        }
        drawPath(head, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
