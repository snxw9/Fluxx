package com.fluxx.android.render

import android.content.Context
import com.fluxx.android.model.*

/**
 * TEXT PERF 11 multi-layer fixtures. The text is the F2 representative's line ("AV office gy 0123456789 ", 22
 * glyphs). `separateLayers = false` builds ONE layer holding that line repeated [rows] times (the PERF 10 single-layer
 * baseline); otherwise [rows] separate layers each hold one line, placed where the single layer draws its lines: row i
 * sits (i - (rows-1)/2) line heights from the centre, and when the size is keyframed the row position is keyframed
 * with the same times and easing. Same font, same glyphs, same sizes and the same covered area (no extra overdraw):
 * only the layer count differs.
 */
internal object TextMultiLayerFixture {
    const val LINE = "AV office gy 0123456789 "
    enum class Kind {
        /** Static 40 px, no animators. */
        PLAIN,
        /** The F2 representative text properties (8 animators × 4 selectors × 13 properties, size 8→72 px over 10 s). */
        ANIMATED,
        /** Font size keyframed 30 → 300 px over 3 s (linear), no animators. */
        SIZE,
    }
    /** Size-animation loop length; playback and scrubbing stay inside it so the tiers keep switching. */
    const val SIZE_SPAN_US = 3_000_000L
    private const val FONT = "fluxx.serif"

    private fun scalar(v: Float) = AnimatableProperty1D(staticValue = v)
    private fun sizeTrack() = AnimatableProperty1D(true, 30f, FrozenList.of(listOf(
        Keyframe1D(0, 30f, EasingPreset.LINEAR), Keyframe1D(SIZE_SPAN_US, 300f, EasingPreset.LINEAR))))

    /** Line height in em of the fixture font (ascent - descent + gap, as the native mesh spaces lines). */
    fun lineHeightEm(context: Context): Float = com.fluxx.android.engine.FontSession(context.assets).use { session ->
        val m = session.metrics(FONT, "AV", true)
        ((m[1] - m[2] + maxOf(0.0, m[3])) / m[0]).toFloat()
    }

    /** [stacked]: every separate layer at the centre (30 duplicates of one layer), as duplicating a layer leaves them. */
    fun project(context: Context, rows: Int, separateLayers: Boolean, kind: Kind, stacked: Boolean = false, font: TextFontRef? = null): ProjectDocument {
        val source = if (separateLayers) LINE else (LINE + "\n").repeat(rows).dropLast(1)
        val string = AnimatableString(staticValue = source, document = font?.let { f -> TextSourceDocument(source).format(0, 0) { it.copy(font = f) } })
        val base = when (kind) {
            Kind.PLAIN -> TextProperties(fontId = FONT, source = string, size = scalar(40f))
            Kind.SIZE -> TextProperties(fontId = FONT, source = string, size = sizeTrack())
            Kind.ANIMATED -> TextF2AcceptanceTest().fixture(false).composition.layers.single().text
                .copy(source = AnimatableString(staticValue = source))
        }
        val height = 1920
        val em = lineHeightEm(context)
        fun ndc(row: Int, size: Float) = (row - (rows - 1) / 2f) * em * size * 2f / height
        val count = if (separateLayers) rows else 1
        val layers = List(count) { i ->
            val position = if (!separateLayers || stacked) AnimatableProperty2D(staticX = 0f, staticY = 0f) else when (kind) {
                Kind.PLAIN -> AnimatableProperty2D(staticX = 0f, staticY = ndc(i, 40f))
                // Same keyframe times and easing as the size track, so each row follows its line in the single layer.
                Kind.SIZE -> AnimatableProperty2D(true, 0f, ndc(i, 30f), FrozenList.of(listOf(
                    Keyframe2D(0, 0f, ndc(i, 30f), EasingPreset.LINEAR), Keyframe2D(SIZE_SPAN_US, 0f, ndc(i, 300f), EasingPreset.LINEAR))))
                Kind.ANIMATED -> AnimatableProperty2D(true, 0f, ndc(i, 8f), FrozenList.of(listOf(
                    Keyframe2D(0, 0f, ndc(i, 8f), EasingPreset.LINEAR), Keyframe2D(10_000_000, 0f, ndc(i, 72f), EasingPreset.EASY_EASE))))
            }
            CompositionLayer(i + 1L, LayerType.TEXT, zOrder = i, timing = ClipTiming(durationUs = 12_000_000),
                transform = Transform(positionY = position.staticY), animTransform = AnimatableTransform(position = position), text = base)
        }
        return ProjectDocument(Composition(width = 1080, height = height, durationUs = 12_000_000, layers = FrozenList.of(layers)))
    }
}
