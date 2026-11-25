package com.fluxx.android.editor

import com.fluxx.android.model.*

/**
 * GRAPH G1: the reducer of [EditorAction.SetKeyEasing] and the pure model behind the Graph page (Ease view).
 *
 * A property is addressed exactly as the existing keyframe actions and focus system address it: an [AnimPropertyType]
 * (transform Position/Scale/Rotation/Opacity and the text-layer tracks) or an [AnimatorTrackAddress] (animator
 * properties and selector fields). [GraphTarget] only carries one of those two existing addresses.
 */
data class GraphTarget(val property: AnimPropertyType? = null, val animator: AnimatorTrackAddress? = null) {
    init { require((property == null) != (animator == null)) { "A graph target is one property or one animator field" } }
}

internal object KeyEasingActions {
    /** Writes the curve on the selected departing keys; keys outside the clip range stay stored (and hidden) as before. */
    private fun <K> keys(list: List<K>, time: (K) -> Long, times: List<Long>?, set: (K) -> K): FrozenList<K> {
        val last = list.lastIndex
        return FrozenList.of(list.mapIndexed { i, k -> if ((times == null && i < last) || (times != null && time(k) in times)) set(k) else k })
    }
    private fun scalar(p: AnimatableProperty1D, times: List<Long>?, c: EasingCurve) =
        p.copy(keyframes = keys(p.keyframes, { it.timeUs }, times) { it.withCurve(c) })
    private fun vector(p: AnimatableProperty2D, times: List<Long>?, c: EasingCurve) =
        p.copy(keyframes = keys(p.keyframes, { it.timeUs }, times) { it.withCurve(c) })
    private fun colour(p: AnimatableColour, times: List<Long>?, c: EasingCurve) =
        p.copy(keyframes = keys(p.keyframes, { it.timeUs }, times) { it.withCurve(c) })

    fun apply(layer: CompositionLayer, action: EditorAction.SetKeyEasing): CompositionLayer {
        val times = action.keyTimes; val c = action.curve
        action.animator?.let { address ->
            require(layer.type == LayerType.TEXT)
            return layer.copy(text = layer.text.copy(animators = FrozenList.of(layer.text.animators.map { a ->
                if (a.id == address.animatorId) animator(a, address, times, c) else a })))
        }
        val at = layer.animTransform
        return when (val p = requireNotNull(action.property)) {
            AnimPropertyType.POSITION -> layer.copy(animTransform = at.copy(position = vector(at.position, times, c)))
            AnimPropertyType.SCALE -> layer.copy(animTransform = at.copy(scale = vector(at.scale, times, c)))
            AnimPropertyType.ROTATION -> layer.copy(animTransform = at.copy(rotation = scalar(at.rotation, times, c)))
            AnimPropertyType.OPACITY -> layer.copy(animTransform = at.copy(opacity = scalar(at.opacity, times, c)))
            AnimPropertyType.FILL_COLOUR, AnimPropertyType.STROKE_COLOUR -> {
                require(layer.type == LayerType.TEXT)
                layer.copy(text = layer.text.withColour(p, colour(layer.text.colour(p), times, c)))
            }
            AnimPropertyType.SOURCE_TEXT -> throw IllegalArgumentException("Source Text holds between keys; it has no easing")
            else -> {
                require(layer.type == LayerType.TEXT)
                layer.copy(text = layer.text.withScalar(p, scalar(layer.text.scalar(p), times, c)))
            }
        }
    }

    private fun animator(a: TextAnimator, address: AnimatorTrackAddress, times: List<Long>?, c: EasingCurve): TextAnimator {
        require(!address.seed) { "The random seed has no easing" }
        if (address.selectorId != null) {
            val field = requireNotNull(address.field)
            return a.copy(selectors = FrozenList.of(a.selectors.map { s ->
                if (s.id == address.selectorId) TextAnimatorEdits.withField(s, field, scalar(s.tracks[field.ordinal], times, c)) else s }))
        }
        val property = address.property
        if (property == null) return if (address.grouping) a.copy(anchorGrouping = scalar(a.anchorGrouping, times, c))
            else a.copy(enabled = scalar(a.enabled, times, c))
        return a.copy(properties = FrozenList.of(a.properties.map { o ->
            if (o.property != property) o else when {
                o.scalar != null -> o.copy(scalar = scalar(o.scalar, times, c))
                o.vector != null -> o.copy(vector = vector(o.vector, times, c))
                else -> o.copy(colour = colour(requireNotNull(o.colour), times, c))
            }
        }))
    }
}

/** One segment of the edited property: keys [index] → [index]+1 (0-based), with its local times and curve. */
data class GraphSegment(val index: Int, val count: Int, val startUs: Long, val endUs: Long, val curve: EasingCurve) {
    val label get() = "Key ${index + 1} to ${index + 2}"
    val durationUs get() = endUs - startUs
}

