package com.fluxx.android.editor

import com.fluxx.android.model.EasingCurve
import com.fluxx.android.model.EasingLimits
import com.fluxx.android.model.EasingPreset
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * GRAPH G1 built-in curve presets. App data (code constants), never stored by id in a project: applying one copies its
 * four floats into the key as CUSTOM. Values come only from published standards: the CSS named timing functions, the
 * widely published cubic-bezier fits of Robert Penner's equations, and Material Design's easing curves.
 */
data class EasePreset(val id: String, val name: String, val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    val curve get() = EasingCurve.bezier(x1, y1, x2, y2)
}

object EasePresets {
    val ALL: List<EasePreset> = listOf(
        // CSS Easing Functions Level 1 named keywords.
        EasePreset("css.ease", "Ease", .25f, .1f, .25f, 1f),
        // CSS ease-in / ease-out are the type tiles Ease In / Ease Out (EaseChoice), so they are not repeated here.
        EasePreset("css.ease_in_out", "Ease in-out", .42f, 0f, .58f, 1f),
        // Penner equations, published cubic-bezier approximations.
        EasePreset("sine.in", "Sine in", .12f, 0f, .39f, 0f), EasePreset("sine.out", "Sine out", .61f, 1f, .88f, 1f),
        EasePreset("sine.in_out", "Sine in-out", .37f, 0f, .63f, 1f),
        EasePreset("quad.in", "Quad in", .11f, 0f, .5f, 0f), EasePreset("quad.out", "Quad out", .5f, 1f, .89f, 1f),
        EasePreset("quad.in_out", "Quad in-out", .45f, 0f, .55f, 1f),
        EasePreset("cubic.in", "Cubic in", .32f, 0f, .67f, 0f), EasePreset("cubic.out", "Cubic out", .33f, 1f, .68f, 1f),
        EasePreset("cubic.in_out", "Cubic in-out", .65f, 0f, .35f, 1f),
        EasePreset("quart.in", "Quart in", .5f, 0f, .75f, 0f), EasePreset("quart.out", "Quart out", .25f, 1f, .5f, 1f),
        EasePreset("quart.in_out", "Quart in-out", .76f, 0f, .24f, 1f),
        EasePreset("quint.in", "Quint in", .64f, 0f, .78f, 0f), EasePreset("quint.out", "Quint out", .22f, 1f, .36f, 1f),
        EasePreset("quint.in_out", "Quint in-out", .83f, 0f, .17f, 1f),
        EasePreset("expo.in", "Expo in", .7f, 0f, .84f, 0f), EasePreset("expo.out", "Expo out", .16f, 1f, .3f, 1f),
        EasePreset("expo.in_out", "Expo in-out", .87f, 0f, .13f, 1f),
        EasePreset("circ.in", "Circ in", .55f, 0f, 1f, .45f), EasePreset("circ.out", "Circ out", 0f, .55f, .45f, 1f),
        EasePreset("circ.in_out", "Circ in-out", .85f, 0f, .15f, 1f),
        // Penner "back": overshoot / undershoot.
        EasePreset("back.in", "Back in", .36f, 0f, .66f, -.56f), EasePreset("back.out", "Back out", .34f, 1.56f, .64f, 1f),
        EasePreset("back.in_out", "Back in-out", .68f, -.6f, .32f, 1.6f),
        // Material Design easing.
        EasePreset("material.standard", "Material standard", .4f, 0f, .2f, 1f),
        EasePreset("material.decelerate", "Material decelerate", 0f, 0f, .2f, 1f),
        EasePreset("material.accelerate", "Material accelerate", .4f, 0f, 1f, 1f),
        EasePreset("material.emphasized", "Material emphasized", .2f, 0f, 0f, 1f),
    )
    const val MATCH_EPSILON = .005f

    /** The preset whose four floats all match [curve] within [MATCH_EPSILON] (only for bezier-equivalent curves). */
    fun match(curve: EasingCurve): EasePreset? {
        if (curve.type != EasingPreset.CUSTOM) return null
        return ALL.firstOrNull { close(curve.a, it.x1) && close(curve.b, it.y1) && close(curve.c, it.x2) && close(curve.d, it.y2) }
    }
    fun close(a: Float, b: Float) = abs(a - b) <= MATCH_EPSILON
}

/** The type chooser. Ease In / Ease Out are named CUSTOM presets (the CSS values). */
enum class EaseChoice(val label: String) {
    HOLD("Hold"), LINEAR("Linear"), EASY_EASE("Easy Ease"), EASE_IN("Ease In"), EASE_OUT("Ease Out"),
    CUSTOM("Custom"), BOUNCE("Bounce"), ELASTIC("Elastic");

