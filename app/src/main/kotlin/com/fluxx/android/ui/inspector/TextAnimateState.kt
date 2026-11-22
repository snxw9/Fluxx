package com.fluxx.android.ui.inspector

import androidx.compose.runtime.saveable.Saver
import com.fluxx.android.editor.*
import com.fluxx.android.model.*

/** Animate page sub-switch for the selected animator. */
internal enum class AnimateTab { PROPERTIES, SELECTORS }

/** How a field is edited. */
internal enum class AnimateEditor { SLIDER, POSITION, SCALE, ROTATION, VECTOR, COLOUR, CHOICE, TOGGLE, SEED }

/** One choice of a discrete field (stored as its index). */
internal data class AnimateChoice(val value: Float, val icon: String, val label: String, val short: String)

/**
 * Everything the face needs to show one field: label, icon, unit, editor kind, limit (numeric) or choices (discrete).
 * Derived from the backend enums ([AnimatorProperty], [SelectorField], [SelectorShape], ...), never from free text.
 */
internal data class AnimateFieldSpec(val label: String, val icon: String, val editor: AnimateEditor,
    val limit: PropertyLimit? = null, val choices: List<AnimateChoice> = emptyList(), val short: String = label)

internal object AnimateFields {
    val SHAPES = listOf(
        AnimateChoice(0f, "SHAPE_SQUARE", "Square", "Sq"), AnimateChoice(1f, "SHAPE_RAMP_UP", "Ramp up", "Up"),
        AnimateChoice(2f, "SHAPE_RAMP_DOWN", "Ramp down", "Down"), AnimateChoice(3f, "SHAPE_TRIANGLE", "Triangle", "Tri"),
        AnimateChoice(4f, "SHAPE_ROUND", "Round", "Rnd"), AnimateChoice(5f, "SHAPE_SMOOTH", "Smooth", "Smth"))
    val BASED_ON = listOf(
        AnimateChoice(0f, "BASIS_CHARACTERS", "Characters", "Chr"), AnimateChoice(1f, "BASIS_NON_SPACE", "Characters excluding spaces", "NoSp"),
        AnimateChoice(2f, "BASIS_WORDS", "Words", "Wrd"), AnimateChoice(3f, "BASIS_LINES", "Lines", "Line"))
    val MODES = listOf(
        AnimateChoice(0f, "MODE_ADD", "Add", "Add"), AnimateChoice(1f, "MODE_SUBTRACT", "Subtract", "Sub"),
        AnimateChoice(2f, "MODE_INTERSECT", "Intersect", "Int"), AnimateChoice(3f, "MODE_MIN", "Min", "Min"),
        AnimateChoice(4f, "MODE_MAX", "Max", "Max"), AnimateChoice(5f, "MODE_DIFFERENCE", "Difference", "Diff"))
    val UNITS = listOf(AnimateChoice(0f, "UNITS_PERCENT", "Percentage", "%"), AnimateChoice(1f, "UNITS_INDEX", "Index", "#"))
    val GROUPING = listOf(
        AnimateChoice(0f, "BASIS_CHARACTERS", "Characters", "Chr"), AnimateChoice(1f, "BASIS_WORDS", "Words", "Wrd"),
        AnimateChoice(2f, "BASIS_LINES", "Lines", "Line"), AnimateChoice(3f, "GROUPING_ALL", "All", "All"))

    init {
        // The choice lists are the backend enums, in order, with nothing added or left out.
        check(SHAPES.size == SelectorShape.entries.size && BASED_ON.size == SelectorBasedOn.entries.size)
        check(MODES.size == SelectorMode.entries.size && UNITS.size == SelectorUnits.entries.size && GROUPING.size == AnchorGrouping.entries.size)
    }

