package com.fluxx.android.editor

import com.fluxx.android.model.*
import com.fluxx.android.ui.inspector.AnimateFields
import com.fluxx.android.ui.inspector.GraphValues

/** TL3: what a twirl-down row's keys hold (decides the key popover and the colour bar). */
enum class TwirlValueKind { SCALAR, VECTOR, COLOUR, TEXT, HOLD }

/** TL3 B1: one row of a layer's twirl-down (UI only). [key] is stable per layer and row. */
sealed interface TwirlRow {
    val layerId: Long
    val key: String
    val heightDp: Float get() = TwirlRows.ROW_DP

    /** A keyframed property (at least one key): its inspector icon and name. */
    data class Property(override val layerId: Long, val target: GraphTarget, val name: String, val icon: String, val kind: TwirlValueKind) : TwirlRow {
        override val key get() = "tw|$layerId|$target"
    }
    /** B5: the layer's audio (a waveform), always the first row of a layer with audio. */
    data class Audio(override val layerId: Long) : TwirlRow {
        override val key get() = "tw|$layerId|audio"
        override val heightDp get() = TwirlRows.AUDIO_DP
    }
    /** A layer with no keyed property and no audio: one dim row. */
    data class Empty(override val layerId: Long) : TwirlRow {
        override val key get() = "tw|$layerId|empty"
    }
}

/**
 * TL3 B1: a layer's twirl-down contents (pure, JVM tested). One row per KEYFRAMED property in the inspector's order
 * (Transform: Position, Scale, Rotation; Opacity; then the text properties; then every animator, in stack order, with its
 * Enabled / Grouping tracks, its properties and its selectors' fields). Properties with no keys never appear. A layer
 * with audio always gets the audio row first. Animator selector Seeds (integer seed keys) are not shown (see TL3.md).
 */
object TwirlRows {
    const val ROW_DP = 28f
    /** TL3.1 B6: the audio row shows only the upper half (a rectified envelope), so it is about half as tall. */
    const val AUDIO_DP = 32f
    const val EMPTY_TEXT = "No animated properties"

    private val TRANSFORM = listOf(
        Triple(AnimPropertyType.POSITION, "Position", "POSITION"), Triple(AnimPropertyType.SCALE, "Scale", "SCALE"),
        Triple(AnimPropertyType.ROTATION, "Rotation", "ROTATION"), Triple(AnimPropertyType.OPACITY, "Opacity", "OPACITY"))
    private val TEXT = listOf(
        Triple(AnimPropertyType.SOURCE_TEXT, "Source Text", "SOURCE"), Triple(AnimPropertyType.FONT_SIZE, "Font size", "SIZE"),
        Triple(AnimPropertyType.FILL_COLOUR, "Fill colour", "FILL"), Triple(AnimPropertyType.STROKE_COLOUR, "Stroke colour", "STROKE"),
        Triple(AnimPropertyType.STROKE_WIDTH, "Stroke width", "STROKE_WIDTH"), Triple(AnimPropertyType.STROKE_OVER_FILL, "Stroke over fill", "ORDER"),
        Triple(AnimPropertyType.TRACKING, "Tracking", "TRACKING"), Triple(AnimPropertyType.LEADING, "Leading", "LEADING"),
        Triple(AnimPropertyType.BASELINE_SHIFT, "Baseline shift", "BASELINE"), Triple(AnimPropertyType.LIGATURES, "Ligatures", "LIGATURES"))

    fun kind(layer: CompositionLayer, target: GraphTarget): TwirlValueKind {
        target.animator?.let { a ->
            if (a.property != null) return when (a.property.kind) { 2 -> TwirlValueKind.VECTOR; 3 -> TwirlValueKind.COLOUR; else -> TwirlValueKind.SCALAR }
            return if (TextAnimatorEdits.isHold(a)) TwirlValueKind.HOLD else TwirlValueKind.SCALAR
        }
        return when (target.property!!) {
            AnimPropertyType.POSITION, AnimPropertyType.SCALE -> TwirlValueKind.VECTOR
            AnimPropertyType.FILL_COLOUR, AnimPropertyType.STROKE_COLOUR -> TwirlValueKind.COLOUR
            AnimPropertyType.SOURCE_TEXT -> TwirlValueKind.TEXT
            AnimPropertyType.STROKE_OVER_FILL, AnimPropertyType.LIGATURES -> TwirlValueKind.HOLD
            else -> if (GraphValues.channels(layer, target) == 0) TwirlValueKind.HOLD else TwirlValueKind.SCALAR
        }
    }

