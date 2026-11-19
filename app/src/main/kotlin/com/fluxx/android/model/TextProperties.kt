package com.fluxx.android.model

import kotlin.math.pow

/** Common ordered-time lookup for scalar, vector, colour and hold-string properties. */
object PropertyTimeSearch {
    inline fun <T> atOrBefore(keys: List<T>, timeUs: Long, time: (T) -> Long): Int {
        var low = 0; var high = keys.size
        while (low < high) { val mid = (low + high) ushr 1
            if (time(keys[mid]) <= timeUs) low = mid + 1 else high = mid }
        return low - 1
    }
}

data class StringKeyframe(val timeUs: Long, val value: String,val document: TextSourceDocument?=null) {
    val sourceDocument=document ?: TextSourceDocument(value)
    init { TextLimits.validateSource(value);require(sourceDocument.text==value) }
}
data class ColourKeyframe(val timeUs: Long, val argb: Int, val easing: EasingPreset = EasingPreset.EASY_EASE)
data class AnimatableString(val isAnimated: Boolean = false, val staticValue: String = "",
    val keyframes: FrozenList<StringKeyframe> = FrozenList.empty(),val document: TextSourceDocument?=null) {
    val sourceDocument=document ?: TextSourceDocument(staticValue)
    init { TextLimits.validateSource(staticValue); TextLimits.ordered(keyframes.map { it.timeUs });require(sourceDocument.text==staticValue) }
    fun evaluate(timeUs: Long): String = if (!isAnimated || keyframes.isEmpty()) staticValue
        else keyframes[PropertyTimeSearch.atOrBefore(keyframes, timeUs) { it.timeUs }.coerceAtLeast(0)].value
    fun evaluateDocument(timeUs: Long): TextSourceDocument = if(!isAnimated || keyframes.isEmpty()) sourceDocument
        else keyframes[PropertyTimeSearch.atOrBefore(keyframes,timeUs) { it.timeUs }.coerceAtLeast(0)].sourceDocument
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
    // TEXT PERF 10: SrgbCodec gives the same bytes as linear()/srgb() with pow, without a pow per channel.
    val t = progress.toDouble(); val aa = (a ushr 24) / 255.0; val ba = (b ushr 24) / 255.0
    val alpha = aa * (1 - t) + ba * t
    var result = SrgbCodec.byte(alpha) shl 24
    for (channel in 0..2) {
        val shift = 16 - channel * 8
        val value = SrgbCodec.linear8((a ushr shift) and 255) * aa * (1 - t) +
            SrgbCodec.linear8((b ushr shift) and 255) * ba * t
        result = result or ((if (alpha == 0.0) 0 else SrgbCodec.encode8((value / alpha).coerceIn(0.0, 1.0))) shl shift)
    }
    return result
}