    fun property(p: AnimatorProperty): AnimateFieldSpec = when (p) {
        AnimatorProperty.POSITION -> AnimateFieldSpec("Position", "POSITION", AnimateEditor.POSITION, PropertyLimits.ANIM_OFFSET, short = "Pos")
        AnimatorProperty.SCALE -> AnimateFieldSpec("Scale", "SCALE", AnimateEditor.SCALE, PropertyLimits.LAYER_SCALE, short = "Scale")
        AnimatorProperty.ROTATION -> AnimateFieldSpec("Rotation", "ROTATION", AnimateEditor.ROTATION, PropertyLimits.ROTATION, short = "Rot")
        AnimatorProperty.OPACITY -> AnimateFieldSpec("Opacity", "OPACITY", AnimateEditor.SLIDER, PropertyLimits.ANIM_PERCENT.copy(name = "Opacity", default = 100f), short = "Opac")
        AnimatorProperty.SKEW -> AnimateFieldSpec("Skew", "SKEW", AnimateEditor.SLIDER, PropertyLimits.ANIM_SKEW, short = "Skew")
        AnimatorProperty.SKEW_AXIS -> AnimateFieldSpec("Skew axis", "SKEW_AXIS", AnimateEditor.SLIDER, PropertyLimits.ANIM_ANGLE.copy(name = "Skew axis"), short = "Axis")
        AnimatorProperty.ANCHOR -> AnimateFieldSpec("Anchor point", "ANCHOR", AnimateEditor.VECTOR, PropertyLimits.ANIM_OFFSET.copy(name = "Anchor point"), short = "Anch")
        AnimatorProperty.FILL -> AnimateFieldSpec("Fill colour", "FILL", AnimateEditor.COLOUR, short = "Fill")
        AnimatorProperty.STROKE_COLOUR -> AnimateFieldSpec("Stroke colour", "STROKE", AnimateEditor.COLOUR, short = "Strk")
        AnimatorProperty.STROKE_WIDTH -> AnimateFieldSpec("Stroke width", "STROKE_WIDTH", AnimateEditor.SLIDER, PropertyLimits.STROKE_DELTA.copy(name = "Stroke width"), short = "Width")
        AnimatorProperty.TRACKING -> AnimateFieldSpec("Tracking", "TRACKING", AnimateEditor.SLIDER, PropertyLimits.TRACKING, short = "Track")
        AnimatorProperty.LINE_ANCHOR -> AnimateFieldSpec("Line anchor", "LINE_ANCHOR", AnimateEditor.SLIDER, PropertyLimits.ANIM_PERCENT.copy(name = "Line anchor"), short = "LnAn")
        AnimatorProperty.BLUR -> AnimateFieldSpec("Blur", "BLUR", AnimateEditor.SLIDER, PropertyLimits.ANIM_BLUR, short = "Blur")
        AnimatorProperty.FILL_OPACITY -> AnimateFieldSpec("Fill opacity", "FILL_OPACITY", AnimateEditor.SLIDER, PropertyLimits.ANIM_PERCENT.copy(name = "Fill opacity", default = 100f), short = "F op")
        AnimatorProperty.STROKE_OPACITY -> AnimateFieldSpec("Stroke opacity", "STROKE_OPACITY", AnimateEditor.SLIDER, PropertyLimits.ANIM_PERCENT.copy(name = "Stroke opacity", default = 100f), short = "S op")
        AnimatorProperty.FILL_HUE -> AnimateFieldSpec("Fill hue", "FILL_HUE", AnimateEditor.SLIDER, PropertyLimits.ANIM_HUE.copy(name = "Fill hue"), short = "F hue")
        AnimatorProperty.FILL_SATURATION -> AnimateFieldSpec("Fill saturation", "FILL_SATURATION", AnimateEditor.SLIDER, PropertyLimits.ANIM_POINTS.copy(name = "Fill saturation"), short = "F sat")
        AnimatorProperty.FILL_BRIGHTNESS -> AnimateFieldSpec("Fill brightness", "FILL_BRIGHTNESS", AnimateEditor.SLIDER, PropertyLimits.ANIM_POINTS.copy(name = "Fill brightness"), short = "F bri")
        AnimatorProperty.STROKE_HUE -> AnimateFieldSpec("Stroke hue", "STROKE_HUE", AnimateEditor.SLIDER, PropertyLimits.ANIM_HUE.copy(name = "Stroke hue"), short = "S hue")
        AnimatorProperty.STROKE_SATURATION -> AnimateFieldSpec("Stroke saturation", "STROKE_SATURATION", AnimateEditor.SLIDER, PropertyLimits.ANIM_POINTS.copy(name = "Stroke saturation"), short = "S sat")
        AnimatorProperty.STROKE_BRIGHTNESS -> AnimateFieldSpec("Stroke brightness", "STROKE_BRIGHTNESS", AnimateEditor.SLIDER, PropertyLimits.ANIM_POINTS.copy(name = "Stroke brightness"), short = "S bri")
        AnimatorProperty.LINE_SPACING -> AnimateFieldSpec("Line spacing", "LEADING", AnimateEditor.SLIDER, PropertyLimits.ANIM_LINE_SPACING, short = "LnSp")
        AnimatorProperty.GROUPING_ALIGNMENT -> AnimateFieldSpec("Grouping alignment", "GROUPING_ALIGNMENT", AnimateEditor.VECTOR, PropertyLimits.ANIM_GROUPING, short = "GrAl")
        AnimatorProperty.CHARACTER_OFFSET -> AnimateFieldSpec("Character offset", "CHARACTER_OFFSET", AnimateEditor.SLIDER, PropertyLimits.ANIM_CHARACTER_OFFSET, short = "ChOf")
        AnimatorProperty.CHARACTER_VALUE -> AnimateFieldSpec("Character value", "CHARACTER_VALUE", AnimateEditor.SLIDER, PropertyLimits.ANIM_CHARACTER_VALUE, short = "ChVl")
    }

