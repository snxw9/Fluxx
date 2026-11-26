package com.fluxx.android.ui.timeline

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxx.android.editor.*
import com.fluxx.android.model.CompositionLayer
import com.fluxx.android.model.ProjectDocument
import com.fluxx.android.ui.inspector.InspectorIcons
import com.fluxx.android.ui.theme.Highlight

/**
 * TL3 B2: ONE motion spec shared by every part of a twirl-down / inspector transition (the sheet's slide, the timeline
 * viewport's growth, the twirl rows, the D's width and the chevron's rotation). They all start in the same frame, from the
 * same state change, so they move together.
 */
internal object TL3Motion {
    const val MS = 280
    val EASING = FastOutSlowInEasing
    fun <T> spec() = tween<T>(MS, easing = EASING)
}

/** TL3 B1 look. */
internal object TwirlStyle {
    /** The faded area under expanded rows: `surfaceVariant` at this alpha. ONE constant, to tune after the phone test. */
    const val AREA_ALPHA = .22f
    /** The area's soft bottom edge (dp). */
    const val AREA_FADE_DP = 10f
    /** The D-button's width when its layer is expanded (the name column), animated from [DButtonLayout.WIDTH_DP]. */
    const val D_EXPANDED_DP = 104f
    const val NAME_SP = 10f
    const val NAME_ICON_DP = 14f
    /** The colour bar's height inside a colour row (B4). */
    const val COLOUR_BAR_DP = 16f
}

/**
 * TL3 B1: the faded area behind expanded rows: a full-viewport-width band per layer's run of twirl rows, drawn behind the
 * timeline content. It reads the list's layout only in the DRAW phase (no recomposition per scroll frame).
 */
@Composable internal fun TwirlArea(list: LazyListState, topPx: Float, modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = TwirlStyle.AREA_ALPHA)
    val fade = with(LocalDensity.current) { TwirlStyle.AREA_FADE_DP.dp.toPx() }
    val brush = remember(tint, fade) { Brush.verticalGradient(listOf(tint, tint.copy(alpha = 0f))) }
    Canvas(modifier) {
        val layout = list.layoutInfo
        val before = layout.beforeContentPadding.toFloat()
        var runTop = Float.NaN; var runBottom = 0f
        fun flush() {
            if (runTop.isNaN()) return
            drawRect(tint, Offset(0f, runTop), Size(size.width, runBottom - runTop))
            translate(0f, runBottom) { drawRect(brush, Offset.Zero, Size(size.width, fade)) }
            runTop = Float.NaN
        }
        for (item in layout.visibleItemsInfo) {
            val top = topPx + item.offset + before
            if (item.key is String) { if (runTop.isNaN()) runTop = top; runBottom = top + item.size } else flush()
        }
        flush()
    }
}

/**
 * TL3 B1: one cell of the D-button's extension (the name column) beside a twirl row: the property's inspector icon and
 * its name, ellipsised; the bracket's fill follows the D's state (neutral, or `primary` when the layer is selected) and
 * fades at its right edge like the D. Tap: select every key of the property (again with all selected: deselect them);
 * long-press: open its inspector page. The last cell rounds toward the timeline.
 */
