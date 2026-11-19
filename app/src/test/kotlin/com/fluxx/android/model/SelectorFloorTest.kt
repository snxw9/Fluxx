package com.fluxx.android.model

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** TEXT PERF 10: SelectorMath.floor must be bit-identical to Math.floor, because Wiggly noise and character values use it. */
class SelectorFloorTest {
    // NaN payloads: HotSpot's interpreted Math.floor keeps a signalling payload while its compiled intrinsic quiets it, so
    // NaN input only requires a NaN result. Every other input must match bit for bit (signed zero included).
    private fun check(x: Double) = if (x != x) assertEquals(true, SelectorMath.floor(x).isNaN()) else
        assertEquals("floor($x)", java.lang.Double.doubleToRawLongBits(Math.floor(x)),
            java.lang.Double.doubleToRawLongBits(SelectorMath.floor(x)))

    @Test fun edgeValuesMatchMathFloor() {
        val two52 = 4503599627370496.0
        listOf(0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MIN_VALUE, -Double.MIN_VALUE,
            Double.MAX_VALUE, -Double.MAX_VALUE, 0.5, -0.5, 1.0, -1.0, 0.49999999999999994, -0.49999999999999994,
            two52, -two52, two52 - 0.5, -(two52 - 0.5), two52 + 1, -(two52 + 1), Math.nextDown(two52), -Math.nextDown(two52),
            Long.MAX_VALUE.toDouble(), Long.MIN_VALUE.toDouble(), 1e300, -1e300, 999_999_999.999999, -999_999_999.999999,
            Math.nextUp(1.0), Math.nextDown(1.0), Math.nextUp(-1.0), Math.nextDown(-1.0), 1114111.5, -1e-320,
        ).forEach(::check)
        for (i in -2000..2000) { val v = i / 8.0; check(v); check(Math.nextUp(v)); check(Math.nextDown(v)) }
    }

    @Test fun randomValuesMatchMathFloor() {
        val random = Random(0x5EED)
        repeat(2_000_000) {
            check(java.lang.Double.longBitsToDouble(random.nextLong())) // every exponent, NaN payloads, subnormals
            check((random.nextDouble() - .5) * 2e9) // the Wiggly coordinate range after % 1e9
            check((random.nextDouble() - .5) * 2.3e6 + .5) // character values: codepoint + coverage * value + 0.5
        }
    }
}