    fun selector(f: SelectorField): AnimateFieldSpec = when (f) {
        SelectorField.ENABLED -> AnimateFieldSpec("Selector on", "ENABLED", AnimateEditor.TOGGLE, short = "On")
        SelectorField.START -> AnimateFieldSpec("Start", "RANGE_START", AnimateEditor.SLIDER, PropertyLimits.SELECTOR_RANGE.copy(name = "Start"))
        SelectorField.END -> AnimateFieldSpec("End", "RANGE_END", AnimateEditor.SLIDER, PropertyLimits.SELECTOR_RANGE.copy(name = "End", default = 100f))
        SelectorField.OFFSET -> AnimateFieldSpec("Offset", "RANGE_OFFSET", AnimateEditor.SLIDER, PropertyLimits.SELECTOR_OFFSET)
        SelectorField.UNITS -> AnimateFieldSpec("Units", "UNITS_PERCENT", AnimateEditor.TOGGLE, choices = UNITS)
        SelectorField.BASED_ON -> AnimateFieldSpec("Based on", "BASIS_CHARACTERS", AnimateEditor.CHOICE, choices = BASED_ON)
        SelectorField.SHAPE -> AnimateFieldSpec("Shape", "SHAPE_SQUARE", AnimateEditor.CHOICE, choices = SHAPES)
        SelectorField.SMOOTHNESS -> AnimateFieldSpec("Smoothness", "SMOOTHNESS", AnimateEditor.SLIDER, PropertyLimits.SELECTOR_SMOOTHNESS, short = "Smth")
        SelectorField.EASE_HIGH -> AnimateFieldSpec("Ease high", "EASE_HIGH", AnimateEditor.SLIDER, PropertyLimits.SELECTOR_SIGNED.copy(name = "Ease high"), short = "EHi")
        SelectorField.EASE_LOW -> AnimateFieldSpec("Ease low", "EASE_LOW", AnimateEditor.SLIDER, PropertyLimits.SELECTOR_SIGNED.copy(name = "Ease low"), short = "ELo")
        SelectorField.MODE -> AnimateFieldSpec("Mode", "MODE_ADD", AnimateEditor.CHOICE, choices = MODES)
        SelectorField.AMOUNT -> AnimateFieldSpec("Amount", "AMOUNT", AnimateEditor.SLIDER, PropertyLimits.SELECTOR_SIGNED.copy(name = "Amount", default = 100f), short = "Amt")
        SelectorField.MIN_AMOUNT -> AnimateFieldSpec("Min amount", "MIN_AMOUNT", AnimateEditor.SLIDER, PropertyLimits.SELECTOR_SIGNED.copy(name = "Min amount"), short = "Min")
        SelectorField.MAX_AMOUNT -> AnimateFieldSpec("Max amount", "MAX_AMOUNT", AnimateEditor.SLIDER, PropertyLimits.SELECTOR_SIGNED.copy(name = "Max amount", default = 100f), short = "Max")
        SelectorField.RANDOMIZE_ORDER -> AnimateFieldSpec("Randomize order", "RANDOMIZE", AnimateEditor.TOGGLE, short = "Rnd")
        SelectorField.KIND -> AnimateFieldSpec("Selector kind", "RANGE", AnimateEditor.CHOICE)
        SelectorField.WIGGLES_PER_SECOND -> AnimateFieldSpec("Wiggles per second", "WIGGLES", AnimateEditor.SLIDER, PropertyLimits.SELECTOR_WIGGLES, short = "Wig/s")
        SelectorField.CORRELATION -> AnimateFieldSpec("Correlation", "CORRELATION", AnimateEditor.SLIDER, PropertyLimits.SELECTOR_CORRELATION, short = "Corr")
        SelectorField.TEMPORAL_PHASE -> AnimateFieldSpec("Temporal phase", "TEMPORAL_PHASE", AnimateEditor.SLIDER, PropertyLimits.SELECTOR_PHASE.copy(name = "Temporal phase"), short = "TPh")
        SelectorField.SPATIAL_PHASE -> AnimateFieldSpec("Spatial phase", "SPATIAL_PHASE", AnimateEditor.SLIDER, PropertyLimits.SELECTOR_PHASE.copy(name = "Spatial phase"), short = "SPh")
        SelectorField.LOCK_DIMENSIONS -> AnimateFieldSpec("Lock dimensions", "LOCK", AnimateEditor.TOGGLE, short = "Lock")
    }
    val SEED = AnimateFieldSpec("Random seed", "SEED", AnimateEditor.SEED, short = "Seed")
    val ANIMATOR_ENABLED = AnimateFieldSpec("Animator on", "ENABLED", AnimateEditor.TOGGLE, short = "On")
    val ANCHOR_GROUPING = AnimateFieldSpec("Anchor point grouping", "GROUPING", AnimateEditor.CHOICE, choices = GROUPING, short = "Grp")

