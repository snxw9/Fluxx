package com.fluxx.android.model

import org.junit.Assert.*
import org.junit.Test

/**
 * TEXT F3.2 engine parity: Ramp Up / Ramp Down coverage outside Start..End (AE), and the interpolated Random Seed
 * (flicker). Every recipe is the user's After Effects recipe replayed through [PreparedSelectorEngine], frame by frame.
 */
class SelectorParityF32Test {
    private val ease = SelectorEaseTable.shared
    private fun p(v: Float) = AnimatableProperty1D(staticValue = v)
    private fun keyed(a: Float, b: Float) = AnimatableProperty1D(true, a, FrozenList.of(listOf(
        Keyframe1D(0, a, EasingPreset.LINEAR), Keyframe1D(1_000_000, b, EasingPreset.LINEAR))))
    private fun membership(n: Int) = SelectorMembership(Array(4) { IntArray(n) { it } }, IntArray(4) { n }, IntArray(n) { it }, IntArray(n) { it + 1 })
    private fun engine(s: TextRangeSelector, n: Int) = PreparedSelectorEngine(TextAnimator(1, selectors = FrozenList.of(listOf(s))), membership(n), ease)
    private val frames = 30
    private fun time(f: Int) = f * 1_000_000L / frames
    private fun weights(engine: PreparedSelectorEngine, n: Int) = List(frames + 1) { f -> DoubleArray(n).also { engine.evaluate(time(f), it) } }

    /** Fade up recipe: Offset −100% → 100% over 1 s, Ease High 25 / Ease Low 100 (both directions share coverage). */
    private fun fadeUp(shape: SelectorShape, high: Float = 25f, low: Float = 100f) =
        TextRangeSelector(2, offset = keyed(-100f, 100f), shape = p(shape.ordinal.toFloat()), easeHigh = p(high), easeLow = p(low))

    @Test fun rampUpFadeUpHidesEverythingThenReleasesInReadingOrder() {
        val n = 12
        val w = weights(engine(fadeUp(SelectorShape.RAMP_UP), n), n)
        // First frame (Offset −100): every character fully selected (hidden: Opacity 0, displaced by Position 100).
        w[0].forEach { assertEquals(1.0, it, 1e-12) }
        // Last frame (Offset 100): every character released.
        w[frames].forEach { assertEquals(0.0, it, 1e-12) }
        for (c in 0 until n) for (f in 1..frames) assertTrue("char $c frame $f falls monotonically", w[f][c] <= w[f - 1][c] + 1e-12)
        // Reading order: at every frame an earlier character is never more selected than a later one.
        for (f in 0..frames) for (c in 1 until n) assertTrue("frame $f order", w[f][c - 1] <= w[f][c] + 1e-12)
        // Quarter (Offset −50) and middle (Offset 0): partial release, first characters release first.
        val quarter = w[frames / 4 + 1]; val middle = w[frames / 2]
        assertTrue(quarter[0] < 1.0 && quarter[n - 1] == 1.0)
        assertTrue(middle[0] < quarter[0] && middle[0] < middle[n - 1])
        // Middle frame, Offset 0: range 0..n, so every character is inside the ramp (none past End).
        assertTrue(middle.all { it > 0.0 && it < 1.0 })
    }

    @Test fun rampDownIsTheMirror() {
        val n = 12
        val w = weights(engine(fadeUp(SelectorShape.RAMP_DOWN), n), n)
        w[0].forEach { assertEquals(0.0, it, 1e-12) } // Offset −100: everything after End, unselected.
        w[frames].forEach { assertEquals(1.0, it, 1e-12) } // Offset 100: everything before Start, fully selected.
        for (c in 0 until n) for (f in 1..frames) assertTrue("char $c frame $f rises monotonically", w[f][c] >= w[f - 1][c] - 1e-12)
        for (f in 0..frames) for (c in 1 until n) assertTrue("frame $f order", w[f][c - 1] >= w[f][c] - 1e-12)
        val middle = w[frames / 2]
        assertTrue(middle.all { it > 0.0 && it < 1.0 })
    }

    @Test fun bothDirectionsShareTheCoverage() {
        // Right-to-left differs only in the Position vector (X 100, Y 0); the selector, and so every weight, is identical.
        val n = 9
        for (shape in listOf(SelectorShape.RAMP_UP, SelectorShape.RAMP_DOWN)) {
            val a = weights(engine(fadeUp(shape), n), n); val b = weights(engine(fadeUp(shape), n), n)
            for (f in 0..frames) assertArrayEquals(a[f], b[f], 0.0)
        }
    }

