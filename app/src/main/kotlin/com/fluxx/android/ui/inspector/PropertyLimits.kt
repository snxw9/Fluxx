package com.fluxx.android.ui.inspector

import com.fluxx.android.model.TextLimits
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One numeric property's limits. The slider (or ladder) is the comfortable range; typed entry may go further
 * up to [typedMin]..[typedMax]; anything beyond the hard cap is clamped on commit and on programmatic set.
 * UI-only: the document model keeps its own (wider or equal) validation.
 */
internal data class PropertyLimit(
    val name: String,
    val unit: String,
    val sliderMin: Float, val sliderMax: Float,
    val typedMin: Float, val typedMax: Float,
    val hardMin: Float, val hardMax: Float,
    val default: Float,
    /** Discrete slider detents (stepped ladder). Null means a continuous slider. */
    val ladder: List<Float>? = null,
    /** Continuous sliders: light detents at 0, [default] and multiples of this step. */
    val detentStep: Float = 0f,
    /** Display/typing resolution. */
    val step: Float = 1f,
) {
    init {
        require(sliderMin < sliderMax && typedMin <= sliderMin && typedMax >= sliderMax) { "$name: slider must sit inside typed range" }
        require(hardMin <= typedMin && hardMax >= typedMax) { "$name: typed range must sit inside hard cap" }
        ladder?.let { l -> require(l.zipWithNext().all { it.first < it.second } && l.first() >= sliderMin && l.last() <= sliderMax) { "$name: ladder" } }
    }

    fun hard(value: Float): Float = if (value.isNaN()) default else value.coerceIn(hardMin, hardMax)
    fun typed(value: Float): Float = if (value.isNaN()) default else value.coerceIn(typedMin, typedMax)
    fun slider(value: Float): Float = if (value.isNaN()) default else value.coerceIn(sliderMin, sliderMax)

    /** Slider thumb position for a value; values outside the slider range pin to the ends. */
    fun fraction(value: Float): Float {
        val v = slider(value)
        ladder?.let { l ->
            val i = l.indexOfLast { it <= v }.coerceAtLeast(0)
            if (i >= l.lastIndex) return 1f
            val local = (v - l[i]) / (l[i + 1] - l[i])
            return ((i + local) / l.lastIndex).coerceIn(0f, 1f)
        }
        return ((v - sliderMin) / (sliderMax - sliderMin)).coerceIn(0f, 1f)
    }

    /** Value for a slider thumb position. Ladders snap to the nearest rung; continuous sliders snap softly to 0/default. */
    fun valueAt(fraction: Float): Float {
        val f = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
        ladder?.let { l -> return l[(f * l.lastIndex).roundToInt().coerceIn(0, l.lastIndex)] }
        val raw = sliderMin + f * (sliderMax - sliderMin)
        val snap = (sliderMax - sliderMin) * SNAP_FRACTION
        for (anchor in listOf(0f, default)) if (anchor in sliderMin..sliderMax && abs(raw - anchor) <= snap) return anchor
        return round(raw)
    }

    fun round(value: Float): Float = if (step <= 0f) value else (value / step).roundToInt() * step

    /** Detent bucket: a change of bucket between two values is one haptic tick. */
    fun detent(value: Float): Int {
        ladder?.let { l -> return l.indexOfLast { it <= value }.coerceAtLeast(0) }
        if (detentStep <= 0f) return 0
        return kotlin.math.floor(value / detentStep).toInt()
    }

    /** Crossing 0 or the default deserves its own tick even inside one bucket. */
    fun crossesAnchor(old: Float, new: Float): Boolean =
        listOf(0f, default).any { a -> (old - a) * (new - a) < 0f || (new == a && old != a) }

    companion object { const val SNAP_FRACTION = .012f }
}

/** Every numeric limit used by the inspector, defined once. */
internal object PropertyLimits {
    /** Font size ladder (px): the size slider snaps to these rungs; typed values are free within the typed range. */
    val SIZE_LADDER = listOf(8f, 10f, 12f, 14f, 16f, 18f, 20f, 24f, 28f, 32f, 36f, 42f, 48f, 56f, 64f, 72f, 90f, 108f, 120f, 144f, 180f, 240f, 300f)

