package com.fluxx.android.ui.dialogs

import android.content.res.Configuration
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fluxx.android.export.ExportState
import com.fluxx.android.model.Composition
import com.fluxx.android.model.CompositionTimecode
import com.fluxx.android.model.FrameRate
import com.fluxx.android.ui.navigation.ChevronRightIcon
import com.fluxx.android.ui.theme.*

@Composable
fun ExportDialog(
    exportState: ExportState,
    onStartExport: () -> Unit,
    onCancelExport: () -> Unit,
    onOpenExportedVideo: (Uri) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!exportState.running) onDismiss() },
        title = {
            Text("Export composition", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (exportState.running) {
                    Text(
                        text = exportState.message.ifBlank { "Export in progress..." },
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    val percent = exportState.percent
                    if (percent == null) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Highlight,
                            trackColor = Border
                        )
                    } else {
                        Column {
                            LinearProgressIndicator(
                                progress = { percent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                                color = Highlight,
                                trackColor = Border
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "$percent%",
                                color = TextPrimary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(SurfaceVariant)
                            .border(1.dp, Border, RoundedCornerShape(4.dp))
                            .clickable { onCancelExport() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Cancel export", color = TextPrimary, style = MaterialTheme.typography.labelMedium)
                    }
                } else if (exportState.output != null) {
                    Text(
                        "Export completed successfully and saved to Movies/Fluxx.",
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Highlight)
                            .clickable { onOpenExportedVideo(exportState.output) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Open exported video", color = Color.Black, style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    Text(
                        "Ready to export composition. Renders full layered Vulkan composition with mixed audio.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (exportState.message.isNotEmpty()) {
                        Text(
                            exportState.message,
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Highlight)
                            .clickable { onStartExport() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Start export", color = Color.Black, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        },
        confirmButton = {
            if (!exportState.running) {
                TextButton(onClick = onDismiss) { Text("Close", color = TextPrimary) }
            }
        },
        containerColor = Surface
    )
}

@Preview(showBackground = true)
@Preview(name = "Dark Mode", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ExportDialogPreview() {
    FluxxTheme {
        ExportDialog(
            exportState = ExportState(running = false, percent = 50, message = "Ready"),
            onStartExport = {},
            onCancelExport = {},
            onOpenExportedVideo = {},
            onDismiss = {}
        )
    }
}

private data class DialogAspectPreset(val label: String, val width: Int, val height: Int)

private val DIALOG_ASPECT_PRESETS = listOf(
    DialogAspectPreset("16:9 (Landscape)", 1920, 1080),
    DialogAspectPreset("9:16 (Story / Reel)", 1080, 1920),
    DialogAspectPreset("1:1 (Square)", 1080, 1080),
    DialogAspectPreset("4:3 (Standard)", 1440, 1080),
    DialogAspectPreset("4:5 (Portrait)", 1080, 1350),
)

@Composable
fun CompositionSettingsDialog(
    composition: Composition,
    onSaveSettings: (width: Int, height: Int, durationUs: Long?, frameRate: FrameRate, startTimecodeUs: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var widthText by remember { mutableStateOf(composition.width.toString()) }
    var heightText by remember { mutableStateOf(composition.height.toString()) }
    var startText by remember { mutableStateOf(CompositionTimecode.format(composition.startTimecodeUs, composition.frameRate)) }
    var endText by remember { mutableStateOf(CompositionTimecode.format(composition.endTimecodeUs, composition.frameRate)) }
    var endEdited by remember { mutableStateOf(false) }
    var automaticEnd by remember { mutableStateOf(composition.durationUs == null) }
    var fpsText by remember { mutableStateOf(CompositionTimecode.frameRateLabel(composition.frameRate)) }
    val rate = if (fpsText == CompositionTimecode.frameRateLabel(composition.frameRate)) composition.frameRate
        else CompositionTimecode.parseFrameRate(fpsText)
    val startUs = rate?.let { CompositionTimecode.parse(startText, it) }
    val timelineLength = if (automaticEnd) composition.layers.maxOfOrNull {
        it.timing.endUs ?: it.timing.startUs
    } ?: 0L else composition.resolvedDurationUs
    val displayedEnd = if (endEdited) endText else rate?.let { r -> startUs?.let {
        runCatching { CompositionTimecode.format(Math.addExact(it, timelineLength), r) }.getOrNull()
    } } ?: endText
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var aspectDropdownExpanded by remember { mutableStateOf(false) }

    val currentAspectLabel = remember(widthText, heightText) {
        val w = widthText.toIntOrNull()
        val h = heightText.toIntOrNull()
        if (w != null && h != null && w > 0 && h > 0) {
            DIALOG_ASPECT_PRESETS.firstOrNull { preset ->
                w.toLong() * preset.height == h.toLong() * preset.width
            }?.label ?: "Custom ($w x $h)"
        } else {
            "Custom"
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Composition settings", style = MaterialTheme.typography.titleMedium, color = TextPrimary) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Aspect ratio dropdown control
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Aspect ratio",
                        modifier = Modifier.weight(1f),
                        color = TextSecondary,
                        style = SheetFieldLabelStyle,
                    )
                    Box {
                        TextButton(
                            onClick = { aspectDropdownExpanded = true },
                            shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.textButtonColors(containerColor = SurfaceElevated),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            Text(currentAspectLabel, style = SheetFieldValueStyle)
                            ChevronRightIcon(TextSecondary, Modifier.rotate(90f))
                        }
                        DropdownMenu(
                            expanded = aspectDropdownExpanded,
                            onDismissRequest = { aspectDropdownExpanded = false },
                            containerColor = SurfaceElevated,
                        ) {
                            DIALOG_ASPECT_PRESETS.forEach { preset ->
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(preset.label, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                                            Spacer(Modifier.width(12.dp))
                                            Text(
                                                "${preset.width}×${preset.height}",
                                                color = TextSecondary,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    },
                                    onClick = {
                                        widthText = preset.width.toString()
                                        heightText = preset.height.toString()
                                        aspectDropdownExpanded = false
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Custom (manual dimensions)", color = TextPrimary) },
                                onClick = { aspectDropdownExpanded = false },
                            )
                        }
                    }
                }

                // Compact side-by-side Width / Height row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = widthText,
                        onValueChange = { widthText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Width", style = MaterialTheme.typography.labelSmall) },
                        textStyle = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Highlight,
                            unfocusedBorderColor = Border,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedLabelColor = Highlight,
                            unfocusedLabelColor = TextSecondary,
                        ),
                    )
                    Text("×", color = TextSecondary, style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = { heightText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Height", style = MaterialTheme.typography.labelSmall) },
                        textStyle = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Highlight,
                            unfocusedBorderColor = Border,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedLabelColor = Highlight,
                            unfocusedLabelColor = TextSecondary,
                        ),
                    )
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = startText, onValueChange = { startText = it },
                        label = { Text("Start timecode", style = MaterialTheme.typography.labelSmall) },
                        placeholder = { Text("00:00:00") }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = displayedEnd, onValueChange = {
                        endText = it; endEdited = true; automaticEnd = false
                    }, label = { Text("End timecode", style = MaterialTheme.typography.labelSmall) },
                        placeholder = { Text("00:00:00") }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
                Text("Minutes:seconds:frames", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = automaticEnd, onCheckedChange = {
                        automaticEnd = it
                        if (it) { endEdited = false; endText = displayedEnd }
                    })
                    Text("End follows layers", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(value = fpsText, onValueChange = { fpsText = it },
                    label = { Text("Framerate", style = MaterialTheme.typography.labelSmall) },
                    suffix = { Text("fps") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = MaterialTheme.typography.bodyMedium)

                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val w = widthText.toIntOrNull() ?: 0
                val h = heightText.toIntOrNull() ?: 0
                val selectedRate = rate
                if (selectedRate == null) {
                    errorMessage = "Enter a framerate from 1 to 120 fps"
                    return@TextButton
                }
                val start = startUs
                val end = CompositionTimecode.parse(displayedEnd, selectedRate)
                if (start == null || end == null || end < start) {
                    errorMessage = "Use minutes:seconds:frames, with end at or after start"
                    return@TextButton
                }
                val durUs = if (automaticEnd) null else if (!endEdited) composition.resolvedDurationUs else end - start
                if (w <= 0 || h <= 0) {
                    errorMessage = "Width and height must be positive"
                    return@TextButton
                }
                if (w % 2 != 0 || h % 2 != 0) {
                    errorMessage = "Width and height must be even numbers"
                    return@TextButton
                }
                if (w.toLong() * h > 1920L * 1080L) {
                    errorMessage = "Resolution must not exceed 1080p area (1920x1080)"
                    return@TextButton
                }
                onSaveSettings(w, h, durUs, selectedRate, start)
                onDismiss()
            }) {
                Text("Save", color = Highlight)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        },
        containerColor = Surface,
    )
}

@Preview(showBackground = true)
@Preview(name = "Dark Mode", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun CompositionSettingsDialogPreview() {
    FluxxTheme {
        CompositionSettingsDialog(
            composition = Composition(width = 1920, height = 1080, durationUs = 5_000_000L),
            onSaveSettings = { _, _, _, _, _ -> },
            onDismiss = {}
        )
    }
}