    @Test fun easeHighLowAreAcceptedAndChangeTheCurveMonotonically() {
        val n = 12
        // Validation accepts the full AE ease range.
        TextRangeSelector(3, easeHigh = p(-100f), easeLow = p(100f)); TextRangeSelector(3, easeHigh = p(100f), easeLow = p(-100f))
        assertThrows(IllegalArgumentException::class.java) { TextRangeSelector(3, easeHigh = p(101f)) }
        for (shape in listOf(SelectorShape.RAMP_UP, SelectorShape.RAMP_DOWN)) {
            val eased = weights(engine(fadeUp(shape), n), n); val linear = weights(engine(fadeUp(shape, 0f, 0f), n), n)
            assertTrue("$shape eased differs from linear", (0..frames).any { f -> (0 until n).any { c -> kotlin.math.abs(eased[f][c] - linear[f][c]) > 1e-3 } })
            // Both share the end states.
            assertArrayEquals(linear[0], eased[0], 1e-12); assertArrayEquals(linear[frames], eased[frames], 1e-12)
            for (c in 0 until n) for (f in 1..frames) {
                if (shape == SelectorShape.RAMP_UP) assertTrue(eased[f][c] <= eased[f - 1][c] + 1e-12) else assertTrue(eased[f][c] >= eased[f - 1][c] - 1e-12)
            }
        }
    }

