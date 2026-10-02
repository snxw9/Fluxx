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
    private val lines = data[4].toInt()
    private val glyphs = data[5].toInt()
    private val membership = 6 + lines + glyphs * 7
    private fun clusters(line: Int): Int = if (data.size > membership) data[membership + line].toInt() else 1
    private fun clusterOrdinal(glyph: Int): Int = if (data.size > membership) data[membership + lines + glyph].toInt() else 0
    private fun advance(line: Int, factor: Double, tracking: Float, size: Float): Double =
        data[6 + line] * factor + (clusters(line)-1).coerceAtLeast(0) * tracking * size / 1000.0
    fun logical(text: TextProperties, time: Long) = logical(text.size.evaluate(time), text.tracking.evaluate(time),
        text.leading.evaluate(time), text.baselineShift.evaluate(time))
    fun bounds(text: TextProperties, time: Long) = logical(text, time).union(ink(text.size.evaluate(time), text.alignment,
        text.tracking.evaluate(time), text.leading.evaluate(time), text.baselineShift.evaluate(time), text.effectiveStrokeWidth(time)))
    fun logical(size: Float, tracking: Float = 0f, leading: Float = 0f, baseline: Float = 0f): TextBounds {
        val out = FloatArray(4); logicalInto(size, tracking, leading, baseline, out)
        return TextBounds(out[0],out[1],out[2],out[3])
    }
    fun logicalInto(size: Float, tracking: Float, leading: Float, baseline: Float, out: FloatArray) {
        if (sourceEmpty) { out.fill(0f); return }
        val factor = size / data[0]
        var width = 0.0
        for (line in 0 until lines) width = maxOf(width,advance(line,factor,tracking,size))
        val step = (data[1]-data[2]+maxOf(0.0,data[3]))*factor+leading
        val last = (lines-1).coerceAtLeast(0)*step
        out[0] = 0f; out[1] = (-data[1]*factor+minOf(0.0,last)).toFloat()
        out[2] = width.coerceAtLeast(0.0).toFloat(); out[3] = (kotlin.math.abs(last)+(data[1]-data[2])*factor).toFloat()
    }
    fun ink(size: Float, alignment: TextAlignment, tracking: Float = 0f, leading: Float = 0f, baseline: Float = 0f, stroke: Float = 0f): TextBounds {
        val factor = size / data[0]; val lines = data[4].toInt(); val glyphs = data[5].toInt()
        val maxAdvance = ((0 until lines).maxOfOrNull { advance(it, factor, tracking, size) } ?: 0.0).coerceAtLeast(0.0)
        var result = TextBounds(0f, 0f, 0f, 0f)
        repeat(glyphs) { index ->
            val i = 6 + lines + index * 7; val line = data[i].toInt()
            val align = (maxAdvance - advance(line, factor, tracking, size)) * alignment.ordinal / 2.0
            val x = (data[i + 1] + data[i + 3])*factor + align + clusterOrdinal(index)*tracking*size/1000.0
            val y = (line * (data[1] - data[2] + maxOf(0.0, data[3])) + data[i + 2] - data[i + 4])*factor + line*leading-baseline
            val w = data[i + 5]; val h = -data[i + 6]
            if (w > 0 && h > 0) {
                val padding = if (stroke > 0f) stroke*.5f+TextSdfLimits.aaPadding(size) else 0f
                result = result.union(TextBounds(x.toFloat()-padding, y.toFloat()-padding,
                    (w * factor).toFloat()+2*padding, (h * factor).toFloat()+2*padding))
            }
        }
        return result
    }
    fun bounds(size: Float, alignment: TextAlignment) = logical(size).union(ink(size, alignment))
    companion object {
        fun fromNative(values: DoubleArray, sourceEmpty: Boolean): TextLayoutMetrics {
            require(values.size >= 6 && values.all { it.isFinite() } && values[0] > 0)
            val lines = values[4].toInt(); val glyphs = values[5].toInt()
            require(lines >= 0 && glyphs >= 0 && (values.size.toLong() == 6L + lines + glyphs * 7L || values.size.toLong() == 6L + lines*2L + glyphs * 8L))
            repeat(glyphs) { require(values[6 + lines + it * 7].toInt() in 0 until lines) }
            return TextLayoutMetrics(values.copyOf(), sourceEmpty)
        }
    }
}

fun interface TextMetricsProvider { fun measure(fontId: String, source: String): TextLayoutMetrics }
class FakeTextMetricsProvider(private val values: Map<Pair<String, String>, TextLayoutMetrics>) : TextMetricsProvider {
    override fun measure(fontId: String, source: String) = requireNotNull(values[fontId to source])
}
