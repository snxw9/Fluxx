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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.testTag
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
    CONTEXTUAL_EDIT, EFFECTS, LAYER_STYLES, TRACK_MATTES, MOTION_BLUR,
    /** GRAPH G1: the Graph Editor page (Ease view), opened from the rail's Graph icon of the page it returns to. */
    GRAPH
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
    /** Animate tab (TEXT F3): the focused animator field, for timeline focus and Prev/Next. */
    onAnimatorFocus: (AnimatorTrackAddress?) -> Unit = {},
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
    onTextDraftSelection: (String,Int,Int)->Unit = { value,_,_->onTextDraft(value) },
    onTextFinish: () -> Unit = {}, onTextCancel: () -> Unit = {},
    onTextStyle: (TextProperties) -> Unit = {},
    onTextCharacter: (Int,Int,(TextRunStyle)->TextRunStyle)->Unit = { _,_,_-> },
    onTextParagraph: (Int,Int,(TextParagraphStyle)->TextParagraphStyle)->Unit = { _,_,_-> },
    onTextBoxWidth: (Float)->Unit = {},
    onTextSizeBegin: () -> Unit = {}, onTextSizePreview: (Float) -> Unit = {}, onTextSizeCommit: () -> Unit = {},
    onTextScalarPreview: (AnimPropertyType, Float) -> Unit = { _, _ -> },
    onTextPayloadPreview: (TextProperties)->Unit = {},
    textFrameRate: FrameRate=FrameRate(),
    /** GRAPH G2.1 §6a: the composition start timecode, so Graph times read like the transport. */
    compositionStartTimecodeUs: Long = 0L,
    onTextBeginDraft: (Boolean,(CompositionLayer)->Unit)->Unit={_,ready->ready(layer)},
    textFinishing: Boolean=false,
    textStrokeClamp: String?=null,
    onTextSampleColour: (((Int?)->Unit)->Unit)?=null,
    onTextCancelSample: ()->Unit={},
    onTextSourceSelection: (String,TextSelectionRange)->Unit={_,_->},
    onTextColourChange: (AnimPropertyType, Int) -> Unit = { _, _ -> },
    onTextColourPreview: (AnimPropertyType, Int) -> Unit = { _, _ -> },
    textSelection: TextSelectionRange?=null,onTextSelection: (TextSelectionRange?)->Unit = {},
    onExplodePrepare: (TextExplodeOptions,(Result<TextExplodePlan>)->Unit)->Unit = {_,reply->reply(Result.failure(IllegalStateException("Explode preparation unavailable")))},
    onExplodeCommit: (TextExplodePlan,(String?)->Unit)->Unit = {_,reply->reply("Explode unavailable")},
    onTextEditVisible: (Boolean)->Unit = {},
    /** GRAPH G1: live preview of a Graph page drag (SetKeyEasing) inside the begin/commit/cancel gesture. */
    onGesturePreview: (EditorAction) -> Unit = {}
) {
    key(layer.id) {
        var section by rememberSaveable { mutableStateOf<InspectorSection?>(null) }
        // GRAPH G1: the Graph page's property and the page it returns to. The return page's saveable state (tab, focused
        // field, transform mode) is kept by [pages] while the Graph page is open and dropped on any other navigation.
        var graphTarget by remember { mutableStateOf<GraphTarget?>(null) }
        var graphReturn by rememberSaveable { mutableStateOf<InspectorSection?>(null) }
        val pages = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        val currentLayer by rememberUpdatedState(layer)
        val graphContext = androidx.compose.ui.platform.LocalContext.current
        val graphHost = remember {
            var host: GraphHost? = null
            GraphHost({ currentLayer }, { graphTarget },
                open = { target ->
                    // GRAPH G3: open on the last-used tab (the page falls back to Ease where that tab is unavailable).
                    if (section != InspectorSection.GRAPH || graphTarget != target) host?.session?.reset(GraphTabs.stored(graphContext))
                    if (section != InspectorSection.GRAPH) graphReturn = section; graphTarget = target; section = InspectorSection.GRAPH },
                close = { host?.session?.reset(); section = graphReturn; graphTarget = null }).also { host = it }
        }
        // A restored GRAPH page without its target (process death) returns to the page it came from.
        if (section == InspectorSection.GRAPH && graphTarget == null) LaunchedEffect(Unit) { section = graphReturn }
        val textEditVisible by rememberUpdatedState(onTextEditVisible)
        // Edit Text uses one fixed sheet height; the editor needs to know when it is showing (the Graph page opened from
        // it keeps the editor's top-bar Back routing).
        LaunchedEffect(section) { textEditVisible(layer.type == LayerType.TEXT && (section == InspectorSection.CONTEXTUAL_EDIT ||
            (section == InspectorSection.GRAPH && graphReturn == InspectorSection.CONTEXTUAL_EDIT))) }
        DisposableEffect(Unit) { onDispose { textEditVisible(false) } }
        var textColourProperty by remember { mutableStateOf(AnimPropertyType.FILL_COLOUR) }
        val focusManager = LocalFocusManager.current
        val focusCallback by rememberUpdatedState(onPropertyFocus)
        val animatorFocusCallback by rememberUpdatedState(onAnimatorFocus)
        LaunchedEffect(section, graphTarget) {
            // The Graph page keeps the timeline focus and Prev/Next on the property it edits.
            if (section == InspectorSection.GRAPH) { graphTarget?.let { focusCallback(it.property); animatorFocusCallback(it.animator) } }
            else if (section != InspectorSection.TRANSFORM) focusCallback(if (section == InspectorSection.BLENDING_OPACITY) AnimPropertyType.OPACITY else null)
            // Leaving a page other than for the Graph page forgets its kept state (unchanged navigation behaviour).
            if (section != InspectorSection.GRAPH) InspectorSection.entries.filter { it != section }.forEach { pages.removeState(it.name) }
        }
        DisposableEffect(layer.id) { onDispose { focusCallback(null) } }
        val dismiss by rememberUpdatedState(onDismiss)
        var swipeOffset by remember { mutableFloatStateOf(0f) }
        val sections = listOf(InspectorSection.TRANSFORM, InspectorSection.TIMING, InspectorSection.BLENDING_OPACITY) +
            (if (layer.type == LayerType.SOLID || layer.type == LayerType.TEXT) listOf(InspectorSection.CONTEXTUAL_EDIT) else emptyList()) +
            // The audio toggle lives in the landing name row; on section pages Audio is one more tab for video layers.
            (if (layer.type == LayerType.VIDEO) listOf(InspectorSection.AUDIO) else emptyList())
        // In-place value fields on a page (e.g. the FULL colour picker's hex) lift the sheet above the keyboard.
        val imeLift = remember { com.fluxx.android.ui.common.ImeLiftHost() }
        Surface(color = Surface, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            modifier = modifier.fillMaxWidth().offset { IntOffset(0, (swipeOffset - imeLift.lift).toInt()) }) {
            // TEXT F3.3: swipe down from the top of the sheet closes it on EVERY page and sub-page (InspectorSwipe).
            BoxWithConstraints(Modifier.fillMaxSize().inspectorSwipeDown(InspectorSwipe.enabled(section, textTyping),
                InspectorSwipe.band(LocalDensity.current.fontScale).dp,
                onStart = { focusManager.clearFocus(); swipeOffset = 0f },
                onDrag = { swipeOffset = (swipeOffset + it).coerceAtLeast(0f) },
                onEnd = { threshold -> if (swipeOffset >= threshold) dismiss() else swipeOffset = 0f },
                onCancel = { swipeOffset = 0f })) {
                val compactHeight = maxHeight < 320.dp
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (compactHeight) 40.dp else 48.dp,
                    com.fluxx.android.ui.common.LocalImeLift provides imeLift) {
                    val textSection = layer.type == LayerType.TEXT && section == InspectorSection.CONTEXTUAL_EDIT
                    // Edit Text is an ordinary inspector page: same frame, padding and height as Transform. It draws its
                    // own single header row (back, Character / Paragraph / Animate, menu) in place of the parent chrome.
                    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        // Section pages: ONE header row (← | page tabs), exactly like Edit Text.
                        // GRAPH G1.1: the Graph page's header sits in the same full-width header row as every section page.
                        if (!textTyping && section == InspectorSection.GRAPH) graphTarget?.let { GraphPageHeader(layer, it, playheadUs, textFrameRate, onBack = graphHost.close, session = graphHost.session,
                            compositionWidth = compositionWidth, compositionHeight = compositionHeight) }
                        if (!textTyping && section != null && !textSection && section != InspectorSection.GRAPH) SectionHeader(sections, section!!, layer.type,
                            onBack = { focusManager.clearFocus(); section = null },
                            onSelect = { focusManager.clearFocus(); section = it })
                        // The landing grid keeps its arrangement: handle, layer name with duplicate / delete, quick actions.
                        if (!textTyping && section == null) {
                        Box(Modifier.fillMaxWidth().height(20.dp).semantics { contentDescription = "Swipe down to dismiss inspector" },
                            contentAlignment = Alignment.Center) {
                            Box(Modifier.size(36.dp, 4.dp).background(Border, CircleShape))
                        }
                        Row(Modifier.fillMaxWidth().height(InspectorChromeBudget.nameRow(compactHeight).dp), verticalAlignment = Alignment.CenterVertically) {
                            val hit = InspectorChromeBudget.nameRow(compactHeight).dp
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
                                    .size(hit)
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
                                Icon(InspectorIcons.icon("DUPLICATE"), null, Modifier.size(InspectorIcons.SIZE), tint = TextPrimary)
                            }
                            Box(Modifier.size(hit).clickable(role = Role.Button, onClick = onDelete).semantics { contentDescription = "Delete layer" },
                                contentAlignment = Alignment.Center) {
                                Icon(InspectorIcons.icon("DELETE_LAYER"), null, Modifier.size(InspectorIcons.SIZE), tint = TextPrimary)
                            }
                            if (layer.type == LayerType.VIDEO) {
                                IconToggleButton(checked = section == InspectorSection.AUDIO,
                                    onCheckedChange = { focusManager.clearFocus(); section = if (it) InspectorSection.AUDIO else InspectorSection.TRANSFORM },
                                    modifier = Modifier.size(hit).semantics { contentDescription = "Audio controls" }) {
                                    Icon(InspectorIcons.icon("AUDIO"), null, Modifier.size(InspectorIcons.SIZE), tint = if (section == InspectorSection.AUDIO) Highlight else TextSecondary)
                                }
                            }
                        }
                        ClipQuickActionBar(layer, playheadUs, compositionDurationUs) { action ->
                            focusManager.clearFocus()
                            onAction(action)
                        }
                        }
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = if(textSection) 0.dp else InspectorChromeBudget.CONTENT_TOP.dp)) {
                          CompositionLocalProvider(LocalGraphHost provides graphHost) {
                          pages.SaveableStateProvider(section?.name ?: "landing") {
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
                                InspectorSection.CONTEXTUAL_EDIT -> if (layer.type == LayerType.TEXT) TextInspectorPanel(
                                    layer,playheadUs,textFrameRate,onAction,onPropertyFocus,onTextBegin,onTextDraftSelection,onTextFinish,onTextCancel,
                                    onTextSizeBegin,onTextPayloadPreview,onTextCharacter,onTextParagraph,onTextBoxWidth,compositionWidth,textSelection,onTextSelection,textError,
                                    {section=null},onExplodePrepare,onExplodeCommit,paletteRepository,onEyedropperRequest,onTextBeginDraft,textFinishing,textStrokeClamp,onTextSampleColour,onTextCancelSample,onTextSourceSelection,
                                    compositionHeight,onAnimatorFocus)
                                // Edit Solid: the FULL variant of the shared colour picker (one undo per interaction).
                                else FluxxColourPicker(ColourPickerVariant.FULL, layer.solidColorArgb,
                                    onBegin = onTransformBegin, onPreview = { onColorPreview(it) }, onFinish = onTransformCommit,
                                    onCancel = onTransformCancel, modifier = Modifier.fillMaxSize(), name = "solid colour",
                                    palette = paletteRepository, onEyedropper = onEyedropperRequest)
                                InspectorSection.GRAPH -> graphTarget?.let { target ->
                                    GraphPage(layer, target, playheadUs, textFrameRate, onAction, onTransformBegin, onGesturePreview,
                                        onTransformCommit, onTransformCancel, onClose = graphHost.close, session = graphHost.session,
                                        compositionWidth = compositionWidth, compositionHeight = compositionHeight, startTimecodeUs = compositionStartTimecodeUs)
                                }
                                else -> Unit // Disabled categories cannot be opened.
                            }
                          }
                          }
                        }
                    }
                }
            }
        }
    }
}

