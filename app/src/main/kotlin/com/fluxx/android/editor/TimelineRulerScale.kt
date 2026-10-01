package com.fluxx.android.editor

import com.fluxx.android.model.FrameRate
import kotlin.math.roundToLong

data class FrameTickSteps(val minor: Long, val major: Long)

/** All ticks are integer frame indices, including at rational rates such as 30000/1001. */
fun timelineTickSteps(rate: FrameRate, pixelsPerUs: Double, minimumTickPx: Double,
    minimumLabelPx: Double): FrameTickSteps {
    require(pixelsPerUs.isFinite() && pixelsPerUs > 0 && minimumTickPx > 0 && minimumLabelPx >= minimumTickPx)
    val pixelsPerFrame = pixelsPerUs * 1_000_000.0 * rate.denominator / rate.numerator
    var major = (rate.numerator.toDouble() / rate.denominator).roundToLong().coerceAtLeast(1)
    while (major * pixelsPerFrame < minimumLabelPx && major <= Long.MAX_VALUE / 2) major *= 2
    val divisions = intArrayOf(60, 30, 24, 20, 15, 12, 10, 8, 6, 5, 4, 3, 2, 1)
    val count = divisions.first { major % it == 0L && major / it * pixelsPerFrame >= minimumTickPx }
    return FrameTickSteps(major / count, major)
}
