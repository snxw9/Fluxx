package com.fluxx.android.ui.inspector

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import com.fluxx.android.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * GRAPH G1: the sheet-level host of the Graph page. Rails read it to enable their Graph icon for their focused property
 * ([InspectorUtilityRail.graphTarget]) and to open / close the page. Provided by [ElementInspectorSheet].
 */
internal class GraphHost(val layer: () -> CompositionLayer, private val target: () -> GraphTarget?,
    val open: (GraphTarget) -> Unit, val close: () -> Unit) {
    val openTarget: GraphTarget? get() = target()
}
internal val LocalGraphHost = compositionLocalOf<GraphHost?> { null }

/**
 * Graph page geometry (dp), pure and JVM tested. The page fits the shared sheet heights (268 / 328dp) without
 * scrolling: a 36dp header, a square Ease canvas beside a grid of the 8 type + 7 tool tiles, and a 40dp preset strip.
 * The tiles shrink (40 → 36 → 32dp) rather than the sheet growing.
 */
internal object GraphLayout {
    const val HEADER = 36f
    const val STRIP = 40f
    const val GAP = 4f
    const val CANVAS_GAP = 6f
    const val TYPES = 8
    const val TOOLS = 7
    const val TILES = TYPES + TOOLS
    const val MIN_CANVAS = 120f
    data class Metrics(val tile: Float, val columns: Int, val rows: Int, val canvas: Float, val body: Float, val controlsWidth: Float) {
        val fits get() = canvas >= MIN_CANVAS && rows * tile + (rows - 1) * GAP <= body + 1e-3f
    }
    /** The header row grows with the font scale like every other inspector header (the sheet grows by the same factor). */
    fun header(fontScale: Float) = HEADER * fontScale.coerceAtLeast(1f)
    fun body(height: Float, fontScale: Float = 1f) = (height - header(fontScale) - STRIP - 2 * GAP).coerceAtLeast(0f)
    fun compute(width: Float, height: Float, fontScale: Float = 1f): Metrics {
        val body = body(height, fontScale)
        var best: Metrics? = null
        for (tile in listOf(40f, 36f, 32f)) {
            val rows = floor((body + GAP) / (tile + GAP)).toInt().coerceAtLeast(1)
            val columns = ceil(TILES / rows.toFloat()).toInt()
            val usedRows = ceil(TILES / columns.toFloat()).toInt()
            val controls = columns * tile + (columns - 1) * GAP
            val canvas = minOf(body, width - controls - CANVAS_GAP).coerceAtLeast(0f)
            val m = Metrics(tile, columns, usedRows, canvas, body, controls)
            if (best == null || m.canvas > best.canvas + .5f) best = m
            if (m.fits && canvas >= body * .7f) return m
        }
        return best!!
    }
}

/** Plot mapping of the Ease canvas: x ∈ [0, 1] across, y ∈ [[low], [high]] upward, inside an [inset] margin. */
internal class EasePlot(val width: Float, val height: Float, val inset: Float, val low: Float, val high: Float) {
    val left get() = inset; val top get() = inset
    val plotWidth get() = (width - 2 * inset).coerceAtLeast(1f)
    val plotHeight get() = (height - 2 * inset).coerceAtLeast(1f)
    fun x(v: Float) = left + v * plotWidth
    fun y(v: Float) = top + (high - v) / (high - low) * plotHeight
    fun valueX(px: Float) = (px - left) / plotWidth
    fun valueY(py: Float) = high - (py - top) / plotHeight * (high - low)
    companion object {
        const val DEFAULT_LOW = -.25f; const val DEFAULT_HIGH = 1.25f
        /** Y range: about [−0.25, 1.25], grown to fit the curve's extremes and its handles with a 0.1 margin. */
        fun range(curve: EasingCurve): Pair<Float, Float> {
            val e = EasingMath.extent(curve)
            var lo = minOf(DEFAULT_LOW, e[0] - .1f); var hi = maxOf(DEFAULT_HIGH, e[1] + .1f)
            curve.bezierOrNull()?.let { lo = minOf(lo, it[1] - .1f, it[3] - .1f); hi = maxOf(hi, it[1] + .1f, it[3] + .1f) }
            return lo to hi
        }
    }
}

