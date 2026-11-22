package com.fluxx.android.ui.inspector

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import com.fluxx.android.ui.common.colorpicker.*
import com.fluxx.android.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Transient Animate page state (TEXT F3.1). Saved with the Edit Text panel, so it survives rotation and switching
 * between Character / Paragraph / Animate. Never document state, never an undo entry.
 */
internal class AnimatePageState(bars: MutableState<AnimateBars>, focus: MutableState<AnimatorTrackAddress?>,
    subPage: MutableState<String?>, linkScale: MutableState<Boolean>) {
    var bars by bars
    var focus by focus
    /** Position / Rotation / Scale pad, dial or rulers shown in place of the list (Controls: Pad, dial, rulers). */
    var subPage by subPage
    var linkScale by linkScale
}

@Composable internal fun rememberAnimatePageState(layerId: Long): AnimatePageState {
    val bars = androidx.compose.runtime.saveable.rememberSaveable(layerId, saver = androidx.compose.runtime.saveable.Saver<MutableState<AnimateBars>, List<Any>>(
        save = { it.value.save() }, restore = { mutableStateOf(runCatching { AnimateBars.restore(it) }.getOrDefault(AnimateBars())) })) { mutableStateOf(AnimateBars()) }
    val focus = androidx.compose.runtime.saveable.rememberSaveable(layerId, saver = androidx.compose.runtime.saveable.Saver<MutableState<AnimatorTrackAddress?>, List<Any>>(
        save = { AnimateAddressCodec.save(it.value) }, restore = { mutableStateOf(runCatching { AnimateAddressCodec.restore(it) }.getOrNull()) })) { mutableStateOf<AnimatorTrackAddress?>(null) }
    val sub = androidx.compose.runtime.saveable.rememberSaveable(layerId) { mutableStateOf<String?>(null) }
    val link = androidx.compose.runtime.saveable.rememberSaveable(layerId) { mutableStateOf(true) }
    return remember(bars, focus, sub, link) { AnimatePageState(bars, focus, sub, link) }
}

/** The bar being dragged by its grip: its block follows the finger; targets use the slot centres captured at the start. */
private class AnimateDrag {
    var id by mutableStateOf<Long?>(null)
    var parent: Long? = null
    var order: List<Long> = emptyList()
    var centres: Map<Long, Float> = emptyMap()
    var startCentre = 0f
    var startTop = 0f
    var scrollAtStart = 0f
    var dy by mutableFloatStateOf(0f)
    var pointer = 0f
    var start = 0
    var target = 0
}

private val CHARACTER_PROPERTIES = setOf(AnimatorProperty.CHARACTER_OFFSET, AnimatorProperty.CHARACTER_VALUE)

