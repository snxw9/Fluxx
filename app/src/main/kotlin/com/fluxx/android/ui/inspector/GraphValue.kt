package com.fluxx.android.ui.inspector

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * GRAPH G2: the pure model behind the Value graph (JVM tested). It reads keys and values through the real evaluator,
 * and writes key edits only with EXISTING actions: [EditorAction.MoveKeyframe] / [EditorAction.SetKeyframe] /
 * [EditorAction.SetKeyframe2D] for transform and text-layer tracks, and [EditorAction.EditTextAnimator] with
 * [AnimatorKeyOperation.MOVE] / [AnimatorKeyOperation.SET] for animator fields, joined in one [EditorAction.Batch]
 * (one undo). Values are written with the key's own easing, so `rekey1D/2D` keep its curve.
 */
internal data class ValueKey(val timeUs: Long, val x: Float, val y: Float, val easing: EasingPreset) {
    fun value(channel: Int) = if (channel == 0) x else y
}

/**
 * One value channel in its inspector page's units (GRAPH G2.1 §5: [units] is the page's own [PropertyUnits]);
 * [min]..[max] are the stored-value clamps (the page's hard limits, in stored units).
 */
internal data class ValueAxis(val units: PropertyUnits, val min: Float = units.storedMin, val max: Float = units.storedMax) {
    val name get() = units.name
    val unit get() = units.unit
    fun toDisplay(v: Float) = units.toDisplay(v)
    fun toStored(d: Float) = units.toStored(d)
    fun clamp(stored: Float) = stored.coerceIn(min, max)
    /** The page's text for a stored value (no unit). */
    fun storedText(stored: Float) = units.storedText(stored)
    /** The page's text for a shown value (no unit). */
    fun text(display: Float) = units.text(display)
    /** A typed shown value → stored, with the page's typed clamp (the page's `limit.typed`, then its conversion). */
    fun typed(display: Float) = clamp(toStored(units.limit.typed(display)))
    /** The finest step the page's formatter can show (tick labels finer than this fall back to more decimals). */
    val resolution: Double get() = when (units.format) {
        DisplayFormat.ONE_DECIMAL, DisplayFormat.TURNS -> .1
        DisplayFormat.SCALE_READOUT -> .01
        DisplayFormat.LIMIT -> .01
    }
}

internal object GraphValues {
    /** |dv| (display units) below this makes a segment degenerate: its handles only move in time. */
    const val FLAT = 1e-4f

    private fun override(layer: CompositionLayer, a: AnimatorTrackAddress) =
        TextAnimatorEdits.animator(layer.text, a.animatorId)?.properties?.firstOrNull { it.property == a.property }

    /** 0 = no Value graph (colour, discrete, missing), 1 = one channel, 2 = X and Y. */
    fun channels(layer: CompositionLayer, target: GraphTarget): Int {
        if (GraphModel.discrete(target)) return 0
        target.animator?.let { a ->
            if (layer.type != LayerType.TEXT || !TextAnimatorEdits.exists(layer.text, a) || a.seed) return 0
            if (a.property == null) return if (TextAnimatorEdits.track(layer.text, a) != null) 1 else 0
            val o = override(layer, a) ?: return 0
            return when { o.scalar != null -> 1; o.vector != null -> 2; else -> 0 }
        }
        return when (target.property!!) {
            AnimPropertyType.POSITION, AnimPropertyType.SCALE -> 2
            AnimPropertyType.ROTATION, AnimPropertyType.OPACITY -> 1
            AnimPropertyType.FILL_COLOUR, AnimPropertyType.STROKE_COLOUR, AnimPropertyType.SOURCE_TEXT -> 0
            else -> if (layer.type == LayerType.TEXT) 1 else 0
        }
    }

    /** The Value tab is enabled: a single-axis (or X/Y) continuous property with at least two keys. Colour is excluded. */
    fun available(layer: CompositionLayer, target: GraphTarget) = channels(layer, target) > 0 && GraphModel.enabled(layer, target)

