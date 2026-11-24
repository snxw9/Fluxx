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

/** A handle drag's result: the selected segment's curve, plus other keys' curves (Smooth through key). One Batch. */
internal data class HandleEdit(val curve: EasingCurve, val others: List<Pair<Long, EasingCurve>> = emptyList())

/**
 * Everything the Value / Speed canvas needs about the edited property, computed once per document / viewport change.
 * GRAPH G3: one canvas for both views; [speed] switches the plotted quantity (value → speed in display units/s), the key
 * and handle positions and the handle drag mapping ([SpeedHandles], [SmoothThroughKey] when [smooth]).
 */
internal class ValueScene(val layer: CompositionLayer, val target: GraphTarget, val rate: FrameRate, val axes: List<ValueAxis>, val channel: Int,
    val segment: GraphSegment, val selection: Int?, val startTimecodeUs: Long = 0L, val speed: Boolean = false, val smooth: Boolean = false) {
    val keys = GraphValues.keys(layer, target)
    val keyTimes = keys.map { it.timeUs }
    val channels = axes.size
    private val sampler = GraphValues.sampler(layer, target)
    private val buffer = FloatArray(2)
    /** Display value of [channel] at local [time]. */
    fun value(time: Long, ch: Int = channel): Double { sampler?.invoke(time, buffer); return axes[ch].toDisplay(buffer[ch]).toDouble() }
    fun keyValue(k: Int, ch: Int = channel) = axes[ch].toDisplay(keys[k].value(ch)).toDouble()
    /** G3: the speed segments (display units/s; one combined curve for 2-D properties). */
    val speedSegs: List<SpeedSegment> by lazy { if (speed) GraphSpeed.segments(layer, target, axes) else emptyList() }
    /** The plotted quantity at local [time]: the value of [ch], or the speed. */
    fun curveY(time: Long, ch: Int = channel): Double = if (speed) GraphSpeed.speedAt(speedSegs, time) else value(time, ch)
    /** Curves to draw: every channel in the Value view, one combined curve in the Speed view. */
    val curves: Int get() = if (speed) 1 else channels
    /** The edited curve (the solid one). */
    val curve: Int get() = if (speed) 0 else channel
    /** Where key [k] is drawn: its value, or its speed (departing; the last key: arriving). */
    fun keyY(k: Int): Double = if (speed) GraphSpeed.keySpeed(speedSegs, k) else keyValue(k)
    /** The fitted window: keys' time range and the sampled curve extent (overshoot and negative speeds; 0 in the Speed view). */
    val fit: ValueViewport by lazy {
        val t0 = keyTimes.first(); val t1 = keyTimes.last()
        var lo = if (speed) 0.0 else Double.MAX_VALUE; var hi = if (speed) 0.0 else -Double.MAX_VALUE
        val n = 240
        for (ch in 0 until curves) for (i in 0..n) {
            val v = curveY(t0 + (t1 - t0) * i / n, ch); lo = minOf(lo, v); hi = maxOf(hi, v)
        }
        for (k in keys.indices) for (ch in 0 until curves) { val v = if (speed) keyY(k) else keyValue(k, ch); lo = minOf(lo, v); hi = maxOf(hi, v) }
        if (speed) for (sg in speedSegs) sg.bezier?.let { b -> for (h in 0..1) { val v = SpeedHandles.speed(sg, b, h); lo = minOf(lo, v); hi = maxOf(hi, v) } }
        ValueView.fit(t0, t1, lo, hi)
    }
    val frameUs get() = 1e6 * rate.denominator / rate.numerator
    val limits by lazy { ValueView.limits(fit, frameUs) }
    val bezier get() = segment.curve.bezierOrNull()
    val segStart get() = keyTimes.indexOf(segment.startUs)
    private val speedSeg: SpeedSegment? get() = segStart.takeIf { speed && it >= 0 }?.let { speedSegs.getOrNull(it) }
    /** Handle [h] of the selected segment (time µs, y), or null (no bezier). */
    fun handlePoint(h: Int): Pair<Double, Double>? {
        val b = bezier ?: return null; val k = segStart; if (k < 0) return null
        if (speed) { val sg = speedSeg ?: return null; return SpeedHandles.point(sg, b, h) }
        return ValueHandles.point(b, h, keyTimes[k].toDouble(), keyValue(k), keyTimes[k + 1].toDouble(), keyValue(k + 1))
    }
    /** Where handle [h]'s line starts: its key (on the curve). */
    fun handleAnchor(h: Int): Pair<Double, Double>? {
        val b = bezier ?: return null; val k = segStart; if (k < 0) return null
        if (speed) { val sg = speedSeg ?: return null; return SpeedHandles.anchor(sg, b, h) }
        return if (h == 0) keyTimes[k].toDouble() to keyValue(k) else keyTimes[k + 1].toDouble() to keyValue(k + 1)
    }
    /**
     * A drag of handle [h] (from the drag's starting bezier [b]) to (local [t], [y]): the Value view's normalised move
     * ([EaseMath.moveHandle], Snap 0.05 / Mirror), or the Speed view's speed / influence ([SpeedHandles.drag]; Snap: 5% and
     * the grid [grid]) plus Smooth through key.
     */
    fun dragHandle(b: FloatArray, h: Int, t: Double, y: Double, snap: Boolean, mirror: Boolean, grid: Double): HandleEdit? {
        val k = segStart; if (k < 0) return null
        if (speed) {
            val sg = speedSeg ?: return null
            val nb = SpeedHandles.drag(sg, b, h, t, y, snap, grid)
            val r = SmoothThroughKey.apply(speedSegs, k, h, nb, smooth)
            return HandleEdit(r.edits.first().second, r.edits.drop(1))
        }
        val (x, yy) = ValueHandles.normalised(b, h, t, y, keyTimes[k].toDouble(), keyValue(k), keyTimes[k + 1].toDouble(), keyValue(k + 1))
        return HandleEdit(EaseMath.moveHandle(b, h, x, yy, snap, mirror))
    }
    /** A value-axis tick label: the page's formatting (Speed: per second, Rotation as a plain number). */
    fun tickLabel(v: Double, step: Double): String {
        val axis = axes[curve.coerceAtMost(axes.lastIndex)]
        if (!speed) return valueLabel(v, step, axis)
        val clean = if (abs(v) < step * 1e-6) 0.0 else v
        if (step >= axis.resolution - 1e-9) return GraphSpeed.text(axis, clean)
        val decimals = when { step >= 1 -> 0; step >= .1 -> 1; step >= .01 -> 2; else -> 3 }
        return String.format(java.util.Locale.ROOT, "%.${decimals}f", clean)
    }
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
    onHandleBegin: () -> Unit, onHandleDrag: (HandleEdit) -> Unit, onHandleEnd: () -> Unit, onHandleCancel: () -> Unit, modifier: Modifier) {
    val density = LocalDensity.current
    val inset = with(density) { 12.dp.toPx() }
    val hit = with(density) { 24.dp.toPx() }
    // G3: the Speed view keeps its own viewport next to the Value one.
    val vp = session.viewportFor(scene.speed) ?: scene.fit
    val current by rememberUpdatedState(scene); val currentVp by rememberUpdatedState(vp)
    val snapOn by rememberUpdatedState(snap); val mirrorOn by rememberUpdatedState(mirror)
    val select by rememberUpdatedState(onSelectKey); val seek by rememberUpdatedState(onSeekKey)
    val hBegin by rememberUpdatedState(onHandleBegin); val hDrag by rememberUpdatedState(onHandleDrag)
    val hEnd by rememberUpdatedState(onHandleEnd); val hCancel by rememberUpdatedState(onHandleCancel)
    // Double-tap detection across gestures (plain holder: never read by composition).
    val lastTap = remember { arrayOfNulls<Any>(3) } // [uptime, hit, position]
    BoxWithConstraints(modifier.clipToBounds().semantics {
        contentDescription = if (scene.speed) "Speed graph" else "Value graph"; stateDescription = vp.describe()
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
                    session.setViewportFor(s.speed, ValueView.pan(zoomed, (zp.time(c0.x) - zp.time(c.x)), (zp.value(c0.y) - zp.value(c.y)), s.limits))
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
                    double && target == ValueHit.Empty -> { session.setViewportFor(s.speed, null); lastTap[0] = null }
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
                        session.setViewportFor(s.speed, ValueView.pan(vpNow, -d.x / p.plotWidth * vpNow.timeSpan, d.y / p.plotHeight * vpNow.valueSpan, s.limits))
                    }
                }
                is ValueHit.Handle -> {
                    val b = s.bezier ?: return@awaitEachGesture
                    val (ht, hv) = s.handlePoint(target.i) ?: return@awaitEachGesture
                    val grab = Offset(plot.x(ht), plot.y(hv)) - down.position
                    session.setViewportFor(s.speed, v0) // frozen: never refits mid-drag
                    hBegin()
                    var completed = false
                    try {
                        completed = drag(first.id) { change ->
                            change.consume()
                            val vpNow = currentVp
                            val p = ValuePlot(size.width.toFloat(), size.height.toFloat(), inset, vpNow)
                            val at = change.position + grab
                            val edit = s.dragHandle(b, target.i, p.time(at.x), p.value(at.y), snapOn, mirrorOn, ValueView.step(vpNow.valueSpan))
                                ?: return@drag
                            session.setViewportFor(s.speed, vpNow.include(p.time(at.x), p.value(at.y)))
                            hDrag(edit)
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
            val x = plot.x(k.timeUs.toDouble()); val y = plot.y(scene.keyY(i))
            if (x in 0f..w && y in 0f..h) Box(Modifier.offset { IntOffset(x.roundToInt() - 1, y.roundToInt() - 1) }.size(2.dp)
                .semantics { contentDescription = "Key ${i + 1}"; selected = scene.selection == i }.testTag("graph-value-key-$i"))
        }
        for (hi in 0..1) {
            scene.handlePoint(hi)?.let { (t, v) ->
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
        val d = (Offset(plot.x(s.keyTimes[i].toDouble()), plot.y(s.keyY(i))) - at).getDistance()
        if (d <= dist) { dist = d; best = ValueHit.Key(i) }
    }
    for (h in 0..1) s.handlePoint(h)?.let { (t, v) ->
        val d = (Offset(plot.x(t), plot.y(v)) - at).getDistance()
        if (d < dist) { dist = d; best = ValueHit.Handle(h) }
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
        val paths = (0 until scene.curves).map { ch ->
            Path().apply {
                var last = Long.MIN_VALUE
                for (i in 0..n) {
                    val t = (vp.t0 + vp.timeSpan * i / n).roundToLong()
                    // Speed jumps at keys (in ≠ out): add the two sides of every key crossed, so the jump is vertical.
                    if (scene.speed && i > 0) for (kt in scene.keyTimes) if (kt in (last + 1)..t) {
                        lineTo(plot.x((kt - 1).toDouble()), plot.y(scene.curveY(kt - 1, ch))); lineTo(plot.x(kt.toDouble()), plot.y(scene.curveY(kt, ch)))
                    }
                    val x = plot.x(t.toDouble()); val y = plot.y(scene.curveY(t, ch))
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                    last = t
                }
            }
        }
        val (tStep, frames) = ValueView.timeStep(vp.timeSpan, scene.rate)
        val vStep = ValueView.step(vp.valueSpan)
        val tTicks = ticks(vp.t0, vp.t1, tStep); val vTicks = ticks(vp.v0, vp.v1, vStep)
        val tLabels = if (labels) tTicks.map { t -> measurer.measure(timeLabel(t, frames, scene), style) } else emptyList()
        val vLabels = if (labels) vTicks.map { v -> measurer.measure(scene.tickLabel(v, vStep), style) } else emptyList()
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
            for (ch in 0 until scene.curves) if (ch != scene.curve) drawPath(paths[ch], TextSecondary.copy(alpha = .45f), style = dim)
            drawPath(paths[scene.curve], TextPrimary, style = stroke)
            handles(scene, plot)
            for (i in scene.keys.indices) {
                val p = Offset(plot.x(scene.keyTimes[i].toDouble()), plot.y(scene.keyY(i)))
                val inSegment = i == scene.segStart || i == scene.segStart + 1
                drawCircle(Surface, 5.dp.toPx(), p)
                drawCircle(if (inSegment) Highlight else TextPrimary, 3.5.dp.toPx(), p)
                if (scene.selection == i) drawCircle(Highlight, 7.dp.toPx(), p, style = Stroke(1.2.dp.toPx()))
            }
        }
    })
}

private fun DrawScope.handles(scene: ValueScene, plot: ValuePlot) {
    val line = 1.dp.toPx()
    for (h in 0..1) {
        val (t, v) = scene.handlePoint(h) ?: continue
        val (at, av) = scene.handleAnchor(h) ?: continue
        val p = Offset(plot.x(t), plot.y(v))
        drawLine(TextSecondary, Offset(plot.x(at), plot.y(av)), p, line, StrokeCap.Round)
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
        if (showDot) { val p = Offset(x, plot.y(scene.curveY(local))); drawCircle(Surface, 5.dp.toPx(), p); drawCircle(TextPrimary, 4.dp.toPx(), p) }
    }
}

/**
 * GRAPH G2 / G2.1 values bar in the Value view, on an opaque fill (nothing draws through it).
 * - Time: the selected key's time in the transport's timecode format, typed (frame-snapped, clamped to the neighbours;
 *   one undo). With no key selected (TL1c) it shows the PLAYHEAD's timecode, live, exactly as the transport and the
 *   timeline pill do (floor: the frame on screen); it is not editable then.
 * - Value: the selected key's value, else the LIVE value at the playhead (as the page shows it). Always enabled; typing
 *   edits the key, or with no key sets the value at the playhead ([GraphValues.atPlayheadAction]).
 * - 2-D properties add an X | Y toggle that picks the channel the Value tile edits.
 */
@Composable internal fun ValueValuesBar(scene: ValueScene, selected: Int?, height: Float, live: FloatArray?, onChannel: (Int) -> Unit,
    onCommit: (EditorAction, Long?) -> Unit, onTypedAtPlayhead: (Float, Float) -> EditorAction?, playheadUs: Long = 0L) {
    val key = selected?.let { scene.keys.getOrNull(it) }
    val axis = scene.axes[scene.channel]
    val anchor = scene.layer.resolvedKeyframeAnchorUs
    val time = key?.let { GraphTime.label(Math.addExact(anchor, it.timeUs), scene.rate, scene.startTimecodeUs) }
        ?: GraphTime.playhead(playheadUs, scene.rate, scene.startTimecodeUs)
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

/**
 * GRAPH G3 Speed view bar: Speed out, Influence out, Speed in, Influence in of the selected segment (the segment at the
 * playhead without a selection). Speeds in display units/s with the page's decimals, influences in % (0.1–100). Typed
 * entry is one undo each (Smooth through key applies to a typed speed). Disabled for Bounce / Elastic / Hold ("Edit in
 * Ease"); a flat segment's speeds are disabled (influence stays editable).
 */
@Composable internal fun SpeedValuesBar(scene: ValueScene, height: Float, onCommit: (HandleEdit) -> Unit) {
    val k = scene.segStart
    val seg = scene.speedSegs.getOrNull(k)
    val b = scene.bezier
    val axis = scene.axes[0]
    val unit = GraphSpeed.unit(axis)
    fun edit(h: Int, influence: Double, speed: Double?) {
        val sg = seg ?: return; val bb = b ?: return
        val nb = SpeedHandles.set(sg, bb, h, influence, speed ?: SpeedHandles.speed(sg, bb, h))
        val r = SmoothThroughKey.apply(scene.speedSegs, k, h, nb, scene.smooth)
        onCommit(HandleEdit(r.edits.first().second, r.edits.drop(1)))
    }
    Row(Modifier.fillMaxWidth().height(height.dp).background(SurfaceHigh).testTag("graph-values-bar")) {
        for (h in 0..1) {
            val side = if (h == 0) "out" else "in"
            val curveOk = b != null && seg != null
            val note = if (!curveOk) "Edit in Ease" else null
            val speedText = if (curveOk) GraphSpeed.text(axis, SpeedHandles.speed(seg!!, b!!, h)) else "–"
            val influenceText = if (b != null) String.format(java.util.Locale.ROOT, "%.1f", SpeedHandles.influence(b, h) * 100) else "–"
            if (h > 0) Box(Modifier.width(GraphLayout.HAIRLINE.dp).fillMaxHeight().background(Border))
            GraphTypedTile("Speed $side", unit, speedText, curveOk && !seg!!.flat, Modifier.weight(1.2f).fillMaxHeight().testTag("graph-speed-$side-speed"),
                description = note ?: if (seg?.flat == true) "Speed $side 0, flat segment" else null) { text ->
                val v = ValueParsing.number(text.removeSuffix("/s").trim(), axis.unit)?.takeIf { it.isFinite() } ?: return@GraphTypedTile
                val bb = b ?: return@GraphTypedTile
                edit(h, SpeedHandles.influence(bb, h), v)
            }
            Box(Modifier.width(GraphLayout.HAIRLINE.dp).fillMaxHeight().background(Border))
            GraphTypedTile("Influence $side", "%", influenceText, curveOk, Modifier.weight(1f).fillMaxHeight().testTag("graph-speed-$side-influence"),
                description = note) { text ->
                val v = ValueParsing.number(text, "%")?.takeIf { it.isFinite() } ?: return@GraphTypedTile
                edit(h, v.coerceIn(.1, 100.0) / 100.0, null)
            }
        }
    }
}

/** A values-bar tile with a corner label and a typed-entry popover (the Ease bar's tile, for free-form values). */
@Composable internal fun GraphTypedTile(label: String, unit: String, text: String, enabled: Boolean, modifier: Modifier,
    description: String? = null, onCommit: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    Box(modifier.background(if (editing) Surface else SurfaceHigh)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Type $label") { editing = true }
        .semantics(mergeDescendants = true) { contentDescription = description ?: "$label $text"; if (!enabled) disabled() }) {
        Text(label, Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 2.dp), color = TextSecondary, fontSize = 8.sp, lineHeight = 9.sp, maxLines = 1)
        Text(if (unit.isEmpty()) text else "$text$unit", Modifier.align(Alignment.Center).padding(top = 6.dp), color = if (enabled) TextPrimary else TextDisabled, maxLines = 1,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace))
        AnchoredPopover(editing, { editing = false }, "value-entry") {
            InspectorValueField(text, label, unit, { typed -> editing = false; onCommit(typed) }, { editing = false })
        }
    }
}