    // Model minimum is 8px (TextLimits.MIN_SIZE, provisional until small-size acceptance); typing 1-7 clamps to 8.
    val FONT_SIZE = PropertyLimit("Font size", "px", 8f, 300f, TextLimits.MIN_SIZE, 1000f, TextLimits.MIN_SIZE, TextLimits.MAX_SIZE, 72f, ladder = SIZE_LADDER)
    val RELATIVE_SIZE = PropertyLimit("Relative size", "%", 10f, 400f, 1f, 1000f, 1f, 1000f, 100f, detentStep = 50f)
    val TRACKING = PropertyLimit("Tracking", "1/1000 em", -100f, 100f, -500f, 500f, -1000f, 1000f, 0f, detentStep = 10f)
    val MANUAL_KERNING = TRACKING.copy(name = "Manual spacing")
    val LEADING = PropertyLimit("Leading", "px", 0f, 300f, 0f, 2000f, -2000f, 2000f, 0f, ladder = listOf(0f) + SIZE_LADDER)
    val GLYPH_SCALE = PropertyLimit("Glyph scale", "%", 10f, 400f, 1f, 1000f, 1f, 1000f, 100f, detentStep = 50f)
    val BASELINE = PropertyLimit("Baseline shift", "px", -100f, 100f, -1000f, 1000f, -1000f, 1000f, 0f, detentStep = 10f)
    val STROKE_WIDTH = PropertyLimit("Stroke width", "px", 0f, 50f, 0f, 200f, 0f, 200f, 0f, detentStep = 5f, step = .5f)
    val STROKE_DELTA = PropertyLimit("Stroke width delta", "px", -50f, 50f, -200f, 200f, -200f, 200f, 0f, detentStep = 5f, step = .5f)
    val PARAGRAPH = PropertyLimit("Paragraph spacing", "px", 0f, 100f, 0f, 500f, 0f, 500f, 0f, detentStep = 10f)
    // First-line indent keeps negative (hanging) indents, which the model and renderer already support.
    val INDENT_FIRST = PropertyLimit("First-line indent", "px", -100f, 100f, -500f, 500f, -500f, 500f, 0f, detentStep = 10f)
    val BOX_WIDTH = PropertyLimit("Box width", "px", 0f, 2000f, 0f, 20000f, 0f, 20000f, 0f, detentStep = 100f)
    val OPACITY = PropertyLimit("Opacity", "%", 0f, 100f, 0f, 100f, 0f, 100f, 100f, detentStep = 10f)
    /** Layer transform scale, percent. The ruler is positive and ends at 10000%; negative scale is typed only. */
    val LAYER_SCALE = PropertyLimit("Scale", "%", ScaleRulerMath.MIN_PERCENT, ScaleRulerMath.MAX_PERCENT, -50000f, 50000f, -50000f, 50000f, 100f, step = .1f)
    /** Rotation: the dial never wraps; typing allows ±100 turns. */
    val ROTATION = PropertyLimit("Rotation", "°", -360f, 360f, -36000f, 36000f, -36000f, 36000f, 0f, step = .1f)
    val AXIS_STEP = .01f

    /** Position in composition pixels: ±10 × the composition size around the composition origin. */
    fun position(compositionSize: Int): PropertyLimit {
        val span = 10f * compositionSize.coerceAtLeast(1)
        return PropertyLimit("Position", "px", 0f, compositionSize.coerceAtLeast(1).toFloat(), -span, span, -span, span, compositionSize / 2f, step = .1f)
    }

