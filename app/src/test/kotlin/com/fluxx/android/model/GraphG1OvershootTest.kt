package com.fluxx.android.model

import com.fluxx.android.editor.*
import org.junit.Assert.*
import org.junit.Test

/**
 * GRAPH G1 overshoot safety: a Custom (y outside [0, 1]) or Elastic segment evaluates outside its keys. Every property
 * kind is driven through such curves; nothing may throw (no require during evaluation or edit-time sampling) and every
 * consumer reads a value inside its legal range. Stored data is never clamped.
 */
class GraphG1OvershootTest {
    private val curves = listOf(EasingCurve.bezier(.1f, -2f, .9f, 3f), EasingCurve.bezier(.34f, 1.56f, .64f, 1f), EasingCurve.elastic(6f, .1f))
    private fun t1(c: EasingCurve, a: Float, b: Float) = AnimatableProperty1D(true, a, FrozenList.of(listOf(
        Keyframe1D(0, a).withCurve(c), Keyframe1D(5_000_000, b).withCurve(c), Keyframe1D(10_000_000, a))))
    private fun t2(c: EasingCurve, a: Float, b: Float) = AnimatableProperty2D(true, a, a, FrozenList.of(listOf(
        Keyframe2D(0, a, a).withCurve(c), Keyframe2D(10_000_000, b, b))))
    private fun colour(c: EasingCurve) = AnimatableColour(true, -1, FrozenList.of(listOf(ColourKeyframe(0, 0x80ff0000.toInt()).withCurve(c),
        ColourKeyframe(10_000_000, 0xff0000ff.toInt()))))
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
    /** Every key sits on a legal boundary, so any overshoot leaves the range. */
    private fun text(c: EasingCurve): TextProperties {
        val selector = TextRangeSelector(11, start = t1(c, 0f, 100f), end = t1(c, 100f, 0f), offset = t1(c, -50f, 50f),
            smoothness = t1(c, 0f, 100f), easeHigh = t1(c, -100f, 100f), easeLow = t1(c, 100f, -100f), amount = t1(c, -100f, 100f),
            minAmount = t1(c, -100f, 100f), maxAmount = t1(c, 100f, -100f), wigglesPerSecond = t1(c, 0f, 1000f), correlation = t1(c, 0f, 100f),
            temporalPhase = t1(c, 0f, 360f), spatialPhase = t1(c, 0f, 360f))
        val wiggly = selector.copy(id = 12, kind = AnimatableProperty1D(staticValue = 1f))
        val properties = AnimatorProperty.entries.take(23).map { p ->
            when (p.kind) {
                2 -> TextPropertyOverride(p, vector = t2(c, 0f, 100f))
                3 -> TextPropertyOverride(p, colour = colour(c))
                else -> TextPropertyOverride(p, scalar = if (AnimatorLimits.percent(p)) t1(c, 0f, 100f) else t1(c, -10f, 10f))
            }
        }
        return TextProperties(source = AnimatableString(staticValue = "AV office"), size = t1(c, 8f, 1000f), tracking = t1(c, -100f, 100f),
            leading = t1(c, 0f, 50f), baselineShift = t1(c, -10f, 10f), strokeWidth = t1(c, 0f, 20f), fill = colour(c), strokeColour = colour(c),
            animators = FrozenList.of(listOf(TextAnimator(10, selectors = FrozenList.of(listOf(selector, wiggly)), properties = FrozenList.of(properties)))))
    }
    private val times = (0..200).map { it * 50_000L }

