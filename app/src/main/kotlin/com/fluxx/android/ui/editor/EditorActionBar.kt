package com.fluxx.android.ui.editor

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.fluxx.android.render.PreviewResolution
import com.fluxx.android.ui.preview.ViewSettings
import com.fluxx.android.model.CompositionLayer
import com.fluxx.android.model.LayerType
import com.fluxx.android.ui.theme.*

/**
 * Transport bar directly below preview per Phase 3 spec:
 * - 7 evenly spaced icon controls:
 *   1. Undo (enabled = canUndo)
 *   2. Redo (enabled = canRedo)
 *   3. Previous boundary
 *   4. Play / Pause (centered prominently)
 *   5. Next boundary
 *   6. Layer menu — selected-layer transforms/info plus clipboard actions
 *   7. View / Preview menu (resolution, overlays and zoom)
 * - Outline style at rest
 * - 20dp visible / 48dp wide, 40dp tall control
 * - White when active, #7A7A7A when inactive/disabled
 * - No gap / card wrapper
 */
internal val EditorActionBarHeight = 40.dp

@Composable
fun EditorActionBar(
    isPlaying: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    hasTimelineContent: Boolean = true,
    hasClipboard: Boolean = false,
    selectedLayer: CompositionLayer? = null,
    textCanFit: Boolean = false,
    onFlipHorizontal: () -> Unit = {},
    onFlipVertical: () -> Unit = {},
    onFitToWidth: () -> Unit = {},
    onFitToHeight: () -> Unit = {},
    onStretchToArea: () -> Unit = {},
    onMediaInfo: () -> Unit = {},

    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onPrevBoundary: () -> Unit,
    onPlayPauseToggle: () -> Unit,
    onNextBoundary: () -> Unit,
    onPasteLayer: () -> Unit = {},
    viewSettings: ViewSettings = ViewSettings(),
    onViewSettingsChange: (ViewSettings) -> Unit = {},
    previewResolution: PreviewResolution = PreviewResolution.FULL,
    onPreviewResolutionChange: (PreviewResolution) -> Unit = {},
    previewFitScale: Float = 1f,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Background,
        modifier = modifier
            .fillMaxWidth()
            .height(EditorActionBarHeight)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Undo
            ToolbarButton(
                enabled = canUndo,
                label = "Undo",
                onClick = onUndo
            ) { color ->
                UndoVectorIcon(color = color)
            }

            // 2. Redo
            ToolbarButton(
                enabled = canRedo,
                label = "Redo",
                onClick = onRedo
            ) { color ->
                RedoVectorIcon(color = color)
            }

            // 3. Jump to Previous boundary
            ToolbarButton(
                enabled = true,
                onClick = onPrevBoundary,
                label = "Previous contextual boundary"
            ) { color ->
                PrevBoundaryVectorIcon(color = color)
            }

            // 4. Play / Pause (centered)
            ToolbarButton(
                enabled = hasTimelineContent,
                onClick = onPlayPauseToggle,
                label = if (isPlaying) "Pause" else "Play"
            ) { color ->
                PlayPauseVectorIcon(isPlaying = isPlaying, color = color)
            }

            // 5. Jump to Next boundary
            ToolbarButton(
                enabled = true,
                onClick = onNextBoundary,
                label = "Next contextual boundary"
            ) { color ->
                NextBoundaryVectorIcon(color = color)
            }

            // 6. Reuse the layer menu for contextual and clipboard actions.
            LayerMenuButton(
                hasClipboard = hasClipboard,
                selectedLayer = selectedLayer,
                onFlipHorizontal = onFlipHorizontal, onFlipVertical = onFlipVertical,
                onFitToWidth = onFitToWidth, onFitToHeight = onFitToHeight,
                onStretchToArea = onStretchToArea, onMediaInfo = onMediaInfo,
                textCanFit = textCanFit,
                onPasteLayer = onPasteLayer
            )

            // 7. View / Preview. Resolution stays inside this menu to retain seven targets.
            ViewMenuButton(viewSettings, onViewSettingsChange, previewResolution, onPreviewResolutionChange, previewFitScale)
        }
    }
}

/**
 * 6th toolbar icon: Layer menu dropdown.
 * - Selected-layer transforms, media info and deferred action placeholders
 * - Paste layer (enabled only when clipboard has content)
 * - Select all layers (always disabled — multi-select not yet implemented)
 */
