package com.fluxx.android.model

data class TextBounds(val left: Float, val top: Float, val width: Float, val height: Float) {
    val empty get() = width <= 0f || height <= 0f
    fun union(other: TextBounds): TextBounds {
        if (other.empty) return this
        if (empty) return other
        val x = minOf(left, other.left); val y = minOf(top, other.top)
        return TextBounds(x, y, maxOf(left + width, other.left + other.width) - x,
            maxOf(top + height, other.top + other.height) - y)
    }
}

/** Immutable design-unit metrics from the same HarfBuzz layout used by the GPU mesh. */
class TextLayoutMetrics private constructor(private val data: DoubleArray, val sourceEmpty: Boolean) {
    fun logical(size: Float): TextBounds {
        if (sourceEmpty) return TextBounds(0f, 0f, 0f, 0f)
        val factor = size / data[0]; val lines = data[4].toInt()
        val width = (0 until lines).maxOfOrNull { data[6 + it] } ?: 0.0
        return TextBounds(0f, (-data[1] * factor).toFloat(), (width * factor).toFloat(),
            ((lines * (data[1] - data[2]) + (lines - 1).coerceAtLeast(0) * maxOf(0.0, data[3])) * factor).toFloat())
    }
    fun ink(size: Float, alignment: TextAlignment): TextBounds {
        val factor = size / data[0]; val lines = data[4].toInt(); val glyphs = data[5].toInt()
        val maxAdvance = (0 until lines).maxOfOrNull { data[6 + it] } ?: 0.0
        var result = TextBounds(0f, 0f, 0f, 0f)
        repeat(glyphs) { index ->
            val i = 6 + lines + index * 7; val line = data[i].toInt()
            val align = (maxAdvance - data[6 + line]) * alignment.ordinal / 2.0
            val x = data[i + 1] + data[i + 3] + align
            val y = line * (data[1] - data[2] + maxOf(0.0, data[3])) + data[i + 2] - data[i + 4]
            val w = data[i + 5]; val h = -data[i + 6]
            if (w > 0 && h > 0) result = result.union(TextBounds((x * factor).toFloat(), (y * factor).toFloat(),
                (w * factor).toFloat(), (h * factor).toFloat()))
        }
        return result
    }
    fun bounds(size: Float, alignment: TextAlignment) = logical(size).union(ink(size, alignment))
    companion object {
        fun fromNative(values: DoubleArray, sourceEmpty: Boolean): TextLayoutMetrics {
            require(values.size >= 6 && values.all { it.isFinite() } && values[0] > 0)
            val lines = values[4].toInt(); val glyphs = values[5].toInt()
            require(lines >= 0 && glyphs >= 0 && values.size.toLong() == 6L + lines + glyphs * 7L)
            repeat(glyphs) { require(values[6 + lines + it * 7].toInt() in 0 until lines) }
            return TextLayoutMetrics(values.copyOf(), sourceEmpty)
        }
    }
}

fun interface TextMetricsProvider { fun measure(fontId: String, source: String): TextLayoutMetrics }
class FakeTextMetricsProvider(private val values: Map<Pair<String, String>, TextLayoutMetrics>) : TextMetricsProvider {
    override fun measure(fontId: String, source: String) = requireNotNull(values[fontId to source])
}
