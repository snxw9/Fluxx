package com.fluxx.android.editor

import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test

/** TL3 B4: the colour bar samples the evaluator; "add keyframe here" keeps the look (exact easing split). */
class Tl3ColourBarTest {
    private val rate = FrameRate(30, 1)
    private fun f(n: Long) = rate.frameTimeUs(n)
    private val fill = GraphTarget(AnimPropertyType.FILL_COLOUR)

    private fun layer(curve: EasingCurve) = CompositionLayer(1, LayerType.TEXT, timing = ClipTiming(0, 0, f(120)), text = TextProperties(
        fill = AnimatableColour(true, keyframes = FrozenList.of(listOf(
            ColourKeyframe(f(0), 0xFFFF0000.toInt()).withCurve(curve), ColourKeyframe(f(60), 0x8000FFFF.toInt()), ColourKeyframe(f(90), 0xFF000000.toInt()))))))

    private fun maxChannelDiff(a: AnimatableColour, b: AnimatableColour, from: Long, to: Long): Int {
        var worst = 0
        var t = from
        while (t <= to) {
            val x = a.evaluate(t); val y = b.evaluate(t)
            for (s in intArrayOf(24, 16, 8, 0)) worst = maxOf(worst, kotlin.math.abs(((x ushr s) and 255) - ((y ushr s) and 255)))
            t += 997 // dense: every ~1ms
        }
        return worst
    }

    @Test fun barStopsAreTheEvaluatorsColoursAndHoldIsAHardStep() {
        val track = layer(EasingCurve.EASY_EASE).text.fill
        val seg = ColourBar.segment(track, 0, ColourBar.MAX_STOPS)
        assertEquals(ColourBar.MAX_STOPS, seg.colours.size)
        for (s in seg.colours.indices) {
            val t = if (s == seg.colours.lastIndex) f(60) else f(0) + ((f(60) - f(0)) * seg.positions[s].toDouble()).toLong()
            assertEquals("stop $s", track.evaluate(t), seg.colours[s])
        }
        assertEquals(2, ColourBar.stops(1f)); assertEquals(ColourBar.MAX_STOPS, ColourBar.stops(10_000f)); assertEquals(16, ColourBar.stops(96f))
        val hold = ColourBar.segment(layer(EasingCurve.HOLD).text.fill, 0, 30)
        assertTrue(hold.hold); assertEquals(listOf(0xFFFF0000.toInt(), 0xFFFF0000.toInt()), hold.colours.toList())
    }

    @Test fun theCacheRecomputesOnlyOnChange() {
        val cache = ColourBar.Cache<Unit>()
        val track = layer(EasingCurve.LINEAR).text.fill
        repeat(5) { cache.get(track, 0, 20) {}; cache.get(track, 1, 20) {} } // scroll frames: hits
        assertEquals(2, cache.computed)
        cache.get(track, 0, 21) {}                                         // the width bucket changed
        assertEquals(3, cache.computed)
        val edited = track.copy(keyframes = FrozenList.of(track.keyframes.mapIndexed { i, k -> if (i == 1) k.copy(argb = -1) else k }))
        cache.get(edited, 0, 20) {}                                        // a key value changed
        assertEquals(4, cache.computed)
        cache.retain(edited); assertEquals(1, cache.size)
    }

    @Test fun addingAKeyKeepsTheBarsLookForLinearEasyEaseCustomAndHold() {
        for (curve in listOf(EasingCurve.LINEAR, EasingCurve.EASY_EASE, EasingCurve.bezier(.2f, .1f, .4f, .95f), EasingCurve.HOLD)) {
            val l = layer(curve)
            val project = ProjectDocument(Composition(durationUs = f(120), frameRate = rate, layers = FrozenList.of(listOf(l))))
            val local = ColourKeyAdd.localFrame(l, rate, f(17))
            val action = ColourKeyAdd.action(l, fill, local)!!
            val after = EditorReducer.reduce(EditorState(project), action).project.composition.layers.single().text.fill
            assertEquals(4, after.keyframes.size)
            val diff = maxChannelDiff(l.text.fill, after, 0, f(90))
            println("TL3 B4 split ${curve.type}: max channel difference $diff / 255")
            assertTrue("${curve.type}: $diff", diff <= 1)
        }
    }

    @Test fun bounceAndElasticSegmentsCannotBeSplitAndKeysAndTheOutsideAreRefused() {
        val bounce = layer(EasingCurve.bounce(EasingLimits.BOUNCE_DEFAULT_COUNT, EasingLimits.BOUNCE_DEFAULT_RESTITUTION))
        assertEquals(ColourKeyAdd.Why.BOUNCE_ELASTIC, ColourKeyAdd.check(bounce.text.fill, f(17)))
        assertNull(ColourKeyAdd.action(bounce, fill, f(17)))
        assertNull(EasingSplit.split(EasingCurve.elastic(EasingLimits.ELASTIC_DEFAULT_OSCILLATIONS, EasingLimits.ELASTIC_DEFAULT_DAMPING), .5f))
        // The second segment (Easy Ease) of the same layer still splits.
        assertEquals(ColourKeyAdd.Why.OK, ColourKeyAdd.check(bounce.text.fill, f(70)))
        assertEquals(ColourKeyAdd.Why.ON_KEY, ColourKeyAdd.check(bounce.text.fill, f(60)))
        assertEquals(ColourKeyAdd.Why.OUTSIDE, ColourKeyAdd.check(bounce.text.fill, f(100)))
        // Linear splits into two linear halves; the split point's y is x for Linear.
        assertEquals(EasingSplit.Halves(EasingCurve.LINEAR, EasingCurve.LINEAR, .25f), EasingSplit.split(EasingCurve.LINEAR, .25f))
    }
}
