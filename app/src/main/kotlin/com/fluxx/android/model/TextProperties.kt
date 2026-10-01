package com.fluxx.android.model

import kotlin.math.pow

/** Common ordered-time lookup for scalar, vector, colour and hold-string properties. */
object PropertyTimeSearch {
    fun <T> atOrBefore(keys: List<T>, timeUs: Long, time: (T) -> Long): Int {
        var low = 0; var high = keys.size
        while (low < high) { val mid = (low + high) ushr 1
            if (time(keys[mid]) <= timeUs) low = mid + 1 else high = mid }
        return low - 1
    }
}

data class StringKeyframe(val timeUs: Long, val value: String) { init { TextLimits.validateSource(value) } }
data class ColourKeyframe(val timeUs: Long, val argb: Int, val easing: EasingPreset = EasingPreset.EASY_EASE)
data class AnimatableString(val isAnimated: Boolean = false, val staticValue: String = "",
    val keyframes: FrozenList<StringKeyframe> = FrozenList.empty()) {
    init { TextLimits.validateSource(staticValue); TextLimits.ordered(keyframes.map { it.timeUs }) }
    fun evaluate(timeUs: Long): String = if (!isAnimated || keyframes.isEmpty()) staticValue
        else keyframes[PropertyTimeSearch.atOrBefore(keyframes, timeUs) { it.timeUs }.coerceAtLeast(0)].value
}
data class AnimatableColour(val isAnimated: Boolean = false, val staticValue: Int = -1,
    val keyframes: FrozenList<ColourKeyframe> = FrozenList.empty()) {
    init { TextLimits.ordered(keyframes.map { it.timeUs }) }
    fun evaluate(timeUs: Long): Int {
        if (!isAnimated || keyframes.isEmpty()) return staticValue
        if (timeUs <= keyframes.first().timeUs) return keyframes.first().argb
        if (timeUs >= keyframes.last().timeUs) return keyframes.last().argb
        val i = PropertyTimeSearch.atOrBefore(keyframes, timeUs) { it.timeUs }
        val a = keyframes[i]; val b = keyframes[i + 1]
        if (timeUs == a.timeUs) return a.argb
        val progress = ((timeUs.toDouble() - a.timeUs) / (b.timeUs.toDouble() - a.timeUs)).toFloat()
        return interpolateColour(a.argb, b.argb, KeyframeEvaluator.easeProgress(progress, a.easing))
    }
}

/** Linear-light, premultiplied interpolation; persisted/static endpoints remain straight sRGB ARGB. */
fun interpolateColour(a: Int, b: Int, progress: Float): Int {
    if (progress <= 0f) return a; if (progress >= 1f) return b
    fun linear(value: Double) = if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
    fun srgb(value: Double) = if (value <= .0031308) value * 12.92 else 1.055 * value.pow(1.0 / 2.4) - .055
    val t = progress.toDouble(); val aa = (a ushr 24) / 255.0; val ba = (b ushr 24) / 255.0
    val alpha = aa * (1 - t) + ba * t
    fun byte(value: Double) = (value * 255 + .5).toInt().coerceIn(0, 255)
    var result = byte(alpha) shl 24
    repeat(3) { channel ->
        val shift = 16 - channel * 8
        val value = linear(((a ushr shift) and 255) / 255.0) * aa * (1 - t) +
            linear(((b ushr shift) and 255) / 255.0) * ba * t
        result = result or (byte(if (alpha == 0.0) 0.0 else srgb((value / alpha).coerceIn(0.0, 1.0))) shl shift)
    }
    return result
}

enum class TextAlignment { LEFT, CENTRE, RIGHT }
data class TextProperties(
    val source: AnimatableString = AnimatableString(),
    val size: AnimatableProperty1D = AnimatableProperty1D(staticValue = 72f),
    val fill: AnimatableColour = AnimatableColour(),
    val fontId: String = "fluxx.sans", val alignment: TextAlignment = TextAlignment.CENTRE,
) {
    init {
        require(fontId.isNotBlank() && fontId.length <= 128 && fontId.none { it == '\u0000' })
        TextLimits.validateSize(size.staticValue); size.keyframes.forEach { TextLimits.validateSize(it.value) }
    }
    val layoutAnimated get() = source.isAnimated || size.isAnimated
    fun rebase(delta: Long) = copy(
        source = source.copy(keyframes = FrozenList.of(source.keyframes.map { it.copy(timeUs = Math.subtractExact(it.timeUs, delta)) })),
        size = size.copy(keyframes = FrozenList.of(size.keyframes.map { it.copy(timeUs = Math.subtractExact(it.timeUs, delta)) })),
        fill = fill.copy(keyframes = FrozenList.of(fill.keyframes.map { it.copy(timeUs = Math.subtractExact(it.timeUs, delta)) })))
    fun sourceBytes(): Long = source.staticValue.toByteArray(Charsets.UTF_8).size.toLong() +
        source.keyframes.sumOf { it.value.toByteArray(Charsets.UTF_8).size.toLong() }
}
object TextLimits {
    const val MIN_SIZE = 8f // Provisional until the user's small-size/Quarter-preview acceptance.
    const val MAX_SIZE = 500f
    const val MAX_SOURCE_UNITS = 4096
    const val MAX_DOCUMENT_BYTES = 1024L * 1024
    fun validateSize(value: Float) { require(value.isFinite() && value in MIN_SIZE..MAX_SIZE) { "Text size must be 8–500px" } }
    fun decodeSize(value: Float): Float { require(value.isFinite()) { "Nonfinite text size" }; return value.coerceIn(MIN_SIZE, MAX_SIZE) }
    fun ordered(times: List<Long>) { require(times.zipWithNext().all { it.first < it.second }) { "Text keys must be ordered and unique" } }
    fun validateSource(value: String) {
        require(value.length <= MAX_SOURCE_UNITS) { "Text exceeds 4096 UTF-16 units" }
        var i = 0
        while (i < value.length) { val c = value[i++]
            require(c != '\u0000' && c != '\r') { "Text contains unsupported NUL/CR" }
            if (c.isHighSurrogate()) { require(i < value.length && value[i].isLowSurrogate()) { "Unpaired text surrogate" }; i++ }
            else require(!c.isLowSurrogate()) { "Unpaired text surrogate" }
        }
    }
}
