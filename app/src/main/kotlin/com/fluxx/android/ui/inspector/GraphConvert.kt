package com.fluxx.android.ui.inspector

import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * GRAPH G4.1: "Convert to keys" for a Bounce or Elastic segment (pure, JVM tested).
 *
 * The segment k0 → k1 is replaced by plain keys whose segments are cubic beziers (Custom easing), written with EXISTING
 * actions only, in ONE [EditorAction.Batch] (one undo): [EditorAction.SetKeyframe] / [EditorAction.SetKeyframe2D] (or the
 * animator's Key SET) for each new key, then [EditorAction.SetKeyEasing] for every departing key of the converted range
 * (k0 included). k0 and k1 keep their times and values; only k0's curve changes (Bounce/Elastic → Custom).
 *
 * - **Bounce** ([EasingMath.bounce]) is piecewise quadratic in progress: the first fall p = τ² and each bounce arc
 *   p = 1 − e²ᵏ(1 − u²). On a µs time grid (transform tracks) it converts EXACTLY: one key per impact and apex, each piece
 *   a quadratic written as a cubic bezier: ease-in (1/3, 0, 2/3, 1/3) from an apex (or the start) down to an impact,
 *   ease-out (1/3, 2/3, 2/3, 1) from an impact up to an apex. Arcs too short to get distinct µs keys are dropped (their
 *   height is e²ᵏ, below 1e-6 of the travel).
 * - **Elastic** ([EasingMath.elastic]) is not polynomial: keys at its peaks and troughs, each piece fitted (Nelder–Mead on the
 *   four bezier numbers, minimising the max error), and a piece whose error is above [SPLIT] of the travel is split at its
 *   worst sample until it fits (so the result stays below [TARGET]).
 * - Text-layer and animator keys are frame-snapped by their actions. On that grid Bounce cannot be exact either, so both
 *   types use the fitted path there, with the keys on frames and the error measured on frames (where those tracks are
 *   evaluated: their pages read the frame-snapped playhead).
 *
 * Colour (and any property without a Value graph) is not converted: colour keys are 8-bit ARGB, so no key set can match.
 */
internal object GraphConvert {
    /** Max error target (fraction of the segment's travel) and the per-piece split threshold. */
    const val TARGET = .01
    const val SPLIT = .004
    private const val SAMPLES = 48
    private const val MAX_DEPTH = 8

    val EASE_IN = EasingCurve.bezier(1f / 3f, 0f, 2f / 3f, 1f / 3f)
    val EASE_OUT = EasingCurve.bezier(1f / 3f, 2f / 3f, 2f / 3f, 1f)

    /** One node of the converted range: local time, eased progress (0 at k0, 1 at k1), the curve departing it (null at k1). */
    data class Node(val timeUs: Long, val progress: Double, val curve: EasingCurve?)

    fun applies(curve: EasingCurve) = curve.type == EasingPreset.BOUNCE || curve.type == EasingPreset.ELASTIC

    /** Convert to keys is enabled: a Bounce / Elastic segment of a property with a Value graph (not colour). */
    fun enabled(layer: CompositionLayer, target: GraphTarget, segment: GraphSegment?) =
        segment != null && applies(segment.curve) && GraphValues.channels(layer, target) > 0 && segment.durationUs > 1

    /** Text and animator keys are frame-snapped by their actions; transform keys are free µs times. */
    fun frameGrid(target: GraphTarget) = target.animator != null || target.property!!.ordinal > AnimPropertyType.OPACITY.ordinal

    // ---- The curves (the evaluator's own formulas, in double) ----------------------------------------------------

    private fun ease(curve: EasingCurve, u: Double): Double = when (curve.type) {
        EasingPreset.ELASTIC -> if (u <= 0.0) 0.0 else if (u >= 1.0) 1.0 else
            1.0 - (1.0 - u) * exp(-10.0 * curve.b * u) * cos(2.0 * PI * curve.a * u)
        else -> curve.ease(u.toFloat()).toDouble()
    }

    /** Elastic's turning points in (0, 1): sign changes of p'(u), refined by bisection. */
    internal fun elasticExtrema(curve: EasingCurve): List<Double> {
        val lambda = 10.0 * curve.b; val w = 2.0 * PI * curve.a
        fun d(u: Double): Double {
            val e = exp(-lambda * u); val g = (1.0 - u) * e; val gp = e * (-1.0 - lambda * (1.0 - u))
            return -gp * cos(w * u) + g * w * sin(w * u)
        }
        val n = 4000; val out = ArrayList<Double>()
        var u0 = 0.0; var d0 = d(1e-9)
        for (i in 1..n) {
            val u1 = i / n.toDouble(); val d1 = d(if (i == n) 1 - 1e-9 else u1)
            if (d0 == 0.0 || d0 * d1 < 0) {
                var lo = u0; var hi = u1; var dl = d0
                repeat(60) { val m = (lo + hi) / 2; val dm = d(m); if (dl * dm <= 0) hi = m else { lo = m; dl = dm } }
                out += (lo + hi) / 2
            }
            u0 = u1; d0 = d1
        }
        return out.filter { it > 1e-6 && it < 1 - 1e-6 }
    }

    /** The exact Bounce pieces in normalised time: (u, progress, departing curve), start to end. */
    internal fun bouncePieces(curve: EasingCurve): List<Triple<Double, Double, EasingCurve?>> {
        val n = curve.a.roundToInt().coerceIn(1, 8); val e = curve.b.toDouble()
        var total = 1.0; var ek = 1.0
        for (k in 1..n) { ek *= e; total += 2.0 * ek }
        val out = ArrayList<Triple<Double, Double, EasingCurve?>>()
        out += Triple(0.0, 0.0, EASE_IN)
        var start = 1.0; ek = 1.0
        out += Triple(start / total, 1.0, EASE_OUT)
        for (k in 1..n) {
            ek *= e
            out += Triple((start + ek) / total, 1.0 - ek * ek, EASE_IN)
            start += 2.0 * ek
            out += Triple(if (k == n) 1.0 else start / total, 1.0, if (k == n) null else EASE_OUT)
        }
        return out
    }

    // ---- The plan --------------------------------------------------------------------------------------------------

    /** A frame grid for text/animator keys: [quantise] is the time a key would get, [between] the frame times strictly inside. */
    class Grid(val quantise: (Long) -> Long, val between: (Long, Long) -> List<Long>)

    /** The nodes replacing the segment [startUs] → [endUs] with [curve]; [grid] null = free µs key times (transform tracks). */
    fun plan(curve: EasingCurve, startUs: Long, endUs: Long, grid: Grid? = null): List<Node> {
        require(applies(curve) && endUs > startUs)
        val d = (endUs - startUs).toDouble()
        fun at(u: Double) = startUs + Math.round(u * d)
        if (curve.type == EasingPreset.BOUNCE && grid == null) {
            // Exact. A piece whose µs time does not come strictly after the previous key is dropped: only arcs a few µs
            // long can collide; their height (e²ᵏ) is negligible and their neighbours sit on or next to the target.
            val pieces = bouncePieces(curve)
            val nodes = ArrayList<Node>()
            for ((i, piece) in pieces.withIndex()) {
                if (i == pieces.lastIndex) { nodes += Node(endUs, 1.0, null); break }
                val t = if (i == 0) startUs else at(piece.first)
                if (i > 0 && (t <= nodes.last().timeUs || t >= endUs)) continue
                nodes += Node(t, piece.second, piece.third)
            }
            return nodes
        }
        // Fitted path: candidate keys at the curve's features (quantised to the grid), then adaptive splitting.
        val features = if (curve.type == EasingPreset.BOUNCE) bouncePieces(curve).map { it.first }.drop(1).dropLast(1) else elasticExtrema(curve)
        val times = sortedSetOf(startUs, endUs)
        for (u in features) { val t = grid?.quantise?.invoke(at(u)) ?: at(u); if (t in (startUs + 1) until endUs) times += t }
        fun p(t: Long) = ease(curve, (t - startUs) / d)
        fun samples(a: Long, b: Long): List<Long> = grid?.between?.invoke(a, b)
            ?: (1 until SAMPLES).map { a + Math.round((b - a) * it / SAMPLES.toDouble()) }.filter { it in (a + 1) until b }.distinct()
        val nodes = ArrayList<Node>()
        fun piece(a: Long, b: Long, depth: Int) {
            val pa = p(a); val pb = p(b); val span = pb - pa
            val pts = samples(a, b)
            val us = DoubleArray(pts.size) { (pts[it] - a).toDouble() / (b - a) }
            val qs = DoubleArray(pts.size) { if (abs(span) < 1e-12) 0.0 else (p(pts[it]) - pa) / span }
            val fit = fit(us, qs)
            var worst = -1; var err = 0.0
            for (i in pts.indices) { val e = abs(EasingMath.bezier(us[i].toFloat(), fit[0], fit[1], fit[2], fit[3]) - qs[i]) * abs(span); if (e > err) { err = e; worst = i } }
            if (err > SPLIT && depth < MAX_DEPTH && worst >= 0) {
                val m = pts[worst]
                if (m > a && m < b) { piece(a, m, depth + 1); piece(m, b, depth + 1); return }
            }
            nodes += Node(a, pa, EasingCurve.bezier(fit[0], fit[1], fit[2], fit[3]))
        }
        val list = times.toList()
        for (i in 0 until list.lastIndex) piece(list[i], list[i + 1], 0)
        nodes += Node(endUs, 1.0, null)
        return nodes
    }

    /** Nelder–Mead on (x1, y1, x2, y2), minimising the max error of bezier(u) against q; x clamped to [0, 1], y to [−2, 3]. */
    internal fun fit(us: DoubleArray, qs: DoubleArray): FloatArray {
        if (us.isEmpty()) return floatArrayOf(1f / 3f, 1f / 3f, 2f / 3f, 2f / 3f)
        fun clampP(v: DoubleArray) = doubleArrayOf(v[0].coerceIn(0.0, 1.0), v[1].coerceIn(-2.0, 3.0), v[2].coerceIn(0.0, 1.0), v[3].coerceIn(-2.0, 3.0))
        fun cost(v: DoubleArray): Double {
            val c = clampP(v); var m = 0.0; var s = 0.0
            for (i in us.indices) {
                val e = abs(EasingMath.bezier(us[i].toFloat(), c[0].toFloat(), c[1].toFloat(), c[2].toFloat(), c[3].toFloat()) - qs[i])
                if (e > m) m = e; s += e * e
            }
            return m + 1e-3 * s / us.size
        }
        var best: DoubleArray? = null; var bestCost = Double.MAX_VALUE
        val starts = listOf(doubleArrayOf(1 / 3.0, 1 / 3.0, 2 / 3.0, 2 / 3.0), doubleArrayOf(.4, 0.0, .6, 1.0),
            doubleArrayOf(.2, .5, .5, 1.0), doubleArrayOf(.5, 0.0, .8, .5))
        for (s0 in starts) {
            val r = nelderMead(s0, ::cost)
            val c = cost(r)
            if (c < bestCost) { bestCost = c; best = r }
        }
        val b = clampP(best!!)
        return floatArrayOf(b[0].toFloat(), b[1].toFloat(), b[2].toFloat(), b[3].toFloat())
    }

    private fun nelderMead(x0: DoubleArray, f: (DoubleArray) -> Double, iterations: Int = 400): DoubleArray {
        val n = x0.size
        val simplex = Array(n + 1) { i -> x0.copyOf().also { if (i > 0) it[i - 1] += .15 } }
        val values = DoubleArray(n + 1) { f(simplex[it]) }
        repeat(iterations) {
            val order = (0..n).sortedBy { values[it] }
            val s = order.map { simplex[it] }; val v = order.map { values[it] }
            for (i in 0..n) { simplex[i] = s[i]; values[i] = v[i] }
            if (values[n] - values[0] < 1e-10) return simplex[0]
            val c = DoubleArray(n) { j -> (0 until n).sumOf { simplex[it][j] } / n }
            fun along(t: Double) = DoubleArray(n) { j -> c[j] + t * (simplex[n][j] - c[j]) }
            val r = along(-1.0); val fr = f(r)
            when {
                fr < values[0] -> { val e = along(-2.0); val fe = f(e); if (fe < fr) { simplex[n] = e; values[n] = fe } else { simplex[n] = r; values[n] = fr } }
                fr < values[n - 1] -> { simplex[n] = r; values[n] = fr }
                else -> {
                    val k = if (fr < values[n]) along(-.5) else along(.5); val fk = f(k)
                    if (fk < minOf(fr, values[n])) { simplex[n] = k; values[n] = fk } else {
                        for (i in 1..n) { simplex[i] = DoubleArray(n) { j -> simplex[0][j] + .5 * (simplex[i][j] - simplex[0][j]) }; values[i] = f(simplex[i]) }
                    }
                }
            }
        }
        return simplex[(0..n).minBy { values[it] }]
    }

    // ---- The action ------------------------------------------------------------------------------------------------

    /**
     * The one-undo Batch converting [segment] of [target] on [layer] (both axes of an X/Y property, the same key times),
     * or null when it does not apply. New keys use the existing key-setting actions with Custom easing, then every
     * departing key of the range gets its curve through [EditorAction.SetKeyEasing].
     */
    fun action(layer: CompositionLayer, target: GraphTarget, segment: GraphSegment, rate: FrameRate): EditorAction? {
        if (!enabled(layer, target, segment)) return null
        val keys = GraphValues.keys(layer, target)
        val k0 = keys.firstOrNull { it.timeUs == segment.startUs } ?: return null
        val k1 = keys.firstOrNull { it.timeUs == segment.endUs } ?: return null
        val grid = if (!frameGrid(target)) null else Grid(
            quantise = { t -> GraphValues.timeOf(layer, rate, GraphValues.frame(layer, rate, t)) },
            between = { a, b -> ((GraphValues.frame(layer, rate, a) + 1) until GraphValues.frame(layer, rate, b))
                .map { GraphValues.timeOf(layer, rate, it) }.filter { it > a && it < b } })
        val nodes = plan(segment.curve, segment.startUs, segment.endUs, grid)
        val two = GraphValues.channels(layer, target) == 2
        val id = layer.id
        val list = ArrayList<EditorAction>()
        for (node in nodes) {
            if (node.timeUs == segment.startUs || node.timeUs == segment.endUs) continue
            val x = (k0.x + (k1.x - k0.x) * node.progress).toFloat()
            val y = (k0.y + (k1.y - k0.y) * node.progress).toFloat()
            list += target.animator?.let { a ->
                EditorAction.EditTextAnimator(id, AnimatorEdit.Key(a, AnimatorKeyOperation.SET, node.timeUs, value = x, y = if (two) y else null, easing = EasingPreset.CUSTOM))
            } ?: if (two) EditorAction.SetKeyframe2D(id, target.property!!, node.timeUs, x, y, EasingPreset.CUSTOM)
                else EditorAction.SetKeyframe(id, target.property!!, node.timeUs, x, EasingPreset.CUSTOM)
        }
        for (node in nodes) node.curve?.let { list += GraphModel.action(id, target, listOf(node.timeUs), it) }
        return EditorAction.Batch(list)
    }
}