@Composable
private fun LayerMenuButton(
    hasClipboard: Boolean,
    selectedLayer: CompositionLayer? = null,
    onFlipHorizontal: () -> Unit = {},
    onFlipVertical: () -> Unit = {},
    onFitToWidth: () -> Unit = {},
    onFitToHeight: () -> Unit = {},
    onStretchToArea: () -> Unit = {},
    onMediaInfo: () -> Unit = {},
    onPasteLayer: () -> Unit,
    textCanFit: Boolean = false
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box(Modifier.semantics { contentDescription = "Layer actions" }) {
        ToolbarButton(
            enabled = true,
            onClick = { menuExpanded = true }
        ) { color ->
            LayerActionVectorIcon(color = color)
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            if (selectedLayer != null) {
                DropdownMenuItem(text = { Text("Flip Horizontal") }, onClick = { menuExpanded = false; onFlipHorizontal() })
                DropdownMenuItem(text = { Text("Flip Vertical") }, onClick = { menuExpanded = false; onFlipVertical() })
                if (selectedLayer.type == LayerType.VIDEO || selectedLayer.type == LayerType.IMAGE || selectedLayer.type == LayerType.TEXT) {
                    val fitEnabled = selectedLayer.type != LayerType.TEXT || textCanFit
                    DropdownMenuItem(text = { Text("Fit to Composition Width") }, enabled = fitEnabled, onClick = { menuExpanded = false; onFitToWidth() })
                    DropdownMenuItem(text = { Text("Fit to Composition Height") }, enabled = fitEnabled, onClick = { menuExpanded = false; onFitToHeight() })
                    DropdownMenuItem(text = { Text("Stretch to Composition Area") }, enabled = fitEnabled, onClick = { menuExpanded = false; onStretchToArea() })
                    if (selectedLayer.type != LayerType.TEXT) DropdownMenuItem(text = { Text("Media Info") }, onClick = { menuExpanded = false; onMediaInfo() })
                }
                DropdownMenuItem(text = { Text("Auto Orient") }, enabled = false, onClick = {})
                DropdownMenuItem(text = { Text("Convert to Outline / Autotrace") }, enabled = false, onClick = {})
                HorizontalDivider()
            }
            DropdownMenuItem(
                text = {
                    Text(
                        "Paste layer",
                        color = if (hasClipboard) TextPrimary else TextDisabled
                    )
                },
                onClick = {
                    menuExpanded = false
                    onPasteLayer()
                },
                enabled = hasClipboard,
                leadingIcon = {
                    PasteVectorIcon(
                        color = if (hasClipboard) TextPrimary else TextDisabled
                    )
                }
            )
            DropdownMenuItem(
                text = {
                    Text(
                        "Select all layers",
                        color = TextDisabled
                    )
                },
                onClick = { /* No-op: multi-select not yet implemented */ },
                enabled = false,
                leadingIcon = {
                    SelectAllVectorIcon(color = TextDisabled)
                }
            )
        }
    }
}

@Preview(showBackground = true)
@Preview(name = "Dark Mode", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun EditorActionBarPreview() {
    FluxxTheme {
        EditorActionBar(
            isPlaying = false,
            canUndo = true,
            canRedo = false,
            hasClipboard = true,
            onUndo = {},
            onRedo = {},
            onPrevBoundary = {},
            onPlayPauseToggle = {},
            onNextBoundary = {},
            onPasteLayer = {},
        )
    }
}

@Composable
private fun ToolbarButton(
    enabled: Boolean,
    label: String? = null,
    onClick: () -> Unit,
    content: @Composable (Color) -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp, EditorActionBarHeight)
            .clickable(enabled = enabled) { onClick() }
            .semantics { if (label != null) contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        val color = if (enabled) TextPrimary else TextSecondary // TextSecondary is #7A7A7A
        content(color)
    }
}

