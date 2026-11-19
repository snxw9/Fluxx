package com.fluxx.android.model

import kotlin.math.pow

/**
 * TEXT PERF 10: exact table forms of the sRGB transfer functions used by colour interpolation.
 *
 * Every per-glyph colour interpolation decoded three 8-bit channels per endpoint and encoded three results with
 * `Math.pow`. In the debuggable JIT each pow is a generic JNI call, so this was about 13% of an animated text frame.
 * The results are bit-identical to the formulas:
 * - [linear8] is a 256-entry table holding exactly `linear(i / 255.0)`.
 * - [encode8] returns exactly `byte(srgb(v))`. It brackets `v` between the analytic thresholds where the rounded byte
 *   changes, and evaluates the formula itself when `v` lies within 1e-9 of a threshold, so a rounding boundary never
 *   depends on the table.
 */
object SrgbCodec {
    fun linear(value: Double) = if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
    fun srgb(value: Double) = if (value <= .0031308) value * 12.92 else 1.055 * value.pow(1.0 / 2.4) - .055
    fun byte(value: Double): Int { val b = (value * 255 + .5).toInt(); return if (b < 0) 0 else if (b > 255) 255 else b } // coerceIn(0, 255)

    private val linearTable = DoubleArray(256) { linear(it / 255.0) }
    /** thresholds[k]: the linear value where byte(srgb(v)) reaches k (k = 1..255); thresholds[0] is unused. */
    private val thresholds = DoubleArray(256) { k -> if (k == 0) Double.NEGATIVE_INFINITY else linear((k - .5) / 255.0) }
    private const val GUARD = 1e-9

    /** Exactly `linear(channel / 255.0)` for an 8-bit channel. */
    fun linear8(channel: Int): Double = linearTable[channel]

    private const val BUCKETS = 1024
    /** buckets[i]: largest k with thresholds[k] <= i / BUCKETS (a lower bound for any v in bucket i or later). */
    private val buckets = IntArray(BUCKETS + 1) { i -> var k = 0; while (k < 255 && thresholds[k + 1] <= i.toDouble() / BUCKETS) k++; k }

    /** Exactly `byte(srgb(v))` for `v` in [0, 1] (callers clamp first, as the formulas did). */
    fun encode8(v: Double): Int {
        var low: Int // largest k with thresholds[k] <= v
        if (v >= 0.0 && v <= 1.0) {
            val bucket = (v * BUCKETS).toInt()
            low = buckets[if (bucket > 0) bucket - 1 else 0] // One bucket back: v*BUCKETS may round up across a boundary.
            while (low < 255 && thresholds[low + 1] <= v) low++
        } else {
            low = 0; var high = 255
            while (low < high) { val mid = (low + high + 1) ushr 1; if (thresholds[mid] <= v) low = mid else high = mid - 1 }
        }
        if ((low > 0 && v - thresholds[low] < GUARD) || (low < 255 && thresholds[low + 1] - v < GUARD)) return byte(srgb(v))
        return low
    }
}
