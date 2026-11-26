package com.fluxx.android.editor

import com.fluxx.android.model.*
import com.fluxx.android.ui.inspector.GraphConvert
import com.fluxx.android.ui.inspector.GraphValues

/**
 * TLP T5: a keyframe selection, scoped to ONE property's keys (the active property on the open property page) of one
 * layer. [times] are local (anchor-relative) key times in selection order. Editor / UI state only (never in undo history).
 */
data class KeyframeSelection(val layerId: Long, val target: GraphTarget, val times: List<Long>) {
    init { require(times.distinct().size == times.size) }
    val size get() = times.size
    operator fun contains(t: Long) = t in times
    fun toggle(t: Long) = copy(times = if (t in times) times - t else times + t)
}

/** One copied key: time relative to the first copied key, stored value(s), curve. */
data class CopiedKey(val relativeUs: Long, val x: Float, val y: Float, val curve: EasingCurve)
/** T5 key clipboard: keys of one property kind ([kind]); pasted only onto the same kind. */
data class KeyClipboard(val kind: String, val keys: List<CopiedKey>)

/** TLP T5 key edits (pure, JVM tested). Every multi-key edit is ONE [EditorAction.Batch] of existing actions. */
object KeyEdits {
    fun times(layer: CompositionLayer, target: GraphTarget): List<Long> = GraphModel.keyTimes(layer, target)
    fun selectAll(layer: CompositionLayer, target: GraphTarget) = KeyframeSelection(layer.id, target, times(layer, target))
    /** Positions 1, 3, 5 … / 2, 4, 6 … in time order within the property (replaces the selection). */
    fun odd(layer: CompositionLayer, target: GraphTarget) = KeyframeSelection(layer.id, target, times(layer, target).filterIndexed { i, _ -> i % 2 == 0 })
    fun even(layer: CompositionLayer, target: GraphTarget) = KeyframeSelection(layer.id, target, times(layer, target).filterIndexed { i, _ -> i % 2 == 1 })
    /** The selection with only keys that still exist; null when none do (key selection mode exits). */
    fun prune(project: ProjectDocument, sel: KeyframeSelection): KeyframeSelection? {
        val layer = project.composition.layers.firstOrNull { it.id == sel.layerId } ?: return null
        val existing = times(layer, sel.target).toSet()
        return sel.copy(times = sel.times.filter { it in existing }).takeIf { it.times.isNotEmpty() }
    }

    private fun remove(layer: CompositionLayer, target: GraphTarget, t: Long): EditorAction = target.animator?.let {
        EditorAction.EditTextAnimator(layer.id, AnimatorEdit.Key(it, AnimatorKeyOperation.REMOVE, t)) } ?: EditorAction.RemoveKeyframe(layer.id, target.property!!, t)
    private fun move(layer: CompositionLayer, target: GraphTarget, from: Long, to: Long): EditorAction = target.animator?.let {
        EditorAction.EditTextAnimator(layer.id, AnimatorEdit.Key(it, AnimatorKeyOperation.MOVE, from, to)) } ?: EditorAction.MoveKeyframe(layer.id, target.property, from, to)

    /** Trash: every selected key, one undo. */
    fun delete(layer: CompositionLayer, sel: KeyframeSelection): EditorAction? =
        sel.times.sorted().map { remove(layer, sel.target, it) }.takeIf { it.isNotEmpty() }?.let { EditorAction.Batch(it) }

    /** Easing buttons: the departing segment of every selected key, one [EditorAction.SetKeyEasing] in one Batch. Null for hold-only tracks. */
    fun ease(layer: CompositionLayer, sel: KeyframeSelection, curve: EasingCurve): EditorAction? {
        if (GraphModel.discrete(sel.target) || sel.times.isEmpty()) return null
        return EditorAction.Batch(listOf(GraphModel.action(layer.id, sel.target, sel.times.sorted(), curve)))
    }

    // ---- Frames (local times on the composition grid, through the layer's keyframe anchor) ----------------------------
    fun frame(layer: CompositionLayer, rate: FrameRate, local: Long): Long = Markers.signedFrame(rate, Math.addExact(layer.resolvedKeyframeAnchorUs, local))
    fun timeOf(layer: CompositionLayer, rate: FrameRate, frame: Long): Long {
        val absolute = if (frame >= 0) rate.frameTimeUs(frame) else Math.negateExact(rate.frameTimeUs(Math.negateExact(frame)))
        return Math.subtractExact(absolute, layer.resolvedKeyframeAnchorUs)
    }

    /** The result of a key move: the action and the selected keys' new times (the selection follows its keys). */
    data class Moved(val action: EditorAction, val times: List<Long>)

    /**
     * T5 Sequence ([spacingFrames] = 1) and Stagger (N): the selected keys, in time order, packed [spacingFrames] frames
     * apart, anchored on the first selected key (which stays where it is). Unselected keys are not touched by the plan,
     * but a moved key that lands on an unselected key's time REPLACES it: that is the existing [EditorAction.MoveKeyframe]
     * (and animator Key MOVE) behaviour, which drops any key already at the target time. The moves are ordered so the
     * selected keys never collide with each other mid-batch (left-moving keys first, earliest first; then right-moving
     * keys, latest first). X/Y keys move both axes (the whole key). One Batch, one undo. Null when nothing moves.
     */
    fun pack(project: ProjectDocument, layer: CompositionLayer, sel: KeyframeSelection, spacingFrames: Int): Moved? {
        require(spacingFrames >= 1) { "Keys are at least one frame apart" }
        val rate = project.composition.frameRate
        val sorted = sel.times.sorted()
        if (sorted.size < 2) return null
        val anchor = frame(layer, rate, sorted[0])
        val targets = sorted.mapIndexed { i, t -> if (i == 0) t else timeOf(layer, rate, anchor + i.toLong() * spacingFrames) }
        val pairs = sorted.zip(targets).filter { (from, to) -> from != to }
        if (pairs.isEmpty()) return null
        val left = pairs.filter { it.second < it.first }.sortedBy { it.first }
        val right = pairs.filter { it.second > it.first }.sortedByDescending { it.first }
        val actions = (left + right).map { (from, to) -> move(layer, sel.target, from, to) }
        val byOld = sorted.zip(targets).toMap()
        return Moved(EditorAction.Batch(actions), sel.times.map { byOld.getValue(it) })
    }

