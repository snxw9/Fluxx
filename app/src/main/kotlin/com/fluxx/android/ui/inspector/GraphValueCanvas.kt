package com.fluxx.android.ui.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import com.fluxx.android.ui.theme.*
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Everything the Value canvas needs about the edited property, computed once per document / viewport change. */
internal class ValueScene(val layer: CompositionLayer, val target: GraphTarget, val rate: FrameRate, val axes: List<ValueAxis>, val channel: Int,
    val segment: GraphSegment, val selection: Int?, val startTimecodeUs: Long = 0L) {
    val keys = GraphValues.keys(layer, target)
    val keyTimes = keys.map { it.timeUs }
    val channels = axes.size
    private val sampler = GraphValues.sampler(layer, target)
    private val buffer = FloatArray(2)
    /** Display value of [channel] at local [time]. */
    fun value(time: Long, ch: Int = channel): Double { sampler?.invoke(time, buffer); return axes[ch].toDisplay(buffer[ch]).toDouble() }
    fun keyValue(k: Int, ch: Int = channel) = axes[ch].toDisplay(keys[k].value(ch)).toDouble()
    /** The fitted window: keys' time range and the sampled curve extent of every channel (overshoot included). */
    val fit: ValueViewport by lazy {
        val t0 = keyTimes.first(); val t1 = keyTimes.last()
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        val n = 240
        for (ch in 0 until channels) for (i in 0..n) {
            val v = value(t0 + (t1 - t0) * i / n, ch); lo = minOf(lo, v); hi = maxOf(hi, v)
        }
        for (k in keys.indices) for (ch in 0 until channels) { val v = keyValue(k, ch); lo = minOf(lo, v); hi = maxOf(hi, v) }
        ValueView.fit(t0, t1, lo, hi)
    }
    val frameUs get() = 1e6 * rate.denominator / rate.numerator
    val limits by lazy { ValueView.limits(fit, frameUs) }
    val bezier get() = segment.curve.bezierOrNull()
    val segStart get() = keyTimes.indexOf(segment.startUs)
}

private sealed interface ValueHit { data class Handle(val i: Int) : ValueHit; data class Key(val i: Int) : ValueHit; data object Empty : ValueHit }

/**
 * GRAPH G2: the Value graph canvas. X = local time, Y = the property's value in its inspector page's units, drawn with
 * the property's own evaluator. Keys are tap-to-select and double-tap-to-seek only (GRAPH G2.1 §3: keys are not
 * draggable; their time and value are typed in the values bar). The selected segment's easing handles are editable on
 * the curve (direct drag only); any other one-finger drag, including one that starts on a key, pans; a pinch zooms
 * (axis-aware); a double tap on empty canvas fits. Nothing here moves the playhead except the key double tap
 * ([onSeekKey]). Every gesture reads current state through holders (the G1.1 rule). Everything is clipped to the
 * canvas bounds (G2.1 §4).
 */
