package com.fluxx.android.editor

import com.fluxx.android.model.*
import com.fluxx.android.ui.inspector.GraphValues

/**
 * TL3 B3: a key selection that can span tracks (layer + property) and layers. Each track is a T5 [KeyframeSelection]
 * (one layer's one property, local times). UI state only: never in undo history, never saved. Never empty.
 */
data class KeySelectionSet(val tracks: List<KeyframeSelection>) {
    init {
        require(tracks.isNotEmpty() && tracks.all { it.times.isNotEmpty() })
        require(tracks.map { it.layerId to it.target }.distinct().size == tracks.size)
    }
    val size get() = tracks.sumOf { it.size }
    val layerIds get() = tracks.map { it.layerId }.distinct()
    val layerCount get() = layerIds.size
    /** Every selected local time, track by track (T5 compatibility: one track's times). */
    val times get() = tracks.flatMap { it.times }
    fun track(layerId: Long, target: GraphTarget) = tracks.firstOrNull { it.layerId == layerId && it.target == target }
    fun contains(layerId: Long, target: GraphTarget, t: Long) = track(layerId, target)?.contains(t) == true
    fun timesOf(layerId: Long, target: GraphTarget): Set<Long> = track(layerId, target)?.times?.toSet().orEmpty()

    /** Adds or removes one key; null when nothing remains (key mode exits). */
    fun toggle(layerId: Long, target: GraphTarget, t: Long): KeySelectionSet? {
        val existing = track(layerId, target)
        val next = existing?.toggle(t) ?: KeyframeSelection(layerId, target, listOf(t))
        return of(tracks.filterNot { it === existing } + next)
    }
    /** Replaces one track's times (empty removes the track). */
    fun withTrack(sel: KeyframeSelection): KeySelectionSet? = of(tracks.filterNot { it.layerId == sel.layerId && it.target == sel.target } + sel)
    fun onlyLayer(layerId: Long) = of(tracks.filter { it.layerId == layerId })
    fun withoutLayer(layerId: Long) = of(tracks.filterNot { it.layerId == layerId })
    fun onlyTrack(layerId: Long, target: GraphTarget) = of(tracks.filter { it.layerId == layerId && it.target == target })
    fun onlyLayers(ids: Set<Long>) = of(tracks.filter { it.layerId in ids })

    /** "N keyframes selected" (" · M layers" when the keys span more than one layer). */
    fun title(): String = (if (size == 1) "1 keyframe selected" else "$size keyframes selected") + if (layerCount > 1) " · $layerCount layers" else ""

    companion object {
        fun of(tracks: List<KeyframeSelection>): KeySelectionSet? = tracks.filter { it.times.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { KeySelectionSet(it) }
        fun of(sel: KeyframeSelection?): KeySelectionSet? = sel?.let { of(listOf(it)) }
    }
}

/** TL3 B3: where a key sits: a twirl-down row (no sheet), or a layer bar with the inspector sheet open on its property. */
enum class KeyPlace { TWIRL, SHEET }

/** TL3 B3: what a key gesture does. */
enum class KeyGestureEffect { NONE, OPEN_POPOVER, OPEN_POPOVER_SINGLE, SELECT, TOGGLE, ADD }

/**
 * TL3 B3 key gestures (REPLACE the TLP.1 / T5 double-tap rules everywhere; there is no double tap and no timeout wait).
 * A hold released without moving (zero frames) is a "hold release"; a hold that moves is a drag (handled by the drag).
 */
object KeyGestureRules {
    fun tap(place: KeyPlace, keyMode: Boolean): KeyGestureEffect = when {
        keyMode -> KeyGestureEffect.TOGGLE
        place == KeyPlace.TWIRL -> KeyGestureEffect.OPEN_POPOVER
        else -> KeyGestureEffect.NONE
    }
    fun holdRelease(place: KeyPlace, keyMode: Boolean, selected: Boolean): KeyGestureEffect = when {
        !keyMode -> KeyGestureEffect.SELECT
        place == KeyPlace.TWIRL -> if (selected) KeyGestureEffect.OPEN_POPOVER else KeyGestureEffect.OPEN_POPOVER_SINGLE
        // Sheet open: the value is edited in the inspector; a hold release on an unselected key adds it.
        else -> if (selected) KeyGestureEffect.NONE else KeyGestureEffect.ADD
    }
}

/**
 * TL3 B3 key selection invariants: a selected key belongs to an EXPANDED layer or to the layer the inspector is open on.
 * Pure (JVM tested); every transition passes through [constrain].
 */
object KeySelectionRules {
    /**
     * [sel] after a transition. [inspected]: the layer the sheet is open on (null when closed); [pageTarget]: the open
     * property page's target (narrows to that track); [expanded]: the expanded layers. Null = key mode ends.
     */
    fun constrain(sel: KeySelectionSet?, inspected: Long?, pageTarget: GraphTarget?, expanded: Set<Long>): KeySelectionSet? {
        sel ?: return null
        if (inspected != null) {
            // 1 / 2: opening the inspector on X keeps X's keys (and drops the others); a property page narrows to its track.
            return if (pageTarget != null) sel.onlyTrack(inspected, pageTarget) else sel.onlyLayer(inspected)
        }
        // 3 / 4: no sheet: only expanded layers keep keys; no twirl-down open ends key mode.
        if (expanded.isEmpty()) return null
        return sel.onlyLayers(expanded)
    }
}

/** TL3 B3 multi-track key edits (pure, JVM tested). Every edit is ONE [EditorAction.Batch] of existing actions. */
object MultiKeyEdits {
    data class Moved(val action: EditorAction, val selection: KeySelectionSet?)

