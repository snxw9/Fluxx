package com.fluxx.android.render

import com.fluxx.android.model.*

/**
 * TEXT PERF 11 multi-layer fixtures. The text is the F2 representative's line ("AV office gy 0123456789 ", 22
 * glyphs). [lines] = 1 builds ONE layer holding that line repeated [rows] times (the PERF 10 single-layer baseline);
 * otherwise [rows] separate layers each hold one line, stacked like the lines of the single layer. Same font, same
 * total glyphs, same per-glyph work: only the layer count differs.
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

    private fun scalar(v: Float) = AnimatableProperty1D(staticValue = v)
    private fun sizeTrack() = AnimatableProperty1D(true, 30f, FrozenList.of(listOf(
        Keyframe1D(0, 30f, EasingPreset.LINEAR), Keyframe1D(SIZE_SPAN_US, 300f, EasingPreset.LINEAR))))

    fun project(rows: Int, separateLayers: Boolean, kind: Kind): ProjectDocument {
        val source = if (separateLayers) LINE else (LINE + "\n").repeat(rows).dropLast(1)
        val base = when (kind) {
            Kind.PLAIN -> TextProperties(fontId = "fluxx.serif", source = AnimatableString(staticValue = source), size = scalar(40f))
            Kind.SIZE -> TextProperties(fontId = "fluxx.serif", source = AnimatableString(staticValue = source), size = sizeTrack())
            Kind.ANIMATED -> TextF2AcceptanceTest().fixture(false).composition.layers.single().text
                .copy(source = AnimatableString(staticValue = source))
        }
        val count = if (separateLayers) rows else 1
        val layers = List(count) { i ->
            val y = if (separateLayers) -0.9f + 1.8f * (i + .5f) / rows else 0f
            CompositionLayer(i + 1L, LayerType.TEXT, zOrder = i, timing = ClipTiming(durationUs = 12_000_000),
                transform = Transform(positionY = y), text = base)
        }
        return ProjectDocument(Composition(width = 1080, height = 1920, durationUs = 12_000_000, layers = FrozenList.of(layers)))
    }
}
