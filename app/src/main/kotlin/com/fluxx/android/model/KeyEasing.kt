package com.fluxx.android.model

import com.google.flatbuffers.FlatBufferBuilder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * GRAPH G1: the easing of one keyframe segment, owned by the departing key, with its four generic curve parameters.
 *
 * - CUSTOM: cubic-bezier control points (a, b, c, d) = (x1, y1, x2, y2) with P0 = (0, 0) and P3 = (1, 1).
 * - BOUNCE (ease-out): a = bounce count (1..8, rounded), b = restitution (velocity kept per bounce, 0.1..0.9).
 * - ELASTIC (ease-out): a = oscillations (1..10), b = damping (0.1..1).
 * - LINEAR, EASY_EASE, HOLD ignore the parameters (they are stored and round-trip unchanged).
 *
 * Kotlin enum order is not the wire order; see [EasingWire].
 */
data class EasingCurve(val type: EasingPreset, val a: Float = EasingLimits.defaultA(type), val b: Float = EasingLimits.defaultB(type),
    val c: Float = EasingLimits.defaultC(type), val d: Float = EasingLimits.defaultD(type)) {
    init { EasingLimits.validate(type, a, b, c, d) }

    companion object {
        val LINEAR = EasingCurve(EasingPreset.LINEAR)
        val EASY_EASE = EasingCurve(EasingPreset.EASY_EASE)
        val HOLD = EasingCurve(EasingPreset.HOLD)
        fun bezier(x1: Float, y1: Float, x2: Float, y2: Float) = EasingCurve(EasingPreset.CUSTOM, x1, y1, x2, y2)
        fun bounce(count: Float, restitution: Float) = EasingCurve(EasingPreset.BOUNCE, count, restitution, 0f, 0f)
        fun elastic(oscillations: Float, damping: Float) = EasingCurve(EasingPreset.ELASTIC, oscillations, damping, 0f, 0f)
    }

    /** The equivalent cubic-bezier of the named bezier types (Linear, Easy Ease, Custom); null for Hold, Bounce, Elastic. */
    fun bezierOrNull(): FloatArray? = when (type) {
        EasingPreset.LINEAR -> floatArrayOf(0f, 0f, 1f, 1f)
        EasingPreset.EASY_EASE -> floatArrayOf(.33f, 0f, .67f, 1f)
        EasingPreset.CUSTOM -> floatArrayOf(a, b, c, d)
        else -> null
    }

    /** Eased progress for [p] in [0, 1] (unclamped output: Custom and Elastic may overshoot). */
    fun ease(p: Float): Float = KeyframeEvaluator.ease(p, type, a, b, c, d)
}

/** Parameter ranges, defaults and validation shared by the data classes, the codec and the editor. */
object EasingLimits {
    const val BEZIER_X_MIN = 0f; const val BEZIER_X_MAX = 1f
    const val BEZIER_Y_MIN = -2f; const val BEZIER_Y_MAX = 3f
    const val BOUNCE_COUNT_MIN = 1f; const val BOUNCE_COUNT_MAX = 8f
    const val BOUNCE_RESTITUTION_MIN = .1f; const val BOUNCE_RESTITUTION_MAX = .9f
    const val ELASTIC_OSCILLATIONS_MIN = 1f; const val ELASTIC_OSCILLATIONS_MAX = 10f
    const val ELASTIC_DAMPING_MIN = .1f; const val ELASTIC_DAMPING_MAX = 1f
    /** Wire/storage defaults of the four floats (the Easy Ease bezier), used by every type that ignores them. */
    const val DEFAULT_A = .33f; const val DEFAULT_B = 0f; const val DEFAULT_C = .67f; const val DEFAULT_D = 1f
    const val BOUNCE_DEFAULT_COUNT = 3f; const val BOUNCE_DEFAULT_RESTITUTION = .5f
    const val ELASTIC_DEFAULT_OSCILLATIONS = 3f; const val ELASTIC_DEFAULT_DAMPING = .5f

    fun defaultA(type: EasingPreset) = when (type) { EasingPreset.BOUNCE -> BOUNCE_DEFAULT_COUNT; EasingPreset.ELASTIC -> ELASTIC_DEFAULT_OSCILLATIONS; else -> DEFAULT_A }
    fun defaultB(type: EasingPreset) = when (type) { EasingPreset.BOUNCE -> BOUNCE_DEFAULT_RESTITUTION; EasingPreset.ELASTIC -> ELASTIC_DEFAULT_DAMPING; else -> DEFAULT_B }
    fun defaultC(type: EasingPreset) = when (type) { EasingPreset.BOUNCE, EasingPreset.ELASTIC -> 0f; else -> DEFAULT_C }
    fun defaultD(type: EasingPreset) = when (type) { EasingPreset.BOUNCE, EasingPreset.ELASTIC -> 0f; else -> DEFAULT_D }