    private fun layer(project: ProjectDocument, id: Long) = project.composition.layers.firstOrNull { it.id == id }

    fun easable(sel: KeySelectionSet) = sel.tracks.any { !GraphModel.discrete(it.target) }

    /** Easing buttons / the More popover: every selected key's departing segment, one SetKeyEasing per continuous track. */
    fun ease(project: ProjectDocument, sel: KeySelectionSet, curve: EasingCurve): EditorAction? =
        sel.tracks.mapNotNull { t -> layer(project, t.layerId)?.let { KeyEdits.ease(it, t, curve) } }
            .flatMap { (it as EditorAction.Batch).actions }.takeIf { it.isNotEmpty() }?.let { EditorAction.Batch(it) }

    /** Trash: every selected key of every track. */
    fun delete(project: ProjectDocument, sel: KeySelectionSet): EditorAction? =
        sel.tracks.mapNotNull { t -> layer(project, t.layerId)?.let { KeyEdits.delete(it, t) } }
            .flatMap { (it as EditorAction.Batch).actions }.takeIf { it.isNotEmpty() }?.let { EditorAction.Batch(it) }

    /** Stagger / Sequence PER TRACK: each track anchored on its own first selected key; one Batch. */
    fun pack(project: ProjectDocument, sel: KeySelectionSet, spacingFrames: Int): Moved? {
        val actions = ArrayList<EditorAction>(); val tracks = ArrayList<KeyframeSelection>()
        for (t in sel.tracks) {
            val l = layer(project, t.layerId)
            val moved = l?.let { KeyEdits.pack(project, it, t, spacingFrames) }
            if (moved != null) { actions += (moved.action as EditorAction.Batch).actions; tracks += t.copy(times = moved.times) } else tracks += t
        }
        if (actions.isEmpty()) return null
        return Moved(EditorAction.Batch(actions), KeySelectionSet.of(tracks))
    }

    /** Select all: every key of every [scope] track (the displayed rows). */
    fun selectAll(project: ProjectDocument, scope: List<Pair<Long, GraphTarget>>): KeySelectionSet? =
        KeySelectionSet.of(scope.mapNotNull { (id, target) -> layer(project, id)?.let { KeyEdits.selectAll(it, target) } })
    /** Odd / Even, counted within each track in time order. */
    fun odd(project: ProjectDocument, scope: List<Pair<Long, GraphTarget>>) =
        KeySelectionSet.of(scope.mapNotNull { (id, target) -> layer(project, id)?.let { KeyEdits.odd(it, target) } })
    fun even(project: ProjectDocument, scope: List<Pair<Long, GraphTarget>>) =
        KeySelectionSet.of(scope.mapNotNull { (id, target) -> layer(project, id)?.let { KeyEdits.even(it, target) } })

    /**
     * Hold-drag of a selected key: EVERY selected key of every track moves by the same whole number of frames on the
     * composition grid ([dragDeltaUs] measured at the earliest selected key, in composition time). Clamped so no
     * track's earliest selected key goes below local 0. [clip] optionally keeps every key inside [first, last]
     * composition time (the single-key drag stays inside its clip). Null when zero frames.
     */
    fun moveTogether(project: ProjectDocument, sel: KeySelectionSet, dragDeltaUs: Long, clip: LongRange? = null): Moved? {
        val rate = project.composition.frameRate
        val layers = sel.tracks.associate { it.layerId to (layer(project, it.layerId) ?: return null) }
        val firsts = sel.tracks.map { t -> val l = layers.getValue(t.layerId); t to t.times.min() }
        val (t0, e0) = firsts.minBy { (t, e) -> Math.addExact(layers.getValue(t.layerId).resolvedKeyframeAnchorUs, e) }
        val l0 = layers.getValue(t0.layerId)
        val f0 = KeyEdits.frame(l0, rate, e0)
        var frames = KeyEdits.frame(l0, rate, Math.addExact(e0, dragDeltaUs)) - f0
        fun fits(n: Long) = sel.tracks.all { t -> val l = layers.getValue(t.layerId)
            t.times.all { k -> val to = KeyEdits.timeOf(l, rate, KeyEdits.frame(l, rate, k) + n)
                to >= 0 && (clip == null || Math.addExact(l.resolvedKeyframeAnchorUs, to) in clip) } }
        // Toward zero until every key fits (the clamp always wins).
        while (frames != 0L && !fits(frames)) frames += if (frames > 0) -1 else 1
        if (frames == 0L) return null
        val actions = ArrayList<EditorAction>(); val tracks = ArrayList<KeyframeSelection>()
        for (t in sel.tracks) {
            val l = layers.getValue(t.layerId)
            val one = KeyEdits.moveTogether(project, l, t, KeyEdits.timeOf(l, rate, KeyEdits.frame(l, rate, t.times.min()) + frames) - t.times.min())
            if (one != null) { actions += (one.action as EditorAction.Batch).actions; tracks += t.copy(times = one.times) } else tracks += t
        }
        if (actions.isEmpty()) return null
        return Moved(EditorAction.Batch(actions), KeySelectionSet.of(tracks))
    }