/** Pure reads behind the Graph page (no Compose). */
object GraphModel {
    /** Discrete tracks hold between keys: the Graph icon stays disabled for them. */
    fun discrete(target: GraphTarget): Boolean = target.animator?.let { TextAnimatorEdits.isHold(it) }
        ?: (target.property == AnimPropertyType.SOURCE_TEXT || target.property == AnimPropertyType.STROKE_OVER_FILL ||
            target.property == AnimPropertyType.LIGATURES)

    /** Local (anchor-relative) key times of an animated target, ascending; empty when it is static or gone. */
    fun keyTimes(layer: CompositionLayer, target: GraphTarget): List<Long> {
        target.animator?.let { a ->
            if (layer.type != LayerType.TEXT || !TextAnimatorEdits.exists(layer.text, a) || a.seed) return emptyList()
            return TextAnimatorEdits.keyTimes(layer, a)
        }
        val p = target.property!!
        if (layer.type != LayerType.TEXT && p.ordinal > AnimPropertyType.OPACITY.ordinal) return emptyList()
        return layer.propertyKeyTimes(p)
    }

    /** The rail Graph icon is enabled for a continuous property animated with at least two keys. */
    fun enabled(layer: CompositionLayer, target: GraphTarget?): Boolean =
        target != null && !discrete(target) && keyTimes(layer, target).size >= 2

    /**
     * G1 segment rule (no key selection yet): the segment containing the local playhead. Strictly between two keys:
     * that segment. Exactly on key k: k → k+1 (outgoing); on the last key: the previous segment. Before the first key:
     * the first segment; after the last key: the last segment. Returns the 0-based index of the departing key, or null
     * with fewer than two keys.
     */
    fun segmentIndex(keys: List<Long>, localUs: Long): Int? {
        val n = keys.size
        if (n < 2) return null
        if (localUs < keys[0]) return 0
        if (localUs >= keys[n - 1]) return n - 2
        var i = 0
        while (i + 1 < n && keys[i + 1] <= localUs) i++
        return i.coerceAtMost(n - 2)
    }

    /** The curve stored on the key at [time] (the departing key of a segment). */
    fun curveAt(layer: CompositionLayer, target: GraphTarget, time: Long): EasingCurve? {
        target.animator?.let { a ->
            val o = TextAnimatorEdits.animator(layer.text, a.animatorId)?.properties?.firstOrNull { it.property == a.property }
            if (a.property != null) return o?.scalar?.keyframes?.firstOrNull { it.timeUs == time }?.curve
                ?: o?.vector?.keyframes?.firstOrNull { it.timeUs == time }?.curve ?: o?.colour?.keyframes?.firstOrNull { it.timeUs == time }?.curve
            return TextAnimatorEdits.track(layer.text, a)?.keyframes?.firstOrNull { it.timeUs == time }?.curve
        }
        val at = layer.animTransform
        return when (val p = target.property!!) {
            AnimPropertyType.POSITION -> at.position.keyframes.firstOrNull { it.timeUs == time }?.curve
            AnimPropertyType.SCALE -> at.scale.keyframes.firstOrNull { it.timeUs == time }?.curve
            AnimPropertyType.ROTATION -> at.rotation.keyframes.firstOrNull { it.timeUs == time }?.curve
            AnimPropertyType.OPACITY -> at.opacity.keyframes.firstOrNull { it.timeUs == time }?.curve
            AnimPropertyType.FILL_COLOUR, AnimPropertyType.STROKE_COLOUR -> layer.text.colour(p).keyframes.firstOrNull { it.timeUs == time }?.curve
            AnimPropertyType.SOURCE_TEXT -> null
            else -> layer.text.scalar(p).keyframes.firstOrNull { it.timeUs == time }?.curve
        }
    }

    /** The edited segment at the playhead (G1 rule), or null when the target has fewer than two keys. */
    fun segment(layer: CompositionLayer, target: GraphTarget, localUs: Long): GraphSegment? {
        val keys = keyTimes(layer, target)
        val i = segmentIndex(keys, localUs) ?: return null
        val curve = curveAt(layer, target, keys[i]) ?: return null
        return GraphSegment(i, keys.size, keys[i], keys[i + 1], curve)
    }

