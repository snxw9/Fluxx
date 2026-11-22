package com.fluxx.android.ui.inspector

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import com.fluxx.android.ui.common.colorpicker.*
import com.fluxx.android.ui.theme.*

/**
 * Transient Animate page selection (TEXT F3). Saved with the Edit Text panel, so it survives rotation and switching
 * between Character / Paragraph / Animate. Never document state, never an undo entry.
 */
internal class AnimatePageState(animatorId: MutableState<Long?>, selectorId: MutableState<Long?>, tab: MutableState<Int>,
    focus: MutableState<AnimatorTrackAddress?>, subPage: MutableState<String?>, linkScale: MutableState<Boolean>) {
    var animatorId by animatorId
    var selectorId by selectorId
    var tabIndex by tab
    var focus by focus
    /** Position / Rotation / Scale editor shown in place of the page body (the Transform tab's own controls). */
    var subPage by subPage
    var linkScale by linkScale
    val tab get() = AnimateTab.entries[tabIndex.coerceIn(0, AnimateTab.entries.lastIndex)]
}

@Composable internal fun rememberAnimatePageState(layerId: Long): AnimatePageState {
    val animator = androidx.compose.runtime.saveable.rememberSaveable(layerId) { mutableStateOf<Long?>(null) }
    val selector = androidx.compose.runtime.saveable.rememberSaveable(layerId) { mutableStateOf<Long?>(null) }
    val tab = androidx.compose.runtime.saveable.rememberSaveable(layerId) { mutableIntStateOf(0) }
    val focus = androidx.compose.runtime.saveable.rememberSaveable(layerId, saver = androidx.compose.runtime.saveable.Saver<MutableState<AnimatorTrackAddress?>, List<Any>>(
        save = { AnimateAddressCodec.save(it.value) }, restore = { mutableStateOf(runCatching { AnimateAddressCodec.restore(it) }.getOrNull()) })) { mutableStateOf<AnimatorTrackAddress?>(null) }
    val sub = androidx.compose.runtime.saveable.rememberSaveable(layerId) { mutableStateOf<String?>(null) }
    val link = androidx.compose.runtime.saveable.rememberSaveable(layerId) { mutableStateOf(true) }
    return remember(animator, selector, tab, focus, sub, link) { AnimatePageState(animator, selector, tab, focus, sub, link) }
}

/**
 * The Animate tab body: animator strip, Properties | Selectors switch, then the selected animator's properties or
 * selectors. Every edit is an existing [AnimatorEdit]; drags run through the existing begin / preview / finish draft
 * (one undo per drag), typed values, choices and structure edits are single actions (one undo each).
 */
