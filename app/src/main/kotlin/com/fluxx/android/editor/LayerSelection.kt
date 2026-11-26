package com.fluxx.android.editor

import com.fluxx.android.model.CompositionLayer
import com.fluxx.android.model.ProjectDocument
import com.fluxx.android.model.FrameRate

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
    /** TLP.1: the layer Stagger popover opens with Reverse ON, Sequence with Reverse OFF (every open; never persisted). */
    const val STAGGER_REVERSE_DEFAULT = true
    const val SEQUENCE_REVERSE_DEFAULT = false
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

    /**
     * T3 move together: every selected layer moves by the same number of FRAMES, so relative offsets are kept exactly.
     * [deltaUs] is the drag's raw delta; it becomes whole frames on the composition grid, clamped so the earliest selected
     * clip never goes before 0. A start that is on a frame lands on a frame; any other start moves by the same µs.
     * Built from the gesture's [base] project (absolute starts), so each preview replaces the last. Null if nothing moves.
     */
    fun moveTogether(base: ProjectDocument, selection: LayerSelection, deltaUs: Long): EditorAction? {
        val rate = base.composition.frameRate
        val moving = layers(base, selection).filter { it.timing.durationUs != 0L }
        if (moving.isEmpty()) return null
        val earliest = moving.minBy { it.timing.startUs }
        val fe = rate.nearestFrame(earliest.timing.startUs)
        val df = (rate.nearestFrame((earliest.timing.startUs + deltaUs).coerceAtLeast(0)) - fe).coerceAtLeast(-fe)
        if (df == 0L) return null
        val shiftUs = rate.frameTimeUs(fe + df) - rate.frameTimeUs(fe)
        return EditorAction.Batch(moving.map { l ->
            val s = l.timing.startUs; val f = rate.nearestFrame(s)
            val to = if (rate.frameTimeUs(f) == s) rate.frameTimeUs(f + df) else (s + shiftUs).coerceAtLeast(0)
            EditorAction.Move(l.id, to)
        })
    }

    /** T3: the contextual trim / move set is the PRIMARY layer's ([ClipQuickActions.available] at the playhead). */
    fun quickSet(project: ProjectDocument, selection: LayerSelection, playheadUs: Long): List<ClipQuickAction> {
        val primary = selection.primary?.let { id -> project.composition.layers.firstOrNull { it.id == id } } ?: return emptyList()
        return ClipQuickActions.available(primary, playheadUs)
    }

    /** A multi-layer quick action: the Batch for every selected layer where [kind] is valid in its own context, and the count skipped. */
    data class Applied(val action: EditorAction?, val skipped: Int)
    fun quick(kind: ClipQuickAction, project: ProjectDocument, selection: LayerSelection, playheadUs: Long): Applied {
        val edits = ArrayList<EditorAction>(); var skipped = 0
        for (l in layers(project, selection)) {
            val a = ClipQuickActions.action(kind, l, playheadUs, project.composition.durationUs)
            if (a == null) skipped++ else edits += a
        }
        return Applied(edits.takeIf { it.isNotEmpty() }?.let { EditorAction.Batch(it) }, skipped)
    }

    /** [base] + [frames] on the composition grid: whole frames from a frame-aligned [base], else the same µs shift. */
    private fun plusFrames(rate: FrameRate, base: Long, frames: Long): Long {
        val f = rate.nearestFrame(base)
        val to = if (rate.frameTimeUs(f) == base) { val t = f + frames; if (t >= 0) rate.frameTimeUs(t) else 0L }
            else base + (if (frames >= 0) rate.frameTimeUs(frames) else -rate.frameTimeUs(-frames))
        return to.coerceAtLeast(0)
    }
    private fun ordered(project: ProjectDocument, selection: LayerSelection, reverse: Boolean) =
        layers(project, selection).let { if (reverse) it.reversed() else it }

    /**
     * T4 Stagger, in SELECTION order (or reversed): layer 1 stays; layer k starts [frames] after layer k−1's start. Whole
     * clips move (duration and in-point kept) with existing [EditorAction.Move]s, one Batch. Null when nothing moves.
     */
    fun stagger(project: ProjectDocument, selection: LayerSelection, frames: Int, reverse: Boolean = STAGGER_REVERSE_DEFAULT): EditorAction? {
        require(frames >= 0) { "Stagger by zero or more frames" }
        val rate = project.composition.frameRate
        val list = ordered(project, selection, reverse)
        if (list.size < 2) return null
        var start = list[0].timing.startUs
        val moves = ArrayList<EditorAction>()
        for (l in list.drop(1)) {
            start = plusFrames(rate, start, frames.toLong())
            if (l.timing.startUs != start) moves += EditorAction.Move(l.id, start)
        }
        return moves.takeIf { it.isNotEmpty() }?.let { EditorAction.Batch(it) }
    }

    /**
     * T4 Sequence, end to end in SELECTION order (or reversed): each layer starts at the previous one's end plus [gapFrames]
     * (negative = overlap; never before 0). Layer 1 stays. An unresolved duration counts as zero. One Batch of Moves.
     */
    fun sequence(project: ProjectDocument, selection: LayerSelection, gapFrames: Int = 0, reverse: Boolean = SEQUENCE_REVERSE_DEFAULT): EditorAction? {
        val rate = project.composition.frameRate
        val list = ordered(project, selection, reverse)
        if (list.size < 2) return null
        var start = list[0].timing.startUs; var duration = list[0].timing.durationUs ?: 0L
        val moves = ArrayList<EditorAction>()
        for (l in list.drop(1)) {
            start = plusFrames(rate, start + duration, gapFrames.toLong())
            if (l.timing.startUs != start) moves += EditorAction.Move(l.id, start)
            duration = l.timing.durationUs ?: 0L
        }
        return moves.takeIf { it.isNotEmpty() }?.let { EditorAction.Batch(it) }
    }

    /** T3 Trash: every selected layer, one undo (the single-layer delete has no confirmation, so neither does this). */
    fun delete(selection: LayerSelection): EditorAction? =
        selection.ids.map { EditorAction.Remove(it) }.takeIf { it.isNotEmpty() }?.let { EditorAction.Batch(it) }
}

