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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.fluxx.android.editor.*
import com.fluxx.android.model.*

/** Fixed controls; the multiline field owns its own vertical scrolling. */
@Composable
internal fun TextEditorControls(layer: CompositionLayer, time: Long, compact: Boolean,
    onAction: (EditorAction) -> Unit, onFocus: (AnimPropertyType?) -> Unit,
    onBeginSource: () -> Unit, onDraft: (String) -> Unit, onFinish: () -> Unit, onCancel: () -> Unit,
    onStyle: (TextProperties) -> Unit, onSizeBegin: () -> Unit, onSizePreview: (Float) -> Unit,
    onSizeCommit: () -> Unit, onOpenFill: (AnimPropertyType) -> Unit, error: String?,
    onScalarPreview: (AnimPropertyType, Float) -> Unit,
    onCharacter: (Int,Int,(TextRunStyle)->TextRunStyle)->Unit = { _,_,_-> },
    onDraftSelection: (String,Int,Int)->Unit = { value,_,_->onDraft(value) },
    onParagraph: (Int,Int,(TextParagraphStyle)->TextParagraphStyle)->Unit = { _,_,_-> },onBoxWidth: (Float)->Unit = {}) {
    val local = time - layer.resolvedKeyframeAnchorUs
    val evaluated = layer.text.source.evaluate(local)
    val fontLibrary=com.fluxx.android.fonts.FontLibraryRepository.getInstance(LocalContext.current)
    val libraryEntries by fontLibrary.state.collectAsState()
    var fontLibraryReady by remember { mutableStateOf(false) }
    LaunchedEffect(fontLibrary) { runCatching { fontLibrary.load() };fontLibraryReady=true }
    val missingFonts=fontLibraryReady && (layer.text.fontId !in TextFontRef.BUNDLED_IDS || layer.text.source.evaluateDocument(local).runs.any { run ->
        val font=run.style.font
        font!=null && !fontLibrary.contains(font)
    })
    var draft by remember(layer.id) { mutableStateOf(TextFieldValue(evaluated)) }
    var focused by remember(layer.id) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(evaluated, focused) { if (!focused && draft.text!=evaluated) draft = TextFieldValue(evaluated) }
    fun done() { focusManager.clearFocus(); onFinish() }
    var page by remember(layer.id) { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (!compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("Text", "Spacing", "Stroke", "Character", "Paragraph").forEachIndexed { index, label ->
                TextButton(modifier=Modifier.weight(1f),contentPadding=PaddingValues(0.dp),onClick = { done(); page = index; onFocus(null) }) { Text(label,style=MaterialTheme.typography.labelSmall,maxLines=1,
                    color = if (page == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
            }
        }
        if (compact || page == 0) {
        if(missingFonts) Text("Missing font · using Inter; requested reference retained",maxLines=1,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall)
        if (!compact) Row(Modifier.fillMaxWidth()) {
            Text("Source Text · Hold", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            TextPropertyDiamond(layer, AnimPropertyType.SOURCE_TEXT, time, onAction, onFocus)
        }
        OutlinedTextField(value = draft, onValueChange = {
            val previous=draft;val changed=previous.text!=it.text;draft = it
            if(changed) {
                val delta=it.text.length-previous.text.length
                val start=if(previous.selection.collapsed && delta<0) it.selection.min else previous.selection.min
                val end=if(previous.selection.collapsed && delta<0) start-delta else previous.selection.max
                onDraftSelection(it.text,start,end)
            }
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
                TextButton(onClick = { focusManager.clearFocus(); onFocus(AnimPropertyType.FILL_COLOUR); onOpenFill(AnimPropertyType.FILL_COLOUR) }, modifier = Modifier.weight(1f)) {
                    Box(Modifier.size(18.dp).background(Color(layer.text.fill.evaluate(local))))
                    Spacer(Modifier.width(6.dp)); Text("Fill Colour")
                }
                TextPropertyDiamond(layer, AnimPropertyType.FILL_COLOUR, time, onAction, onFocus)
            }
        }
        }
        if (!compact && page == 1) {
            TextScalarControl(layer, time, AnimPropertyType.TRACKING, "Tracking · 1/1000 em", -500f..1000f, onAction, onFocus, onSizeBegin, onScalarPreview, onSizeCommit)
            TextScalarControl(layer, time, AnimPropertyType.LEADING, "Additional leading · px", -500f..500f, onAction, onFocus, onSizeBegin, onScalarPreview, onSizeCommit)
            TextScalarControl(layer, time, AnimPropertyType.BASELINE_SHIFT, "Baseline shift · px", -500f..500f, onAction, onFocus, onSizeBegin, onScalarPreview, onSizeCommit)
            Text("Layer tracking splits ligatures away from zero; combining marks stay together.", style = MaterialTheme.typography.labelSmall)
        }
        if (!compact && page == 4) {
            val document=layer.text.source.evaluateDocument(local).replace(draft.text)
            ParagraphControls(document,draft.selection.min,draft.selection.max,layer.text.alignment,onParagraph,onBoxWidth)
        }
        if (!compact && page == 3) {
            val document=layer.text.source.evaluateDocument(local).replace(draft.text)
            val context=LocalContext.current;val ligatures=layer.text.effectiveLigatures(local)
            val metrics by produceState<TextLayoutMetrics?>(null,document,layer.text.fontId,ligatures,libraryEntries) {
                value=try { com.fluxx.android.render.TextMetricsRepository.get(context).measure(layer.text.fontId,document,ligatures) } catch(_:Exception) { null }
            }
            CharacterControls(document,draft.selection.min,draft.selection.max,layer.text.fontId,onCharacter,
                metrics?.strokeClampLabel(layer.text,local,draft.selection.min,draft.selection.max))
        }
        if (!compact && page == 2) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = { done(); onFocus(AnimPropertyType.STROKE_COLOUR); onOpenFill(AnimPropertyType.STROKE_COLOUR) }, modifier = Modifier.weight(1f)) {
                    Box(Modifier.size(18.dp).background(Color(layer.text.strokeColour.evaluate(local))))
                    Spacer(Modifier.width(6.dp)); Text("Stroke Colour")
                }
                TextPropertyDiamond(layer, AnimPropertyType.STROKE_COLOUR, time, onAction, onFocus)
            }
            TextScalarControl(layer, time, AnimPropertyType.STROKE_WIDTH, "Stroke width · px", 0f..500f, onAction, onFocus, onSizeBegin, onScalarPreview, onSizeCommit)
            val requested = layer.text.strokeWidth.evaluate(local)
            val effective = layer.text.effectiveStrokeWidth(local)
            if (requested > effective) Text("Limited to ${"%.2f".format(effective)} px at this font size (requested ${"%.2f".format(requested)} px)",
                color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelSmall, maxLines = 2)
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = {
                    done(); onFocus(AnimPropertyType.STROKE_OVER_FILL)
                    onAction(EditorAction.SetTextPayload(layer.id, TextEdits.scalar(layer, time, AnimPropertyType.STROKE_OVER_FILL,
                        if (layer.text.paintOrder(local) == 0) 1f else 0f)))
                }, modifier = Modifier.weight(1f)) { Text(if (layer.text.paintOrder(local) == 0) "Fill over stroke" else "Stroke over fill") }
                TextPropertyDiamond(layer, AnimPropertyType.STROKE_OVER_FILL, time, onAction, onFocus)
            }
            Text("Centred stroke · round joins", style = MaterialTheme.typography.labelSmall)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, maxLines = 2) }
    }
}