internal fun sectionTitle(tab: InspectorSection, type: LayerType) = when (tab) {
    InspectorSection.TRANSFORM -> "Transform"
    InspectorSection.TIMING -> "Timing"
    InspectorSection.BLENDING_OPACITY -> "Blending & Opacity"
    InspectorSection.CONTEXTUAL_EDIT -> when (type) { LayerType.TEXT -> "Edit Text"; LayerType.SOLID -> "Edit Solid"; else -> "Edit Footage" }
    InspectorSection.AUDIO -> "Audio"
    else -> ""
}

/**
 * The single section-page header row, the same as Edit Text's: ← | page tabs, [InspectorChromeBudget.header] tall.
 * No layer name, duplicate or delete here (they stay on the landing grid). ← returns to the landing grid exactly as
 * before; a vertical swipe on the row dismisses the sheet (the drag handle lives on the landing grid only).
 */
@Composable
private fun SectionHeader(sections: List<InspectorSection>, section: InspectorSection, type: LayerType,
    onBack: () -> Unit, onSelect: (InspectorSection) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    // The swipe-down-to-close gesture is the sheet's (InspectorSwipe), not this row's, so every page has it.
    Row(Modifier.fillMaxWidth().height(InspectorChromeBudget.header(fontScale).dp).testTag("inspector-section-header"),
        verticalAlignment = Alignment.CenterVertically) {
        InspectorIconButton(InspectorIcons.icon("BACK"), "Back to layer tools", Modifier.testTag("inspector-section-back")) { onBack() }
        sections.forEach { tab ->
            val active = tab == section
            Box(Modifier.weight(1f).fillMaxHeight().selectable(active, role = Role.Tab) { onSelect(tab) }
                .testTag("inspector-tab-${tab.name}"), contentAlignment = Alignment.Center) {
                Text(sectionTitle(tab, type), color = if (active) TextPrimary else TextSecondary,
                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (active) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(24.dp, 2.dp).background(TextPrimary, RoundedCornerShape(1.dp)))
            }
        }
    }
}