/** TLP T1: the two zones of the D-button (left: the eye; right: the arrow). */
enum class DZone { EYE, ARROW, BODY }

/**
 * TL3.1 A2: the D-button's tap zones. On a SELECTED layer in selection mode: the left [EYE_FRACTION] of the 56dp D is the
 * eye (bulk visibility), the next [BODY_FRACTION] the body (toggle = deselect), the last [ARROW_FRACTION] the arrow (bulk
 * expand); anything past 56dp (the widened D) and the name column is body too. Elsewhere (outside the mode, or an
 * unselected layer) the D keeps its two halves: eye | arrow.
 */
object DZones {
    const val WIDTH_DP = 56f
    const val EYE_FRACTION = .36f
    const val BODY_FRACTION = .28f
    const val ARROW_FRACTION = .36f
    fun zone(xDp: Float, selectionMode: Boolean, selected: Boolean): DZone {
        if (!(selectionMode && selected)) return if (xDp < WIDTH_DP / 2f) DZone.EYE else DZone.ARROW
        return when {
            xDp < WIDTH_DP * EYE_FRACTION -> DZone.EYE
            xDp < WIDTH_DP * (EYE_FRACTION + BODY_FRACTION) -> DZone.BODY
            xDp < WIDTH_DP -> DZone.ARROW
            else -> DZone.BODY
        }
    }
}
/** What a D-button or layer-body touch does. */
enum class DEffect { TOGGLE_VISIBILITY, BULK_VISIBILITY, ENTER, ADD, REMOVE, EXIT, NONE, EXPAND }

/**
 * TLP T1 rules (pure, JVM tested). Outside selection mode: the eye toggles that layer, the arrow is disabled, a long press
 * enters selection mode with the layer. In selection mode: any tap on an UNSELECTED layer's D-button adds it; on a
 * SELECTED one the eye sets every selected layer's visibility and the arrow (bulk expand, TL3) is disabled; a long press
 * on a selected D-button removes it. Removing the last layer exits the mode ([afterRemove]).
 */
object DButtonRules {
    fun tap(selectionMode: Boolean, selected: Boolean, zone: DZone): DEffect = when {
        // TL3 B1: the arrow toggles the twirl-down (on a selected layer in selection mode: every selected layer).
        !selectionMode -> when (zone) { DZone.EYE -> DEffect.TOGGLE_VISIBILITY; DZone.ARROW -> DEffect.EXPAND; DZone.BODY -> DEffect.NONE }
        !selected -> DEffect.ADD
        zone == DZone.EYE -> DEffect.BULK_VISIBILITY
        zone == DZone.BODY -> DEffect.REMOVE // TL3.1 A2: a tap on a selected D's body deselects it (however it was selected)
        else -> DEffect.EXPAND
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