@Composable
private fun TextPropertyDiamond(layer: CompositionLayer, property: AnimPropertyType, time: Long,
    onAction: (EditorAction) -> Unit, onFocus: (AnimPropertyType?) -> Unit) {
    val local = time - layer.resolvedKeyframeAnchorUs
    val enabled = layer.text.animated(property)
    val key = layer.propertyKeyTimes(property).contains(local)
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.width(60.dp).height(36.dp)) {
        TextButton(onClick = { onFocus(property)
            val action = if (!enabled) EditorAction.ToggleAnimated(layer.id, property, true, time)
                else if (key) EditorAction.RemoveKeyframe(layer.id, property, local)
                else when (property) {
                    AnimPropertyType.SOURCE_TEXT -> EditorAction.SetStringKeyframe(layer.id, local, layer.text.source.evaluate(local))
                    AnimPropertyType.FILL_COLOUR, AnimPropertyType.STROKE_COLOUR -> EditorAction.SetColourKeyframe(layer.id, local, layer.text.colour(property).evaluate(local), property = property)
                    else -> EditorAction.SetKeyframe(layer.id, property, local, layer.text.scalarValue(property, local))
                }
            onAction(action)
        }, modifier = Modifier.size(36.dp).semantics { contentDescription = "$property keyframe diamond" }, contentPadding = PaddingValues(0.dp)) {
            Text(if (key) "◆" else "◇")
        }
        // Same track enable/freeze and easing capabilities as the shared property framework.
        if (enabled) TextButton(onClick = { onFocus(property); menu = true }, modifier = Modifier.width(24.dp).offset(x = 36.dp), contentPadding = PaddingValues(0.dp)) { Text("⋮") }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Freeze at playhead") }, onClick = { menu = false; onAction(EditorAction.ToggleAnimated(layer.id, property, false, time)) })
            if (property != AnimPropertyType.SOURCE_TEXT && property != AnimPropertyType.STROKE_OVER_FILL && key) EasingPreset.entries.forEach { easing ->
                DropdownMenuItem(text = { Text(if (easing == EasingPreset.LINEAR) "Linear" else "Easy Ease") }, onClick = {
                    menu = false; onAction(if (property == AnimPropertyType.FILL_COLOUR || property == AnimPropertyType.STROKE_COLOUR) EditorAction.SetColourKeyframe(layer.id, local, layer.text.colour(property).evaluate(local), easing, property)
                        else EditorAction.SetKeyframe(layer.id, property, local, layer.text.scalarValue(property, local), easing))
                })
            }
        }
    }
}

@Composable
private fun TextScalarControl(layer: CompositionLayer, time: Long, property: AnimPropertyType, label: String, range: ClosedFloatingPointRange<Float>,
    onAction: (EditorAction) -> Unit, onFocus: (AnimPropertyType?) -> Unit, begin: () -> Unit,
    preview: (AnimPropertyType, Float) -> Unit, commit: () -> Unit) {
    val value = layer.text.scalarValue(property, time-layer.resolvedKeyframeAnchorUs)
    Column {
        Text("$label: ${"%.1f".format(value)}", style = MaterialTheme.typography.labelSmall)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Slider(value = value.coerceIn(range), onValueChange = { onFocus(property); begin(); preview(property,it) },
                onValueChangeFinished = commit, valueRange = range, modifier = Modifier.weight(1f).height(36.dp).semantics { contentDescription = label })
            TextPropertyDiamond(layer, property, time, onAction, onFocus)
        }
    }
}