    fun keys(layer: CompositionLayer, target: GraphTarget): List<ValueKey> {
        fun one(list: List<Keyframe1D>) = list.map { ValueKey(it.timeUs, it.value, 0f, it.easing) }
        fun two(list: List<Keyframe2D>) = list.map { ValueKey(it.timeUs, it.x, it.y, it.easing) }
        target.animator?.let { a ->
            if (channels(layer, target) == 0) return emptyList()
            if (a.property == null) return one(TextAnimatorEdits.track(layer.text, a)?.keyframes.orEmpty())
            val o = override(layer, a) ?: return emptyList()
            return o.scalar?.let { one(it.keyframes) } ?: o.vector?.let { two(it.keyframes) } ?: emptyList()
        }
        val at = layer.animTransform
        return when (val p = target.property!!) {
            AnimPropertyType.POSITION -> two(at.position.keyframes)
            AnimPropertyType.SCALE -> two(at.scale.keyframes)
            AnimPropertyType.ROTATION -> one(at.rotation.keyframes)
            AnimPropertyType.OPACITY -> one(at.opacity.keyframes)
            else -> if (channels(layer, target) == 1) one(layer.text.scalar(p).keyframes) else emptyList()
        }
    }

    /**
     * A sampler over local time using the property's own evaluator (the same keyframe evaluation the renderer and the
     * Ease view use). Writes the stored value(s) into [out] (index 0 = X / the value, 1 = Y); allocation-free per call.
     */
    fun sampler(layer: CompositionLayer, target: GraphTarget): ((Long, FloatArray) -> Unit)? {
        target.animator?.let { a ->
            if (channels(layer, target) == 0) return null
            if (a.property == null) { val t = TextAnimatorEdits.track(layer.text, a) ?: return null; return { time, out -> out[0] = t.evaluate(time) } }
            val o = override(layer, a) ?: return null
            o.scalar?.let { s -> return { time, out -> out[0] = s.evaluate(time) } }
            o.vector?.let { v -> return { time, out -> v.evaluate(time, out, 0) } }
            return null
        }
        val at = layer.animTransform
        return when (val p = target.property!!) {
            AnimPropertyType.POSITION -> { time, out -> at.position.evaluate(time, out, 0) }
            AnimPropertyType.SCALE -> { time, out -> at.scale.evaluate(time, out, 0) }
            AnimPropertyType.ROTATION -> { time, out -> out[0] = at.rotation.evaluate(time) }
            AnimPropertyType.OPACITY -> { time, out -> out[0] = at.opacity.evaluate(time) }
            else -> if (channels(layer, target) == 1) { val t = layer.text.scalar(p); { time, out -> out[0] = t.evaluate(time) } } else null
        }
    }

    /** The value axes, one per channel, in the inspector page's units ([PropertyDisplay]) with its hard limits (stored units). */
    fun axes(layer: CompositionLayer, target: GraphTarget, compositionWidth: Int, compositionHeight: Int): List<ValueAxis> =
        PropertyDisplay.of(layer, target, compositionWidth, compositionHeight, channels(layer, target)).map { ValueAxis(it) }

    /**
     * GRAPH G2.1 §6: the live stored value(s) at the playhead, read exactly as the property's inspector page reads it
     * (Transform / Opacity: the evaluated transform at the raw playhead; Text: the layer's clamped readers at the
     * frame-snapped playhead; Animate: [TextAnimatorEdits.value] / [TextAnimatorEdits.vector]). Null when unavailable.
     */
    fun live(layer: CompositionLayer, target: GraphTarget, playheadUs: Long, rate: FrameRate): FloatArray? {
        if (channels(layer, target) == 0) return null
        val local = GraphModel.local(layer, target, playheadUs, rate)
        return runCatching {
            target.animator?.let { a ->
                return@runCatching if (channels(layer, target) == 2) TextAnimatorEdits.vector(layer.text, a, local)
                else floatArrayOf(TextAnimatorEdits.value(layer.text, a, local), 0f)
            }
            when (val p = target.property!!) {
                AnimPropertyType.POSITION -> layer.evaluatedTransform(playheadUs).let { floatArrayOf(it.positionX, it.positionY) }
                AnimPropertyType.SCALE -> layer.evaluatedTransform(playheadUs).let { floatArrayOf(it.scaleX, it.scaleY) }
                AnimPropertyType.ROTATION -> floatArrayOf(layer.evaluatedTransform(playheadUs).rotationDegrees, 0f)
                AnimPropertyType.OPACITY -> floatArrayOf(layer.evaluatedTransform(playheadUs).opacity, 0f)
                AnimPropertyType.FONT_SIZE -> floatArrayOf(layer.text.sizeAt(local), 0f)
                AnimPropertyType.STROKE_WIDTH -> floatArrayOf(layer.text.strokeWidthAt(local), 0f)
                else -> floatArrayOf(layer.text.scalar(p).evaluate(local), 0f)
            }
        }.getOrNull()
    }