/** Curve sampling for drawing: exact parametric bezier, adaptive subdivision for Bounce / Elastic, a step for Hold. */
internal object EaseSampling {
    fun points(curve: EasingCurve, tolerance: Float = .002f): FloatArray {
        val b = curve.bezierOrNull()
        if (curve.type == EasingPreset.HOLD) return floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f)
        if (b != null) {
            val n = 64; val out = FloatArray((n + 1) * 2)
            for (i in 0..n) {
                val s = i / n.toFloat(); val u = 1 - s
                out[i * 2] = 3 * u * u * s * b[0] + 3 * u * s * s * b[2] + s * s * s
                out[i * 2 + 1] = 3 * u * u * s * b[1] + 3 * u * s * s * b[3] + s * s * s
            }
            return out
        }
        val list = ArrayList<Float>()
        val base = 48 + 24 * curve.a.toInt().coerceIn(1, 10)
        list.add(0f); list.add(curve.ease(0f))
        fun refine(t0: Float, v0: Float, t1: Float, v1: Float, depth: Int) {
            val tm = (t0 + t1) / 2; val vm = curve.ease(tm)
            if (depth < 6 && abs(vm - (v0 + v1) / 2) > tolerance) { refine(t0, v0, tm, vm, depth + 1); refine(tm, vm, t1, v1, depth + 1) }
            else { list.add(tm); list.add(vm); list.add(t1); list.add(v1) }
        }
        for (i in 0 until base) { val t0 = i / base.toFloat(); val t1 = (i + 1) / base.toFloat(); refine(t0, curve.ease(t0), t1, curve.ease(t1), 0) }
        return list.toFloatArray()
    }
}

/** The numeric popover's slider + typed rows (no −/+ steppers; typed values commit on focus loss). */
internal object EaseLimits {
    val X1 = PropertyLimit("x1", "", 0f, 1f, 0f, 1f, 0f, 1f, .33f, detentStep = .1f, step = .01f)
    val Y1 = PropertyLimit("y1", "", -1f, 2f, -2f, 3f, -2f, 3f, 0f, detentStep = .5f, step = .01f)
    val X2 = PropertyLimit("x2", "", 0f, 1f, 0f, 1f, 0f, 1f, .67f, detentStep = .1f, step = .01f)
    val Y2 = PropertyLimit("y2", "", -1f, 2f, -2f, 3f, -2f, 3f, 1f, detentStep = .5f, step = .01f)
    val BOUNCES = PropertyLimit("Bounces", "", 1f, 8f, 1f, 8f, 1f, 8f, EasingLimits.BOUNCE_DEFAULT_COUNT, ladder = (1..8).map { it.toFloat() }, step = 1f)
    val RESTITUTION = PropertyLimit("Bounce height", "", .1f, .9f, .1f, .9f, .1f, .9f, EasingLimits.BOUNCE_DEFAULT_RESTITUTION, detentStep = .1f, step = .01f)
    val OSCILLATIONS = PropertyLimit("Oscillations", "", 1f, 10f, 1f, 10f, 1f, 10f, EasingLimits.ELASTIC_DEFAULT_OSCILLATIONS, detentStep = 1f, step = .1f)
    val DAMPING = PropertyLimit("Damping", "", .1f, 1f, .1f, 1f, .1f, 1f, EasingLimits.ELASTIC_DEFAULT_DAMPING, detentStep = .1f, step = .01f)
    /** (limit, read, write) per editable parameter of [curve]'s type. */
    fun rows(curve: EasingCurve): List<Triple<PropertyLimit, Float, (Float) -> EasingCurve>> {
        val b = curve.bezierOrNull()
        return when {
            curve.type == EasingPreset.BOUNCE -> listOf(
                Triple(BOUNCES, curve.a) { v -> curve.copy(a = v.coerceIn(1f, 8f)) },
                Triple(RESTITUTION, curve.b) { v -> curve.copy(b = v.coerceIn(.1f, .9f)) })
            curve.type == EasingPreset.ELASTIC -> listOf(
                Triple(OSCILLATIONS, curve.a) { v -> curve.copy(a = v.coerceIn(1f, 10f)) },
                Triple(DAMPING, curve.b) { v -> curve.copy(b = v.coerceIn(.1f, 1f)) })
            b != null -> listOf(X1, Y1, X2, Y2).mapIndexed { i, limit ->
                Triple(limit, b[i]) { v: Float ->
                    val o = b.copyOf(); o[i] = if (i % 2 == 0) EaseMath.clampX(v) else EaseMath.clampY(v)
                    EasingCurve.bezier(o[0], o[1], o[2], o[3]) }
            }
            else -> emptyList()
        }
    }
}

