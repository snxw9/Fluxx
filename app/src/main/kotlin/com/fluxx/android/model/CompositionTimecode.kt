package com.fluxx.android.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** The editor clock is minutes:seconds:frames, including at fractional frame rates. */
object CompositionTimecode {
    fun format(timeUs: Long, rate: FrameRate): String {
        val fps = nominalFps(rate)
        val frame = rate.nearestFrame(timeUs.coerceAtLeast(0))
        return String.format(Locale.ROOT, "%02d:%02d:%02d", frame / fps / 60, frame / fps % 60, frame % fps)
    }

    /** A timecode frame index (start frame + composition frame) as MM:SS:FF at the nominal rate. */
    fun formatFrame(frame: Long, rate: FrameRate): String {
        val fps = nominalFps(rate); val f = frame.coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d:%02d", f / fps / 60, f / fps % 60, f % fps)
    }

    /** TL1: the timecode frame of the composition start (shown at composition time 0). */
    fun startFrame(startTimecodeUs: Long, rate: FrameRate): Long = rate.floorFrame(startTimecodeUs.coerceAtLeast(0))

    /**
     * TL1: the playhead readout (transport, timeline pill, Graph live Time): the frame ON SCREEN (floor), so playback
     * never reads a frame ahead; at rest the playhead sits on a frame and floor equals nearest. Counted as start frame +
     * composition frame, the same index the ruler classifies its ticks by.
     */
    fun formatPlayhead(playheadUs: Long, startTimecodeUs: Long, rate: FrameRate): String =
        formatFrame(Math.addExact(startFrame(startTimecodeUs, rate), rate.floorFrame(playheadUs.coerceAtLeast(0))), rate)

    /** TL1: a time on the frame grid (a key) as start frame + its nearest composition frame. */
    fun formatOnGrid(timeUs: Long, startTimecodeUs: Long, rate: FrameRate): String =
        formatFrame(Math.addExact(startFrame(startTimecodeUs, rate), rate.nearestFrame(timeUs.coerceAtLeast(0))), rate)

    /** TL1: a typed timecode back to composition µs (frame-exact), relative to the start frame; null when invalid or before 0. */
    fun parseOnGrid(text: String, startTimecodeUs: Long, rate: FrameRate): Long? {
        val absolute = parse(text, rate) ?: return null
        val frame = rate.nearestFrame(absolute) - startFrame(startTimecodeUs, rate)
        return if (frame < 0) null else rate.frameTimeUs(frame)
    }

    fun nominal(rate: FrameRate): Long = nominalFps(rate)

    fun parse(text: String, rate: FrameRate): Long? = try {
        val parts = text.trim().split(':')
        require(parts.size == 3 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) })
        val (minutes, seconds, frames) = parts.map(String::toLong)
        val fps = nominalFps(rate)
        require(seconds in 0..59 && frames in 0 until fps)
        rate.frameTimeUs(Math.addExact(Math.multiplyExact(Math.addExact(Math.multiplyExact(minutes, 60), seconds), fps), frames))
    } catch (_: Exception) { null }

    private fun nominalFps(rate: FrameRate) = Math.round(rate.numerator.toDouble() / rate.denominator).coerceAtLeast(1)

    fun frameRateLabel(rate: FrameRate): String = when (rate) {
        FrameRate(24000, 1001) -> "23.976"
        FrameRate(30000, 1001) -> "29.97"
        FrameRate(60000, 1001) -> "59.94"
        FrameRate(120000, 1001) -> "119.88"
        else -> BigDecimal(rate.numerator).divide(BigDecimal(rate.denominator), 6, RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString()
    }

    fun parseFrameRate(text: String): FrameRate? = try {
        val input = text.trim()
        require(input.length <= 32 && input.matches(Regex("[0-9]+(?:\\.[0-9]{1,6})?")))
        val value = BigDecimal(input).stripTrailingZeros()
        require(value >= BigDecimal.ONE && value <= BigDecimal(120))
        when (value.toPlainString()) {
            "23.976" -> FrameRate(24000, 1001)
            "29.97" -> FrameRate(30000, 1001)
            "59.94" -> FrameRate(60000, 1001)
            "119.88" -> FrameRate(120000, 1001)
            else -> {
                val denominator = java.math.BigInteger.TEN.pow(value.scale().coerceAtLeast(0))
                val numerator = value.multiply(BigDecimal(denominator)).toBigIntegerExact()
                val gcd = numerator.gcd(denominator)
                FrameRate(numerator.divide(gcd).intValueExact(), denominator.divide(gcd).intValueExact())
            }
        }
    } catch (_: Exception) { null }
}