    /**
     * GRAPH G2.1 §6: a typed value with no key selected sets the value AT THE PLAYHEAD, with the existing action the
     * property's page uses for an animated property: [EditorAction.SetKeyframe2D] / [EditorAction.SetKeyframe] at
     * [GraphModel.local] keeping an existing key's easing (else Easy Ease, as `EditorViewModel.editTransform`), or the
     * Animate page's [TextAnimatorEdits.set]. [x] / [y] are stored values. Null when nothing changes.
     */
    fun atPlayheadAction(layer: CompositionLayer, target: GraphTarget, playheadUs: Long, rate: FrameRate, x: Float, y: Float): EditorAction? {
        val current = live(layer, target, playheadUs, rate) ?: return null
        val two = channels(layer, target) == 2
        if (x == current[0] && (!two || y == current[1])) return null
        val local = GraphModel.local(layer, target, playheadUs, rate)
        target.animator?.let { a ->
            return TextAnimatorEdits.action(layer.id, listOf(TextAnimatorEdits.set(layer.text, a, local, x, if (two) y else null)))
        }
        val p = target.property!!
        val easing = keys(layer, target).firstOrNull { it.timeUs == local }?.easing ?: EasingPreset.EASY_EASE
        return if (two) EditorAction.SetKeyframe2D(layer.id, p, local, x, y, easing) else EditorAction.SetKeyframe(layer.id, p, local, x, easing)
    }

    /** The header's start / end value labels in the page's units and formatting (colour: [GraphModel.values]'s hex). */
    fun labels(layer: CompositionLayer, target: GraphTarget, s: GraphSegment, compositionWidth: Int, compositionHeight: Int): Pair<String, String> {
        val axes = axes(layer, target, compositionWidth, compositionHeight)
        if (axes.isEmpty()) return GraphModel.values(layer, target, s)
        val ks = keys(layer, target)
        fun at(time: Long): String {
            val k = ks.firstOrNull { it.timeUs == time } ?: return ""
            return axes.indices.joinToString(", ") { ch -> axes[ch].storedText(k.value(ch)) } + axes[0].unit
        }
        return at(s.startUs) to at(s.endUs)
    }

    /** A stored value clamped by the axis and, for animator fields, the model's own validated range. */
    fun clampValue(target: GraphTarget, axis: ValueAxis, stored: Float): Float {
        val v = axis.clamp(stored)
        return target.animator?.takeIf { it.property != null || it.field != null }?.let { TextAnimatorEdits.clamp(it, v) } ?: v
    }

    // ---- Time -------------------------------------------------------------------------------------------------

    /** Frame index of a local time (on the composition frame grid, through the layer's keyframe anchor). */
    fun frame(layer: CompositionLayer, rate: FrameRate, local: Long): Long = Markers.signedFrame(rate, Math.addExact(layer.resolvedKeyframeAnchorUs, local))
    fun timeOf(layer: CompositionLayer, rate: FrameRate, frame: Long): Long {
        val absolute = if (frame >= 0) rate.frameTimeUs(frame) else Math.negateExact(rate.frameTimeUs(Math.negateExact(frame)))
        return Math.subtractExact(absolute, layer.resolvedKeyframeAnchorUs)
    }