@Composable internal fun TextAnimatePage(layer: CompositionLayer, time: Long, frameRate: FrameRate, compositionWidth: Int, compositionHeight: Int,
    state: AnimatePageState, finishing: Boolean, palette: PaletteRepository?,
    onAction: (EditorAction) -> Unit, onValueBegin: () -> Unit, onPayloadPreview: (TextProperties) -> Unit,
    onFinish: () -> Unit, onCancel: () -> Unit, onMessage: (String) -> Unit, metrics: AnimateMetrics, modifier: Modifier = Modifier) {
    val text = layer.text
    val local = Math.subtractExact(frameRate.snap(time), layer.resolvedKeyframeAnchorUs)
    val animator = AnimateSelection.animator(text, state.animatorId)
    val selector = AnimateSelection.selector(animator, state.selectorId)
    var popover by remember(layer.id) { mutableStateOf<String?>(null) }
    var page by remember(layer.id, animator?.id) { mutableIntStateOf(0) }
    val cell = metrics.cell.dp; val pitch = metrics.pitch.dp; val half = (metrics.gap / 2).dp
    val inset = PaddingValues(half)

    // ---- Edit plumbing ----------------------------------------------------------------------------------------
    var draft by remember { mutableStateOf<CompositionLayer?>(null) }
    val currentLayer by rememberUpdatedState(layer)
    fun focus(address: AnimatorTrackAddress) { state.focus = address }
    fun fail(t: Throwable) { onMessage(t.message ?: "Animator edit failed") }
    /** One undoable action (structure, choice, toggle, typed value). */
    fun commit(build: (TextProperties) -> List<AnimatorEdit>) {
        if (finishing) return
        runCatching { build(currentLayer.text) }.onSuccess { if (it.isNotEmpty()) onAction(TextAnimatorEdits.action(currentLayer.id, it)) }.onFailure(::fail)
    }
    fun set(address: AnimatorTrackAddress, value: Float = 0f, y: Float? = null, argb: Int? = null, seed: Long? = null) =
        commit { listOf(TextAnimatorEdits.set(it, address, local, value, y, argb, seed)) }
    /** Drag session: begin once on the captured layer, previews on that base, then finish (one undo) or cancel. */
    fun begin(address: AnimatorTrackAddress) { if (finishing || draft != null) return; focus(address); draft = currentLayer; onValueBegin() }
    fun preview(build: (TextProperties) -> AnimatorEdit) {
        val base = draft ?: return
        runCatching { TextAnimatorEdits.payload(base, frameRate, listOf(build(base.text))) }.onSuccess(onPayloadPreview).onFailure(::fail)
    }
    fun finish() { if (draft != null) { draft = null; onFinish() } }
    fun cancel() { if (draft != null) { draft = null; onCancel() } }
    fun close() { cancel(); popover = null }
    DisposableEffect(layer.id) { onDispose { if (draft != null) onCancel() } }
    BackHandler(enabled = state.subPage != null) { cancel(); state.subPage = null }

    fun badge(address: AnimatorTrackAddress) = KeyBadges.state(TextAnimatorEdits.keyTimes(layer, address), local)
    fun key(address: AnimatorTrackAddress) = "f:" + AnimateAddressCodec.save(address).joinToString(":")

    // ---- Field tile ---------------------------------------------------------------------------------------------
    @Composable fun FieldTile(address: AnimatorTrackAddress, modifier: Modifier, fill: PaddingValues?, testTag: String, onLong: (() -> Unit)? = null,
        menu: @Composable () -> Unit = {}) {
        val spec = AnimateFields.spec(address)
        val focused = state.focus == address
        val open = popover == key(address)
        val b = badge(address)
        when (spec.editor) {
            AnimateEditor.SLIDER -> {
                val limit = requireNotNull(spec.limit)
                val v = TextAnimatorEdits.value(text, address, local)
                val caption = if (address.property == AnimatorProperty.CHARACTER_VALUE) AnimateCaption.character(v) else AnimateCaption.scalar(limit, v)
                InspectorTile(InspectorIcons.icon(spec.icon), "${spec.label}, ${PropertyLimits.format(limit, v)} ${limit.unit}".trim(), modifier, active = open || focused,
                    enabled = !finishing, caption = caption, testTag = testTag, fill = fill, badge = b, onLongClick = onLong, popover = {
                        menu()
                        AnchoredPopover(open, ::close, "animate-popover-$testTag") {
                            SliderPopoverContent(limit, v, false, spec.label, onReset = { commit { listOf(TextAnimatorEdits.reset(it, address, local)) } },
                                onBegin = { begin(address) }, onPreview = { x -> preview { t -> TextAnimatorEdits.set(t, address, local, limit.hard(x)) } },
                                onFinish = ::finish, onCancel = ::cancel, onTyped = { x -> set(address, limit.hard(x)) }, enabled = !finishing)
                        }
                    }) { focus(address); popover = key(address) }
            }
            AnimateEditor.VECTOR -> {
                val limit = requireNotNull(spec.limit)
                val v = TextAnimatorEdits.vector(text, address, local)
                InspectorTile(InspectorIcons.icon(spec.icon), "${spec.label}, ${PropertyLimits.format(limit, v[0])}, ${PropertyLimits.format(limit, v[1])} ${limit.unit}", modifier,
                    active = open || focused, enabled = !finishing, caption = AnimateCaption.vector(limit, v[0], v[1]), testTag = testTag, fill = fill, badge = b,
                    onLongClick = onLong, popover = {
                        menu()
                        AnchoredPopover(open, ::close, "animate-popover-$testTag") {
                            var base by remember { mutableStateOf(v) }
                            SliderPopoverContent(limit, v[0], false, "${spec.label} X", onReset = { commit { listOf(TextAnimatorEdits.set(it, address, local, TextAnimatorEdits.defaultVector(address.property!!), v[1])) } },
                                onBegin = { base = v; begin(address) }, onPreview = { x -> preview { t -> TextAnimatorEdits.set(t, address, local, limit.hard(x), base[1]) } },
                                onFinish = ::finish, onCancel = ::cancel, onTyped = { x -> set(address, limit.hard(x), v[1]) }, enabled = !finishing)
                            SliderPopoverContent(limit, v[1], false, "${spec.label} Y", onReset = { commit { listOf(TextAnimatorEdits.set(it, address, local, v[0], TextAnimatorEdits.defaultVector(address.property!!))) } },
                                onBegin = { base = v; begin(address) }, onPreview = { y -> preview { t -> TextAnimatorEdits.set(t, address, local, base[0], limit.hard(y)) } },
                                onFinish = ::finish, onCancel = ::cancel, onTyped = { y -> set(address, v[0], limit.hard(y)) }, enabled = !finishing)
                        }
                    }) { focus(address); popover = key(address) }
            }
            AnimateEditor.POSITION, AnimateEditor.SCALE, AnimateEditor.ROTATION -> {
                val caption = when (spec.editor) {
                    AnimateEditor.ROTATION -> AnimateCaption.scalar(PropertyLimits.ROTATION, TextAnimatorEdits.value(text, address, local))
                    else -> TextAnimatorEdits.vector(text, address, local).let { AnimateCaption.vector(requireNotNull(spec.limit), it[0], it[1]) }
                }
                InspectorTile(InspectorIcons.icon(spec.icon), "${spec.label}, open ${spec.label.lowercase()} controls", modifier, active = focused, enabled = !finishing,
                    caption = caption, testTag = testTag, fill = fill, badge = b, onLongClick = onLong, popover = menu) {
                    focus(address); popover = null; state.subPage = requireNotNull(address.property).name
                }
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

    Column(modifier.testTag("animate-page")) {
        // ---- Sub-page: the Transform tab's own Position / Rotation / Scale controls for an animator property ------
        val sub = state.subPage?.let { name -> runCatching { AnimatorProperty.valueOf(name) }.getOrNull() }
        if (sub != null && animator != null && animator.properties.any { it.property == sub }) {
            val address = TextAnimatorEdits.property(animator.id, sub)
            AnimateSubPage(address, sub, animator.name, text, local, metrics, compositionWidth, compositionHeight, state.linkScale, { state.linkScale = it },
                finishing, badge(address), onBack = { cancel(); state.subPage = null }, onBegin = { begin(address) }, onPreview = { x, y -> preview { t -> TextAnimatorEdits.set(t, address, local, x, y) } },
                onFinish = ::finish, onCancel = ::cancel, onSet = { x, y -> set(address, x, y) })
            return@Column
        } else if (state.subPage != null) SideEffect { state.subPage = null }

        // ---- 1. Animator strip -------------------------------------------------------------------------------
        Row(Modifier.fillMaxWidth().height(pitch).horizontalScroll(rememberScrollState()).testTag("animate-animator-strip")) {
            text.animators.forEachIndexed { i, a ->
                val selected = a.id == animator?.id
                val enabled = a.enabled.hold(local) != 0f
                val menuKey = "menu-anim:${a.id}"
                InspectorTile(InspectorIcons.icon("ANIMATOR"), "Animator ${i + 1}: ${a.name}", Modifier.width(cell).fillMaxHeight(), filled = selected, enabled = !finishing,
                    caption = if (enabled) "${i + 1}" else "${i + 1} off", state = listOfNotNull(if (selected) "Selected" else null, if (enabled) null else "Disabled").joinToString(", ").ifEmpty { "Not selected" },
                    testTag = "animate-animator-$i", fill = inset, onLongClick = { state.animatorId = a.id; popover = menuKey }, popover = {
                        DropdownMenu(popover == menuKey, { popover = null }, Modifier.testTag("animate-animator-menu")) {
                            DropdownMenuItem(text = { Text("Rename…") }, onClick = { popover = "rename:${a.id}" })
                            DropdownMenuItem(text = { Text(if (enabled) "Disable" else "Enable") }, onClick = {
                                popover = null; set(TextAnimatorEdits.animatorEnabled(a.id), if (enabled) 0f else 1f) })
                            DropdownMenuItem(text = { Text("Duplicate") }, enabled = text.animators.size < TextAnimatorEdits.MAX_ANIMATORS, onClick = {
                                popover = null; commit { t -> TextAnimatorEdits.duplicateAnimator(t, a.id) } })
                            DropdownMenuItem(text = { Text("Move earlier") }, enabled = i > 0, onClick = { popover = null; commit { t -> listOfNotNull(TextAnimatorEdits.moveAnimator(t, a.id, -1)) } })
                            DropdownMenuItem(text = { Text("Move later") }, enabled = i < text.animators.lastIndex, onClick = { popover = null; commit { t -> listOfNotNull(TextAnimatorEdits.moveAnimator(t, a.id, 1)) } })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { popover = null; commit { listOf(AnimatorEdit.Remove(a.id)) } })
                        }
                        AnchoredPopover(popover == "rename:${a.id}", { popover = null }, "animate-rename") {
                            InspectorValueField(a.name, "Animator name", "", { name -> popover = null; commit { t -> listOfNotNull(TextAnimatorEdits.rename(t, a.id, name)) } },
                                { popover = null }, Modifier.widthIn(min = 200.dp), keyboardType = KeyboardType.Text)
                        }
                    }) { if (!selected) { state.animatorId = a.id; popover = null } }
            }
            if (text.animators.size < TextAnimatorEdits.MAX_ANIMATORS) InspectorTile(InspectorIcons.icon("ADD"), "Add animator", Modifier.width(cell).fillMaxHeight(), hollow = true,
                enabled = !finishing, state = "${text.animators.size} of ${TextAnimatorEdits.MAX_ANIMATORS}", testTag = "animate-add-animator", fill = inset) {
                commit { t -> listOf(TextAnimatorEdits.addAnimator(t).also { state.animatorId = it.animator.id; state.selectorId = it.animator.selectors.first().id }) }
            }
        }
        if (animator == null) {
            // Empty state: the add tile above plus an icon-only hint (no paragraph text).
            Box(Modifier.fillMaxWidth().weight(1f).semantics(mergeDescendants = true) { contentDescription = "No animators. Add one with the add tile above." }
                .testTag("animate-empty"), contentAlignment = Alignment.Center) {
                Icon(InspectorIcons.icon("ANIMATOR"), null, Modifier.size(32.dp), tint = TextDisabled)
            }
            return@Column
        }

        // ---- 2. Sub-switch: Properties | Selectors, plus the animator's own keyframeable grouping and on/off ----
        Row(Modifier.fillMaxWidth().height(pitch).testTag("animate-switch")) {
            AnimateTab.entries.forEach { t ->
                val count = if (t == AnimateTab.PROPERTIES) animator.properties.size else animator.selectors.size
                InspectorTile(InspectorIcons.icon(if (t == AnimateTab.PROPERTIES) "PROPERTIES" else "SELECTORS"),
                    if (t == AnimateTab.PROPERTIES) "Properties: $count" else "Selectors: $count", Modifier.width(cell * 2).fillMaxHeight(), active = state.tab == t,
                    enabled = !finishing, caption = "$count", state = if (state.tab == t) "Selected" else "Not selected", testTag = "animate-tab-${t.name}", fill = inset) {
                    if (state.tab != t) { state.tabIndex = t.ordinal; popover = null }
                }
            }
            FieldTile(TextAnimatorEdits.grouping(animator.id), Modifier.width(cell).fillMaxHeight(), inset, "animate-grouping")
            FieldTile(TextAnimatorEdits.animatorEnabled(animator.id), Modifier.width(cell).fillMaxHeight(), inset, "animate-enabled")
        }
        Spacer(Modifier.height(metrics.groupGap.dp))
        Spacer(Modifier.height((metrics.slack / 2).dp))

        // ---- 3. Properties tab: one tile per property, add tile at the end, pager when they overflow ------------
        if (state.tab == AnimateTab.PROPERTIES) {
            val cells: List<AnimatorProperty?> = animator.properties.map { it.property } + if (animator.properties.size < AnimatorProperty.entries.size) listOf(null) else emptyList()
            val pages = AnimatePaging.pages(cells, metrics.gridCells)
            val index = page.coerceIn(0, pages.lastIndex)
            val shown: List<Any?> = pages[index] + if (pages.size > 1) listOf("pager") else emptyList()
            Column(Modifier.fillMaxWidth().testTag("animate-properties")) {
                shown.chunked(metrics.columns).forEachIndexed { r, row ->
                    Row(Modifier.fillMaxWidth().height(pitch).testTag("animate-row-$r")) {
                        row.forEach { item ->
                            val m = Modifier.width(cell).fillMaxHeight()
                            when (item) {
                                "pager" -> InspectorTile(InspectorIcons.icon("NEXT"), "More properties, page ${index + 1} of ${pages.size}", m, enabled = !finishing,
                                    caption = "${index + 1}/${pages.size}", testTag = "animate-properties-pager", fill = inset) { page = (index + 1) % pages.size; popover = null }
                                null -> InspectorTile(InspectorIcons.icon("ADD"), "Add property", m, hollow = true, active = popover == "add-property", enabled = !finishing,
                                    testTag = "animate-add-property", fill = inset, popover = {
                                        AnchoredPopover(popover == "add-property", { popover = null }, "animate-add-property-popover") {
                                            AnimateFields.GROUPS.forEach { (group, list) ->
                                                Text(group, Modifier.padding(start = 6.dp, top = 2.dp).semantics { heading() }, color = TextSecondary, fontSize = 10.sp, lineHeight = 12.sp)
                                                list.chunked(7).forEach { line ->
                                                    Row {
                                                        line.forEach { p ->
                                                            val added = animator.properties.any { it.property == p }
                                                            val spec = AnimateFields.property(p)
                                                            InspectorIconButton(InspectorIcons.icon(spec.icon), if (added) "${spec.label}, added; show it" else "Add ${spec.label}",
                                                                Modifier.testTag("animate-add-${p.name}"), selected = added) {
                                                                popover = null
                                                                if (!added) commit { t -> listOfNotNull(TextAnimatorEdits.addProperty(t, animator.id, p)) }
                                                                focus(TextAnimatorEdits.property(animator.id, p))
                                                                page = (cells.indexOf(p).coerceAtLeast(0)) / (metrics.gridCells - 1).coerceAtLeast(1)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }) { popover = "add-property" }
                                else -> {
                                    val p = item as AnimatorProperty
                                    val address = TextAnimatorEdits.property(animator.id, p)
                                    val menuKey = "menu-prop:${p.name}"
                                    FieldTile(address, m, inset, "animate-property-${p.name}", onLong = { focus(address); popover = menuKey }) {
                                        DropdownMenu(popover == menuKey, { popover = null }, Modifier.testTag("animate-property-menu")) {
                                            DropdownMenuItem(text = { Text("Reset ${AnimateFields.property(p).label} value") }, onClick = {
                                                popover = null; commit { t -> listOf(TextAnimatorEdits.reset(t, address, local)) } })
                                            DropdownMenuItem(text = { Text("Remove ${AnimateFields.property(p).label}") }, onClick = {
                                                popover = null; commit { listOf(AnimatorEdit.RemoveProperty(animator.id, p)) } })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // ---- 4. Selectors tab: selector strip, then the selected selector's controls ------------------------
            Row(Modifier.fillMaxWidth().height(pitch).horizontalScroll(rememberScrollState()).testTag("animate-selector-strip")) {
                animator.selectors.forEachIndexed { i, s ->
                    val selected = s.id == selector?.id
                    val kind = TextAnimatorEdits.kind(s)
                    val on = s.enabled.hold(local) != 0f
                    val menuKey = "menu-sel:${s.id}"
                    InspectorTile(InspectorIcons.icon(kind.name), "${if (kind == TextSelectorKind.RANGE) "Range" else "Wiggly"} selector ${i + 1}", Modifier.width(cell).fillMaxHeight(),
                        filled = selected, enabled = !finishing, caption = if (on) "${i + 1}" else "${i + 1} off",
                        state = listOfNotNull(if (selected) "Selected" else null, if (on) null else "Disabled").joinToString(", ").ifEmpty { "Not selected" },
                        testTag = "animate-selector-$i", fill = inset, onLongClick = { state.selectorId = s.id; popover = menuKey }, popover = {
                            DropdownMenu(popover == menuKey, { popover = null }, Modifier.testTag("animate-selector-menu")) {
                                DropdownMenuItem(text = { Text(if (on) "Disable" else "Enable") }, onClick = {
                                    popover = null; set(TextAnimatorEdits.selectorField(animator.id, s.id, SelectorField.ENABLED), if (on) 0f else 1f) })
                                DropdownMenuItem(text = { Text("Duplicate") }, enabled = animator.selectors.size < TextAnimatorEdits.MAX_SELECTORS, onClick = {
                                    popover = null; commit { t -> TextAnimatorEdits.duplicateSelector(t, animator.id, s.id) } })
                                DropdownMenuItem(text = { Text("Move earlier") }, enabled = i > 0, onClick = { popover = null; commit { t -> listOfNotNull(TextAnimatorEdits.moveSelector(t, animator.id, s.id, -1)) } })
                                DropdownMenuItem(text = { Text("Move later") }, enabled = i < animator.selectors.lastIndex, onClick = { popover = null; commit { t -> listOfNotNull(TextAnimatorEdits.moveSelector(t, animator.id, s.id, 1)) } })
                                DropdownMenuItem(text = { Text("Delete") }, onClick = { popover = null; commit { listOf(AnimatorEdit.RemoveSelector(animator.id, s.id)) } })
                            }
                        }) { if (!selected) { state.selectorId = s.id; popover = null } }
                }
                if (animator.selectors.size < TextAnimatorEdits.MAX_SELECTORS) InspectorTile(InspectorIcons.icon("ADD"), "Add selector", Modifier.width(cell).fillMaxHeight(), hollow = true,
                    active = popover == "add-selector", enabled = !finishing, state = "${animator.selectors.size} of ${TextAnimatorEdits.MAX_SELECTORS}", testTag = "animate-add-selector", fill = inset, popover = {
                        AnchoredPopover(popover == "add-selector", { popover = null }, "animate-add-selector-popover") {
                            Row {
                                TextSelectorKind.entries.forEach { k ->
                                    InspectorIconButton(InspectorIcons.icon(k.name), "Add ${if (k == TextSelectorKind.RANGE) "Range" else "Wiggly"} selector", Modifier.testTag("animate-add-${k.name}")) {
                                        popover = null
                                        commit { t -> listOf(TextAnimatorEdits.addSelector(t, animator.id, k).also { state.selectorId = it.selector.id }) }
                                    }
                                }
                            }
                        }
                    }) { popover = "add-selector" }
            }
            if (selector != null) {
                val kind = TextAnimatorEdits.kind(selector)
                val face = SelectorFaces.layout(kind, metrics.selectorRows, metrics.columns)
                Column(Modifier.fillMaxWidth().testTag("animate-selector-controls")) {
                    if (face.strip) {
                        val units = selector.units.hold(local)
                        val basis = selector.basedOn.hold(local).toInt()
                        val document = text.source.evaluateDocument(local)
                        val span = if (units == 0f) 100f else AnimateUnits.count(document.text, document.boundaries, basis).toFloat()
                        RangeHandleStrip(selector, local, span, units != 0f, state.focus, Modifier.fillMaxWidth().height(pitch).padding(inset),
                            enabled = !finishing, badgeOf = { f -> badge(TextAnimatorEdits.selectorField(animator.id, selector.id, f)) },
                            onFocus = { f -> focus(TextAnimatorEdits.selectorField(animator.id, selector.id, f)) },
                            onBegin = { f -> begin(TextAnimatorEdits.selectorField(animator.id, selector.id, f)) },
                            onPreview = { f, v -> val limit = requireNotNull(AnimateFields.selector(f).limit)
                                preview { t -> TextAnimatorEdits.set(t, TextAnimatorEdits.selectorField(animator.id, selector.id, f), local, limit.hard(v)) } },
                            onFinish = ::finish, onCancel = ::cancel)
                    }
                    val cells: List<Any?> = face.face + if (face.more.isNotEmpty()) listOf("more") else emptyList()
                    cells.chunked(metrics.columns).forEachIndexed { r, row ->
                        Row(Modifier.fillMaxWidth().height(pitch).testTag("animate-selector-row-$r")) {
                            row.forEach { f ->
                                val m = Modifier.width(cell).fillMaxHeight()
                                if (f == "more") InspectorTile(InspectorIcons.icon("OVERFLOW"), "More selector options", m, active = popover == "more", enabled = !finishing,
                                    caption = "${face.more.size}", testTag = "animate-selector-more", fill = inset, popover = {
                                        AnchoredPopover(popover == "more", { popover = null }, "animate-more-popover") {
                                            face.more.chunked(6).forEach { line ->
                                                Row {
                                                    line.forEach { g ->
                                                        val address = if (g == null) TextAnimatorEdits.seed(animator.id, selector.id) else TextAnimatorEdits.selectorField(animator.id, selector.id, g)
                                                        FieldTile(address, Modifier.size(48.dp, 44.dp), PaddingValues(2.dp), "animate-more-${g?.name ?: "SEED"}")
                                                    }
                                                }
                                            }
                                        }
                                    }) { popover = "more" }
                                else {
                                    val address = if (f == null) TextAnimatorEdits.seed(animator.id, selector.id) else TextAnimatorEdits.selectorField(animator.id, selector.id, f as SelectorField)
                                    FieldTile(address, m, inset, "animate-field-${(f as? SelectorField)?.name ?: "SEED"}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Unit counts for the Index handle strip scale (characters, characters excluding spaces, words, explicit lines). */
internal object AnimateUnits {
    fun count(text: String, boundaries: IntArray, basis: Int): Int = when (basis) {
        0 -> boundaries.count { it < text.length }
        1 -> boundaries.count { it < text.length && !Character.isWhitespace(text.codePointAt(it)) }
        2 -> Regex("\\S+").findAll(text).count()
        else -> text.split('\n').size
    }.coerceAtLeast(1)
}

/** Colour property swatch: solid for fill, ring for stroke (real colours; chrome stays monochrome). */
@Composable private fun AnimateSwatch(argb: Int, ring: Boolean) {
    val shape = RoundedCornerShape(4.dp)
    Box(Modifier.size(InspectorIcons.INLINE).clip(shape).background(if (ring) Color.Transparent else Color(argb))
        .border(if (ring) 4.dp else 1.dp, if (ring) Color(argb) else TextPrimary, shape).border(1.dp, TextSecondary, shape))
}

/**
 * Range handle strip: the text's units from 0 to [span] (100 for percentage units), two handles (start, end) and the
 * band between them. Drag a handle to move it; drag the band to change Offset. The preview is live (one undo per drag).
 */
@Composable internal fun RangeHandleStrip(selector: TextRangeSelector, local: Long, span: Float, index: Boolean, focus: AnimatorTrackAddress?, modifier: Modifier,
    enabled: Boolean, badgeOf: (SelectorField) -> KeyBadge, onFocus: (SelectorField) -> Unit, onBegin: (SelectorField) -> Unit,
    onPreview: (SelectorField, Float) -> Unit, onFinish: () -> Unit, onCancel: () -> Unit) {
    val start = selector.start.evaluate(local); val end = selector.end.evaluate(local); val offset = selector.offset.evaluate(local)
    val current by rememberUpdatedState(Triple(start, end, offset))
    val spanNow by rememberUpdatedState(span.coerceAtLeast(1f))
    val focusField = focus?.takeIf { it.selectorId == selector.id }?.field
    var dragging by remember { mutableStateOf<SelectorField?>(null) }
    val density = LocalDensity.current
    val grab = with(density) { 24.dp.toPx() }
    val unit = if (index) "" else "%"
    fun fmt(v: Float) = PropertyLimits.format(PropertyLimits.SELECTOR_RANGE, v) + unit
    val begin by rememberUpdatedState(onBegin); val previewCb by rememberUpdatedState(onPreview)
    val finishCb by rememberUpdatedState(onFinish); val cancelCb by rememberUpdatedState(onCancel); val focusCb by rememberUpdatedState(onFocus)
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(SurfaceHigh).testTag("animate-range-strip")
        .semantics(mergeDescendants = true) {
            contentDescription = "Range: start ${fmt(start)}, end ${fmt(end)}, offset ${fmt(offset)}. Drag a handle or the band"
            stateDescription = listOf(SelectorField.START, SelectorField.END, SelectorField.OFFSET).mapNotNull { f -> KeyBadges.description(badgeOf(f))?.let { "${f.name.lowercase()} $it" } }.joinToString(", ")
        }
        .then(if (!enabled) Modifier else Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val w = size.width.toFloat().coerceAtLeast(1f)
                val (s, e, o) = current
                val sx = (s + o) / spanNow * w; val ex = (e + o) / spanNow * w
                val x = down.position.x
                val target = when {
                    kotlin.math.abs(x - sx) <= grab && kotlin.math.abs(x - sx) <= kotlin.math.abs(x - ex) -> SelectorField.START
                    kotlin.math.abs(x - ex) <= grab -> SelectorField.END
                    x > minOf(sx, ex) && x < maxOf(sx, ex) -> SelectorField.OFFSET
                    kotlin.math.abs(x - sx) < kotlin.math.abs(x - ex) -> SelectorField.START
                    else -> SelectorField.END
                }
                focusCb(target)
                val base = when (target) { SelectorField.START -> s; SelectorField.END -> e; else -> o }
                var moved = 0f
                val slop = awaitTouchSlopOrCancellation(down.id) { change, over -> change.consume(); moved = over.x }
                if (slop == null) return@awaitEachGesture
                dragging = target; begin(target)
                previewCb(target, base + moved / w * spanNow)
                var active = true
                try {
                    val completed = drag(slop.id) { change ->
                        change.consume()
                        moved += change.position.x - change.previousPosition.x
                        previewCb(target, base + moved / w * spanNow)
                    }
                    active = false
                    if (completed) finishCb() else cancelCb()
                } finally {
                    if (active) cancelCb()
                    dragging = null
                }
            }
        }), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val count = spanNow.toInt()
            if (index && count in 1..40) for (i in 0..count) {
                val x = i / spanNow * w
                drawLine(Border, Offset(x, h * .7f), Offset(x, h * .85f), 1.dp.toPx(), StrokeCap.Round)
            } else for (i in 0..10) {
                val x = i / 10f * w
                drawLine(Border, Offset(x, h * (if (i % 5 == 0) .62f else .72f)), Offset(x, h * .85f), 1.dp.toPx(), StrokeCap.Round)
            }
            val sx = ((start + offset) / spanNow * w).coerceIn(0f, w); val ex = ((end + offset) / spanNow * w).coerceIn(0f, w)
            val l = minOf(sx, ex); val r = maxOf(sx, ex)
            val bandColour = if (focusField == SelectorField.OFFSET || dragging == SelectorField.OFFSET) TextSecondary.copy(alpha = .55f) else TextSecondary.copy(alpha = .3f)
            drawRoundRect(bandColour, Offset(l, h * .3f), Size((r - l).coerceAtLeast(1f), h * .4f), CornerRadius(3.dp.toPx()))
            for ((f, x) in listOf(SelectorField.START to sx, SelectorField.END to ex)) {
                val hot = focusField == f || dragging == f
                drawLine(if (hot) Highlight else TextPrimary, Offset(x, h * .15f), Offset(x, h * .85f), (if (hot) 3.dp else 2.dp).toPx(), StrokeCap.Round)
                drawCircle(if (hot) Highlight else TextPrimary, 4.dp.toPx(), Offset(x, h * .15f))
            }
        }
        dragging?.let { f ->
            val v = when (f) { SelectorField.START -> start; SelectorField.END -> end; else -> offset }
            Text("${f.name.lowercase().replaceFirstChar { it.uppercase() }} ${fmt(v)}", Modifier.align(Alignment.TopCenter).padding(top = 1.dp)
                .background(SurfaceHigh, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp), color = TextPrimary, fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1)
        }
    }
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
