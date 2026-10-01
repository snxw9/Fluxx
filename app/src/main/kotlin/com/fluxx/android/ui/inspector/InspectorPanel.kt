package com.fluxx.android.ui.inspector

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxx.android.model.*
import com.fluxx.android.model.Composition
import com.fluxx.android.ui.common.TimeFormat
import com.fluxx.android.ui.theme.*

@Composable
fun InspectorPanel(
    layer: CompositionLayer?,
    composition: Composition,
    onTransformChange: (Transform) -> Unit,
    onAudioChange: (muted: Boolean, gain: Float) -> Unit,
    onColorChange: (Int) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onRelinkClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (layer == null) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .background(Surface)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Select a layer to inspect properties",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
        return
    }

    val transform = layer.transform
    var linkScale by remember { mutableStateOf(true) }
    var renameDialogVisible by remember { mutableStateOf(false) }
    var colorPickerVisible by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Surface)
            .border(width = 0.5.dp, color = Border)
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top Header: Name, outline type badge, Rename and Delete
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Outline type glyph
                val glyph = when (layer.type) {
                    LayerType.VIDEO -> "▷"
                    LayerType.IMAGE -> "□"
                    LayerType.SOLID -> "■"
                    LayerType.TEXT -> "T"
                }
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(SurfaceVariant)
                        .border(1.dp, Border, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(glyph, color = TextPrimary, fontSize = 11.sp)
                }

                // Name with rename click (sentence case)
                Text(
                    text = layer.name.ifBlank {
                        when (layer.type) {
                            LayerType.VIDEO -> "Video layer"
                            LayerType.IMAGE -> "Image layer"
                            LayerType.SOLID -> "Solid layer"
                            LayerType.TEXT -> "Text layer"
                        }
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    modifier = Modifier.clickable { renameDialogVisible = true }
                )
                Text("✎", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.clickable { renameDialogVisible = true })
            }

            // Relink / Delete buttons (min 44dp touch area)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (layer.type == LayerType.VIDEO || layer.type == LayerType.IMAGE) {
                    val isMissing = layer.asset == null || layer.asset.access != MediaAccess.AVAILABLE
                    Box(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(SurfaceVariant)
                            .border(1.dp, if (isMissing) Highlight else Border, RoundedCornerShape(4.dp))
                            .clickable { onRelinkClick() }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (isMissing) "Relink (missing)" else "Relink",
                            color = if (isMissing) Highlight else TextPrimary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(SurfaceVariant)
                        .border(1.dp, Border, RoundedCornerShape(4.dp))
                        .clickable { onDelete() }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Delete", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // Timing Readout: Start & Duration in sentence case
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceVariant, RoundedCornerShape(4.dp))
                .border(1.dp, Border, RoundedCornerShape(4.dp))
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Start", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                Text(
                    TimeFormat.formatDurationCompact(layer.timing.startUs),
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Duration", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                Text(
                    TimeFormat.formatDurationCompact(layer.timing.durationUs ?: 0L),
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (layer.type == LayerType.VIDEO) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Source in", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                    Text(
                        TimeFormat.formatDurationCompact(layer.timing.sourceInUs),
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Solid color picker if solid layer
        if (layer.type == LayerType.SOLID) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Solid color", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(layer.solidColorArgb))
                            .border(1.dp, Border, CircleShape)
                            .clickable { colorPickerVisible = true }
                    )
                    Text(
                        "#%08X".format(layer.solidColorArgb),
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable { colorPickerVisible = true }
                    )
                }
            }
        }

        // Audio controls if Video layer
        if (layer.type == LayerType.VIDEO) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Audio", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .height(32.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (layer.muted) SurfaceVariant else Surface)
                            .border(1.dp, if (layer.muted) Highlight else Border, RoundedCornerShape(4.dp))
                            .clickable { onAudioChange(!layer.muted, layer.audioGain) }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (layer.muted) "Muted" else "Mute",
                            color = if (layer.muted) Highlight else TextSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Text("Gain: ${(layer.audioGain * 100).toInt()}%", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!layer.muted) {
                Slider(
                    value = layer.audioGain.coerceIn(0f, 1f),
                    onValueChange = { onAudioChange(false, it) },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = Highlight,
                        activeTrackColor = Highlight,
                        inactiveTrackColor = Border
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Transform section
        Text("Transform", style = MaterialTheme.typography.titleSmall, color = TextSecondary)

        // Position X / Y
        val compHalfW = (layer.referenceWidth.takeIf { it > 0 } ?: composition.width) / 2f
        val compHalfH = (layer.referenceHeight.takeIf { it > 0 } ?: composition.height) / 2f
        val pxX = (transform.positionX * compHalfW).toInt()
        val pxY = (transform.positionY * compHalfH).toInt()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Position x: $pxX px (${"%.2f".format(transform.positionX)})", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = transform.positionX.coerceIn(-2f, 2f),
                    onValueChange = { onTransformChange(transform.copy(positionX = it)) },
                    valueRange = -2f..2f,
                    colors = SliderDefaults.colors(
                        thumbColor = Highlight,
                        activeTrackColor = Highlight,
                        inactiveTrackColor = Border
                    )
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Position y: $pxY px (${"%.2f".format(transform.positionY)})", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = transform.positionY.coerceIn(-2f, 2f),
                    onValueChange = { onTransformChange(transform.copy(positionY = it)) },
                    valueRange = -2f..2f,
                    colors = SliderDefaults.colors(
                        thumbColor = Highlight,
                        activeTrackColor = Highlight,
                        inactiveTrackColor = Border
                    )
                )
            }
        }

        // Scale X / Y with Link Scale Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Scale: ${"%.2f".format(transform.scaleX)} × ${"%.2f".format(transform.scaleY)}",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Link", color = if (linkScale) TextPrimary else TextSecondary, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(4.dp))
                Switch(
                    checked = linkScale,
                    onCheckedChange = { linkScale = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = Highlight,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = SurfaceVariant
                    ),
                    modifier = Modifier.height(24.dp)
                )
            }
        }

        if (linkScale) {
            Slider(
                value = transform.scaleX.coerceIn(0.1f, 3.0f),
                onValueChange = { onTransformChange(transform.copy(scaleX = it, scaleY = it)) },
                valueRange = 0.1f..3.0f,
                colors = SliderDefaults.colors(
                    thumbColor = Highlight,
                    activeTrackColor = Highlight,
                    inactiveTrackColor = Border
                ),
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Scale x: ${"%.2f".format(transform.scaleX)}", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = transform.scaleX.coerceIn(0.1f, 3.0f),
                        onValueChange = { onTransformChange(transform.copy(scaleX = it)) },
                        valueRange = 0.1f..3.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = Highlight,
                            activeTrackColor = Highlight,
                            inactiveTrackColor = Border
                        )
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Scale y: ${"%.2f".format(transform.scaleY)}", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = transform.scaleY.coerceIn(0.1f, 3.0f),
                        onValueChange = { onTransformChange(transform.copy(scaleY = it)) },
                        valueRange = 0.1f..3.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = Highlight,
                            activeTrackColor = Highlight,
                            inactiveTrackColor = Border
                        )
                    )
                }
            }
        }

        // Rotation & Opacity
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Rotation: ${transform.rotationDegrees.toInt()}°", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = (transform.rotationDegrees % 360f + 360f) % 360f,
                    onValueChange = { onTransformChange(transform.copy(rotationDegrees = it)) },
                    valueRange = 0f..360f,
                    colors = SliderDefaults.colors(
                        thumbColor = Highlight,
                        activeTrackColor = Highlight,
                        inactiveTrackColor = Border
                    )
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Opacity: ${(transform.opacity * 100).toInt()}%", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = transform.opacity.coerceIn(0f, 1f),
                    onValueChange = { onTransformChange(transform.copy(opacity = it)) },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = Highlight,
                        activeTrackColor = Highlight,
                        inactiveTrackColor = Border
                    )
                )
            }
        }
    }

    // Rename Dialog
    if (renameDialogVisible) {
        var newName by remember { mutableStateOf(layer.name) }
        AlertDialog(
            onDismissRequest = { renameDialogVisible = false },
            title = { Text("Rename layer", style = MaterialTheme.typography.titleMedium) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("Name") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Highlight,
                        unfocusedBorderColor = Border
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(newName.trim())
                    renameDialogVisible = false
                }) { Text("Done", color = Highlight) }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogVisible = false }) { Text("Cancel", color = TextSecondary) }
            },
            containerColor = Surface
        )
    }

    // Color Picker Dialog for Solid Layers
    if (colorPickerVisible) {
        val monochromePalette = listOf(
            0xFFFFFFFF.toInt(), // White
            0xFFDDDDDD.toInt(), // Light grey
            0xFFAAAAAA.toInt(), // Medium grey
            0xFF666666.toInt(), // Slate grey
            0xFF333333.toInt(), // Dark grey
            0xFF111111.toInt(), // Near black
            0xFF000000.toInt()  // Black
        )
        AlertDialog(
            onDismissRequest = { colorPickerVisible = false },
            title = { Text("Select solid color", style = MaterialTheme.typography.titleMedium) },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    monochromePalette.forEach { argb ->
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .border(
                                    width = 1.dp,
                                    color = if (layer.solidColorArgb == argb) Highlight else Border,
                                    shape = CircleShape
                                )
                                .clickable {
                                    onColorChange(argb)
                                    colorPickerVisible = false
                                }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { colorPickerVisible = false }) { Text("Close", color = TextPrimary) }
            },
            containerColor = Surface
        )
    }
}

@Preview(showBackground = true)
@Preview(name = "Dark Mode", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InspectorPanelPreview() {
    FluxxTheme {
        InspectorPanel(
            layer = CompositionLayer(
                id = 101L,
                name = "Sample Solid",
                type = LayerType.SOLID,
                solidColorArgb = 0xFF4477CC.toInt()
            ),
            composition = Composition(),
            onTransformChange = {},
            onAudioChange = { _, _ -> },
            onColorChange = {},
            onRename = {},
            onDelete = {},
            onRelinkClick = {}
        )
    }
}