    /**
     * Where key [index] of [keyTimes] may go: frame-snapped, at least one frame from both neighbours (never crossing or
     * equal), and inside the clip (the range the timeline's key drag uses). A key already outside the clip keeps its
     * own time as a bound so it is never forced to jump.
     */
    fun clampTime(layer: CompositionLayer, rate: FrameRate, keyTimes: List<Long>, index: Int, local: Long): Long {
        val own = keyTimes[index]
        var lo = Markers.signedFrame(rate, layer.timing.startUs)
        var hi = layer.timing.endUs?.let { Markers.signedFrame(rate, it) } ?: Long.MAX_VALUE / 4
        val ownFrame = frame(layer, rate, own)
        lo = minOf(lo, ownFrame); hi = maxOf(hi, ownFrame)
        if (index > 0) lo = maxOf(lo, frame(layer, rate, keyTimes[index - 1]) + 1)
        if (index < keyTimes.lastIndex) hi = minOf(hi, frame(layer, rate, keyTimes[index + 1]) - 1)
        if (lo > hi) return own
        val f = frame(layer, rate, local).coerceIn(lo, hi)
        return timeOf(layer, rate, f)
    }

    /**
     * One undoable key edit (time and/or value of ONE key) with existing actions. [x]/[y] are stored values; for a
     * one-channel property [y] is ignored. Returns null when nothing changes.
     */
    fun keyAction(layer: CompositionLayer, target: GraphTarget, key: ValueKey, newTimeUs: Long, x: Float, y: Float): EditorAction? {
        val moved = newTimeUs != key.timeUs
        val changed = x != key.x || (y != key.y && channels(layer, target) == 2)
        if (!moved && !changed) return null
        val id = layer.id
        val list = ArrayList<EditorAction>(2)
        target.animator?.let { a ->
            if (moved) list += EditorAction.EditTextAnimator(id, AnimatorEdit.Key(a, AnimatorKeyOperation.MOVE, key.timeUs, newTimeUs))
            if (changed) list += EditorAction.EditTextAnimator(id, AnimatorEdit.Key(a, AnimatorKeyOperation.SET, newTimeUs,
                value = x, y = if (channels(layer, target) == 2) y else null, easing = key.easing))
            return EditorAction.Batch(list)
        }
        val p = target.property!!
        if (moved) list += EditorAction.MoveKeyframe(id, p, key.timeUs, newTimeUs)
        if (changed) list += when (p) {
            AnimPropertyType.POSITION, AnimPropertyType.SCALE -> EditorAction.SetKeyframe2D(id, p, newTimeUs, x, y, key.easing)
            else -> EditorAction.SetKeyframe(id, p, newTimeUs, x, key.easing)
        }
        return EditorAction.Batch(list)
    }
}

// ---- Viewport -----------------------------------------------------------------------------------------------------

/** The visible window of the Value graph: local time [t0, t1] (µs) across, display value [v0, v1] upward. */
internal data class ValueViewport(val t0: Double, val t1: Double, val v0: Double, val v1: Double) {
    val timeSpan get() = t1 - t0
    val valueSpan get() = v1 - v0
    /** Grown (never shrunk) to include the point. */
    fun include(t: Double, v: Double, margin: Double = .05): ValueViewport {
        val mt = timeSpan * margin; val mv = valueSpan * margin
        return ValueViewport(minOf(t0, t - mt), maxOf(t1, t + mt), minOf(v0, v - mv), maxOf(v1, v + mv))
    }
    fun describe(): String = String.format(java.util.Locale.ROOT, "t %.3f–%.3f s, v %.3f–%.3f", t0 / 1e6, t1 / 1e6, v0, v1)
}

internal enum class ZoomAxes { TIME, VALUE, BOTH }

internal object ValueView {
    const val MARGIN_T = .06
    const val MARGIN_V = .12

    /** Fit: the keys' time range and the curve's value extent (overshoot included) with a small margin. */
    fun fit(tMin: Long, tMax: Long, vMin: Double, vMax: Double): ValueViewport {
        val ts = (tMax - tMin).toDouble().coerceAtLeast(1.0)
        val vs = (vMax - vMin).let { if (it < 1e-6) maxOf(1.0, abs(vMax) * .2) else it }
        val vc = (vMin + vMax) / 2
        return ValueViewport(tMin - ts * MARGIN_T, tMax + ts * MARGIN_T, vc - vs / 2 - vs * MARGIN_V, vc + vs / 2 + vs * MARGIN_V)
    }