    // ---- B3 value popover: bulk value edits ---------------------------------------------------------------------------
    /** The keys a value popover on ([layerId], [target], [time]) edits: the same-property keys of the selection, or just it. */
    fun valueScope(sel: KeySelectionSet?, layerId: Long, target: GraphTarget, time: Long): List<KeyframeSelection> {
        if (sel == null || !sel.contains(layerId, target, time)) return listOf(KeyframeSelection(layerId, target, listOf(time)))
        val kind = KeyEdits.kind(target)
        // Same property id (Position never changes Scale): the T5 paste kind; on any layer in the selection.
        return sel.tracks.filter { KeyEdits.kind(it.target) == kind && (it.target.property != null) == (target.property != null) }
    }

    /**
     * Typing a value on ONE axis ([axis] 0 = X / the value, 1 = Y) sets that SAME stored [value] on every key of [scope]
     * (other axis untouched), through the existing key-set actions at each key's own time with its own easing (so each
     * key keeps its curve). No seek. One Batch; null when nothing changes.
     */
    fun setValue(project: ProjectDocument, scope: List<KeyframeSelection>, axis: Int, value: Float): EditorAction? {
        val actions = ArrayList<EditorAction>()
        for (t in scope) {
            val l = layer(project, t.layerId) ?: continue
            val two = GraphValues.channels(l, t.target) == 2
            val keys = GraphValues.keys(l, t.target).associateBy { it.timeUs }
            for (time in t.times.sorted()) {
                val k = keys[time] ?: continue
                val x = if (axis == 0) value else k.x
                val y = if (axis == 1) value else k.y
                if (x == k.x && (!two || y == k.y)) continue
                actions += t.target.animator?.let { a ->
                    EditorAction.EditTextAnimator(l.id, AnimatorEdit.Key(a, AnimatorKeyOperation.SET, time, value = x, y = if (two) y else null, easing = k.easing))
                } ?: if (two) EditorAction.SetKeyframe2D(l.id, t.target.property!!, time, x, y, k.easing)
                    else EditorAction.SetKeyframe(l.id, t.target.property!!, time, x, k.easing)
            }
        }
        return actions.takeIf { it.isNotEmpty() }?.let { EditorAction.Batch(it) }
    }

    /** A colour key value ([argb]) on every key of [scope], each with its own easing. */
    fun setColour(project: ProjectDocument, scope: List<KeyframeSelection>, argb: Int): EditorAction? {
        val actions = ArrayList<EditorAction>()
        for (t in scope) {
            val l = layer(project, t.layerId) ?: continue
            val track = TwirlColour.track(l, t.target) ?: continue
            for (time in t.times.sorted()) {
                val k = track.keyframes.firstOrNull { it.timeUs == time } ?: continue
                if (k.argb == argb) continue
                actions += t.target.animator?.let { a ->
                    EditorAction.EditTextAnimator(l.id, AnimatorEdit.Key(a, AnimatorKeyOperation.SET, time, argb = argb, easing = k.easing))
                } ?: EditorAction.SetColourKeyframe(l.id, time, argb, k.easing, t.target.property!!)
            }
        }
        return actions.takeIf { it.isNotEmpty() }?.let { EditorAction.Batch(it) }
    }
}

/** TL3: the colour track behind a colour target (text Fill / Stroke colour, animator Fill / Stroke colour). */
object TwirlColour {
    fun track(layer: CompositionLayer, target: GraphTarget): AnimatableColour? {
        target.animator?.let { a ->
            if (a.property == null || a.property.kind != 3) return null
            return TextAnimatorEdits.animator(layer.text, a.animatorId)?.properties?.firstOrNull { it.property == a.property }?.colour
        }
        return when (target.property) {
            AnimPropertyType.FILL_COLOUR -> layer.text.fill
            AnimPropertyType.STROKE_COLOUR -> layer.text.strokeColour
            else -> null
        }
    }
}