enum class TextAlignment { LEFT, CENTRE, RIGHT }
data class TextProperties(
    val source: AnimatableString = AnimatableString(),
    val size: AnimatableProperty1D = AnimatableProperty1D(staticValue = 72f),
    val fill: AnimatableColour = AnimatableColour(),
    val fontId: String = "fluxx.sans", val alignment: TextAlignment = TextAlignment.CENTRE,
    val tracking: AnimatableProperty1D = AnimatableProperty1D(),
    val leading: AnimatableProperty1D = AnimatableProperty1D(),
    val baselineShift: AnimatableProperty1D = AnimatableProperty1D(),
    val strokeColour: AnimatableColour = AnimatableColour(staticValue = 0xff000000.toInt()),
    val strokeWidth: AnimatableProperty1D = AnimatableProperty1D(),
    val strokeOverFill: AnimatableProperty1D = AnimatableProperty1D(),
    val animators: FrozenList<TextAnimator> = FrozenList.empty(),
    val ligatures: AnimatableProperty1D = AnimatableProperty1D(staticValue = 1f),
) {
    init {
        ligatures.validateRange(0f,1f,true)
        require(animators.size <= 16 && animators.map { it.id }.distinct().size == animators.size)
        val selectorIds = animators.flatMap { it.selectors.map { s -> s.id } }
        require(selectorIds.distinct().size == selectorIds.size)
        require(fontId.isNotBlank() && fontId.length <= 128 && fontId.none { it == '\u0000' })
        TextLimits.validateSize(size.staticValue); size.keyframes.forEach { TextLimits.validateSize(it.value) }
        listOf(tracking, leading, baselineShift).forEach { p ->
            require(p.staticValue.isFinite()); p.keyframes.forEach { require(it.value.isFinite()) }
        }
        require(strokeWidth.staticValue.isFinite() && strokeWidth.staticValue >= 0f)
        strokeWidth.keyframes.forEach { require(it.value.isFinite() && it.value >= 0f) }
        validatePaintOrder(strokeOverFill.staticValue)
        strokeOverFill.keyframes.forEach { validatePaintOrder(it.value) }
    }
    val layoutAnimated get() = source.isAnimated || size.isAnimated || tracking.isAnimated || leading.isAnimated || baselineShift.isAnimated || ligatures.isAnimated || animators.isNotEmpty()
    fun effectiveLigatures(time: Long) = ligatures.hold(time) == 1f && tracking.evaluate(time) == 0f
    fun effectiveStrokeWidth(time: Long): Float = TextSdfLimits.strokeWidth(strokeWidth.evaluate(time), size.evaluate(time))
    fun paintOrder(time: Long): Int = strokeOverFill.hold(time).toInt()
    fun rebase(delta: Long) = copy(
        source = source.copy(keyframes = FrozenList.of(source.keyframes.map { it.copy(timeUs = Math.subtractExact(it.timeUs, delta)) })),
        size = size.copy(keyframes = FrozenList.of(size.keyframes.map { it.copy(timeUs = Math.subtractExact(it.timeUs, delta)) })),
        fill = fill.rebase(delta), tracking = tracking.rebase(delta), leading = leading.rebase(delta),
        baselineShift = baselineShift.rebase(delta), strokeColour = strokeColour.rebase(delta),
        strokeWidth = strokeWidth.rebase(delta), strokeOverFill = strokeOverFill.rebase(delta),
        animators = FrozenList.of(animators.map { it.rebase(delta) }), ligatures = ligatures.shift(delta))
    fun sourceBytes(): Long = source.staticValue.toByteArray(Charsets.UTF_8).size.toLong() +
        (source.document?.storageBytes() ?: 0L) +
        source.keyframes.sumOf { it.value.toByteArray(Charsets.UTF_8).size.toLong() + (it.document?.storageBytes() ?: 0L) }
}

/** Hold sampling uses the same scalar track/storage, without interpolating discrete paint order. */
fun AnimatableProperty1D.hold(time: Long): Float = if (!isAnimated || keyframes.isEmpty()) staticValue
    else keyframes[PropertyTimeSearch.atOrBefore(keyframes, time) { it.timeUs }.coerceAtLeast(0)].value
private fun validatePaintOrder(value: Float) { require(value == 0f || value == 1f) { "Invalid stroke/fill order" } }
private fun AnimatableProperty1D.rebase(delta: Long) = copy(keyframes = FrozenList.of(keyframes.map {
    it.copy(timeUs = Math.subtractExact(it.timeUs, delta)) }))
private fun AnimatableColour.rebase(delta: Long) = copy(keyframes = FrozenList.of(keyframes.map {
    it.copy(timeUs = Math.subtractExact(it.timeUs, delta)) }))

/** 48 ppem / 8 spread. Stroke half-width and softness share this conservative distance budget. */
object TextSdfLimits {
    const val HALF_WIDTH_EM = .10f
    fun strokeWidth(requested: Float, size: Float): Float = requested.coerceIn(0f, 2f * HALF_WIDTH_EM * size)
    fun softness(requested: Float, size: Float, effectiveStroke: Float): Float =
        requested.coerceIn(0f, (HALF_WIDTH_EM * size - effectiveStroke * .5f).coerceAtLeast(0f))
    // Conservative source-space AA allowance; the raster shader also uses screen derivatives.
    fun aaPadding(size: Float): Float = size / 48f
}
object TextLimits {
    const val MIN_SIZE = 8f // Provisional until the user's small-size/Quarter-preview acceptance.
    const val MAX_SIZE = 1000f // SDF glyphs rasterise at a fixed 48 ppem, so display size never grows the atlas.
    const val MAX_SOURCE_UNITS = 4096
    const val MAX_DOCUMENT_BYTES = 1024L * 1024
    fun validateSize(value: Float) { require(value.isFinite() && value in MIN_SIZE..MAX_SIZE) { "Text size must be 8–1000px" } }
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
