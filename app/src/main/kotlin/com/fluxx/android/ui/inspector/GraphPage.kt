package com.fluxx.android.ui.inspector

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import com.fluxx.android.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * GRAPH G1: the sheet-level host of the Graph page. Rails read it to enable their Graph icon for their focused property
 * ([InspectorUtilityRail.graphTarget]) and to open / close the page. Provided by [ElementInspectorSheet].
 */
internal class GraphHost(val layer: () -> CompositionLayer, private val target: () -> GraphTarget?,
    val open: (GraphTarget) -> Unit, val close: () -> Unit) {
    val openTarget: GraphTarget? get() = target()
    /** GRAPH G2: the open page's shared state (view, key selection, viewport, channel, library scroll); reset on open / close. */
    val session = GraphSession()
}
internal val LocalGraphHost = compositionLocalOf<GraphHost?> { null }

/**
 * GRAPH G1.1 Graph page geometry (dp), pure and JVM tested. The sheet draws the full-width header row (the same height
 * as every section page) and the usual CONTENT_TOP gap; the content below holds:
 * left rail (5 tiles) | centre item (touchpad over values bar) | right rail (types + presets, scrolls vertically).
 * Tiles shrink (never below 36dp) rather than the sheet growing; nothing but the right rail scrolls.
 */
internal object GraphLayout {
    const val RAIL = 40f
    const val RAIL_GAP = 4f
    const val LEFT_TILES = 5
    const val LEFT_GAP = 2f
    const val LEFT_MIN = 36f
    const val BAR = 40f
    const val HAIRLINE = 1f
    const val MAX_ASPECT = 2f

    /** One left-rail tile: 40dp when five fit with 2dp gaps, otherwise shrunk to fit (not below [LEFT_MIN]). */
    fun leftTile(content: Float): Float = ((content - (LEFT_TILES - 1) * LEFT_GAP) / LEFT_TILES).coerceIn(LEFT_MIN, RAIL)
    fun leftRailFits(content: Float) = LEFT_TILES * leftTile(content) + (LEFT_TILES - 1) * LEFT_GAP <= content + 1e-3f
    fun bar(fontScale: Float) = BAR * fontScale.coerceAtLeast(1f)

    /** GRAPH G2L: the type strip inside the centre item, its minimum, and the touchpad width it must leave at 360dp. */
    const val STRIP = 40f
    const val STRIP_MIN = 36f
    const val PAD_MIN_WIDTH = 170f
    /** Library header row (no title) and its icons' touch width; thumbnail drawing height and name lines. */
    const val LIBRARY_HEADER = 28f
    const val THUMB = 40f
    const val THUMB_NAME_LINE = 9f
    const val CAPTION = 14f

    /**
     * [padWidth] is the touchpad (and values bar) width, identical with the library closed and open. The right region
     * ([right]) holds hairline + type strip + gap + pinned rail when closed and the library column when open, so the
     * library is exactly the strip's + rail's combined width.
     */
    data class Metrics(val content: Float, val leftTile: Float, val centreWidth: Float, val padWidth: Float, val padHeight: Float, val bar: Float,
        val strip: Float, val right: Float, val libraryFraction: Float)
    /** [width] / [height] are the content area below the header (sheet frame and CONTENT_TOP already removed). */
    fun compute(width: Float, height: Float, fontScale: Float = 1f): Metrics {
        val bar = bar(fontScale)
        val centre = (width - RAIL - RAIL_GAP).coerceAtLeast(0f)
        val padHeight = (height - bar - HAIRLINE).coerceAtLeast(0f)
        // Narrow the strip (not below 36dp) only if the touchpad would drop under 170dp.
        val strip = (centre - (HAIRLINE + RAIL_GAP + RAIL) - PAD_MIN_WIDTH).coerceIn(STRIP_MIN, STRIP)
        val right = HAIRLINE + strip + RAIL_GAP + RAIL
        // Wider than 2:1 is left as margin after the centre item.
        val padWidth = minOf((centre - right).coerceAtLeast(0f), padHeight * MAX_ASPECT)
        return Metrics(height, leftTile(height), centre, padWidth, padHeight, bar, strip, right, right / width)
    }
    /** One library thumbnail item: drawing + up to two name lines + padding. */
    fun thumbItem(fontScale: Float) = THUMB + 2f + 2 * THUMB_NAME_LINE * fontScale.coerceAtLeast(1f) + 6f
    fun caption(fontScale: Float) = CAPTION * fontScale.coerceAtLeast(1f)
    /** Whole thumbnails visible in the library below its header. */
    fun thumbsVisible(height: Float, fontScale: Float) = (height - LIBRARY_HEADER) / thumbItem(fontScale)

    /**
     * GRAPH G2 (A1): the library is a 2-column grid. One cell: a curve thumbnail [GRID_THUMB_H] tall over a name of up to
     * two 7sp lines ([GRID_NAME_LINE] each). Captions span both columns.
     */
    const val GRID_THUMB_H = 24f
    const val GRID_NAME_LINE = 7.5f
    const val GRID_CAPTION = 12f
    fun gridRow(fontScale: Float) = GRID_THUMB_H + 2f + 2 * GRID_NAME_LINE * fontScale.coerceAtLeast(1f)
    fun gridCaption(fontScale: Float) = GRID_CAPTION * fontScale.coerceAtLeast(1f)
    /** Grid rows visible below the header and the first caption (fractional). */
    fun gridRowsVisible(height: Float, fontScale: Float) = (height - LIBRARY_HEADER - gridCaption(fontScale)) / gridRow(fontScale)

