package com.fluxx.android.ui.inspector

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import com.fluxx.android.ui.theme.*
import kotlin.math.roundToInt

/** Where a popover sits relative to its anchor. Pure arithmetic in window pixels; covered by JVM tests. */
internal data class PopoverPlacement(val x: Int, val y: Int, val above: Boolean, val originX: Float)

internal object PopoverPlacer {
    /**
     * Directly above the anchor when it fits, otherwise below, otherwise pinned inside the window. The popover is
     * clamped horizontally with [margin] on both sides and its bottom never goes below [visibleBottom] - [margin]
     * (the top of the keyboard when one is showing), so only the popover lifts above the IME.
     */
    fun place(anchorLeft: Int, anchorTop: Int, anchorRight: Int, anchorBottom: Int, width: Int, height: Int,
        windowWidth: Int, windowHeight: Int, visibleBottom: Int, margin: Int, gap: Int = 0): PopoverPlacement {
        val bottomLimit = minOf(windowHeight, visibleBottom.coerceAtLeast(0))
        val centre = (anchorLeft + anchorRight) / 2
        val maxX = (windowWidth - width - margin).coerceAtLeast(margin)
        val x = (centre - width / 2).coerceIn(margin, maxX)
        val aboveY = anchorTop - gap - height
        val belowY = anchorBottom + gap
        val (y, above) = when {
            aboveY >= margin && aboveY + height <= bottomLimit - margin -> aboveY to true
            belowY + height <= bottomLimit - margin -> belowY to false
            else -> (bottomLimit - margin - height).coerceAtLeast(margin).coerceAtMost(maxOf(margin, windowHeight - height)) to true
        }
        val origin = if (width <= 0) .5f else ((centre - x).toFloat() / width).coerceIn(0f, 1f)
        return PopoverPlacement(x, y, above, origin)
    }
}

/** Top of the keyboard in this window, in pixels; the window height when no keyboard is showing. */
@Composable internal fun rememberVisibleBottom(): Int {
    val view = LocalView.current
    val ime = WindowInsets.ime.getBottom(LocalDensity.current)
    val height = view.rootView.height.takeIf { it > 0 } ?: Int.MAX_VALUE
    return if (height == Int.MAX_VALUE) height else height - ime
}

/** Haptics through the View so the system "touch feedback" setting is respected. */
internal object InspectorHaptics {
    fun tick(view: View) { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    fun strong(view: View) { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }
    fun press(view: View) { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
}

/**
 * A small popover anchored to the composable that contains it, like an overflow menu: never centred, never a
 * sheet. Scales out from the anchor; tap outside or Back closes it. Focusable so its value field can take the IME.
 */
@Composable internal fun AnchoredPopover(visible: Boolean, onDismiss: () -> Unit, testTag: String = "inspector-popover",
    content: @Composable ColumnScope.() -> Unit) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = visible
    if (!state.currentState && !state.targetState && state.isIdle) return
    val density = LocalDensity.current
    val visibleBottom = rememberVisibleBottom()
    val hostIme = WindowInsets.ime.getBottom(density)
    val margin = with(density) { 8.dp.roundToPx() }
    val gap = with(density) { 4.dp.roundToPx() }
    var origin by remember { mutableStateOf(TransformOrigin(.5f, 1f)) }
    val provider = remember(visibleBottom, margin, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val p = PopoverPlacer.place(anchorBounds.left, anchorBounds.top, anchorBounds.right, anchorBounds.bottom,
                    popupContentSize.width, popupContentSize.height, windowSize.width, windowSize.height,
                    minOf(windowSize.height, visibleBottom), margin, gap)
                origin = TransformOrigin(p.originX, if (p.above) 1f else 0f)
                return IntOffset(p.x, p.y)
            }
        }
    }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
      CompositionLocalProvider(LocalHostImeBottom provides hostIme) {
        AnimatedVisibility(state, enter = scaleIn(tween(140), .8f, origin) + fadeIn(tween(110)),
            exit = scaleOut(tween(110), .8f, origin) + fadeOut(tween(90))) {
            Surface(Modifier.testTag(testTag).widthIn(max = 344.dp), shape = RoundedCornerShape(10.dp), color = SurfaceHigh,
                tonalElevation = 3.dp, shadowElevation = 8.dp, border = BorderStroke(1.dp, Border)) {
                Column(Modifier.padding(4.dp), content = content)
            }
        }
      }
    }
}

/**
 * Typed-entry lifecycle, exactly once: IME Done, focus loss or closing the popover by tapping outside commits;
 * Escape cancels; Back (which first hides the keyboard after it was shown) cancels.
 */
internal class TypedEntry(private val onCommit: (String) -> Unit, private val onCancel: () -> Unit) {
    var finished = false; private set
    private var keyboardSeen = false
    fun commit(text: String) { if (!finished) { finished = true; onCommit(text) } }
    fun cancel() { if (!finished) { finished = true; onCancel() } }
    fun keyboard(visible: Boolean) { if (visible) keyboardSeen = true else if (keyboardSeen) cancel() }
    fun dispose(text: String) = commit(text)
}

