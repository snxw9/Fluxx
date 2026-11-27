package com.fluxx.android.editor

import com.fluxx.android.model.*

/**
 * TL3 B4: the colour bar of a colour row (pure, JVM tested). Between each pair of consecutive keys, stops sampled from the
 * evaluator's OWN colour interpolation ([AnimatableColour.evaluate]: linear-light, premultiplied, the segment's easing),
 * never re-derived. Hold segments are a hard step; nothing before the first key or after the last.
 */
object ColourBar {
    const val MAX_STOPS = 64
    /** Roughly one stop per this many px, at least 2, at most [MAX_STOPS] per segment (adaptive). */
    const val PX_PER_STOP = 6f

    /** One drawn segment: colours at [positions] (0..1 of the segment). */
    class Segment(val startUs: Long, val endUs: Long, val colours: IntArray, val positions: FloatArray, val hold: Boolean)

    /** The width bucket of a segment [widthPx] wide: the stop count, so the cache only refills when it changes. */
    fun stops(widthPx: Float): Int = (widthPx / PX_PER_STOP).toInt().coerceIn(2, MAX_STOPS)

    /** Segment [i] (keys i and i+1) of [track] with [stops] stops. */
    fun segment(track: AnimatableColour, i: Int, stops: Int): Segment {
        val a = track.keyframes[i]; val b = track.keyframes[i + 1]
        if (a.easing == EasingPreset.HOLD) return Segment(a.timeUs, b.timeUs, intArrayOf(a.argb, a.argb), floatArrayOf(0f, 1f), true)
        val colours = IntArray(stops); val positions = FloatArray(stops)
        for (s in 0 until stops) {
            val p = s / (stops - 1f)
            positions[s] = p
            val t = if (s == stops - 1) b.timeUs else a.timeUs + ((b.timeUs - a.timeUs) * p.toDouble()).toLong()
            colours[s] = if (s == stops - 1) b.argb else track.evaluate(t)
        }
        return Segment(a.timeUs, b.timeUs, colours, positions, false)
    }

    /**
     * Cache per segment keyed by (the two keys: values, times and curve; the stop bucket). Recomputed only when a key or the
     * bucket changes, never per scroll frame. [extra] holds the drawing object built once per entry (the gradient brush).
     */
    class Cache<T> {
        data class Key(val a: ColourKeyframe, val b: ColourKeyframe, val stops: Int)
        class Entry<T>(val segment: Segment, val extra: T)
        private val map = HashMap<Key, Entry<T>>()
        var computed = 0; private set
        fun get(track: AnimatableColour, i: Int, stops: Int, build: (Segment) -> T): Entry<T> {
            val k = Key(track.keyframes[i], track.keyframes[i + 1], stops)
            return map.getOrPut(k) { computed++; val s = segment(track, i, stops); Entry(s, build(s)) }
        }
        /** Drop entries whose keys no longer exist (after an edit). */
        fun retain(track: AnimatableColour) {
            val live = track.keyframes.toSet()
            map.keys.retainAll { it.a in live && it.b in live }
        }
        val size get() = map.size
    }
}

/**
 * TL3 B4: splitting a segment's easing exactly at progress [x0] (0 < x0 < 1). Easing is a normalised cubic bezier; it is
 * split where x = x0 (de Casteljau at the parameter whose x is x0) and both halves are normalised to unit boxes. Linear and
 * Hold split into themselves. Easy Ease is the `.33,0,.67,1` bezier (the evaluator's LUT of it). Bounce / Elastic: null
 * (the add is not available there). Null as well when a half is degenerate or out of the bezier limits.
 */
object EasingSplit {
    data class Halves(val left: EasingCurve, val right: EasingCurve, val y: Float)

    fun split(curve: EasingCurve, x0: Float): Halves? {
        require(x0 > 0f && x0 < 1f)
        return when (curve.type) {
            EasingPreset.LINEAR -> Halves(EasingCurve.LINEAR, EasingCurve.LINEAR, x0)
            EasingPreset.HOLD -> Halves(EasingCurve.HOLD, EasingCurve.HOLD, 0f)
            EasingPreset.EASY_EASE -> bezier(.33, 0.0, .67, 1.0, x0.toDouble())
            EasingPreset.CUSTOM -> bezier(curve.a.toDouble(), curve.b.toDouble(), curve.c.toDouble(), curve.d.toDouble(), x0.toDouble())
            EasingPreset.BOUNCE, EasingPreset.ELASTIC -> null
        }
    }

