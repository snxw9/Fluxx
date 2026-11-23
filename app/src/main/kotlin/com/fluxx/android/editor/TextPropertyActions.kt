package com.fluxx.android.editor

import com.fluxx.android.model.*

/** Typed tracks share the existing action/gesture transaction boundary. */
internal object TextPropertyActions {
    private val properties = AnimPropertyType.entries.filter { it.ordinal >= AnimPropertyType.FONT_SIZE.ordinal }.toSet()
    fun apply(state: EditorState, action: EditorAction): EditorAction? {
        if (action is EditorAction.MoveKeyframe && action.property == null) {
            val layer=requireNotNull(state.project.composition.layers.firstOrNull { it.id==action.id })
            return EditorAction.Batch(AnimPropertyType.entries.map { action.copy(property = it) } +
                TextAnimatorActions.moveAll(layer,action.oldTimeUs,action.newTimeUs))
        }
        val id: Long; val property: AnimPropertyType
        when (action) {
            is EditorAction.ToggleAnimated -> { id = action.id; property = action.property }
            is EditorAction.SetKeyframe -> { id = action.id; property = action.property }
            is EditorAction.SetStringKeyframe -> { id = action.id; property = AnimPropertyType.SOURCE_TEXT }
            is EditorAction.SetColourKeyframe -> { id = action.id; property = action.property }
            is EditorAction.RemoveKeyframe -> { id = action.id; property = action.property }
            is EditorAction.MoveKeyframe -> { id = action.id; property = requireNotNull(action.property) }
            is EditorAction.ResetPropertyAnimation -> { id = action.id; property = action.property }
            else -> return null
        }
        if (property !in properties) return null
        val layer = requireNotNull(state.project.composition.layers.firstOrNull { it.id == id })
        if (layer.type != LayerType.TEXT) return EditorAction.Batch(emptyList())
        val text = layer.text
        if (action is EditorAction.ToggleAnimated) {
            val enabled = text.animated(property)
            if (enabled == action.enable) return EditorAction.Batch(emptyList())
        }
        val local = when (action) {
            is EditorAction.ToggleAnimated -> Math.subtractExact(state.project.composition.frameRate.snap(action.playheadUs), layer.resolvedKeyframeAnchorUs)
            is EditorAction.SetKeyframe -> action.localTimeUs
            is EditorAction.SetStringKeyframe -> action.localTimeUs
            is EditorAction.SetColourKeyframe -> action.localTimeUs
            is EditorAction.RemoveKeyframe -> action.localTimeUs
            is EditorAction.MoveKeyframe -> action.oldTimeUs
            else -> Math.subtractExact(state.playheadUs, layer.resolvedKeyframeAnchorUs)
        }
        fun snap(time: Long): Long {
            val absolute = Math.addExact(layer.resolvedKeyframeAnchorUs, time)
            val rate = state.project.composition.frameRate
            val frame = Markers.signedFrame(rate, absolute)
            val snapped = if (frame >= 0) rate.frameTimeUs(frame) else Math.negateExact(rate.frameTimeUs(Math.negateExact(frame)))
            return Math.subtractExact(snapped, layer.resolvedKeyframeAnchorUs)
        }
        val time = snap(local)
        fun <T> edit(keys: List<T>, keyTime: (T) -> Long, withTime: (T, Long) -> T, value: () -> T): FrozenList<T> {
            val next = when (action) {
                is EditorAction.ToggleAnimated -> if (action.enable) listOf(withTime(value(), time)) else emptyList()
                is EditorAction.ResetPropertyAnimation -> emptyList()
                is EditorAction.RemoveKeyframe -> keys.filterNot { keyTime(it) == local }
                is EditorAction.MoveKeyframe -> {
                    val target = snap(action.newTimeUs)
                    val existing = keys.firstOrNull { keyTime(it) == local } ?: return FrozenList.of(keys)
                    keys.filterNot { keyTime(it) == local || keyTime(it) == target } + withTime(existing, target)
                }
                else -> keys.filterNot { keyTime(it) == time } + withTime(value(), time)
            }
            return FrozenList.of(next.sortedBy(keyTime))
        }
        val next = when (property) {
            AnimPropertyType.SOURCE_TEXT -> {
                val sampled = text.source.evaluateDocument(local)
                val value = (action as? EditorAction.SetStringKeyframe)?.value?.let(sampled::replace) ?: sampled
                val keys = edit(text.source.keyframes, { it.timeUs }, { k, t -> k.copy(timeUs = t) }) { StringKeyframe(time, value.text,value) }
                val held=if(action is EditorAction.ResetPropertyAnimation) TextSourceDocument("") else sampled
                text.copy(source = AnimatableString(keys.isNotEmpty(),held.text,keys,held))
            }
            AnimPropertyType.FONT_SIZE, AnimPropertyType.TRACKING, AnimPropertyType.LEADING, AnimPropertyType.BASELINE_SHIFT,
            AnimPropertyType.STROKE_WIDTH, AnimPropertyType.STROKE_OVER_FILL, AnimPropertyType.LIGATURES -> {
                val track = text.scalar(property)
                val sampled = text.scalarValue(property, local)
                val value = (action as? EditorAction.SetKeyframe)?.value ?: sampled
                val keys = edit(track.keyframes, { it.timeUs }, { k, t -> k.copy(timeUs = t) }) {
                    rekey1D(track.keyframes.firstOrNull { it.timeUs == time }, time, value, (action as? EditorAction.SetKeyframe)?.easing ?: EasingPreset.EASY_EASE) }
                text.withScalar(property, AnimatableProperty1D(keys.isNotEmpty(), if (action is EditorAction.ResetPropertyAnimation) (when(property) { AnimPropertyType.FONT_SIZE -> 72f; AnimPropertyType.LIGATURES -> 1f; else -> 0f }) else sampled, keys))
            }
            AnimPropertyType.FILL_COLOUR, AnimPropertyType.STROKE_COLOUR -> {
                val track = text.colour(property)
                val sampled = track.evaluate(local)
                val value = (action as? EditorAction.SetColourKeyframe)?.argb ?: sampled
                val keys = edit(track.keyframes, { it.timeUs }, { k, t -> k.copy(timeUs = t) }) {
                    rekeyColour(track.keyframes.firstOrNull { it.timeUs == time }, time, value, (action as? EditorAction.SetColourKeyframe)?.easing ?: EasingPreset.EASY_EASE) }
                text.withColour(property, AnimatableColour(keys.isNotEmpty(), if (action is EditorAction.ResetPropertyAnimation) (if (property == AnimPropertyType.FILL_COLOUR) -1 else 0xff000000.toInt()) else sampled, keys))
            }
            else -> error("Not a text property")
        }
        return EditorAction.SetTextPayload(id, next)
    }
}