    /** GRAPH G2 (A2): the library icon's fade ends this far beyond the icon (left and top); the same open and closed. */
    const val SCRIM_EXTENT = 6f
    fun scrimSize() = RAIL + SCRIM_EXTENT
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
    /** The pad's normalised coordinates (Bounce / Elastic): 0..1 across the plot rectangle, 0 at the bottom. */
    fun padX(px: Float) = (px - left) / plotWidth
    fun padY(py: Float) = 1f - (py - top) / plotHeight
    fun padToX(u: Float) = left + u * plotWidth
    fun padToY(v: Float) = top + (1f - v) * plotHeight
    companion object {
        const val DEFAULT_LOW = -.15f; const val DEFAULT_HIGH = 1.15f
        /** Y range: about [−0.15, 1.15], grown to fit the curve's extremes and its handles with a 0.1 margin. */
        fun range(curve: EasingCurve): Pair<Float, Float> {
            val e = EasingMath.extent(curve)
            var lo = minOf(DEFAULT_LOW, e[0] - .1f); var hi = maxOf(DEFAULT_HIGH, e[1] + .1f)
            curve.bezierOrNull()?.let { lo = minOf(lo, it[1] - .1f, it[3] - .1f); hi = maxOf(hi, it[1] + .1f, it[3] + .1f) }
            return lo to hi
        }
    }
}

/**
 * GRAPH G1.1: Bounce and Elastic are edited on the canvas as an XY pad with one handle. x and y are 0..1 across the
 * plot rectangle (0 at the bottom). Bounce: x → count 1–8 (whole bounces), y → height (restitution) 0.1–0.9.
 * Elastic: x → oscillations 1–10, y → damping 0.1–1.
 */