    private fun cubic(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
        val u = 1 - t
        return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3
    }

    private fun bezier(x1: Double, y1: Double, x2: Double, y2: Double, x0: Double): Halves? {
        // The parameter whose x is x0 (x is monotone for x controls in [0, 1]): bisection to double precision.
        var lo = 0.0; var hi = 1.0
        repeat(64) { val mid = (lo + hi) / 2; if (cubic(0.0, x1, x2, 1.0, mid) < x0) lo = mid else hi = mid }
        val t = (lo + hi) / 2
        fun lerp(a: Double, b: Double) = a + (b - a) * t
        // de Casteljau on x and y.
        val ax = lerp(0.0, x1); val bx = lerp(x1, x2); val cx = lerp(x2, 1.0)
        val ay = lerp(0.0, y1); val by = lerp(y1, y2); val cy = lerp(y2, 1.0)
        val dx = lerp(ax, bx); val ex = lerp(bx, cx); val dy = lerp(ay, by); val ey = lerp(by, cy)
        val mx = lerp(dx, ex); val my = lerp(dy, ey)
        if (my <= 1e-6 || my >= 1 - 1e-6 || mx <= 0 || mx >= 1) return null
        val left = EasingCurve.bezier((ax / mx).toFloat(), (ay / my).toFloat(), (dx / mx).toFloat(), (dy / my).toFloat())
        val right = EasingCurve.bezier(((ex - mx) / (1 - mx)).toFloat(), ((ey - my) / (1 - my)).toFloat(),
            ((cx - mx) / (1 - mx)).toFloat(), ((cy - my) / (1 - my)).toFloat())
        if (!EasingLimits.valid(left.type, left.a, left.b, left.c, left.d) || !EasingLimits.valid(right.type, right.a, right.b, right.c, right.d)) return null
        return Halves(left, right, my.toFloat())
    }
}

/**
 * TL3 B4: "add keyframe here" on a colour bar (pure, JVM tested): a key at the tapped frame with the sampled colour, and
 * the segment's easing split exactly between the left key and the new key, so the bar looks the same. Existing actions in
 * ONE Batch (the key set, then SetKeyEasing on the left key and on the new key). Null when not available (Bounce /
 * Elastic, on an existing key, outside the keys).
 */
object ColourKeyAdd {
    enum class Why { OK, BOUNCE_ELASTIC, OUTSIDE, ON_KEY }

    /** The tapped composition time snapped to the frame grid, in the layer's local time. */
    fun localFrame(layer: CompositionLayer, rate: FrameRate, compositionUs: Long): Long =
        Math.subtractExact(rate.snap(compositionUs.coerceAtLeast(0)), layer.resolvedKeyframeAnchorUs)

    fun check(track: AnimatableColour, local: Long): Why {
        val k = track.keyframes
        if (k.size < 2 || local <= k.first().timeUs || local >= k.last().timeUs) return if (k.any { it.timeUs == local }) Why.ON_KEY else Why.OUTSIDE
        if (k.any { it.timeUs == local }) return Why.ON_KEY
        val a = k[PropertyTimeSearch.atOrBefore(k, local) { it.timeUs }]
        return if (a.easing == EasingPreset.BOUNCE || a.easing == EasingPreset.ELASTIC) Why.BOUNCE_ELASTIC else Why.OK
    }

    fun action(layer: CompositionLayer, target: GraphTarget, local: Long): EditorAction? {
        val track = TwirlColour.track(layer, target) ?: return null
        if (check(track, local) != Why.OK) return null
        val k = track.keyframes
        val i = PropertyTimeSearch.atOrBefore(k, local) { it.timeUs }
        val a = k[i]; val b = k[i + 1]
        val x0 = ((local - a.timeUs).toDouble() / (b.timeUs - a.timeUs)).toFloat()
        val halves = EasingSplit.split(a.curve, x0) ?: return null
        val argb = track.evaluate(local)
        val set = target.animator?.let { EditorAction.EditTextAnimator(layer.id, AnimatorEdit.Key(it, AnimatorKeyOperation.SET, local, argb = argb, easing = halves.right.type)) }
            ?: EditorAction.SetColourKeyframe(layer.id, local, argb, halves.right.type, target.property!!)
        return EditorAction.Batch(listOf(set,
            EditorAction.SetKeyEasing(layer.id, target.property, target.animator, listOf(a.timeUs), halves.left),
            EditorAction.SetKeyEasing(layer.id, target.property, target.animator, listOf(local), halves.right)))
    }
}
