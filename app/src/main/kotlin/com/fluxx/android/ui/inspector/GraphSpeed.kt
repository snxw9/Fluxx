package com.fluxx.android.ui.inspector

import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * GRAPH G3: the pure model behind the Speed graph (JVM tested). A segment from (t0, v0) to (t1, v1) in display units
 * (the Value graph's [ValueAxis] units) evaluates as v0 + (v1 − v0)·p(τ), τ = (t − t0)/Δt, so its speed along its own
 * direction is S(t) = (D/Δt)·p′(τ) with D = |v1 − v0| (2-D: the length of the display-unit displacement). S ≥ 0 for
 * monotone easing and goes negative where an overshoot reverses. Outside the first and last keys the speed is 0.
 */
internal object SpeedMath {
    /** p′(τ) on the SAME cubic the evaluator solves ([EasingMath.bezier]): dy/ds ÷ dx/ds at the s with x(s) = τ. */
    fun bezierSlope(tau: Double, x1: Float, y1: Float, x2: Float, y2: Float): Double {
        val t = tau.coerceIn(0.0, 1.0)
        val cx = 3.0 * x1; val bx = 3.0 * (x2 - x1) - cx; val ax = 1.0 - cx - bx
        val cy = 3.0 * y1; val by = 3.0 * (y2 - y1) - cy; val ay = 1.0 - cy - by
        var s = t
        var solved = false
        for (i in 0 until 8) {
            val x = ((ax * s + bx) * s + cx) * s - t
            if (abs(x) < 1e-12) { solved = true; break }
            val dx = (3.0 * ax * s + 2.0 * bx) * s + cx
            if (abs(dx) < 1e-12) break
            s -= x / dx
            if (s < 0.0 || s > 1.0) break
        }
        if (!solved || s < 0.0 || s > 1.0) {
            var lo = 0.0; var hi = 1.0; s = t
            for (i in 0 until 80) { s = (lo + hi) * .5; val x = ((ax * s + bx) * s + cx) * s; if (abs(x - t) < 1e-14) break; if (x < t) lo = s else hi = s }
        }
        fun dxAt(u: Double) = (3.0 * ax * u + 2.0 * bx) * u + cx
        fun dyAt(u: Double) = (3.0 * ay * u + 2.0 * by) * u + cy
        var dx = dxAt(s)
        // A zero x-velocity (an end handle with x = 0 or 1) has a finite limit: step a hair inside.
        if (abs(dx) < 1e-9) { s = if (s < .5) s + 1e-5 else s - 1e-5; dx = dxAt(s) }
        if (abs(dx) < 1e-12) return 0.0
        return dyAt(s) / dx
    }

    /** p′(τ) of any easing: analytic for the bezier types, a central difference of the real evaluator for Bounce / Elastic, 0 for Hold. */
    fun slope(curve: EasingCurve, tau: Double): Double {
        curve.bezierOrNull()?.let { return bezierSlope(tau, it[0], it[1], it[2], it[3]) }
        if (curve.type == EasingPreset.HOLD) return 0.0
        val h = 1e-3
        val a = (tau - h).coerceIn(0.0, 1.0); val b = (tau + h).coerceIn(0.0, 1.0)
        if (b <= a) return 0.0
        return (curve.ease(b.toFloat()) - curve.ease(a.toFloat())).toDouble() / (b - a)
    }
}

/** One segment of the Speed graph: local times (µs), D in display units, the unit chord direction and the easing. */
internal class SpeedSegment(val t0: Long, val t1: Long, val delta: Double, val dir: DoubleArray, val curve: EasingCurve) {
    val seconds: Double get() = (t1 - t0) / 1e6
    /** D/Δt: the average speed (display units per second). */
    val rate: Double get() = if (seconds > 0) delta / seconds else 0.0
    val flat: Boolean get() = delta < GraphValues.FLAT
    val bezier: FloatArray? get() = curve.bezierOrNull()
    /** Speed at local [t] inside the segment. */
    fun speed(t: Long): Double {
        if (flat || curve.type == EasingPreset.HOLD || t1 <= t0) return 0.0
        return rate * SpeedMath.slope(curve, (t - t0).toDouble() / (t1 - t0))
    }
}

internal object GraphSpeed {
    /** The segments of [target] in the axes' display units (one combined curve for 2-D properties). */
    fun segments(layer: CompositionLayer, target: GraphTarget, axes: List<ValueAxis>): List<SpeedSegment> {
        val keys = GraphValues.keys(layer, target)
        if (keys.size < 2 || axes.isEmpty()) return emptyList()
        return (0 until keys.size - 1).map { i ->
            val a = keys[i]; val b = keys[i + 1]
            val dx = axes[0].toDisplay(b.x).toDouble() - axes[0].toDisplay(a.x)
            val dy = if (axes.size == 2) axes[1].toDisplay(b.y).toDouble() - axes[1].toDisplay(a.y) else 0.0
            val d = hypot(dx, dy)
            val dir = if (d > 0) doubleArrayOf(dx / d, dy / d) else doubleArrayOf(0.0, 0.0)
            SpeedSegment(a.timeUs, b.timeUs, d, dir, GraphModel.curveAt(layer, target, a.timeUs) ?: EasingCurve.EASY_EASE)
        }
    }
    /** Speed at local [t]: 0 outside the keys; on a key, the segment that departs it (the last key: the arriving one). */
    fun speedAt(segs: List<SpeedSegment>, t: Long): Double {
        if (segs.isEmpty() || t < segs.first().t0 || t > segs.last().t1) return 0.0
        for (s in segs) if (t >= s.t0 && t < s.t1) return s.speed(t)
        return segs.last().speed(t)
    }
    /** The speed a key shows (its departing segment's start speed; the last key: the arriving segment's end speed). */
    fun keySpeed(segs: List<SpeedSegment>, k: Int): Double =
        if (k < segs.size) segs[k].speed(segs[k].t0) else segs.last().speed(segs.last().t1)

    /** The speed text in the page's units and decimals, with "/s" (Rotation reads as a plain number: turns are not a rate). */
    fun text(axis: ValueAxis, speed: Double): String = when (axis.units.format) {
        DisplayFormat.TURNS -> PropertyDisplay.oneDecimal(speed.toFloat())
        else -> axis.text(speed.toFloat())
    }
    fun unit(axis: ValueAxis) = "${axis.unit}/s"
}

/**
 * GRAPH G3 §2: After-Effects-style speed / influence handles of a bezier segment.
 * - Out (start key): influence i0 = x1, speed S0 = (D/Δt)·y1/x1, drawn at (t0 + i0·Δt, S0).
 * - In (end key): influence i1 = 1 − x2, speed S1 = (D/Δt)·(1 − y2)/(1 − x2), drawn at (t1 − i1·Δt, S1).
 * Dragging: x sets the influence (0.01–1), y the speed: y1 = x1·S0/(D/Δt), y2 = 1 − i1·S1/(D/Δt); y clamped to [−2, 3].
 * A flat segment (D below the flat threshold) has speed 0 everywhere: handles sit on the zero line and move in x only.
 */
internal object SpeedHandles {
    const val INFLUENCE_MIN = .01f
    /** Influence (0..1) and speed of handle [h]. */
    fun influence(b: FloatArray, h: Int): Double = if (h == 0) b[0].toDouble() else 1.0 - b[2]
    fun speed(seg: SpeedSegment, b: FloatArray, h: Int): Double {
        if (seg.flat) return 0.0
        val i = influence(b, h)
        return if (i > 1e-9) seg.rate * (if (h == 0) b[1] / b[0].toDouble() else (1.0 - b[3]) / (1.0 - b[2]))
        else seg.rate * SpeedMath.bezierSlope(if (h == 0) 0.0 else 1.0, b[0], b[1], b[2], b[3])
    }
    /** Handle [h]'s point (local µs, speed). */
    fun point(seg: SpeedSegment, b: FloatArray, h: Int): Pair<Double, Double> {
        val dt = (seg.t1 - seg.t0).toDouble(); val i = influence(b, h)
        return (if (h == 0) seg.t0 + i * dt else seg.t1 - i * dt) to speed(seg, b, h)
    }
    /** The key's own point that handle [h]'s line starts from (its speed equals the handle's: the line is level). */
    fun anchor(seg: SpeedSegment, b: FloatArray, h: Int): Pair<Double, Double> =
        (if (h == 0) seg.t0.toDouble() else seg.t1.toDouble()) to speed(seg, b, h)

    /** Sets handle [h] to influence [influence] (0..1) and speed [speed]; returns the new bezier (clamped to the curve limits). */
    fun set(seg: SpeedSegment, b: FloatArray, h: Int, influence: Double, speed: Double?): FloatArray {
        val i = influence.coerceIn(INFLUENCE_MIN.toDouble(), 1.0).toFloat()
        val o = b.copyOf()
        if (h == 0) {
            o[0] = i
            if (!seg.flat && speed != null && seg.rate > 0) o[1] = (i * speed / seg.rate).toFloat().coerceIn(EasingLimits.BEZIER_Y_MIN, EasingLimits.BEZIER_Y_MAX)
        } else {
            o[2] = (1f - i).coerceIn(EasingLimits.BEZIER_X_MIN, EasingLimits.BEZIER_X_MAX)
            if (!seg.flat && speed != null && seg.rate > 0) o[3] = (1.0 - i * speed / seg.rate).toFloat().coerceIn(EasingLimits.BEZIER_Y_MIN, EasingLimits.BEZIER_Y_MAX)
        }
        return o
    }
    /** A drag of handle [h] to (local [t] µs, [speed]); Snap snaps the influence to 5% and the speed to [grid]. */
    fun drag(seg: SpeedSegment, b: FloatArray, h: Int, t: Double, speed: Double, snap: Boolean, grid: Double): FloatArray {
        val dt = (seg.t1 - seg.t0).toDouble().coerceAtLeast(1.0)
        var i = if (h == 0) (t - seg.t0) / dt else (seg.t1 - t) / dt
        var s = speed
        if (snap) { i = (i / .05).roundToInt() * .05; if (grid > 0) s = (s / grid).roundToInt() * grid }
        return set(seg, b, h, i, s)
    }
}

/**
 * GRAPH G3 §3 "Smooth through key": after an edit that changes the speed at a key with a segment on both sides, the
 * neighbouring segment's handle at that key gets the same speed (in display units/s, converted through the neighbour's
 * own D/Δt); influences stay independent. Opposite directions on the two sides (a peak or valley at the key) can only
 * match at zero: both sides become 0 and the edited handle's speed is pinned to 0. For Position / Scale this matches the
 * magnitude along each segment's chord (spatial tangents are out of scope). Pure; the caller joins the edits in one Batch.
 */
internal object SmoothThroughKey {
    /** The edits: segment start key time → new curve (the edited segment first). */
    data class Result(val edits: List<Pair<Long, EasingCurve>>, val edited: FloatArray)

    fun apply(segs: List<SpeedSegment>, index: Int, handle: Int, edited: FloatArray, enabled: Boolean): Result {
        val seg = segs[index]
        fun only() = Result(listOf(seg.t0 to EasingCurve.bezier(edited[0], edited[1], edited[2], edited[3])), edited)
        if (!enabled || seg.flat) return only()
        val n = if (handle == 0) index - 1 else index + 1
        val nb = segs.getOrNull(n) ?: return only()
        val nbB = nb.bezier ?: return only()
        if (nb.flat) return only()
        val e = edited.copyOf()
        val same = seg.dir[0] * nb.dir[0] + seg.dir[1] * nb.dir[1] > 0
        var s = SpeedHandles.speed(seg, e, handle)
        if (!same) {
            s = 0.0
            if (handle == 0) e[1] = 0f else e[3] = 1f
        }
        // The neighbour's handle AT the shared key: its in-handle when the key ends it, its out-handle when the key starts it.
        val nh = if (handle == 0) 1 else 0
        val nbNew = SpeedHandles.set(nb, nbB, nh, SpeedHandles.influence(nbB, nh), s)
        return Result(listOf(seg.t0 to EasingCurve.bezier(e[0], e[1], e[2], e[3]),
            nb.t0 to EasingCurve.bezier(nbNew[0], nbNew[1], nbNew[2], nbNew[3])), e)
    }
}

/**
 * GRAPH G3.1 §3: the Speed view's stable window. It frames the selected segment plus 30% of each neighbouring segment's
 * duration on each side (8% of the segment where there is no neighbour), and in speed the curve over that window, 0, the
 * average speed and both handles' speeds, with [HEADROOM] of the span added above and below. It is computed on entering
 * the view, when the selected segment changes, on double-tap Fit, and never while the curve changes. After a drag
 * ends, the window expands once if a handle is within [EDGE] of an edge.
 */
internal object SpeedFrame {
    const val NEIGHBOUR = .3
    const val NO_NEIGHBOUR = .08
    const val HEADROOM = .35
    const val EDGE = .1
    /** The smallest speed span (display units/s) for a flat segment, so the plot never collapses. */
    const val MIN_SPAN = 1.0

    fun frame(segs: List<SpeedSegment>, index: Int): ValueViewport? {
        val seg = segs.getOrNull(index) ?: return null
        val dt = (seg.t1 - seg.t0).toDouble()
        val left = segs.getOrNull(index - 1)?.let { NEIGHBOUR * (it.t1 - it.t0) } ?: (NO_NEIGHBOUR * dt)
        val right = segs.getOrNull(index + 1)?.let { NEIGHBOUR * (it.t1 - it.t0) } ?: (NO_NEIGHBOUR * dt)
        val t0 = seg.t0 - left; val t1 = seg.t1 + right
        var lo = 0.0; var hi = 0.0
        fun take(v: Double) { if (v.isFinite()) { lo = minOf(lo, v); hi = maxOf(hi, v) } }
        val n = 240
        for (i in 0..n) take(GraphSpeed.speedAt(segs, (t0 + (t1 - t0) * i / n).toLong()))
        take(seg.rate)
        seg.bezier?.let { b -> for (h in 0..1) take(SpeedHandles.speed(seg, b, h)) }
        val span = maxOf(hi - lo, MIN_SPAN)
        return ValueViewport(t0, t1, lo - span * HEADROOM, hi + span * HEADROOM)
    }
    /** A point (t, speed) lies within [EDGE] of the window's top or bottom (or left / right). */
    fun nearEdge(vp: ValueViewport, points: List<Pair<Double, Double>>): Boolean = points.any { (t, v) ->
        v < vp.v0 + EDGE * vp.valueSpan || v > vp.v1 - EDGE * vp.valueSpan || t < vp.t0 + EDGE * vp.timeSpan || t > vp.t1 - EDGE * vp.timeSpan }
    /** The window grown (never shrunk) so every point has [HEADROOM] of the span between it and the edges. */
    fun expand(vp: ValueViewport, points: List<Pair<Double, Double>>): ValueViewport {
        var r = vp
        for ((t, v) in points) {
            val sv = r.valueSpan * HEADROOM; val st = r.timeSpan * EDGE * 2
            r = ValueViewport(minOf(r.t0, t - st), maxOf(r.t1, t + st), minOf(r.v0, v - sv), maxOf(r.v1, v + sv))
        }
        return r
    }
    /**
     * GRAPH G4.3: after a library / type-strip / preset tap, the window to animate to: the segment-framed [home] when a
     * handle of the new curve lies outside [vp] or within [EDGE] of an edge, else null (the window does not move).
     */
    fun reframe(vp: ValueViewport, handles: List<Pair<Double, Double>>, home: ValueViewport?): ValueViewport? =
        if (home != null && handles.isNotEmpty() && nearEdge(vp, handles) && home != vp) home else null
    /** A dragged point clamped inside the (frozen) window. */
    fun clamp(vp: ValueViewport, t: Double, v: Double): Pair<Double, Double> = t.coerceIn(vp.t0, vp.t1) to v.coerceIn(vp.v0, vp.v1)
}

/**
 * GRAPH G3.1 §4: in the Speed view the handles cannot cross. With influences i0 (out) and i1 (in) they cross when
 * i0 + i1 > 1. A dragged handle stops a [gap] (fraction of the segment) before the other one. A curve that STARTS crossed
 * (e.g. the Back eases, x1 > x2) may only uncross: the dragged influence may not grow beyond its value at the drag's
 * start. Typed influences are clamped the same way (not discarded).
 */
internal object SpeedCross {
    const val GAP = .01
    /** The largest influence handle [h] may take, from the curve [start] at the beginning of the drag (or entry). */
    fun maxInfluence(start: FloatArray, h: Int, gap: Double = GAP): Double {
        val own = SpeedHandles.influence(start, h); val other = SpeedHandles.influence(start, 1 - h)
        val limit = if (own + other <= 1.0 + 1e-9) 1.0 - other - gap else own
        return limit.coerceIn(SpeedHandles.INFLUENCE_MIN.toDouble(), 1.0)
    }
}

/**
 * GRAPH G3.1 §5: magnetic landmarks for a Speed view handle drag (under the Snap toggle; typed values never snap). Speed
 * landmarks: 0, the average speed D/Δt, the other handle's speed and the neighbouring segment's speed at the same key. A
 * flat segment has none. Influence landmarks: 33.3%, 50% and the other handle's influence. Each locks within [speedZone] /
 * [influenceZone] (10dp on screen, converted by the caller), never more than 35% of the distance to the nearest other
 * landmark, and releases beyond it. Outside every zone the existing snaps apply (influence 5%, speed the grid step).
 * One instance per drag.
 */
internal class SpeedSnap(val speedLandmarks: List<Double>, val influenceLandmarks: List<Double>, speedZone: Double, influenceZone: Double,
    private val grid: Double, private val onLock: () -> Unit = {}) {
    private val speedMagnet = magnet(speedLandmarks, speedZone)
    private val influenceMagnet = magnet(influenceLandmarks, influenceZone)
    val lockedSpeed: Double? get() = speedMagnet?.locked?.toDouble()
    val lockedInfluence: Double? get() = influenceMagnet?.locked?.toDouble()
    fun speed(raw: Double): Double {
        val m = speedMagnet
        val out = m?.apply(raw.toFloat(), onLock)?.toDouble() ?: raw
        if (m?.locked != null) return m.locked!!.toDouble()
        return if (grid > 0) Math.round(out / grid) * grid else out
    }
    fun influence(raw: Double): Double {
        val m = influenceMagnet
        m?.apply(raw.toFloat(), onLock)
        if (m?.locked != null) return m.locked!!.toDouble()
        return Math.round(raw / .05) * .05
    }
    companion object {
        const val ZONE_DP = 10f
        const val MAX_ZONE_FRACTION = .35
        private fun magnet(points: List<Double>, zone: Double): Magnet? {
            val ls = points.filter { it.isFinite() }.distinctBy { Math.round(it * 1e6) }.sorted()
            if (ls.isEmpty()) return null
            fun zoneOf(l: Double): Double {
                val others = ls.filter { it != l }.map { abs(it - l) }
                return if (others.isEmpty()) zone else minOf(zone, MAX_ZONE_FRACTION * others.min())
            }
            val zones = ls.associateWith { zoneOf(it) }
            return Magnet({ v -> ls.minByOrNull { abs(it - v) }?.toFloat() }, { a, b -> abs(a - b) },
                { d -> zones.entries.minByOrNull { abs(it.key - d) }!!.value.toFloat() })
        }
        /** The landmarks of handle [h] of segment [index] (its curve [b]). */
        fun landmarks(segs: List<SpeedSegment>, index: Int, b: FloatArray, h: Int): Pair<List<Double>, List<Double>> {
            val seg = segs[index]
            val influence = listOf(1.0 / 3, .5, SpeedHandles.influence(b, 1 - h))
            if (seg.flat) return emptyList<Double>() to influence
            val speeds = mutableListOf(0.0, seg.rate, SpeedHandles.speed(seg, b, 1 - h))
            val nb = segs.getOrNull(if (h == 0) index - 1 else index + 1)
            nb?.bezier?.let { nbB -> if (!nb.flat) speeds += SpeedHandles.speed(nb, nbB, if (h == 0) 1 else 0) }
            return speeds to influence
        }
    }
}
