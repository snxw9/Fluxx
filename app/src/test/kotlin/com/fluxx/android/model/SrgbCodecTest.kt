package com.fluxx.android.model

import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test

/** TEXT PERF 10: the colour tables must reproduce the pow formulas byte for byte. */
class SrgbCodecTest {
    // The TEXT FIX 9 implementations, verbatim.
    private fun linear(value: Double) = if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
    private fun srgb(value: Double) = if (value <= .0031308) value * 12.92 else 1.055 * value.pow(1.0 / 2.4) - .055
    private fun byte(value: Double) = (value * 255 + .5).toInt().coerceIn(0, 255)
    private fun reference(a: Int, b: Int, progress: Float): Int {
        if (progress <= 0f) return a; if (progress >= 1f) return b
        val t = progress.toDouble(); val aa = (a ushr 24) / 255.0; val ba = (b ushr 24) / 255.0
        val alpha = aa * (1 - t) + ba * t
        var result = byte(alpha) shl 24
        repeat(3) { channel ->
            val shift = 16 - channel * 8
            val value = linear(((a ushr shift) and 255) / 255.0) * aa * (1 - t) + linear(((b ushr shift) and 255) / 255.0) * ba * t
            result = result or (byte(if (alpha == 0.0) 0.0 else srgb((value / alpha).coerceIn(0.0, 1.0))) shl shift)
        }
        return result
    }
    private fun referenceBlend(a: Int, b: Int, red: Double, green: Double, blue: Double): Int {
        val aa = (a ushr 24) / 255.0; val ba = (b ushr 24) / 255.0
        val t = red.coerceIn(0.0, 1.0); val alpha = aa * (1 - t) + ba * t
        var result = (alpha * 255 + .5).toInt().coerceIn(0, 255) shl 24
        for (channel in 0..2) {
            val weight = (if (channel == 0) red else if (channel == 1) green else blue).coerceIn(0.0, 1.0)
            val shift = 16 - channel * 8
            val value = linear(((a ushr shift) and 255) / 255.0) * aa * (1 - weight) + linear(((b ushr shift) and 255) / 255.0) * ba * weight
            val valueAlpha = aa * (1 - weight) + ba * weight
            val byte = if (valueAlpha == 0.0) 0 else (srgb((value / valueAlpha).coerceIn(0.0, 1.0)) * 255 + .5).toInt().coerceIn(0, 255)
            result = result or (byte shl shift)
        }
        return result
    }

    @Test fun decodeTableIsTheFormula() {
        for (i in 0..255) assertEquals(java.lang.Double.doubleToRawLongBits(linear(i / 255.0)), java.lang.Double.doubleToRawLongBits(SrgbCodec.linear8(i)))
    }
    @Test fun encodeMatchesTheFormulaEverywhereIncludingRoundingBoundaries() {
        val random = java.util.Random(10)
        repeat(2_000_000) { val v = random.nextDouble(); assertEquals("v=$v", byte(srgb(v)), SrgbCodec.encode8(v)) }
        for (v in doubleArrayOf(0.0, 1.0, Double.MIN_VALUE, .0031308, .0031308049, .04045, Double.NaN, 1 - 1e-17)) assertEquals("v=$v", byte(srgb(v)), SrgbCodec.encode8(v))
        // Every byte boundary, and the doubles around it (where a table could round differently).
        for (k in 1..255) {
            val boundary = linear((k - .5) / 255.0)
            var v = boundary
            repeat(64) { v = Math.nextDown(v) }
            repeat(129) { if (v in 0.0..1.0) assertEquals("k=$k v=$v", byte(srgb(v)), SrgbCodec.encode8(v)); v = Math.nextUp(v) }
            for (offset in doubleArrayOf(-2e-9, -1e-9, -5e-10, 5e-10, 1e-9, 2e-9)) {
                val w = boundary + offset
                if (w in 0.0..1.0) assertEquals("k=$k w=$w", byte(srgb(w)), SrgbCodec.encode8(w))
            }
        }
    }
    @Test fun interpolationIsBitIdenticalForEveryChannelPair() {
        val progresses = floatArrayOf(1e-6f, .01f, .1f, .25f, 1f / 3, .5f, .7f, .9f, .999f)
        for (x in 0..255 step 3) for (y in 0..255 step 5) for (alphaA in intArrayOf(0, 1, 128, 255)) for (alphaB in intArrayOf(0, 77, 255)) {
            val a = (alphaA shl 24) or (x shl 16) or (y shl 8) or ((x + y) and 255)
            val b = (alphaB shl 24) or (y shl 16) or (255 - x shl 8) or ((x * 7) and 255)
            for (p in progresses) assertEquals(reference(a, b, p), interpolateColour(a, b, p))
        }
        val random = java.util.Random(11)
        repeat(500_000) {
            val a = random.nextInt(); val b = random.nextInt(); val p = random.nextFloat()
            assertEquals(reference(a, b, p), interpolateColour(a, b, p))
            val r = random.nextDouble() * 1.2 - .1; val g = random.nextDouble(); val bl = random.nextDouble()
            assertEquals(referenceBlend(a, b, r, g, bl), TextAnimatorColour.blend(a, b, r, g, bl))
        }
    }
}
