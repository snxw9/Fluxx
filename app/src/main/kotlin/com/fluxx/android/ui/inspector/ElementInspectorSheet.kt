package com.fluxx.android.ui.inspector

import com.fluxx.android.ui.common.colorpicker.*

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import com.fluxx.android.ui.theme.*
import kotlinx.coroutines.flow.collect
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class InspectorSection {
    TRANSFORM,
    BLENDING_OPACITY,
    TIMING,
    AUDIO,
    CONTEXTUAL_EDIT, EFFECTS, LAYER_STYLES, TRACK_MATTES, MOTION_BLUR
}

enum class TransformSubMode {
    POSITION,
    ROTATION,
    SCALE
}

/** Inspector tabs use the existing single-layer selection and transform gesture protocol. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ElementInspectorSheet(
    layer: CompositionLayer,
    compositionWidth: Int,
    compositionHeight: Int,
    compositionDurationUs: Long? = null,
    playheadUs: Long = 0L,
    onAction: (EditorAction) -> Unit = {},
    onPropertyFocus: (AnimPropertyType?) -> Unit = {},
    onRename: (String) -> Unit,
    onDuplicate: () -> Unit,
    onCopyLayer: () -> Unit = {},
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onTransformBegin: () -> Unit,
    onTransformPreview: (Transform) -> Unit,
    onTransformCommit: () -> Unit,
    onTransformCancel: () -> Unit,
    onTransformEdit: (Transform) -> Unit,
    onTimingChange: (ClipTiming) -> Unit,
    onAudioChange: (muted: Boolean, gain: Float) -> Unit,
    onColorChange: (Int) -> Unit,
    onEyedropperRequest: () -> Unit = {},
    onColorPreview: (Int) -> Unit = onColorChange,
    modifier: Modifier = Modifier,
    paletteRepository: PaletteRepository? = null,
    mediaRepository: com.fluxx.android.media.MediaRepository? = null,
    onAnchorModeChange: (Boolean) -> Unit = {},
    onAnchorPreview: (EditorAction.SetAnchorPoint) -> Unit = {},
    textTyping: Boolean = false,
    textError: String? = null,
    onTextBegin: () -> Unit = {}, onTextDraft: (String) -> Unit = {},
    onTextFinish: () -> Unit = {}, onTextCancel: () -> Unit = {},
    onTextStyle: (TextProperties) -> Unit = {},
    onTextSizeBegin: () -> Unit = {}, onTextSizePreview: (Float) -> Unit = {}, onTextSizeCommit: () -> Unit = {}
) {
    key(layer.id) {
        var section by remember { mutableStateOf<InspectorSection?>(null) }
        var textFill by remember { mutableStateOf(false) }
        val focusManager = LocalFocusManager.current
        val focusCallback by rememberUpdatedState(onPropertyFocus)
        LaunchedEffect(section) {
            if (section != InspectorSection.TRANSFORM) focusCallback(if (section == InspectorSection.BLENDING_OPACITY) AnimPropertyType.OPACITY else null)
        }
        DisposableEffect(layer.id) { onDispose { focusCallback(null) } }
        val dismiss by rememberUpdatedState(onDismiss)
        var swipeOffset by remember { mutableFloatStateOf(0f) }
        val sections = listOf(InspectorSection.TRANSFORM, InspectorSection.TIMING, InspectorSection.BLENDING_OPACITY) +
            if (layer.type == LayerType.SOLID || layer.type == LayerType.TEXT) listOf(InspectorSection.CONTEXTUAL_EDIT) else emptyList()
        Surface(color = Surface, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            modifier = modifier.fillMaxWidth().offset { IntOffset(0, swipeOffset.toInt()) }) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val compactHeight = maxHeight < 320.dp
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (compactHeight) 40.dp else 48.dp) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        val textSection = layer.type == LayerType.TEXT && section == InspectorSection.CONTEXTUAL_EDIT
                        if (!textTyping && !textSection) {
                        Box(Modifier.fillMaxWidth().height(20.dp).pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart = { focusManager.clearFocus(); swipeOffset = 0f },
                                onVerticalDrag = { change, amount -> change.consume(); swipeOffset = (swipeOffset + amount).coerceAtLeast(0f) },
                                onDragEnd = { if (swipeOffset >= 48.dp.toPx()) dismiss() else swipeOffset = 0f },
                                onDragCancel = { swipeOffset = 0f }
                            )
                        }.semantics { contentDescription = "Swipe down to dismiss inspector" }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size(36.dp, 4.dp).background(Border, CircleShape))
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (section != null) IconButton(onClick = { focusManager.clearFocus(); if (textFill) textFill = false else section = null },
                                modifier = Modifier.semantics { contentDescription = "Back to layer tools" }) {
                                Canvas(Modifier.size(22.dp)) {
                                    val path = Path().apply {
                                        moveTo(size.width * .65f, size.height * .2f)
                                        lineTo(size.width * .35f, size.height * .5f)
                                        lineTo(size.width * .65f, size.height * .8f)
                                    }
                                    drawPath(path, TextPrimary, style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                                }
                            }
                            BasicTextField(
                                value = layer.name,
                                onValueChange = onRename,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.titleSmall.copy(color = TextPrimary),
                                cursorBrush = SolidColor(Highlight),
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                                    .semantics { contentDescription = "Layer name" }
                            )
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .combinedClickable(
                                        role = Role.Button,
                                        onClickLabel = "Duplicate layer",
                                        onLongClickLabel = "Copy layer",
                                        onClick = onDuplicate,
                                        onLongClick = onCopyLayer
                                    )
                                    .semantics { contentDescription = "Tap to duplicate, hold to copy" },
                                contentAlignment = Alignment.Center
                            ) {
                                DuplicateVectorIcon(TextPrimary)
                            }
                            IconButton(onClick = onDelete, modifier = Modifier.semantics { contentDescription = "Delete layer" }) {
                                DeleteVectorIcon(TextPrimary)
                            }
                            if (layer.type == LayerType.VIDEO) {
                                IconToggleButton(checked = section == InspectorSection.AUDIO,
                                    onCheckedChange = { focusManager.clearFocus(); section = if (it) InspectorSection.AUDIO else InspectorSection.TRANSFORM },
                                    modifier = Modifier.semantics { contentDescription = "Audio controls" }) {
                                    AudioVectorIcon(if (section == InspectorSection.AUDIO) Highlight else TextSecondary)
                                }
                            }
                        }
                        if (section == null) ClipQuickActionBar(layer, playheadUs, compositionDurationUs) { action ->
                            focusManager.clearFocus()
                            onAction(action)
                        }
                        if (section != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            sections.forEach { tab ->
                                val active = section == tab
                                val title = when (tab) {
                                    InspectorSection.TRANSFORM -> "Transform"
                                    InspectorSection.TIMING -> "Timing"
                                    InspectorSection.BLENDING_OPACITY -> "Blending & Opacity"
                                    InspectorSection.CONTEXTUAL_EDIT -> if (layer.type == LayerType.TEXT) "Edit Text" else "Edit Solid"
                                    InspectorSection.AUDIO -> "Audio"
                                    else -> ""
                                }
                                Column(Modifier.weight(1f).selectable(active, role = Role.Tab, onClick = {
                                    focusManager.clearFocus()
                                    section = tab
                                }).padding(top = if (compactHeight) 6.dp else 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(title, color = if (active) Highlight else TextSecondary,
                                        style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(if (compactHeight) 6.dp else 10.dp))
                                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) Highlight else Color.Transparent))
                                }
                            }
                        }
                        }
                        if (!textTyping && textSection) Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { focusManager.clearFocus(); if (textFill) textFill = false else section = null }) { Text("Back") }
                            Text(if (textFill) "Fill Colour" else "Edit Text", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = onDismiss) { Text("Done") }
                        }
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)) {
                            when (section) {
                                null -> InspectorLandingGrid(layer.type) { section=it }
                                InspectorSection.TRANSFORM -> OverhauledTransformSectionContent(
                                    layer, compositionWidth, compositionHeight,
                                    playheadUs, onAction, onTransformBegin,
                                    onTransformPreview, onTransformCommit, onTransformCancel, onTransformEdit, onPropertyFocus,
                                    mediaRepository, onAnchorModeChange, onAnchorPreview)
                                InspectorSection.BLENDING_OPACITY -> CompactOpacitySectionContent(
                                    layer, playheadUs, onAction, onTransformBegin,
                                    { onTransformPreview(layer.evaluatedTransform(playheadUs).copy(opacity = it)) },
                                    onTransformCommit, onTransformCancel,
                                    { onTransformEdit(layer.evaluatedTransform(playheadUs).copy(opacity = it)) })
                                InspectorSection.TIMING -> CompactTimingSectionContent(layer.timing, layer.type == LayerType.VIDEO, onTimingChange)
                                InspectorSection.AUDIO -> CompactAudioSectionContent(layer.muted, layer.audioGain, onAudioChange)
                                InspectorSection.CONTEXTUAL_EDIT -> if (layer.type == LayerType.TEXT && !textFill) TextEditorControls(
                                    layer, playheadUs, textTyping, onAction, onPropertyFocus,
                                    onTextBegin, onTextDraft, onTextFinish, onTextCancel, onTextStyle,
                                    onTextSizeBegin, onTextSizePreview, onTextSizeCommit, { textFill = true }, textError)
                                else FluxxColorPicker(FluxxColor.fromArgb(if (layer.type == LayerType.TEXT)
                                    layer.text.fill.evaluate(playheadUs - layer.resolvedKeyframeAnchorUs) else layer.solidColorArgb),
                                    { onColorChange(it.toArgb()) }, ColorPickerConfig(),
                                    onEyedropperRequest, Modifier.fillMaxSize(), onTransformBegin, onTransformCommit,
                                    onTransformCancel, { onColorPreview(it.toArgb()) }, paletteRepository=paletteRepository)
                                else -> Unit // Disabled categories cannot be opened.
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Exact edits remain local until Done/Enter/focus loss; one edit is one undo step. */
@Composable
private fun CompactValueBox(
    label: String,
    valueText: String,
    suffix: String = "",
    onCommitValue: (Float) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Decimal,
    parse: (String) -> Float? = { it.replace(',', '.').toFloatOrNull() }
) {
    var text by remember { mutableStateOf(valueText) }
    var focused by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(valueText, focused) { if (!focused) text = valueText }
    fun commit() {
        if (!dirty) return
        dirty = false
        val value = parse(text)
        if (value != null && value.isFinite()) onCommitValue(value) else text = valueText
    }
    Row(modifier.background(SurfaceHigh, RoundedCornerShape(4.dp))
        .border(0.5.dp, Border, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label.isNotEmpty()) Text(label, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        BasicTextField(value = text, onValueChange = { text = it; dirty = true }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit(); focusManager.clearFocus() }),
            textStyle = MaterialTheme.typography.labelMedium.copy(color = TextPrimary, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(Highlight),
            modifier = Modifier.weight(1f).semantics { contentDescription = label.ifEmpty { "Rotation degrees" } }
                .onFocusChanged { if (focused && !it.isFocused) commit(); focused = it.isFocused }
                .onPreviewKeyEvent {
                    if (it.key == Key.Enter && it.type == KeyEventType.KeyUp) {
                        commit(); focusManager.clearFocus(); true
                    } else false
                })
        if (suffix.isNotEmpty()) Text(suffix, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

/** Stable pointer keys: preview recompositions must never restart an active gesture. */
@Composable
private fun Modifier.inspectorDrag(
    onStart: (Offset, Size) -> Unit,
    onMove: (Offset, Size) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
    holdToStart: Boolean = false,
    onTap: () -> Unit = {}
): Modifier {
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onMove)
    val commit by rememberUpdatedState(onCommit)
    val cancel by rememberUpdatedState(onCancel)
    val tap by rememberUpdatedState(onTap)
    val focusManager = LocalFocusManager.current
    return pointerInput(holdToStart) {
        awaitEachGesture {
            val down = awaitFirstDown()
            focusManager.clearFocus()
            if (holdToStart && awaitLongPressOrCancellation(down.id) == null) {
                // A released short tap snaps to centre; a consumed/cancelled gesture does nothing.
                val up = currentEvent.changes.firstOrNull { it.id == down.id }
                if (up != null && !up.pressed && !up.isConsumed) { up.consume(); tap() }
                return@awaitEachGesture
            }
            var active = true
            try {
                start(down.position, Size(size.width.toFloat(), size.height.toFloat()))
                down.consume()
                val completed = drag(down.id) { change ->
                    change.consume()
                    move(change.position, Size(size.width.toFloat(), size.height.toFloat()))
                }
                active = false
                if (completed) commit() else cancel()
            } finally {
                if (active) cancel()
            }
        }
    }
}
private fun DrawScope.dotGrid() {
    val spacing = 16.dp.toPx()
    var x = spacing / 2f
    while (x < size.width) {
        var y = spacing / 2f
        while (y < size.height) {
            drawCircle(TextSecondary.copy(alpha = .25f), .8.dp.toPx(), Offset(x, y))
            y += spacing
        }
        x += spacing
    }
}

@Composable
private fun PositionTrackpad(
    xFraction: Float, yFraction: Float,
    onBegin: () -> Unit, onPreview: (Float, Float) -> Unit,
    onCommit: () -> Unit, onCancel: () -> Unit
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var base by remember { mutableStateOf(Offset.Zero) }
    Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Surface)
        .border(.5.dp, Border, RoundedCornerShape(8.dp))
        .inspectorDrag(
            onStart = { point, _ -> origin = point; base = Offset(xFraction, yFraction); onBegin() },
            onMove = { point, bounds ->
                val delta = point - origin
                onPreview(base.x + delta.x / bounds.width, base.y + delta.y / bounds.height)
            }, onCommit = onCommit, onCancel = onCancel)) {
        dotGrid()
        val inset = minOf(6.dp.toPx(), size.minDimension / 2f)
        val point = Offset((xFraction * size.width).coerceIn(inset, size.width - inset),
            (yFraction * size.height).coerceIn(inset, size.height - inset))
        drawCircle(Highlight, 5.dp.toPx(), point, style = Stroke(1.3.dp.toPx()))
        drawLine(Highlight, point - Offset(9.dp.toPx(), 0f), point + Offset(9.dp.toPx(), 0f), 1.3.dp.toPx(), StrokeCap.Round)
        drawLine(Highlight, point - Offset(0f, 9.dp.toPx()), point + Offset(0f, 9.dp.toPx()), 1.3.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
private fun RotationDialController(
    rotationDegrees: Float,
    onBegin: () -> Unit, onPreview: (Float) -> Unit,
    onCommit: () -> Unit, onCancel: () -> Unit, onEdit: (Float) -> Unit
) {
    var previousAngle by remember { mutableFloatStateOf(0f) }
    var accumulated by remember { mutableFloatStateOf(0f) }
    fun angle(point: Offset, bounds: Size) = Math.toDegrees(atan2(
        (point.y - bounds.height / 2).toDouble(), (point.x - bounds.width / 2).toDouble())).toFloat()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().inspectorDrag(
            onStart = { point, bounds -> previousAngle = angle(point, bounds); accumulated = rotationDegrees; onBegin() },
            onMove = { point, bounds ->
                val next = angle(point, bounds)
                val delta = ((next - previousAngle + 540f) % 360f) - 180f
                accumulated += delta
                previousAngle = next
                onPreview(accumulated)
            }, onCommit = onCommit, onCancel = onCancel)) {
            val radius = (size.minDimension / 2f - 12.dp.toPx()).coerceAtLeast(0f)
            drawCircle(Border, radius, center, style = Stroke(1.3.dp.toPx()))
            for (degrees in 0 until 360 step 30) {
                val radians = Math.toRadians(degrees.toDouble())
                val direction = Offset(cos(radians).toFloat(), sin(radians).toFloat())
                drawLine(TextSecondary, center + direction * (radius - 6.dp.toPx()), center + direction * radius,
                    1.3.dp.toPx(), StrokeCap.Round)
            }
            val radians = Math.toRadians(rotationDegrees.toDouble())
            val handle = center + Offset(cos(radians).toFloat(), sin(radians).toFloat()) * radius
            drawCircle(Highlight, 6.dp.toPx(), handle)
        }
        CompactValueBox("", formatRotation(rotationDegrees), "\u00b0", onEdit,
            modifier = Modifier.fillMaxWidth(.7f), keyboardType = KeyboardType.Text,
            parse = ::parseRotation)
    }
}

/** n x degrees is a count of complete turns plus a remainder, not multiplication. */
private fun formatRotation(degrees: Float): String {
    val turns = kotlin.math.floor(degrees.toDouble() / 360.0).toLong()
    val remainder = degrees.toDouble() - turns * 360.0
    return "${turns}x${number(remainder.toFloat())}"
}

private fun parseRotation(input: String): Float? {
    val text = input.trim().removeSuffix("\u00b0").replace(',', '.')
    val parts = text.lowercase().split('x', '\u00d7')
    if (parts.size == 1) return parts[0].toFloatOrNull()?.takeIf { it.isFinite() }
    if (parts.size != 2) return null
    val turns = parts[0].trim().toLongOrNull() ?: return null
    val remainder = parts[1].trim().toDoubleOrNull()?.takeIf { it in 0.0..360.0 } ?: return null
    return (turns * 360.0 + remainder).toFloat().takeIf { it.isFinite() }
}
/** Horizontal rulers share the same scale and tick spacing in linked and unlinked modes. */
@Composable
private fun ScaleController(
    scaleX: Float, scaleY: Float, linked: Boolean,
    onBegin: () -> Unit, onPreview: (Float, Float) -> Unit,
    onCommit: () -> Unit, onCancel: () -> Unit
) {
    var initial by remember { mutableStateOf(Offset(1f, 1f)) }
    Column(Modifier.fillMaxWidth().heightIn(max = if (linked) 64.dp else 136.dp).fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        key(linked) {
            ScaleRuler(if (linked) "Scale" else "Width", scaleX,
                onBegin = { initial = Offset(scaleX, scaleY); onBegin() },
                onPreview = { value ->
                    val y = if (linked) (initial.y * value / initial.x.coerceAtLeast(.05f)).coerceIn(.05f, 10f) else initial.y
                    onPreview(value, y)
                }, onCommit = onCommit, onCancel = onCancel, modifier = Modifier.weight(1f))
            if (!linked) {
                ScaleRuler("Height", scaleY,
                    onBegin = { initial = Offset(scaleX, scaleY); onBegin() },
                    onPreview = { onPreview(initial.x, it) },
                    onCommit = onCommit, onCancel = onCancel, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ScaleRuler(
    label: String, value: Float, onBegin: () -> Unit, onPreview: (Float) -> Unit,
    onCommit: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = TextSecondary, fontSize = 10.sp)
    val pixelsPerPercent = with(LocalDensity.current) { .8.dp.toPx() }
    var startX by remember { mutableFloatStateOf(0f) }
    var previousX by remember { mutableFloatStateOf(0f) }
    var base by remember { mutableFloatStateOf(value) }
    Canvas(modifier.fillMaxWidth().heightIn(max = 64.dp).clip(RoundedCornerShape(6.dp))
        .background(SurfaceHigh).semantics { contentDescription = "$label scale, ${number(value * 100f)} percent" }
        .inspectorDrag(
            onStart = { point, _ -> startX = point.x; previousX = point.x; base = value; onBegin() },
            onMove = { point, _ ->
                // Y never contributes to scale. A purely vertical movement produces no edit.
                if (point.x != previousX) {
                    previousX = point.x
                    onPreview((base + (point.x - startX) / pixelsPerPercent / 100f).coerceIn(.05f, 10f))
                }
            }, onCommit = onCommit, onCancel = onCancel)) {
        val percent = value * 100f
        val halfRange = size.width / 2f / pixelsPerPercent
        val first = (kotlin.math.floor((percent - halfRange) / 10f).toInt() * 10).coerceAtLeast(10)
        val last = (kotlin.math.ceil((percent + halfRange) / 10f).toInt() * 10).coerceAtMost(1000)
        val textHeight = textMeasurer.measure("200", labelStyle).size.height.toFloat()
        val bottom = (size.height - textHeight - 3.dp.toPx()).coerceAtLeast(0f)
        val top = (textHeight + 4.dp.toPx()).coerceAtMost(bottom)
        drawText(textMeasurer, label, Offset(6.dp.toPx(), 0f), style = labelStyle)
        for (tick in first..last step 10) {
            val x = center.x + (tick - percent) * pixelsPerPercent
            val major = tick % 50 == 0
            val tickTop = if (major) top else top + (bottom - top) * .45f
            drawLine(if (major) TextPrimary else TextSecondary, Offset(x, tickTop), Offset(x, bottom),
                1.dp.toPx(), StrokeCap.Round)
            if (major) {
                val layout = textMeasurer.measure(tick.toString(), labelStyle)
                drawText(layout, topLeft = Offset(x - layout.size.width / 2f, bottom + 2.dp.toPx()))
            }
        }
        drawLine(Highlight, Offset(center.x, top), Offset(center.x, bottom), 2.dp.toPx(), StrokeCap.Round)
    }
}

/** Fit the entire square control into the remaining sheet height, with a sensible size cap. */
@Composable
private fun FittedSquare(modifier: Modifier, maxSize: Dp, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight, maxSize)
        Box(Modifier.size(side), contentAlignment = Alignment.Center) { content() }
    }
}
private data class AnchorSource(val width: Int, val height: Int, val rotation: Int = 0, val pixelAspect: Float = 1f,
    val text: TextLayoutMetrics? = null)

@Composable
private fun AnchorSubModeIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        drawCircle(color, size.minDimension * .28f, style = Stroke(1.3.dp.toPx()))
        drawCircle(color, 2.dp.toPx())
        drawLine(color, Offset(size.width * .5f, 0f), Offset(size.width * .5f, size.height), 1.3.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(0f, size.height * .5f), Offset(size.width, size.height * .5f), 1.3.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
private fun AnchorPointControls(x: Float, y: Float, positionAnimated: Boolean, ready: Boolean,
    onEdit: (Float, Float) -> Unit, onBegin: () -> Unit, onPreview: (Float, Float) -> Unit,
    onCommit: () -> Unit, onCancel: () -> Unit) {
    var base by remember { mutableStateOf(Offset.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CompactValueBox("X", number(x * 100f), "%", { if (ready) onEdit(it / 100f, y) },
                Modifier.weight(1f).semantics { contentDescription = "Anchor X percentage" })
            CompactValueBox("Y", number(y * 100f), "%", { if (ready) onEdit(x, it / 100f) },
                Modifier.weight(1f).semantics { contentDescription = "Anchor Y percentage" })
        }
        if (positionAnimated || !ready) Text(
            if (positionAnimated) "Position is keyframed — auto-compensation skipped" else "Source geometry unavailable",
            color = TextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().background(SurfaceHigh, RoundedCornerShape(4.dp))
                .border(.5.dp, Border, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp))
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val labels = listOf("Top left", "Top", "Top right", "Left", "Centre", "Right", "Bottom left", "Bottom", "Bottom right")
            repeat(3) { row ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(3) { col ->
                        val ax = col / 2f; val ay = row / 2f
                        val selected = kotlin.math.abs(x - ax) < .001f && kotlin.math.abs(y - ay) < .001f
                        var cell = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp))
                            .background(if (selected) SurfaceHigh else Surface)
                            .border(.5.dp, if (selected) Highlight else Border, RoundedCornerShape(6.dp))
                        cell = if (row == 1 && col == 1 && ready) cell.inspectorDrag(
                            onStart = { point, _ -> base = Offset(x, y); origin = point; onBegin() },
                            onMove = { point, bounds ->
                                val delta = point - origin
                                onPreview(base.x + delta.x / (bounds.width * 3f), base.y + delta.y / (bounds.height * 3f))
                            }, onCommit = onCommit, onCancel = onCancel, holdToStart = true,
                            onTap = { onEdit(.5f, .5f) }) else cell.clickable(enabled = ready) { onEdit(ax, ay) }
                        Box(cell.semantics {
                            contentDescription = "Anchor ${labels[row * 3 + col]}"
                            if (!ready) disabled()
                            if (ready && row == 1 && col == 1) onClick("Centre anchor") { onEdit(.5f, .5f); true }
                        }, contentAlignment = Alignment.Center) {
                            Canvas(Modifier.size(18.dp)) {
                                val tint = if (!ready) TextDisabled else if (selected) Highlight else TextSecondary
                                drawRoundRect(tint, topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                                    size = Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = Stroke(1.dp.toPx()))
                                drawCircle(tint, 2.5.dp.toPx(), Offset((.15f + ax * .7f) * size.width, (.15f + ay * .7f) * size.height))
                            }
                        }
                    }
                }
            }
        }
        Text("Hold and drag centre for free positioning", color = TextSecondary,
            style = MaterialTheme.typography.labelSmall, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

private fun number(value: Float) = String.format(java.util.Locale.ROOT, "%.1f", value)

@Composable
private fun InspectorUtilityRail(resetLabel: String, onReset: () -> Unit,
    onResetAll: (() -> Unit)? = null, isAnimated: Boolean = false,
    hasKeyframeAtPlayhead: Boolean = false, onToggleKeyframe: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Column(Modifier.width(40.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick={ focus.clearFocus(); onToggleKeyframe?.invoke() }, enabled=onToggleKeyframe!=null,
            modifier=Modifier.size(36.dp).semantics { contentDescription=if(hasKeyframeAtPlayhead) "Remove keyframe at playhead" else "Add keyframe at playhead" }) {
            KeyframeVectorIcon(if(onToggleKeyframe == null) TextDisabled else if(isAnimated) Highlight else TextSecondary,hasKeyframeAtPlayhead)
        }
        IconButton(onClick={},enabled=false,modifier=Modifier.size(36.dp).semantics { contentDescription="Graph editor (not yet available)" }) { GraphVectorIcon(TextDisabled) }
        IconButton(onClick={},enabled=false,modifier=Modifier.size(36.dp).semantics { contentDescription="3D layer controls (not yet available)" }) { InspectorCategoryIcon(InspectorSection.MOTION_BLUR,TextDisabled,cube=true) }
        Box {
            IconButton(onClick={ focus.clearFocus(); menu=true },modifier=Modifier.size(36.dp).semantics { contentDescription="Property options" }) { OverflowDotsVectorIcon(TextPrimary) }
            DropdownMenu(menu,{menu=false}) {
                DropdownMenuItem(text={Text(resetLabel)},onClick={menu=false;onReset()})
                if(onResetAll!=null) DropdownMenuItem(text={Text("Reset all transforms")},onClick={menu=false;onResetAll()})
            }
        }
    }
}

@Composable
private fun OverhauledTransformSectionContent(
    layer: CompositionLayer, compositionWidth: Int, compositionHeight: Int,
    playheadUs: Long, onAction: (EditorAction) -> Unit,
    onBeginGesture: () -> Unit, onPreviewGesture: (Transform) -> Unit,
    onCommitGesture: () -> Unit, onCancelGesture: () -> Unit, onEdit: (Transform) -> Unit,
    onPropertyFocus: (AnimPropertyType?) -> Unit,
    mediaRepository: com.fluxx.android.media.MediaRepository?,
    onAnchorModeChange: (Boolean) -> Unit,
    onAnchorPreview: (EditorAction.SetAnchorPoint) -> Unit
) {
    var mode by remember { mutableStateOf(TransformSubMode.POSITION) }
    var isAnchorMode by remember { mutableStateOf(false) }
    val anchorModeCallback by rememberUpdatedState(onAnchorModeChange)
    SideEffect { anchorModeCallback(isAnchorMode) }
    DisposableEffect(Unit) { onDispose { anchorModeCallback(false) } }
    var linked by remember { mutableStateOf(true) }
    val focusManager = LocalFocusManager.current
    val currentTransform = remember(layer, playheadUs) { layer.evaluatedTransform(playheadUs) }
    val transform = currentTransform
    val refW = layer.referenceWidth.takeIf { it > 0 } ?: compositionWidth
    val refH = layer.referenceHeight.takeIf { it > 0 } ?: compositionHeight
    val halfReferenceW = refW / 2f
    val halfReferenceH = refH / 2f
    val context = LocalContext.current
    val heldSource = layer.text.source.evaluate(playheadUs - layer.resolvedKeyframeAnchorUs)
    val source by produceState<AnchorSource?>(null, layer.type, layer.asset, mediaRepository, refW, refH, layer.text.fontId, heldSource) {
        value = null
        value = if (layer.type == LayerType.TEXT) {
            try { AnchorSource(1, 1, text = com.fluxx.android.render.TextMetricsRepository.get(context).measure(layer.text.fontId, heldSource)) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        } else if (layer.type == LayerType.SOLID) AnchorSource(refW, refH) else {
            val uri = layer.asset?.uri
            if (uri == null || mediaRepository == null) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    if (layer.type == LayerType.VIDEO) mediaRepository.video(uri).let {
                        AnchorSource(it.width, it.height, it.rotation, it.pixelAspect)
                    } else mediaRepository.image(uri).let { bitmap ->
                        try { AnchorSource(bitmap.width, bitmap.height) } finally { bitmap.recycle() }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { null }
            }
        }
    }
    val compensationMatrix = remember { FloatArray(16) }
    var anchorGestureLayer by remember { mutableStateOf(layer) }
    var anchorGestureTransform by remember { mutableStateOf(transform) }
    fun anchorAction(x: Float, y: Float, preview: Boolean): EditorAction.SetAnchorPoint? {
        val base = if (preview) anchorGestureLayer else layer
        val t = if (preview) anchorGestureTransform else transform
        val ax = x.coerceIn(0f, 1f); val ay = y.coerceIn(0f, 1f)
        val position = if (base.animTransform.position.isAnimated) null else {
            val geometry = source ?: return null
            if (base.type == LayerType.TEXT) com.fluxx.android.render.LayerGeometry.compensateTextAnchor(t,
                requireNotNull(geometry.text).logical(base.text.size.evaluate(playheadUs - base.resolvedKeyframeAnchorUs)),
                base.anchorX, base.anchorY, ax, ay, refW, refH)
            else com.fluxx.android.render.LayerGeometry.compensatePosition(compensationMatrix, t,
                base.anchorX, base.anchorY, ax, ay, geometry.width, geometry.height,
                geometry.rotation, geometry.pixelAspect, refW, refH)
        }
        return EditorAction.SetAnchorPoint(layer.id, ax, ay, position)
    }
    val pixelX = compositionWidth / 2f + transform.positionX * halfReferenceW
    val pixelY = compositionHeight / 2f + transform.positionY * halfReferenceH
    var gestureBase by remember { mutableStateOf(transform) }
    val begin = { gestureBase = transform; onBeginGesture() }

    val localTimeUs = Math.subtractExact(playheadUs, layer.resolvedKeyframeAnchorUs)
    val currentProp = when (mode) {
        TransformSubMode.POSITION -> AnimPropertyType.POSITION
        TransformSubMode.SCALE -> AnimPropertyType.SCALE
        TransformSubMode.ROTATION -> AnimPropertyType.ROTATION
    }
    SideEffect { onPropertyFocus(if (isAnchorMode) null else currentProp) }
    val isAnim = when (mode) {
        TransformSubMode.POSITION -> layer.animTransform.position.isAnimated
        TransformSubMode.SCALE -> layer.animTransform.scale.isAnimated
        TransformSubMode.ROTATION -> layer.animTransform.rotation.isAnimated
    }
    val hasKf = when (mode) {
        TransformSubMode.POSITION -> layer.animTransform.position.keyframes.any { it.timeUs == localTimeUs }
        TransformSubMode.SCALE -> layer.animTransform.scale.keyframes.any { it.timeUs == localTimeUs }
        TransformSubMode.ROTATION -> layer.animTransform.rotation.keyframes.any { it.timeUs == localTimeUs }
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        InspectorUtilityRail(
            resetLabel = if (isAnchorMode) "Reset anchor point" else "Reset ${mode.name.lowercase()}",
            onReset = {
                if (isAnchorMode) {
                    anchorAction(.5f, .5f, false)?.let(onAction)
                } else if (isAnim) {
                    onAction(EditorAction.ResetPropertyAnimation(layer.id, currentProp))
                } else {
                    onEdit(when (mode) {
                        TransformSubMode.POSITION -> transform.copy(positionX = 0f, positionY = 0f)
                        TransformSubMode.ROTATION -> transform.copy(rotationDegrees = 0f)
                        TransformSubMode.SCALE -> transform.copy(scaleX = 1f, scaleY = 1f)
                    })
                }
            },
            onResetAll = {
                onAction(EditorAction.Batch(listOf(AnimPropertyType.POSITION, AnimPropertyType.SCALE,
                    AnimPropertyType.ROTATION).map { EditorAction.ResetPropertyAnimation(layer.id, it) } +
                    EditorAction.SetAnchorPoint(layer.id, .5f, .5f)))
            },
            isAnimated = isAnim && !isAnchorMode,
            hasKeyframeAtPlayhead = hasKf && !isAnchorMode,
            onToggleKeyframe = if (isAnchorMode) null else {
              {
                if (!isAnim) {
                    onAction(EditorAction.ToggleAnimated(layer.id, currentProp, true, playheadUs))
                } else if (hasKf) {
                    onAction(EditorAction.RemoveKeyframe(layer.id, currentProp, localTimeUs))
                } else {
                    when (currentProp) {
                        AnimPropertyType.POSITION -> onAction(EditorAction.SetKeyframe2D(layer.id, currentProp, localTimeUs, transform.positionX, transform.positionY))
                        AnimPropertyType.SCALE -> onAction(EditorAction.SetKeyframe2D(layer.id, currentProp, localTimeUs, transform.scaleX, transform.scaleY))
                        AnimPropertyType.ROTATION -> onAction(EditorAction.SetKeyframe(layer.id, currentProp, localTimeUs, transform.rotationDegrees))
                        else -> Unit
                    }
                }
              }
            }
        )
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (mode) {
                TransformSubMode.POSITION -> {
                  if (isAnchorMode) {
                    AnchorPointControls(layer.anchorX, layer.anchorY, layer.animTransform.position.isAnimated,
                        source != null || layer.animTransform.position.isAnimated,
                        onEdit = { x, y -> anchorAction(x, y, false)?.let(onAction) },
                        onBegin = { anchorGestureLayer = layer; anchorGestureTransform = transform; onBeginGesture() },
                        onPreview = { x, y -> anchorAction(x, y, true)?.let(onAnchorPreview) },
                        onCommit = onCommitGesture, onCancel = onCancelGesture)
                  } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        CompactValueBox("X", number(pixelX), onCommitValue = {
                            val newPosX = (it - compositionWidth / 2f) / halfReferenceW
                            onEdit(transform.copy(positionX = newPosX))
                        }, modifier = Modifier.weight(1f))
                        CompactValueBox("Y", number(pixelY), onCommitValue = {
                            val newPosY = (it - compositionHeight / 2f) / halfReferenceH
                            onEdit(transform.copy(positionY = newPosY))
                        }, modifier = Modifier.weight(1f))
                    }
                    // Planned: Z position requires a 3D layer model.
                    FittedSquare(Modifier.weight(1f).fillMaxWidth(), 200.dp) { PositionTrackpad(pixelX / compositionWidth, pixelY / compositionHeight, begin, { x, y ->
                        val newPosX = (x * compositionWidth - compositionWidth / 2f) / halfReferenceW
                        val newPosY = (y * compositionHeight - compositionHeight / 2f) / halfReferenceH
                        onPreviewGesture(gestureBase.copy(positionX = newPosX, positionY = newPosY))
                    }, onCommitGesture, onCancelGesture) }
                  }
                }
                TransformSubMode.ROTATION -> {
                    // Planned: XYZ rotation requires 3D support. Keep the existing single-angle model.
                    FittedSquare(Modifier.weight(1f).fillMaxWidth(), 184.dp) { RotationDialController(transform.rotationDegrees, begin,
                        {
                            onPreviewGesture(gestureBase.copy(rotationDegrees = it))
                        }, onCommitGesture, onCancelGesture,
                        {
                            onEdit(transform.copy(rotationDegrees = it))
                        }) }
                }
                TransformSubMode.SCALE -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        CompactValueBox("W", number(transform.scaleX * 100f), "%", {
                            val value = (it / 100f).coerceIn(.05f, 10f)
                            val newScaleX = value
                            val newScaleY = if (linked) value else transform.scaleY
                            onEdit(transform.copy(scaleX = newScaleX, scaleY = newScaleY))
                        }, Modifier.weight(1f))
                        IconToggleButton(checked = linked, onCheckedChange = { focusManager.clearFocus(); linked = it },
                            modifier = Modifier.size(32.dp).semantics { contentDescription = "Link width and height scale" }) {
                            LinkVectorIcon(linked, if (linked) Highlight else TextSecondary)
                        }
                        CompactValueBox("H", number(transform.scaleY * 100f), "%", {
                            val value = (it / 100f).coerceIn(.05f, 10f)
                            val newScaleX = if (linked) value else transform.scaleX
                            val newScaleY = value
                            onEdit(transform.copy(scaleX = newScaleX, scaleY = newScaleY))
                        }, Modifier.weight(1f))
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { ScaleController(transform.scaleX, transform.scaleY, linked, begin,
                        { x, y ->
                            onPreviewGesture(gestureBase.copy(scaleX = x, scaleY = y))
                        }, onCommitGesture, onCancelGesture) }
                }
            }
        }
        Column(Modifier.width(48.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TransformSubMode.entries.forEach { item ->
                val active = mode == item
                IconToggleButton(checked = active, onCheckedChange = {
                    focusManager.clearFocus()
                    isAnchorMode = item == TransformSubMode.POSITION && active && !isAnchorMode
                    mode = item
                }, modifier = Modifier.semantics { contentDescription = if (item == TransformSubMode.POSITION)
                    (if (isAnchorMode) "Anchor point; tap for Position" else "Position; tap again for Anchor point")
                    else item.name.lowercase().replaceFirstChar { it.uppercase() } }) {
                    val color = if (active) Highlight else TextSecondary
                    when (item) {
                        TransformSubMode.POSITION -> if (isAnchorMode) AnchorSubModeIcon(color) else PositionSubModeIcon(color, active = active)
                        TransformSubMode.ROTATION -> RotationSubModeIcon(color, active = active)
                        TransformSubMode.SCALE -> ScaleSubModeIcon(color, active = active)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun CompactOpacitySectionContent(
    layer: CompositionLayer,
    playheadUs: Long,
    onAction: (EditorAction) -> Unit,
    onBeginGesture: () -> Unit,
    onPreviewGesture: (Float) -> Unit,
    onCommitGesture: () -> Unit,
    onCancelGesture: () -> Unit,
    onEdit: (Float) -> Unit
) {
    val localTimeUs = Math.subtractExact(playheadUs, layer.resolvedKeyframeAnchorUs)
    val isAnim = layer.animTransform.opacity.isAnimated
    val hasKf = layer.animTransform.opacity.keyframes.any { it.timeUs == localTimeUs }
    val currentTransform = remember(layer, playheadUs) { layer.evaluatedTransform(playheadUs) }
    val opacity = currentTransform.opacity
    var dragging by remember { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val focusManager = LocalFocusManager.current
    val cancel by rememberUpdatedState(onCancelGesture)
    DisposableEffect(Unit) { onDispose { if (dragging) cancel() } }
    LaunchedEffect(interactions) {
        interactions.interactions.collect { interaction ->
            if (interaction is DragInteraction.Cancel && dragging) {
                dragging = false
                cancel()
            }
        }
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InspectorUtilityRail(
            resetLabel = "Reset opacity",
            onReset = {
                if (isAnim) onAction(EditorAction.ResetPropertyAnimation(layer.id, AnimPropertyType.OPACITY))
                else onEdit(1f)
            },
            isAnimated = isAnim,
            hasKeyframeAtPlayhead = hasKf,
            onToggleKeyframe = {
                if (!isAnim) {
                    onAction(EditorAction.ToggleAnimated(layer.id, AnimPropertyType.OPACITY, true, playheadUs))
                } else if (hasKf) {
                    onAction(EditorAction.RemoveKeyframe(layer.id, AnimPropertyType.OPACITY, localTimeUs))
                } else {
                    onAction(EditorAction.SetKeyframe(layer.id, AnimPropertyType.OPACITY, localTimeUs, opacity))
                }
            }
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().background(SurfaceHigh,RoundedCornerShape(6.dp))
                .border(.5.dp,Border,RoundedCornerShape(6.dp)).padding(horizontal=8.dp,vertical=6.dp)
                .semantics { contentDescription="Blend Mode: Normal, not yet available"; disabled() },
                horizontalArrangement=Arrangement.SpaceBetween) {
                Text("Blend Mode",color=TextDisabled,fontSize=11.sp)
                Text("Normal",color=TextDisabled,fontSize=11.sp)
            }
            CompactValueBox("Opacity", number(opacity * 100f), "%", {
                val newOp = (it / 100f).coerceIn(0f, 1f)
                onEdit(newOp)
            })
            Slider(value = opacity, valueRange = 0f..1f,
                onValueChange = {
                    if (!dragging) { focusManager.clearFocus(); onBeginGesture(); dragging = true }
                    onPreviewGesture(it)
                },
                onValueChangeFinished = { if (dragging) { dragging = false; onCommitGesture() } },
                interactionSource = interactions,
                colors = SliderDefaults.colors(thumbColor = Highlight, activeTrackColor = Highlight, inactiveTrackColor = Border),
                modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun CompactTimingSectionContent(
    timing: ClipTiming,
    isLayerVideo: Boolean,
    onTimingChange: (ClipTiming) -> Unit
) {
    val startSec = timing.startUs / 1_000_000f
    val durationSec = (timing.durationUs ?: 5_000_000L) / 1_000_000f
    val sourceInSec = timing.sourceInUs / 1_000_000f

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        CompactSliderItem(
            modifier = Modifier.fillMaxWidth(),
            label = "Start",
            value = startSec,
            valueRange = 0f..60f,
            displayValue = String.format("%.2fs", startSec),
            onBegin = {},
            onValueChange = {
                onTimingChange(timing.copy(startUs = (it * 1_000_000L).toLong().coerceAtLeast(0L)))
            },
            onCommit = {},
            onDirectEdit = {
                onTimingChange(timing.copy(startUs = (it * 1_000_000L).toLong().coerceAtLeast(0L)))
            },
            dialogTitle = "Set Start Time (seconds)"
        )

        CompactSliderItem(
            modifier = Modifier.fillMaxWidth(),
            label = "Duration",
            value = durationSec,
            valueRange = 0.5f..60f,
            displayValue = String.format("%.2fs", durationSec),
            onBegin = {},
            onValueChange = {
                onTimingChange(timing.copy(durationUs = (it * 1_000_000L).toLong().coerceAtLeast(100_000L)))
            },
            onCommit = {},
            onDirectEdit = {
                onTimingChange(timing.copy(durationUs = (it * 1_000_000L).toLong().coerceAtLeast(100_000L)))
            },
            dialogTitle = "Set Duration (seconds)"
        )

        if (isLayerVideo) {
            CompactSliderItem(
                modifier = Modifier.fillMaxWidth(),
                label = "Source In",
                value = sourceInSec,
                valueRange = 0f..60f,
                displayValue = String.format("%.2fs", sourceInSec),
                onBegin = {},
                onValueChange = {
                    onTimingChange(timing.copy(sourceInUs = (it * 1_000_000L).toLong().coerceAtLeast(0L)))
                },
                onCommit = {},
                onDirectEdit = {
                    onTimingChange(timing.copy(sourceInUs = (it * 1_000_000L).toLong().coerceAtLeast(0L)))
                },
                dialogTitle = "Set Source In (seconds)"
            )
        }
    }
}

/**
 * Compact Audio Sub-panel:
 * Mute toggle and Volume gain slider.
 */
@Composable
private fun CompactAudioSectionContent(
    muted: Boolean,
    gain: Float,
    onAudioChange: (Boolean, Float) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.semantics { disabled() }) {
            Text("Extract Audio", color = TextDisabled, style = MaterialTheme.typography.labelLarge)
            Text("Audio extraction is not available yet", color = TextDisabled,
                style = MaterialTheme.typography.bodySmall)
        }
        // Mute Toggle Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(SurfaceHigh)
                .border(0.5.dp, Border, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Mute audio track", color = TextPrimary, fontSize = 13.sp)
            Switch(
                checked = muted,
                onCheckedChange = { onAudioChange(it, gain) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Highlight,
                    uncheckedThumbColor = TextDisabled,
                    uncheckedTrackColor = Surface
                ),
                modifier = Modifier.scale(0.8f)
            )
        }

        // Volume Gain Slider Row
        CompactSliderItem(
            modifier = Modifier.fillMaxWidth(),
            label = "Gain",
            value = gain,
            valueRange = 0f..2f,
            displayValue = String.format("%.0f%%", gain * 100f),
            onBegin = {},
            onValueChange = { onAudioChange(muted, it) },
            onCommit = {},
            onDirectEdit = {
                val clamped = (it / 100f).coerceIn(0f, 2f)
                onAudioChange(muted, clamped)
            },
            dialogTitle = "Set Volume Gain (%)"
        )
    }
}

/**
 * Reusable compact slider row with label, thin slider, and tappable numeric box.
 */
@Composable
private fun CompactSliderItem(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    displayValue: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onBegin: () -> Unit,
    onValueChange: (Float) -> Unit,
    onCommit: () -> Unit,
    onDirectEdit: (Float) -> Unit,
    dialogTitle: String = "Set $label",
    dialogSuffix: String = "",
    rowHeight: Dp = 34.dp
) {
    var showDialog by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.height(rowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            color = if (enabled) TextSecondary else TextDisabled,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.widthIn(min = 14.dp)
        )

        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = {
                if (enabled) {
                    if (!isDragging) {
                        isDragging = true
                        onBegin()
                    }
                    onValueChange(it)
                }
            },
            onValueChangeFinished = {
                if (enabled) {
                    isDragging = false
                    onCommit()
                }
            },
            valueRange = valueRange,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = if (enabled) Highlight else TextDisabled,
                activeTrackColor = if (enabled) Highlight else TextDisabled,
                inactiveTrackColor = SurfaceHigh
            ),
            modifier = Modifier.weight(1f)
        )

        Box(
            modifier = Modifier
                .heightIn(min = rowHeight)
                .widthIn(min = if (rowHeight >= 48.dp) 48.dp else 0.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(SurfaceHigh)
                .border(0.5.dp, Border, RoundedCornerShape(4.dp))
                .clickable(enabled = enabled) { showDialog = true }
                .padding(horizontal = 6.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = displayValue,
                color = if (enabled) TextPrimary else TextDisabled,
                fontSize = 11.sp,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium
            )
        }
    }

    if (showDialog) {
        val cleanInitial = displayValue.trimEnd('°', 'Â', '%', 'x', 's')
        NumericInputDialog(
            title = dialogTitle,
            initialValue = cleanInitial,
            suffix = dialogSuffix,
            onConfirm = { onDirectEdit(it) },
            onDismiss = { showDialog = false }
        )
    }
}

/**
 * Lightweight numeric entry dialog for exact values.
 */
@Composable
private fun NumericInputDialog(
    title: String,
    initialValue: String,
    suffix: String = "",
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceHigh,
        title = {
            Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                suffix = if (suffix.isNotEmpty()) { { Text(suffix, color = TextSecondary) } } else null,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = Highlight,
                    unfocusedBorderColor = Border
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsed = text.toFloatOrNull()
                    if (parsed != null) {
                        onConfirm(parsed)
                    }
                    onDismiss()
                }
            ) {
                Text("OK", color = Highlight)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

// Vector Icons drawn cleanly with Canvas (no emoji)

@Composable
private fun BackChevronVectorIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val stroke = 2.dp.toPx()
        val path = Path().apply {
            moveTo(size.width * 0.65f, size.height * 0.15f)
            lineTo(size.width * 0.3f, size.height * 0.5f)
            lineTo(size.width * 0.65f, size.height * 0.85f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
private fun CloseVectorIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(14.dp)) {
        val stroke = 1.6.dp.toPx()
        drawLine(color, Offset(0f, 0f), Offset(size.width, size.height), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), stroke, StrokeCap.Round)
    }
}

@Composable
private fun LinkVectorIcon(linked: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(16.dp)) {
        val stroke = 1.5.dp.toPx()
        val w = size.width
        val h = size.height
        // Left loop
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.1f, h * 0.25f),
            size = Size(w * 0.45f, h * 0.5f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.2f),
            style = Stroke(width = stroke)
        )
        // Right loop
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.45f, h * 0.25f),
            size = Size(w * 0.45f, h * 0.5f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.2f),
            style = Stroke(width = stroke)
        )
        if (linked) {
            // Connecting bar in center
            drawLine(
                color = color,
                start = Offset(w * 0.35f, h * 0.5f),
                end = Offset(w * 0.65f, h * 0.5f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun TimingVectorIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(22.dp)) {
        val stroke = 1.6.dp.toPx()
        val r = size.minDimension * 0.42f
        val center = Offset(size.width / 2f, size.height / 2f)
        // Clock circle
        drawCircle(color = color, radius = r, center = center, style = Stroke(width = stroke))
        // Clock hands
        drawLine(
            color = color,
            start = center,
            end = Offset(center.x, center.y - r * 0.6f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = center,
            end = Offset(center.x + r * 0.45f, center.y),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun DuplicateVectorIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = 1.6.dp.toPx()
        // Back card
        drawRoundRect(
            color = color.copy(alpha = 0.6f),
            topLeft = Offset(size.width * 0.28f, size.height * 0.08f),
            size = Size(size.width * 0.62f, size.height * 0.62f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
            style = Stroke(width = stroke)
        )
        // Front card
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.08f, size.height * 0.28f),
            size = Size(size.width * 0.62f, size.height * 0.62f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
            style = Stroke(width = stroke)
        )
        // Plus inside front card
        val cx = size.width * 0.39f
        val cy = size.height * 0.59f
        val arm = size.width * 0.12f
        drawLine(color, Offset(cx - arm, cy), Offset(cx + arm, cy), stroke, StrokeCap.Round)
        drawLine(color, Offset(cx, cy - arm), Offset(cx, cy + arm), stroke, StrokeCap.Round)
    }
}

@Composable
private fun DeleteVectorIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.6.dp.toPx()
        // Lid
        drawLine(color, Offset(w * 0.18f, h * 0.25f), Offset(w * 0.82f, h * 0.25f), stroke, StrokeCap.Round)
        // Handle
        val handle = Path().apply {
            moveTo(w * 0.38f, h * 0.25f)
            lineTo(w * 0.38f, h * 0.14f)
            lineTo(w * 0.62f, h * 0.14f)
            lineTo(w * 0.62f, h * 0.25f)
        }
        drawPath(handle, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // Can body
        val can = Path().apply {
            moveTo(w * 0.26f, h * 0.25f)
            lineTo(w * 0.30f, h * 0.86f)
            lineTo(w * 0.70f, h * 0.86f)
            lineTo(w * 0.74f, h * 0.25f)
        }
        drawPath(can, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // Inner vertical rib lines
        drawLine(color, Offset(w * 0.43f, h * 0.38f), Offset(w * 0.43f, h * 0.74f), stroke * 0.8f, StrokeCap.Round)
        drawLine(color, Offset(w * 0.57f, h * 0.38f), Offset(w * 0.57f, h * 0.74f), stroke * 0.8f, StrokeCap.Round)
    }
}

@Composable
private fun TransformVectorIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.6.dp.toPx()
        // Center cross
        drawLine(color, Offset(w * 0.2f, h * 0.5f), Offset(w * 0.8f, h * 0.5f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.5f, h * 0.2f), Offset(w * 0.5f, h * 0.8f), stroke, StrokeCap.Round)
        // Arrowheads
        // Left
        val left = Path().apply { moveTo(w * 0.32f, h * 0.36f); lineTo(w * 0.18f, h * 0.5f); lineTo(w * 0.32f, h * 0.64f) }
        drawPath(left, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // Right
        val right = Path().apply { moveTo(w * 0.68f, h * 0.36f); lineTo(w * 0.82f, h * 0.5f); lineTo(w * 0.68f, h * 0.64f) }
        drawPath(right, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // Top
        val top = Path().apply { moveTo(w * 0.36f, h * 0.32f); lineTo(w * 0.5f, h * 0.18f); lineTo(w * 0.64f, h * 0.32f) }
        drawPath(top, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // Bottom
        val bottom = Path().apply { moveTo(w * 0.36f, h * 0.68f); lineTo(w * 0.5f, h * 0.82f); lineTo(w * 0.64f, h * 0.68f) }
        drawPath(bottom, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun AudioVectorIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.6.dp.toPx()
        // Speaker horn
        val horn = Path().apply {
            moveTo(w * 0.18f, h * 0.38f)
            lineTo(w * 0.35f, h * 0.38f)
            lineTo(w * 0.55f, h * 0.20f)
            lineTo(w * 0.55f, h * 0.80f)
            lineTo(w * 0.35f, h * 0.62f)
            lineTo(w * 0.18f, h * 0.62f)
            close()
        }
        drawPath(horn, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // Sound wave
        val wave = Path().apply {
            moveTo(w * 0.70f, h * 0.35f)
            quadraticTo(w * 0.82f, h * 0.50f, w * 0.70f, h * 0.65f)
        }
        drawPath(wave, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun ColorPaletteVectorIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.3.dp.toPx()
        // Palette circle outline
        drawCircle(color, radius = w * 0.40f, style = Stroke(width = stroke))
        // Droplets inside
        drawCircle(color, radius = w * 0.07f, center = Offset(w * 0.38f, h * 0.38f))
        drawCircle(color, radius = w * 0.07f, center = Offset(w * 0.62f, h * 0.38f))
        drawCircle(color, radius = w * 0.07f, center = Offset(w * 0.50f, h * 0.65f))
    }
}

@Composable
private fun OpacityVectorIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.3.dp.toPx()
        val r = size.minDimension * 0.28f
        // Left circle (outline)
        drawCircle(
            color = color,
            radius = r,
            center = Offset(w * 0.40f, h * 0.50f),
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
        // Right circle (semi-transparent fill + outline)
        drawCircle(
            color = color.copy(alpha = 0.35f),
            radius = r,
            center = Offset(w * 0.60f, h * 0.50f)
        )
        drawCircle(
            color = color,
            radius = r,
            center = Offset(w * 0.60f, h * 0.50f),
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun KeyframeVectorIcon(color: Color, filled: Boolean = false, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.12f)
            lineTo(w * 0.88f, h * 0.5f)
            lineTo(w * 0.5f, h * 0.88f)
            lineTo(w * 0.12f, h * 0.5f)
            close()
        }
        if (filled) {
            drawPath(path, color)
        } else {
            val stroke = 1.3.dp.toPx()
            drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun GraphVectorIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.3.dp.toPx()
        // Axes
        drawLine(color, Offset(w * 0.18f, h * 0.15f), Offset(w * 0.18f, h * 0.85f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.18f, h * 0.85f), Offset(w * 0.85f, h * 0.85f), stroke, StrokeCap.Round)
        // Bezier curve
        val curve = Path().apply {
            moveTo(w * 0.22f, h * 0.80f)
            cubicTo(w * 0.50f, h * 0.80f, w * 0.50f, h * 0.25f, w * 0.82f, h * 0.25f)
        }
        drawPath(curve, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun OverflowDotsVectorIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val r = 1.4.dp.toPx()
        drawCircle(color, r, Offset(w * 0.25f, h * 0.5f))
        drawCircle(color, r, Offset(w * 0.50f, h * 0.5f))
        drawCircle(color, r, Offset(w * 0.75f, h * 0.5f))
    }
}

@Composable
private fun PositionSubModeIcon(color: Color, modifier: Modifier = Modifier, active: Boolean = false) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.3.dp.toPx()
        drawLine(color, Offset(w * 0.22f, h * 0.5f), Offset(w * 0.78f, h * 0.5f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.5f, h * 0.22f), Offset(w * 0.5f, h * 0.78f), stroke, StrokeCap.Round)
        // Arrowheads
        val left = Path().apply { moveTo(w * 0.35f, h * 0.36f); lineTo(w * 0.20f, h * 0.5f); lineTo(w * 0.35f, h * 0.64f) }
        drawPath(left, color, style = if (active) Fill else Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val right = Path().apply { moveTo(w * 0.65f, h * 0.36f); lineTo(w * 0.80f, h * 0.5f); lineTo(w * 0.65f, h * 0.64f) }
        drawPath(right, color, style = if (active) Fill else Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val top = Path().apply { moveTo(w * 0.36f, h * 0.35f); lineTo(w * 0.5f, h * 0.20f); lineTo(w * 0.64f, h * 0.35f) }
        drawPath(top, color, style = if (active) Fill else Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val bottom = Path().apply { moveTo(w * 0.36f, h * 0.65f); lineTo(w * 0.5f, h * 0.80f); lineTo(w * 0.64f, h * 0.65f) }
        drawPath(bottom, color, style = if (active) Fill else Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun RotationSubModeIcon(color: Color, modifier: Modifier = Modifier, active: Boolean = false) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.3.dp.toPx()
        val r = size.minDimension * 0.36f
        val center = Offset(w * 0.5f, h * 0.5f)
        drawArc(
            color = color,
            startAngle = 45f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(center.x - r, center.y - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
        val arrow = Path().apply {
            moveTo(w * 0.76f, h * 0.20f)
            lineTo(w * 0.82f, h * 0.36f)
            lineTo(w * 0.64f, h * 0.38f)
        }
        drawPath(arrow, color, style = if (active) Fill else Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun ScaleSubModeIcon(color: Color, modifier: Modifier = Modifier, active: Boolean = false) {
    Canvas(modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.3.dp.toPx()
        drawLine(color, Offset(w * .23f, h * .77f), Offset(w * .77f, h * .23f), stroke, StrokeCap.Round)
        val upper = Path().apply {
            moveTo(w * .49f, h * .2f); lineTo(w * .8f, h * .2f); lineTo(w * .8f, h * .51f)
        }
        val lower = Path().apply {
            moveTo(w * .2f, h * .49f); lineTo(w * .2f, h * .8f); lineTo(w * .51f, h * .8f)
        }
        drawPath(upper, color, style = if (active) Fill else Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(lower, color, style = if (active) Fill else Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
