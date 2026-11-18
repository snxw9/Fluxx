package com.fluxx.android.ui.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fluxx.android.model.Marker
import com.fluxx.android.ui.common.colorpicker.ColourPickerVariant
import com.fluxx.android.ui.common.colorpicker.FluxxColourPicker
import com.fluxx.android.ui.common.colorpicker.MARKER_SWATCHES
import com.fluxx.android.ui.inspector.AnchoredPopover

@Composable
internal fun MarkerDialog(marker: Marker, existing: Boolean, onDismiss: () -> Unit,
    onSave: (Marker) -> Unit, onDelete: () -> Unit) {
    var color by remember(marker) { mutableIntStateOf(marker.colorArgb) }
    var description by remember(marker) { mutableStateOf(marker.description) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(horizontal = 12.dp),
            shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (existing) "Edit marker" else "Add marker", Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium)
                    val tint = MaterialTheme.colorScheme.onSurface
                    IconButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = "Close marker editor" }) {
                        Canvas(Modifier.size(18.dp)) {
                            drawLine(tint, Offset.Zero, Offset(size.width, size.height), 2.dp.toPx())
                            drawLine(tint, Offset(size.width, 0f), Offset(0f, size.height), 2.dp.toPx())
                        }
                    }
                }
                OutlinedTextField(value = description, onValueChange = {
                    description = it.replace("\n", "").replace("\r", "").take(128)
                }, singleLine = true, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                MarkerColourControl(color) { color = it }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (existing) TextButton(onClick = onDelete) { Text("Delete") }
                    Button(onClick = { onSave(marker.copy(colorArgb = color, description = description)) }) {
                        Text(if (existing) "Save" else "Add")
                    }
                }
            }
        }
    }
}

/**
 * Marker colour control: a swatch + hex chip that opens the COMPACT shared picker as a popover anchored to this
 * control (clamped to the window with 8dp margins, never centred). The colour is staged locally exactly as before and
 * persists only on Add/Save (one undo); the swatch strip is the marker palette.
 */
@Composable
internal fun MarkerColourControl(argb: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var before by remember { mutableIntStateOf(argb) }
    val shape = RoundedCornerShape(10.dp)
    Box {
        Row(Modifier.fillMaxWidth().height(48.dp).clip(shape).border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(role = Role.Button, onClickLabel = "Edit marker colour") { open = true }
            .semantics { contentDescription = "Marker colour #${"%08X".format(argb)}; opens the colour picker" }
            .testTag("marker-colour-control").padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(Color(argb)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)))
            Text("Colour", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
            Text("#${"%08X".format(argb)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
        }
        AnchoredPopover(open, { open = false }, "marker-colour-popover") {
            FluxxColourPicker(ColourPickerVariant.COMPACT, argb,
                onBegin = { before = argb }, onPreview = onChange, onFinish = {}, onCancel = { onChange(before) },
                name = "marker colour", swatches = MARKER_SWATCHES)
        }
    }
}
