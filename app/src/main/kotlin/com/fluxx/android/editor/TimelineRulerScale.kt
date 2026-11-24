package com.fluxx.android.editor

import com.fluxx.android.model.CompositionTimecode
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

/**
 * TL1: the ONE mapping between time and timeline x (content px, relative to the start of the ruler). A frame's x is
 * `round(frame × pxPerFrame)` with pxPerFrame from the rational rate (no accumulated per-frame rounding); the ruler draws
 * every tick there and the playhead's scroll offset uses the same function, so the playhead line sits on its frame's tick.
 */
class TimelineScale(val rate: FrameRate, val pxPerUs: Double) {
    init { require(pxPerUs.isFinite() && pxPerUs > 0) }
    val pxPerFrame: Double = pxPerUs * 1_000_000.0 * rate.denominator / rate.numerator
    fun frameX(frame: Long): Long = Math.round(frame * pxPerFrame)
    /** A time on the frame grid uses its frame's x; any other time (playback) its exact position. */
    fun timeX(timeUs: Long): Long {
        val t = timeUs.coerceAtLeast(0)
        val f = rate.nearestFrame(t)
        return if (rate.frameTimeUs(f) == t) frameX(f) else Math.round(t * pxPerUs)
    }
    /** The nearest frame to a content x (a scroll position): scrubbing lands on whole frames. */
    fun frameAt(x: Double): Long = Math.round(x / pxPerFrame).coerceAtLeast(0)
    /** The last frame inside a timeline of [durationUs] (its start time is at or before the end). */
    fun lastFrame(durationUs: Long): Long = rate.floorFrame(durationUs.coerceAtLeast(0))
    /** A scroll position to the snapped playhead time, inside the timeline. */
    fun snappedTime(x: Double, durationUs: Long): Long = rate.frameTimeUs(frameAt(x).coerceAtMost(lastFrame(durationUs)))
}

enum class TickTier { MAJOR, MID, MINOR }

/** One zoom level of the tiered ruler: majors every [period] timecode frames, mids at [midOffset], minors on every frame. */
data class RulerLevel(val stepSeconds: Long, val period: Long, val mids: Boolean, val minors: Boolean) {
    val midOffset: Long get() = period / 2
}

/**
 * TL1 tiered ruler. Ticks are classified by the DISPLAYED timecode frame (start frame + composition frame), so majors
 * land exactly where the timecode shows `:00` frames.
 * - MAJOR: every step boundary (1 s when zoomed in: at 30 fps frames 0, 30, 60…).
 * - MID: the middle of the step, on frame `floor(period / 2)` (30 fps: 15; 25 fps: 12, as 12.5 is not a frame; 23.976: 12).
 *   The same rule for every rate and every step.
 * - MINOR: every other frame (only at the 1 s step).
 * Zooming out, tiers drop in order: minors when the frame pitch is under [MINOR_DP] (the ruler's existing 6dp minimum),
 * then mids when half a step is under [MID_DP], then the major step grows (2, 5, 10, 30 s, 1, 2, 5, 10, 30 min, 1 h) to keep
 * majors at least [MAJOR_DP] apart, with mids at half the step again when they fit.
 */
object RulerTiers {
    const val MINOR_DP = 6f
    const val MID_DP = 8f
    const val MAJOR_DP = 12f
    val STEPS_SECONDS = longArrayOf(1, 2, 5, 10, 30, 60, 120, 300, 600, 1800, 3600)
    /** Tick heights relative to the major tick (≈ 60% and 35%). */
    const val MID_HEIGHT = .6f
    const val MINOR_HEIGHT = .35f

    fun level(rate: FrameRate, pxPerFrame: Double, density: Float): RulerLevel {
        val nominal = CompositionTimecode.nominal(rate)
        val minor = MINOR_DP * density; val mid = MID_DP * density; val major = MAJOR_DP * density
        if (pxPerFrame >= minor) return RulerLevel(1, nominal, mids = true, minors = true)
        for (s in STEPS_SECONDS) {
            val period = s * nominal
            if (period * pxPerFrame < major) continue
            val half = minOf(period / 2, period - period / 2)
            return RulerLevel(s, period, mids = half > 0 && half * pxPerFrame >= mid, minors = false)
        }
        val last = STEPS_SECONDS.last()
        return RulerLevel(last, last * nominal, mids = false, minors = false)
    }

    /** The tier of the tick at timecode frame [timecodeFrame] (start frame + composition frame), or null for no tick. */
    fun tier(timecodeFrame: Long, level: RulerLevel): TickTier? {
        val r = Math.floorMod(timecodeFrame, level.period)
        return when {
            r == 0L -> TickTier.MAJOR
            level.mids && r == level.midOffset -> TickTier.MID
            level.minors -> TickTier.MINOR
            else -> null
        }
    }
}
