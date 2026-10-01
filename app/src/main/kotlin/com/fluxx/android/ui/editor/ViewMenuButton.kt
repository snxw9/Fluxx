package com.fluxx.android.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.fluxx.android.render.PreviewResolution
import com.fluxx.android.ui.preview.ViewSettings

private enum class ViewMenuPage { MAIN, LAYERS, RESOLUTION, CAMERA, ZOOM }

@Composable
internal fun ViewMenuButton(settings: ViewSettings, onSettingsChange: (ViewSettings) -> Unit,
    resolution: PreviewResolution, onResolutionChange: (PreviewResolution) -> Unit, fitScale: Float) {
    var expanded by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(ViewMenuPage.MAIN) }
    val color = MaterialTheme.colorScheme.onSurface
    Box {
        Box(Modifier.size(48.dp, EditorActionBarHeight).clickable { page = ViewMenuPage.MAIN; expanded = true }
            .semantics { contentDescription = "View and preview, resolution ${resolution.label}" },
            contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(14.dp, 20.dp)) {
                    for (i in -1..1) drawCircle(color, 1.3.dp.toPx(), Offset(center.x, center.y + i * 6.dp.toPx()))
                }
                Text(resolution.badge, style = MaterialTheme.typography.labelSmall, color = color)
            }
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            if (page != ViewMenuPage.MAIN) {
                DropdownMenuItem(text = { Text("Back to View / Preview") }, onClick = { page = ViewMenuPage.MAIN })
                HorizontalDivider()
            }
            when (page) {
                ViewMenuPage.MAIN -> {
                    DropdownMenuItem(text = { Text("Layer Controls") }, onClick = { page = ViewMenuPage.LAYERS })
                    DropdownMenuItem(text = { Text("Preview Resolution · ${resolution.label}") }, onClick = { page = ViewMenuPage.RESOLUTION },
                        leadingIcon = { ViewSectionIcon(grid = false) })
                    ViewToggle("Grid Overlay", settings.showGrid, leadingIcon = { ViewSectionIcon(grid = true) }) {
                        onSettingsChange(settings.copy(showGrid = !settings.showGrid))
                    }
                    DropdownMenuItem(text = { Text("Camera") }, onClick = { page = ViewMenuPage.CAMERA })
                    DropdownMenuItem(text = { Text("Zoom") }, onClick = { page = ViewMenuPage.ZOOM })
                }
                ViewMenuPage.LAYERS -> {
                    ViewToggle("Bounding Boxes", settings.showBoundingBoxes) { onSettingsChange(settings.copy(showBoundingBoxes = !settings.showBoundingBoxes)) }
                    ViewToggle("Selection Handles", settings.showSelectionHandles) { onSettingsChange(settings.copy(showSelectionHandles = !settings.showSelectionHandles)) }
                    ViewToggle("Anchor Points", settings.showAnchorPoints) { onSettingsChange(settings.copy(showAnchorPoints = !settings.showAnchorPoints)) }
                    for (label in listOf("Motion Paths", "Control Points", "X-Ray Mode")) {
                        DropdownMenuItem(text = { Text(label) }, enabled = false, onClick = {})
                    }
                }
                ViewMenuPage.RESOLUTION -> PreviewResolution.entries.forEach { value ->
                    DropdownMenuItem(text = { Text(value.label) }, onClick = { onResolutionChange(value); expanded = false },
                        leadingIcon = { RadioButton(selected = value == resolution, onClick = null) },
                        modifier = Modifier.semantics { selected = value == resolution; role = Role.RadioButton })
                }
                ViewMenuPage.CAMERA -> for (label in listOf("Active Camera (Default)", "Top", "Front")) {
                    DropdownMenuItem(text = { Text(label) }, enabled = false, onClick = {})
                }
                ViewMenuPage.ZOOM -> {
                    DropdownMenuItem(text = { Text("Zoom In") }, onClick = { onSettingsChange(settings.zoomBy(1.25f, fitScale)) })
                    DropdownMenuItem(text = { Text("Zoom Out") }, onClick = { onSettingsChange(settings.zoomBy(.8f, fitScale)) })
                    DropdownMenuItem(text = { Text("100%") }, onClick = { onSettingsChange(settings.actualSize()); expanded = false })
                    DropdownMenuItem(text = { Text("Fit to Viewport") }, onClick = { onSettingsChange(settings.fit()); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun ViewToggle(label: String, checked: Boolean, leadingIcon: @Composable (() -> Unit)? = null, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick,
        leadingIcon = leadingIcon,
        trailingIcon = { Checkbox(checked, onCheckedChange = null) },
        modifier = Modifier.semantics { role = Role.Checkbox; toggleableState = ToggleableState(checked) })
}

@Composable
private fun ViewSectionIcon(grid: Boolean) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(20.dp)) {
        val inset = 2.dp.toPx()
        drawRect(color, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), style = Stroke(1.dp.toPx()))
        if (grid) {
            for (i in 1..2) {
                val x = inset + (size.width - 2 * inset) * i / 3f
                val y = inset + (size.height - 2 * inset) * i / 3f
                drawLine(color, Offset(x, inset), Offset(x, size.height - inset), 1.dp.toPx())
                drawLine(color, Offset(inset, y), Offset(size.width - inset, y), 1.dp.toPx())
            }
        } else {
            for (i in 0..2) drawRect(color, Offset(5.dp.toPx() + i * 4.dp.toPx(), 9.dp.toPx()), Size(2.dp.toPx(), 2.dp.toPx()))
        }
    }
}
