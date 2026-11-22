package com.fluxx.android.ui.inspector

import androidx.compose.runtime.saveable.Saver
import com.fluxx.android.editor.*
import com.fluxx.android.model.*

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
        AnimatorProperty.SCALE -> AnimateFieldSpec("Scale", "SCALE", AnimateEditor.SCALE, PropertyLimits.ANIM_SCALE, short = "Scale")
        AnimatorProperty.ROTATION -> AnimateFieldSpec("Rotation", "ROTATION", AnimateEditor.ROTATION, PropertyLimits.ANIM_ROTATION, short = "Rot")
        AnimatorProperty.OPACITY -> AnimateFieldSpec("Opacity", "OPACITY", AnimateEditor.SLIDER, PropertyLimits.ANIM_PERCENT.copy(name = "Opacity", default = 100f), short = "Opac")
        AnimatorProperty.SKEW -> AnimateFieldSpec("Skew", "SKEW", AnimateEditor.SLIDER, PropertyLimits.ANIM_SKEW, short = "Skew")
        AnimatorProperty.SKEW_AXIS -> AnimateFieldSpec("Skew axis", "SKEW_AXIS", AnimateEditor.SLIDER, PropertyLimits.ANIM_ANGLE.copy(name = "Skew axis"), short = "Axis")
        AnimatorProperty.ANCHOR -> AnimateFieldSpec("Anchor point", "ANCHOR", AnimateEditor.VECTOR, PropertyLimits.ANIM_OFFSET.copy(name = "Anchor point"), short = "Anch")
        AnimatorProperty.FILL -> AnimateFieldSpec("Fill colour", "FILL", AnimateEditor.COLOUR, short = "Fill")
        AnimatorProperty.STROKE_COLOUR -> AnimateFieldSpec("Stroke colour", "STROKE", AnimateEditor.COLOUR, short = "Strk")
        AnimatorProperty.STROKE_WIDTH -> AnimateFieldSpec("Stroke width", "STROKE_WIDTH", AnimateEditor.SLIDER, PropertyLimits.STROKE_DELTA.copy(name = "Stroke width"), short = "Width")
        AnimatorProperty.TRACKING -> AnimateFieldSpec("Tracking", "TRACKING", AnimateEditor.SLIDER, PropertyLimits.ANIM_TRACKING, short = "Track")
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
        SelectorField.ENABLED -> AnimateFieldSpec("Selector on", "EYE", AnimateEditor.TOGGLE, short = "On")
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
    val ANIMATOR_ENABLED = AnimateFieldSpec("Animator on", "EYE", AnimateEditor.TOGGLE, short = "On")
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

    /**
     * Add-property popover groups (TEXT F3.1), in order. Colour has a collapsed Colour shift sub-group (relative hue /
     * saturation / brightness shifts of the existing colour, not second colour properties); Advanced is collapsed.
     * Every [AnimatorProperty] appears exactly once.
     */
    val GROUPS: List<AnimatePropertyGroup> = listOf(
        AnimatePropertyGroup("Transform", listOf(AnimatorProperty.ANCHOR, AnimatorProperty.POSITION, AnimatorProperty.SCALE, AnimatorProperty.SKEW,
            AnimatorProperty.SKEW_AXIS, AnimatorProperty.ROTATION, AnimatorProperty.OPACITY)),
        AnimatePropertyGroup("Colour", listOf(AnimatorProperty.FILL, AnimatorProperty.STROKE_COLOUR, AnimatorProperty.FILL_OPACITY,
            AnimatorProperty.STROKE_OPACITY, AnimatorProperty.STROKE_WIDTH),
            sub = AnimatePropertyGroup("Colour shift", listOf(AnimatorProperty.FILL_HUE, AnimatorProperty.FILL_SATURATION, AnimatorProperty.FILL_BRIGHTNESS,
                AnimatorProperty.STROKE_HUE, AnimatorProperty.STROKE_SATURATION, AnimatorProperty.STROKE_BRIGHTNESS), collapsed = true)),
        AnimatePropertyGroup("Text", listOf(AnimatorProperty.TRACKING, AnimatorProperty.LINE_ANCHOR, AnimatorProperty.LINE_SPACING,
            AnimatorProperty.GROUPING_ALIGNMENT, AnimatorProperty.BLUR)),
        AnimatePropertyGroup("Advanced", listOf(AnimatorProperty.CHARACTER_OFFSET, AnimatorProperty.CHARACTER_VALUE), collapsed = true))
    /** Page group header of a property: Transform / Colour (colour shifts included) / Text / Advanced. */
    fun pageGroup(p: AnimatorProperty): String = GROUPS.first { p in it.all }.name

    /**
     * Selector tiles (TEXT F3.1). `null` is the random seed tile (the seed is not a [SelectorField]). The on/off
     * ([SelectorField.ENABLED]) lives on the selector bar; [SelectorField.KIND] is chosen when the selector is added.
     * Range hides Min / Max amount (redundant with Amount; After Effects' Range selector has only Amount) and shows
     * Smoothness only while the Shape is Square (the engine ignores it otherwise). Wiggly hides Amount (it only scales
     * Min and Max; After Effects' Wiggly has none). Hidden fields stay in the document and the engine still reads them.
     */
    fun primary(kind: TextSelectorKind): List<SelectorField> = when (kind) {
        TextSelectorKind.RANGE -> listOf(SelectorField.START, SelectorField.END, SelectorField.OFFSET, SelectorField.SHAPE)
        TextSelectorKind.WIGGLY -> listOf(SelectorField.MAX_AMOUNT, SelectorField.MIN_AMOUNT, SelectorField.WIGGLES_PER_SECOND, SelectorField.CORRELATION)
    }
    fun advanced(kind: TextSelectorKind, square: Boolean): List<SelectorField?> = when (kind) {
        TextSelectorKind.RANGE -> listOfNotNull(SelectorField.UNITS, SelectorField.BASED_ON, SelectorField.MODE, SelectorField.AMOUNT,
            SelectorField.SMOOTHNESS.takeIf { square }, SelectorField.EASE_HIGH, SelectorField.EASE_LOW, SelectorField.RANDOMIZE_ORDER) + listOf(null)
        TextSelectorKind.WIGGLY -> listOf(SelectorField.BASED_ON, SelectorField.MODE, SelectorField.TEMPORAL_PHASE, SelectorField.SPATIAL_PHASE,
            SelectorField.LOCK_DIMENSIONS, null)
    }
    /** Fields the UI never shows as tiles for [kind] (besides ENABLED, which is the bar eye, and KIND, fixed at add time). */
    fun hidden(kind: TextSelectorKind): List<SelectorField> = when (kind) {
        TextSelectorKind.RANGE -> listOf(SelectorField.MIN_AMOUNT, SelectorField.MAX_AMOUNT)
        TextSelectorKind.WIGGLY -> listOf(SelectorField.AMOUNT)
    }
    /** Every tile a selector can show (Smoothness included), for inventory checks. */
    fun selectorTiles(kind: TextSelectorKind): List<SelectorField?> = primary(kind) + advanced(kind, true)
    /** The Advanced tiles of [s] at [local]: Smoothness only while the Shape is Square. */
    fun advanced(s: TextRangeSelector, local: Long): List<SelectorField?> =
        advanced(TextAnimatorEdits.kind(s), s.shape.hold(local) == SelectorShape.SQUARE.ordinal.toFloat())
}