    fun valid(type: EasingPreset, a: Float, b: Float, c: Float, d: Float): Boolean {
        if (!(a.isFinite() && b.isFinite() && c.isFinite() && d.isFinite())) return false
        return when (type) {
            EasingPreset.CUSTOM -> a in BEZIER_X_MIN..BEZIER_X_MAX && c in BEZIER_X_MIN..BEZIER_X_MAX &&
                b in BEZIER_Y_MIN..BEZIER_Y_MAX && d in BEZIER_Y_MIN..BEZIER_Y_MAX
            EasingPreset.BOUNCE -> a in BOUNCE_COUNT_MIN..BOUNCE_COUNT_MAX && b in BOUNCE_RESTITUTION_MIN..BOUNCE_RESTITUTION_MAX
            EasingPreset.ELASTIC -> a in ELASTIC_OSCILLATIONS_MIN..ELASTIC_OSCILLATIONS_MAX && b in ELASTIC_DAMPING_MIN..ELASTIC_DAMPING_MAX
            EasingPreset.LINEAR, EasingPreset.EASY_EASE, EasingPreset.HOLD -> true
        }
    }
    fun validate(type: EasingPreset, a: Float, b: Float, c: Float, d: Float) {
        require(valid(type, a, b, c, d)) { "Invalid ${type.name} easing parameters ($a, $b, $c, $d)" }
    }

    /**
     * Decoder sanitising (the `TextLimits.decodeSize` approach): non-finite values are corrupt data and are refused;
     * finite out-of-range values are clamped into the type's range. Types that ignore the floats keep them unchanged.
     */
    fun sanitize(type: EasingPreset, a: Float, b: Float, c: Float, d: Float): FloatArray {
        require(a.isFinite() && b.isFinite() && c.isFinite() && d.isFinite()) { "Nonfinite easing parameters" }
        return when (type) {
            EasingPreset.CUSTOM -> floatArrayOf(a.coerceIn(BEZIER_X_MIN, BEZIER_X_MAX), b.coerceIn(BEZIER_Y_MIN, BEZIER_Y_MAX),
                c.coerceIn(BEZIER_X_MIN, BEZIER_X_MAX), d.coerceIn(BEZIER_Y_MIN, BEZIER_Y_MAX))
            EasingPreset.BOUNCE -> floatArrayOf(a.coerceIn(BOUNCE_COUNT_MIN, BOUNCE_COUNT_MAX), b.coerceIn(BOUNCE_RESTITUTION_MIN, BOUNCE_RESTITUTION_MAX), c, d)
            EasingPreset.ELASTIC -> floatArrayOf(a.coerceIn(ELASTIC_OSCILLATIONS_MIN, ELASTIC_OSCILLATIONS_MAX), b.coerceIn(ELASTIC_DAMPING_MIN, ELASTIC_DAMPING_MAX), c, d)
            else -> floatArrayOf(a, b, c, d)
        }
    }
}

/**
 * Closed-form, allocation-free, stateless easing maths (thread-safe: UI and render threads both call it).
 * Every function returns exactly 0 at progress <= 0 and exactly 1 at progress >= 1.
 */
object EasingMath {
    /**
     * Cubic-bezier timing: solve x(s) = [p] (Newton-Raphson, then bisection when Newton leaves [0, 1] or does not
     * converge, e.g. for extreme handles), return y(s). The output is not clamped (overshoot/undershoot allowed).
     * For x1, x2 in [0, 1] x(s) is monotonic, so the solution is unique. Double precision throughout.
     */
    fun bezier(p: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        if (p <= 0f) return 0f
        if (p >= 1f) return 1f
        val t = p.toDouble()
        val cx = 3.0 * x1; val bx = 3.0 * (x2 - x1) - cx; val ax = 1.0 - cx - bx
        val cy = 3.0 * y1; val by = 3.0 * (y2 - y1) - cy; val ay = 1.0 - cy - by
        var s = t
        var solved = false
        for (i in 0 until 8) {
            val x = ((ax * s + bx) * s + cx) * s - t
            if (abs(x) < 1e-9) { solved = true; break }
            val dx = (3.0 * ax * s + 2.0 * bx) * s + cx
            if (abs(dx) < 1e-9) break
            s -= x / dx
            if (s < 0.0 || s > 1.0) break
        }
        if (!solved || s < 0.0 || s > 1.0) {
            var lo = 0.0; var hi = 1.0
            s = t
            for (i in 0 until 60) {
                s = (lo + hi) * .5
                val x = ((ax * s + bx) * s + cx) * s
                if (abs(x - t) < 1e-12) break
                if (x < t) lo = s else hi = s
            }
        }
        return (((ay * s + by) * s + cy) * s).toFloat()
    }