    fun spec(address: AnimatorTrackAddress): AnimateFieldSpec = when {
        address.seed -> SEED
        address.selectorId != null -> selector(requireNotNull(address.field))
        address.property != null -> property(address.property)
        address.grouping -> ANCHOR_GROUPING
        else -> ANIMATOR_ENABLED
    }
    /** Icon of a discrete field: the icon of its current choice (shape, based on, mode, units, grouping). */
    fun icon(spec: AnimateFieldSpec, value: Float): String =
        spec.choices.firstOrNull { it.value == value }?.icon ?: spec.icon

    /** Add-property popover groups. Every [AnimatorProperty] appears exactly once. */
    val GROUPS: List<Pair<String, List<AnimatorProperty>>> = listOf(
        "Transform" to listOf(AnimatorProperty.ANCHOR, AnimatorProperty.POSITION, AnimatorProperty.SCALE, AnimatorProperty.SKEW,
            AnimatorProperty.SKEW_AXIS, AnimatorProperty.ROTATION, AnimatorProperty.OPACITY),
        "Colour" to listOf(AnimatorProperty.FILL, AnimatorProperty.FILL_OPACITY, AnimatorProperty.FILL_HUE, AnimatorProperty.FILL_SATURATION,
            AnimatorProperty.FILL_BRIGHTNESS, AnimatorProperty.STROKE_COLOUR, AnimatorProperty.STROKE_OPACITY, AnimatorProperty.STROKE_HUE,
            AnimatorProperty.STROKE_SATURATION, AnimatorProperty.STROKE_BRIGHTNESS, AnimatorProperty.STROKE_WIDTH),
        "Text" to listOf(AnimatorProperty.TRACKING, AnimatorProperty.LINE_ANCHOR, AnimatorProperty.LINE_SPACING, AnimatorProperty.GROUPING_ALIGNMENT,
            AnimatorProperty.BLUR, AnimatorProperty.CHARACTER_OFFSET, AnimatorProperty.CHARACTER_VALUE))