    /** Short start/end value labels of a segment for the page header (numbers rounded; colours as hex). */
    fun values(layer: CompositionLayer, target: GraphTarget, s: GraphSegment): Pair<String, String> {
        fun n(v: Float) = if (kotlin.math.abs(v) >= 100f) "%.0f".format(java.util.Locale.ROOT, v) else "%.2f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')
        fun hex(argb: Int) = "#%06X".format(argb and 0xffffff)
        fun at(time: Long): String {
            target.animator?.let { a ->
                val o = TextAnimatorEdits.animator(layer.text, a.animatorId)?.properties?.firstOrNull { it.property == a.property }
                return when {
                    a.property != null && o?.vector != null -> TextAnimatorEdits.vector(layer.text, a, time).let { "${n(it[0])}, ${n(it[1])}" }
                    a.property != null && o?.colour != null -> hex(TextAnimatorEdits.colour(layer.text, a, time))
                    else -> n(TextAnimatorEdits.track(layer.text, a)?.keyframes?.firstOrNull { it.timeUs == time }?.value ?: 0f)
                }
            }
            val at = layer.animTransform
            return when (val p = target.property!!) {
                AnimPropertyType.POSITION -> at.position.keyframes.first { it.timeUs == time }.let { "${n(it.x)}, ${n(it.y)}" }
                AnimPropertyType.SCALE -> at.scale.keyframes.first { it.timeUs == time }.let { "${n(it.x * 100f)}, ${n(it.y * 100f)}%" }
                AnimPropertyType.ROTATION -> n(at.rotation.keyframes.first { it.timeUs == time }.value) + "°"
                AnimPropertyType.OPACITY -> n(at.opacity.keyframes.first { it.timeUs == time }.value * 100f) + "%"
                AnimPropertyType.FILL_COLOUR, AnimPropertyType.STROKE_COLOUR -> hex(layer.text.colour(p).keyframes.first { it.timeUs == time }.argb)
                else -> n(layer.text.scalar(p).keyframes.first { it.timeUs == time }.value)
            }
        }
        return runCatching { at(s.startUs) to at(s.endUs) }.getOrDefault("" to "")
    }

    /**
     * The local time the page's own controls use: transform pages use the raw playhead (as their diamond does), text
     * and animator pages the frame-snapped playhead (as theirs does).
     */
    fun local(layer: CompositionLayer, target: GraphTarget, playheadUs: Long, rate: FrameRate): Long =
        if (target.property != null && target.property.ordinal <= AnimPropertyType.OPACITY.ordinal)
            Math.subtractExact(playheadUs, layer.resolvedKeyframeAnchorUs)
        else Math.subtractExact(rate.snap(playheadUs), layer.resolvedKeyframeAnchorUs)

    /** Segment progress (0..1) of the local playhead when it lies inside the segment, else null. */
    fun progress(s: GraphSegment, localUs: Long): Float? =
        if (localUs < s.startUs || localUs > s.endUs || s.durationUs <= 0) null else ((localUs - s.startUs).toDouble() / s.durationUs).toFloat()

    /** One-undo edits: the edited segment, or every segment ("Apply to all keys"). */
    fun action(layerId: Long, target: GraphTarget, keyTimes: List<Long>?, curve: EasingCurve) =
        EditorAction.SetKeyEasing(layerId, target.property, target.animator, keyTimes, curve)

    /**
     * The rail diamond on the Graph page: the same key model as the page it was opened from (enable at the playhead
     * when static, remove the key on it, otherwise add a key with the current value). Null when nothing applies.
     */
    fun toggleKey(layer: CompositionLayer, target: GraphTarget, playheadUs: Long, rate: FrameRate): EditorAction? {
        val local = local(layer, target, playheadUs, rate)
        target.animator?.let { a ->
            if (layer.type != LayerType.TEXT || !TextAnimatorEdits.exists(layer.text, a)) return null
            val onKey = local in TextAnimatorEdits.keyTimes(layer, a)
            return TextAnimatorEdits.action(layer.id, listOf(TextAnimatorEdits.toggleKey(layer.text, a, local, onKey)))
        }
        val p = target.property!!
        val keys = layer.propertyKeyTimes(p)
        val animated = keys.isNotEmpty()
        if (!animated) return EditorAction.ToggleAnimated(layer.id, p, true, playheadUs)
        if (local in keys) return EditorAction.RemoveKeyframe(layer.id, p, local)
        val t = layer.evaluatedTransform(playheadUs)
        return when (p) {
            AnimPropertyType.POSITION -> EditorAction.SetKeyframe2D(layer.id, p, local, t.positionX, t.positionY)
            AnimPropertyType.SCALE -> EditorAction.SetKeyframe2D(layer.id, p, local, t.scaleX, t.scaleY)
            AnimPropertyType.ROTATION -> EditorAction.SetKeyframe(layer.id, p, local, t.rotationDegrees)
            AnimPropertyType.OPACITY -> EditorAction.SetKeyframe(layer.id, p, local, t.opacity)
            AnimPropertyType.FILL_COLOUR, AnimPropertyType.STROKE_COLOUR ->
                EditorAction.SetColourKeyframe(layer.id, local, layer.text.colour(p).evaluate(local), property = p)
            AnimPropertyType.SOURCE_TEXT -> null
            else -> EditorAction.SetKeyframe(layer.id, p, local, layer.text.scalarValue(p, local))
        }
    }
}