    /**
     * Bounce (ease-out), a ball dropped from 0 onto the target 1 with velocity restitution e and n bounces:
     * the first fall takes 1 time unit (p = τ²), bounce k (k = 1..n) lasts 2eᵏ units and reaches height e²ᵏ below the
     * target: p = 1 − e²ᵏ(1 − u²), u = (τ − startₖ − eᵏ)/eᵏ ∈ [−1, 1]. Total D = 1 + 2·Σ eᵏ and τ = progress·D.
     * Continuous (each arc starts and ends on the target), never above 1.
     */
    fun bounce(p: Float, count: Float, restitution: Float): Float {
        if (p <= 0f) return 0f
        if (p >= 1f) return 1f
        val n = count.roundToInt().coerceIn(1, 8)
        val e = restitution.toDouble()
        var total = 1.0; var ek = 1.0
        for (k in 1..n) { ek *= e; total += 2.0 * ek }
        val tau = p * total
        if (tau <= 1.0) return (tau * tau).toFloat()
        var start = 1.0; ek = 1.0
        for (k in 1..n) {
            ek *= e
            val length = 2.0 * ek
            if (tau <= start + length || k == n) {
                val u = ((tau - start - ek) / ek).coerceIn(-1.0, 1.0)
                return (1.0 - ek * ek * (1.0 - u * u)).coerceAtMost(1.0).toFloat()
            }
            start += length
        }
        return 1f
    }

    /**
     * Elastic (ease-out): p = 1 − (1 − t)·e^(−λt)·cos(2π·n·t), λ = 10·damping, n = oscillations.
     * p(0) = 0, the envelope (1 − t)·e^(−λt) decays to exactly 0 at t = 1, so p(1) = 1; between, p oscillates around 1
     * within [0, 2) (overshoot and undershoot of the target).
     */
    fun elastic(p: Float, oscillations: Float, damping: Float): Float {
        if (p <= 0f) return 0f
        if (p >= 1f) return 1f
        val t = p.toDouble()
        val envelope = (1.0 - t) * exp(-10.0 * damping * t)
        return (1.0 - envelope * cos(2.0 * PI * oscillations * t)).toFloat()
    }

    /** Lowest and highest eased value of a curve (for the Ease view's y range), sampled. */
    fun extent(curve: EasingCurve, samples: Int = 256): FloatArray {
        var lo = 0f; var hi = 1f
        for (i in 0..samples) { val v = curve.ease(i / samples.toFloat()); if (v < lo) lo = v; if (v > hi) hi = v }
        return floatArrayOf(lo, hi)
    }
}

/**
 * FlatBuffers wire mapping for easing (schema v13). Wire values are explicit and independent of Kotlin enum order:
 * LINEAR = 0, EASY_EASE = 1, HOLD = 2, CUSTOM = 3, BOUNCE = 4, ELASTIC = 5. The four curve floats are written only
 * when they differ from the schema defaults (0.33, 0, 0.67, 1), so keys that do not use them cost nothing.
 */
internal object EasingWire {
    fun byte(e: EasingPreset): Byte = when (e) {
        EasingPreset.LINEAR -> 0; EasingPreset.EASY_EASE -> 1; EasingPreset.HOLD -> 2
        EasingPreset.CUSTOM -> 3; EasingPreset.BOUNCE -> 4; EasingPreset.ELASTIC -> 5
    }

    /**
     * v1–v12 are read exactly as before: transform tracks map any non-zero byte to Easy Ease (their historic leniency),
     * text and animator tracks accept only 0/1. From v13 the six wire values are accepted and any other byte is
     * corrupt data and refused, as the text decoder already refuses unknown easing bytes.
     */
    fun decode(value: Byte, version: Int, lenientLegacy: Boolean): EasingPreset {
        val v = value.toInt()
        if (version < 13) {
            if (lenientLegacy) return if (v == 0) EasingPreset.LINEAR else EasingPreset.EASY_EASE
            require(v in 0..1) { "Unsupported easing $v" }
            return if (v == 0) EasingPreset.LINEAR else EasingPreset.EASY_EASE
        }
        return when (v) {
            0 -> EasingPreset.LINEAR; 1 -> EasingPreset.EASY_EASE; 2 -> EasingPreset.HOLD
            3 -> EasingPreset.CUSTOM; 4 -> EasingPreset.BOUNCE; 5 -> EasingPreset.ELASTIC
            else -> throw IllegalArgumentException("Unsupported easing $v")
        }
    }