    /** Zoom limits: at least 2 frames in time, at most 20× the content; value at least 1e-3 of the content (min 1e-3). */
    data class Limits(val minTime: Double, val maxTime: Double, val minValue: Double, val maxValue: Double, val content: ValueViewport)
    fun limits(content: ValueViewport, frameUs: Double): Limits =
        Limits(2 * frameUs, maxOf(content.timeSpan * 20, 4 * frameUs), maxOf(1e-3, content.valueSpan * 1e-3), maxOf(content.valueSpan * 20, 1e-2), content)

    /** Pan by data deltas, keeping the window's centre inside the content window (fit + margin). */
    fun pan(vp: ValueViewport, dt: Double, dv: Double, limits: Limits): ValueViewport = clamp(
        ValueViewport(vp.t0 + dt, vp.t1 + dt, vp.v0 + dv, vp.v1 + dv), limits)

    /**
     * Pinch zoom: [scale] > 1 zooms in. The data point under the focal fraction ([fx] across, [fy] down) stays fixed.
     * Only the chosen [axes] change.
     */
    fun zoom(vp: ValueViewport, fx: Double, fy: Double, scale: Double, axes: ZoomAxes, limits: Limits): ValueViewport {
        var t0 = vp.t0; var t1 = vp.t1; var v0 = vp.v0; var v1 = vp.v1
        if (axes != ZoomAxes.VALUE) {
            val span = (vp.timeSpan / scale).coerceIn(limits.minTime, limits.maxTime)
            val ft = vp.t0 + fx * vp.timeSpan
            t0 = ft - fx * span; t1 = t0 + span
        }
        if (axes != ZoomAxes.TIME) {
            val span = (vp.valueSpan / scale).coerceIn(limits.minValue, limits.maxValue)
            val fv = vp.v1 - fy * vp.valueSpan
            v1 = fv + fy * span; v0 = v1 - span
        }
        return clamp(ValueViewport(t0, t1, v0, v1), limits)
    }

    /** Which axes a pinch zooms, from the two fingers' separation: mostly horizontal → time, mostly vertical → value. */
    fun axes(dx: Float, dy: Float): ZoomAxes = when {
        abs(dx) > 2 * abs(dy) -> ZoomAxes.TIME
        abs(dy) > 2 * abs(dx) -> ZoomAxes.VALUE
        else -> ZoomAxes.BOTH
    }

    private fun clamp(vp: ValueViewport, l: Limits): ValueViewport {
        val c = l.content
        val tc = ((vp.t0 + vp.t1) / 2).coerceIn(c.t0, c.t1); val ts = vp.timeSpan
        val vc = ((vp.v0 + vp.v1) / 2).coerceIn(c.v0, c.v1); val vs = vp.valueSpan
        return ValueViewport(tc - ts / 2, tc + ts / 2, vc - vs / 2, vc + vs / 2)
    }

    /** A 1-2-5 tick step giving at most [max] ticks over [span]. */
    fun step(span: Double, max: Int = 4): Double {
        if (span <= 0 || !span.isFinite()) return 1.0
        val raw = span / max
        val p = 10.0.pow(floor(log10(raw)))
        val m = raw / p
        val n = when { m <= 1 -> 1.0; m <= 2 -> 2.0; m <= 5 -> 5.0; else -> 10.0 }
        return n * p
    }

    /** Time ticks: whole frames while the step is under one second, else seconds. Step in µs and whether to label frames. */
    fun timeStep(spanUs: Double, rate: FrameRate, max: Int = 4): Pair<Double, Boolean> {
        val frameUs = 1e6 * rate.denominator / rate.numerator
        val frames = step(spanUs / frameUs, max).coerceAtLeast(1.0)
        return if (frames * frameUs < 1e6) frames * frameUs to true else step(spanUs / 1e6, max).coerceAtLeast(.1) * 1e6 to false
    }

