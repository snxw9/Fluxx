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