    /**
     * Face order of a selector's tiles; `null` is the random seed tile (the seed is not a [SelectorField]). Range keeps its
     * start/end/offset on the handle strip, so their exact-entry tiles come after the primary tiles.
     */
    fun selectorTiles(kind: TextSelectorKind): List<SelectorField?> = when (kind) {
        TextSelectorKind.RANGE -> listOf(SelectorField.SHAPE, SelectorField.BASED_ON, SelectorField.MODE, SelectorField.AMOUNT, SelectorField.UNITS,
            SelectorField.RANDOMIZE_ORDER, null, SelectorField.START, SelectorField.END, SelectorField.OFFSET, SelectorField.SMOOTHNESS,
            SelectorField.EASE_HIGH, SelectorField.EASE_LOW, SelectorField.MIN_AMOUNT, SelectorField.MAX_AMOUNT, SelectorField.ENABLED)
        TextSelectorKind.WIGGLY -> listOf(SelectorField.MAX_AMOUNT, SelectorField.MIN_AMOUNT, SelectorField.BASED_ON, SelectorField.MODE,
            SelectorField.WIGGLES_PER_SECOND, SelectorField.CORRELATION, SelectorField.TEMPORAL_PHASE, SelectorField.SPATIAL_PHASE,
            SelectorField.LOCK_DIMENSIONS, null, SelectorField.AMOUNT, SelectorField.ENABLED)
    }
}

/** Tile caption: a compact value (at most five characters), shared with the Character tiles. */
internal object AnimateCaption {
    fun scalar(limit: PropertyLimit, value: Float) = tileCaption(limit, value)
    fun vector(limit: PropertyLimit, x: Float, y: Float): String {
        fun short(v: Float) = PropertyLimits.format(limit, v).let { if (it.length > 3) tileCaption(limit, v).take(3) else it }
        return if (x == y) short(x) else "${short(x)},${short(y)}".take(7)
    }
    /** Unsigned 64-bit seed, shortened. */
    fun seed(value: Long): String = java.lang.Long.toUnsignedString(value).let { if (it.length <= 5) it else it.take(4) + "…" }
    fun character(value: Float): String {
        val cp = kotlin.math.round(value).toInt()
        return if (cp in 33..0x10FFFF && Character.isDefined(cp) && !Character.isISOControl(cp) && Character.getType(cp) != Character.SURROGATE.toInt())
            String(Character.toChars(cp)) else "U+%X".format(cp.coerceAtLeast(0)).take(5)
    }
}

/**
 * Exact Animate page geometry (dp) for one body. Top to bottom: animator strip (one pitch), sub-switch row (one pitch),
 * group gap, content rows (whole pitches). Horizontally the content is [columns] equal cells. Pure arithmetic; JVM tested
 * at 268 / 328 and font scales 1 / 1.3. The page never scrolls; only the animator and selector strips scroll sideways.
 */
internal data class AnimateMetrics(val body: Float, val width: Float, val compact: Boolean) {
    val tile get() = if (compact) TextInspectorBudget.TILE_COMPACT else TextInspectorBudget.TILE_STANDARD
    val gap get() = TextInspectorBudget.GRID_GAP
    val groupGap get() = TextInspectorBudget.GROUP_GAP
    val pitch get() = tile + gap
    val columns get() = kotlin.math.max(4, kotlin.math.floor((width + 1e-3f) / MIN_CELL).toInt()).coerceAtMost(8)
    val cell get() = width / columns
    val strip get() = pitch
    val switchRow get() = pitch
    val content get() = (body - strip - switchRow - groupGap).coerceAtLeast(0f)
    /** Whole content rows; at least one. */
    val rows get() = kotlin.math.floor((content + 1e-3f) / pitch).toInt().coerceAtLeast(1)
    /** Leftover content height, split above and below the rows so the group sits centred. */
    val slack get() = (content - rows * pitch).coerceAtLeast(0f)
    val fits get() = strip + switchRow + groupGap + rows * pitch <= body + 1e-3f && pitch >= TextInspectorBudget.MIN_TARGET && cell >= TextInspectorBudget.MIN_TARGET
    /** Property grid capacity (the add tile included). */
    val gridCells get() = rows * columns
    /** Selectors tab: selector strip, then the selected selector's controls. */
    val selectorRows get() = (rows - 1).coerceAtLeast(1)
    companion object { const val MIN_CELL = 48f }
}

/**
 * Lays cells into pages of [capacity]. When everything fits there is one page. Otherwise each page keeps its last cell
 * for the pager tile, so no cell is ever hidden behind a scroll.
 */
