package com.fluxx.android.ui.inspector

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * GRAPH G2.1 §9: a magnetic detent on a dragged control. The output follows the raw (finger) value continuously; when the
 * raw value comes within the zone of a detent it LOCKS to it, and it RELEASES as soon as the raw value is beyond the
 * zone again. Outside the zones the output is the raw value, so no value between detents is ever skipped. Typed values
 * never pass through a magnet. One instance per drag ([reset] at the start).
 *
 * The existing inspector "detents" (`PropertyLimit.detentStep` / `detent`, the Character Tracking slider since TEXT
 * F3.4) are haptic buckets only (a tick when the value crosses a multiple, no lock); this adds the lock and reuses the
 * same haptic tick ([InspectorHaptics.tick]) when it locks.
 */
internal class Magnet(private val nearest: (Float) -> Float?, private val distance: (Float, Float) -> Float, private val zone: (Float) -> Float) {
    var locked: Float? = null; private set
    fun reset() { locked = null }
    fun apply(raw: Float, onLock: () -> Unit = {}): Float {
        if (!raw.isFinite()) return raw
        locked?.let { d -> if (distance(raw, d) <= zone(d)) return d; locked = null }
        val d = nearest(raw) ?: return raw
        if (distance(raw, d) <= zone(d)) { locked = d; onLock(); return d }
        return raw
    }
}

/** The Transform detents and their zones. Position has none (it needs layer geometry; parked). */
internal object TransformDetents {
    const val ROTATION_STEP = 5f
    /** Opacity and Scale are stored as fractions: 25% steps. */
    const val OPACITY_STEP = .25f
    const val SCALE_STEP = .25f
    /** The zone a finger gets, before the spacing rule. */
    const val ZONE_DP = 8f
    /** The zone never exceeds this fraction of the distance between adjacent detents (< 0.5, so zones never merge). */
    const val MAX_ZONE_FRACTION = .35f

    /** The zone in pixels for detents [spacingPx] apart. */
    fun zonePx(zonePx: Float, spacingPx: Float) = min(zonePx, MAX_ZONE_FRACTION * spacingPx).coerceAtLeast(0f)

    /** A linear control: detents every [step] value units; [valuePerPx] is the control's travel. */
    fun linear(step: Float, valuePerPx: Float, zonePx: Float): Magnet {
        val zone = zonePx(zonePx, step / valuePerPx) * valuePerPx
        return Magnet({ v -> (v / step).roundToInt() * step }, { a, b -> abs(a - b) }, { zone })
    }
    /** The Rotation dial: degrees along the handle's circle of [radiusPx]. */
    fun rotation(radiusPx: Float, zonePx: Float) = linear(ROTATION_STEP, (180.0 / (Math.PI * radiusPx.coerceAtLeast(1f))).toFloat(), zonePx)
    /** The Opacity slider: 0..1 across [trackPx]. */
    fun opacity(trackPx: Float, zonePx: Float) = linear(OPACITY_STEP, 1f / trackPx.coerceAtLeast(1f), zonePx)

    /**
     * The Scale ruler is logarithmic ([ScaleRulerMath.DP_PER_DOUBLING] per doubling), so 25% detents get closer as the
     * value grows: 100→125% is 31dp apart, 400→425% 8.4dp, 1000→1025% 3.4dp. Each detent's zone follows its own spacing.
     */
    fun scale(pxPerDoubling: Float, zonePx: Float): Magnet = Magnet(
        { v -> val d = (abs(v) / SCALE_STEP).roundToInt() * SCALE_STEP; if (d <= 0f) null else if (v < 0f) -d else d },
        { a, b -> abs(log2(abs(a).coerceAtLeast(1e-6f)) - log2(abs(b))) },
        { d -> zonePx(zonePx, scaleSpacingDoublings(abs(d)) * pxPerDoubling) / pxPerDoubling })

    /** Doublings to the nearer neighbouring 25% detent of [d] (> 0). */
    fun scaleSpacingDoublings(d: Float): Float {
        val up = log2((d + SCALE_STEP) / d)
        val down = if (d - SCALE_STEP > 0f) log2(d / (d - SCALE_STEP)) else Float.MAX_VALUE
        return min(up, down)
    }
}