internal object EasePad {
    fun applies(curve: EasingCurve) = curve.type == EasingPreset.BOUNCE || curve.type == EasingPreset.ELASTIC
    fun curve(type: EasingPreset, x: Float, y: Float): EasingCurve {
        val u = x.coerceIn(0f, 1f); val v = y.coerceIn(0f, 1f)
        return when (type) {
            EasingPreset.BOUNCE -> EasingCurve.bounce((1f + u * 7f).roundToInt().toFloat().coerceIn(1f, 8f), (.1f + v * .8f).coerceIn(.1f, .9f))
            EasingPreset.ELASTIC -> EasingCurve.elastic((1f + u * 9f).coerceIn(1f, 10f), (.1f + v * .9f).coerceIn(.1f, 1f))
            else -> throw IllegalArgumentException("The XY pad edits Bounce and Elastic only")
        }
    }
    /** The handle's pad position for [curve]. */
    fun position(curve: EasingCurve): Pair<Float, Float> = when (curve.type) {
        EasingPreset.BOUNCE -> ((curve.a.roundToInt().coerceIn(1, 8) - 1) / 7f) to ((curve.b - .1f) / .8f).coerceIn(0f, 1f)
        EasingPreset.ELASTIC -> ((curve.a - 1f) / 9f).coerceIn(0f, 1f) to ((curve.b - .1f) / .9f).coerceIn(0f, 1f)
        else -> throw IllegalArgumentException("The XY pad edits Bounce and Elastic only")
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

/**
 * The single source of ranges and labels for the values bar (and the pad's ranges). Ranges: x1, x2 ∈ [0, 1];
 * y1, y2 ∈ [−2, 3]; Bounces 1–8; Height 0.1–0.9; Oscillations 1–10; Damping 0.1–1.
 */
internal object EaseLimits {
    val X1 = PropertyLimit("x1", "", 0f, 1f, 0f, 1f, 0f, 1f, .33f, detentStep = .1f, step = .01f)
    val Y1 = PropertyLimit("y1", "", -1f, 2f, -2f, 3f, -2f, 3f, 0f, detentStep = .5f, step = .01f)
    val X2 = PropertyLimit("x2", "", 0f, 1f, 0f, 1f, 0f, 1f, .67f, detentStep = .1f, step = .01f)
    val Y2 = PropertyLimit("y2", "", -1f, 2f, -2f, 3f, -2f, 3f, 1f, detentStep = .5f, step = .01f)
    val BOUNCES = PropertyLimit("Bounces", "", 1f, 8f, 1f, 8f, 1f, 8f, EasingLimits.BOUNCE_DEFAULT_COUNT, ladder = (1..8).map { it.toFloat() }, step = 1f)
    val RESTITUTION = PropertyLimit("Height", "", .1f, .9f, .1f, .9f, .1f, .9f, EasingLimits.BOUNCE_DEFAULT_RESTITUTION, detentStep = .1f, step = .01f)
    val OSCILLATIONS = PropertyLimit("Oscillations", "", 1f, 10f, 1f, 10f, 1f, 10f, EasingLimits.ELASTIC_DEFAULT_OSCILLATIONS, detentStep = 1f, step = .1f)
    val DAMPING = PropertyLimit("Damping", "", .1f, 1f, .1f, 1f, .1f, 1f, EasingLimits.ELASTIC_DEFAULT_DAMPING, detentStep = .1f, step = .01f)
    /** (limit, read, write) per editable parameter of [curve]'s type; writing a bezier value makes the curve Custom. */
    fun rows(curve: EasingCurve): List<Triple<PropertyLimit, Float, (Float) -> EasingCurve>> {
        val b = curve.bezierOrNull()
        return when {
            curve.type == EasingPreset.BOUNCE -> listOf(
                Triple(BOUNCES, curve.a) { v -> curve.copy(a = v.roundToInt().toFloat().coerceIn(1f, 8f)) },
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
    /** The values-bar text of one parameter: Bounces as an integer, Oscillations 1 decimal, the rest 2 decimals. */
    fun format(limit: PropertyLimit, value: Float): String = when (limit) {
        BOUNCES -> value.roundToInt().toString()
        OSCILLATIONS -> String.format(java.util.Locale.ROOT, "%.1f", value)
        else -> String.format(java.util.Locale.ROOT, "%.2f", value)
    }
    /** A typed value: parsed, clamped by the writer; null (discarded) when not a finite number. */
    fun typed(curve: EasingCurve, index: Int, text: String): EasingCurve? {
        val row = rows(curve).getOrNull(index) ?: return null
        val v = ValueParsing.number(text)?.toFloat()?.takeIf { it.isFinite() } ?: return null
        return row.third(v)
    }
}

/** The right rail's type tiles, top to bottom (no Custom tile: dragging a handle makes a curve Custom). */
internal val GRAPH_TYPE_TILES = listOf(EaseChoice.HOLD, EaseChoice.LINEAR, EaseChoice.EASY_EASE, EaseChoice.EASE_IN, EaseChoice.EASE_OUT,
    EaseChoice.BOUNCE, EaseChoice.ELASTIC)
internal val CHOICE_ICONS = mapOf(EaseChoice.HOLD to "EASE_HOLD", EaseChoice.LINEAR to "EASE_LINEAR", EaseChoice.EASY_EASE to "EASE_EASY",
    EaseChoice.EASE_IN to "EASE_IN", EaseChoice.EASE_OUT to "EASE_OUT", EaseChoice.CUSTOM to "EASE_CUSTOM",
    EaseChoice.BOUNCE to "EASE_BOUNCE", EaseChoice.ELASTIC to "EASE_ELASTIC")
/** Which right-rail type tile is highlighted for [curve], or null (a Custom curve matching no tile). */
internal fun graphTypeTile(curve: EasingCurve): EaseChoice? = EaseChoice.of(curve).takeIf { it != EaseChoice.CUSTOM }

/**
 * GRAPH G3: the last-used Graph tab (persisted app-level in the curve library file). The page opens on it; Value and
 * Speed fall back to Ease where unavailable (colour, fewer than two keys).
 */
internal object GraphTabs {
    fun parse(name: String?): GraphMode = GraphMode.entries.firstOrNull { it.name == name } ?: GraphMode.EASE
    fun stored(context: android.content.Context): GraphMode = parse(CurveLibraryStore.get(context).state.value.graphTab)
    fun effective(mode: GraphMode, valueAvailable: Boolean): GraphMode = if (mode != GraphMode.EASE && !valueAvailable) GraphMode.EASE else mode
    fun remember(store: CurveLibraryStore, mode: GraphMode) = store.update { it.copy(graphTab = mode.name) }
}

/**
 * GRAPH G1.1 header contents (the sheet places it in the shared header row): back arrow (returns, like the Graph icon),
 * the Value | Speed | Ease mode icons (Value and Speed visible, disabled, inert) and the two-line segment label.
 */
@Composable internal fun GraphPageHeader(layer: CompositionLayer, target: GraphTarget, playheadUs: Long, frameRate: FrameRate, onBack: () -> Unit,
    session: GraphSession = remember { GraphSession() }, compositionWidth: Int = 1080, compositionHeight: Int = 1920) {
    // TLP.2: while key selection is active the key bar takes the header's place, at exactly its height.
    com.fluxx.android.ui.editor.LocalKeyHeaderBar.current?.let { bar -> com.fluxx.android.ui.editor.KeyHeaderBar(InspectorChromeBudget.header(LocalDensity.current.fontScale).dp, bar); return }
    val local = GraphModel.local(layer, target, playheadUs, frameRate)
    val segment = GraphSelection.segment(layer, target, session.selection, playheadUs, local)
    val valueAvailable = GraphValues.available(layer, target)
    val mode = GraphTabs.effective(session.mode, valueAvailable)
    val picked = GraphSelection.valid(session.selection, GraphModel.keyTimes(layer, target), playheadUs)
    val tabStore = CurveLibraryStore.get(androidx.compose.ui.platform.LocalContext.current)
    Row(Modifier.fillMaxWidth().height(InspectorChromeBudget.header(LocalDensity.current.fontScale).dp).testTag("graph-header"),
        verticalAlignment = Alignment.CenterVertically) {
        InspectorIconButton(InspectorIcons.icon("BACK"), "Back from the graph editor", Modifier.testTag("graph-back")) { onBack() }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GraphModeTile("GRAPH_VALUE", if (valueAvailable) "Value graph" else "Value graph (not available for colour)", enabled = valueAvailable,
                selected = mode == GraphMode.VALUE) { if (session.mode != GraphMode.VALUE) { session.mode = GraphMode.VALUE; session.viewport = null }; GraphTabs.remember(tabStore, GraphMode.VALUE) }
            GraphModeTile("GRAPH_SPEED", if (valueAvailable) "Speed graph" else "Speed graph (not available for colour)", enabled = valueAvailable,
                selected = mode == GraphMode.SPEED) { if (session.mode != GraphMode.SPEED) { session.mode = GraphMode.SPEED; session.speedViewport = null }; GraphTabs.remember(tabStore, GraphMode.SPEED) }
            GraphModeTile("GRAPH_EASE", "Ease view", enabled = true, selected = mode == GraphMode.EASE) { session.mode = GraphMode.EASE; GraphTabs.remember(tabStore, GraphMode.EASE) }
        }
        Spacer(Modifier.weight(1f))
        if (segment != null) {
            // GRAPH G2.1 §5: the page's own units and formatting.
            val values = GraphValues.labels(layer, target, segment, compositionWidth, compositionHeight)
            val context = androidx.compose.ui.platform.LocalContext.current
            val libraryState by remember(context) { CurveLibraryStore.get(context) }.state.collectAsState()
            val curveName = CurveLibrary.nameFor(libraryState, segment.curve)
            Column(Modifier.widthIn(max = 132.dp).padding(end = 4.dp).semantics(mergeDescendants = true) {
                contentDescription = "${segment.label} of ${segment.count}, ${values.first} to ${values.second}, ${seconds(segment.durationUs)}, curve $curveName; ${if (picked) "the selected key's segment" else "the segment at the playhead"}"
            }, horizontalAlignment = Alignment.End) {
                Text("${segment.label} · ${seconds(segment.durationUs)}", color = TextPrimary, style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.testTag("graph-segment-label"))
                Text(curveName, color = TextSecondary, style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
            }
        }
    }
}

private fun seconds(us: Long) = String.format(java.util.Locale.ROOT, "%.2fs", us / 1_000_000.0)

@Composable private fun GraphModeTile(icon: String, label: String, enabled: Boolean, selected: Boolean, onClick: () -> Unit = {}) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(if (selected) Surface else Color.Transparent)
        .clickable(enabled = enabled, role = Role.Tab) { onClick() }
        .semantics { contentDescription = label; if (!enabled) disabled(); if (selected) this.selected = true }.testTag("graph-mode-$icon"),
        contentAlignment = Alignment.Center) {
        Icon(InspectorIcons.icon(icon), null, Modifier.size(20.dp), tint = if (!enabled) TextDisabled else if (selected) TextPrimary else TextSecondary)
    }
}

/**
 * The Graph page body (Ease view): left rail | touchpad over values bar | right rail. Every change is
 * [EditorAction.SetKeyEasing]: handle / pad drags use begin / preview / commit / cancel (one undo per drag); taps (type,
 * preset, invert, paste, reset, apply to all, typed values) are one action each. Nothing here seeks: the playhead is
 * only read. Non-modal: the preview, timeline, transport and the rail stay live.
 */
@Composable internal fun GraphPage(layer: CompositionLayer, target: GraphTarget, playheadUs: Long, frameRate: FrameRate,
    onAction: (EditorAction) -> Unit, onBegin: () -> Unit, onPreview: (EditorAction) -> Unit, onCommit: () -> Unit, onCancel: () -> Unit,
    onClose: () -> Unit, modifier: Modifier = Modifier, session: GraphSession = remember { GraphSession() },
    compositionWidth: Int = 1080, compositionHeight: Int = 1920, startTimecodeUs: Long = 0L) {
    val close by rememberUpdatedState(onClose)
    BackHandler { close() }
    val local = GraphModel.local(layer, target, playheadUs, frameRate)
    // GRAPH G2: a selected key's departing segment overrides the playhead's until the playhead moves.
    val segment = GraphSelection.segment(layer, target, session.selection, playheadUs, local)
    LaunchedEffect(playheadUs) { session.selection?.let { if (it.playheadUs != playheadUs) session.selection = null } }
    val available = GraphValues.available(layer, target)
    val valueMode = session.mode == GraphMode.VALUE && available
    val speedMode = session.mode == GraphMode.SPEED && available
    // The property lost its keys (undo, diamond, deletion) or the address disappeared: fall back to the page it came from.
    if (segment == null) { LaunchedEffect(Unit) { close() }; Box(modifier.fillMaxSize().testTag("graph-page")); return }
    val keyTime = segment.startUs
    var draft by remember(target, keyTime) { mutableStateOf<EasingCurve?>(null) }
    var ghost by remember(target, keyTime) { mutableStateOf<EasingCurve?>(null) }
    var selectedHandle by remember(target, keyTime) { mutableStateOf<Int?>(null) }
    var mirror by rememberSaveable { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val curve = draft ?: segment.curve
    val stored by rememberUpdatedState(segment.curve)
    val focus = LocalFocusManager.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val cancel by rememberUpdatedState(onCancel)
    DisposableEffect(target, keyTime) { onDispose { if (dragging) { dragging = false; cancel() } } }

    // GRAPH G1.1 playhead fix: the handlers below are handed to long-lived gesture coroutines and memoized callable
    // references, so they must never capture this composition's values. Every input goes through an always-current
    // holder; otherwise a drag after Prev/Next would commit with the callbacks (and the playhead) from when the page opened.
    val latestAction by rememberUpdatedState(onAction); val latestBegin by rememberUpdatedState(onBegin)
    val latestPreview by rememberUpdatedState(onPreview); val latestCommit by rememberUpdatedState(onCommit)
    val latestCancel by rememberUpdatedState(onCancel)
    val latestLayerId by rememberUpdatedState(layer.id); val latestTarget by rememberUpdatedState(target)
    val latestKey by rememberUpdatedState(keyTime)
    fun edit(c: EasingCurve, all: Boolean = false) {
        focus.clearFocus()
        if (!all && EaseMath.same(c, stored)) return
        ghost = stored
        latestAction(GraphModel.action(latestLayerId, latestTarget, if (all) null else listOf(latestKey), c))
        // GRAPH G4.3: a one-shot edit in the Speed view asks the canvas to check (once) that the handles still fit its window.
        if (!dragging && session.mode == GraphMode.SPEED) session.reframeCurve = c
    }
    fun begin() { if (!dragging) { dragging = true; ghost = stored; latestBegin() } }
    fun preview(c: EasingCurve) { if (dragging) { draft = c; latestPreview(GraphModel.action(latestLayerId, latestTarget, listOf(latestKey), c)) } }
    /** GRAPH G3: a handle edit as ONE action: the segment's SetKeyEasing, or a Batch with the neighbours' (Smooth through key). */
    fun editAction(e: HandleEdit): EditorAction {
        val own = GraphModel.action(latestLayerId, latestTarget, listOf(latestKey), e.curve)
        return if (e.others.isEmpty()) own else EditorAction.Batch(listOf(own) + e.others.map { (k, c) -> GraphModel.action(latestLayerId, latestTarget, listOf(k), c) })
    }
    fun previewEdit(e: HandleEdit) { if (dragging) { draft = e.curve; latestPreview(editAction(e)) } }
    fun commit() { if (dragging) { dragging = false; latestCommit(); draft = null } }
    fun cancelDrag() { if (dragging) { dragging = false; latestCancel(); draft = null } }
    // GRAPH G2.1 §3: keys are not draggable; a tap selects, a double tap seeks, the values bar types time and value.
    val latestPlayhead by rememberUpdatedState(playheadUs)
    fun selectKey(time: Long?) { focus.clearFocus(); session.selection = time?.let { KeySelection(it, latestPlayhead) } }
    // Double tap on a key: the one graph gesture that moves the playhead (Seek, as Prev/Next; frame-snapped).
    fun seekKey(time: Long) { focus.clearFocus(); latestAction(EditorAction.Seek(Math.addExact(layer.resolvedKeyframeAnchorUs, time), snap = true)) }
    val onKey = local in GraphModel.keyTimes(layer, target)
    // GRAPH G2L: the curve library (app-level). Closed by default; leaving the Graph page resets it.
    val context = androidx.compose.ui.platform.LocalContext.current
    val libraryStore = remember(context) { CurveLibraryStore.get(context) }
    val library by libraryStore.state.collectAsState()
    // GRAPH G2.1 §9: Snap is on by default and persisted app-level (like "Show playhead dot"); Mirror is per page.
    val snap = library.graphSnap
    val libraryUi = remember { GraphLibraryUi() }
    var libraryOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = libraryOpen) { libraryOpen = false }
    // Animator duration scale 0 (developer option) shows the end state immediately.
    val animate = remember(context) { runCatching { android.provider.Settings.Global.getFloat(context.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) > 0f }
    val libraryProgress by androidx.compose.animation.core.animateFloatAsState(if (libraryOpen) 1f else 0f,
        if (animate) androidx.compose.animation.core.tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing)
        else androidx.compose.animation.core.snap(), label = "graph-library")

    BoxWithConstraints(modifier.fillMaxSize().dismissPopoversOnEmptyTap().testTag("graph-page")) {
        val m = GraphLayout.compute(maxWidth.value, maxHeight.value, LocalDensity.current.fontScale)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(GraphLayout.RAIL_GAP.dp)) {
            GraphLeftRail(m.leftTile, curve, onKey, snap, mirror,
                onToggleKey = { GraphModel.toggleKey(layer, target, playheadUs, frameRate)?.let(onAction) },
                onGraph = { close() },
                onInvert = { EaseMath.invert(stored)?.let { edit(it) } },
                onSnap = { libraryStore.update { it.copy(graphSnap = !it.graphSnap) } },
                smooth = library.smoothThroughKey, onSmooth = { libraryStore.update { it.copy(smoothThroughKey = !it.smoothThroughKey) } }, onMirror = { mirror = !mirror }, onApplyAll = { edit(stored, all = true) },
                onCopy = { CubicBezierText.format(stored)?.let { text ->
                    scope.launch { runCatching { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("cubic-bezier", text))) } } } },
                onPaste = { scope.launch {
                    val text = runCatching { clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() }.getOrNull()
                    CubicBezierText.parse(text)?.let { edit(it) } } },
                onReset = { edit(EasingCurve.EASY_EASE) }, showDot = library.showPlayheadDot,
                onToggleDot = { libraryStore.update { it.copy(showPlayheadDot = !it.showPlayheadDot) } },
                // GRAPH G4.1: the selected (or playhead) Bounce / Elastic segment becomes plain keys, one undo.
                convertEnabled = GraphConvert.enabled(layer, target, segment),
                onConvert = { GraphConvert.action(layer, target, segment, frameRate)?.let { focus.clearFocus(); latestAction(it) } })
            // Centre: ONE rounded item (touchpad over values bar, type strip on its right edge), then the right column.
            // With the library open the strip and the pinned rail fold into the library column, which takes exactly their
            // combined width, so the touchpad and the values bar never move or resize.
            val p = libraryProgress
            Box(Modifier.width((m.padWidth + m.right).dp).fillMaxHeight().testTag("graph-right-region")) {
                val stripShown = (m.strip + GraphLayout.HAIRLINE) * (1f - p)
                Row(Modifier.width((m.padWidth + stripShown).dp).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(SurfaceHigh)
                    .border(.5.dp, Border, RoundedCornerShape(8.dp)).testTag("graph-centre")) {
                    Column(Modifier.width(m.padWidth.dp).fillMaxHeight().testTag("graph-pad-column")) {
                        if (valueMode || speedMode) {
                            val axes = remember(layer, target, compositionWidth, compositionHeight) { GraphValues.axes(layer, target, compositionWidth, compositionHeight) }
                            val channel = session.channel.coerceIn(0, (axes.size - 1).coerceAtLeast(0))
                            val keyTimes = GraphModel.keyTimes(layer, target)
                            val picked = session.selection?.takeIf { GraphSelection.valid(it, keyTimes, playheadUs) }?.let { keyTimes.indexOf(it.keyTimeUs) }
                            val smooth = library.smoothThroughKey
                            val scene = remember(layer, target, frameRate, axes, channel, segment, picked, startTimecodeUs, speedMode, smooth) {
                                ValueScene(layer, target, frameRate, axes, channel, segment.copy(curve = curve), picked, startTimecodeUs, speed = speedMode, smooth = smooth) }
                            ValueCanvas(scene, session, local, library.showPlayheadDot, snap, mirror,
                                onSelectKey = ::selectKey, onSeekKey = ::seekKey,
                                onHandleBegin = ::begin, onHandleDrag = ::previewEdit, onHandleEnd = ::commit, onHandleCancel = ::cancelDrag,
                                modifier = Modifier.fillMaxWidth().height(m.padHeight.dp).testTag(if (speedMode) "graph-speed-canvas" else "graph-value-canvas"))
                            Box(Modifier.fillMaxWidth().height(GraphLayout.HAIRLINE.dp).background(Border))
                            if (speedMode) SpeedValuesBar(scene, m.bar) { e -> focus.clearFocus(); latestAction(editAction(e)) } else {
                            // GRAPH G2.1 §6: the live value at the playhead (as the page shows it) when no key is selected.
                            val live = remember(layer, target, playheadUs, frameRate) { GraphValues.live(layer, target, playheadUs, frameRate) }
                            ValueValuesBar(scene, picked, m.bar, live, onChannel = { session.channel = it },
                                onCommit = { action, time ->
                                    focus.clearFocus(); latestAction(action)
                                    if (time != null) session.selection = KeySelection(time, latestPlayhead) },
                                onTypedAtPlayhead = { x, y -> GraphValues.atPlayheadAction(layer, target, latestPlayhead, frameRate, x, y) },
                                playheadUs = playheadUs) }
                        } else {
                            EaseCanvas(curve, ghost, GraphModel.progress(segment, local), selectedHandle, snap, mirror, library.showPlayheadDot,
                                onSelect = { selectedHandle = it }, onBegin = ::begin, onDrag = ::preview, onEnd = ::commit, onCancel = ::cancelDrag,
                                modifier = Modifier.fillMaxWidth().height(m.padHeight.dp).testTag("graph-ease-canvas"))
                            Box(Modifier.fillMaxWidth().height(GraphLayout.HAIRLINE.dp).background(Border))
                            EaseValuesBar(curve, m.bar, onTyped = { edit(it) })
                        }
                    }
                    if (p < 1f) {
                        Box(Modifier.width(GraphLayout.HAIRLINE.dp).fillMaxHeight().background(Border))
                        GraphTypeStrip(m.strip, curve, onType = { edit(EaseChoice.curve(it, stored)) },
                            Modifier.graphicsLayer { alpha = 1f - p })
                    }
                }
                val railX = m.padWidth + m.strip + GraphLayout.HAIRLINE + GraphLayout.RAIL_GAP
                if (p < 1f) GraphPinnedRail(library, curve, libraryStore, libraryUi, onApply = { edit(it) },
                    Modifier.offset(x = railX.dp).width(GraphLayout.RAIL.dp).fillMaxHeight().graphicsLayer { alpha = 1f - p })
                if (p > 0f) {
                    val x = m.padWidth + stripShown + GraphLayout.RAIL_GAP
                    GraphLibraryColumn(library, curve, libraryStore, libraryUi, session.libraryGrid, onApply = { edit(it) },
                        Modifier.offset(x = x.dp).width((m.padWidth + m.right - x).dp).fillMaxHeight().graphicsLayer { alpha = p })
                }
                // The library toggle: fixed at the bottom of the right column, the same position open and closed.
                GraphLibraryButton(libraryOpen, onToggle = { libraryOpen = !libraryOpen }, Modifier.align(Alignment.BottomEnd))
            }
        }
    }
}