internal object AnimatePaging {
    fun <T> pages(items: List<T>, capacity: Int): List<List<T>> {
        val c = capacity.coerceAtLeast(2)
        if (items.size <= c) return listOf(items)
        return items.chunked(c - 1)
    }
}

/**
 * Selector face: Range keeps [primary] tiles on the face (after the handle strip) and the rest behind "More";
 * Wiggly has no strip, so all rows hold tiles. [capacity] counts tile cells, the More tile included.
 */
internal data class SelectorFace(val strip: Boolean, val face: List<SelectorField?>, val more: List<SelectorField?>)
internal object SelectorFaces {
    fun layout(kind: TextSelectorKind, rows: Int, columns: Int): SelectorFace {
        val tiles = AnimateFields.selectorTiles(kind)
        val strip = kind == TextSelectorKind.RANGE
        val capacity = ((if (strip) rows - 1 else rows).coerceAtLeast(1)) * columns
        return if (tiles.size <= capacity) SelectorFace(strip, tiles, emptyList())
        else SelectorFace(strip, tiles.take(capacity - 1), tiles.drop(capacity - 1))
    }
}

/** Transient Animate page selection: never document state, never undo. Resolves stale ids after undo or delete. */
internal object AnimateSelection {
    fun animator(text: TextProperties, id: Long?): TextAnimator? = text.animators.firstOrNull { it.id == id } ?: text.animators.firstOrNull()
    fun selector(animator: TextAnimator?, id: Long?): TextRangeSelector? = animator?.selectors?.let { list -> list.firstOrNull { it.id == id } ?: list.firstOrNull() }
    /** The focused field, only while it still exists and belongs to the shown animator. */
    fun focus(text: TextProperties, animator: TextAnimator?, address: AnimatorTrackAddress?): AnimatorTrackAddress? =
        address?.takeIf { animator != null && it.animatorId == animator.id && TextAnimatorEdits.exists(text, it) }
}

/** Saved state for the focused field: a token list (bundle-safe), restored only while the field still exists. */
internal object AnimateAddressCodec {
    fun save(address: AnimatorTrackAddress?): List<Any> = if (address == null) listOf("none") else listOf("field", address.animatorId,
        address.selectorId ?: 0L, address.field?.name ?: "", address.property?.name ?: "", address.grouping, address.seed)
    fun restore(values: List<Any>): AnimatorTrackAddress? {
        if (values.firstOrNull() != "field") return null
        val selector = (values[2] as Long).takeIf { it != 0L }
        return AnimatorTrackAddress(values[1] as Long, selector, (values[3] as String).takeIf { it.isNotEmpty() }?.let(SelectorField::valueOf),
            (values[4] as String).takeIf { it.isNotEmpty() }?.let(AnimatorProperty::valueOf), values[5] as Boolean, values[6] as Boolean)
    }
    val saver = Saver<AnimatorTrackAddress?, List<Any>>(save = { save(it) }, restore = { runCatching { restore(it) }.getOrNull() })
}

/** Rail labels for an animator field (the rail itself is the shared [InspectorUtilityRail]). */
internal object AnimateRail {
    fun keyIcon(address: AnimatorTrackAddress?, animated: Boolean, onKey: Boolean): String {
        val hold = address != null && TextAnimatorEdits.isHold(address)
        return when { address == null || !animated -> "KEY"; onKey -> if (hold) "HOLD_ON" else "KEY_ON"; else -> if (hold) "HOLD_ANIM" else "KEY_ANIM" }
    }
    fun keyLabel(name: String?, animated: Boolean, onKey: Boolean) = when {
        name == null -> "Choose an animator field to keyframe"
        !animated -> "Enable $name animation at playhead"
        onKey -> "Remove $name keyframe at playhead"
        else -> "Add $name keyframe at playhead"
    }
    fun keyState(name: String?, hold: Boolean, animated: Boolean, onKey: Boolean, timecode: String) = when {
        name == null -> "No animator field selected"
        !animated -> "$name: animation off"
        onKey -> "$name: on key at $timecode" + if (hold) ", Hold" else ""
        else -> "$name: animated, between keys" + if (hold) ", Hold" else ""
    }
}