private val CHOICE_ICONS = mapOf(EaseChoice.HOLD to "EASE_HOLD", EaseChoice.LINEAR to "EASE_LINEAR", EaseChoice.EASY_EASE to "EASE_EASY",
    EaseChoice.EASE_IN to "EASE_IN", EaseChoice.EASE_OUT to "EASE_OUT", EaseChoice.CUSTOM to "EASE_CUSTOM",
    EaseChoice.BOUNCE to "EASE_BOUNCE", EaseChoice.ELASTIC to "EASE_ELASTIC")

/**
 * The Graph page (Ease view): a normalised editor for the segment at the playhead (GraphModel.segmentIndex). Every
 * change is [EditorAction.SetKeyEasing]: handle drags and numeric slider drags use begin / preview / commit / cancel
 * (one undo per drag); taps (type, preset, invert, paste, apply to all, typed values) are one action each.
 * Non-modal: the preview, timeline, transport and the rail stay live; the Graph icon or Back returns.
 */
@Composable internal fun GraphPage(layer: CompositionLayer, target: GraphTarget, playheadUs: Long, frameRate: FrameRate,
    onAction: (EditorAction) -> Unit, onBegin: () -> Unit, onPreview: (EditorAction) -> Unit, onCommit: () -> Unit, onCancel: () -> Unit,
    onClose: () -> Unit, modifier: Modifier = Modifier) {
    val close by rememberUpdatedState(onClose)
    BackHandler { close() }
    val local = GraphModel.local(layer, target, playheadUs, frameRate)
    val segment = GraphModel.segment(layer, target, local)
    // The property lost its keys (undo, diamond, deletion) or the address disappeared: fall back to the page it came from.
    if (segment == null) { LaunchedEffect(Unit) { close() }; Box(modifier.fillMaxSize().testTag("graph-page")); return }
    val keyTime = segment.startUs
    var draft by remember(target, keyTime) { mutableStateOf<EasingCurve?>(null) }
    var ghost by remember(target, keyTime) { mutableStateOf<EasingCurve?>(null) }
    var selectedHandle by remember(target, keyTime) { mutableStateOf<Int?>(null) }
    var snap by rememberSaveable { mutableStateOf(false) }
    var mirror by rememberSaveable { mutableStateOf(false) }
    var numeric by remember(target, keyTime) { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val curve = draft ?: segment.curve
    val stored by rememberUpdatedState(segment.curve)
    val focus = LocalFocusManager.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val cancel by rememberUpdatedState(onCancel)
    DisposableEffect(target, keyTime) { onDispose { if (dragging) { dragging = false; cancel() } } }

    fun edit(c: EasingCurve, all: Boolean = false) {
        focus.clearFocus()
        if (!all && EaseMath.same(c, stored)) return
        ghost = stored
        onAction(GraphModel.action(layer.id, target, if (all) null else listOf(keyTime), c))
    }
    fun begin() { if (!dragging) { dragging = true; ghost = stored; onBegin() } }
    fun preview(c: EasingCurve) { if (dragging) { draft = c; onPreview(GraphModel.action(layer.id, target, listOf(keyTime), c)) } }
    fun commit() { if (dragging) { dragging = false; onCommit(); draft = null } }
    fun cancelDrag() { if (dragging) { dragging = false; onCancel(); draft = null } }

    val onKey = local in GraphModel.keyTimes(layer, target)
    val values = remember(layer, target, segment) { GraphModel.values(layer, target, segment) }
    Row(modifier.fillMaxSize().testTag("graph-page"), horizontalArrangement = Arrangement.spacedBy(TextInspectorBudget.RAIL_GAP.dp)) {
        InspectorUtilityRail(resetLabel = "Reset curve to Easy Ease", onReset = { edit(EasingCurve.EASY_EASE) },
            isAnimated = true, hasKeyframeAtPlayhead = onKey,
            onToggleKeyframe = { GraphModel.toggleKey(layer, target, playheadUs, frameRate)?.let(onAction) },
            show3D = false, keyLabel = if (onKey) "Remove keyframe at playhead" else "Add keyframe at playhead",
            extraItems = listOf(RailMenuItem("Apply this curve to all keys") { edit(curve, all = true) }),
            graphTarget = target)
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().dismissPopoversOnEmptyTap().testTag("graph-body")) {
            val fontScale = LocalDensity.current.fontScale
            val m = GraphLayout.compute(maxWidth.value, maxHeight.value, fontScale)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(GraphLayout.GAP.dp)) {
                GraphHeader(segment, values, GraphLayout.header(fontScale))
                Row(Modifier.fillMaxWidth().height(m.body.dp), horizontalArrangement = Arrangement.spacedBy(GraphLayout.CANVAS_GAP.dp)) {
                    val progress = GraphModel.progress(segment, local)
                    EaseCanvas(curve, ghost, progress, selectedHandle, snap, mirror,
                        onSelect = { selectedHandle = it },
                        onBegin = ::begin, onDrag = ::preview, onEnd = ::commit, onCancel = ::cancelDrag,
                        modifier = Modifier.size(m.canvas.dp).testTag("graph-ease-canvas"))
                    EaseControls(m, curve, snap, mirror, numeric,
                        onChoice = { edit(EaseChoice.curve(it, stored)) },
                        onSnap = { snap = !snap }, onMirror = { mirror = !mirror },
                        onInvert = { EaseMath.invert(stored)?.let { edit(it) } },
                        onApplyAll = { edit(stored, all = true) },
                        onCopy = { CubicBezierText.format(stored)?.let { text ->
                            scope.launch { runCatching { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("cubic-bezier", text))) } } } },
                        onPaste = { scope.launch {
                            val text = runCatching { clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() }.getOrNull()
                            CubicBezierText.parse(text)?.let { edit(it) } } },
                        // Decide the toggle first: a dismissal request closes the open popover synchronously (Main.immediate).
                        onNumeric = { val open = PopoverToggle.next(if (numeric) "n" else null, "n") != null
                            if (open) { PopoverDismissal.request(); numeric = true } else { numeric = false; cancelDrag() } },
                        numericContent = {
                            AnchoredPopover(numeric, { numeric = false; cancelDrag() }, "graph-numeric-popover", modal = false) {
                                EaseNumericRows(curve, onBegin = ::begin, onPreview = ::preview, onFinish = ::commit, onCancel = ::cancelDrag,
                                    onTyped = { edit(it) })
                            }
                        })
                }
                PresetStrip(curve) { edit(it) }
            }
        }
    }
}