/**
 * Exact value box. Tapping opens a small anchored entry popover (select-all, IME Done or focus loss commits,
 * Escape/Back cancels). The keyboard overlays the editor; only that popover lifts above it. One edit = one undo.
 */
@Composable
internal fun CompactValueBox(
    label: String,
    valueText: String,
    suffix: String = "",
    onCommitValue: (Float) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.DecimalSigned,
    parse: (String) -> Float? = { ValueParsing.number(it)?.toFloat()?.takeIf { v -> v.isFinite() } },
    height: Dp? = null,
    large: Boolean = false
) {
    var editing by remember { mutableStateOf(false) }
    val name = label.ifEmpty { "Rotation degrees" }
    Row((if (height != null) modifier.height(height) else modifier).clip(RoundedCornerShape(if (height != null) 8.dp else 4.dp)).background(SurfaceHigh)
        .border(0.5.dp, Border, RoundedCornerShape(if (height != null) 8.dp else 4.dp))
        .clickable(role = Role.Button, onClickLabel = "Type $name") { editing = true }
        .semantics(mergeDescendants = true) { contentDescription = "$name $valueText $suffix" }
        .padding(horizontal = if (height != null) 10.dp else 6.dp, vertical = if (height != null) 0.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label.isNotEmpty()) Text(label, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Text(valueText, Modifier.weight(1f), color = TextPrimary, maxLines = 1,
            style = (if (large) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelMedium).copy(fontFamily = FontFamily.Monospace))
        if (suffix.isNotEmpty()) Text(suffix, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        AnchoredPopover(editing, { editing = false }, "value-entry") {
            InspectorValueField(valueText, name, suffix, { text ->
                editing = false
                parse(text)?.takeIf { it.isFinite() }?.let(onCommitValue)
            }, { editing = false }, keyboardType = keyboardType)
        }
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
internal fun PositionTrackpad(
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
internal fun RotationDialController(
    rotationDegrees: Float,
    onBegin: () -> Unit, onPreview: (Float) -> Unit,
    onCommit: () -> Unit, onCancel: () -> Unit, onEdit: (Float) -> Unit, valueHeight: Dp = SectionLayout.VALUE_BOX.dp,
    /** GRAPH G2.1 §9: magnetic 5° detents while dragging (Transform); typed values are never snapped. */
    magnetic: Boolean = false
) {
    var previousAngle by remember { mutableFloatStateOf(0f) }
    var accumulated by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val view = LocalView.current
    var magnet by remember { mutableStateOf<Magnet?>(null) }
    fun angle(point: Offset, bounds: Size) = Math.toDegrees(atan2(
        (point.y - bounds.height / 2).toDouble(), (point.x - bounds.width / 2).toDouble())).toFloat()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().inspectorDrag(
            onStart = { point, bounds -> previousAngle = angle(point, bounds); accumulated = rotationDegrees
                // The handle runs on the circle drawn below (radius = half the dial minus 12dp).
                magnet = if (magnetic) with(density) { TransformDetents.rotation(bounds.minDimension / 2f - 12.dp.toPx(), TransformDetents.ZONE_DP.dp.toPx()) } else null
                onBegin() },
            onMove = { point, bounds ->
                val next = angle(point, bounds)
                val delta = ((next - previousAngle + 540f) % 360f) - 180f
                accumulated += delta
                previousAngle = next
                onPreview(magnet?.apply(accumulated) { InspectorHaptics.tick(view) } ?: accumulated)
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
        CompactValueBox("", PropertyDisplay.ROTATION.storedText(rotationDegrees), "\u00b0", onEdit,
            modifier = Modifier.fillMaxWidth(.7f), keyboardType = KeyboardType.Text,
            parse = ::parseRotation, height = valueHeight, large = true)
    }
}

/** n x degrees is a count of complete turns plus a remainder, not multiplication. */
internal fun formatRotation(degrees: Float): String {
    val turns = kotlin.math.floor(degrees.toDouble() / 360.0).toLong()
    val remainder = degrees.toDouble() - turns * 360.0
    return "${turns}x${number(remainder.toFloat())}"
}

internal fun parseRotation(input: String): Float? {
    val text = input.trim().removeSuffix("\u00b0").replace(',', '.')
    val parts = text.lowercase().split('x', '\u00d7')
    if (parts.size == 1) return ValueParsing.number(parts[0])?.toFloat()?.takeIf { it.isFinite() }
    if (parts.size != 2) return null
    val turns = parts[0].trim().toLongOrNull() ?: return null
    val remainder = parts[1].trim().toDoubleOrNull()?.takeIf { it in 0.0..360.0 } ?: return null
    return (turns * 360.0 + remainder).toFloat().takeIf { it.isFinite() }
}
/**
 * Log-scale rulers. Linked: one ruler drives X and Y proportionally (ScaleController semantics unchanged);
 * unlinked: Width and Height rulers. Previews go through the existing onPreview path, one undo per drag.
 */
@Composable
internal fun ScaleController(
    scaleX: Float, scaleY: Float, linked: Boolean,
    onBegin: () -> Unit, onPreview: (Float, Float) -> Unit,
    onCommit: () -> Unit, onCancel: () -> Unit, onTyped: (Float, Float) -> Unit,
    ruler: SectionLayout.Ruler,
    linkToggle: @Composable () -> Unit
) {
    var initial by remember { mutableStateOf(Offset(1f, 1f)) }
    Column(Modifier.fillMaxWidth().testTag("scale-controller"), verticalArrangement = Arrangement.spacedBy(ScaleRulerMath.GAP_DP.dp)) {
        key(linked) {
            Box(Modifier.fillMaxWidth()) {
                LogScaleRuler(if (linked) "Scale" else "Width", scaleX, magnetic = true,
                    onBegin = { initial = Offset(scaleX, scaleY); onBegin() },
                    onPreview = { value -> onPreview(value, if (linked) ScaleRulerMath.linkedY(initial.x, initial.y, value) else initial.y) },
                    onCommit = onCommit, onCancel = onCancel,
                    onTyped = { value -> onTyped(value, if (linked) ScaleRulerMath.linkedY(scaleX, scaleY, value) else scaleY) },
                    onReset = { onTyped(1f, if (linked) 1f else scaleY) }, modifier = Modifier.fillMaxWidth(), readoutDp = ruler.readout, stripDp = ruler.strip)
                Box(Modifier.align(Alignment.TopEnd)) { linkToggle() }
            }
            if (!linked) {
                LogScaleRuler("Height", scaleY, magnetic = true,
                    onBegin = { initial = Offset(scaleX, scaleY); onBegin() },
                    onPreview = { onPreview(initial.x, it) },
                    onCommit = onCommit, onCancel = onCancel,
                    onTyped = { onTyped(scaleX, it) }, onReset = { onTyped(scaleX, 1f) },
                    modifier = Modifier.fillMaxWidth(), readoutDp = ruler.readout, stripDp = ruler.strip)
            }
        }
    }
}

private data class AnchorSource(val width: Int, val height: Int, val rotation: Int = 0, val pixelAspect: Float = 1f,
    val text: TextLayoutMetrics? = null)

/** Anchor cell marker: a rounded frame with a dot at the anchor's relative position (Box shapes, no Canvas). */
@Composable
private fun AnchorCellMarker(ax: Float, ay: Float, tint: Color) {
    Box(Modifier.size(18.dp).padding(1.dp).border(1.5.dp, tint, RoundedCornerShape(4.dp))) {
        Box(Modifier.align(androidx.compose.ui.BiasAlignment(ax * 2f - 1f, ay * 2f - 1f)).padding(3.dp).size(4.dp).background(tint, CircleShape))
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
                            AnchorCellMarker(ax, ay, if (!ready) TextDisabled else if (selected) Highlight else TextSecondary)
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

/** One extra item in the rail's property-options menu. */
internal data class RailMenuItem(val label: String, val enabled: Boolean = true, val action: () -> Unit)

/**
 * The shared left utility rail (Transform, Opacity, Edit Text): a tight top-aligned stack of 40dp tiles with a 6dp
 * gap, in this order: keyframe diamond, Graph (disabled), optional 3D (disabled; dropped for text), property-options ⋮.
 * When a short sheet cannot hold the full stack, only the disabled placeholders shrink. [keyIcon]/[keyState] let a page
 * show richer diamond states (between keys, Hold squares); [confirm] shows a small anchored confirmation on the ⋮,
 * and [menuAnchor] hosts popovers opened from a ⋮ item (anchored on the ⋮).
 */
@Composable
internal fun InspectorUtilityRail(resetLabel: String, onReset: () -> Unit,
    onResetAll: (() -> Unit)? = null, isAnimated: Boolean = false,
    hasKeyframeAtPlayhead: Boolean = false, onToggleKeyframe: (() -> Unit)? = null,
    show3D: Boolean = true, keyIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    keyLabel: String? = null, keyState: String? = null, extraItems: List<RailMenuItem> = emptyList(),
    confirm: Pair<String, () -> Unit>? = null, onConfirmDismiss: () -> Unit = {}, menuAnchor: @Composable () -> Unit = {},
    graphTarget: GraphTarget? = null) {
    var menu by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    // GRAPH G1: the Graph icon opens the Graph page for this rail's focused property (continuous, >= 2 keys) and,
    // on the Graph page itself, returns to the page it was opened from. Otherwise it stays visible and disabled.
    val graphHost = LocalGraphHost.current
    val graphOpen = graphHost != null && graphTarget != null && graphHost.openTarget == graphTarget
    val graphEnabled = graphOpen || (graphHost != null && graphTarget != null && GraphModel.enabled(graphHost.layer(), graphTarget))
    BoxWithConstraints(Modifier.width(RailBudget.TILE.dp).fillMaxHeight().testTag("inspector-rail")) {
        val disabledCount = if (show3D) 2 else 1
        val placeholder = RailBudget.disabledHeight(maxHeight.value, 2, disabledCount).dp
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RailBudget.GAP.dp)) {
            val tint = if (onToggleKeyframe == null) TextDisabled else if (isAnimated) Highlight else TextSecondary
            InspectorIconButton(keyIcon ?: InspectorIcons.icon(if (hasKeyframeAtPlayhead) "KEY_ON" else if (isAnimated) "KEY_ANIM" else "KEY"),
                keyLabel ?: if (hasKeyframeAtPlayhead) "Remove keyframe at playhead" else "Add keyframe at playhead",
                Modifier.testTag("inspector-key-diamond").semantics {
                    if (keyState != null) { stateDescription = keyState; liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }
                }, enabled = onToggleKeyframe != null, tint = tint) { focus.clearFocus(); onToggleKeyframe?.invoke() }
            if (graphEnabled) Box(Modifier.size(RailBudget.TILE.dp, placeholder).clip(RoundedCornerShape(10.dp))
                .background(if (graphOpen) Surface else Color.Transparent)
                .clickable(role = Role.Button, onClickLabel = if (graphOpen) "Close graph editor" else "Open graph editor") {
                    focus.clearFocus(); PopoverDismissal.request()
                    if (graphOpen) graphHost!!.close() else graphHost!!.open(graphTarget!!)
                }
                .semantics { contentDescription = "Graph editor"; stateDescription = if (graphOpen) "Open" else "Closed"; selected = graphOpen }
                .testTag("inspector-rail-graph"), contentAlignment = Alignment.Center) {
                Icon(InspectorIcons.icon("GRAPH"), null, Modifier.size(minOf(InspectorIcons.SIZE, placeholder)), tint = if (graphOpen) Highlight else TextSecondary)
            }
            else Box(Modifier.size(RailBudget.TILE.dp, placeholder).semantics { contentDescription = "Graph editor (needs a continuous property with two keys)"; disabled() }.testTag("inspector-rail-graph"),
                contentAlignment = Alignment.Center) { Icon(InspectorIcons.icon("GRAPH"), null, Modifier.size(minOf(InspectorIcons.SIZE, placeholder)), tint = TextDisabled) }
            if (show3D) Box(Modifier.size(RailBudget.TILE.dp, placeholder).semantics { contentDescription = "3D layer controls (not yet available)"; disabled() }.testTag("inspector-rail-3d"),
                contentAlignment = Alignment.Center) { Icon(InspectorIcons.icon("CUBE"), null, Modifier.size(minOf(InspectorIcons.SIZE, placeholder)), tint = TextDisabled) }
            Box {
                InspectorIconButton(InspectorIcons.icon("OVERFLOW"), "Property options", Modifier.testTag("inspector-rail-menu"), tint = TextPrimary) { focus.clearFocus(); menu = true }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(resetLabel) }, onClick = { menu = false; onReset() })
                    if (onResetAll != null) DropdownMenuItem(text = { Text("Reset all transforms") }, onClick = { menu = false; onResetAll() })
                    extraItems.forEach { item -> DropdownMenuItem(text = { Text(item.label) }, enabled = item.enabled, onClick = { menu = false; item.action() }) }
                }
                AnchoredPopover(confirm != null, onConfirmDismiss, "inspector-rail-confirm") {
                    Text(confirm?.first.orEmpty(), Modifier.widthIn(max = 240.dp).padding(8.dp), color = TextPrimary, style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.align(Alignment.End)) {
                        TextButton(onClick = onConfirmDismiss) { Text("Cancel", color = TextSecondary) }
                        TextButton(onClick = { confirm?.second?.invoke(); onConfirmDismiss() }) { Text("Reset", color = TextPrimary) }
                    }
                }
                menuAnchor()
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
    // GRAPH G1: saveable, so returning from the Graph page restores the mode it was opened from.
    var mode by rememberSaveable { mutableStateOf(TransformSubMode.POSITION) }
    var isAnchorMode by rememberSaveable { mutableStateOf(false) }
    val anchorModeCallback by rememberUpdatedState(onAnchorModeChange)
    SideEffect { anchorModeCallback(isAnchorMode) }
    DisposableEffect(Unit) { onDispose { anchorModeCallback(false) } }
    var linked by rememberSaveable { mutableStateOf(true) }
    val focusManager = LocalFocusManager.current
    val currentTransform = remember(layer, playheadUs) { layer.evaluatedTransform(playheadUs) }
    val transform = currentTransform
    val refW = layer.referenceWidth.takeIf { it > 0 } ?: compositionWidth
    val refH = layer.referenceHeight.takeIf { it > 0 } ?: compositionHeight
    val context = LocalContext.current
    val heldSource = layer.text.source.evaluateDocument(playheadUs - layer.resolvedKeyframeAnchorUs)
    val ligatures=layer.text.effectiveLigatures(playheadUs-layer.resolvedKeyframeAnchorUs)
    val source by produceState<AnchorSource?>(null, layer.type, layer.asset, mediaRepository, refW, refH, layer.text.fontId, heldSource,ligatures,layer.text) {
        value = null
        value = if (layer.type == LayerType.TEXT) {
            try { AnchorSource(1, 1, text = com.fluxx.android.render.TextMetricsRepository.get(context).measure(layer.text.fontId, heldSource,ligatures,layer.text)) }
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
                requireNotNull(geometry.text).logical(base.text, playheadUs - base.resolvedKeyframeAnchorUs),
                base.anchorX, base.anchorY, ax, ay, refW, refH)
            else com.fluxx.android.render.LayerGeometry.compensatePosition(compensationMatrix, t,
                base.anchorX, base.anchorY, ax, ay, geometry.width, geometry.height,
                geometry.rotation, geometry.pixelAspect, refW, refH)
        }
        return EditorAction.SetAnchorPoint(layer.id, ax, ay, position)
    }
    // GRAPH G2.1 §5: the shared stored → shown conversion (the Value graph uses the same helper).
    val unitsX = PropertyDisplay.positionX(compositionWidth, refW); val unitsY = PropertyDisplay.positionY(compositionHeight, refH)
    val pixelX = unitsX.toDisplay(transform.positionX)
    val pixelY = unitsY.toDisplay(transform.positionY)
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
    // Keyframe badges on the mode column: same key model as the rail diamond, per property at the playhead.
    val modeBadges = remember(layer.animTransform, localTimeUs) {
        TransformSubMode.entries.associateWith { m -> KeyBadges.of(layer, when (m) {
            TransformSubMode.POSITION -> AnimPropertyType.POSITION
            TransformSubMode.ROTATION -> AnimPropertyType.ROTATION
            TransformSubMode.SCALE -> AnimPropertyType.SCALE
        }, localTimeUs) }
    }
    // TEXT UI 8: fixed control sizes (SectionLayout), independent of the sheet height; the group is centred.
    Box(Modifier.fillMaxSize()) {
    val fontScale = LocalDensity.current.fontScale
    val valueBox = SectionLayout.valueBox(fontScale).dp
    val ruler = SectionLayout.ruler(fontScale)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(TextInspectorBudget.RAIL_GAP.dp)) {
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
            },
            graphTarget = if (isAnchorMode) null else GraphTarget(currentProp)
        )
        // The active mode's control group is centred vertically in the content area and horizontally between the
        // rail and the mode column (CentredSectionGroup); sizes are SectionLayout constants, never the sheet height.
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (mode) {
                TransformSubMode.POSITION -> {
                  if (isAnchorMode) {
                    AnchorPointControls(layer.anchorX, layer.anchorY, layer.animTransform.position.isAnimated,
                        source != null || layer.animTransform.position.isAnimated,
                        onEdit = { x, y -> anchorAction(x, y, false)?.let(onAction) },
                        onBegin = { anchorGestureLayer = layer; anchorGestureTransform = transform; onBeginGesture() },
                        onPreview = { x, y -> anchorAction(x, y, true)?.let(onAnchorPreview) },
                        onCommit = onCommitGesture, onCancel = onCancelGesture)
                  } else CentredSectionGroup(SectionLayout.positionGroup(fontScale), "transform-group-position") {
                    val limitX = PropertyLimits.position(compositionWidth); val limitY = PropertyLimits.position(compositionHeight)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SectionLayout.GAP.dp)) {
                        CompactValueBox("X", unitsX.text(pixelX), onCommitValue = {
                            val newPosX = unitsX.toStored(limitX.typed(it))
                            onEdit(transform.copy(positionX = newPosX))
                        }, modifier = Modifier.weight(1f), height = valueBox, large = true)
                        CompactValueBox("Y", unitsY.text(pixelY), onCommitValue = {
                            val newPosY = unitsY.toStored(limitY.typed(it))
                            onEdit(transform.copy(positionY = newPosY))
                        }, modifier = Modifier.weight(1f), height = valueBox, large = true)
                    }
                    Spacer(Modifier.height(SectionLayout.GAP.dp))
                    // Planned: Z position requires a 3D layer model. The pad never clamps inside ±10× the composition.
                    Box(Modifier.size(SectionLayout.PAD.dp).testTag("position-pad")) { PositionTrackpad(pixelX / compositionWidth, pixelY / compositionHeight, begin, { x, y ->
                        val newPosX = unitsX.toStored(limitX.hard(x * compositionWidth))
                        val newPosY = unitsY.toStored(limitY.hard(y * compositionHeight))
                        onPreviewGesture(gestureBase.copy(positionX = newPosX, positionY = newPosY))
                    }, onCommitGesture, onCancelGesture) }
                  }
                }
                TransformSubMode.ROTATION -> CentredSectionGroup(SectionLayout.dialGroup(), "transform-group-rotation") {
                    // Planned: XYZ rotation requires 3D support. Keep the existing single-angle model.
                    Box(Modifier.size(SectionLayout.DIAL.dp).testTag("rotation-dial")) { RotationDialController(transform.rotationDegrees, begin,
                        {
                            // The dial never wraps; it only stops at the ±100-turn hard cap.
                            onPreviewGesture(gestureBase.copy(rotationDegrees = PropertyLimits.ROTATION.hard(it)))
                        }, onCommitGesture, onCancelGesture,
                        {
                            onEdit(transform.copy(rotationDegrees = PropertyLimits.ROTATION.typed(it)))
                        }, valueBox, magnetic = true) }
                }
                TransformSubMode.SCALE -> CentredSectionGroup(SectionLayout.scaleGroup(linked, fontScale), "transform-group-scale") {
                    // Typed values (±50000%) come from each ruler's readout; Flip H/V stays in the layer menu.
                    ScaleController(transform.scaleX, transform.scaleY, linked, begin,
                        { x, y ->
                            onPreviewGesture(gestureBase.copy(scaleX = x, scaleY = y))
                        }, onCommitGesture, onCancelGesture,
                        { x, y -> val cap = PropertyLimits.LAYER_SCALE.hardMax / 100f
                            onEdit(transform.copy(scaleX = x.coerceIn(-cap, cap), scaleY = y.coerceIn(-cap, cap))) }, ruler) {
                        Box(Modifier.size(SectionLayout.MIN_TARGET.dp, maxOf(ruler.readout, SectionLayout.MIN_TARGET).dp).toggleable(linked, role = Role.Switch) { focusManager.clearFocus(); linked = it }
                            .semantics { contentDescription = "Link width and height scale" }.testTag("scale-link"), contentAlignment = Alignment.Center) {
                            Icon(InspectorIcons.icon(if (linked) "LINK" else "LINK_OFF"), null, Modifier.size(InspectorIcons.INLINE), tint = if (linked) Highlight else TextSecondary)
                        }
                    }
                }
            }
        }
        // Mode column: top-aligned with the first content row; each mode shows its keyframe badge (hollow / filled).
        Column(Modifier.width(SectionLayout.MODE_COLUMN.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(SectionLayout.MODE_GAP.dp)) {
            TransformSubMode.entries.forEach { item ->
                val active = mode == item
                val badge = if (item == TransformSubMode.POSITION && isAnchorMode) KeyBadge.NONE else modeBadges.getValue(item)
                Box(Modifier.size(SectionLayout.MODE_TILE.dp).testTag("transform-mode-${item.name}")) {
                IconToggleButton(checked = active, onCheckedChange = {
                    focusManager.clearFocus()
                    isAnchorMode = item == TransformSubMode.POSITION && active && !isAnchorMode
                    mode = item
                }, modifier = Modifier.fillMaxSize().semantics { contentDescription = if (item == TransformSubMode.POSITION)
                    (if (isAnchorMode) "Anchor point; tap for Position" else "Position; tap again for Anchor point")
                    else item.name.lowercase().replaceFirstChar { it.uppercase() }
                    KeyBadges.description(badge)?.let { stateDescription = it } }) {
                    val color = if (active) Highlight else TextSecondary
                    Icon(InspectorIcons.icon(when (item) {
                        TransformSubMode.POSITION -> if (isAnchorMode) "ANCHOR" else "POSITION"
                        TransformSubMode.ROTATION -> "ROTATION"
                        TransformSubMode.SCALE -> "SCALE"
                    }), null, Modifier.size(InspectorIcons.SIZE), tint = color)
                }
                KeyBadgeGlyph(badge, if (active) Highlight else TextSecondary, Modifier.align(Alignment.TopEnd))
                }
            }
            Spacer(Modifier.weight(1f))
        }
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
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(TextInspectorBudget.RAIL_GAP.dp)) {
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
            },
            graphTarget = GraphTarget(AnimPropertyType.OPACITY)
        )
        // Fixed sizes (SectionLayout): a 40dp blend-mode row and one slider row (20 label + 48 touch), centred.
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val fontScale = LocalDensity.current.fontScale
            val row = SectionLayout.sliderRow(fontScale)
            val badge = remember(layer.animTransform.opacity, localTimeUs) { KeyBadges.of(layer, AnimPropertyType.OPACITY, localTimeUs) }
            CentredSectionGroup(SectionLayout.sliderGroup(1, 1, fontScale), "opacity-group") {
                Row(Modifier.fillMaxWidth().height(SectionLayout.text(SectionLayout.BLEND_ROW, fontScale).dp).background(SurfaceHigh, RoundedCornerShape(8.dp))
                    .border(.5.dp, Border, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp)
                    .semantics { contentDescription = "Blend Mode: Normal, not yet available"; disabled() },
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Blend Mode", color = TextDisabled, fontSize = 13.sp)
                    Text("Normal", color = TextDisabled, fontSize = 13.sp)
                }
                Spacer(Modifier.height(SectionLayout.GAP.dp))
                Column(Modifier.fillMaxWidth().testTag("opacity-row")) {
                    SliderRowLabel("Opacity", row, badge)
                    Row(Modifier.fillMaxWidth().height(row.touch.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SectionLayout.GAP.dp)) {
                        SectionSlider(opacity, row, "Opacity", detents = { track, zone -> TransformDetents.opacity(track, zone) },
                            onBegin = { if (!dragging) { onBeginGesture(); dragging = true } },
                            onChange = { onPreviewGesture(it) },
                            onFinish = { if (dragging) { dragging = false; onCommitGesture() } },
                            onCancel = { if (dragging) { dragging = false; onCancelGesture() } },
                            modifier = Modifier.weight(1f))
                        CompactValueBox("", PropertyDisplay.OPACITY.storedText(opacity), "%", {
                            val newOp = PropertyDisplay.OPACITY.toStored(PropertyLimits.OPACITY.typed(it))
                            onEdit(newOp)
                        }, modifier = Modifier.width(row.valueWidth.dp).semantics { contentDescription = "Opacity percentage" },
                            height = row.valueHeight.dp, large = true)
                    }
                }
            }
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

    // Fixed slider rows (SectionLayout: 20 label + 48 touch, 6dp track, 20dp thumb, 72 x 40 value), centred as a group.
    Box(Modifier.fillMaxSize()) {
    val fontScale = LocalDensity.current.fontScale
    val row = SectionLayout.sliderRow(fontScale)
    CentredSectionGroup(SectionLayout.sliderGroup(if (isLayerVideo) 3 else 2, 0, fontScale), "timing-group") {
        CompactSliderItem(
            modifier = Modifier.fillMaxWidth(), row = row,
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
            modifier = Modifier.fillMaxWidth(), row = row,
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
                modifier = Modifier.fillMaxWidth(), row = row,
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
    // Same sizing discipline as Timing: fixed rows (SectionLayout), the measured group centred in the content area.
    val fontScale = LocalDensity.current.fontScale
    CentredSectionGroup(null, "audio-group") { Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SectionLayout.GAP.dp)
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
                .height(SectionLayout.text(SectionLayout.MIN_TARGET, fontScale).dp)
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
    } }
}

/**
 * Section slider row (Timing, Audio): a label line above a touch row holding a thick-track slider and a tappable value
 * box, sized by [SectionLayout.sliderRow] so rows use the height the single header freed.
 */
@Composable
private fun CompactSliderItem(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    displayValue: String,
    modifier: Modifier = Modifier,
    row: SectionLayout.SliderRow = SectionLayout.sliderRow(LocalDensity.current.fontScale),
    enabled: Boolean = true,
    onBegin: () -> Unit,
    onValueChange: (Float) -> Unit,
    onCommit: () -> Unit,
    onDirectEdit: (Float) -> Unit,
    dialogTitle: String = "Set $label",
    dialogSuffix: String = ""
) {
    var showDialog by remember { mutableStateOf(false) }
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    Column(modifier.testTag("slider-row-$label")) {
        SliderRowLabel(label, row, enabled = enabled)
        Row(Modifier.fillMaxWidth().height(row.touch.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SectionLayout.GAP.dp)) {
            SectionSlider(((value - valueRange.start) / span).coerceIn(0f, 1f), row, label,
                onBegin = onBegin, onChange = { f -> onValueChange(valueRange.start + f * span) },
                onFinish = onCommit, onCancel = onCommit, modifier = Modifier.weight(1f), enabled = enabled)
            Box(
                modifier = Modifier
                    .height(row.valueHeight.dp)
                    .widthIn(min = row.valueWidth.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceHigh)
                    .border(0.5.dp, Border, RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled, role = Role.Button, onClickLabel = dialogTitle) { showDialog = true }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = displayValue,
                    color = if (enabled) TextPrimary else TextDisabled,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
            }
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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.DecimalSigned),
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
                    val parsed = ValueParsing.number(text)?.toFloat()?.takeIf { it.isFinite() }
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