fun CompositionLayer.propertyKeyTimes(property: AnimPropertyType): List<Long> = when (property) {
    AnimPropertyType.POSITION -> animTransform.position.takeIf { it.isAnimated }?.keyframes?.map { it.timeUs }
    AnimPropertyType.SCALE -> animTransform.scale.takeIf { it.isAnimated }?.keyframes?.map { it.timeUs }
    AnimPropertyType.ROTATION -> animTransform.rotation.takeIf { it.isAnimated }?.keyframes?.map { it.timeUs }
    AnimPropertyType.OPACITY -> animTransform.opacity.takeIf { it.isAnimated }?.keyframes?.map { it.timeUs }
    AnimPropertyType.FONT_SIZE -> text.size.takeIf { it.isAnimated }?.keyframes?.map { it.timeUs }
    AnimPropertyType.FILL_COLOUR -> text.fill.takeIf { it.isAnimated }?.keyframes?.map { it.timeUs }
    AnimPropertyType.SOURCE_TEXT -> text.source.takeIf { it.isAnimated }?.keyframes?.map { it.timeUs }
    AnimPropertyType.STROKE_COLOUR -> text.strokeColour.takeIf { it.isAnimated }?.keyframes?.map { it.timeUs }
    else -> text.scalar(property).takeIf { it.isAnimated }?.keyframes?.map { it.timeUs }
}.orEmpty()

/** Anchor-relative aggregation for the existing timeline model; no new UI controls. */
fun CompositionLayer.aggregateKeyTimes(): List<Long> {
    val properties=if(type==LayerType.TEXT) AnimPropertyType.entries else AnimPropertyType.entries.take(4)
    return (properties.flatMap { propertyKeyTimes(it) } +
        if(type==LayerType.TEXT) text.animators.flatMap { it.activeKeyTimes() } else emptyList()).distinct().sorted()
}

fun TextProperties.scalar(property: AnimPropertyType): AnimatableProperty1D = when (property) {
    AnimPropertyType.FONT_SIZE -> size
    AnimPropertyType.TRACKING -> tracking
    AnimPropertyType.LEADING -> leading
    AnimPropertyType.BASELINE_SHIFT -> baselineShift
    AnimPropertyType.STROKE_WIDTH -> strokeWidth
    AnimPropertyType.STROKE_OVER_FILL -> strokeOverFill
    AnimPropertyType.LIGATURES -> ligatures
    else -> error("Not a scalar text property")
}
/** The value at [time] as edits sample it: hold for the discrete tracks, and (GRAPH G1) clamped to the property's legal
 * range, so an overshooting curve can never be written back into a validated key or static value. */
fun TextProperties.scalarValue(property: AnimPropertyType, time: Long): Float = when (property) {
    AnimPropertyType.STROKE_OVER_FILL, AnimPropertyType.LIGATURES -> scalar(property).hold(time)
    AnimPropertyType.FONT_SIZE -> sizeAt(time)
    AnimPropertyType.STROKE_WIDTH -> strokeWidthAt(time)
    else -> scalar(property).evaluate(time)
}
fun TextProperties.colour(property: AnimPropertyType): AnimatableColour = when (property) {
    AnimPropertyType.FILL_COLOUR -> fill
    AnimPropertyType.STROKE_COLOUR -> strokeColour
    else -> error("Not a colour text property")
}
fun TextProperties.animated(property: AnimPropertyType): Boolean = when (property) {
    AnimPropertyType.SOURCE_TEXT -> source.isAnimated
    AnimPropertyType.FILL_COLOUR, AnimPropertyType.STROKE_COLOUR -> colour(property).isAnimated
    else -> scalar(property).isAnimated
}
fun TextProperties.withScalar(property: AnimPropertyType, value: AnimatableProperty1D): TextProperties = when (property) {
    AnimPropertyType.FONT_SIZE -> copy(size = value)
    AnimPropertyType.TRACKING -> copy(tracking = value)
    AnimPropertyType.LEADING -> copy(leading = value)
    AnimPropertyType.BASELINE_SHIFT -> copy(baselineShift = value)
    AnimPropertyType.STROKE_WIDTH -> copy(strokeWidth = value)
    AnimPropertyType.STROKE_OVER_FILL -> copy(strokeOverFill = value)
    AnimPropertyType.LIGATURES -> copy(ligatures = value)
    else -> error("Not a scalar text property")
}
fun TextProperties.withColour(property: AnimPropertyType, value: AnimatableColour): TextProperties = when (property) {
    AnimPropertyType.FILL_COLOUR -> copy(fill = value)
    AnimPropertyType.STROKE_COLOUR -> copy(strokeColour = value)
    else -> error("Not a colour text property")
}
