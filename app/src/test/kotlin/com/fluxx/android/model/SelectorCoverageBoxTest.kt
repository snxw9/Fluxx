package com.fluxx.android.model

import org.junit.Assert.*
import org.junit.Test

/** TLP.1 part 6: the selector coverage box (coverage → one box; only the focused selector's own coverage counts). */
class SelectorCoverageBoxTest {
    /** Five clusters on one line, 10 units wide each, plus a second line under cluster 4. */
    private val boxes = floatArrayOf(
        0f, 0f, 10f, 20f,
        10f, 0f, 20f, 20f,
        20f, 0f, 30f, 20f,
        30f, 0f, 40f, 20f,
        0f, 30f, 10f, 50f)
    private fun box(vararg c: Double) = SelectorCoverageBox.box(c, boxes)?.toList()

    @Test fun contiguousCoverageWrapsTheCoveredCharacters() {
        assertEquals(listOf(10f, 0f, 30f, 20f), box(0.0, 1.0, 1.0, 0.0, 0.0))
        assertEquals(listOf(0f, 0f, 40f, 50f), box(1.0, 1.0, 1.0, 1.0, 1.0))
    }

    @Test fun rampedEdgesUseTheFivePercentThreshold() {
        assertEquals(.05, SelectorCoverageBox.THRESHOLD, 0.0)
        // A ramp: .04 is not covered, .05 is (the box starts at cluster 1 and ends at cluster 3).
        assertEquals(listOf(10f, 0f, 40f, 20f), box(.04, .05, .5, 1.0, .049))
        // Partial amounts are not drawn: a cluster at 6% and one at 100% give the same box edges as two at 100%.
        assertEquals(box(0.0, 1.0, 1.0, 0.0, 0.0), box(0.0, .06, 1.0, 0.0, 0.0))
        // A negative amount covers by magnitude.
        assertEquals(listOf(20f, 0f, 30f, 20f), box(0.0, 0.0, -.5, 0.0, 0.0))
    }

    @Test fun nonContiguousCoverageIsOneBoxFromTheFirstToTheLastCoveredCharacter() {
        assertEquals(listOf(0f, 0f, 40f, 20f), box(1.0, 0.0, 0.0, 1.0, 0.0))
        // First on line 1, last on line 2: one box over both lines.
        assertEquals(listOf(0f, 0f, 40f, 50f), box(0.0, 1.0, 0.0, 0.0, 1.0))
    }

    @Test fun nothingCoveredGivesNoBox() {
        assertNull(box(0.0, 0.0, 0.0, 0.0, 0.0))
        assertNull(box(.049, .01, 0.0, 0.0, 0.0))
        assertNull(SelectorCoverageBox.box(DoubleArray(0), FloatArray(0)))
    }

    // ---- The coverage source: the same selector engine, the focused selector alone -------------------------------
    private fun scalar(v: Float) = AnimatableProperty1D(staticValue = v)
    /** Two unligated characters, one line, 600-unit advances (the PreparedTextAnimationTest fixture). */
    private fun metrics(): DoubleArray = doubleArrayOf(1000.0, 800.0, -200.0, 0.0, 1.0, 2.0, 1200.0,
        0.0, 0.0, 0.0, 0.0, 700.0, 500.0, -700.0,
        0.0, 600.0, 0.0, 0.0, 700.0, 500.0, -700.0,
        2.0, 0.0, 1.0,
        271828.0, 2.0, 2.0, 1.0,
        0.0, 1.0, 0.0, 0.0, 0.0, 600.0, 1.0, 2.0, 0.0, 0.0, 600.0, 600.0,
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0)
    /** Selector 2 covers the second character only; selector 3 covers both and SUBTRACTS (the combination covers nothing). */
    private val animator = TextAnimator(1, selectors = FrozenList.of(listOf(
        TextRangeSelector(2, start = scalar(50f), end = scalar(100f)),
        TextRangeSelector(3, mode = scalar(SelectorMode.SUBTRACT.ordinal.toFloat())))),
        properties = FrozenList.of(listOf(TextPropertyOverride(AnimatorProperty.TRACKING, scalar = scalar(100f)))))
    private val text = TextProperties(size = scalar(100f), animators = FrozenList.of(listOf(animator)))
    private fun prepared() = PreparedTextAnimation(text, metrics(), SelectorEaseTable.shared).also { it.evaluate(0) }

    @Test fun onlyTheFocusedSelectorsOwnCoverageIsUsed() {
        val p = prepared()
        assertEquals(listOf(0.0, 1.0), p.selectorCoverage(animator, 2, 0)!!.toList())
        // Selector 3 alone (its Subtract mode is not applied against the others): both characters.
        assertEquals(listOf(1.0, 1.0), p.selectorCoverage(animator, 3, 0)!!.toList())
        assertNull(p.selectorCoverage(animator, 9, 0))
        // The box of selector 2 is the second character's box; of selector 3 both characters'.
        val b = p.clusterBoxes()
        assertEquals(listOf(b[4], b[5], b[6], b[7]), SelectorCoverageBox.box(p.selectorCoverage(animator, 2, 0)!!, b)!!.toList())
        assertEquals(listOf(b[0], minOf(b[1], b[5]), b[6], maxOf(b[3], b[7])), SelectorCoverageBox.box(p.selectorCoverage(animator, 3, 0)!!, b)!!.toList())
    }

    @Test fun readingCoverageChangesNothingTheRendererSees() {
        val p = prepared()
        val before = (0 until p.modifiers.limit() / 4).map { p.modifiers.getFloat(it * 4) }
        p.selectorCoverage(animator, 2, 0); p.selectorCoverage(animator, 3, 0)
        p.evaluate(0)
        assertEquals(before, (0 until p.modifiers.limit() / 4).map { p.modifiers.getFloat(it * 4) })
    }

    @Test fun layoutMetricsGiveTheBoxInLayoutSpaceOrNullWhenNothingIsCovered() {
        val m = TextLayoutMetrics.fromNative(metrics(), false)
        val box = m.selectorBox(text, 0, 1, 2)!!
        val b = prepared().clusterBoxes()
        assertEquals(b[4], box.left, 0f); assertEquals(b[6] - b[4], box.width, .001f)
        val none = text.copy(animators = FrozenList.of(listOf(animator.copy(selectors = FrozenList.of(listOf(
            TextRangeSelector(2, start = scalar(0f), end = scalar(0f))))))))
        assertNull(m.selectorBox(none, 0, 1, 2))
        assertNull("unknown animator", m.selectorBox(text, 0, 7, 2))
    }
}
