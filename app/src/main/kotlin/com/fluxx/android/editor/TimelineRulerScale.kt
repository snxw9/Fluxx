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

/** The zoom a new timeline opens at (dp per second of composition time). */
const val TIMELINE_DEFAULT_DP_PER_SECOND = 120f

/**
 * One zoom level of the tiered ruler: majors every [period] timecode frames ([stepSeconds] of timecode), mids at
 * [midOffset], minors either every [minorEvery] frames (1 s step) or at [minorDivisions] equal parts of the step (zoomed out).
 */
data class RulerLevel(val stepSeconds: Long, val period: Long, val mids: Boolean, val minorEvery: Long = 0, val minorDivisions: Int = 0) {
    val midOffset: Long get() = period / 2
    val minors: Boolean get() = minorEvery > 0 || minorDivisions > 0
}

/**
 * TL1 / GRAPH G3.1 tiered ruler. Ticks are classified by the DISPLAYED timecode frame (start frame + composition frame), so
 * majors land exactly where the timecode shows `:00` frames, and every tick sits on a frame.
 * - MAJOR: every step boundary (1 s, or 2, 5, 10, 30 s, 1 min … zoomed out); the step grows only when the 1 s pitch drops
 *   under [MAJOR_DP].
 * - MID: the middle of the step, on frame `floor(period / 2)` (30 fps: 15; 25 fps: 12; 23.976 / 24: 12), shown whenever its
 *   pitch is at least [MID_DP] (always, given the major minimum).
 * - MINOR (1 s step): every frame when the frame pitch is at least [MINOR_DP]; otherwise every Nth frame, N the smallest of
 *   [MINOR_EVERY] with a pitch of at least [MINOR_DP] (so the default zoom shows three tiers at every rate, 60 fps
 *   included). Zoomed out: a tenth of the step when that is at least [MINOR_DP], else a fifth, else none; each on the frame
 *   `floor(k·period / divisions)` (the mid rule's rounding). Minors skip frames that are majors or mids.
 */
object RulerTiers {
    const val MAJOR_DP = 12f
    const val MID_DP = 5f
    const val MINOR_DP = 3f
    val STEPS_SECONDS = longArrayOf(1, 2, 5, 10, 30, 60, 120, 300, 600, 1800, 3600)
    val MINOR_EVERY = longArrayOf(2, 3, 4, 5, 6, 10, 15, 30)
    /** Tick heights relative to the major tick (≈ 60% and 35%). */
    const val MID_HEIGHT = .6f
    const val MINOR_HEIGHT = .35f

    fun level(rate: FrameRate, pxPerFrame: Double, density: Float): RulerLevel {
        val nominal = CompositionTimecode.nominal(rate)
        val minor = MINOR_DP * density; val mid = MID_DP * density; val major = MAJOR_DP * density
        val s = STEPS_SECONDS.firstOrNull { it * nominal * pxPerFrame >= major } ?: STEPS_SECONDS.last()
        val period = s * nominal
        val mids = period / 2 > 0 && minOf(period / 2, period - period / 2) * pxPerFrame >= mid
        if (s == 1L) {
            val every = if (pxPerFrame >= minor) 1L else MINOR_EVERY.firstOrNull { it < period && it * pxPerFrame >= minor } ?: 0L
            return RulerLevel(s, period, mids, minorEvery = every)
        }
        val divisions = when { period / 10.0 * pxPerFrame >= minor -> 10; period / 5.0 * pxPerFrame >= minor -> 5; else -> 0 }
        return RulerLevel(s, period, mids, minorDivisions = divisions)
    }

    /** The tier of the tick at timecode frame [timecodeFrame] (start frame + composition frame), or null for no tick. */
    fun tier(timecodeFrame: Long, level: RulerLevel): TickTier? {
        val p = level.period
        val r = Math.floorMod(timecodeFrame, p)
        if (r == 0L) return TickTier.MAJOR
        if (level.mids && r == level.midOffset) return TickTier.MID
        if (level.minorEvery > 0) return if (r % level.minorEvery == 0L) TickTier.MINOR else null
        val d = level.minorDivisions
        if (d > 0) {
            val k = r * d / p
            for (c in k..k + 1) if (c in 1 until d && c * p / d == r) return TickTier.MINOR
        }
        return null
    }

    /** The tick offsets inside one period (frames from its major), for drawing without visiting every frame. */
    fun offsets(level: RulerLevel): LongArray {
        val p = level.period
        val out = sortedSetOf(0L)
        if (level.mids) out += level.midOffset
        if (level.minorEvery > 0) { var f = level.minorEvery; while (f < p) { out += f; f += level.minorEvery } }
        if (level.minorDivisions > 0) for (k in 1 until level.minorDivisions) out += k * p / level.minorDivisions
        return out.toLongArray()
    }
}