    // ---- Copy / paste -----------------------------------------------------------------------------------------------
    /** The paste-compatibility kind of a target: the same transform / text property, or the same animator property / field. */
    fun kind(target: GraphTarget): String = target.animator?.let { a ->
        "animator:${a.property}:${a.field}:${a.grouping}:${a.seed}" } ?: "property:${target.property}"
    fun copyable(layer: CompositionLayer, target: GraphTarget) = GraphValues.channels(layer, target) > 0

    /** Copy keys: values, curves and timing relative to the first selected key (time order). Null for colour / text source. */
    fun copy(layer: CompositionLayer, sel: KeyframeSelection): KeyClipboard? {
        if (!copyable(layer, sel.target) || sel.times.isEmpty()) return null
        val keys = GraphValues.keys(layer, sel.target).associateBy { it.timeUs }
        val sorted = sel.times.sorted()
        val first = sorted[0]
        return KeyClipboard(kind(sel.target), sorted.mapNotNull { t ->
            val k = keys[t] ?: return@mapNotNull null
            CopiedKey(t - first, k.x, k.y, GraphModel.curveAt(layer, sel.target, t) ?: EasingCurve(k.easing))
        })
    }

    /**
     * TLP.1 the ⋮ Copy keys scope: the selected keys when the key selection is on this layer's [target], otherwise ALL
     * keys of that property. Null when nothing can be copied (colour / text source, or no keys).
     */
    fun copyScope(layer: CompositionLayer, target: GraphTarget, sel: KeyframeSelection?): KeyframeSelection? {
        if (!copyable(layer, target)) return null
        val scoped = sel?.takeIf { it.layerId == layer.id && it.target == target && it.times.isNotEmpty() }
        return scoped ?: selectAll(layer, target).takeIf { it.times.isNotEmpty() }
    }

    fun canPaste(layer: CompositionLayer, target: GraphTarget, clip: KeyClipboard?) =
        clip != null && clip.keys.isNotEmpty() && copyable(layer, target) && clip.kind == kind(target)

    /**
     * Paste keys: the FIRST copied key lands at the playhead in the target layer's own (local) time, the rest keep their
     * exact relative timing; the playhead may be outside the clip. Same property kind only. Existing keys at those times
     * are replaced (the key-setting actions' rule). One Batch: the existing set-key action per key, then its curve.
     * Text and animator keys are frame-snapped by their actions, so their times are snapped here first.
     */
    fun paste(project: ProjectDocument, layer: CompositionLayer, target: GraphTarget, clip: KeyClipboard, playheadUs: Long): Moved? {
        if (!canPaste(layer, target, clip)) return null
        val rate = project.composition.frameRate
        val base = Math.subtractExact(playheadUs, layer.resolvedKeyframeAnchorUs)
        val grid = GraphConvert.frameGrid(target)
        val two = GraphValues.channels(layer, target) == 2
        val sets = ArrayList<EditorAction>(); val curves = ArrayList<EditorAction>(); val times = ArrayList<Long>()
        for (k in clip.keys) {
            var t = Math.addExact(base, k.relativeUs)
            if (grid) t = timeOf(layer, rate, frame(layer, rate, t))
            if (t in times) continue
            times += t
            sets += target.animator?.let { a ->
                EditorAction.EditTextAnimator(layer.id, AnimatorEdit.Key(a, AnimatorKeyOperation.SET, t, value = k.x, y = if (two) k.y else null, easing = k.curve.type))
            } ?: if (two) EditorAction.SetKeyframe2D(layer.id, target.property!!, t, k.x, k.y, k.curve.type)
                else EditorAction.SetKeyframe(layer.id, target.property!!, t, k.x, k.curve.type)
            if (!GraphModel.discrete(target)) curves += GraphModel.action(layer.id, target, listOf(t), k.curve)
        }
        return Moved(EditorAction.Batch(sets + curves), times)
    }
}

/** TLP.1 what a tap on an editable timeline key does. */
enum class KeyTapEffect { NONE, SELECT, TOGGLE }

/**
 * TLP.1 key tap rules (timeline). Outside key selection mode a single tap does NOTHING (no seek, no selection; the tap is
 * still consumed so it never reaches the clip) and a double tap selects the key and enters key selection mode, without a
 * seek. In key selection mode a tap or a double tap toggles the key (one toggle per gesture). No rule ever seeks.
 */
object KeyTapRules {
    fun tap(keyMode: Boolean) = if (keyMode) KeyTapEffect.TOGGLE else KeyTapEffect.NONE
    fun doubleTap(keyMode: Boolean) = if (keyMode) KeyTapEffect.TOGGLE else KeyTapEffect.SELECT
}