/**
 * The Animate tab body (TEXT F3.1): a centred add control, then one collapsible bar per animator holding its Property,
 * Selector and Grouping rows. The body scrolls vertically when the open content is taller than it; the sheet never
 * grows. Every edit is an existing [AnimatorEdit]: drags (sliders, pad, reorder) run through the existing begin /
 * preview / finish draft (one undo per drag); taps, typed values and structure edits are single actions (one undo each).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun TextAnimatePage(layer: CompositionLayer, time: Long, frameRate: FrameRate, compositionWidth: Int, compositionHeight: Int,
    state: AnimatePageState, finishing: Boolean, palette: PaletteRepository?, controls: AnimateControls,
    onAction: (EditorAction) -> Unit, onValueBegin: () -> Unit, onPayloadPreview: (TextProperties) -> Unit,
    onFinish: () -> Unit, onCancel: () -> Unit, onMessage: (String) -> Unit, metrics: AnimateMetrics, modifier: Modifier = Modifier) {
    val text = layer.text
    val local = Math.subtractExact(frameRate.snap(time), layer.resolvedKeyframeAnchorUs)
    val resolved = state.bars.resolve(text)
    if (resolved !== state.bars) SideEffect { state.bars = resolved }
    val bars = resolved
    var popover by remember(layer.id) { mutableStateOf<String?>(null) }
    val cell = metrics.cell.dp; val pitch = metrics.pitch.dp; val half = (metrics.gap / 2).dp
    val inset = PaddingValues(half)
    val density = LocalDensity.current
    val rowPx = with(density) { pitch.roundToPx() }.toFloat()
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val drag = remember(layer.id) { AnimateDrag() }

    // ---- Edit plumbing ----------------------------------------------------------------------------------------
    var draft by remember { mutableStateOf<CompositionLayer?>(null) }
    val currentLayer by rememberUpdatedState(layer)
    /** Character offset / value edits refused by the pre-check: the tile shows the error, its popover the line. */
    var refused by remember(layer.id) { mutableStateOf<Pair<AnimatorProperty, String>?>(null) }
    val characterProblem = remember(text) { TextCharacterPreparation.problem(text) }
    fun focus(address: AnimatorTrackAddress) { state.focus = address }
    fun fail(t: Throwable) { onMessage(t.message ?: "Animator edit failed") }
    /** The pre-check for [guard] (a Character property): refuse a result whose reachable characters exceed the caps. */
    fun allowed(guard: AnimatorProperty?, result: TextProperties): Boolean {
        if (guard == null) return true
        val problem = TextCharacterPreparation.problem(result) ?: run { if (refused?.first == guard) refused = null; return true }
        refused = guard to problem; onMessage(problem); return false
    }
    /** One undoable action (structure, choice, toggle, typed value). */
    fun commit(guard: AnimatorProperty? = null, build: (TextProperties) -> List<AnimatorEdit>) {
        if (finishing) return
        runCatching {
            val edits = build(currentLayer.text)
            if (edits.isNotEmpty() && allowed(guard, TextAnimatorEdits.payload(currentLayer, frameRate, edits))) onAction(TextAnimatorEdits.action(currentLayer.id, edits))
        }.onFailure(::fail)
    }
    fun guardOf(address: AnimatorTrackAddress) = address.property?.takeIf { it in CHARACTER_PROPERTIES }
    fun set(address: AnimatorTrackAddress, value: Float = 0f, y: Float? = null, argb: Int? = null, seed: Long? = null) =
        commit(guardOf(address)) { listOf(TextAnimatorEdits.set(it, address, local, value, y, argb, seed)) }
    /** Drag session: begin once on the captured layer, previews on that base, then finish (one undo) or cancel. */
    fun begin(address: AnimatorTrackAddress?) {
        if (finishing || draft != null) return
        address?.let(::focus); draft = currentLayer; onValueBegin()
    }
    fun preview(guard: AnimatorProperty? = null, build: (TextProperties) -> AnimatorEdit) {
        val base = draft ?: return
        runCatching { TextAnimatorEdits.payload(base, frameRate, listOf(build(base.text))) }
            .onSuccess { if (allowed(guard, it)) onPayloadPreview(it) }.onFailure(::fail)
    }
    fun finish() { if (draft != null) { draft = null; onFinish() } }
    fun cancel() { if (draft != null) { draft = null; onCancel() } }
    fun close() { cancel(); popover = null }
    DisposableEffect(layer.id) { onDispose { if (draft != null) onCancel() } }
    BackHandler(enabled = state.subPage != null) { cancel(); state.subPage = null }

    fun badge(address: AnimatorTrackAddress) = KeyBadges.state(TextAnimatorEdits.keyTimes(layer, address), local)
    fun key(address: AnimatorTrackAddress) = "f:" + AnimateAddressCodec.save(address).joinToString(":")

    // ---- Slider popover pieces shared by vector / transform fields --------------------------------------------
    @Composable fun ErrorLine(message: String) {
        Row(Modifier.widthIn(max = 280.dp).padding(horizontal = 8.dp, vertical = 4.dp).testTag("animate-character-error"),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(InspectorIcons.icon("WARNING"), null, Modifier.size(InspectorIcons.INLINE), tint = TextPrimary)
            Text(message, Modifier.padding(start = 6.dp), color = TextPrimary, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    @Composable fun VectorSliders(address: AnimatorTrackAddress, limit: PropertyLimit, label: String, v: FloatArray, default: Float) {
        var base by remember { mutableStateOf(v) }
        SliderPopoverContent(limit, v[0], false, "$label X", onReset = { set(address, default, v[1]) },
            onBegin = { base = v; begin(address) }, onPreview = { x -> preview { t -> TextAnimatorEdits.set(t, address, local, limit.hard(x), base[1]) } },
            onFinish = ::finish, onCancel = ::cancel, onTyped = { x -> set(address, limit.hard(x), v[1]) }, enabled = !finishing)
        SliderPopoverContent(limit, v[1], false, "$label Y", onReset = { set(address, v[0], default) },
            onBegin = { base = v; begin(address) }, onPreview = { y -> preview { t -> TextAnimatorEdits.set(t, address, local, base[0], limit.hard(y)) } },
            onFinish = ::finish, onCancel = ::cancel, onTyped = { y -> set(address, v[0], limit.hard(y)) }, enabled = !finishing)
    }
    /** Scale as sliders with the Transform tab's link: linked = one Scale slider keeping the X:Y ratio; unlinked = X and Y. */
    @Composable fun ScaleSliders(address: AnimatorTrackAddress, v: FloatArray) {
        val limit = PropertyLimits.LAYER_SCALE
        var base by remember { mutableStateOf(v) }
        val linked = state.linkScale
        fun linkedY(b: FloatArray, x: Float) = ScaleRulerMath.linkedY(b[0] / 100f, b[1] / 100f, x / 100f) * 100f
        val link: @Composable RowScope.() -> Unit = {
            InspectorIconButton(InspectorIcons.icon(if (linked) "LINK" else "LINK_OFF"), if (linked) "Unlink width and height" else "Link width and height",
                Modifier.testTag("animate-scale-link"), selected = linked) { state.linkScale = !linked }
        }
        SliderPopoverContent(limit, v[0], false, if (linked) "Scale" else "Scale X", onReset = { set(address, 100f, if (linked) 100f else v[1]) },
            onBegin = { base = v; begin(address) },
            onPreview = { x -> preview { t -> TextAnimatorEdits.set(t, address, local, limit.hard(x), if (linked) limit.hard(linkedY(base, x)) else base[1]) } },
            onFinish = ::finish, onCancel = ::cancel, onTyped = { x -> set(address, limit.hard(x), if (linked) limit.hard(linkedY(v, x)) else v[1]) },
            enabled = !finishing, extras = if (linked) link else null)
        if (!linked) SliderPopoverContent(limit, v[1], false, "Scale Y", onReset = { set(address, v[0], 100f) },
            onBegin = { base = v; begin(address) }, onPreview = { y -> preview { t -> TextAnimatorEdits.set(t, address, local, base[0], limit.hard(y)) } },
            onFinish = ::finish, onCancel = ::cancel, onTyped = { y -> set(address, v[0], limit.hard(y)) }, enabled = !finishing, extras = link)
    }

    /** Controls: Pad, dial, rulers — the tile opens the Transform tab's own control in place of the list. */
    @Composable fun TransformTile(address: AnimatorTrackAddress, spec: AnimateFieldSpec, modifier: Modifier, testTag: String, onLong: (() -> Unit)?,
        menu: @Composable () -> Unit) {
        val caption = when (spec.editor) {
            AnimateEditor.ROTATION -> AnimateCaption.scalar(PropertyLimits.ROTATION, TextAnimatorEdits.value(text, address, local))
            else -> TextAnimatorEdits.vector(text, address, local).let { AnimateCaption.vector(requireNotNull(spec.limit), it[0], it[1]) }
        }
        InspectorTile(InspectorIcons.icon(spec.icon), "${spec.label}, open ${spec.label.lowercase()} pad, dial or rulers", modifier, active = state.focus == address,
            enabled = !finishing, caption = caption, testTag = testTag, fill = inset, badge = badge(address), onLongClick = onLong, popover = menu) {
            focus(address); popover = null; state.subPage = requireNotNull(address.property).name
        }
    }

    // ---- Field tile ---------------------------------------------------------------------------------------------
    @Composable fun FieldTile(address: AnimatorTrackAddress, modifier: Modifier, testTag: String, onLong: (() -> Unit)? = null,
        menu: @Composable () -> Unit = {}) {
        val spec = AnimateFields.spec(address)
        val focused = state.focus == address
        val open = popover == key(address)
        val b = badge(address)
        val fill = inset
        val character = address.property?.takeIf { it in CHARACTER_PROPERTIES }
        val error = character?.let { p -> refused?.takeIf { it.first == p }?.second ?: characterProblem }
        val transformSliders = controls == AnimateControls.SLIDERS
        when (spec.editor) {
            AnimateEditor.SLIDER, AnimateEditor.ROTATION -> {
                if (spec.editor == AnimateEditor.ROTATION && !transformSliders) { TransformTile(address, spec, modifier, testTag, onLong, menu); return }
                val limit = requireNotNull(spec.limit)
                val v = TextAnimatorEdits.value(text, address, local)
                val caption = if (address.property == AnimatorProperty.CHARACTER_VALUE) AnimateCaption.character(v) else AnimateCaption.scalar(limit, v)
                InspectorTile(InspectorIcons.icon(if (error != null) "WARNING" else spec.icon), "${spec.label}, ${PropertyLimits.format(limit, v)} ${limit.unit}".trim(), modifier,
                    active = open || focused, enabled = !finishing, caption = caption, testTag = testTag, fill = fill, badge = b, onLongClick = onLong,
                    state = error?.let { "Error: $it" }, popover = {
                        menu()
                        AnchoredPopover(open, ::close, "animate-popover-$testTag") {
                            val rotation = spec.editor == AnimateEditor.ROTATION
                            SliderPopoverContent(limit, v, false, spec.label, onReset = { commit(character) { listOf(TextAnimatorEdits.reset(it, address, local)) } },
                                onBegin = { begin(address) }, onPreview = { x -> preview(character) { t -> TextAnimatorEdits.set(t, address, local, limit.hard(x)) } },
                                onFinish = ::finish, onCancel = ::cancel, onTyped = { x -> set(address, limit.hard(x)) }, enabled = !finishing,
                                // Rotation keeps the dial's turns notation (n x remainder) as a read-out; slider and typing are degrees.
                                extras = if (rotation) ({ Text("${formatRotation(v)}°", Modifier.padding(horizontal = 8.dp).testTag("animate-rotation-turns"),
                                    color = TextSecondary, style = MaterialTheme.typography.labelMedium, maxLines = 1) }) else null)
                            if (error != null) ErrorLine(error)
                        }
                    }) { focus(address); popover = key(address) }
            }
            AnimateEditor.VECTOR, AnimateEditor.POSITION, AnimateEditor.SCALE -> {
                if (spec.editor != AnimateEditor.VECTOR && !transformSliders) { TransformTile(address, spec, modifier, testTag, onLong, menu); return }
                val limit = requireNotNull(spec.limit)
                val v = TextAnimatorEdits.vector(text, address, local)
                InspectorTile(InspectorIcons.icon(spec.icon), "${spec.label}, ${PropertyLimits.format(limit, v[0])}, ${PropertyLimits.format(limit, v[1])} ${limit.unit}", modifier,
                    active = open || focused, enabled = !finishing, caption = AnimateCaption.vector(limit, v[0], v[1]), testTag = testTag, fill = fill, badge = b,
                    onLongClick = onLong, popover = {
                        menu()
                        AnchoredPopover(open, ::close, "animate-popover-$testTag") {
                            if (spec.editor == AnimateEditor.SCALE) ScaleSliders(address, v)
                            else VectorSliders(address, limit, spec.label, v, TextAnimatorEdits.defaultVector(requireNotNull(address.property)))
                        }
                    }) { focus(address); popover = key(address) }
            }
            AnimateEditor.COLOUR -> {
                val argb = TextAnimatorEdits.colour(text, address, local)
                InspectorTile(null, "${spec.label} #${"%08X".format(argb)}", modifier, active = open || focused, enabled = !finishing, testTag = testTag, fill = fill, badge = b,
                    onLongClick = onLong, iconContent = { AnimateSwatch(argb, address.property == AnimatorProperty.STROKE_COLOUR) }, popover = {
                        menu()
                        AnchoredPopover(open, ::close, "animate-popover-$testTag") {
                            FluxxColourPicker(ColourPickerVariant.COMPACT, argb, onBegin = { begin(address) },
                                onPreview = { c -> preview { t -> TextAnimatorEdits.set(t, address, local, argb = c) } }, onFinish = ::finish, onCancel = ::cancel,
                                modifier = Modifier.testTag("animate-colour-picker"), name = spec.label.lowercase(), swatches = paletteSwatches(palette, TEXT_DEFAULT_SWATCHES),
                                onReset = { commit { listOf(TextAnimatorEdits.reset(it, address, local)) } },
                                onSwatch = { c -> set(address, argb = TextColourEdit.swatch(argb, c)) })
                        }
                    }) { focus(address); popover = key(address) }
            }
            AnimateEditor.CHOICE -> {
                val v = TextAnimatorEdits.value(text, address, local)
                val current = spec.choices.firstOrNull { it.value == v }
                InspectorTile(InspectorIcons.icon(AnimateFields.icon(spec, v)), "${spec.label}: ${current?.label ?: v}", modifier, active = open || focused,
                    enabled = !finishing, caption = current?.short, testTag = testTag, fill = fill, badge = b, onLongClick = onLong, popover = {
                        menu()
                        AnchoredPopover(open, ::close, "animate-choice-$testTag") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                spec.choices.forEach { c ->
                                    InspectorIconButton(InspectorIcons.icon(c.icon), "${spec.label}: ${c.label}", Modifier.testTag("animate-choice-${c.icon}"), selected = c.value == v) {
                                        popover = null; set(address, c.value)
                                    }
                                }
                            }
                        }
                    }) { focus(address); popover = key(address) }
            }
            AnimateEditor.TOGGLE -> {
                val v = TextAnimatorEdits.value(text, address, local)
                val units = address.field == SelectorField.UNITS
                val on = v != 0f
                InspectorTile(InspectorIcons.icon(AnimateFields.icon(spec, v)), spec.label, modifier, active = if (units) focused else on, enabled = !finishing,
                    caption = if (units) spec.choices.firstOrNull { it.value == v }?.short else null, testTag = testTag, fill = fill, badge = b,
                    state = if (units) spec.choices.firstOrNull { it.value == v }?.label else if (on) "On" else "Off", onLongClick = onLong, popover = menu) {
                    focus(address); set(address, if (on) 0f else 1f)
                }
            }
            AnimateEditor.SEED -> {
                val seed = TextAnimatorEdits.seedValue(text, address, local)
                InspectorTile(InspectorIcons.icon("SEED"), "${spec.label}, ${java.lang.Long.toUnsignedString(seed)}", modifier, active = open || focused, enabled = !finishing,
                    caption = AnimateCaption.seed(seed), testTag = testTag, fill = fill, badge = b, onLongClick = onLong, popover = {
                        menu()
                        AnchoredPopover(open, ::close, "animate-seed-$testTag") {
                            var typing by remember { mutableStateOf(false) }
                            Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                                InspectorIconButton(InspectorIcons.icon("SEED"), "New random seed", Modifier.testTag("animate-seed-new")) {
                                    set(address, seed = java.util.concurrent.ThreadLocalRandom.current().nextLong())
                                }
                                InspectorIconButton(InspectorIcons.icon("RESET"), "Reset seed to 0") { set(address, seed = 0L) }
                                if (typing) InspectorValueField(java.lang.Long.toUnsignedString(seed), spec.label, "", { t ->
                                    typing = false
                                    runCatching { java.lang.Long.parseUnsignedLong(t.trim()) }.onSuccess { set(address, seed = it) }.onFailure { onMessage("Enter a whole number from 0 to 18446744073709551615") }
                                }, { typing = false }, keyboardType = KeyboardType.Number)
                                else TextButton(onClick = { typing = true }, modifier = Modifier.heightIn(min = 40.dp).semantics { contentDescription = "Seed ${java.lang.Long.toUnsignedString(seed)}; tap to type" }) {
                                    Text(java.lang.Long.toUnsignedString(seed), color = TextPrimary, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                }
                            }
                        }
                    }) { focus(address); popover = key(address) }
            }
        }
    }

    Box(modifier.testTag("animate-page")) {
        // Persistent faded Animate glyph behind the page: monochrome, low contrast, no semantics, never takes touches
        // and never moves layout (tiles draw their own surfaces on top, so they stay fully legible).
        Icon(InspectorIcons.icon("ANIMATOR"), null, Modifier.align(Alignment.Center).size(minOf(metrics.body, metrics.width).times(.6f).dp)
            .testTag("animate-glyph"), tint = TextPrimary.copy(alpha = .05f))

        // ---- Sub-page: the Transform tab's own Position / Rotation / Scale controls (Controls: Pad, dial, rulers) ----
        val sub = state.subPage?.let { name -> runCatching { AnimatorProperty.valueOf(name) }.getOrNull() }
        val subAnimator = state.focus?.let { f -> text.animators.firstOrNull { it.id == f.animatorId } }
        if (sub != null && controls == AnimateControls.PAD && subAnimator != null && subAnimator.properties.any { it.property == sub }) {
            val address = TextAnimatorEdits.property(subAnimator.id, sub)
            AnimateSubPage(address, sub, subAnimator.name, text, local, metrics, compositionWidth, compositionHeight, state.linkScale, { state.linkScale = it },
                finishing, badge(address), onBack = { cancel(); state.subPage = null }, onBegin = { begin(address) }, onPreview = { x, y -> preview { t -> TextAnimatorEdits.set(t, address, local, x, y) } },
                onFinish = ::finish, onCancel = ::cancel, onSet = { x, y -> set(address, x, y) })
            return@Box
        } else if (state.subPage != null) SideEffect { state.subPage = null }

        val rows = AnimateRows.build(text, bars, local, metrics.columns)
        val keys = rows.map { it.key }
        fun scrollPos() = list.firstVisibleItemIndex * rowPx + list.firstVisibleItemScrollOffset

        // ---- Grip reorder: the timeline hierarchy rule (absolute pointer vs. slot centres at drag start) -----------
        fun dragStart(id: Long, parent: Long?, siblings: List<Long>, barKey: (Long) -> String) {
            if (finishing || draft != null) return
            drag.parent = parent; drag.order = siblings
            drag.centres = siblings.associateWith { s -> keys.indexOf(barKey(s)) * rowPx + rowPx / 2 }
            drag.startCentre = drag.centres.getValue(id); drag.startTop = drag.startCentre - rowPx / 2
            drag.scrollAtStart = scrollPos(); drag.dy = 0f
            drag.start = siblings.indexOf(id); drag.target = drag.start
            drag.id = id; popover = null
            begin(null)
            scope.launch {
                // Edge scroll while the finger rests near the top or bottom of the body.
                while (drag.id == id) {
                    val viewport = list.layoutInfo.viewportSize.height.toFloat()
                    val y = drag.startCentre + drag.dy + (scrollPos() - drag.scrollAtStart) - scrollPos()
                    val step = when { y < rowPx -> -rowPx / 6; y > viewport - rowPx -> rowPx / 6; else -> 0f }
                    if (step != 0f) list.scrollBy(step)
                    delay(16)
                }
            }
        }
        fun dragMove(id: Long, amount: Float, edit: (Int) -> AnimatorEdit) {
            if (drag.id != id) return
            drag.dy += amount
            val y = drag.startCentre + drag.dy + (scrollPos() - drag.scrollAtStart)
            val target = AnimateReorder.target(drag.order, id, drag.centres, y)
            if (target != drag.target) { drag.target = target; preview { edit(target) } }
        }
        fun dragEnd(id: Long, commit: Boolean) {
            if (drag.id != id) return
            drag.id = null
            if (commit && drag.target != drag.start) finish() else cancel()
        }
        fun Modifier.grip(id: Long, parent: Long?, siblings: List<Long>, barKey: (Long) -> String, edit: (Int) -> AnimatorEdit) = pointerInput(id, siblings) {
            detectDragGesturesAfterLongPress(onDragStart = { dragStart(id, parent, siblings, barKey) },
                onDrag = { change, amount -> change.consume(); dragMove(id, amount.y, edit) },
                onDragEnd = { dragEnd(id, true) }, onDragCancel = { dragEnd(id, false) })
        }
        /** Visual offset of a row in the dragged block: the block follows the finger from where it started. */
        fun blockOffset(row: AnimateRow): Float {
            val id = drag.id ?: return 0f
            val inBlock = if (drag.parent == null) row.animatorId == id else row.selectorId == id
            if (!inBlock) return 0f
            val barIndex = keys.indexOf(if (drag.parent == null) "a:$id" else "s:$id").coerceAtLeast(0)
            return drag.startTop + drag.dy + (scrollPos() - drag.scrollAtStart) - barIndex * rowPx
        }
        DisposableEffect(layer.id) { onDispose { drag.id = null } }

        // ---- Bars ---------------------------------------------------------------------------------------------
        /** Collapsible bar: grip, chevron, name, duplicate, eye. Swipe sideways to delete (undo restores). */
        @Composable fun Bar(label: String, index: Int, expanded: Boolean, enabledAddress: AnimatorTrackAddress, tag: String, grip: Modifier,
            onToggle: () -> Unit, onDuplicate: (() -> Unit)?, onDelete: () -> Unit, onLong: (() -> Unit)?, menu: @Composable () -> Unit = {}) {
            val on = TextAnimatorEdits.value(text, enabledAddress, local) != 0f
            var swipe by remember { mutableFloatStateOf(0f) }
            var swiping by remember { mutableStateOf(false) }
            val shown by animateFloatAsState(if (swiping) swipe else 0f, label = "swipe")
            val chevron by animateFloatAsState(if (expanded) 0f else -90f, label = "chevron")
            var width by remember { mutableFloatStateOf(1f) }
            val barShape = RoundedCornerShape(8.dp)
            Box(Modifier.fillMaxWidth().height(pitch).onSizeChanged { width = it.width.toFloat() }) {
                // Revealed under a swipe: the delete glyph on the side the bar leaves.
                Row(Modifier.matchParentSize().padding(inset).clip(barShape).background(Border), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (shown > 0) Arrangement.Start else Arrangement.End) {
                    Icon(InspectorIcons.icon("DELETE"), null, Modifier.padding(horizontal = 12.dp).size(InspectorIcons.INLINE), tint = TextPrimary)
                }
                // The drawn bar is inset by half a gap like the tiles; its controls use the whole row pitch as touch targets.
                Row(Modifier.matchParentSize().offset { IntOffset(shown.roundToInt(), 0) }
                    .drawBehind {
                        val h = half.toPx()
                        val r = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx())
                        drawRoundRect(SurfaceHigh, androidx.compose.ui.geometry.Offset(h, h), androidx.compose.ui.geometry.Size(size.width - 2 * h, size.height - 2 * h), r)
                        if (expanded) drawRoundRect(TextSecondary.copy(alpha = .5f), androidx.compose.ui.geometry.Offset(h, h),
                            androidx.compose.ui.geometry.Size(size.width - 2 * h, size.height - 2 * h), r, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                    }
                    .pointerInput(tag) {
                        detectHorizontalDragGestures(onDragStart = { swiping = true; swipe = 0f },
                            onHorizontalDrag = { change, amount -> change.consume(); swipe += amount },
                            onDragEnd = { if (kotlin.math.abs(swipe) > width * .4f) onDelete(); swiping = false; swipe = 0f },
                            onDragCancel = { swiping = false; swipe = 0f })
                    }
                    .testTag(tag), verticalAlignment = Alignment.CenterVertically) {
                    Box(grip.size(pitch, pitch).semantics { contentDescription = "Hold and drag to reorder $label" }.testTag("$tag-grip"),
                        contentAlignment = Alignment.Center) {
                        Icon(InspectorIcons.icon("GRIP"), null, Modifier.size(InspectorIcons.INLINE), tint = TextSecondary)
                    }
                    Row(Modifier.weight(1f).fillMaxHeight().combinedClickable(enabled = !finishing, onClick = onToggle, onLongClick = onLong)
                        .semantics(mergeDescendants = true) { contentDescription = label; stateDescription = (if (expanded) "Expanded" else "Collapsed") + if (on) "" else ", off"
                            customActions = listOf(CustomAccessibilityAction("Delete") { onDelete(); true }) }
                        .testTag("$tag-toggle"), verticalAlignment = Alignment.CenterVertically) {
                        Icon(InspectorIcons.icon("EXPANDED"), null, Modifier.size(InspectorIcons.INLINE).graphicsLayer { rotationZ = chevron }, tint = TextSecondary)
                        Text(label, Modifier.padding(start = 6.dp).weight(1f), color = if (on) TextPrimary else TextSecondary, style = MaterialTheme.typography.labelLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        menu()
                    }
                    if (onDuplicate != null) InspectorTile(InspectorIcons.icon("DUPLICATE"), "Duplicate $label", Modifier.size(pitch, pitch), enabled = !finishing,
                        testTag = "$tag-duplicate", fill = inset) { onDuplicate() }
                    InspectorTile(InspectorIcons.icon(if (on) "EYE" else "EYE_OFF"), if (on) "Turn $label off" else "Turn $label on", Modifier.size(pitch, pitch),
                        active = state.focus == enabledAddress, enabled = !finishing, state = if (on) "On" else "Off", testTag = "$tag-eye", fill = inset,
                        badge = badge(enabledAddress)) { focus(enabledAddress); set(enabledAddress, if (on) 0f else 1f) }
                }
            }
        }
        /** Row label with its own add control ("Property [+]", "Selector [+]"). */
        @Composable fun LabelRow(label: String, tag: String, content: @Composable RowScope.() -> Unit) {
            Row(Modifier.fillMaxWidth().height(pitch).padding(start = 12.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f).semantics { heading() }, color = TextSecondary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                content()
            }
        }
        @Composable fun HeaderRow(label: String, caption: String?, open: Boolean, tag: String, onToggle: () -> Unit) {
            val chevron by animateFloatAsState(if (open) 0f else -90f, label = "chevron")
            Row(Modifier.fillMaxWidth().height(pitch).padding(start = 12.dp).clickable(enabled = !finishing, onClick = onToggle)
                .semantics(mergeDescendants = true) { contentDescription = label; stateDescription = if (open) "Expanded" else "Collapsed"; role = Role.Button }
                .testTag(tag), verticalAlignment = Alignment.CenterVertically) {
                Icon(InspectorIcons.icon("EXPANDED"), null, Modifier.size(InspectorIcons.INLINE).graphicsLayer { rotationZ = chevron }, tint = TextSecondary)
                Text(label, Modifier.padding(start = 6.dp).weight(1f), color = TextSecondary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                if (caption != null) Text(caption, Modifier.padding(end = 8.dp), color = TextSecondary, fontSize = 11.sp, maxLines = 1)
            }
        }

        LazyColumn(Modifier.fillMaxSize().testTag("animate-list"), state = list, userScrollEnabled = drag.id == null) {
            items(rows, key = { it.key }) { row ->
                val dragged = blockOffset(row) != 0f || (drag.id != null && (row.animatorId == drag.id && drag.parent == null || row.selectorId == drag.id))
                val itemModifier = (if (dragged) Modifier else Modifier.animateItem())
                    .zIndex(if (dragged) 1f else 0f).graphicsLayer { translationY = blockOffset(row) }
                Box(itemModifier) {
                    when (row) {
                        AnimateRow.Add -> {
                            val empty = text.animators.isEmpty()
                            Box(Modifier.fillMaxWidth().then(if (empty) Modifier.fillParentMaxHeight() else Modifier.height(pitch)), contentAlignment = Alignment.Center) {
                                if (text.animators.size < TextAnimatorEdits.MAX_ANIMATORS) InspectorTile(InspectorIcons.icon("ADD"), "Add animator",
                                    Modifier.size(cell, pitch), hollow = true, enabled = !finishing, state = "${text.animators.size} of ${TextAnimatorEdits.MAX_ANIMATORS}",
                                    testTag = "animate-add-animator", fill = inset) {
                                    commit { t -> listOf(TextAnimatorEdits.addAnimator(t).also { put ->
                                        state.bars = state.bars.expandAnimator(put.animator.id).expandSelector(put.animator.selectors.first().id) }) }
                                }
                            }
                        }
                        is AnimateRow.AnimatorBar -> {
                            val a = row.animator
                            val tag = "animate-animator-${row.index}"
                            val menuKey = "menu-anim:${a.id}"
                            Bar(a.name, row.index, row.expanded, TextAnimatorEdits.animatorEnabled(a.id), tag,
                                Modifier.grip(a.id, null, text.animators.map { it.id }, { "a:$it" }) { target -> AnimatorEdit.Reorder(a.id, target) },
                                onToggle = { popover = null; state.bars = state.bars.toggleAnimator(a.id) },
                                onDuplicate = if (text.animators.size < TextAnimatorEdits.MAX_ANIMATORS) ({ commit { t -> TextAnimatorEdits.duplicateAnimator(t, a.id) } }) else null,
                                onDelete = { commit { listOf(AnimatorEdit.Remove(a.id)) } },
                                onLong = { popover = menuKey }) {
                                DropdownMenu(popover == menuKey, { popover = null }, Modifier.testTag("animate-animator-menu")) {
                                    DropdownMenuItem(text = { Text("Rename…") }, onClick = { popover = "rename:${a.id}" })
                                }
                                AnchoredPopover(popover == "rename:${a.id}", { popover = null }, "animate-rename") {
                                    InspectorValueField(a.name, "Animator name", "", { name -> popover = null; commit { t -> listOfNotNull(TextAnimatorEdits.rename(t, a.id, name)) } },
                                        { popover = null }, Modifier.widthIn(min = 200.dp), keyboardType = KeyboardType.Text)
                                }
                            }
                        }
                        is AnimateRow.PropertyLabel -> {
                            val a = requireNotNull(text.animators.firstOrNull { it.id == row.animatorId })
                            LabelRow("Property", "animate-property-row") {
                                InspectorTile(InspectorIcons.icon("ADD"), "Add property", Modifier.size(cell, pitch), hollow = true, active = popover == "add-property:${a.id}",
                                    enabled = !finishing && !row.full, testTag = "animate-add-property", fill = inset, popover = {
                                        AnchoredPopover(popover == "add-property:${a.id}", { popover = null }, "animate-add-property-popover") {
                                            AddPropertyContent(a) { p ->
                                                popover = null
                                                val added = a.properties.any { it.property == p }
                                                if (!added) commit(p.takeIf { it in CHARACTER_PROPERTIES }) { t -> listOfNotNull(TextAnimatorEdits.addProperty(t, a.id, p)) }
                                                focus(TextAnimatorEdits.property(a.id, p))
                                                if (!state.bars.groupOpen(a.id, AnimateFields.pageGroup(p))) state.bars = state.bars.toggleGroup(a.id, AnimateFields.pageGroup(p))
                                            }
                                        }
                                    }) { popover = "add-property:${a.id}" }
                            }
                        }
                        is AnimateRow.GroupHeader -> HeaderRow(row.group, "${row.count}", row.open, "animate-group-${row.group}") {
                            state.bars = state.bars.toggleGroup(requireNotNull(row.animatorId), row.group)
                        }
                        is AnimateRow.PropertyTiles -> Row(Modifier.fillMaxWidth().height(pitch)) {
                            val id = requireNotNull(row.animatorId)
                            row.properties.forEach { p ->
                                val address = TextAnimatorEdits.property(id, p)
                                val menuKey = "menu-prop:$id:${p.name}"
                                FieldTile(address, Modifier.width(cell).fillMaxHeight(), "animate-property-${p.name}", onLong = { focus(address); popover = menuKey }) {
                                    DropdownMenu(popover == menuKey, { popover = null }, Modifier.testTag("animate-property-menu")) {
                                        DropdownMenuItem(text = { Text("Reset ${AnimateFields.property(p).label} value") }, onClick = {
                                            popover = null; commit { t -> listOf(TextAnimatorEdits.reset(t, address, local)) } })
                                        DropdownMenuItem(text = { Text("Remove ${AnimateFields.property(p).label}") }, onClick = {
                                            popover = null; commit { listOf(AnimatorEdit.RemoveProperty(id, p)) } })
                                    }
                                }
                            }
                        }
                        is AnimateRow.SelectorLabel -> {
                            val id = requireNotNull(row.animatorId)
                            LabelRow("Selector", "animate-selector-row") {
                                InspectorTile(InspectorIcons.icon("ADD"), "Add selector", Modifier.size(cell, pitch), hollow = true, active = popover == "add-selector:$id",
                                    enabled = !finishing && !row.full, testTag = "animate-add-selector", fill = inset, popover = {
                                        AnchoredPopover(popover == "add-selector:$id", { popover = null }, "animate-add-selector-popover") {
                                            Row {
                                                TextSelectorKind.entries.forEach { k ->
                                                    InspectorIconButton(InspectorIcons.icon(k.name), "Add ${if (k == TextSelectorKind.RANGE) "Range" else "Wiggly"} selector", Modifier.testTag("animate-add-${k.name}")) {
                                                        popover = null
                                                        commit { t -> listOf(TextAnimatorEdits.addSelector(t, id, k).also { state.bars = state.bars.expandSelector(it.selector.id) }) }
                                                    }
                                                }
                                            }
                                        }
                                    }) { popover = "add-selector:$id" }
                            }
                        }
                        is AnimateRow.SelectorBar -> {
                            val id = requireNotNull(row.animatorId); val s = row.selector
                            val a = requireNotNull(text.animators.firstOrNull { it.id == id })
                            val kind = TextAnimatorEdits.kind(s)
                            val name = "${if (kind == TextSelectorKind.RANGE) "Range" else "Wiggly"} Selector ${row.index + 1}"
                            Box(Modifier.padding(start = 12.dp)) {
                                Bar(name, row.index, row.expanded, TextAnimatorEdits.selectorField(id, s.id, SelectorField.ENABLED), "animate-selector-${row.index}",
                                    Modifier.grip(s.id, id, a.selectors.map { it.id }, { "s:$it" }) { target -> AnimatorEdit.ReorderSelector(id, s.id, target) },
                                    onToggle = { popover = null; state.bars = state.bars.toggleSelector(s.id) },
                                    onDuplicate = if (a.selectors.size < TextAnimatorEdits.MAX_SELECTORS) ({ commit { t -> TextAnimatorEdits.duplicateSelector(t, id, s.id) } }) else null,
                                    onDelete = { commit { listOf(AnimatorEdit.RemoveSelector(id, s.id)) } }, onLong = null)
                            }
                        }
                        is AnimateRow.AdvancedHeader -> Box(Modifier.padding(start = 12.dp)) {
                            HeaderRow("Advanced", null, row.open, "animate-advanced-${row.selector.id}") { state.bars = state.bars.toggleAdvanced(row.selector.id) }
                        }
                        is AnimateRow.SelectorTiles -> Row(Modifier.fillMaxWidth().height(pitch)) {
                            val id = requireNotNull(row.animatorId); val s = row.selector
                            val tileWidth = cell
                            row.cells.forEach { c ->
                                val m = Modifier.width(tileWidth).fillMaxHeight()
                                when {
                                    c.hint -> HiddenTile(id, s, m, inset, finishing, popover == "hidden:${s.id}", { popover = if (popover == "hidden:${s.id}") null else "hidden:${s.id}" },
                                        { popover = null }) { popover = null; commit { listOf(AnimateHidden.reset(id, s)) } }
                                    c.field == null -> FieldTile(TextAnimatorEdits.seed(id, s.id), m, "animate-field-SEED")
                                    else -> FieldTile(TextAnimatorEdits.selectorField(id, s.id, c.field), m, "animate-field-${c.field.name}")
                                }
                            }
                        }
                        is AnimateRow.GroupingRow -> LabelRow("Grouping", "animate-grouping-row") {
                            FieldTile(TextAnimatorEdits.grouping(requireNotNull(row.animatorId)), Modifier.size(cell, pitch), "animate-grouping")
                        }
                    }
                }
            }
        }
    }
}

/** Add-property popover: Transform, Colour (+ collapsed Colour shift), Text, collapsed Advanced. Added ones show selected. */
@Composable private fun AddPropertyContent(animator: TextAnimator, onPick: (AnimatorProperty) -> Unit) {
    val open = remember { mutableStateMapOf<String, Boolean>() }
    @Composable fun Buttons(list: List<AnimatorProperty>) {
        list.chunked(7).forEach { line ->
            Row {
                line.forEach { p ->
                    val added = animator.properties.any { it.property == p }
                    val spec = AnimateFields.property(p)
                    InspectorIconButton(InspectorIcons.icon(spec.icon), if (added) "${spec.label}, added; show it" else "Add ${spec.label}",
                        Modifier.testTag("animate-add-${p.name}"), selected = added) { onPick(p) }
                }
            }
        }
    }
    @Composable fun Header(name: String, collapsible: Boolean, expanded: Boolean) {
        Row(Modifier.padding(start = 6.dp, top = 2.dp).then(if (collapsible) Modifier.clickable { open[name] = !expanded }
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" } else Modifier).testTag("animate-add-group-$name"),
            verticalAlignment = Alignment.CenterVertically) {
            if (collapsible) Icon(InspectorIcons.icon("EXPANDED"), null, Modifier.size(14.dp).graphicsLayer { rotationZ = if (expanded) 0f else -90f }, tint = TextSecondary)
            Text(name, Modifier.semantics { heading() }, color = TextSecondary, fontSize = 10.sp, lineHeight = 12.sp)
        }
    }
    Column(Modifier.testTag("animate-add-property-groups")) {
        AnimateFields.GROUPS.forEach { g ->
            val expanded = open[g.name] ?: !g.collapsed
            Header(g.name, g.collapsed, expanded)
            if (expanded) Buttons(g.properties)
            g.sub?.let { sub ->
                val subOpen = open[sub.name] ?: !sub.collapsed
                Header(sub.name, true, subOpen)
                if (subOpen) Buttons(sub.properties)
            }
        }
    }
}

/** The one hint for hidden selector fields that hold non-default values (the engine still applies them). */
@Composable private fun HiddenTile(animatorId: Long, s: TextRangeSelector, modifier: Modifier, fill: PaddingValues, finishing: Boolean,
    open: Boolean, onClick: () -> Unit, onDismiss: () -> Unit, onReset: () -> Unit) {
    val hidden = AnimateHidden.active(s)
    val summary = hidden.joinToString(" · ") { f -> "${AnimateFields.selector(f).label} ${PropertyLimits.format(requireNotNull(AnimateFields.selector(f).limit),
        s.tracks[f.ordinal].staticValue)}%" + if (s.tracks[f.ordinal].isAnimated) " (keyed)" else "" }
    InspectorTile(InspectorIcons.icon("WARNING"), "Hidden values: $summary", modifier, enabled = !finishing, caption = "${hidden.size}",
        testTag = "animate-field-HIDDEN", fill = fill, active = open, popover = {
            AnchoredPopover(open, onDismiss, "animate-hidden-popover") {
                Text("Not shown for this selector, still applied: $summary", Modifier.widthIn(max = 280.dp).padding(8.dp), color = TextPrimary,
                    style = MaterialTheme.typography.labelSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InspectorIconButton(InspectorIcons.icon("RESET"), "Reset hidden values", Modifier.testTag("animate-hidden-reset"), enabled = !finishing) { onReset() }
                }
            }
        }) { onClick() }
}

/** Colour property swatch: solid for fill, ring for stroke (real colours; chrome stays monochrome). */
@Composable private fun AnimateSwatch(argb: Int, ring: Boolean) {
    val shape = RoundedCornerShape(4.dp)
    Box(Modifier.size(InspectorIcons.INLINE).clip(shape).background(if (ring) Color.Transparent else Color(argb))
        .border(if (ring) 4.dp else 1.dp, if (ring) Color(argb) else TextPrimary, shape).border(1.dp, TextSecondary, shape))
}

/**
 * Position / Rotation / Scale of an animator, edited with the Transform tab's own controls (pad + X/Y boxes, dial,
 * log rulers) in place of the page body. Its header has ← (also system Back) and the property name.
 */
@Composable private fun AnimateSubPage(address: AnimatorTrackAddress, property: AnimatorProperty, animatorName: String, text: TextProperties, local: Long,
    metrics: AnimateMetrics, compositionWidth: Int, compositionHeight: Int, linked: Boolean, onLink: (Boolean) -> Unit, finishing: Boolean, badge: KeyBadge,
    onBack: () -> Unit, onBegin: () -> Unit, onPreview: (Float, Float?) -> Unit, onFinish: () -> Unit, onCancel: () -> Unit, onSet: (Float, Float?) -> Unit) {
    val spec = AnimateFields.property(property)
    val fontScale = LocalDensity.current.fontScale
    Column(Modifier.fillMaxSize().testTag("animate-subpage-${property.name}")) {
        Row(Modifier.fillMaxWidth().height(metrics.pitch.dp), verticalAlignment = Alignment.CenterVertically) {
            InspectorIconButton(InspectorIcons.icon("BACK"), "Back to animator properties", Modifier.testTag("animate-subpage-back")) { onBack() }
            Icon(InspectorIcons.icon(spec.icon), null, Modifier.size(InspectorIcons.INLINE), tint = TextSecondary)
            Text("${spec.label} · $animatorName", Modifier.weight(1f).padding(horizontal = 6.dp).semantics { heading() }, color = TextPrimary,
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            KeyBadgeGlyph(badge, TextSecondary)
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            when (property) {
                AnimatorProperty.ROTATION -> {
                    val v = TextAnimatorEdits.value(text, address, local)
                    Box(Modifier.size(SectionLayout.DIAL.dp).testTag("animate-rotation-dial")) {
                        RotationDialController(v, onBegin, { onPreview(PropertyLimits.ROTATION.hard(it), null) }, onFinish, onCancel,
                            { onSet(PropertyLimits.ROTATION.typed(it), null) }, valueHeight = SectionLayout.valueBox(fontScale).dp)
                    }
                }
                AnimatorProperty.SCALE -> {
                    val v = TextAnimatorEdits.vector(text, address, local)
                    Box(Modifier.fillMaxWidth().testTag("animate-scale")) {
                        ScaleController(v[0] / 100f, v[1] / 100f, linked, onBegin, { x, y -> onPreview(PropertyLimits.LAYER_SCALE.hard(x * 100f), PropertyLimits.LAYER_SCALE.hard(y * 100f)) },
                            onFinish, onCancel, { x, y -> onSet(PropertyLimits.LAYER_SCALE.typed(x * 100f), PropertyLimits.LAYER_SCALE.typed(y * 100f)) },
                            SectionLayout.ruler(fontScale)) {
                            InspectorIconButton(InspectorIcons.icon(if (linked) "LINK" else "LINK_OFF"), if (linked) "Unlink width and height" else "Link width and height",
                                selected = linked) { onLink(!linked) }
                        }
                    }
                }
                else -> {
                    val v = TextAnimatorEdits.vector(text, address, local)
                    val limit = PropertyLimits.ANIM_OFFSET
                    val spanX = compositionWidth.coerceAtLeast(1).toFloat(); val spanY = (if (compositionHeight > 0) compositionHeight else compositionWidth).coerceAtLeast(1).toFloat()
                    Row(horizontalArrangement = Arrangement.spacedBy(SectionLayout.GAP.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(112.dp), verticalArrangement = Arrangement.spacedBy(SectionLayout.GAP.dp)) {
                            CompactValueBox("X", PropertyLimits.format(limit, v[0]), "px", { onSet(limit.typed(it), v[1]) }, Modifier.fillMaxWidth(), height = SectionLayout.valueBox(fontScale).dp, large = true)
                            CompactValueBox("Y", PropertyLimits.format(limit, v[1]), "px", { onSet(v[0], limit.typed(it)) }, Modifier.fillMaxWidth(), height = SectionLayout.valueBox(fontScale).dp, large = true)
                        }
                        // The pad spans one composition width/height around the glyph's own place (centre = no offset).
                        Box(Modifier.size(SectionLayout.PAD.dp).testTag("animate-position-pad")) {
                            PositionTrackpad(.5f + v[0] / spanX, .5f + v[1] / spanY, onBegin,
                                { fx, fy -> onPreview(limit.hard((fx - .5f) * spanX), limit.hard((fy - .5f) * spanY)) }, onFinish, onCancel)
                        }
                    }
                }
            }
        }
    }
}
