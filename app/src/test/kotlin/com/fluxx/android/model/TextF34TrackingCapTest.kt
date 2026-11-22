package com.fluxx.android.model

import com.fluxx.android.ui.inspector.PropertyLimits
import org.junit.Assert.*
import org.junit.Test

/**
 * TEXT F3.4: the Character tab's Tracking slider spans its hard limit (±1000, detents every 100, typed ±1000).
 * Static tracking at the cap evaluates to finite records and bounds (point and wrapped box text, with and without an
 * animator tracking on top), and costs the same as zero tracking: the evaluation path does not depend on the value.
 */
class TextF34TrackingCapTest {
    private fun scalar(v: Float) = AnimatableProperty1D(staticValue = v)
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

    private fun prepare(tracking: Float, box: Boolean, animated: Float): PreparedTextAnimation {
        var document = TextSourceDocument(source)
        if (box) document = document.copy(boxWidth = 500f, paragraphs = FrozenList.of(listOf(TextParagraphStyle(alignment = ParagraphAlignment.CENTRE))))
        val animators = if (animated == 0f) FrozenList.empty() else FrozenList.of(listOf(TextAnimator(1,
            selectors = FrozenList.of(listOf(TextRangeSelector(2))),
            properties = FrozenList.of(listOf(TextPropertyOverride(AnimatorProperty.TRACKING, scalar = scalar(animated)))))))
        val text = TextProperties(source = AnimatableString(staticValue = source, document = document), size = scalar(300f),
            tracking = scalar(tracking), animators = animators, alignment = TextAlignment.CENTRE)
        return PreparedTextAnimation(text, metrics(source.length), SelectorEaseTable.shared, document,
            breaks = IntArray(source.length + 1) { it }, paragraphKernel = ParagraphOracle)
    }

    @Test fun characterTrackingSliderSpansTheHardLimit() {
        val t = PropertyLimits.TRACKING
        assertEquals(-1000f to 1000f, t.sliderMin to t.sliderMax)
        assertEquals(-1000f to 1000f, t.typedMin to t.typedMax)
        assertEquals(t.hardMin to t.hardMax, t.sliderMin to t.sliderMax)
        assertEquals(100f, t.detentStep, 0f)
        // Same ranges as the Animate Tracking slider; Manual spacing keeps its pre-F3.4 ranges.
        val a = PropertyLimits.ANIM_TRACKING
        assertEquals(listOf(t.sliderMin, t.sliderMax, t.typedMin, t.typedMax, t.detentStep), listOf(a.sliderMin, a.sliderMax, a.typedMin, a.typedMax, a.detentStep))
        val m = PropertyLimits.MANUAL_KERNING
        assertEquals(listOf(-100f, 100f, -500f, 500f), listOf(m.sliderMin, m.sliderMax, m.typedMin, m.typedMax))
        // Fine control: typed values are exact, a detent sits every 100, and the zero snap stays a narrow band.
        assertEquals(37f, PropertyLimits.parseTyped(t, "37")!!, 0f)
        assertEquals(-1000f, PropertyLimits.parseTyped(t, "-1500")!!, 0f)
        assertEquals(100f, t.valueAt(t.fraction(100f)), 0f)
        assertEquals(0f, t.valueAt(t.fraction(20f)), 0f)  // inside the 1.2% snap band (±24)
        assertEquals(30f, t.valueAt(t.fraction(30f)), 0f)
    }

    @Test fun staticTrackingAtTheCapRendersFinite() {
        for (tracking in listOf(-1000f, 1000f)) for (box in listOf(false, true)) for (animated in listOf(0f, 1000f, -1000f)) {
            val a = prepare(tracking, box, animated)
            for (time in listOf(0L, 500_000L)) {
                a.evaluate(time)
                val label = "tracking $tracking box $box animated $animated"
                for (i in 0 until a.modifiers.capacity() / 4) assertTrue(label, a.modifiers.getFloat(i * 4).isFinite())
                assertTrue(label, a.inkBounds.all { it.isFinite() } && a.logicalBounds.all { it.isFinite() })
                assertTrue(label, a.geometrySnapshot().boxes.all { it.isFinite() })
            }
        }
    }

    @Test fun costDoesNotChangeWithTheValue() {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        fun allocated(a: PreparedTextAnimation): Long {
            repeat(50) { a.evaluate(it * 10_000L) } // warm up
            val before = bean.getThreadAllocatedBytes(Thread.currentThread().id)
            repeat(200) { a.evaluate(it * 10_000L) }
            return bean.getThreadAllocatedBytes(Thread.currentThread().id) - before
        }
        for (box in listOf(false, true)) {
            val zero = allocated(prepare(0f, box, 100f))
            for (tracking in listOf(-1000f, 1000f)) assertEquals("box $box tracking $tracking", zero, allocated(prepare(tracking, box, 100f)))
        }
    }
}
