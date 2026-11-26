package com.fluxx.android.editor

import com.fluxx.android.model.*
import com.fluxx.android.ui.inspector.GraphValues

/**
 * TL3.1 B7: "add keyframe" on a twirl property row (pure, JVM tested): a key at the PLAYHEAD frame (layer-local time) with
 * the value evaluated there, WITHOUT changing the motion. Inside a segment the segment's easing is split exactly
 * ([EasingSplit], as on the colour bar): the left half on the left key, the right half on the new key. Before the first
 * key or after the last the new key takes the held end value (nothing changes). Colour rows use [ColourKeyAdd]; 2-D keys
 * add both axes; hold-only fields add a hold key with their current value; animator fields use `Key SET`. Disabled on a
 * frame that already has a key, outside the layer's clip, and on Bounce / Elastic segments. One Batch, one undo; no seek.
 */
object KeyAdd {
    enum class Why { OK, ON_KEY, OUTSIDE_CLIP, BOUNCE_ELASTIC, UNSUPPORTED }

    /** The playhead's frame in the layer's local (anchor-relative) time. */
    fun local(layer: CompositionLayer, rate: FrameRate, playheadUs: Long) = ColourKeyAdd.localFrame(layer, rate, playheadUs)

    fun check(project: ProjectDocument, layer: CompositionLayer, target: GraphTarget, playheadUs: Long): Why {
        val rate = project.composition.frameRate
        val comp = rate.snap(playheadUs.coerceAtLeast(0))
        val end = layer.timing.endUs ?: return Why.OUTSIDE_CLIP
        if (comp < layer.timing.startUs || comp > end) return Why.OUTSIDE_CLIP
        val t = local(layer, rate, playheadUs)
        val times = TwirlRows.keyTimes(layer, target)
        if (times.isEmpty()) return Why.UNSUPPORTED
        if (t in times) return Why.ON_KEY
        val left = times.lastOrNull { it < t }
        if (left != null && times.any { it > t } && !GraphModel.discrete(target)) {
            val e = GraphModel.curveAt(layer, target, left)?.type
            if (e == EasingPreset.BOUNCE || e == EasingPreset.ELASTIC) return Why.BOUNCE_ELASTIC
        }
        return Why.OK
    }

    fun action(project: ProjectDocument, layer: CompositionLayer, target: GraphTarget, playheadUs: Long): EditorAction? {
        if (check(project, layer, target, playheadUs) != Why.OK) return null
        val rate = project.composition.frameRate
        val t = local(layer, rate, playheadUs)
        // Colour: the bar's add inside a segment; outside the keys the held end colour.
        TwirlColour.track(layer, target)?.let { track ->
            ColourKeyAdd.action(layer, target, t)?.let { return it }
            val argb = track.evaluate(t)
            return EditorAction.Batch(listOf(target.animator?.let { EditorAction.EditTextAnimator(layer.id, AnimatorEdit.Key(it, AnimatorKeyOperation.SET, t, argb = argb)) }
                ?: EditorAction.SetColourKeyframe(layer.id, t, argb, property = target.property!!)))
        }
        // Source Text: a key holding the current text.
        if (target.property == AnimPropertyType.SOURCE_TEXT) return EditorAction.Batch(listOf(EditorAction.SetStringKeyframe(layer.id, t, layer.text.source.evaluate(t))))
        val times = TwirlRows.keyTimes(layer, target)
        val left = times.lastOrNull { it < t }; val right = times.firstOrNull { it > t }
        // Hold-only fields: a hold key with the current value.
        if (GraphModel.discrete(target)) {
            val v = target.animator?.let { TextAnimatorEdits.track(layer.text, it)?.hold(t) } ?: layer.text.scalar(target.property!!).hold(t)
            return EditorAction.Batch(listOf(set(layer, target, t, v, null, EasingPreset.HOLD)))
        }
        val sampler = GraphValues.sampler(layer, target) ?: return null
        val out = FloatArray(2); sampler(t, out)
        val two = GraphValues.channels(layer, target) == 2
        if (left == null || right == null) {
            // Before the first / after the last key: the held end value, nothing moves.
            val preset = GraphModel.curveAt(layer, target, left ?: right!!)?.type ?: EasingPreset.EASY_EASE
            return EditorAction.Batch(listOf(set(layer, target, t, out[0], if (two) out[1] else null, preset)))
        }
        val curve = GraphModel.curveAt(layer, target, left) ?: return null
        val x0 = ((t - left).toDouble() / (right - left)).toFloat()
        val halves = EasingSplit.split(curve, x0) ?: return null
        return EditorAction.Batch(listOf(set(layer, target, t, out[0], if (two) out[1] else null, halves.right.type),
            GraphModel.action(layer.id, target, listOf(left), halves.left),
            GraphModel.action(layer.id, target, listOf(t), halves.right)))
    }

    private fun set(layer: CompositionLayer, target: GraphTarget, t: Long, x: Float, y: Float?, easing: EasingPreset): EditorAction =
        target.animator?.let { EditorAction.EditTextAnimator(layer.id, AnimatorEdit.Key(it, AnimatorKeyOperation.SET, t, value = x, y = y, easing = easing)) }
            ?: if (y != null) EditorAction.SetKeyframe2D(layer.id, target.property!!, t, x, y, easing)
            else EditorAction.SetKeyframe(layer.id, target.property!!, t, x, easing)
}