@Composable private fun GraphHeader(segment: GraphSegment, values: Pair<String, String>, height: Float) {
    Row(Modifier.fillMaxWidth().height(height.dp).testTag("graph-header"), verticalAlignment = Alignment.CenterVertically) {
        // Mode switch: Value and Speed arrive in later passes (visible, disabled and inert); Ease is the G1 view.
        GraphModeTile("GRAPH_VALUE", "Value graph (not yet available)", enabled = false, selected = false)
        GraphModeTile("GRAPH_SPEED", "Speed graph (not yet available)", enabled = false, selected = false)
        GraphModeTile("GRAPH_EASE", "Ease view", enabled = true, selected = true)
        Column(Modifier.weight(1f).padding(start = 8.dp).semantics(mergeDescendants = true) {
            contentDescription = "${segment.label} of ${segment.count}, ${values.first} to ${values.second}, ${seconds(segment.durationUs)}; the segment at the playhead"
        }) {
            Text("${segment.label} · ${seconds(segment.durationUs)}", color = TextPrimary, style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("graph-segment-label"))
            Text("${values.first} → ${values.second}", color = TextSecondary, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun seconds(us: Long) = String.format(java.util.Locale.ROOT, "%.2fs", us / 1_000_000.0)

@Composable private fun GraphModeTile(icon: String, label: String, enabled: Boolean, selected: Boolean) {
    Box(Modifier.size(GraphLayout.HEADER.dp).clip(RoundedCornerShape(8.dp)).background(if (selected) Surface else Color.Transparent)
        .semantics { contentDescription = label; if (!enabled) disabled(); if (selected) this.selected = true }.testTag("graph-mode-$icon"),
        contentAlignment = Alignment.Center) {
        Icon(InspectorIcons.icon(icon), null, Modifier.size(20.dp), tint = if (!enabled) TextDisabled else if (selected) TextPrimary else TextSecondary)
    }
}

private class EaseTool(val icon: String, val label: String, val enabled: Boolean, val selected: Boolean, val tag: String, val click: () -> Unit)

@Composable private fun EaseControls(m: GraphLayout.Metrics, curve: EasingCurve, snap: Boolean, mirror: Boolean, numeric: Boolean,
    onChoice: (EaseChoice) -> Unit, onSnap: () -> Unit, onMirror: () -> Unit, onInvert: () -> Unit, onApplyAll: () -> Unit,
    onCopy: () -> Unit, onPaste: () -> Unit, onNumeric: () -> Unit, numericContent: @Composable () -> Unit) {
    val choice = EaseChoice.of(curve)
    val handles = curve.bezierOrNull() != null
    val tiles = EaseChoice.entries.map { c -> EaseTool(CHOICE_ICONS.getValue(c), c.label, true, c == choice, "graph-type-${c.name}") { onChoice(c) } } + listOf(
        EaseTool("EASE_SNAP", "Snap handles to 0.05", handles, snap, "graph-tool-snap", onSnap),
        EaseTool("EASE_MIRROR", "Mirror handles", handles, mirror, "graph-tool-mirror", onMirror),
        EaseTool("EASE_INVERT", "Invert curve (swap ease in and out)", EaseMath.invert(curve) != null, false, "graph-tool-invert", onInvert),
        EaseTool("EASE_APPLY_ALL", "Apply to all keys", true, false, "graph-tool-apply-all", onApplyAll),
        EaseTool("EASE_COPY", "Copy cubic-bezier", handles, false, "graph-tool-copy", onCopy),
        EaseTool("PASTE", "Paste cubic-bezier", true, false, "graph-tool-paste", onPaste),
        EaseTool("EASE_NUMERIC", "Exact values", EaseLimits.rows(curve).isNotEmpty(), numeric, "graph-tool-numeric", onNumeric))
    Column(Modifier.width(m.controlsWidth.dp).testTag("graph-controls"), verticalArrangement = Arrangement.spacedBy(GraphLayout.GAP.dp)) {
        tiles.chunked(m.columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(GraphLayout.GAP.dp)) {
                row.forEach { t ->
                    Box {
                        InspectorIconButton(InspectorIcons.icon(t.icon), t.label, Modifier.size(m.tile.dp).testTag(t.tag), enabled = t.enabled,
                            selected = t.selected) { t.click() }
                        if (t.tag == "graph-tool-numeric") numericContent()
                    }
                }
            }
        }
    }
}

@Composable private fun EaseNumericRows(curve: EasingCurve, onBegin: () -> Unit, onPreview: (EasingCurve) -> Unit, onFinish: () -> Unit,
    onCancel: () -> Unit, onTyped: (EasingCurve) -> Unit) {
    val rows = EaseLimits.rows(curve)
    if (rows.isEmpty()) { Text("Hold has no parameters", Modifier.padding(8.dp), color = TextSecondary, style = MaterialTheme.typography.labelMedium); return }
    // The curve at the start of a slider drag: other parameters stay fixed while one moves.
    var base by remember { mutableStateOf(curve) }
    Column(Modifier.testTag("graph-numeric-rows")) {
        rows.forEachIndexed { i, (limit, value, _) ->
            SliderPopoverContent(limit, value, false, limit.name,
                onReset = { onTyped(EaseLimits.rows(curve)[i].third(limit.default)) },
                onBegin = { base = curve; onBegin() },
                onPreview = { v -> onPreview(EaseLimits.rows(base)[i].third(v)) },
                onFinish = onFinish, onCancel = onCancel,
                onTyped = { v -> onTyped(EaseLimits.rows(curve)[i].third(v)) })
        }
    }
}

@Composable private fun PresetStrip(curve: EasingCurve, onPreset: (EasingCurve) -> Unit) {
    val match = EasePresets.match(curve)
    LazyRow(Modifier.fillMaxWidth().height(GraphLayout.STRIP.dp).testTag("graph-preset-strip"), horizontalArrangement = Arrangement.spacedBy(GraphLayout.GAP.dp)) {
        items(EasePresets.ALL, key = { it.id }) { p ->
            InspectorIconButton(InspectorIcons.curve(p.id, { t -> p.curve.ease(t) }), p.name, Modifier.size(GraphLayout.STRIP.dp).testTag("graph-preset-${p.id}"),
                selected = p == match) { onPreset(p.curve) }
        }
    }
}

/**
 * The Ease canvas. Direct drag on a handle (48dp target around a 6dp dot) with a loupe bubble away from the finger;
 * after a handle is selected by a tap, a drag that starts on empty canvas moves it relatively (trackpad style, 0.35×).
 * The curve layer redraws only when the curve, ghost or range changes; the playhead dot is its own layer.
 */
@Composable private fun EaseCanvas(curve: EasingCurve, ghost: EasingCurve?, progress: Float?, selected: Int?, snap: Boolean, mirror: Boolean,
    onSelect: (Int?) -> Unit, onBegin: () -> Unit, onDrag: (EasingCurve) -> Unit, onEnd: () -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    val density = LocalDensity.current
    val inset = with(density) { 10.dp.toPx() }
    val hit = with(density) { 24.dp.toPx() }
    var frozen by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val natural = remember(curve) { EasePlot.range(curve) }
    val range = frozen?.let { minOf(it.first, natural.first) to maxOf(it.second, natural.second) } ?: natural
    var loupe by remember { mutableStateOf<Int?>(null) }
    val current by rememberUpdatedState(curve); val currentRange by rememberUpdatedState(range)
    val currentSelected by rememberUpdatedState(selected)
    val snapOn by rememberUpdatedState(snap); val mirrorOn by rememberUpdatedState(mirror)
    val select by rememberUpdatedState(onSelect); val begin by rememberUpdatedState(onBegin)
    val moved by rememberUpdatedState(onDrag); val end by rememberUpdatedState(onEnd); val cancel by rememberUpdatedState(onCancel)
    val description = CubicBezierText.format(curve) ?: when (curve.type) {
        EasingPreset.HOLD -> "Hold"; EasingPreset.BOUNCE -> "Bounce, ${curve.a.toInt()} bounces"; else -> "Elastic, ${"%.1f".format(curve.a)} oscillations" }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(SurfaceHigh).border(.5.dp, Border, RoundedCornerShape(8.dp))
        .semantics { contentDescription = "Ease curve: $description" }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val b = current.bezierOrNull() ?: return@awaitEachGesture
                val plot = EasePlot(size.width.toFloat(), size.height.toFloat(), inset, currentRange.first, currentRange.second)
                val p1 = Offset(plot.x(b[0]), plot.y(b[1])); val p2 = Offset(plot.x(b[2]), plot.y(b[3]))
                val d1 = (down.position - p1).getDistance(); val d2 = (down.position - p2).getDistance()
                val direct = when { d1 <= hit && d1 <= d2 -> 0; d2 <= hit -> 1; else -> null }
                val handle = direct ?: currentSelected
                val slop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                if (slop == null) {
                    // A tap: select the handle under the finger, or clear the selection on empty canvas.
                    select(direct)
                    return@awaitEachGesture
                }
                if (handle == null) return@awaitEachGesture
                val startPoint = if (handle == 0) p1 else p2
                val grab = startPoint - down.position
                val startValue = floatArrayOf(b[handle * 2], b[handle * 2 + 1])
                frozen = currentRange
                select(handle)
                if (direct != null) loupe = handle
                begin()
                var completed = false
                try {
                    completed = drag(slop.id) { change ->
                        change.consume()
                        val (x, y) = if (direct != null) {
                            val at = change.position + grab
                            plot.valueX(at.x) to plot.valueY(at.y)
                        } else {
                            val delta = change.position - down.position
                            (startValue[0] + delta.x * RELATIVE / plot.plotWidth) to
                                (startValue[1] - delta.y * RELATIVE / plot.plotHeight * (plot.high - plot.low))
                        }
                        moved(EaseMath.moveHandle(b, handle, x, y, snapOn, mirrorOn))
                    }
                } finally {
                    loupe = null; frozen = null
                    if (completed) end() else cancel()
                }
            }
        }) {
        EaseCurveLayer(curve, ghost, range, selected, inset)
        EasePlayheadLayer(curve, progress, range, inset)
        loupe?.let { EaseLoupe(curve, it, range, inset) }
    }
}

