package com.fluxx.android.model

import org.junit.Assert.*
import org.junit.Test

/**
 * TEXT F3.3 §5: animated (animator) tracking on box / paragraph text spreads from each wrapped line's alignment point,
 * like the Character tab's static tracking: centred lines grow ±total/2 about their centre, left lines grow right,
 * right lines grow left. Measured on the visible (ink) glyphs of each line; a trailing space at a wrap stays outside.
 */
class TextF33TrackingBoxTest {
    private fun scalar(v: Float) = AnimatableProperty1D(staticValue = v)
    /** Synthetic single-glyph clusters, 600-unit advances (the golden fixture's metric layout). */
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
    private val source = "AV office gy 0123 AV office gy"

    private class Laid(val x: FloatArray, val lines: IntArray)
    private fun lay(alignment: ParagraphAlignment, tracking: Float, staticTracking: Float = 0f, time: Long = 0): Laid {
        val document = TextSourceDocument(source).copy(boxWidth = 500f, paragraphs = FrozenList.of(listOf(TextParagraphStyle(alignment = alignment))))
        val animators = if (tracking == 0f) FrozenList.empty() else FrozenList.of(listOf(TextAnimator(1,
            selectors = FrozenList.of(listOf(TextRangeSelector(2))),
            properties = FrozenList.of(listOf(TextPropertyOverride(AnimatorProperty.TRACKING, scalar = scalar(tracking)))))))
        val text = TextProperties(source = AnimatableString(staticValue = source, document = document), size = scalar(100f),
            tracking = scalar(staticTracking), animators = animators)
        val a = PreparedTextAnimation(text, metrics(source.length), SelectorEaseTable.shared, document,
            breaks = IntArray(source.length + 1) { it }, paragraphKernel = ParagraphOracle)
        a.evaluate(time)
        // Glyph i is cluster i; its laid-out visual box (left edge) after the animators.
        val g = a.geometrySnapshot()
        return Laid(FloatArray(source.length) { g.boxes[it * 4] }, g.lines)
    }
    /** Index ranges of the visible glyphs of each wrapped line. */
    private fun inkLines(lines: IntArray): List<IntRange> = lines.indices.groupBy { lines[it] }.values.map { line ->
        val ink = line.filter { source[it] != ' ' }; ink.first()..ink.last() }

    @Test fun wrappedCentredBoxSpreadsFromEachLinesCentre() {
        for (amount in listOf(100f, -60f)) {
            val plain = lay(ParagraphAlignment.CENTRE, 0f); val tracked = lay(ParagraphAlignment.CENTRE, amount)
            assertArrayEquals("animator tracking never re-wraps", plain.lines, tracked.lines)
            val lines = inkLines(plain.lines)
            assertTrue("the fixture wraps (${lines.size} lines)", lines.size >= 3)
            for (r in lines) {
                val first = tracked.x[r.first] - plain.x[r.first]; val last = tracked.x[r.last] - plain.x[r.last]
                val total = (r.last - r.first) * amount / 10f // amount/1000 em at size 100 per visible boundary
                assertEquals("$amount line $r grows by the visible boundaries", total, last - first, 1e-3f)
                assertEquals("$amount line $r is symmetric about its centre", -first, last, 1e-3f)
            }
        }
    }

    @Test fun wrappedLeftAndRightBoxesGrowAwayFromTheirEdge() {
        for ((alignment, align) in listOf(ParagraphAlignment.LEFT to 0f, ParagraphAlignment.RIGHT to 1f, ParagraphAlignment.CENTRE to .5f)) {
            val plain = lay(alignment, 0f); val tracked = lay(alignment, 100f)
            for (r in inkLines(plain.lines)) {
                val total = (r.last - r.first) * 10f
                assertEquals("$alignment $r first glyph", -total * align, tracked.x[r.first] - plain.x[r.first], 1e-3f)
                assertEquals("$alignment $r last glyph", total * (1 - align), tracked.x[r.last] - plain.x[r.last], 1e-3f)
            }
        }
    }

    @Test fun animatedMatchesStaticTrackingInTheBox() {
        // Static tracking of the same amount (no re-wrap at this width) lands the visible glyphs in the same places.
        for (alignment in listOf(ParagraphAlignment.LEFT, ParagraphAlignment.CENTRE, ParagraphAlignment.RIGHT)) {
            val plain = lay(alignment, 0f); val static = lay(alignment, 0f, staticTracking = 50f); val animated = lay(alignment, 50f)
            if (!plain.lines.contentEquals(static.lines)) continue
            for (r in inkLines(plain.lines)) for (c in r) if (source[c] != ' ') {
                // Moves relative to the plain layout.
                val staticMove = static.x[c] - plain.x[c]; val animatedMove = animated.x[c] - plain.x[c]
                assertEquals("$alignment glyph $c", staticMove, animatedMove, 1e-3f)
            }
        }
    }
}