/** One add-property popover group; [sub] is a nested collapsible group, [collapsed] its default state. */
internal data class AnimatePropertyGroup(val name: String, val properties: List<AnimatorProperty>, val sub: AnimatePropertyGroup? = null,
    val collapsed: Boolean = false) {
    val all: List<AnimatorProperty> get() = properties + sub?.properties.orEmpty()
}

/**
 * Hidden selector fields whose stored value differs from the backend default or is keyed. The engine still honours
 * them; the selector's Advanced group shows one hint tile (with a one-undo Reset) only while this list is not empty.
 */
internal object AnimateHidden {
    fun active(s: TextRangeSelector): List<SelectorField> = AnimateFields.hidden(TextAnimatorEdits.kind(s)).filter { f ->
        val t = s.tracks[f.ordinal]
        t.isAnimated || t.staticValue != TextRangeSelector(1).tracks[f.ordinal].staticValue
    }
    /** One edit that resets every active hidden field of [s] to its default and drops its keys. */
    fun reset(animatorId: Long, s: TextRangeSelector): AnimatorEdit.PutSelector {
        val defaults = TextRangeSelector(1).tracks
        var out = s
        for (f in active(s)) out = TextAnimatorEdits.withField(out, f, defaults[f.ordinal])
        return AnimatorEdit.PutSelector(animatorId, out)
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
 * Animate page geometry (dp) for one body (TEXT F3.1). Every row (add, bars, row labels, group headers, tile rows) is
 * one [pitch]; tiles sit in [columns] equal cells. The body scrolls vertically when the open content is taller than
 * it (the one deviation from the no-scroll sheet rule); the sheet height never changes. Pure arithmetic, JVM tested.
 */
internal data class AnimateMetrics(val body: Float, val width: Float, val compact: Boolean) {
    val tile get() = if (compact) TextInspectorBudget.TILE_COMPACT else TextInspectorBudget.TILE_STANDARD
    val gap get() = TextInspectorBudget.GRID_GAP
    val pitch get() = tile + gap
    val columns get() = kotlin.math.max(4, kotlin.math.floor((width + 1e-3f) / MIN_CELL).toInt()).coerceAtMost(8)
    val cell get() = width / columns
    /** Whole rows visible at once without scrolling. */
    val visibleRows get() = kotlin.math.floor((body + 1e-3f) / pitch).toInt()
    fun tileRows(cells: Int) = (cells + columns - 1) / columns
    /** Cells and pitch are touch sized and the body shows at least the add row plus one animator bar. */
    val fits get() = pitch >= TextInspectorBudget.MIN_TARGET && cell >= TextInspectorBudget.MIN_TARGET && visibleRows >= 2
    /** True when [rows] list rows are taller than the body (the body then scrolls; the sheet does not grow). */
    fun scrolls(rows: Int) = rows * pitch > body + 1e-3f
    companion object { const val MIN_CELL = 48f }
}

/**
 * Transient bar state of the Animate list: never document state, never undo. One animator is expanded at a time;
 * selectors, property groups and Advanced groups expand independently. Ids that no longer exist are dropped. Focus
 * is held separately, so collapsing or expanding never loses it.
 */
internal data class AnimateBars(val animator: Long? = null, val selectors: Set<Long> = emptySet(),
    val collapsedGroups: Set<String> = emptySet(), val advanced: Set<Long> = emptySet()) {
    fun toggleAnimator(id: Long) = copy(animator = if (animator == id) null else id)
    fun expandAnimator(id: Long) = copy(animator = id)
    fun toggleSelector(id: Long) = copy(selectors = if (id in selectors) selectors - id else selectors + id)
    fun expandSelector(id: Long) = copy(selectors = selectors + id)
    fun toggleGroup(animatorId: Long, group: String) = groupKey(animatorId, group).let { k ->
        copy(collapsedGroups = if (k in collapsedGroups) collapsedGroups - k else collapsedGroups + k) }
    fun groupOpen(animatorId: Long, group: String) = groupKey(animatorId, group) !in collapsedGroups
    fun toggleAdvanced(selectorId: Long) = copy(advanced = if (selectorId in advanced) advanced - selectorId else advanced + selectorId)
    /** Drop ids that undo / delete removed. */
    fun resolve(text: TextProperties): AnimateBars {
        val animators = text.animators.map { it.id }.toSet()
        val selectorIds = text.animators.flatMap { a -> a.selectors.map { it.id } }.toSet()
        val next = AnimateBars(animator?.takeIf { it in animators }, selectors.filterTo(HashSet()) { it in selectorIds },
            collapsedGroups.filterTo(HashSet()) { it.substringBefore(':').toLongOrNull() in animators }, advanced.filterTo(HashSet()) { it in selectorIds })
        return if (next == this) this else next
    }
    fun save(): List<Any> = listOf(animator ?: 0L, selectors.toLongArray(), ArrayList(collapsedGroups), advanced.toLongArray())
    companion object {
        fun groupKey(animatorId: Long, group: String) = "$animatorId:$group"
        @Suppress("UNCHECKED_CAST")
        fun restore(v: List<Any>) = AnimateBars((v[0] as Long).takeIf { it != 0L }, (v[1] as LongArray).toSet(),
            (v[2] as List<String>).toSet(), (v[3] as LongArray).toSet())
        val saver = Saver<AnimateBars, List<Any>>(save = { it.save() }, restore = { runCatching { restore(it) }.getOrNull() })
    }
}

/**
 * Grip reorder (TEXT F3.1), the timeline hierarchy rule: the target is the pointer's absolute position against the
 * siblings' slot centres captured when the drag began (never their animated positions); the dragged row follows the
 * finger. One preview per target change, one undo entry on release, cancel restores.
 */
internal object AnimateReorder {
    /** Target index of [dragged] among [order] (ids in their current order) for pointer [y] and the drag-start slot centres. */
    fun target(order: List<Long>, dragged: Long, centres: Map<Long, Float>, y: Float): Int =
        order.filter { it != dragged }.count { id -> (centres[id] ?: Float.MAX_VALUE) < y }
}

/** Animator Position / Rotation / Scale controls: slider popovers (default) or the Transform tab's pad, dial, rulers. */
internal enum class AnimateControls { SLIDERS, PAD;
    val label get() = if (this == SLIDERS) "Sliders" else "Pad, dial, rulers"
}

/** Key/value storage for app preferences (SharedPreferences on device, a map in JVM tests). */
internal interface PreferenceStore { fun get(key: String): String?; fun put(key: String, value: String) }

/** App preference (not project state, no schema): which [AnimateControls] the animator transform tiles use. Survives restart. */
internal class AnimateControlsPreference(private val store: PreferenceStore) {
    private val mutable = kotlinx.coroutines.flow.MutableStateFlow(read())
    val value: kotlinx.coroutines.flow.StateFlow<AnimateControls> = mutable
    private fun read() = store.get(KEY)?.let { runCatching { AnimateControls.valueOf(it) }.getOrNull() } ?: AnimateControls.SLIDERS
    fun set(controls: AnimateControls) { store.put(KEY, controls.name); mutable.value = controls }
    companion object {
        const val KEY = "animate_transform_controls"
        /** The tiles the preference applies to. */
        val PROPERTIES = setOf(AnimatorProperty.POSITION, AnimatorProperty.ROTATION, AnimatorProperty.SCALE)
        @Volatile private var instance: AnimateControlsPreference? = null
        /** Tests only: use [replacement] (an in-memory store) instead of the app's file; returns the previous instance. */
        internal fun replaceForTest(replacement: AnimateControlsPreference?): AnimateControlsPreference? = synchronized(this) { instance.also { instance = replacement } }
        fun get(context: android.content.Context): AnimateControlsPreference = instance ?: synchronized(this) {
            instance ?: AnimateControlsPreference(object : PreferenceStore {
                private val prefs = context.applicationContext.getSharedPreferences("inspector_preferences", android.content.Context.MODE_PRIVATE)
                override fun get(key: String) = prefs.getString(key, null)
                override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
            }).also { instance = it }
        }
    }
}

/** Transient Animate page focus: never document state, never undo. A focus survives collapsing; it clears when its field is gone. */
internal object AnimateSelection {
    fun focus(text: TextProperties, address: AnimatorTrackAddress?): AnimatorTrackAddress? =
        address?.takeIf { TextAnimatorEdits.exists(text, it) }
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
    /** The rail ⋮ entry that switches the Position / Rotation / Scale control style (shown on those fields only). */
    fun controlsLabel(current: AnimateControls) =
        "Controls: ${current.label} → ${(if (current == AnimateControls.SLIDERS) AnimateControls.PAD else AnimateControls.SLIDERS).label}"
}

/** One selector tile: a field, the seed ([field] null), or the hidden-values hint ([hint]). */
internal data class SelectorCell(val field: SelectorField?, val hint: Boolean = false)

/**
 * The Animate list, one [AnimateMetrics.pitch] per row (TEXT F3.1). Pure: built from the document and the transient
 * [AnimateBars], so expansion, grouping and the selector tile sets are JVM tested. [animatorId] / [selectorId] mark the
 * block a row belongs to (a dragged bar moves its whole block).
 */
internal sealed class AnimateRow(val key: String, val animatorId: Long? = null, val selectorId: Long? = null) {
    object Add : AnimateRow("add")
    class AnimatorBar(val animator: TextAnimator, val index: Int, val expanded: Boolean) : AnimateRow("a:${animator.id}", animator.id)
    /** TEXT F3.2: the Property bar, a dropdown header (chevron, count, trash for all tiles, add). */
    class PropertyLabel(id: Long, val full: Boolean, val count: Int = 0, val open: Boolean = true) : AnimateRow("p:$id", id)
    /** Property tiles in insertion order (the order added), [AnimateMetrics.columns] per row. */
    class PropertyTiles(id: Long, row: Int, val properties: List<AnimatorProperty>) : AnimateRow("pt:$id:$row", id)
    class SelectorLabel(id: Long, val full: Boolean) : AnimateRow("sl:$id", id)
    class SelectorBar(id: Long, val selector: TextRangeSelector, val index: Int, val expanded: Boolean) : AnimateRow("s:${selector.id}", id, selector.id)
    class SelectorTiles(id: Long, val selector: TextRangeSelector, section: String, row: Int, val cells: List<SelectorCell>) :
        AnimateRow("st:${selector.id}:$section:$row", id, selector.id)
    class AdvancedHeader(id: Long, val selector: TextRangeSelector, val open: Boolean) : AnimateRow("sa:${selector.id}", id, selector.id)
    class GroupingRow(id: Long) : AnimateRow("g:$id", id)
}

internal object AnimateRows {
    /** The Property bar's open/closed key in [AnimateBars.collapsedGroups]. */
    const val PROPERTIES = "Properties"
    /**
     * TEXT F3.2: with no animators the "+ Animator" bar is the first (only) row; otherwise it follows the last animator,
     * scrolling with the list, and (TEXT F3.3) only while every animator bar is collapsed. Property tiles keep insertion
     * order under one collapsible Property bar.
     */
    fun build(text: TextProperties, bars: AnimateBars, local: Long, columns: Int): List<AnimateRow> {
        val out = mutableListOf<AnimateRow>()
        if (text.animators.isEmpty()) out += AnimateRow.Add
        text.animators.forEachIndexed { i, a ->
            val open = bars.animator == a.id
            out += AnimateRow.AnimatorBar(a, i, open)
            if (!open) return@forEachIndexed
            val propertiesOpen = bars.groupOpen(a.id, PROPERTIES)
            out += AnimateRow.PropertyLabel(a.id, a.properties.size >= AnimatorProperty.entries.size, a.properties.size, propertiesOpen)
            if (propertiesOpen) a.properties.map { it.property }.chunked(columns).forEachIndexed { r, line -> out += AnimateRow.PropertyTiles(a.id, r, line) }
            out += AnimateRow.SelectorLabel(a.id, a.selectors.size >= TextAnimatorEdits.MAX_SELECTORS)
            a.selectors.forEachIndexed { j, s ->
                val sOpen = s.id in bars.selectors
                out += AnimateRow.SelectorBar(a.id, s, j, sOpen)
                if (!sOpen) return@forEachIndexed
                val kind = TextAnimatorEdits.kind(s)
                AnimateFields.primary(kind).map { SelectorCell(it) }.chunked(columns).forEachIndexed { r, line ->
                    out += AnimateRow.SelectorTiles(a.id, s, "p", r, line) }
                val advancedOpen = s.id in bars.advanced
                out += AnimateRow.AdvancedHeader(a.id, s, advancedOpen)
                if (advancedOpen) {
                    val cells = AnimateFields.advanced(s, local).map { SelectorCell(it) } +
                        if (AnimateHidden.active(s).isNotEmpty()) listOf(SelectorCell(null, hint = true)) else emptyList()
                    cells.chunked(columns).forEachIndexed { r, line -> out += AnimateRow.SelectorTiles(a.id, s, "x", r, line) }
                }
            }
            out += AnimateRow.GroupingRow(a.id)
        }
        // TEXT F3.3: hidden while any animator bar is expanded; it returns (at the bottom) once every bar is collapsed.
        if (text.animators.isNotEmpty() && text.animators.none { it.id == bars.animator }) out += AnimateRow.Add
        return out
    }
    /** Every selector field a built list shows (for the "hidden fields never appear" checks). */
    fun shownFields(rows: List<AnimateRow>): Set<SelectorField> =
        rows.filterIsInstance<AnimateRow.SelectorTiles>().flatMap { r -> r.cells.mapNotNull { it.field } }.toSet()
}

/** TEXT F3.2: the faded Animate glyph, centred and fixed in the body (smaller than F3.1's 60%). */
internal object AnimateGlyph { const val FRACTION = .4f }

/** Swipe-to-delete threshold (bars, selector bars, property tiles): past 40% of the width and at least [min] px. */
internal object AnimateSwipe {
    fun deletes(distance: Float, width: Float, min: Float) = kotlin.math.abs(distance) > maxOf(width * .4f, min)
}