    /** Writes a float field only when it differs (as a Float, sign of zero included) from its schema default. */
    private fun curve(b: FlatBufferBuilder, slot: Int, a: Float, bb: Float, c: Float, d: Float) {
        // NaN as the builder's "default" never equals x, so a field we chose to write is always written.
        if (!a.equals(EasingLimits.DEFAULT_A)) b.addFloat(slot, a, Double.NaN)
        if (!bb.equals(EasingLimits.DEFAULT_B)) b.addFloat(slot + 1, bb, Double.NaN)
        if (!c.equals(EasingLimits.DEFAULT_C)) b.addFloat(slot + 2, c, Double.NaN)
        if (!d.equals(EasingLimits.DEFAULT_D)) b.addFloat(slot + 3, d, Double.NaN)
    }

    fun key1D(b: FlatBufferBuilder, k: Keyframe1D): Int {
        fluxx.schema.Keyframe1D.startKeyframe1D(b)
        fluxx.schema.Keyframe1D.addTimeUs(b, k.timeUs)
        fluxx.schema.Keyframe1D.addValue(b, k.value)
        fluxx.schema.Keyframe1D.addEasing(b, byte(k.easing))
        curve(b, 3, k.curveA, k.curveB, k.curveC, k.curveD)
        return fluxx.schema.Keyframe1D.endKeyframe1D(b)
    }
    fun key2D(b: FlatBufferBuilder, k: Keyframe2D): Int {
        fluxx.schema.Keyframe2D.startKeyframe2D(b)
        fluxx.schema.Keyframe2D.addTimeUs(b, k.timeUs)
        fluxx.schema.Keyframe2D.addX(b, k.x); fluxx.schema.Keyframe2D.addY(b, k.y)
        fluxx.schema.Keyframe2D.addEasing(b, byte(k.easing))
        curve(b, 4, k.curveA, k.curveB, k.curveC, k.curveD)
        return fluxx.schema.Keyframe2D.endKeyframe2D(b)
    }
    fun colourKey(b: FlatBufferBuilder, k: ColourKeyframe): Int {
        fluxx.schema.ColourKeyframe.startColourKeyframe(b)
        fluxx.schema.ColourKeyframe.addTimeUs(b, k.timeUs)
        fluxx.schema.ColourKeyframe.addArgb(b, k.argb)
        fluxx.schema.ColourKeyframe.addEasing(b, byte(k.easing))
        curve(b, 3, k.curveA, k.curveB, k.curveC, k.curveD)
        return fluxx.schema.ColourKeyframe.endColourKeyframe(b)
    }

    fun key1D(k: fluxx.schema.Keyframe1D, version: Int, lenient: Boolean, value: Float = k.value): Keyframe1D {
        val e = decode(k.easing, version, lenient)
        val c = EasingLimits.sanitize(e, k.curveA, k.curveB, k.curveC, k.curveD)
        return Keyframe1D(k.timeUs, value, e, c[0], c[1], c[2], c[3])
    }
    fun key2D(k: fluxx.schema.Keyframe2D, version: Int, lenient: Boolean): Keyframe2D {
        val e = decode(k.easing, version, lenient)
        val c = EasingLimits.sanitize(e, k.curveA, k.curveB, k.curveC, k.curveD)
        return Keyframe2D(k.timeUs, k.x, k.y, e, c[0], c[1], c[2], c[3])
    }
    fun colourKey(k: fluxx.schema.ColourKeyframe, version: Int): ColourKeyframe {
        val e = decode(k.easing, version, false)
        val c = EasingLimits.sanitize(e, k.curveA, k.curveB, k.curveC, k.curveD)
        return ColourKeyframe(k.timeUs, k.argb, e, c[0], c[1], c[2], c[3])
    }
}

/**
 * GRAPH G1: replacing a key's value keeps its segment curve. When [existing] (the key already at that time) has the
 * same easing type, its curve parameters are kept; a new key or a changed type gets the type's defaults. Every edit path
 * that re-keys a value (inspector edits, auto-key, animator SET) uses these, so none resets a Custom curve.
 */
fun rekey1D(existing: Keyframe1D?, time: Long, value: Float, easing: EasingPreset): Keyframe1D =
    if (existing != null && existing.easing == easing) existing.copy(timeUs = time, value = value) else Keyframe1D(time, value, easing)
fun rekey2D(existing: Keyframe2D?, time: Long, x: Float, y: Float, easing: EasingPreset): Keyframe2D =
    if (existing != null && existing.easing == easing) existing.copy(timeUs = time, x = x, y = y) else Keyframe2D(time, x, y, easing)
fun rekeyColour(existing: ColourKeyframe?, time: Long, argb: Int, easing: EasingPreset): ColourKeyframe =
    if (existing != null && existing.easing == easing) existing.copy(timeUs = time, argb = argb) else ColourKeyframe(time, argb, easing)