@Composable internal fun ValueCanvas(scene: ValueScene, session: GraphSession, local: Long, showDot: Boolean, snap: Boolean, mirror: Boolean,
    onSelectKey: (Long?) -> Unit, onSeekKey: (Long) -> Unit,
    onHandleBegin: () -> Unit, onHandleDrag: (EasingCurve) -> Unit, onHandleEnd: () -> Unit, onHandleCancel: () -> Unit, modifier: Modifier) {
    val density = LocalDensity.current
    val inset = with(density) { 12.dp.toPx() }
    val hit = with(density) { 24.dp.toPx() }
    val vp = session.viewport ?: scene.fit
    val current by rememberUpdatedState(scene); val currentVp by rememberUpdatedState(vp)
    val snapOn by rememberUpdatedState(snap); val mirrorOn by rememberUpdatedState(mirror)
    val select by rememberUpdatedState(onSelectKey); val seek by rememberUpdatedState(onSeekKey)
    val hBegin by rememberUpdatedState(onHandleBegin); val hDrag by rememberUpdatedState(onHandleDrag)
    val hEnd by rememberUpdatedState(onHandleEnd); val hCancel by rememberUpdatedState(onHandleCancel)
    // Double-tap detection across gestures (plain holder: never read by composition).
    val lastTap = remember { arrayOfNulls<Any>(3) } // [uptime, hit, position]
    BoxWithConstraints(modifier.clipToBounds().semantics {
        contentDescription = "Value graph"; stateDescription = vp.describe()
    }.pointerInput(Unit) {
        val doubleTap = viewConfiguration.doubleTapTimeoutMillis
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val s = current; val v0 = currentVp
            val plot = ValuePlot(size.width.toFloat(), size.height.toFloat(), inset, v0)
            val target = hitTest(s, plot, down.position, hit)
            var moved: PointerInputChange? = null
            var pinch = false
            while (true) {
                val ev = awaitPointerEvent()
                if (ev.changes.count { it.pressed } >= 2) { pinch = true; break }
                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) break
                if ((c.position - down.position).getDistance() > slop) { moved = c; break }
            }
            if (pinch) {
                // Two-finger pinch: axis-aware zoom about the (moving) centroid; the data point under it stays fixed.
                var ev = currentEvent
                val ids = ev.changes.filter { it.pressed }.take(2).map { it.id }
                fun pts() = ids.mapNotNull { id -> currentEvent.changes.firstOrNull { it.id == id }?.position }
                val start = pts(); if (start.size < 2) return@awaitEachGesture
                val d0 = (start[0] - start[1]).getDistance().coerceAtLeast(1f)
                val axes = ValueView.axes(start[0].x - start[1].x, start[0].y - start[1].y)
                val c0 = (start[0] + start[1]) / 2f
                val base = currentVp
                val fx = ((c0.x - inset) / plot.plotWidth).toDouble(); val fy = ((c0.y - inset) / plot.plotHeight).toDouble()
                while (true) {
                    ev = awaitPointerEvent()
                    ev.changes.forEach { it.consume() }
                    val p = pts()
                    if (p.size < 2 || ev.changes.count { it.pressed } < 2) break
                    val scale = ((p[0] - p[1]).getDistance() / d0).toDouble()
                    val zoomed = ValueView.zoom(base, fx, fy, scale, axes, s.limits)
                    val c = (p[0] + p[1]) / 2f
                    val zp = ValuePlot(size.width.toFloat(), size.height.toFloat(), inset, zoomed)
                    session.viewport = ValueView.pan(zoomed, (zp.time(c0.x) - zp.time(c.x)), (zp.value(c0.y) - zp.value(c.y)), s.limits)
                }
                return@awaitEachGesture
            }
            if (moved == null) {
                // A tap. Double tap: on a key seeks to it, on empty canvas fits. Single tap: key selects, empty clears.
                val now = down.uptimeMillis
                val prevAt = lastTap[0] as Long?; val prevHit = lastTap[1]; val prevPos = lastTap[2] as Offset?
                val double = prevAt != null && now - prevAt <= doubleTap && prevHit == target && prevPos != null && (prevPos - down.position).getDistance() < 2 * hit
                when {
                    double && target is ValueHit.Key -> { seek(s.keyTimes[target.i]); lastTap[0] = null }
                    double && target == ValueHit.Empty -> { session.viewport = null; lastTap[0] = null }
                    else -> {
                        if (target is ValueHit.Key) select(s.keyTimes[target.i]) else if (target == ValueHit.Empty) select(null)
                        lastTap[0] = now; lastTap[1] = target; lastTap[2] = down.position
                    }
                }
                return@awaitEachGesture
            }
            lastTap[0] = null
            val first = moved
            when (target) {
                ValueHit.Empty, is ValueHit.Key -> {
                    // One-finger pan (G2.1 §3: a drag that starts on a key pans too).
                    var last = first.position
                    drag(first.id) { change ->
                        change.consume()
                        val vpNow = currentVp
                        val p = ValuePlot(size.width.toFloat(), size.height.toFloat(), inset, vpNow)
                        val d = change.position - last; last = change.position
                        session.viewport = ValueView.pan(vpNow, -d.x / p.plotWidth * vpNow.timeSpan, d.y / p.plotHeight * vpNow.valueSpan, s.limits)
                    }
                }
                is ValueHit.Handle -> {
                    val b = s.bezier ?: return@awaitEachGesture
                    val k = s.segStart; if (k < 0) return@awaitEachGesture
                    val t0 = s.keyTimes[k].toDouble(); val t1 = s.keyTimes[k + 1].toDouble()
                    val a0 = s.keyValue(k); val a1 = s.keyValue(k + 1)
                    val (ht, hv) = ValueHandles.point(b, target.i, t0, a0, t1, a1)
                    val grab = Offset(plot.x(ht), plot.y(hv)) - down.position
                    session.viewport = v0 // frozen: never refits mid-drag
                    hBegin()
                    var completed = false
                    try {
                        completed = drag(first.id) { change ->
                            change.consume()
                            val vpNow = currentVp
                            val p = ValuePlot(size.width.toFloat(), size.height.toFloat(), inset, vpNow)
                            val at = change.position + grab
                            val (x, y) = ValueHandles.normalised(b, target.i, p.time(at.x), p.value(at.y), t0, a0, t1, a1)
                            val c = EaseMath.moveHandle(b, target.i, x, y, snapOn, mirrorOn)
                            c.bezierOrNull()?.let { nb -> val (nt, nv) = ValueHandles.point(nb, target.i, t0, a0, t1, a1); session.viewport = vpNow.include(nt, nv) }
                            hDrag(c)
                        }
                    } finally { if (completed) hEnd() else hCancel() }
                }
            }
        }
    }) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        val labels = maxWidth >= 120.dp && maxHeight >= 120.dp
        ValueCurveLayer(scene, vp, inset, labels)
        val plot = ValuePlot(w, h, inset, vp)
        // Semantics-only markers at the key and handle positions (tests and accessibility); no pointer input of their own.
        scene.keys.forEachIndexed { i, k ->
            val x = plot.x(k.timeUs.toDouble()); val y = plot.y(scene.keyValue(i))
            if (x in 0f..w && y in 0f..h) Box(Modifier.offset { IntOffset(x.roundToInt() - 1, y.roundToInt() - 1) }.size(2.dp)
                .semantics { contentDescription = "Key ${i + 1}"; selected = scene.selection == i }.testTag("graph-value-key-$i"))
        }
        scene.bezier?.takeIf { scene.segStart >= 0 }?.let { b ->
            val k = scene.segStart
            for (hi in 0..1) {
                val (t, v) = ValueHandles.point(b, hi, scene.keyTimes[k].toDouble(), scene.keyValue(k), scene.keyTimes[k + 1].toDouble(), scene.keyValue(k + 1))
                val x = plot.x(t); val y = plot.y(v)
                if (x in 0f..w && y in 0f..h) Box(Modifier.offset { IntOffset(x.roundToInt() - 1, y.roundToInt() - 1) }.size(2.dp)
                    .semantics { contentDescription = "Handle ${hi + 1}" }.testTag("graph-value-handle-$hi"))
            }
        }
        ValuePlayheadLayer(scene, vp, inset, local, showDot)
    }
}