/** The Graph page's own left rail (other pages keep [InspectorUtilityRail] unchanged). */
@Composable private fun GraphLeftRail(tile: Float, curve: EasingCurve, onKey: Boolean, snap: Boolean, mirror: Boolean,
    onToggleKey: () -> Unit, onGraph: () -> Unit, onInvert: () -> Unit, onSnap: () -> Unit,
    smooth: Boolean, onSmooth: () -> Unit, onMirror: () -> Unit, onApplyAll: () -> Unit,
    onCopy: () -> Unit, onPaste: () -> Unit, onReset: () -> Unit, showDot: Boolean, onToggleDot: () -> Unit,
    convertEnabled: Boolean = false, onConvert: () -> Unit = {}) {
    val focus = LocalFocusManager.current
    val handles = curve.bezierOrNull() != null
    var tools by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val t = Modifier.size(GraphLayout.RAIL.dp, tile.dp)
    Column(Modifier.width(GraphLayout.RAIL.dp).fillMaxHeight().testTag("graph-left-rail"), verticalArrangement = Arrangement.spacedBy(GraphLayout.LEFT_GAP.dp)) {
        InspectorIconButton(InspectorIcons.icon(if (onKey) "KEY_ON" else "KEY_ANIM"), if (onKey) "Remove keyframe at playhead" else "Add keyframe at playhead",
            t.testTag("inspector-key-diamond"), tint = Highlight) { focus.clearFocus(); onToggleKey() }
        InspectorIconButton(InspectorIcons.icon("GRAPH"), "Close graph editor", t.testTag("inspector-rail-graph").semantics { selected = true },
            selected = true, tint = Highlight) { focus.clearFocus(); PopoverDismissal.request(); onGraph() }
        InspectorIconButton(InspectorIcons.icon("EASE_INVERT"), "Invert curve (swap ease in and out)", t.testTag("graph-tool-invert"),
            enabled = EaseMath.invert(curve) != null) { onInvert() }
        Box {
            InspectorIconButton(InspectorIcons.icon("GRAPH_TOOLS"), "Graph tools", t.testTag("graph-tools"), selected = tools || snap || mirror || smooth,
                tint = if (snap || mirror || smooth) Highlight else null) {
                // Decide the toggle first: a dismissal request closes an open popover synchronously (Main.immediate).
                val open = PopoverToggle.next(if (tools) "tools" else null, "tools") != null
                if (open) { PopoverDismissal.request(); tools = true } else tools = false
            }
            AnchoredPopover(tools, { tools = false }, "graph-tools-popover", modal = false) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InspectorIconButton(InspectorIcons.icon("EASE_SNAP"), "Snap handles to 0.05", Modifier.testTag("graph-tool-snap"), enabled = handles,
                        selected = snap) { onSnap() }
                    InspectorIconButton(InspectorIcons.icon("EASE_MIRROR"), "Mirror handles", Modifier.testTag("graph-tool-mirror"), enabled = handles,
                        selected = mirror) { onMirror() }
                    // GRAPH G3: off by default, persisted; applies to the Speed graph's handle drags and typed speeds.
                    InspectorIconButton(InspectorIcons.icon("GRAPH_SMOOTH"), "Smooth through key (Speed graph)", Modifier.testTag("graph-tool-smooth")
                        .semantics { stateDescription = if (smooth) "On" else "Off" }, selected = smooth) { onSmooth() }
                    InspectorIconButton(InspectorIcons.icon("EASE_APPLY_ALL"), "Apply to all keys", Modifier.testTag("graph-tool-apply-all")) { onApplyAll() }
                }
            }
        }
        Box {
            InspectorIconButton(InspectorIcons.icon("OVERFLOW"), "Curve options", t.testTag("inspector-rail-menu"), tint = TextPrimary) { focus.clearFocus(); menu = true }
            DropdownMenu(menu, { menu = false }, Modifier.testTag("graph-menu")) {
                DropdownMenuItem(text = { Text("Copy cubic-bezier") }, enabled = handles, onClick = { menu = false; onCopy() }, modifier = Modifier.testTag("graph-menu-copy"))
                DropdownMenuItem(text = { Text("Paste cubic-bezier") }, onClick = { menu = false; onPaste() }, modifier = Modifier.testTag("graph-menu-paste"))
                DropdownMenuItem(text = { Text("Reset curve to Easy Ease") }, onClick = { menu = false; onReset() }, modifier = Modifier.testTag("graph-menu-reset"))
                DropdownMenuItem(text = { Text("Convert to keys") }, enabled = convertEnabled, onClick = { menu = false; onConvert() },
                    modifier = Modifier.testTag("graph-menu-convert"))
                DropdownMenuItem(text = { Text("Show playhead dot") }, onClick = { menu = false; onToggleDot() },
                    trailingIcon = { Icon(InspectorIcons.icon(if (showDot) "EYE" else "EYE_OFF"), if (showDot) "On" else "Off", Modifier.size(InspectorIcons.INLINE), tint = TextPrimary) },
                    modifier = Modifier.testTag("graph-menu-dot").semantics { stateDescription = if (showDot) "On" else "Off" })
            }
        }
    }
}