    /** Snap a display value to the visible value grid step. */
    fun snapValue(v: Double, vp: ValueViewport) = step(vp.valueSpan).let { s -> Math.round(v / s) * s }
}

/** Plot mapping of the Value canvas inside an [inset] margin. */
internal class ValuePlot(val width: Float, val height: Float, val inset: Float, val vp: ValueViewport) {
    val plotWidth get() = (width - 2 * inset).coerceAtLeast(1f)
    val plotHeight get() = (height - 2 * inset).coerceAtLeast(1f)
    fun x(t: Double) = (inset + (t - vp.t0) / vp.timeSpan * plotWidth).toFloat()
    fun y(v: Double) = (inset + (vp.v1 - v) / vp.valueSpan * plotHeight).toFloat()
    fun time(px: Float) = vp.t0 + (px - inset) / plotWidth * vp.timeSpan
    fun value(py: Float) = vp.v1 - (py - inset) / plotHeight * vp.valueSpan
}

/**
 * The selected segment's easing handles on the value curve (§B3): P1 = (t0 + x1·dt, v0 + y1·dv), P2 = (t0 + x2·dt,
 * v0 + y2·dv) in display units. A flat segment (|dv| < [GraphValues.FLAT]) draws them on the value line and maps
 * back in time only.
 */
internal object ValueHandles {
    fun point(b: FloatArray, handle: Int, t0: Double, v0: Double, t1: Double, v1: Double): Pair<Double, Double> {
        val dt = t1 - t0; val dv = v1 - v0
        val x = b[handle * 2].toDouble(); val y = b[handle * 2 + 1].toDouble()
        return (t0 + x * dt) to (if (abs(dv) < GraphValues.FLAT) v0 else v0 + y * dv)
    }
    /** Normalised (x, y) for a dragged handle at (t, v); y is the handle's current y when the segment is flat. */
    fun normalised(b: FloatArray, handle: Int, t: Double, v: Double, t0: Double, v0: Double, t1: Double, v1: Double): Pair<Float, Float> {
        val dt = (t1 - t0).let { if (abs(it) < 1e-9) 1e-9 else it }; val dv = v1 - v0
        val x = ((t - t0) / dt).toFloat()
        val y = if (abs(dv) < GraphValues.FLAT) b[handle * 2 + 1] else ((v - v0) / dv).toFloat()
        return x to y
    }
    fun flat(v0: Double, v1: Double) = abs(v1 - v0) < GraphValues.FLAT
}

// ---- Selection and session ----------------------------------------------------------------------------------------

/** A tapped key: its local time and the playhead at the moment it was selected. */
internal data class KeySelection(val keyTimeUs: Long, val playheadUs: Long)

internal object GraphSelection {
    /** The selection counts only while the playhead has not moved since and the key still exists. */
    fun valid(sel: KeySelection?, keyTimes: List<Long>, playheadUs: Long) = sel != null && sel.playheadUs == playheadUs && sel.keyTimeUs in keyTimes
    /**
     * The edited segment: a selected key's DEPARTING segment (the last key: the previous one); otherwise the G1 rule at
     * the playhead ([GraphModel.segmentIndex]).
     */
    fun segmentIndex(keyTimes: List<Long>, sel: KeySelection?, playheadUs: Long, localUs: Long): Int? {
        if (keyTimes.size < 2) return null
        if (valid(sel, keyTimes, playheadUs)) { val k = keyTimes.indexOf(sel!!.keyTimeUs); return if (k == keyTimes.lastIndex) k - 1 else k }
        return GraphModel.segmentIndex(keyTimes, localUs)
    }
    fun segment(layer: CompositionLayer, target: GraphTarget, sel: KeySelection?, playheadUs: Long, localUs: Long): GraphSegment? {
        val keys = GraphModel.keyTimes(layer, target)
        val i = segmentIndex(keys, sel, playheadUs, localUs) ?: return null
        val curve = GraphModel.curveAt(layer, target, keys[i]) ?: return null
        return GraphSegment(i, keys.size, keys[i], keys[i + 1], curve)
    }
}

internal enum class GraphMode { VALUE, SPEED, EASE }