    /** Every target of [layer] that CAN hold keys, in inspector order, with its row name and icon. */
    fun targets(layer: CompositionLayer): List<Triple<GraphTarget, String, String>> = buildList {
        for ((p, name, icon) in TRANSFORM) add(Triple(GraphTarget(property = p), name, icon))
        if (layer.type != LayerType.TEXT) return@buildList
        for ((p, name, icon) in TEXT) add(Triple(GraphTarget(property = p), name, icon))
        for (a in layer.text.animators) {
            val prefix = a.name.ifBlank { "Animator" }
            add(Triple(GraphTarget(animator = AnimatorTrackAddress(a.id)), "$prefix on", "ENABLED"))
            add(Triple(GraphTarget(animator = AnimatorTrackAddress(a.id, grouping = true)), "$prefix grouping", "GROUPING"))
            for (o in a.properties) {
                val spec = AnimateFields.property(o.property)
                add(Triple(GraphTarget(animator = AnimatorTrackAddress(a.id, property = o.property)), "$prefix ${spec.label.lowercase()}", spec.icon))
            }
            for (s in a.selectors) for (f in SelectorField.entries) {
                val spec = AnimateFields.selector(f)
                add(Triple(GraphTarget(animator = AnimatorTrackAddress(a.id, selectorId = s.id, field = f)), "$prefix ${spec.label.lowercase()}", spec.icon))
            }
        }
    }

    /** The rows of [layer]'s twirl-down. [hasAudio]: the layer carries audio (B5, capability based). */
    fun rows(layer: CompositionLayer, hasAudio: Boolean): List<TwirlRow> {
        val keyed = targets(layer).filter { (t, _, _) -> keyTimes(layer, t).isNotEmpty() }
            .map { (t, name, icon) -> TwirlRow.Property(layer.id, t, name, icon, kind(layer, t)) }
        val audio = if (hasAudio) listOf(TwirlRow.Audio(layer.id)) else emptyList()
        return if (keyed.isEmpty() && audio.isEmpty()) listOf(TwirlRow.Empty(layer.id)) else audio + keyed
    }

    /** Local key times of one target (every kind: numbers, colours, Source Text, hold fields). */
    fun keyTimes(layer: CompositionLayer, target: GraphTarget): List<Long> = GraphModel.keyTimes(layer, target)

    /** The timeline items: each layer, then (when expanded) its rows. */
    fun items(layers: List<CompositionLayer>, expanded: Set<Long>, hasAudio: (CompositionLayer) -> Boolean): List<TwirlItem> = buildList {
        for (l in layers) {
            add(TwirlItem.Layer(l))
            if (l.id in expanded) for (r in rows(l, hasAudio(l))) add(TwirlItem.Row(r))
        }
    }

    /**
     * The offset table of [items] (cumulative y in dp, one entry per item plus the total), so layout, scrolling and
     * hit-testing find the visible range in O(log n) by binary search.
     */
    fun offsets(items: List<TwirlItem>): FloatArray {
        val out = FloatArray(items.size + 1)
        for (i in items.indices) out[i + 1] = out[i] + items[i].heightDp
        return out
    }

    /** The index of the item at [y] dp in [offsets] (binary search), clamped to the items. */
    fun indexAt(offsets: FloatArray, y: Float): Int {
        if (offsets.size < 2) return 0
        var lo = 0; var hi = offsets.size - 2
        while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (offsets[mid] <= y) lo = mid else hi = mid - 1 }
        return lo
    }
}

/** TL3 B1: one timeline item: a layer row or one of an expanded layer's rows. */
sealed interface TwirlItem {
    val heightDp: Float
    data class Layer(val layer: CompositionLayer) : TwirlItem { override val heightDp get() = TwirlRows.ROW_DP }
    data class Row(val row: TwirlRow) : TwirlItem { override val heightDp get() = row.heightDp }
}

/**
 * TL3 B1: which layers are expanded (UI state only: never in undo history, never saved). Pure rules, JVM tested.
 */
object TwirlExpansion {
    /** Prune layers that no longer exist. */
    fun prune(expanded: Set<Long>, project: ProjectDocument): Set<Long> {
        val ids = project.composition.layers.mapTo(HashSet()) { it.id }
        return expanded.filterTo(LinkedHashSet()) { it in ids }
    }

    /**
     * The arrow on [tapped]. Outside layer selection mode it toggles that layer. On a SELECTED layer in selection mode it
     * sets ALL selected layers to the inverse of the tapped layer's state (like the eye).
     */
    fun arrow(expanded: Set<Long>, tapped: Long, selection: LayerSelection?): Set<Long> {
        val next = tapped !in expanded
        val ids = if (selection != null && tapped in selection) selection.ids else listOf(tapped)
        return if (next) expanded + ids else expanded - ids.toSet()
    }
}