@Composable internal fun TwirlNameCell(row: TwirlRow, selected: Boolean, last: Boolean, onTap: () -> Unit, onLongPress: () -> Unit,
    modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val fill = if (selected) scheme.primary else scheme.surfaceVariant
    val ink = if (selected) scheme.onPrimary else scheme.onSurfaceVariant
    val stops = DButtonLayout.fadeStops(TwirlStyle.D_EXPANDED_DP)
    val brush = remember(fill) { Brush.horizontalGradient(*stops.map { (at, a) -> at to fill.copy(alpha = fill.alpha * a) }.toTypedArray()) }
    val haptic = LocalHapticFeedback.current
    val tap by rememberUpdatedState(onTap); val hold by rememberUpdatedState(onLongPress)
    val radius = with(LocalDensity.current) { DButtonLayout.RADIUS_DP.dp.toPx() }
    val (name, icon) = when (row) {
        is TwirlRow.Property -> row.name to row.icon
        is TwirlRow.Audio -> "Audio" to "AUDIO"
        is TwirlRow.Empty -> TwirlRows.EMPTY_TEXT to null
    }
    Box(modifier.width(TwirlStyle.D_EXPANDED_DP.dp).height(row.heightDp.dp)
        .drawWithCache {
            // Flat on the left edge; the last cell rounds its bottom-right corner toward the timeline (path cached per size).
            val p = Path().apply {
                moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height - radius)
                quadraticTo(size.width, size.height, size.width - radius, size.height); lineTo(0f, size.height); close()
            }
            onDrawBehind { if (!last) drawRect(brush) else drawPath(p, brush) }
        }
        .testTag("twirl-name-${row.key}")
        .then(if (row is TwirlRow.Property) Modifier.pointerInput(row.key) {
            detectTapGestures(onTap = { tap() }, onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); hold() })
        }.semantics {
            contentDescription = name
            onClick("Select every keyframe of $name") { tap(); true }
            onLongClick("Open $name in the inspector") { hold(); true }
        } else Modifier.semantics { contentDescription = name })) {
        Row(Modifier.fillMaxSize().padding(start = 6.dp, end = 10.dp), verticalAlignment = if (row is TwirlRow.Audio) Alignment.Top else Alignment.CenterVertically) {
            if (icon != null) Icon(InspectorIcons.icon(icon), null, Modifier.padding(top = if (row is TwirlRow.Audio) 7.dp else 0.dp).size(TwirlStyle.NAME_ICON_DP.dp), tint = ink)
            Text(name, Modifier.padding(start = 4.dp, top = if (row is TwirlRow.Audio) 6.dp else 0.dp).weight(1f),
                color = if (row is TwirlRow.Empty) ink.copy(alpha = .55f) else ink, fontSize = TwirlStyle.NAME_SP.sp, lineHeight = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** TL3: the visible horizontal range of the content (px), read in the draw phase only. */
internal fun interface VisibleRange { fun px(): ClosedFloatingPointRange<Float> }

/**
 * TL3 B1 / B3: one keyed property's track row. Key diamonds as on the layer bars (selected keys with the accent outline),
 * drawn in ONE Canvas for the visible horizontal range only (sorted keys, binary-searched); paths are reused, so a scroll
 * frame allocates nothing and recomposes nothing. Gestures and popovers are attached by the caller ([gestures]).
 */
@Composable internal fun TwirlTrackRow(layer: CompositionLayer, row: TwirlRow.Property, pxPerUs: Double, playheadUs: Long,
    selectedTimes: Set<Long>, visible: VisibleRange, modifier: Modifier = Modifier, underlay: (DrawScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit = {}) {
    val times = remember(layer, row.target) { TwirlRows.keyTimes(layer, row.target) }
    val anchor = layer.resolvedKeyframeAnchorUs
    val start = layer.timing.startUs; val end = layer.timing.endUs ?: start
    val diamond = remember { Path() }
    val density = LocalDensity.current
    val knockout = remember(density) { Stroke(with(density) { LayerKeyMarkers.KNOCKOUT_STROKE_DP.dp.toPx() }) }
    val ring = remember(density) { Stroke(with(density) { 1.5.dp.toPx() }) }
    val outline = remember(density) { Stroke(with(density) { 2.5.dp.toPx() }) }
    val back = MaterialTheme.colorScheme.background
    Box(modifier.fillMaxWidth().height(row.heightDp.dp).testTag("twirl-row-${row.key}")
        .drawBehind {
            underlay?.invoke(this)
            val range = visible.px()
            val sizePx = LayerKeyMarkers.FOCUSED_DP.dp.toPx()
            LayerKeyMarkers.diamond(diamond, Size(sizePx, sizePx))
            val pad = sizePx
            var i = firstVisible(times, anchor, pxPerUs, range.start - pad)
            while (i < times.size) {
                val comp = anchor + times[i]
                val x = (comp * pxPerUs).toFloat()
                if (x > range.endInclusive + pad) break
                if (comp in start..end) translate(x - sizePx / 2f, (size.height - sizePx) / 2f) {
                    drawPath(diamond, back, style = knockout)
                    if (times[i] in selectedTimes) drawPath(diamond, Highlight, style = outline)
                    if (comp == playheadUs) { drawPath(diamond, Highlight); drawPath(diamond, Color(0xFF1C1C1C), style = ring) }
                    else { drawPath(diamond, Color(0xFF1C1C1C)); drawPath(diamond, Highlight.copy(alpha = .9f), style = ring) }
                }
                i++
            }
        }, content = content)
}

/** The first index whose x is at or after [px] (keys sorted by time). */
internal fun firstVisible(times: List<Long>, anchor: Long, pxPerUs: Double, px: Float): Int {
    var lo = 0; var hi = times.size
    while (lo < hi) { val mid = (lo + hi) ushr 1; if (((anchor + times[mid]) * pxPerUs).toFloat() < px) lo = mid + 1 else hi = mid }
    return lo
}

/** The key of [times] nearest to [x] (px, content space) within [halfHitPx]; midpoints split close keys. */
internal fun nearestKey(times: List<Long>, layer: CompositionLayer, pxPerUs: Double, x: Float, halfHitPx: Float): Long? {
    val anchor = layer.resolvedKeyframeAnchorUs
    val start = layer.timing.startUs; val end = layer.timing.endUs ?: start
    var best: Long? = null; var bestD = Float.MAX_VALUE
    for (t in times) {
        val comp = anchor + t
        if (comp !in start..end) continue
        val d = kotlin.math.abs((comp * pxPerUs).toFloat() - x)
        if (d <= halfHitPx && d < bestD) { best = t; bestD = d }
    }
    return best
}

/** TL3: the dim row of a layer with nothing animated (its text is in the name column). */
@Composable internal fun TwirlEmptyRow(row: TwirlRow.Empty, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(row.heightDp.dp).testTag("twirl-row-${row.key}"))
}

/** The current project's layer, for rows. */
internal fun ProjectDocument.layer(id: Long) = composition.layers.firstOrNull { it.id == id }

/**
 * TL3 B3: the ONE key gesture (no double tap, no timeout wait anywhere). A tap (up before the long-press timeout, inside the
 * touch slop) is [onTap] at once. A hold: [onLongPress] (haptic), then either released without moving ([onHoldRelease]) or
 * moved: [onDragStart], [onDrag] with the total x since the down, and on release [onDragEnd], which returns false when the
 * drag moved nothing (zero frames): that release is a hold release too. Moving past the slop before the hold leaves the
 * gesture to the timeline (scroll). [onDown] decides whether the down is on a key at all.
 */
internal suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectKeyGestures(
    onDown: (Offset) -> Boolean, onTap: (Offset) -> Unit, onLongPress: () -> Unit, onHoldRelease: (Offset) -> Unit,
    onDragStart: (Offset) -> Unit, onDrag: (Float) -> Unit, onDragEnd: () -> Boolean, onDragCancel: () -> Unit) =
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!onDown(down.position)) return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        var up: androidx.compose.ui.input.pointer.PointerInputChange? = null
        val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var outcome = 0
            while (outcome == 0) {
                val e = awaitPointerEvent()
                val c = e.changes.firstOrNull { it.id == down.id }
                outcome = when {
                    c == null || e.changes.count { it.pressed } > 1 -> 2
                    !c.pressed -> { up = c; 1 }
                    c.isConsumed || (c.position - down.position).getDistance() > slop -> 2
                    else -> 0
                }
            }
            outcome
        }
        if (early == 1) { up?.consume(); onTap(down.position); return@awaitEachGesture }
        if (early == 2) return@awaitEachGesture
        onLongPress()
        var dragging = false; var finished = false
        try {
            while (!finished) {
                val e = awaitPointerEvent()
                val c = e.changes.firstOrNull { it.id == down.id }
                if (c == null || (!dragging && e.changes.count { it.pressed } > 1)) { if (dragging) onDragCancel(); finished = true; continue }
                if (!c.pressed) {
                    c.consume(); finished = true
                    if (!dragging || !onDragEnd()) onHoldRelease(down.position)
                    continue
                }
                if (!dragging && c.position != c.previousPosition) { dragging = true; onDragStart(down.position) }
                if (dragging) { c.consume(); onDrag(c.position.x - down.position.x) }
            }
        } finally { if (dragging && !finished) onDragCancel() }
    }