private const val RELATIVE = .35f

@Composable private fun EaseCurveLayer(curve: EasingCurve, ghost: EasingCurve?, range: Pair<Float, Float>, selected: Int?, inset: Float) {
    Spacer(Modifier.fillMaxSize().drawWithCache {
        val plot = EasePlot(size.width, size.height, inset, range.first, range.second)
        val path = pathOf(curve, plot); val ghostPath = ghost?.takeIf { it != curve }?.let { pathOf(it, plot) }
        val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val dashed = Stroke(1.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
        onDrawBehind {
            grid(plot)
            ghostPath?.let { drawPath(it, TextSecondary.copy(alpha = .55f), style = dashed) }
            drawPath(path, TextPrimary, style = stroke)
            curve.bezierOrNull()?.let { b ->
                val p1 = Offset(plot.x(b[0]), plot.y(b[1])); val p2 = Offset(plot.x(b[2]), plot.y(b[3]))
                val line = 1.dp.toPx()
                drawLine(TextSecondary, Offset(plot.x(0f), plot.y(0f)), p1, line, StrokeCap.Round)
                drawLine(TextSecondary, Offset(plot.x(1f), plot.y(1f)), p2, line, StrokeCap.Round)
                for ((i, p) in listOf(p1, p2).withIndex()) {
                    drawCircle(Highlight, 3.dp.toPx(), p)
                    if (selected == i) drawCircle(Highlight, 7.dp.toPx(), p, style = Stroke(1.2.dp.toPx()))
                }
            }
        }
    })
}

private fun DrawScope.grid(plot: EasePlot) {
    val thin = .5.dp.toPx(); val strong = 1.dp.toPx()
    for (i in 0..10) {
        val x = plot.x(i / 10f)
        drawLine(Border.copy(alpha = if (i == 0 || i == 10) 1f else .5f), Offset(x, plot.y(plot.high)), Offset(x, plot.y(plot.low)), if (i == 0 || i == 10) strong else thin)
    }
    var v = ceil(plot.low * 10f - 1e-4f) / 10f
    while (v <= plot.high + 1e-4f) {
        val emphasised = abs(v) < 1e-4f || abs(v - 1f) < 1e-4f
        drawLine(if (emphasised) TextSecondary else Border.copy(alpha = .5f), Offset(plot.x(0f), plot.y(v)), Offset(plot.x(1f), plot.y(v)), if (emphasised) strong else thin)
        v += .1f
    }
}

private fun pathOf(curve: EasingCurve, plot: EasePlot): Path {
    val pts = EaseSampling.points(curve)
    val path = Path()
    path.moveTo(plot.x(pts[0]), plot.y(pts[1]))
    var i = 2
    while (i < pts.size) { path.lineTo(plot.x(pts[i]), plot.y(pts[i + 1])); i += 2 }
    return path
}

@Composable private fun EasePlayheadLayer(curve: EasingCurve, progress: Float?, range: Pair<Float, Float>, inset: Float) {
    if (progress == null) return
    Canvas(Modifier.fillMaxSize().testTag("graph-playhead-dot")) {
        val plot = EasePlot(size.width, size.height, inset, range.first, range.second)
        val p = Offset(plot.x(progress), plot.y(curve.ease(progress)))
        drawLine(TextSecondary.copy(alpha = .6f), Offset(p.x, plot.y(plot.high)), Offset(p.x, plot.y(plot.low)), .75.dp.toPx())
        drawCircle(Surface, 5.dp.toPx(), p)
        drawCircle(TextPrimary, 4.dp.toPx(), p)
    }
}

/** Loupe: a bubble away from the finger with the handle's x, y and a 3× crop of the curve around it. */
@Composable private fun EaseLoupe(curve: EasingCurve, handle: Int, range: Pair<Float, Float>, inset: Float) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize().testTag("graph-loupe")) {
        val b = curve.bezierOrNull() ?: return@Canvas
        val plot = EasePlot(size.width, size.height, inset, range.first, range.second)
        val hx = b[handle * 2]; val hy = b[handle * 2 + 1]
        val at = Offset(plot.x(hx), plot.y(hy))
        val r = 26.dp.toPx(); val away = 58.dp.toPx()
        val above = at.y - away - r >= 0f
        val cx = (at.x + if (at.x > size.width / 2) -r * .6f else r * .6f).coerceIn(r, size.width - r)
        val cy = (if (above) at.y - away else at.y + away).coerceIn(r, size.height - r - 14.dp.toPx())
        val centre = Offset(cx, cy)
        drawCircle(Surface, r + 1.dp.toPx(), centre)
        val clip = Path().apply { addOval(Rect(centre, r)) }
        clipPath(clip) {
            withTransform({ translate(centre.x - at.x, centre.y - at.y); scale(3f, 3f, at) }) {
                grid(plot)
                drawPath(pathOf(curve, plot), TextPrimary, style = Stroke(.6.dp.toPx(), cap = StrokeCap.Round))
                drawCircle(Highlight, 1.2.dp.toPx(), at)
            }
        }
        drawCircle(TextPrimary, r, centre, style = Stroke(1.dp.toPx()))
        val label = measurer.measure("x ${"%.2f".format(java.util.Locale.ROOT, hx)}  y ${"%.2f".format(java.util.Locale.ROOT, hy)}",
            TextStyle(color = TextPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace))
        val tl = Offset((centre.x - label.size.width / 2f).coerceIn(0f, (size.width - label.size.width).coerceAtLeast(0f)),
            (centre.y + r + 2.dp.toPx()).coerceAtMost(size.height - label.size.height))
        drawRect(Surface, tl - Offset(3.dp.toPx(), 1.dp.toPx()), Size(label.size.width + 6.dp.toPx(), label.size.height + 2.dp.toPx()))
        drawText(label, topLeft = tl)
    }
}