    @Test fun offsetUnitsAndBasedOnResolveBeforeTheExtension() {
        // Index units: Start 2, End 4 on 8 characters. Ramp Up selects 4..7 fully, Ramp Down 0..1 fully.
        val up = DoubleArray(8).also { engine(TextRangeSelector(4, start = p(2f), end = p(4f), units = p(1f), shape = p(1f)), 8).evaluate(0, it) }
        assertArrayEquals(doubleArrayOf(0.0, 0.0, .25, .75, 1.0, 1.0, 1.0, 1.0), up, 1e-12)
        val down = DoubleArray(8).also { engine(TextRangeSelector(4, start = p(2f), end = p(4f), units = p(1f), shape = p(2f)), 8).evaluate(0, it) }
        assertArrayEquals(doubleArrayOf(1.0, 1.0, .75, .25, 0.0, 0.0, 0.0, 0.0), down, 1e-12)
        // Offset 2 (index units) shifts the extension with the range.
        val shifted = DoubleArray(8).also { engine(TextRangeSelector(4, start = p(2f), end = p(4f), offset = p(2f), units = p(1f), shape = p(1f)), 8).evaluate(0, it) }
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 0.0, 0.0, .25, .75, 1.0, 1.0), shifted, 1e-12)
        // Amount and Max amount scale the extended region like the ramp itself.
        val half = DoubleArray(8).also { engine(TextRangeSelector(4, start = p(2f), end = p(4f), units = p(1f), shape = p(1f), amount = p(50f)), 8).evaluate(0, it) }
        assertEquals(.5, half[7], 1e-12)
        // Based on Words: members are words, the extension follows the word index.
        val maps = arrayOf(intArrayOf(0, 1, 2, 3), intArrayOf(0, 1, 2, 3), intArrayOf(0, 0, 1, 1), intArrayOf(0, 0, 0, 0))
        val m = SelectorMembership(maps, intArrayOf(4, 4, 2, 1), intArrayOf(0, 1, 2, 3), intArrayOf(1, 2, 3, 4))
        val words = DoubleArray(4).also { PreparedSelectorEngine(TextAnimator(1, selectors = FrozenList.of(listOf(
            TextRangeSelector(5, start = p(0f), end = p(50f), basedOn = p(2f), shape = p(1f))))), m, ease).evaluate(0, it) }
        assertArrayEquals(doubleArrayOf(.5, .5, 1.0, 1.0), words, 1e-12)
    }

    @Test fun nonRampShapesStayZeroOutsideTheRange() {
        for (shape in listOf(SelectorShape.SQUARE, SelectorShape.TRIANGLE, SelectorShape.ROUND, SelectorShape.SMOOTH)) {
            val out = DoubleArray(8)
            engine(TextRangeSelector(6, start = p(2f), end = p(4f), units = p(1f), shape = p(shape.ordinal.toFloat()), smoothness = p(0f)), 8).evaluate(0, out)
            for (c in listOf(0, 1, 4, 5, 6, 7)) assertEquals("$shape char $c", 0.0, out[c], 0.0)
        }
        // A zero-width range stays empty for non-ramps and becomes a step for ramps.
        val empty = DoubleArray(4).also { engine(TextRangeSelector(7, start = p(50f), end = p(50f)), 4).evaluate(0, it) }
        assertArrayEquals(DoubleArray(4), empty, 0.0)
        val step = DoubleArray(4).also { engine(TextRangeSelector(7, start = p(50f), end = p(50f), shape = p(1f)), 4).evaluate(0, it) }
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 1.0, 1.0), step, 0.0)
    }

    @Test fun selectorModesCombineOnTheExtendedCoverage() {
        // Ramp Up (Add) then a Square 0..50% Subtract: the subtract removes the first half, the extension survives on the rest.
        val a = TextAnimator(1, selectors = FrozenList.of(listOf(
            TextRangeSelector(8, start = p(0f), end = p(0f), shape = p(1f)),
            TextRangeSelector(9, start = p(0f), end = p(50f), mode = p(1f), smoothness = p(0f)))))
        val out = DoubleArray(4); PreparedSelectorEngine(a, membership(4), ease).evaluate(0, out)
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 1.0, 1.0), out, 1e-12)
    }

    // ---- Random Seed interpolation (flicker) -------------------------------------------------------------------
    private fun seedKeys(to: Long) = FrozenList.of(listOf(TextSeedKeyframe(0, 0), TextSeedKeyframe(1_000_000, to)))

    @Test fun seedInterpolatesAndRoundsBetweenKeys() {
        val s = TextRangeSelector(10, seedKeyframes = seedKeys(25))
        assertEquals(0L, s.seedAt(-5)); assertEquals(0L, s.seedAt(0))
        assertEquals(25L, s.seedAt(1_000_000)); assertEquals(25L, s.seedAt(9_000_000))
        assertEquals(13L, s.seedAt(500_000)) // 12.5 rounds half up
        assertEquals(1L, s.seedAt(20_000)) // 0.5
        assertEquals(0L, s.seedAt(19_999))
        val down = TextRangeSelector(10, seedKeyframes = FrozenList.of(listOf(TextSeedKeyframe(0, 25), TextSeedKeyframe(1_000_000, 0))))
        assertEquals(13L, down.seedAt(500_000)); assertEquals(0L, down.seedAt(1_000_000))
        // A static seed (no keys) never moves.
        assertEquals(7L, TextRangeSelector(10, seed = 7).seedAt(123_456))
        // Unsigned high seeds next to each other interpolate without overflow.
        val high = TextRangeSelector(10, seedKeyframes = FrozenList.of(listOf(TextSeedKeyframe(0, -2), TextSeedKeyframe(1_000_000, -1))))
        assertEquals(-1L, high.seedAt(600_000))
        assertEquals(2L, TextSeedRange.reachable(high))
    }

    @Test fun reachableSeedsCountTheRamp() {
        for (to in listOf(25L, 150L, 200L)) assertEquals(to + 1, TextSeedRange.reachable(TextRangeSelector(11, seedKeyframes = seedKeys(to))))
        // Overlapping segments count once: 0→200→100 reaches 0..200.
        val back = TextRangeSelector(11, seedKeyframes = FrozenList.of(listOf(TextSeedKeyframe(0, 0), TextSeedKeyframe(1, 200), TextSeedKeyframe(2, 100))))
        assertEquals(201L, TextSeedRange.reachable(back))
        assertEquals(1L, TextSeedRange.reachable(TextRangeSelector(11, seed = 99)))
        assertNull(TextSeedRange.problem(TextProperties(animators = FrozenList.of(listOf(TextAnimator(1, selectors = FrozenList.of(listOf(
            TextRangeSelector(11, seedKeyframes = seedKeys(255))))))))))
        assertNotNull(TextSeedRange.problem(TextProperties(animators = FrozenList.of(listOf(TextAnimator(1, selectors = FrozenList.of(listOf(
            TextRangeSelector(11, seedKeyframes = seedKeys(256))))))))))
        // The cap is 256 for a typical layer and shrinks only when one seed's permutations would not fit 32 MiB.
        assertEquals(256, TextSeedRange.cap(4L * 4 * 720)); assertEquals(2, TextSeedRange.cap(LinePermutationPool.MAX_BYTES / 2))
    }

    /** The user's flicker recipe: Opacity 0%, Start 0→100% over 1 s, Randomize Order, Seed 0→N, Smoothness 0, Square. */
    private fun flicker(to: Long?) = TextRangeSelector(12, start = keyed(0f, 100f), randomizeOrder = p(1f), smoothness = p(0f),
        seedKeyframes = if (to == null) FrozenList.empty() else seedKeys(to))
    private fun selected(w: DoubleArray) = w.indices.filter { w[it] > .5 }.toSet()

    @Test fun flickerRecipeReshufflesEveryFrame() {
        val n = 24
        for (to in listOf(25L, 150L, 200L)) {
            val w = weights(engine(flicker(to), n), n)
            assertEquals("seed 0→$to: all hidden at the start", (0 until n).toSet(), selected(w[0]))
            assertTrue("seed 0→$to: all visible at the end", selected(w[frames]).isEmpty())
            val s = flicker(to)
            var reHidden = 0
            for (f in 1 until frames) {
                val a = selected(w[f - 1]); val b = selected(w[f])
                if (s.seedAt(time(f)) != s.seedAt(time(f - 1)) && b.isNotEmpty() && a.size < n) assertNotEquals("seed 0→$to frame $f", a, b)
                if (!a.containsAll(b)) reHidden++ // a revealed character hidden again: the flicker
            }
            assertTrue("seed 0→$to flickers ($reHidden frames)", reHidden >= frames / 3)
        }
    }

    @Test fun staticSeedKeepsAStableOrder() {
        val n = 24
        val w = weights(engine(flicker(null), n), n)
        assertEquals((0 until n).toSet(), selected(w[0])); assertTrue(selected(w[frames]).isEmpty())
        for (f in 1..frames) assertTrue("frame $f only reveals", selected(w[f - 1]).containsAll(selected(w[f])))
    }

    @Test fun overCapDocumentsPrepareClampedWithoutThrowing() {
        val s = TextRangeSelector(13, randomizeOrder = p(1f), seedKeyframes = FrozenList.of(listOf(
            TextSeedKeyframe(0, 5), TextSeedKeyframe(1_000_000, 0x7654_3210L))))
        val engine = engine(s, 16)
        assertTrue(engine.seedsClamped)
        val out = DoubleArray(16)
        for (f in 0..frames) { engine.evaluate(time(f), out); assertTrue(out.all { it.isFinite() }) }
        // The keyed seeds are kept, so both ends render exactly as their keys.
        val exact = DoubleArray(16).also { engine(TextRangeSelector(13, randomizeOrder = p(1f), seed = 0x7654_3210L, start = p(0f), end = p(50f)), 16).evaluate(0, it) }
        val clamped = DoubleArray(16).also { engine(s.copy(end = p(50f)), 16).evaluate(1_000_000, it) }
        assertArrayEquals(exact, clamped, 0.0)
        // A 0→200 ramp is within the cap.
        assertFalse(engine(flicker(200), 720).seedsClamped)
    }

    @Test fun flickerFrameCostOn720Glyphs() {
        val n = 720
        val began = System.nanoTime()
        val engine = engine(flicker(200), n)
        val prepareMs = (System.nanoTime() - began) / 1e6
        val out = DoubleArray(n)
        repeat(300) { engine.evaluate(time(it % (frames + 1)), out) } // warm-up
        val t0 = System.nanoTime(); val count = 3000
        for (i in 0 until count) engine.evaluate(time(i % (frames + 1)) + (i / (frames + 1)), out)
        val perFrameUs = (System.nanoTime() - t0) / 1e3 / count
        println("F3.2 flicker 0→200, 720 glyphs (JVM): prepare $prepareMs ms, evaluate $perFrameUs µs/frame")
        assertTrue("per-frame selector cost $perFrameUs µs", perFrameUs < 2_000)
        // Cached permutations per distinct seed: the per-frame reshuffle allocates nothing.
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        for (i in 0 until 600) engine.evaluate(time(i % (frames + 1)) + i, out)
        assertEquals("bytes allocated over 600 flicker frames", 0L, bean.getThreadAllocatedBytes(id) - before)
    }
}
