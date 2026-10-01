package com.fluxx.android.ui.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fluxx.android.editor.*
import com.fluxx.android.model.*

/** Fixed controls; the multiline field owns its own vertical scrolling. */
@Composable
internal fun TextEditorControls(layer: CompositionLayer, time: Long, compact: Boolean,
    onAction: (EditorAction) -> Unit, onFocus: (AnimPropertyType?) -> Unit,
    onBeginSource: () -> Unit, onDraft: (String) -> Unit, onFinish: () -> Unit, onCancel: () -> Unit,
    onStyle: (TextProperties) -> Unit, onSizeBegin: () -> Unit, onSizePreview: (Float) -> Unit,
    onSizeCommit: () -> Unit, onOpenFill: () -> Unit, error: String?) {
    val local = time - layer.resolvedKeyframeAnchorUs
    val evaluated = layer.text.source.evaluate(local)
    var draft by remember(layer.id) { mutableStateOf(evaluated) }
    var focused by remember(layer.id) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(evaluated, focused) { if (!focused) draft = evaluated }
    fun done() { focusManager.clearFocus(); onFinish() }
    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (!compact) Row(Modifier.fillMaxWidth()) {
            Text("Source Text · Hold", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            TextPropertyDiamond(layer, AnimPropertyType.SOURCE_TEXT, time, onAction, onFocus)
        }
        OutlinedTextField(value = draft, onValueChange = {
            draft = it; onDraft(it)
        }, modifier = Modifier.fillMaxWidth().weight(1f).onFocusChanged {
            if (it.isFocused && !focused) { onFocus(AnimPropertyType.SOURCE_TEXT); onBeginSource() }
            if (!it.isFocused && focused) onFinish()
            focused = it.isFocused
        }.semantics { contentDescription = "Source Text" },
            textStyle = MaterialTheme.typography.bodySmall, minLines = 1, maxLines = if (compact) 4 else 2,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { done() }), isError = error != null)
        if (compact) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onCancel(); focusManager.clearFocus() }) { Text("Cancel") }
                Button(onClick = { done() }) { Text("Done") }
            }
        } else {
            var fontsOpen by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { focusManager.clearFocus(); fontsOpen = true }) {
                        Text(when (layer.text.fontId) { "fluxx.sans" -> "Inter"; "fluxx.serif" -> "Noto Serif"; "fluxx.mono" -> "JetBrains Mono"; else -> "Inter (fallback)" }, maxLines = 1)
                    }
                    DropdownMenu(fontsOpen, { fontsOpen = false }) {
                        listOf("fluxx.sans" to "Inter", "fluxx.serif" to "Noto Serif", "fluxx.mono" to "JetBrains Mono").forEach { (id, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { fontsOpen = false; onStyle(layer.text.copy(fontId = id)) })
                        }
                    }
                }
                TextAlignment.entries.forEach { alignment ->
                    TextButton(onClick = { focusManager.clearFocus(); onStyle(layer.text.copy(alignment = alignment)) },
                        modifier = Modifier.width(38.dp), contentPadding = PaddingValues(0.dp)) {
                        Text(when (alignment) { TextAlignment.LEFT -> "L"; TextAlignment.CENTRE -> "C"; TextAlignment.RIGHT -> "R" },
                            color = if (alignment == layer.text.alignment) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                val size = layer.text.size.evaluate(local)
                Text("${size.toInt()} px", style = MaterialTheme.typography.labelSmall)
                Slider(value = size, onValueChange = { value ->
                    focusManager.clearFocus(); onFocus(AnimPropertyType.FONT_SIZE); onSizeBegin(); onSizePreview(value)
                }, onValueChangeFinished = onSizeCommit, valueRange = TextLimits.MIN_SIZE..TextLimits.MAX_SIZE,
                    modifier = Modifier.weight(1f).height(36.dp).semantics { contentDescription = "Font Size" })
                TextPropertyDiamond(layer, AnimPropertyType.FONT_SIZE, time, onAction, onFocus)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = { focusManager.clearFocus(); onFocus(AnimPropertyType.FILL_COLOUR); onOpenFill() }, modifier = Modifier.weight(1f)) {
                    Box(Modifier.size(18.dp).background(Color(layer.text.fill.evaluate(local))))
                    Spacer(Modifier.width(6.dp)); Text("Fill Colour")
                }
                TextPropertyDiamond(layer, AnimPropertyType.FILL_COLOUR, time, onAction, onFocus)
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, maxLines = 2) }
    }
}

@Composable
private fun TextPropertyDiamond(layer: CompositionLayer, property: AnimPropertyType, time: Long,
    onAction: (EditorAction) -> Unit, onFocus: (AnimPropertyType?) -> Unit) {
    val local = time - layer.resolvedKeyframeAnchorUs
    val enabled = when (property) { AnimPropertyType.SOURCE_TEXT -> layer.text.source.isAnimated
        AnimPropertyType.FONT_SIZE -> layer.text.size.isAnimated; else -> layer.text.fill.isAnimated }
    val key = layer.propertyKeyTimes(property).contains(local)
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.width(60.dp).height(36.dp)) {
        TextButton(onClick = { onFocus(property)
            val action = if (!enabled) EditorAction.ToggleAnimated(layer.id, property, true, time)
                else if (key) EditorAction.RemoveKeyframe(layer.id, property, local)
                else when (property) {
                    AnimPropertyType.SOURCE_TEXT -> EditorAction.SetStringKeyframe(layer.id, local, layer.text.source.evaluate(local))
                    AnimPropertyType.FONT_SIZE -> EditorAction.SetKeyframe(layer.id, property, local, layer.text.size.evaluate(local))
                    else -> EditorAction.SetColourKeyframe(layer.id, local, layer.text.fill.evaluate(local))
                }
            onAction(action)
        }, modifier = Modifier.size(36.dp).semantics { contentDescription = "$property keyframe diamond" }, contentPadding = PaddingValues(0.dp)) {
            Text(if (key) "◆" else "◇")
        }
        // Same track enable/freeze and easing capabilities as the shared property framework.
        if (enabled) TextButton(onClick = { onFocus(property); menu = true }, modifier = Modifier.width(24.dp).offset(x = 36.dp), contentPadding = PaddingValues(0.dp)) { Text("⋮") }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Freeze at playhead") }, onClick = { menu = false; onAction(EditorAction.ToggleAnimated(layer.id, property, false, time)) })
            if (property != AnimPropertyType.SOURCE_TEXT && key) EasingPreset.entries.forEach { easing ->
                DropdownMenuItem(text = { Text(if (easing == EasingPreset.LINEAR) "Linear" else "Easy Ease") }, onClick = {
                    menu = false; onAction(if (property == AnimPropertyType.FONT_SIZE) EditorAction.SetKeyframe(layer.id, property, local, layer.text.size.evaluate(local), easing)
                        else EditorAction.SetColourKeyframe(layer.id, local, layer.text.fill.evaluate(local), easing))
                })
            }
        }
    }
}