/**
 * The values bar: one bar divided by hairlines, each tile a corner label (8sp, the Animate tiles' caption style) and a
 * centred monospace value. Tapping a tile opens the typed-entry popover (commit on IME Done / focus loss; invalid input
 * is discarded). Values follow the live draft while a handle is dragged.
 */
@Composable private fun EaseValuesBar(curve: EasingCurve, height: Float, onTyped: (EasingCurve) -> Unit) {
    val rows = EaseLimits.rows(curve)
    // GRAPH G2.1 §4: an opaque bar (the container's fill), so nothing draws through it.
    Row(Modifier.fillMaxWidth().height(height.dp).background(SurfaceHigh).testTag("graph-values-bar")) {
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().semantics { contentDescription = "Hold: no parameters"; disabled() }.testTag("graph-value-hold"), contentAlignment = Alignment.Center) {
                Text("Hold", color = TextDisabled, style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace))
            }
            return@Row
        }
        rows.forEachIndexed { i, (limit, value, _) ->
            if (i > 0) Box(Modifier.width(GraphLayout.HAIRLINE.dp).fillMaxHeight().background(Border))
            EaseValueTile(limit, value, Modifier.weight(1f).fillMaxHeight().testTag("graph-value-$i")) { text ->
                EaseLimits.typed(curve, i, text)?.let(onTyped)
            }
        }
    }
}