    // ---- Text animators (TEXT F3). Slider = comfortable range, typed/hard = what the backend accepts and renders. ----
    /** Animator Position / Anchor offsets in layer pixels (the model is unbounded; this is the UI cap). */
    val ANIM_OFFSET = PropertyLimit("Offset", "px", -500f, 500f, -100000f, 100000f, -100000f, 100000f, 0f, detentStep = 50f, step = .1f)
    /** Skew: the evaluator clamps the coverage-weighted skew to ±85° per glyph (TextSelectorEngine.affine), so ±85 is the cap. */
    val ANIM_SKEW = PropertyLimit("Skew", "°", -85f, 85f, -85f, 85f, -85f, 85f, 0f, detentStep = 15f, step = .1f)
    val ANIM_ANGLE = PropertyLimit("Angle", "°", -180f, 180f, -3600f, 3600f, -3600f, 3600f, 0f, detentStep = 15f, step = .1f)
    /** Percent fields the model validates to 0..100 (opacity family, line anchor). */
    val ANIM_PERCENT = PropertyLimit("Percent", "%", 0f, 100f, 0f, 100f, 0f, 100f, 0f, detentStep = 10f)
    /**
     * Per-glyph edge softness (an SDF shader parameter, not a Gaussian layer blur). PreparedTextAnimation.writeRecords
     * clamps it to the glyph's SDF budget: 10% of the glyph's font size (7.5% with faux bold) minus half the stroke width.
     * At the 1,000px maximum layer size that is 100px, so 100 is the typed cap.
     */
    val ANIM_BLUR = PropertyLimit("Blur", "px", 0f, 50f, 0f, 100f, 0f, 100f, 0f, detentStep = 5f, step = .5f)
    /** Hue in degrees (wraps modulo 360 in the evaluator). */
    val ANIM_HUE = PropertyLimit("Hue", "°", -180f, 180f, -3600f, 3600f, -3600f, 3600f, 0f, detentStep = 30f)
    /** Saturation / brightness in percentage points; the evaluator clamps the result, so ±100 is the useful range. */
    val ANIM_POINTS = PropertyLimit("Points", "%", -100f, 100f, -100f, 100f, -100f, 100f, 0f, detentStep = 10f)
    val ANIM_LINE_SPACING = PropertyLimit("Line spacing", "px", -100f, 100f, -2000f, 2000f, -2000f, 2000f, 0f, detentStep = 10f)
    val ANIM_GROUPING = PropertyLimit("Grouping alignment", "%", -100f, 100f, -1000f, 1000f, -1000f, 1000f, 0f, detentStep = 25f)
    /** Character offset in code points; the typed cap is the Unicode range. */
    val ANIM_CHARACTER_OFFSET = PropertyLimit("Character offset", "", -100f, 100f, -1114111f, 1114111f, -1114111f, 1114111f, 0f, detentStep = 10f)
    /** Character value: an absolute code point. The slider covers printable ASCII to Latin Extended-B; type the rest. */
    val ANIM_CHARACTER_VALUE = PropertyLimit("Character value", "", 32f, 591f, 0f, 1114111f, 0f, 1114111f, 0f, detentStep = 32f)
    /** Selector start / end / offset: the model is unbounded; the slider is the selector's own 0–100% range. */
    val SELECTOR_RANGE = PropertyLimit("Range", "%", 0f, 100f, -100000f, 100000f, -100000f, 100000f, 0f, detentStep = 10f, step = .1f)
    val SELECTOR_OFFSET = PropertyLimit("Offset", "%", -100f, 100f, -100000f, 100000f, -100000f, 100000f, 0f, detentStep = 10f, step = .1f)
    /** Amount, ease high/low, min/max amount: the model validates -100..100. */
    val SELECTOR_SIGNED = PropertyLimit("Amount", "%", -100f, 100f, -100f, 100f, -100f, 100f, 0f, detentStep = 10f)
    val SELECTOR_SMOOTHNESS = PropertyLimit("Smoothness", "%", 0f, 100f, 0f, 100f, 0f, 100f, 100f, detentStep = 10f)
    val SELECTOR_WIGGLES = PropertyLimit("Wiggles per second", "/s", 0f, 10f, 0f, 1000f, 0f, 1000f, 2f, detentStep = 1f, step = .1f)
    val SELECTOR_CORRELATION = PropertyLimit("Correlation", "%", 0f, 100f, 0f, 100f, 0f, 100f, 50f, detentStep = 10f)
    val SELECTOR_PHASE = PropertyLimit("Phase", "°", -360f, 360f, -36000f, 36000f, -36000f, 36000f, 0f, detentStep = 45f, step = .1f)

    fun forControl(control: TextControl, selected: Boolean): PropertyLimit? = when (control) {
        TextControl.SIZE -> if (selected) RELATIVE_SIZE else FONT_SIZE
        TextControl.RELATIVE_SIZE -> RELATIVE_SIZE
        TextControl.TRACKING -> TRACKING
        TextControl.MANUAL_KERNING -> MANUAL_KERNING
        TextControl.LEADING -> LEADING
        TextControl.H_SCALE, TextControl.V_SCALE -> GLYPH_SCALE
        TextControl.BASELINE -> BASELINE
        TextControl.STROKE_WIDTH -> if (selected) STROKE_DELTA else STROKE_WIDTH
        TextControl.INDENT_LEFT, TextControl.INDENT_RIGHT, TextControl.BEFORE, TextControl.AFTER -> PARAGRAPH
        TextControl.INDENT_FIRST -> INDENT_FIRST
        TextControl.BOX -> BOX_WIDTH
        else -> null
    }

    /** Parse a typed entry: comma decimals accepted; result clamped to the typed range. Null if not a finite number. */
    fun parseTyped(limit: PropertyLimit, input: String): Float? =
        input.trim().removeSuffix(limit.unit).trim().removeSuffix("%").replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }?.let(limit::typed)

    fun format(limit: PropertyLimit, value: Float): String {
        if (!value.isFinite()) return "—"
        val v = limit.round(value)
        return if (v == v.toLong().toFloat()) v.toLong().toString()
        else String.format(java.util.Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')
    }
}
