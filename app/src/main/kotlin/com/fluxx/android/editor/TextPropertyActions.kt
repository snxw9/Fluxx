package com.fluxx.android.editor

import com.fluxx.android.model.*

/** Typed tracks share the existing action/gesture transaction boundary. */
internal object TextPropertyActions {
    private val properties = setOf(AnimPropertyType.FONT_SIZE, AnimPropertyType.FILL_COLOUR, AnimPropertyType.SOURCE_TEXT)
    fun apply(state: EditorState, action: EditorAction): EditorAction? {
        if (action is EditorAction.MoveKeyframe && action.property == null)
            return EditorAction.Batch(AnimPropertyType.entries.map { action.copy(property = it) })
        val id: Long; val property: AnimPropertyType
        when (action) {
            is EditorAction.ToggleAnimated -> { id = action.id; property = action.property }
            is EditorAction.SetKeyframe -> { id = action.id; property = action.property }
            is EditorAction.SetStringKeyframe -> { id = action.id; property = AnimPropertyType.SOURCE_TEXT }
            is EditorAction.SetColourKeyframe -> { id = action.id; property = AnimPropertyType.FILL_COLOUR }
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
            val enabled = when (property) { AnimPropertyType.SOURCE_TEXT -> text.source.isAnimated
                AnimPropertyType.FONT_SIZE -> text.size.isAnimated; else -> text.fill.isAnimated }
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
                val sampled = text.source.evaluate(local)
                val value = (action as? EditorAction.SetStringKeyframe)?.value ?: sampled
                val keys = edit(text.source.keyframes, { it.timeUs }, { k, t -> k.copy(timeUs = t) }) { StringKeyframe(time, value) }
                text.copy(source = AnimatableString(keys.isNotEmpty(), if (action is EditorAction.ResetPropertyAnimation) "" else sampled, keys))
            }
            AnimPropertyType.FONT_SIZE -> {
                val sampled = text.size.evaluate(local)
                val value = (action as? EditorAction.SetKeyframe)?.value ?: sampled
                val keys = edit(text.size.keyframes, { it.timeUs }, { k, t -> k.copy(timeUs = t) }) {
                    Keyframe1D(time, value, (action as? EditorAction.SetKeyframe)?.easing ?: EasingPreset.EASY_EASE) }
                text.copy(size = AnimatableProperty1D(keys.isNotEmpty(), if (action is EditorAction.ResetPropertyAnimation) 72f else sampled, keys))
            }
            AnimPropertyType.FILL_COLOUR -> {
                val sampled = text.fill.evaluate(local)
                val value = (action as? EditorAction.SetColourKeyframe)?.argb ?: sampled
                val keys = edit(text.fill.keyframes, { it.timeUs }, { k, t -> k.copy(timeUs = t) }) {
                    ColourKeyframe(time, value, (action as? EditorAction.SetColourKeyframe)?.easing ?: EasingPreset.EASY_EASE) }
                text.copy(fill = AnimatableColour(keys.isNotEmpty(), if (action is EditorAction.ResetPropertyAnimation) -1 else sampled, keys))
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
}.orEmpty()
