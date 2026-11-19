package com.fluxx.android.model

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/**
 * TEXT PERF 10: the evaluation refactor (split into JIT-sized methods, bulk record writes, colour tables, hoisted
 * trigonometry) must be bit-identical. The hashes below were recorded from the TEXT FIX 9 implementation (commit
 * 9fd4499) before any change: every output a frame consumes (modifier records, logical/ink/visual bounds, clamp
 * labels) for the F2 animator stacks (plain, rich runs, wrapped paragraphs, wiggly/advanced selectors) at fixed times.
 */
class PreparedTextAnimationGoldenTest {
    private fun scalar(v: Float) = AnimatableProperty1D(staticValue = v)
    private fun track(a: Float, b: Float) = AnimatableProperty1D(true, a, FrozenList.of(listOf(
        Keyframe1D(0, a, EasingPreset.LINEAR), Keyframe1D(10_000_000, b, EasingPreset.EASY_EASE))))
    private fun metrics(count: Int): DoubleArray {
        val out = ArrayList<Double>()
        out.addAll(listOf(1000.0, 800.0, -200.0, 0.0, 1.0, count.toDouble(), count * 600.0))
        for (i in 0 until count) out.addAll(listOf(0.0, i * 600.0, 0.0, 0.0, 700.0, 500.0, -700.0))
        out.add(count.toDouble()); for (i in 0 until count) out.add(i.toDouble())
        out.addAll(listOf(271828.0, count.toDouble(), count.toDouble(), 1.0))
        for (i in 0 until count) out.addAll(listOf(i.toDouble(), i + 1.0, 0.0, 0.0, i * 600.0, 600.0))
        for (i in 0 until count) out.addAll(listOf(0.0, 0.0, i.toDouble(), 0.0))
        for (i in 0 until count) out.add(i.toDouble())
        return out.toDoubleArray()
    }
    /** The TEXT FIX 9d F2 fixture's animator stack (TextF2AcceptanceTest.fixture), on synthetic single-line metrics. */
    private fun text(source: String, rich: Boolean, wrapped: Boolean, advanced: Boolean): Pair<TextProperties, TextSourceDocument> {
        val count = 8; val selectors = 4
        val animators = List(count) { a -> TextAnimator(a + 1L, anchorGrouping = scalar((a % 4).toFloat()),
            selectors = FrozenList.of(List(selectors) { s -> TextRangeSelector(100L + a * 8 + s,
                start = track(0f, 10f), end = track(100f, 90f), offset = track(-5f, 5f),
                basedOn = scalar((s % 4).toFloat()), shape = scalar((s % 6).toFloat()), mode = scalar((s % 6).toFloat()),
                easeLow = track(-30f, 30f), easeHigh = track(30f, -30f), randomizeOrder = scalar(1f), seed = 0x12345678L + a * 8 + s,
                kind = scalar(if (advanced && s % 2 == 1) 1f else 0f), lockDimensions = scalar(if (advanced && s % 3 == 0) 0f else 1f),
                correlation = track(0f, 100f), temporalPhase = track(0f, 180f), spatialPhase = track(0f, 90f),
                seedKeyframes = if (advanced) FrozenList.of(listOf(TextSeedKeyframe(0, 0x12345678L + a * 8 + s),
                    TextSeedKeyframe(6_000_000, 0x76543210L + a * 8 + s))) else FrozenList.empty()) }),
            properties = FrozenList.of(AnimatorProperty.entries.take(if (advanced) 23 else 13).map { p ->
                when (p.kind) {
                    2 -> TextPropertyOverride(p, vector = if (p == AnimatorProperty.SCALE) AnimatableProperty2D(staticX = 101f, staticY = 99f)
                        else AnimatableProperty2D(staticX = 1f, staticY = -1f))
                    3 -> TextPropertyOverride(p, colour = AnimatableColour(staticValue = if (p == AnimatorProperty.FILL) 0x80ff8800.toInt() else 0x800080ff.toInt()))
                    else -> TextPropertyOverride(p, scalar = when (p) {
                        AnimatorProperty.OPACITY, AnimatorProperty.FILL_OPACITY, AnimatorProperty.STROKE_OPACITY -> track(100f, 98f)
                        AnimatorProperty.LINE_ANCHOR -> track(0f, 100f)
                        AnimatorProperty.SKEW_AXIS -> track(0f, 90f)
                        AnimatorProperty.TRACKING -> track(-5f, 5f)
                        AnimatorProperty.STROKE_WIDTH -> track(0f, .1f)
                        AnimatorProperty.BLUR -> track(0f, .1f)
                        AnimatorProperty.CHARACTER_OFFSET -> track(-1f, 1f)
                        AnimatorProperty.CHARACTER_VALUE -> track(65f, 90f)
                        else -> track(-1f, 1f)
                    })
                }
            })) }
        var document = TextSourceDocument(source)
        if (rich) {
            val boundaries = document.boundaries
            for (part in 0 until 6) {
                val start = boundaries[part * boundaries.lastIndex / 6]; val end = boundaries[(part + 1) * boundaries.lastIndex / 6]
                document = document.format(start, end) { it.copy(sizeFactor = 1f + part * .02f, horizontalScale = 90f + part, verticalScale = 110f - part,
                    fauxBold = part % 2 == 0, fauxItalic = part % 3 == 0, trackingDelta = part.toFloat(), baselineDelta = (part % 3).toFloat(), strokeDelta = .1f,
                    fill = if (part % 2 == 0) 0x80ff4400.toInt() else null, stroke = if (part % 2 != 0) 0x800088ff.toInt() else null) }
            }
        }
        if (wrapped) document = document.copy(boxWidth = 2000f, paragraphs = FrozenList.of(listOf(TextParagraphStyle(
            alignment = ParagraphAlignment.entries[4], indentLeft = 4f, indentRight = 3f, indentFirst = 5f, spaceBefore = 2f, spaceAfter = 3f))))
        val text = TextProperties(fontId = "fluxx.serif", source = AnimatableString(staticValue = source, document = document),
            size = track(8f, 72f), tracking = track(-100f, 100f), strokeWidth = track(20f, 0f), strokeOverFill = track(0f, 1f),
            ligatures = track(1f, 0f), animators = FrozenList.of(animators))
        return text to document
    }
    private fun digest(rich: Boolean, wrapped: Boolean, advanced: Boolean): String {
        val source = "AV office gy 0123456789 AV office gy"
        val (text, document) = text(source, rich, wrapped, advanced)
        val prepared = PreparedTextAnimation(text, metrics(source.length), SelectorEaseTable.shared, document,
            breaks = IntArray(source.length + 1) { it }, paragraphKernel = ParagraphOracle)
        val sha = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(prepared.modifiers.capacity())
        for (time in longArrayOf(0, 16_667, 1_234_567, 2_500_000, 5_000_000, 6_000_000, 7_777_777, 9_999_999, 10_000_000, 12_000_000, 5_000_000)) {
            prepared.evaluate(time)
            prepared.modifiers.duplicate().also { it.position(0) }.get(bytes); sha.update(bytes)
            // TEXT PERF 10: tier inputs read from the staged records equal the TEXT FIX 9 buffer scan.
            assertEquals(com.fluxx.android.render.SdfAtlasTiers.maxGlyphScale(prepared.modifiers).toRawBits(), prepared.maxGlyphScale().toRawBits())
            assertEquals(com.fluxx.android.render.SdfAtlasTiers.maxModifierDistanceEm(prepared.modifiers).toRawBits(), prepared.maxModifierDistanceEm().toRawBits())
            for (v in prepared.logicalBounds + prepared.inkBounds) sha.update(java.lang.Float.floatToRawIntBits(v).toString().toByteArray())
            val geometry = prepared.geometrySnapshot()
            sha.update((geometry.starts.contentToString() + geometry.ends.contentToString() + geometry.lines.contentToString() +
                geometry.words.contentToString()).toByteArray())
            for (v in geometry.boxes + geometry.origins + geometry.logical) sha.update(java.lang.Float.floatToRawIntBits(v).toString().toByteArray())
            sha.update((prepared.strokeClampLabel(0, source.length) ?: "-").toByteArray())
        }
        return sha.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /**
     * TEXT PERF 11 TrackMemo: duplicated layers share memo slots. Three layers per stack on ONE memo, evaluated
     * interleaved (the second at a different local time, as layers with different keyframe anchors are, with repeated
     * and backward times), must produce byte-identical records, bounds and geometry to the same layers on private
     * memos. Equal tracks built separately share a slot; a layer whose start track differs does not share that one.
     */
    @Test fun sharedTrackMemoIsBitIdenticalAcrossLayers() {
        val source = "AV office gy 0123456789 AV office gy"
        for ((rich, wrapped, advanced) in listOf(Triple(false, false, false), Triple(true, false, false), Triple(true, true, false), Triple(true, true, true))) {
            fun build(memo: TrackMemo, variant: Boolean): PreparedTextAnimation {
                val (base, document) = text(source, rich, wrapped, advanced)
                val text = if (!variant) base else base.copy(animators = FrozenList.of(base.animators.mapIndexed { a, animator ->
                    if (a != 0) animator else animator.copy(selectors = FrozenList.of(animator.selectors.mapIndexed { s, selector ->
                        if (s == 0) selector.copy(start = track(0f, 11f)) else selector })) }))
                return PreparedTextAnimation(text, metrics(source.length), SelectorEaseTable.shared, document,
                    breaks = IntArray(source.length + 1) { it }, paragraphKernel = ParagraphOracle, memo = memo)
            }
            val shared = TrackMemo()
            val layers = listOf(build(shared, false), build(shared, false), build(shared, true))
            val alone = listOf(build(TrackMemo(), false), build(TrackMemo(), false), build(TrackMemo(), true))
            val sharedSlots = shared.slots
            val privateSlots = TrackMemo().also { m -> build(m, false) }.slots
            assertTrue("equal tracks share slots ($sharedSlots vs $privateSlots)", sharedSlots < privateSlots * 3)
            assertTrue("the changed start track is its own slot", sharedSlots > privateSlots)
            fun snapshot(p: PreparedTextAnimation): String {
                val bytes = ByteArray(p.modifiers.capacity()); p.modifiers.duplicate().also { it.position(0) }.get(bytes)
                val g = p.geometrySnapshot()
                return bytes.contentToString() + (p.logicalBounds + p.inkBounds + g.boxes + g.origins + g.logical)
                    .joinToString(",") { java.lang.Float.floatToRawIntBits(it).toString() } + g.lines.contentToString()
            }
            for (time in longArrayOf(0, 16_667, 1_234_567, 5_000_000, 5_000_000, 2_500_000, 6_000_000, 9_999_999, 12_000_000, 16_667)) {
                val times = longArrayOf(time, time + 33_333, time)
                for (i in layers.indices) { layers[i].evaluate(times[i]); alone[i].evaluate(times[i]) }
                for (i in layers.indices) assertEquals("stack rich=$rich wrapped=$wrapped advanced=$advanced layer $i at ${times[i]}",
                    snapshot(alone[i]), snapshot(layers[i]))
            }
        }
    }
    @Test fun evaluationIsBitIdenticalToTextFix9() {
        val actual = listOf(digest(false, false, false), digest(true, false, false), digest(true, true, false), digest(true, true, true))
        assertEquals(GOLDEN, actual)
    }
    private companion object {
        val GOLDEN = listOf(
            "c308f9b724765c33671be915a3c8de17536aad4523e15fa417b96ed8cc7a84eb", // plain
            "2c87f9bde24754484c95ca60873394efe72b5f0207c17ad5575b38aac4580607", // rich runs
            "e1ed5d2cf4554777fe34f17d039b5fd217caf402411966c0c14d7637a548214b", // wrapped paragraphs
            "1a028c140311de9350cae605b2fbd72e852bf88ca4feb7def7cbc4779b31a9ed") // wiggly/advanced
    }
}