@Composable
private fun UndoVectorIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.5.dp.toPx()
        val arrow = Path().apply {
            moveTo(w * 0.16f, h * 0.28f)
            lineTo(w * 0.38f, h * 0.12f)
            moveTo(w * 0.16f, h * 0.28f)
            lineTo(w * 0.38f, h * 0.46f)
        }
        drawPath(arrow, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val curve = Path().apply {
            moveTo(w * 0.22f, h * 0.28f)
            cubicTo(w * 0.65f, h * 0.15f, w * 0.85f, h * 0.45f, w * 0.80f, h * 0.82f)
        }
        drawPath(curve, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun RedoVectorIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.5.dp.toPx()
        val arrow = Path().apply {
            moveTo(w * 0.84f, h * 0.28f)
            lineTo(w * 0.62f, h * 0.12f)
            moveTo(w * 0.84f, h * 0.28f)
            lineTo(w * 0.62f, h * 0.46f)
        }
        drawPath(arrow, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val curve = Path().apply {
            moveTo(w * 0.78f, h * 0.28f)
            cubicTo(w * 0.35f, h * 0.15f, w * 0.15f, h * 0.45f, w * 0.20f, h * 0.82f)
        }
        drawPath(curve, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun PrevBoundaryVectorIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.5.dp.toPx()
        drawLine(
            color = color,
            start = Offset(w * 0.18f, h * 0.20f),
            end = Offset(w * 0.18f, h * 0.80f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        val tri = Path().apply {
            moveTo(w * 0.28f, h * 0.50f)
            lineTo(w * 0.82f, h * 0.20f)
            lineTo(w * 0.82f, h * 0.80f)
            close()
        }
        drawPath(tri, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun NextBoundaryVectorIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.5.dp.toPx()
        val tri = Path().apply {
            moveTo(w * 0.72f, h * 0.50f)
            lineTo(w * 0.18f, h * 0.20f)
            lineTo(w * 0.18f, h * 0.80f)
            close()
        }
        drawPath(tri, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(
            color = color,
            start = Offset(w * 0.82f, h * 0.20f),
            end = Offset(w * 0.82f, h * 0.80f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun PlayPauseVectorIcon(isPlaying: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.5.dp.toPx()
        if (isPlaying) {
            val barW = w * 0.20f
            drawRoundRect(
                color = color,
                topLeft = Offset(w * 0.22f, h * 0.20f),
                size = Size(barW, h * 0.60f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                style = Stroke(width = stroke)
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(w * 0.58f, h * 0.20f),
                size = Size(barW, h * 0.60f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                style = Stroke(width = stroke)
            )
        } else {
            val tri = Path().apply {
                moveTo(w * 0.24f, h * 0.18f)
                lineTo(w * 0.84f, h * 0.50f)
                lineTo(w * 0.24f, h * 0.82f)
                close()
            }
            drawPath(tri, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun LayerActionVectorIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = 1.6.dp.toPx()
        drawRoundRect(
            color = color.copy(alpha = 0.6f),
            topLeft = Offset(size.width * 0.30f, size.height * 0.10f),
            size = Size(size.width * 0.60f, size.height * 0.60f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
            style = Stroke(width = stroke)
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.10f, size.height * 0.30f),
            size = Size(size.width * 0.60f, size.height * 0.60f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
            style = Stroke(width = stroke)
        )
    }
}

/** Paste clipboard icon: clipboard shape with a plus sign */
@Composable
private fun PasteVectorIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.3.dp.toPx()
        // Clipboard body
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.15f, h * 0.20f),
            size = Size(w * 0.70f, h * 0.72f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
            style = Stroke(width = stroke)
        )
        // Clipboard clip tab at top
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.32f, h * 0.08f),
            size = Size(w * 0.36f, h * 0.20f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()),
            style = Stroke(width = stroke)
        )
        // Plus horizontal
        drawLine(
            color = color,
            start = Offset(w * 0.35f, h * 0.58f),
            end = Offset(w * 0.65f, h * 0.58f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        // Plus vertical
        drawLine(
            color = color,
            start = Offset(w * 0.50f, h * 0.43f),
            end = Offset(w * 0.50f, h * 0.73f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

/** Select all icon: stacked checkboxes */
@Composable
private fun SelectAllVectorIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.3.dp.toPx()
        // Back rect (offset top-right)
        drawRoundRect(
            color = color.copy(alpha = 0.5f),
            topLeft = Offset(w * 0.25f, h * 0.10f),
            size = Size(w * 0.60f, h * 0.55f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
            style = Stroke(width = stroke)
        )
        // Front rect (offset bottom-left)
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.12f, h * 0.30f),
            size = Size(w * 0.60f, h * 0.55f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
            style = Stroke(width = stroke)
        )
        // Checkmark inside front rect
        val check = Path().apply {
            moveTo(w * 0.25f, h * 0.58f)
            lineTo(w * 0.38f, h * 0.70f)
            lineTo(w * 0.58f, h * 0.45f)
        }
        drawPath(check, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