    companion object {
        val EASE_IN_CURVE = EasingCurve.bezier(.42f, 0f, 1f, 1f)
        val EASE_OUT_CURVE = EasingCurve.bezier(0f, 0f, .58f, 1f)
        /** Hold, Bounce and Elastic have no bezier equivalent; Custom starts from the Easy Ease bezier for them. */
        val CUSTOM_DEFAULT = EasingCurve.bezier(.33f, 0f, .67f, 1f)

        /** Which choice is selected for [curve] (Ease In / Out when a Custom curve equals their values). */
        fun of(curve: EasingCurve): EaseChoice = when (curve.type) {
            EasingPreset.HOLD -> HOLD; EasingPreset.LINEAR -> LINEAR; EasingPreset.EASY_EASE -> EASY_EASE
            EasingPreset.BOUNCE -> BOUNCE; EasingPreset.ELASTIC -> ELASTIC
            EasingPreset.CUSTOM -> when {
                EaseMath.same(curve, EASE_IN_CURVE) -> EASE_IN
                EaseMath.same(curve, EASE_OUT_CURVE) -> EASE_OUT
                else -> CUSTOM
            }
        }

        /** The curve a tap on [choice] writes, starting from [current]. */
        fun curve(choice: EaseChoice, current: EasingCurve): EasingCurve = when (choice) {
            HOLD -> EasingCurve.HOLD
            LINEAR -> EasingCurve.LINEAR
            EASY_EASE -> EasingCurve.EASY_EASE
            EASE_IN -> EASE_IN_CURVE
            EASE_OUT -> EASE_OUT_CURVE
            CUSTOM -> if (current.type == EasingPreset.CUSTOM) current
                else current.bezierOrNull()?.let { EasingCurve.bezier(it[0], it[1], it[2], it[3]) } ?: CUSTOM_DEFAULT
            BOUNCE -> if (current.type == EasingPreset.BOUNCE) current
                else EasingCurve.bounce(EasingLimits.BOUNCE_DEFAULT_COUNT, EasingLimits.BOUNCE_DEFAULT_RESTITUTION)
            ELASTIC -> if (current.type == EasingPreset.ELASTIC) current
                else EasingCurve.elastic(EasingLimits.ELASTIC_DEFAULT_OSCILLATIONS, EasingLimits.ELASTIC_DEFAULT_DAMPING)
        }
    }
}

/** Handle maths of the Ease view (pure; JVM tested). */
object EaseMath {
    const val SNAP = .05f
    fun same(a: EasingCurve, b: EasingCurve) = a.type == b.type && EasePresets.close(a.a, b.a) && EasePresets.close(a.b, b.b) &&
        EasePresets.close(a.c, b.c) && EasePresets.close(a.d, b.d)

    /** Swap ease-in and ease-out character: (x1, y1, x2, y2) → (1 − x2, 1 − y2, 1 − x1, 1 − y1). Null where meaningless. */
    fun invert(curve: EasingCurve): EasingCurve? {
        val b = curve.bezierOrNull() ?: return null
        if (curve.type == EasingPreset.LINEAR) return null
        return EasingCurve.bezier(1f - b[2], 1f - b[3], 1f - b[0], 1f - b[1])
    }
    /** The mirrored partner of a handle at ([x], [y]): (1 − x, 1 − y). */
    fun mirror(x: Float, y: Float): Pair<Float, Float> = (1f - x) to (1f - y)
    fun snap(v: Float): Float = (v / SNAP).roundToInt() * SNAP
    fun clampX(x: Float) = x.coerceIn(EasingLimits.BEZIER_X_MIN, EasingLimits.BEZIER_X_MAX)
    fun clampY(y: Float) = y.coerceIn(EasingLimits.BEZIER_Y_MIN, EasingLimits.BEZIER_Y_MAX)

    /**
     * Move handle [handle] (0 = P1, 1 = P2) of the bezier [base] to ([x], [y]) with the Snap and Mirror aids, clamped to
     * x ∈ [0, 1], y ∈ [−2, 3]. Any bezier-equivalent type becomes CUSTOM.
     */
    fun moveHandle(base: FloatArray, handle: Int, x: Float, y: Float, snap: Boolean, mirror: Boolean): EasingCurve {
        var hx = clampX(if (snap) snap(x) else x); var hy = clampY(if (snap) snap(y) else y)
        hx = clampX(hx); hy = clampY(hy)
        val out = base.copyOf()
        out[handle * 2] = hx; out[handle * 2 + 1] = hy
        if (mirror) { val (mx, my) = mirror(hx, hy); out[(1 - handle) * 2] = clampX(mx); out[(1 - handle) * 2 + 1] = clampY(my) }
        return EasingCurve.bezier(out[0], out[1], out[2], out[3])
    }
}

/** `cubic-bezier()` clipboard text (pure; JVM tested). */
object CubicBezierText {
    private val NUMBER = Regex("""[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""")
    private val WRAPPED = Regex("""^\s*cubic-bezier\s*\((.*)\)\s*;?\s*$""", RegexOption.IGNORE_CASE)
    private val BARE = Regex("""^[\s,]*[-+.\d][-+.\deE\s,]*$""")

    fun format(x1: Float, y1: Float, x2: Float, y2: Float): String = "cubic-bezier(${n(x1)}, ${n(y1)}, ${n(x2)}, ${n(y2)})"
    fun format(curve: EasingCurve): String? = curve.bezierOrNull()?.let { format(it[0], it[1], it[2], it[3]) }
    private fun n(v: Float): String {
        val r = Math.round(v * 1000.0) / 1000.0
        val s = if (r == Math.rint(r)) r.toLong().toString() else r.toString()
        return if (s == "-0") "0" else s
    }

    /**
     * `cubic-bezier(a, b, c, d)` or bare `a, b, c, d` (spaces or commas, optional leading dots, negatives). Exactly four
     * finite numbers; x outside [0, 1] is rejected, y is clamped to [−2, 3]. Anything else (or empty) is null.
     */
    fun parse(text: String?): EasingCurve? {
        if (text == null || text.isBlank() || text.length > 256) return null
        val body = WRAPPED.find(text)?.groupValues?.get(1) ?: text.trim().takeIf { BARE.matches(it) } ?: return null
        val tokens = body.split(',', ' ', '\t', '\n').filter { it.isNotBlank() }
        if (tokens.size != 4 || tokens.any { !NUMBER.matches(it.trim()) }) return null
        val v = tokens.map { it.trim().toFloatOrNull() ?: return null }
        if (v.any { !it.isFinite() }) return null
        if (v[0] !in 0f..1f || v[2] !in 0f..1f) return null
        return EasingCurve.bezier(v[0], EaseMath.clampY(v[1]), v[2], EaseMath.clampY(v[3]))
    }
}