private fun hitTest(s: ValueScene, plot: ValuePlot, at: Offset, radius: Float): ValueHit {
    // The nearest target within the radius wins; a key wins a tie (a handle close to its key never steals the key's tap).
    var best: ValueHit = ValueHit.Empty; var dist = radius
    for (i in s.keys.indices) {
        val d = (Offset(plot.x(s.keyTimes[i].toDouble()), plot.y(s.keyValue(i))) - at).getDistance()
        if (d <= dist) { dist = d; best = ValueHit.Key(i) }
    }
    s.bezier?.takeIf { s.segStart >= 0 }?.let { b ->
        val k = s.segStart
        for (h in 0..1) {
            val (t, v) = ValueHandles.point(b, h, s.keyTimes[k].toDouble(), s.keyValue(k), s.keyTimes[k + 1].toDouble(), s.keyValue(k + 1))
            val d = (Offset(plot.x(t), plot.y(v)) - at).getDistance()
            if (d < dist) { dist = d; best = ValueHit.Handle(h) }
        }
    }
    return best
}

/** Grid, labels, curve(s), keys and handles; rebuilt only when the scene, viewport or size changes. */
@Composable private fun ValueCurveLayer(scene: ValueScene, vp: ValueViewport, inset: Float, labels: Boolean) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(color = TextSecondary, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
    Spacer(Modifier.fillMaxSize().drawWithCache {
        val plot = ValuePlot(size.width, size.height, inset, vp)
        val n = (plot.plotWidth / 2f).toInt().coerceIn(32, 1200)
        val paths = (0 until scene.channels).map { ch ->
            Path().apply {
                for (i in 0..n) {
                    val t = vp.t0 + vp.timeSpan * i / n
                    val x = plot.x(t); val y = plot.y(scene.value(t.roundToLong(), ch))
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
        }
        val (tStep, frames) = ValueView.timeStep(vp.timeSpan, scene.rate)
        val vStep = ValueView.step(vp.valueSpan)
        val tTicks = ticks(vp.t0, vp.t1, tStep); val vTicks = ticks(vp.v0, vp.v1, vStep)
        val tLabels = if (labels) tTicks.map { t -> measurer.measure(timeLabel(t, frames, scene), style) } else emptyList()
        val axis = scene.axes[scene.channel]
        val vLabels = if (labels) vTicks.map { v -> measurer.measure(valueLabel(v, vStep, axis), style) } else emptyList()
        val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val dim = Stroke(1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        onDrawBehind {
            val thin = .5.dp.toPx()
            for ((i, t) in tTicks.withIndex()) {
                val x = plot.x(t); drawLine(Border.copy(alpha = .5f), Offset(x, 0f), Offset(x, size.height), thin)
                tLabels.getOrNull(i)?.let { drawText(it, topLeft = Offset(x + 2.dp.toPx(), size.height - it.size.height - 1.dp.toPx())) }
            }
            for ((i, v) in vTicks.withIndex()) {
                val y = plot.y(v); drawLine(if (abs(v) < vStep * 1e-6) TextSecondary.copy(alpha = .6f) else Border.copy(alpha = .5f), Offset(0f, y), Offset(size.width, y), thin)
                vLabels.getOrNull(i)?.let { drawText(it, topLeft = Offset(2.dp.toPx(), y - it.size.height - 1.dp.toPx())) }
            }
            // The other channel dimmed, the edited one solid.
            for (ch in 0 until scene.channels) if (ch != scene.channel) drawPath(paths[ch], TextSecondary.copy(alpha = .45f), style = dim)
            drawPath(paths[scene.channel], TextPrimary, style = stroke)
            scene.bezier?.takeIf { scene.segStart >= 0 }?.let { b -> handles(scene, plot, b) }
            for (i in scene.keys.indices) {
                val p = Offset(plot.x(scene.keyTimes[i].toDouble()), plot.y(scene.keyValue(i)))
                val inSegment = i == scene.segStart || i == scene.segStart + 1
                drawCircle(Surface, 5.dp.toPx(), p)
                drawCircle(if (inSegment) Highlight else TextPrimary, 3.5.dp.toPx(), p)
                if (scene.selection == i) drawCircle(Highlight, 7.dp.toPx(), p, style = Stroke(1.2.dp.toPx()))
            }
        }
    })
}

private fun DrawScope.handles(scene: ValueScene, plot: ValuePlot, b: FloatArray) {
    val k = scene.segStart
    val t0 = scene.keyTimes[k].toDouble(); val t1 = scene.keyTimes[k + 1].toDouble()
    val a0 = scene.keyValue(k); val a1 = scene.keyValue(k + 1)
    val line = 1.dp.toPx()
    for (h in 0..1) {
        val (t, v) = ValueHandles.point(b, h, t0, a0, t1, a1)
        val p = Offset(plot.x(t), plot.y(v))
        val from = if (h == 0) Offset(plot.x(t0), plot.y(a0)) else Offset(plot.x(t1), plot.y(a1))
        drawLine(TextSecondary, from, p, line, StrokeCap.Round)
        drawCircle(Highlight, 3.dp.toPx(), p)
    }
}

private fun ticks(lo: Double, hi: Double, step: Double): List<Double> {
    if (step <= 0 || !step.isFinite()) return emptyList()
    val out = ArrayList<Double>(6)
    var v = kotlin.math.ceil(lo / step) * step
    while (v <= hi && out.size < 6) { out += v; v += step }
    return out.take(4)
}

/** A time tick: the transport's timecode formatter (GRAPH G2.1 §6a), on composition time. */
private fun timeLabel(localUs: Double, @Suppress("UNUSED_PARAMETER") frames: Boolean, scene: ValueScene): String =
    GraphTime.label(Math.addExact(localUs.roundToLong(), scene.layer.resolvedKeyframeAnchorUs), scene.rate, scene.startTimecodeUs)

/** A value tick in the page's formatting ([ValueAxis.text]); steps finer than the formatter can show use step decimals. */
internal fun valueLabel(v: Double, step: Double, axis: ValueAxis): String {
    val clean = if (abs(v) < step * 1e-6) 0.0 else v
    if (step >= axis.resolution - 1e-9) return axis.text(clean.toFloat())
    val decimals = when { step >= 1 -> 0; step >= .1 -> 1; step >= .01 -> 2; else -> 3 }
    return String.format(java.util.Locale.ROOT, "%.${decimals}f", clean)
}

/** The one playhead: a vertical line at its local time and (if enabled) the dot on the edited channel's curve. */
@Composable private fun ValuePlayheadLayer(scene: ValueScene, vp: ValueViewport, inset: Float, local: Long, showDot: Boolean) {
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize().testTag("graph-playhead-dot")) {
        val plot = ValuePlot(size.width, size.height, inset, vp)
        val x = plot.x(local.toDouble())
        if (x < 0f || x > size.width) return@Canvas
        drawLine(TextSecondary.copy(alpha = .6f), Offset(x, 0f), Offset(x, size.height), .75.dp.toPx())
        if (showDot) { val p = Offset(x, plot.y(scene.value(local))); drawCircle(Surface, 5.dp.toPx(), p); drawCircle(TextPrimary, 4.dp.toPx(), p) }
    }
}

/**
 * GRAPH G2 / G2.1 values bar in the Value view, on an opaque fill (nothing draws through it).
 * - Time: the selected key's time in the transport's timecode format, typed (frame-snapped, clamped to the neighbours;
 *   one undo). With no key selected it is inert and shows "–" (G2.1 §6: no live time until the time audit is resolved).
 * - Value: the selected key's value, else the LIVE value at the playhead (as the page shows it). Always enabled; typing
 *   edits the key, or with no key sets the value at the playhead ([GraphValues.atPlayheadAction]).
 * - 2-D properties add an X | Y toggle that picks the channel the Value tile edits.
 */
@Composable internal fun ValueValuesBar(scene: ValueScene, selected: Int?, height: Float, live: FloatArray?, onChannel: (Int) -> Unit,
    onCommit: (EditorAction, Long?) -> Unit, onTypedAtPlayhead: (Float, Float) -> EditorAction?) {
    val key = selected?.let { scene.keys.getOrNull(it) }
    val axis = scene.axes[scene.channel]
    val anchor = scene.layer.resolvedKeyframeAnchorUs
    val time = key?.let { GraphTime.label(Math.addExact(anchor, it.timeUs), scene.rate, scene.startTimecodeUs) } ?: "–"
    val stored = key?.value(scene.channel) ?: live?.getOrNull(scene.channel)
    val value = stored?.let { axis.storedText(it) } ?: "–"
    Row(Modifier.fillMaxWidth().height(height.dp).background(SurfaceHigh).testTag("graph-values-bar")) {
        GraphTypedTile("Time", "", time, key != null, Modifier.weight(1f).fillMaxHeight().testTag("graph-value-time")) { text ->
            val k = selected ?: return@GraphTypedTile
            val kk = key ?: return@GraphTypedTile
            val absolute = GraphTime.parse(text, scene.rate, scene.startTimecodeUs) ?: return@GraphTypedTile
            val t = GraphValues.clampTime(scene.layer, scene.rate, scene.keyTimes, k, Math.subtractExact(absolute, anchor))
            GraphValues.keyAction(scene.layer, scene.target, kk, t, kk.x, kk.y)?.let { onCommit(it, t) }
        }
        Box(Modifier.width(GraphLayout.HAIRLINE.dp).fillMaxHeight().background(Border))
        GraphTypedTile(axis.name, axis.unit, value, stored != null, Modifier.weight(1.2f).fillMaxHeight().testTag("graph-value-value")) { text ->
            val d = (if (axis.units.format == DisplayFormat.TURNS) parseRotation(text) else ValueParsing.number(text, axis.unit)?.toFloat())
                ?.takeIf { it.isFinite() } ?: return@GraphTypedTile
            val v = GraphValues.clampValue(scene.target, axis, axis.typed(d))
            if (key != null) {
                val x = if (scene.channel == 0) v else key.x; val y = if (scene.channel == 1) v else key.y
                GraphValues.keyAction(scene.layer, scene.target, key, key.timeUs, x, y)?.let { onCommit(it, key.timeUs) }
            } else {
                val cur = live ?: return@GraphTypedTile
                val x = if (scene.channel == 0) v else cur[0]; val y = if (scene.channel == 1) v else cur.getOrElse(1) { 0f }
                onTypedAtPlayhead(x, y)?.let { onCommit(it, null) }
            }
        }
        if (scene.channels == 2) {
            Box(Modifier.width(GraphLayout.HAIRLINE.dp).fillMaxHeight().background(Border))
            Row(Modifier.width(56.dp).fillMaxHeight().testTag("graph-value-channel"), verticalAlignment = Alignment.CenterVertically) {
                for (ch in 0..1) {
                    val on = scene.channel == ch
                    Box(Modifier.weight(1f).fillMaxHeight().background(if (on) Surface else SurfaceHigh).clickable(role = Role.Tab) { onChannel(ch) }
                        .semantics { contentDescription = if (ch == 0) "Edit X" else "Edit Y"; if (on) stateDescription = "Selected" }
                        .testTag("graph-value-channel-$ch"), contentAlignment = Alignment.Center) {
                        Text(if (ch == 0) "X" else "Y", color = if (on) TextPrimary else TextSecondary,
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace))
                    }
                }
            }
        }
    }
}

/** A values-bar tile with a corner label and a typed-entry popover (the Ease bar's tile, for free-form values). */
@Composable internal fun GraphTypedTile(label: String, unit: String, text: String, enabled: Boolean, modifier: Modifier, onCommit: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    Box(modifier.background(if (editing) Surface else SurfaceHigh)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Type $label") { editing = true }
        .semantics(mergeDescendants = true) { contentDescription = "$label $text"; if (!enabled) disabled() }) {
        Text(label, Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 2.dp), color = TextSecondary, fontSize = 8.sp, lineHeight = 9.sp, maxLines = 1)
        Text(if (unit.isEmpty()) text else "$text$unit", Modifier.align(Alignment.Center).padding(top = 6.dp), color = if (enabled) TextPrimary else TextDisabled, maxLines = 1,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace))
        AnchoredPopover(editing, { editing = false }, "value-entry") {
            InspectorValueField(text, label, unit, { typed -> editing = false; onCommit(typed) }, { editing = false })
        }
    }
}