/** The host window's keyboard height, passed into popup windows (which may not receive IME insets themselves). */
internal val LocalHostImeBottom = compositionLocalOf { -1 }

/**
 * Inline numeric field: select-all on focus, numeric IME, commit on IME Done or focus loss, Escape cancels.
 * The caller decides how Back cancels (it hides the IME first, then closes the popover without committing).
 */
@Composable internal fun InspectorValueField(initial: String, label: String, unit: String, onCommit: (String) -> Unit, onCancel: () -> Unit,
    modifier: Modifier = Modifier, keyboardType: KeyboardType = KeyboardType.Decimal) {
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val currentCommit by rememberUpdatedState(onCommit); val currentCancel by rememberUpdatedState(onCancel)
    val entry = remember { TypedEntry({ currentCommit(it) }, { currentCancel() }) }
    fun commit() = entry.commit(field.text)
    fun cancel() = entry.cancel()
    LaunchedEffect(Unit) { requester.requestFocus() }
    val hostIme = LocalHostImeBottom.current
    val ime = if (hostIme >= 0) hostIme else WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(ime) { entry.keyboard(ime > 0) }
    DisposableEffect(Unit) { onDispose { entry.dispose(field.text) } }
    Row(modifier.height(36.dp).clip(RoundedCornerShape(8.dp)).background(Surface).border(1.dp, TextPrimary, RoundedCornerShape(8.dp))
        .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(field, { field = it }, singleLine = true,
            textStyle = MaterialTheme.typography.labelLarge.copy(color = TextPrimary, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End),
            cursorBrush = SolidColor(TextPrimary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier.widthIn(min = 56.dp, max = 120.dp).focusRequester(requester)
                .onFocusChanged {
                    if (it.isFocused && !focused) field = field.copy(selection = TextRange(0, field.text.length))
                    if (focused && !it.isFocused) commit()
                    focused = it.isFocused
                }
                .onPreviewKeyEvent { if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) { cancel(); true } else false }
                .semantics { contentDescription = "$label, exact value in $unit" })
        if (unit.isNotEmpty()) Text(unit, Modifier.padding(start = 4.dp), color = TextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/**
 * `[reset ↺]  slider ----●  [value]`. One undo entry per drag (begin on first move, finish on release) and per
 * typed commit. Live preview is conflated to one publish per frame, exactly like the old scrub.
 */
@Composable internal fun SliderPopoverContent(limit: PropertyLimit, value: Float, mixed: Boolean, label: String,
    onReset: () -> Unit, onBegin: () -> Unit, onPreview: (Float) -> Unit, onFinish: () -> Unit, onCancel: () -> Unit,
    onTyped: (Float) -> Unit, enabled: Boolean = true, extras: (@Composable RowScope.() -> Unit)? = null) {
    val view = LocalView.current
    var dragging by remember { mutableStateOf(false) }
    var live by remember { mutableFloatStateOf(value) }
    var pending by remember { mutableStateOf<Float?>(null) }
    var editing by remember { mutableStateOf(false) }
    var lastHaptic by remember { mutableLongStateOf(0L) }
    val preview by rememberUpdatedState(onPreview)
    val cancel by rememberUpdatedState(onCancel)
    LaunchedEffect(value) { if (!dragging) live = value }
    LaunchedEffect(dragging) { while (dragging) { withFrameNanos { }; pending?.let { pending = null; preview(it) } } }
    DisposableEffect(Unit) { onDispose { if (dragging) cancel() } }
    Row(Modifier.height(48.dp).widthIn(min = 280.dp), verticalAlignment = Alignment.CenterVertically) {
        InspectorIconButton(InspectorIcons.icon("RESET"), "Reset $label to default", enabled = enabled) { onReset() }
        Slider(value = limit.fraction(live), enabled = enabled,
            onValueChange = { f ->
                val next = limit.valueAt(f)
                if (!dragging) { dragging = true; InspectorHaptics.press(view); onBegin() }
                val now = System.currentTimeMillis()
                if ((limit.detent(live) != limit.detent(next) || limit.crossesAnchor(live, next)) && now - lastHaptic >= 35) {
                    InspectorHaptics.tick(view); lastHaptic = now
                }
                live = next; pending = next
            },
            onValueChangeFinished = { if (dragging) { pending = null; preview(live); dragging = false; onFinish() } },
            steps = limit.ladder?.let { (it.size - 2).coerceAtLeast(0) } ?: 0,
            colors = SliderDefaults.colors(thumbColor = TextPrimary, activeTrackColor = TextPrimary, inactiveTrackColor = Border,
                activeTickColor = Surface, inactiveTickColor = TextSecondary),
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp).semantics { contentDescription = "$label slider" })
        if (editing) InspectorValueField(if (mixed) "" else PropertyLimits.format(limit, live), label, limit.unit,
            { text -> editing = false; PropertyLimits.parseTyped(limit, text)?.let { v -> live = v; onTyped(v) } }, { editing = false })
        else TextButton(onClick = { editing = true }, enabled = enabled, contentPadding = PaddingValues(horizontal = 6.dp),
            modifier = Modifier.heightIn(min = 40.dp).widthIn(min = 56.dp).semantics { contentDescription = "$label ${if (mixed && !dragging) "mixed" else PropertyLimits.format(limit, live)} ${limit.unit}; tap to type" }) {
            Text((if (mixed && !dragging) "—" else PropertyLimits.format(limit, live)) + if (limit.unit.length <= 2) limit.unit else "",
                color = TextPrimary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
    if (extras != null) Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically, content = extras)
}

/** Small monochrome icon button used inside popovers and the rail. */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun InspectorIconButton(vector: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    selected: Boolean = false, tint: Color? = null, click: () -> Unit) {
    var tooltip by remember { mutableStateOf(false) }
    Box(modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (selected) Surface else Color.Transparent)
        .combinedClickable(enabled = enabled, onClick = click, onLongClick = { tooltip = true }, role = Role.Button)
        .semantics { contentDescription = label; if (selected) stateDescription = "Selected" }, contentAlignment = Alignment.Center) {
        Icon(vector, null, Modifier.size(InspectorIcons.SIZE), tint = tint ?: if (!enabled) TextDisabled else if (selected) TextPrimary else TextSecondary)
        DropdownMenu(tooltip, { tooltip = false }) { DropdownMenuItem(text = { Text(label) }, onClick = { tooltip = false }) }
    }
}

/**
 * Filled tile from the inspector landing grid: SurfaceHigh, 10dp radius, 1dp bright border when active, pressed
 * overlay, icon plus an optional tiny value caption in the corner. With [fill] the tile is a grid cell: the whole
 * cell (tile + gap) is the touch target and the drawn tile fills it inset by [fill]. Without it, [hit] is the
 * tap target (40 compact / 48dp) and the drawn tile is 40dp, centred.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun InspectorTile(vector: ImageVector?, label: String, modifier: Modifier = Modifier, hit: Dp = 40.dp, active: Boolean = false,
    enabled: Boolean = true, caption: String? = null, text: String? = null, state: String? = null, testTag: String? = null,
    onLongClick: (() -> Unit)? = null, iconContent: (@Composable () -> Unit)? = null, fill: PaddingValues? = null,
    popover: @Composable () -> Unit = {}, click: () -> Unit) {
    var tooltip by remember { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val ink = if (!enabled) TextDisabled else if (active) TextPrimary else TextSecondary
    val shape = RoundedCornerShape(if (fill != null) 8.dp else 10.dp)
    Box((if (fill != null) modifier else modifier.heightIn(min = hit).widthIn(min = hit)).then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
        .combinedClickable(interactionSource = interactions, indication = null, enabled = enabled, role = Role.Button, onClick = click,
            onLongClick = { onLongClick?.invoke() ?: run { tooltip = true } })
        .semantics(mergeDescendants = true) {
            contentDescription = label + (caption?.let { ", $it" } ?: "")
            stateDescription = state ?: if (!enabled) "Unavailable" else if (active) "On" else "Off"
        }, contentAlignment = Alignment.Center) {
        val visual = if (fill != null) Modifier.fillMaxSize().padding(fill)
            else Modifier.then(if (text != null) Modifier.fillMaxWidth().padding(horizontal = (hit - 40.dp).coerceAtLeast(0.dp) / 2) else Modifier.size(40.dp)).height(40.dp)
        Box(visual.clip(shape).background(if (pressed) Surface else SurfaceHigh)
            .border(1.dp, if (active) TextPrimary else if (pressed) Border else Color.Transparent, shape), contentAlignment = Alignment.Center) {
            if (text != null) Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                vector?.let { Icon(it, null, Modifier.size(InspectorIcons.INLINE), tint = ink) } ?: iconContent?.invoke()
                Text(text, Modifier.weight(1f).padding(start = 4.dp), color = ink, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                caption?.let { Text(it, color = TextSecondary, fontSize = 9.sp, maxLines = 1) }
            } else {
                vector?.let { Icon(it, null, Modifier.size(InspectorIcons.SIZE), tint = ink) } ?: iconContent?.invoke()
                caption?.let { Text(it, Modifier.align(Alignment.BottomEnd).padding(end = 3.dp, bottom = 1.dp), color = TextSecondary,
                    fontSize = 8.sp, lineHeight = 9.sp, maxLines = 1, overflow = TextOverflow.Clip) }
            }
        }
        DropdownMenu(tooltip, { tooltip = false }) { DropdownMenuItem(text = { Text(if (state != null) "$label · $state" else label) }, onClick = { tooltip = false }) }
        popover()
    }
}