@Composable internal fun EaseValueTile(limit: PropertyLimit, value: Float, modifier: Modifier, onCommit: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val text = EaseLimits.format(limit, value)
    Box(modifier.background(if (editing) Surface else SurfaceHigh)
        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Type ${limit.name}") { editing = true }
        .semantics(mergeDescendants = true) { contentDescription = "${limit.name} $text" }) {
        Text(limit.name, Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 2.dp), color = TextSecondary, fontSize = 8.sp, lineHeight = 9.sp, maxLines = 1)
        Text(text, Modifier.align(Alignment.Center).padding(top = 6.dp), color = TextPrimary, maxLines = 1,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace))
        AnchoredPopover(editing, { editing = false }, "value-entry") {
            InspectorValueField(text, limit.name, limit.unit, { typed -> editing = false; onCommit(typed) }, { editing = false })
        }
    }
}

/**
 * The Ease canvas (touchpad). Bezier types: direct drag on a handle (48dp target around a 6dp dot); after a handle is
 * selected by a tap, a drag that starts on empty canvas moves it relatively (trackpad style, 0.35×). Bounce / Elastic:
 * the whole canvas is an XY pad with one always-selected handle ([EasePad]). The curve layer redraws only when the
 * curve, ghost or range changes; the playhead dot is its own layer.
 */