/**
 * TL3 B5: the audio row (56dp, first in a layer's twirl-down): the waveform aligned to the clip, through the mixer's own
 * time mapping ([com.fluxx.android.model.WaveformMapping]: start, in-point, 1x), drawn for the visible range only, at a
 * FIXED amplitude scale (no auto-gain), dimmed when the layer is muted or at volume 0. Peaks are requested only while the
 * row is shown and the layer is visible (eye on); a row leaving mid-decode cancels it. While they compute, a static faint
 * placeholder (no animation).
 */
@Composable internal fun TwirlAudioRow(layer: CompositionLayer, row: TwirlRow.Audio, pxPerUs: Double, visible: VisibleRange,
    provider: com.fluxx.android.media.AudioPeakProvider, modifier: Modifier = Modifier) {
    val uri = layer.asset?.uri
    if (uri != null && layer.visible) DisposableEffect(uri) { provider.request(uri); onDispose { provider.cancel(uri) } }
    val peaks = uri?.let { provider.states[it] } as? com.fluxx.android.media.AudioPeakProvider.Peaks.Ready
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val dim = com.fluxx.android.model.WaveformMapping.dimmed(layer)
    Box(modifier.fillMaxWidth().height(row.heightDp.dp).testTag("twirl-row-${row.key}")
        .semantics { contentDescription = if (peaks != null) "Audio waveform" else "Audio waveform loading" }
        .drawBehind {
            val range = visible.px()
            val clipL = (layer.timing.startUs * pxPerUs).toFloat(); val clipR = ((layer.timing.endUs ?: layer.timing.startUs) * pxPerUs).toFloat()
            val x0 = maxOf(range.start, clipL); val x1 = minOf(range.endInclusive, clipR)
            if (x1 <= x0) return@drawBehind
            val mid = size.height / 2f; val half = size.height / 2f - 2.dp.toPx()
            val pyramid = peaks?.pyramid
            if (pyramid == null) {
                // Static placeholder: a faint band and the centre line.
                drawRect(ink.copy(alpha = .06f), Offset(x0, mid - half / 3f), Size(x1 - x0, half * 2f / 3f))
                drawLine(ink.copy(alpha = .2f), Offset(x0, mid), Offset(x1, mid), 1f)
                return@drawBehind
            }
            val colour = ink.copy(alpha = if (dim) .3f else .8f)
            val usPerPx = 1.0 / pxPerUs
            val level = pyramid.levelFor(usPerPx)
            val (mins, maxs) = pyramid.levels[level]
            var x = kotlin.math.floor(x0)
            while (x < x1) {
                val src = com.fluxx.android.model.WaveformMapping.sourceUs(layer, (x / pxPerUs).toLong())
                if (src != null) {
                    val b0 = pyramid.bucketAt(level, src); val b1 = pyramid.bucketAt(level, src + usPerPx.toLong()).let { if (it < 0) b0 else it }
                    if (b0 >= 0) {
                        var lo = mins[b0]; var hi = maxs[b0]
                        for (b in b0 + 1..b1) { if (mins[b] < lo) lo = mins[b]; if (maxs[b] > hi) hi = maxs[b] }
                        drawLine(colour, Offset(x, mid - hi.coerceIn(-1f, 1f) * half), Offset(x, mid - lo.coerceIn(-1f, 1f) * half + 1f), 1f)
                    }
                }
                x += 1f
            }
        })
}
