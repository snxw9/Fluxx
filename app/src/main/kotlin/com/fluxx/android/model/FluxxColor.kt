package com.fluxx.android.model

import kotlin.math.roundToInt

/** Straight-alpha sRGB. No Android or Compose dependency. Packed persistence stays ARGB. */
data class FluxxColor(val r: Float, val g: Float, val b: Float, val a: Float = 1f) {
    init { require(listOf(r, g, b, a).all { it.isFinite() && it in 0f..1f }) }
    fun toArgb(): Int = ((a * 255).roundToInt() shl 24) or ((r * 255).roundToInt() shl 16) or
        ((g * 255).roundToInt() shl 8) or (b * 255).roundToInt()
    /** Eight digits use AARRGGBB, matching persisted ARGB. */
    fun toHexString(includeAlpha: Boolean = a < 1f): String =
        toArgb().toUInt().toString(16).uppercase().padStart(8, '0').let { if (includeAlpha) it else it.takeLast(6) }
    data class Hsv(val hue: Float, val saturation: Float, val value: Float)
    fun toHsv(): Hsv {
        val hi = maxOf(r, g, b); val lo = minOf(r, g, b); val delta = hi - lo
        val h = when {
            delta == 0f -> 0f
            hi == r -> 60f * ((g - b) / delta % 6)
            hi == g -> 60f * ((b - r) / delta + 2)
            else -> 60f * ((r - g) / delta + 4)
        }
        return Hsv((h + 360) % 360, if (hi == 0f) 0f else delta / hi, hi)
    }
    companion object {
        fun fromArgb(argb: Int) = FluxxColor((argb ushr 16 and 255) / 255f,
            (argb ushr 8 and 255) / 255f, (argb and 255) / 255f, (argb ushr 24) / 255f)
        /**
         * The one hex parser (GRAPH G2.1 §2). Case-insensitive, optional leading `#`, surrounding whitespace ignored.
         * `RGB` (each digit doubled) and `RRGGBB` take [alpha]; `AARRGGBB` sets alpha too. Any other length, a non-hex
         * character or empty text is invalid (null).
         */
        fun parseHex(text: String, alpha: Float = 1f): FluxxColor? {
            val raw = text.trim().removePrefix("#")
            if (!raw.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            val digits = when (raw.length) { 3 -> raw.map { "$it$it" }.joinToString(""); 6, 8 -> raw; else -> return null }
            val bits = digits.toUIntOrNull(16) ?: return null
            val value = fromArgb(bits.toInt())
            return if (digits.length == 6) value.copy(a = alpha) else value
        }
        /** While typing, only complete 6 / 8-digit values apply live (a 3-digit prefix of a longer value would flash). */
        fun hexAppliesLive(text: String) = text.trim().removePrefix("#").length.let { it == 6 || it == 8 }
        /** Typed hex entry on packed ARGB: 3/6 digits keep [current]'s alpha byte exactly; 8 digits replace it. */
        fun parseHexEntry(text: String, current: Int): Int? {
            val argb = parseHex(text)?.toArgb() ?: return null
            return if (text.trim().removePrefix("#").length == 8) argb else (argb and 0x00FFFFFF) or (current and 0xFF000000.toInt())
        }
        fun fromHsv(hue: Float, saturation: Float, value: Float, alpha: Float = 1f): FluxxColor {
            require(hue.isFinite() && saturation in 0f..1f && value in 0f..1f)
            val h = ((hue % 360 + 360) % 360) / 60f
            val c = value * saturation; val x = c * (1 - kotlin.math.abs(h % 2 - 1)); val m = value - c
            val rgb = when (h.toInt()) {
                0 -> floatArrayOf(c,x,0f); 1 -> floatArrayOf(x,c,0f); 2 -> floatArrayOf(0f,c,x)
                3 -> floatArrayOf(0f,x,c); 4 -> floatArrayOf(x,0f,c); else -> floatArrayOf(c,0f,x)
            }
            return FluxxColor((rgb[0]+m).coerceIn(0f,1f),(rgb[1]+m).coerceIn(0f,1f),(rgb[2]+m).coerceIn(0f,1f),alpha)
        }
    }
}