@Composable private fun EaseCanvas(curve: EasingCurve, ghost: EasingCurve?, progress: Float?, selected: Int?, snap: Boolean, mirror: Boolean, showDot: Boolean,
    onSelect: (Int?) -> Unit, onBegin: () -> Unit, onDrag: (EasingCurve) -> Unit, onEnd: () -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    val density = LocalDensity.current
    val inset = with(density) { 10.dp.toPx() }
    val hit = with(density) { 24.dp.toPx() }
    var frozen by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val natural = remember(curve) { EasePlot.range(curve) }
    val range = frozen?.let { minOf(it.first, natural.first) to maxOf(it.second, natural.second) } ?: natural
    val current by rememberUpdatedState(curve); val currentRange by rememberUpdatedState(range)
    val currentSelected by rememberUpdatedState(selected)
    val snapOn by rememberUpdatedState(snap); val mirrorOn by rememberUpdatedState(mirror)
    val select by rememberUpdatedState(onSelect); val begin by rememberUpdatedState(onBegin)
    val moved by rememberUpdatedState(onDrag); val end by rememberUpdatedState(onEnd); val cancel by rememberUpdatedState(onCancel)
    val description = CubicBezierText.format(curve) ?: when (curve.type) {
        EasingPreset.HOLD -> "Hold"; EasingPreset.BOUNCE -> "Bounce, ${curve.a.roundToInt()} bounces"; else -> "Elastic, ${"%.1f".format(curve.a)} oscillations" }
    Box(modifier.semantics { contentDescription = "Ease curve: $description" }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val c = current
                val plot = EasePlot(size.width.toFloat(), size.height.toFloat(), inset, currentRange.first, currentRange.second)
                if (EasePad.applies(c)) {
                    // XY pad: direct drag on the dot, otherwise a relative drag of the (always selected) handle.
                    val (u, v) = EasePad.position(c)
                    val dot = Offset(plot.padToX(u), plot.padToY(v))
                    val direct = (down.position - dot).getDistance() <= hit
                    val slop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
                    val grab = dot - down.position
                    begin()
                    var completed = false
                    try {
                        completed = drag(slop.id) { change ->
                            change.consume()
                            val (x, y) = if (direct) { val at = change.position + grab; plot.padX(at.x) to plot.padY(at.y) } else {
                                val d = change.position - down.position
                                (u + d.x * RELATIVE / plot.plotWidth) to (v - d.y * RELATIVE / plot.plotHeight) }
                            moved(EasePad.curve(c.type, x, y))
                        }
                    } finally { if (completed) end() else cancel() }
                    return@awaitEachGesture
                }
                val b = c.bezierOrNull() ?: return@awaitEachGesture
                val p1 = Offset(plot.x(b[0]), plot.y(b[1])); val p2 = Offset(plot.x(b[2]), plot.y(b[3]))
                val d1 = (down.position - p1).getDistance(); val d2 = (down.position - p2).getDistance()
                val direct = when { d1 <= hit && d1 <= d2 -> 0; d2 <= hit -> 1; else -> null }
                val handle = direct ?: currentSelected
                val slop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                if (slop == null) { select(direct); return@awaitEachGesture } // a tap selects / clears
                if (handle == null) return@awaitEachGesture
                val grab = (if (handle == 0) p1 else p2) - down.position
                val startValue = floatArrayOf(b[handle * 2], b[handle * 2 + 1])
                frozen = currentRange
                select(handle)
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
                    frozen = null
                    if (completed) end() else cancel()
                }
            }
        }) {
        EaseCurveLayer(curve, ghost, range, selected, inset)
        EasePlayheadLayer(curve, progress, range, inset, showDot)
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
            if (EasePad.applies(curve)) {
                // The pad handle: a dot with a faint crosshair.
                val (u, v) = EasePad.position(curve)
                val p = Offset(plot.padToX(u), plot.padToY(v))
                val faint = TextSecondary.copy(alpha = .45f); val thin = .75.dp.toPx()
                drawLine(faint, Offset(plot.left, p.y), Offset(plot.left + plot.plotWidth, p.y), thin)
                drawLine(faint, Offset(p.x, plot.top), Offset(p.x, plot.top + plot.plotHeight), thin)
                drawCircle(Highlight, 3.5.dp.toPx(), p)
                drawCircle(Highlight, 7.dp.toPx(), p, style = Stroke(1.2.dp.toPx()))
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

@Composable private fun EasePlayheadLayer(curve: EasingCurve, progress: Float?, range: Pair<Float, Float>, inset: Float, showDot: Boolean = true) {
    if (progress == null) return
    Canvas(Modifier.fillMaxSize().testTag("graph-playhead-dot")) {
        val plot = EasePlot(size.width, size.height, inset, range.first, range.second)
        val p = Offset(plot.x(progress), plot.y(curve.ease(progress)))
        drawLine(TextSecondary.copy(alpha = .6f), Offset(p.x, plot.y(plot.high)), Offset(p.x, plot.y(plot.low)), .75.dp.toPx())
        // "Show playhead dot" (overflow, app-level): off hides the dot; the vertical playhead line always stays.
        if (showDot) { drawCircle(Surface, 5.dp.toPx(), p); drawCircle(TextPrimary, 4.dp.toPx(), p) }
    }
}
