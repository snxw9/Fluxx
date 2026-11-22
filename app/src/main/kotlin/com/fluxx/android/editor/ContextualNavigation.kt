package com.fluxx.android.editor

import com.fluxx.android.model.*
import java.util.TreeSet

/**
 * Contextual Previous / Next (TEXT F3.1, F3.3): one global rule, per direction. Tiers are tried in order; the first
 * tier with a stop strictly earlier (Previous) or strictly later (Next) than the playhead frame wins, and an exhausted
 * tier falls through. The same rule applies on every inspector page and with the inspector closed.
 *
 * With a layer selected:
 *  1. the focused property or animator field's keyframes (empty when nothing is focused, the inspector is closed or
 *     the focused field is static);
 *  2. (TEXT F3.3) every keyframe time of the selected layer: transform and text properties, animator properties,
 *     selector fields, seed keys and the animator enabled flag. So a focused field whose keys all lie behind the
 *     playhead falls through to the layer's other keys ahead, never straight to the markers or the out point;
 *  3. markers inside the layer span (in <= t <= out): the layer's own markers and the composition's markers;
 *  4. the layer in / out points;
 *  5. markers outside the layer span: the layer's markers (also those retained outside a trimmed clip) and the
 *     composition's markers;
 *  6. the timeline bounds (start, end and every layer boundary).
 * With nothing selected: composition markers, then the timeline bounds.
 *
 * Guards: negative and overflowing times are ignored, tiers are ordered sets (lower / higher), and Next stays put
 * beyond the last bound.
 */
object ContextualNavigation {
    fun findPrevious(project: ProjectDocument, selectedLayer: CompositionLayer?, isInspectorOpen: Boolean,
        activeProperty: AnimPropertyType?, playheadUs: Long, selectedMarkerId: Long? = null, animatorTrack: AnimatorTrackAddress? = null): Long =
        find(project, selectedLayer, isInspectorOpen, activeProperty, playheadUs, false, animatorTrack)

    fun findNext(project: ProjectDocument, selectedLayer: CompositionLayer?, isInspectorOpen: Boolean,
        activeProperty: AnimPropertyType?, playheadUs: Long, selectedMarkerId: Long? = null, animatorTrack: AnimatorTrackAddress? = null): Long =
        find(project, selectedLayer, isInspectorOpen, activeProperty, playheadUs, true, animatorTrack)

    /** The tiers for [selected] (or the no-selection tiers), in order. Exposed for tests. */
    internal fun tiers(project: ProjectDocument, selected: CompositionLayer?, inspector: Boolean,
        property: AnimPropertyType?, animatorTrack: AnimatorTrackAddress?): List<List<Long>> {
        val comp = project.composition
        val compMarkers = comp.markers.map { it.timeUs }
        val duration = comp.resolvedDurationUs
        val bounds = buildList {
            add(0L); add(duration)
            comp.layers.forEach { add(it.timing.startUs); it.timing.endUs?.let(::add) }
        }
        if (selected == null) return listOf(compMarkers, bounds)
        val focus = focusedKeys(selected, inspector, property, animatorTrack)
        val layerMarkers = selected.markers.mapNotNull { add(selected.resolvedKeyframeAnchorUs, it.timeUs) }
        val inPoint = selected.timing.startUs
        val outPoint = selected.timing.endUs ?: Long.MAX_VALUE
        val markers = layerMarkers + compMarkers
        val inside = markers.filter { it in inPoint..outPoint }
        val outside = markers.filter { it !in inPoint..outPoint }
        return listOf(focus, layerKeys(selected), inside, listOfNotNull(selected.timing.startUs, selected.timing.endUs), outside, bounds)
    }

    /** Keys of the focused field: an animator field wins over a layer property; empty when the field is static. */
    private fun focusedKeys(selected: CompositionLayer, inspector: Boolean, property: AnimPropertyType?, animatorTrack: AnimatorTrackAddress?): List<Long> {
        if (!inspector) return emptyList()
        val animator = animatorTrack?.takeIf { selected.type == LayerType.TEXT && TextAnimatorEdits.exists(selected.text, it) }
        if (animator != null) return TextAnimatorActions.keyTimes(selected, animator)
        return property?.let { keyframeTimes(selected, it) }.orEmpty()
    }

    private fun find(project: ProjectDocument, selected: CompositionLayer?, inspector: Boolean,
        property: AnimPropertyType?, time: Long, next: Boolean, animatorTrack: AnimatorTrackAddress?): Long {
        val rate = project.composition.frameRate
        val frame = rate.nearestFrame(time)
        for (tier in tiers(project, selected, inspector, property, animatorTrack)) {
            // Strictly earlier / later than the playhead frame, so a stop never lands on the frame already shown.
            val set = TreeSet<Long>()
            for (t in tier) if (t >= 0 && (if (next) rate.nearestFrame(t) > frame else rate.nearestFrame(t) < frame)) set.add(t)
            (if (next) set.firstOrNull() else set.lastOrNull())?.let { return it }
        }
        return if (next) maxOf(time, project.composition.resolvedDurationUs) else 0L
    }

    /** Every keyframe time of [layer] (aggregateKeyTimes: transform, text, animator properties, selectors, seeds, enabled). */
    internal fun layerKeys(layer: CompositionLayer): List<Long> = layer.aggregateKeyTimes().mapNotNull { add(layer.resolvedKeyframeAnchorUs, it) }

    private fun add(a: Long, b: Long): Long? = try { Math.addExact(a, b).takeIf { it >= 0 } } catch (_: ArithmeticException) { null }

    internal fun keyframeTimes(layer: CompositionLayer, property: AnimPropertyType): List<Long> {
        val animation = layer.animTransform
        val local = when (property) {
            AnimPropertyType.POSITION -> animation.position.takeIf { it.isAnimated }?.keyframes?.map { it.timeUs }
            AnimPropertyType.SCALE -> animation.scale.takeIf { it.isAnimated }?.keyframes?.map { it.timeUs }
            AnimPropertyType.ROTATION -> animation.rotation.takeIf { it.isAnimated }?.keyframes?.map { it.timeUs }
            AnimPropertyType.OPACITY -> animation.opacity.takeIf { it.isAnimated }?.keyframes?.map { it.timeUs }
            else -> layer.propertyKeyTimes(property)
        }.orEmpty()
        return local.mapNotNull { add(layer.resolvedKeyframeAnchorUs, it) }
    }
}
