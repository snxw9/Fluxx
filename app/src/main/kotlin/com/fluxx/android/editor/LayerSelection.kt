package com.fluxx.android.editor

import com.fluxx.android.model.CompositionLayer
import com.fluxx.android.model.ProjectDocument

/**
 * TLP T0/T1: the timeline's multi-layer selection, an ORDERED set of layer ids in selection order. [primary] is the last
 * one selected. Editor / UI state only: never part of the document or of undo history (see docs/checkpoints/TLP.md §T0).
 */
data class LayerSelection(val ids: List<Long>) {
    init { require(ids.distinct().size == ids.size) { "A layer is selected at most once" } }
    val primary: Long? get() = ids.lastOrNull()
    val size get() = ids.size
    val isEmpty get() = ids.isEmpty()
    operator fun contains(id: Long) = id in ids
    /** Adds [id] at the end (it becomes the primary); an already selected id moves to the end. */
    fun add(id: Long) = LayerSelection(ids.filterNot { it == id } + id)
    fun remove(id: Long) = LayerSelection(ids.filterNot { it == id })
    fun toggle(id: Long) = if (id in ids) remove(id) else add(id)
    /** Drops ids whose layers no longer exist (after undo, redo, delete), keeping the order. */
    fun prune(existing: Collection<Long>) = LayerSelection(ids.filter { it in existing })

    companion object {
        fun of(vararg ids: Long) = LayerSelection(ids.toList())
    }
}

/** Layer order and selection helpers shared by the timeline, the bars and the JVM tests. */
object LayerOrder {
    /** Timeline order, top first: front to back, as the timeline lists rows (zero-length clips are not listed). */
    fun timeline(layers: List<CompositionLayer>): List<CompositionLayer> =
        layers.filter { (it.timing.durationUs ?: 1L) > 0L }.sortedWith(compareByDescending<CompositionLayer> { it.zOrder }.thenByDescending { it.id })

    fun all(layers: List<CompositionLayer>) = LayerSelection(timeline(layers).map { it.id })
    /** Positions 1, 3, 5 … counted from the top over all layers in timeline order (replaces the selection). */
    fun odd(layers: List<CompositionLayer>) = LayerSelection(timeline(layers).filterIndexed { i, _ -> i % 2 == 0 }.map { it.id })
    /** Positions 2, 4, 6 … counted from the top. */
    fun even(layers: List<CompositionLayer>) = LayerSelection(timeline(layers).filterIndexed { i, _ -> i % 2 == 1 }.map { it.id })
    /** Every listed layer that is not selected, in timeline order. */
    fun invert(layers: List<CompositionLayer>, selection: LayerSelection) =
        LayerSelection(timeline(layers).map { it.id }.filterNot { it in selection })
}

/** TLP: multi-layer edits, each ONE [EditorAction.Batch] of existing actions (one undo). */
object MultiLayerEdits {
    private fun layers(project: ProjectDocument, selection: LayerSelection) =
        selection.ids.mapNotNull { id -> project.composition.layers.firstOrNull { it.id == id } }

    /**
     * T1: the eye of a SELECTED layer sets visibility on every selected layer to the inverse of the tapped layer's state.
     * Layers already in that state get no action. Null when nothing changes.
     */
    fun visibility(project: ProjectDocument, selection: LayerSelection, tappedId: Long): EditorAction? {
        val tapped = project.composition.layers.firstOrNull { it.id == tappedId } ?: return null
        val target = !tapped.visible
        val edits = layers(project, selection).filter { it.visible != target }.map { EditorAction.SetVisibility(it.id, target) }
        return edits.takeIf { it.isNotEmpty() }?.let { EditorAction.Batch(it) }
    }
}

/** TLP T1: the two zones of the D-button (left: the eye; right: the arrow). */
enum class DZone { EYE, ARROW }
/** What a D-button or layer-body touch does. */
enum class DEffect { TOGGLE_VISIBILITY, BULK_VISIBILITY, ENTER, ADD, REMOVE, EXIT, NONE }

/**
 * TLP T1 rules (pure, JVM tested). Outside selection mode: the eye toggles that layer, the arrow is disabled, a long press
 * enters selection mode with the layer. In selection mode: any tap on an UNSELECTED layer's D-button adds it; on a
 * SELECTED one the eye sets every selected layer's visibility and the arrow (bulk expand, TL3) is disabled; a long press
 * on a selected D-button removes it. Removing the last layer exits the mode ([afterRemove]).
 */
object DButtonRules {
    fun tap(selectionMode: Boolean, selected: Boolean, zone: DZone): DEffect = when {
        !selectionMode -> if (zone == DZone.EYE) DEffect.TOGGLE_VISIBILITY else DEffect.NONE
        !selected -> DEffect.ADD
        zone == DZone.EYE -> DEffect.BULK_VISIBILITY
        else -> DEffect.NONE // the arrow: bulk expand arrives with TL3
    }
    fun longPress(selectionMode: Boolean, selected: Boolean): DEffect = when {
        !selectionMode -> DEffect.ENTER
        selected -> DEffect.REMOVE
        else -> DEffect.ADD
    }
    /** A tap on a layer's body (clip). Outside selection mode: null (the existing select-and-open-inspector rule). */
    fun body(selectionMode: Boolean, selected: Boolean): DEffect? = when {
        !selectionMode -> null
        selected -> DEffect.REMOVE
        else -> DEffect.ADD
    }
    /** A tap on empty timeline space: in selection mode it deselects all and exits; outside, the existing rule. */
    fun empty(selectionMode: Boolean): DEffect? = if (selectionMode) DEffect.EXIT else null
    /** The selection after removing [id]; null = selection mode exits (the last layer was deselected). */
    fun afterRemove(selection: LayerSelection, id: Long): LayerSelection? = selection.remove(id).takeUnless { it.isEmpty }
}