/**
 * Graph page state that outlives recomposition while the page is open (the sheet's header and the page body share it)
 * and is reset whenever the page opens or closes: the view, the selected key, the Value viewport (null = fit), the
 * edited channel and the library's scroll position.
 */
internal class GraphSession {
    var mode by mutableStateOf(GraphMode.EASE)
    var selection by mutableStateOf<KeySelection?>(null)
    var viewport by mutableStateOf<ValueViewport?>(null)
    /** GRAPH G3: the Speed view's own viewport (reset with the page, i.e. when the property changes). */
    var speedViewport by mutableStateOf<ValueViewport?>(null)
    /** GRAPH G3.1: the Speed view's selected handle (0 out, 1 in), for the Ease-style relative drag. */
    var speedHandle by mutableStateOf<Int?>(null)
    /** GRAPH G4.3: a one-shot curve edit (library, type strip, preset) waiting for the Speed view to check its window. */
    var reframeCurve by mutableStateOf<EasingCurve?>(null)
    var channel by mutableStateOf(0)
    var libraryGrid = LazyGridState()
        private set
    fun viewportFor(speed: Boolean) = if (speed) speedViewport else viewport
    fun setViewportFor(speed: Boolean, v: ValueViewport?) { if (speed) speedViewport = v else viewport = v }
    /** [mode]: the tab to open on (G3: the last-used tab, persisted app-level; it falls back to Ease where unavailable). */
    fun reset(mode: GraphMode = GraphMode.EASE) { this.mode = mode; selection = null; viewport = null; speedViewport = null; speedHandle = null; reframeCurve = null; channel = 0; libraryGrid = LazyGridState() }
}

// ---- Library grid -------------------------------------------------------------------------------------------------

/** GRAPH G2 (A1): drop positions in the 2-column library grid, from the pointer position over the pinned cells. */
internal object GridDrop {
    /** The insertion gap (0..cells.size) for a pointer at (x, y): before a cell when left of its centre, else after it. */
    fun index(cells: List<Rect>, x: Float, y: Float): Int? {
        if (cells.isEmpty()) return 0
        for ((i, r) in cells.withIndex()) {
            if (y < r.top) return i
            // In this cell's row: before it when left of its centre; otherwise try the next cell in the row.
            if (y <= r.bottom && x < r.center.x) return i
        }
        return cells.size
    }
    /** The cell under the pointer (replace when the pinned set is full), or the nearest one in the same row. */
    fun slot(cells: List<Rect>, x: Float, y: Float): Int? {
        val row = cells.withIndex().filter { y >= it.value.top && y <= it.value.bottom }
        if (row.isEmpty()) return null
        return row.minByOrNull { abs(it.value.center.x - x) }!!.index
    }
}

/**
 * GRAPH G2.1 §6a: Graph times read exactly like the transport and the timeline's playhead pill:
 * [CompositionTimecode.format] (MM:SS:FF, nearest frame) of the composition start timecode + composition time.
 */
internal object GraphTime {
    /** A time on the frame grid (keys, axis ticks): start frame + its nearest frame, as the ruler and pill count. */
    fun label(compositionUs: Long, rate: FrameRate, startTimecodeUs: Long = 0L): String =
        CompositionTimecode.formatOnGrid(compositionUs, startTimecodeUs, rate)
    /** TL1c: the playhead, exactly as the transport and the timeline pill show it (floor: the frame on screen). */
    fun playhead(playheadUs: Long, rate: FrameRate, startTimecodeUs: Long = 0L): String =
        CompositionTimecode.formatPlayhead(playheadUs, startTimecodeUs, rate)
    /** A typed time: a timecode (MM:SS:FF, as shown) or a whole number of composition frames ("45" or "45f"). Composition µs. */
    fun parse(text: String, rate: FrameRate, startTimecodeUs: Long = 0L): Long? {
        if (text.contains(':')) return CompositionTimecode.parseOnGrid(text, startTimecodeUs, rate)
        val f = ValueParsing.integer(text.trim().removeSuffix("f").trim())?.takeIf { it >= 0 } ?: return null
        return runCatching { rate.frameTimeUs(f) }.getOrNull()
    }
}
