package com.fluxx.android.ui.timeline

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fluxx.android.model.FluxxColor
import com.fluxx.android.model.Marker
import com.fluxx.android.ui.common.colorpicker.FluxxColorPicker

@Composable
internal fun MarkerDialog(marker: Marker, existing: Boolean, onDismiss: () -> Unit,
    onSave: (Marker) -> Unit, onDelete: () -> Unit) {
    var color by remember(marker) { mutableStateOf(FluxxColor.fromArgb(marker.colorArgb)) }
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
                FluxxColorPicker(color, { color = it }, modifier = Modifier.height(208.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (existing) TextButton(onClick = onDelete) { Text("Delete") }
                    Button(onClick = { onSave(marker.copy(colorArgb = color.toArgb(), description = description)) }) {
                        Text(if (existing) "Save" else "Add")
                    }
                }
            }
        }
    }
}