    @Test fun everyEvaluatedConsumerStaysInRange() {
        for (c in curves) {
            val text = text(c)
            // The curves really leave the key ranges.
            assertTrue(times.any { text.size.evaluate(it) > 1000f || text.size.evaluate(it) < 8f })
            assertTrue(times.any { text.strokeWidth.evaluate(it) < 0f })
            for (t in times) {
                assertTrue(text.sizeAt(t) in TextLimits.MIN_SIZE..TextLimits.MAX_SIZE)
                assertTrue(text.strokeWidthAt(t) >= 0f)
                assertTrue(text.effectiveStrokeWidth(t).isFinite())
                for (field in SelectorField.entries) {
                    val address = TextAnimatorEdits.selectorField(10, 11, field)
                    val v = TextAnimatorEdits.value(text, address, t)
                    // The same validation the stored track uses accepts the evaluated value.
                    AnimatableProperty1D(staticValue = v).also { probe -> TextRangeSelector(11).let { TextAnimatorEdits.withField(it, field, probe) } }
                }
                for (p in AnimatorProperty.entries.take(23).filter { it.kind == 1 }) {
                    val v = TextAnimatorEdits.value(text, TextAnimatorEdits.property(10, p), t)
                    TextPropertyOverride(p, scalar = AnimatableProperty1D(staticValue = v))
                }
            }
            // The per-frame text evaluation (selectors, animator values, records, bounds) never throws.
            val document = TextSourceDocument("AV office")
            val prepared = PreparedTextAnimation(text, metrics(9), SelectorEaseTable.shared, document,
                breaks = IntArray(10) { it }, paragraphKernel = ParagraphOracle)
            for (t in times) {
                prepared.evaluate(t)
                for (v in prepared.logicalBounds + prepared.inkBounds) assertTrue(v.isFinite())
                val bytes = prepared.modifiers.duplicate().order(prepared.modifiers.order()).also { it.position(0) }.asFloatBuffer()
                while (bytes.hasRemaining()) assertTrue(bytes.get().isFinite())
            }
        }
    }

    @Test fun layerOpacityAndTransformsStayLegal() {
        for (c in curves) {
            val layer = CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(0, 0, 10_000_000), animTransform = AnimatableTransform(
                position = t2(c, -1f, 1f), scale = t2(c, 0f, 2f), rotation = t1(c, 0f, 360f), opacity = t1(c, 0f, 1f)))
            val out = FloatArray(6)
            for (t in times) {
                layer.evaluatedTransformInto(t, out); assertTrue(out[5] in 0f..1f); assertTrue(out.all { it.isFinite() })
                layer.evaluatedTransform(t) // constructs a validated Transform
            }
        }
    }

    /** Edit paths sample the evaluated value and write it into validated keys: they must clamp, never throw. */
    @Test fun editsThatSampleAnOvershootingValueNeverThrow() {
        for (c in curves) {
            val text = text(c)
            val layer = CompositionLayer(1, LayerType.TEXT, timing = ClipTiming(0, 0, 10_000_000), text = text, animTransform = AnimatableTransform(
                opacity = t1(c, 0f, 1f)))
            for (t in times.filter { it % 250_000L == 0L && it !in listOf(0L, 5_000_000L, 10_000_000L) }) {
                var state = EditorState(ProjectDocument(Composition(durationUs = 10_000_000, layers = FrozenList.of(listOf(layer)))), 1, t)
                for (p in listOf(AnimPropertyType.FONT_SIZE, AnimPropertyType.STROKE_WIDTH, AnimPropertyType.TRACKING, AnimPropertyType.LEADING, AnimPropertyType.BASELINE_SHIFT)) {
                    state = EditorReducer.reduce(state, EditorAction.SetKeyframe(1, p, t, text.scalarValue(p, t)))
                }
                state = EditorReducer.reduce(state, EditorAction.SetKeyframe(1, AnimPropertyType.OPACITY, t, layer.evaluatedTransform(t).opacity))
                for (field in SelectorField.entries) state = EditorReducer.reduce(state, EditorAction.EditTextAnimator(1,
                    AnimatorEdit.Key(TextAnimatorEdits.selectorField(10, 11, field), AnimatorKeyOperation.SET, t)))
                for (p in AnimatorProperty.entries.take(23)) state = EditorReducer.reduce(state, EditorAction.EditTextAnimator(1,
                    AnimatorEdit.Key(TextAnimatorEdits.property(10, p), AnimatorKeyOperation.SET, t)))
                // Freeze (sample, then drop the keys) and disable animation sample too.
                state = EditorReducer.reduce(state, EditorAction.EditTextAnimator(1, TextAnimatorEdits.freeze(TextAnimatorEdits.selectorField(10, 11, SelectorField.SMOOTHNESS), t)))
                state = EditorReducer.reduce(state, EditorAction.ToggleAnimated(1, AnimPropertyType.FONT_SIZE, false, t))
                assertTrue(state.project.composition.layers[0].text.size.staticValue in TextLimits.MIN_SIZE..TextLimits.MAX_SIZE)
            }
        }
    }
}
