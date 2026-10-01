package com.fluxx.android.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxx.android.CompositionPreset
import com.fluxx.android.CompositionPresetStore
import com.fluxx.android.model.CompositionDraft
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import com.fluxx.android.ui.navigation.*
import com.fluxx.android.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CompositionCreationSheet(
    draft: CompositionDraft,
    busy: Boolean,
    creationError: String?,
    presets: CompositionPresetStore,
    onUpdate: (CompositionDraft) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    val latestBusy by rememberUpdatedState(busy)
    val confirmSheetChange = remember { { value: SheetValue -> !latestBusy || value != SheetValue.Hidden } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = confirmSheetChange)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val bottomInset = with(density) { WindowInsets.safeDrawing.getBottom(this).toDp() }
    val heightCap = ((LocalConfiguration.current.screenHeightDp * .85f).dp - bottomInset).coerceAtLeast(0.dp)
    var saved by remember { mutableStateOf<List<CompositionPreset>>(emptyList()) }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }
    var showPresetName by rememberSaveable { mutableStateOf(false) }
    var presetName by rememberSaveable { mutableStateOf("") }
    var removePreset by rememberSaveable { mutableStateOf<String?>(null) }

    var storing by remember { mutableStateOf(false) }
    LaunchedEffect(presets) {
        try { saved = presets.load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { localError = failure.message ?: "Could not load presets" }
    }
    fun close() { if (!busy) scope.launch { sheetState.hide(); onDismiss() } }
    fun presetAction(action: suspend () -> List<CompositionPreset>) {
        if (storing) return
        storing = true
        scope.launch {
            try { saved = action(); showPresetName = false; removePreset = null; localError = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { localError = failure.message ?: "Could not save preset" }
            finally { storing = false }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Keep the anchor layout unconstrained: it must see the full window height.
        dragHandle = null,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        containerColor = Surface,
        scrimColor = Color(0x66000000)
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = heightCap).wrapContentHeight()
            .padding(horizontal = 16.dp)) {
            Box(Modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(32.dp, 4.dp).background(TextSecondary, CircleShape))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Composition settings", Modifier.weight(1f), style = SheetHeaderStyle)
                IconButton(onClick = { close() }, enabled = !busy, modifier = Modifier.size(48.dp)
                    .semantics { contentDescription = "Close composition settings" }) {
                    ProjectVectorIcon(ProjectGlyph.Close, TextPrimary)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Project", "Advanced", "3D").forEachIndexed { index, title ->
                    val active = draft.tab == index
                    Box(Modifier.weight(1f).height(48.dp).clip(CircleShape)
                        .background(if (active) SurfaceElevated else Surface)
                        .selectable(active, enabled = !busy, role = Role.Tab,
                            onClick = { onUpdate(draft.copy(tab = index)) }),
                        contentAlignment = Alignment.Center) {
                        Text(title, color = if (active) Highlight else TextSecondary,
                            style = if (active) MaterialTheme.typography.titleSmall else SheetFieldLabelStyle)
                    }
                }
            }
            TextField(value = draft.name, onValueChange = { onUpdate(draft.copy(name = it)) },
                modifier = Modifier.fillMaxWidth(), label = { Text("Project name", style = SheetFieldLabelStyle) },
                textStyle = SheetFieldValueStyle, singleLine = true, enabled = !busy,
                colors = TextFieldDefaults.colors(focusedContainerColor = Surface,
                    unfocusedContainerColor = Surface, disabledContainerColor = Surface))
            Column(Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (draft.tab) {
                    0 -> {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
                        ) {
                            listOf("16:9", "9:16", "1:1", "4:3", "4:5").forEach { aspect ->
                                PresetChip(aspect, draft.customPreset == null && draft.aspect == aspect,
                                    enabled = !busy, onClick = { onUpdate(draft.withAspect(aspect)) })
                            }
                            IconButton(onClick = { onUpdate(draft.copy(pencil = !draft.pencil)) }, enabled = !busy,
                                modifier = Modifier.size(36.dp)
                                    .semantics { contentDescription = "Toggle custom dimensions"; selected = draft.pencil }) {
                                ProjectVectorIcon(ProjectGlyph.Pencil, if (draft.pencil) Highlight else TextSecondary,
                                    isActive = draft.pencil)
                            }
                            IconButton(onClick = { presetName = ""; showPresetName = true; localError = null },
                                enabled = !busy, modifier = Modifier.size(36.dp)
                                    .semantics { contentDescription = "Save composition preset" }) {
                                ProjectVectorIcon(ProjectGlyph.Save, TextPrimary)
                            }
                            if (saved.isNotEmpty()) {
                                var expanded by remember { mutableStateOf(false) }
                                Box {
                                    TextButton(onClick = { expanded = true }, enabled = !busy) { Text("Saved presets", color = TextPrimary) }
                                    DropdownMenu(expanded, { expanded = false }, containerColor = Surface) {
                                        saved.forEach { preset ->
                                            Row(Modifier.widthIn(min = 240.dp), verticalAlignment = Alignment.CenterVertically) {
                                                DropdownMenuItem(modifier = Modifier.weight(1f),
                                                    text = { Text(preset.name) }, onClick = {
                                                        onUpdate(draft.copy(width = preset.width.toString(),
                                                            height = preset.height.toString(), fps = preset.fps,
                                                            aspect = preset.aspectRatioLabel, customPreset = preset.name))
                                                        expanded = false
                                                    })
                                                IconButton(onClick = { removePreset = preset.name; expanded = false },
                                                    modifier = Modifier.semantics { contentDescription = "Delete preset " + preset.name }) {
                                                    ProjectVectorIcon(ProjectGlyph.Close, TextSecondary)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (draft.pencil) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                DimensionField("Width", draft.width, !busy, Modifier.weight(1f)) {
                                    onUpdate(draft.copy(width = it, customPreset = null))
                                }
                                DimensionField("Height", draft.height, !busy, Modifier.weight(1f)) {
                                    onUpdate(draft.copy(height = it, customPreset = null))
                                }
                            }
                        } else {
                            val normal = runCatching { draft.withAspect() }.getOrNull()
                            val label = if (normal != null && normal.width == draft.width && normal.height == draft.height)
                                resolutionLabel(draft.resolution) else "Custom (${draft.width} x ${draft.height})"
                            ChoiceRow("Resolution", label, listOf(1080, 720, 540), !busy,
                                labelFor = ::resolutionLabel) { onUpdate(draft.withAspect(pixels = it)) }
                        }
                        ChoiceRow("Frame Rate", "${draft.fps}fps", listOf(12, 14, 18, 20, 24, 25, 30, 50, 60, 120), !busy,
                            labelFor = { "${it}fps" }) { onUpdate(draft.copy(fps = it)) }
                    }
                    1 -> Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Motion Blur", Modifier.weight(1f).alpha(.33f), style = SheetFieldLabelStyle)
                        Switch(checked = false, onCheckedChange = null, enabled = false, modifier = Modifier.alpha(.33f),
                            colors = SwitchDefaults.colors(disabledUncheckedThumbColor = TextPrimary,
                                disabledUncheckedTrackColor = SurfaceElevated, disabledUncheckedBorderColor = TextSecondary))
                        Text("Not yet available", Modifier.padding(start = 8.dp).alpha(.33f), style = CardSecondaryStyle)
                    }
                    2 -> Column(Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        ProjectVectorIcon(ProjectGlyph.Cube, TextSecondary, Modifier.size(40.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Coming soon", color = TextSecondary, fontSize = 14.sp)
                    }
                }
            }
            (creationError ?: localError)?.let { Text(it, color = TextPrimary, style = CardSecondaryStyle) }
            Button(onClick = onCreate, enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Highlight, contentColor = Color(0xFF0A0A0A)),
                modifier = Modifier.align(Alignment.End).padding(vertical = 8.dp)) {
                Text(if (busy) "Creating" else "Create Project", color = if (busy) TextDisabled else Color(0xFF0A0A0A))
            }
        }
    }
    if (showPresetName) AlertDialog(onDismissRequest = { if (!storing) showPresetName = false }, containerColor = Surface,
        title = { Text("Save preset", style = SheetHeaderStyle) },
        text = { Column {
            TextField(presetName, { presetName = it }, singleLine = true, enabled = !storing, label = { Text("Preset name") })
            localError?.let { Text(it, style = CardSecondaryStyle) }
        } },
        confirmButton = { Button(colors = ButtonDefaults.buttonColors(containerColor = Highlight, contentColor = Color(0xFF0A0A0A)), enabled = !storing && presetName.isNotBlank(), onClick = {
            presetAction {
                val settings = draft.copy(name = "Preset", duration = "1").request()
                fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
                val divisor = gcd(settings.width, settings.height)
                presets.save(CompositionPreset(presetName.trim(), settings.width, settings.height, settings.fps,
                    "${settings.width / divisor}:${settings.height / divisor}"))
            }
        }) { Text("Save Preset", color = Color(0xFF0A0A0A)) } },
        dismissButton = { TextButton(enabled = !storing, onClick = { showPresetName = false }) { Text("Cancel") } })
    removePreset?.let { name -> AlertDialog(onDismissRequest = { if (!storing) removePreset = null }, containerColor = Surface,
        title = { Text("Delete preset?", style = SheetHeaderStyle) }, text = { Column {
            Text(name)
            localError?.let { Text(it, style = CardSecondaryStyle) }
        } },
        confirmButton = { TextButton(enabled = !storing, onClick = { presetAction { presets.delete(name) } }) { Text("Delete") } },
        dismissButton = { TextButton(enabled = !storing, onClick = { removePreset = null }) { Text("Cancel") } }) }
}

@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null
) {
    val parts = remember(label) { label.split(':') }
    val aspectW = parts.getOrNull(0)?.toFloatOrNull() ?: 16f
    val aspectH = parts.getOrNull(1)?.toFloatOrNull() ?: 9f
    val ratio = aspectW / aspectH

    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 10.sp,
        letterSpacing = 0.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
    )
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Reserve enough space inside even the narrow portrait outline, including font scaling.
    // All five chips share a row height; labels never sit across the rectangle's stroke.
    val portraitLabel = textMeasurer.measure("9:16", labelStyle)
    val measuredLabel = textMeasurer.measure(label, labelStyle)
    val outlineHeight = with(density) {
        maxOf(
            40.dp.toPx(),
            (portraitLabel.size.width + 6.dp.toPx()) / (9f / 16f),
            (measuredLabel.size.width + 6.dp.toPx()) / ratio,
            measuredLabel.size.height + 6.dp.toPx()
        ).toDp()
    }
    val chipHeight = outlineHeight + 8.dp
    val chipWidth = (outlineHeight * ratio + 8.dp).coerceAtLeast(48.dp)

    Box(
        modifier = Modifier
            .width(chipWidth)
            .height(chipHeight)
            .clip(MaterialTheme.shapes.small)
            .semantics { this.selected = selected }
            .background(if (selected) SurfaceElevated else Surface)
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = longClickLabel
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 1.3.dp.toPx()
            val padX = 4.dp.toPx()
            val padY = 4.dp.toPx()
            val maxW = size.width - padX * 2f
            val maxH = size.height - padY * 2f

            val rectH = minOf(maxH, maxW / ratio)
            val rectW = minOf(maxW, rectH * ratio)

            val left = (size.width - rectW) / 2f
            val top = (size.height - rectH) / 2f
            val corner = 2.dp.toPx()

            drawRoundRect(
                color = if (selected) Highlight else TextSecondary,
                topLeft = Offset(left, top),
                size = Size(rectW, rectH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
                style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            )
        }

        Text(
            text = label,
            color = if (selected) Highlight else TextSecondary,
            style = labelStyle,
            maxLines = 1,
            softWrap = false,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun DimensionField(label: String, value: String, enabled: Boolean, modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Number, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, modifier = modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
        label = { Text(label, style = SheetFieldLabelStyle) }, textStyle = SheetFieldValueStyle,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Highlight, unfocusedBorderColor = Border))
}

private fun resolutionLabel(value: Int) = when (value) { 1080 -> "1080p (FHD)"; 720 -> "720p (HD)"; else -> "540p (SD)" }

@Composable
private fun ChoiceRow(label: String, value: String, options: List<Int>, enabled: Boolean,
    labelFor: (Int) -> String, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = SheetFieldLabelStyle)
        Box {
            TextButton(enabled = enabled, onClick = { expanded = true },
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.textButtonColors(containerColor = SurfaceElevated),
                contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text(value, style = SheetFieldValueStyle)
                ChevronRightIcon(TextSecondary, Modifier.rotate(90f))
            }
            DropdownMenu(expanded, { expanded = false }, containerColor = Surface) {
                options.forEach { option -> DropdownMenuItem(text = { Text(labelFor(option)) },
                    onClick = { expanded = false; onSelect(option) }) }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
fun AspectChipsAndIconsPreview() {
    FluxxTheme {
        Column(
            modifier = Modifier
                .background(Background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Aspect Ratio Chips (Number Inside)", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
            ) {
                listOf("16:9", "9:16", "1:1", "4:3", "4:5").forEachIndexed { index, aspect ->
                    PresetChip(
                        label = aspect,
                        selected = index == 0,
                        enabled = true,
                        onClick = {}
                    )
                }
                IconButton(
                    onClick = {},
                    modifier = Modifier.size(36.dp)
                ) {
                    ProjectVectorIcon(ProjectGlyph.Pencil, TextPrimary)
                }
                IconButton(
                    onClick = {},
                    modifier = Modifier.size(36.dp)
                ) {
                    ProjectVectorIcon(ProjectGlyph.Save, TextPrimary)
                }
            }

            Text("Redrawn Shell & Project Icons (1.3dp weight, round caps/joins)", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HomeNavIcon(isActive = true, color = Highlight)
                HomeNavIcon(isActive = false, color = TextSecondary)
                ProjectsNavIcon(isActive = true, color = Highlight)
                ProjectsNavIcon(isActive = false, color = TextSecondary)
                UserOutlineIcon(color = TextPrimary)
                ShellBackArrowIcon(color = TextPrimary)
                ChevronRightIcon(color = TextSecondary)
                EmptyProjectGlyph(color = TextSecondary)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProjectVectorIcon(ProjectGlyph.Plus, TextPrimary)
                ProjectVectorIcon(ProjectGlyph.More, TextSecondary)
                ProjectVectorIcon(ProjectGlyph.Aspect, TextPrimary)
                ProjectVectorIcon(ProjectGlyph.Resolution, TextPrimary)
                ProjectVectorIcon(ProjectGlyph.Duration, TextPrimary)
                ProjectVectorIcon(ProjectGlyph.FrameRate, TextPrimary)
                ProjectVectorIcon(ProjectGlyph.FileSize, TextPrimary)
                ProjectVectorIcon(ProjectGlyph.Cube, TextPrimary)
                MissingMediaWarningBadge()
            }
        }
    }
}
