package com.fluxx.android.editor

import com.fluxx.android.model.*
import java.util.TreeSet

/** Each priority tier is independent; an exhausted direction falls through, never clamps a tier. */
object ContextualNavigation {
    fun findPrevious(project: ProjectDocument, selectedLayer: CompositionLayer?, isInspectorOpen: Boolean,
        activeProperty: AnimPropertyType?, playheadUs: Long, selectedMarkerId: Long? = null): Long =
        find(project, selectedLayer, isInspectorOpen, activeProperty, playheadUs, false, selectedMarkerId)

    fun findNext(project: ProjectDocument, selectedLayer: CompositionLayer?, isInspectorOpen: Boolean,
        activeProperty: AnimPropertyType?, playheadUs: Long, selectedMarkerId: Long? = null): Long =
        find(project, selectedLayer, isInspectorOpen, activeProperty, playheadUs, true, selectedMarkerId)

    private fun find(project: ProjectDocument, selected: CompositionLayer?, inspector: Boolean,
        property: AnimPropertyType?, time: Long, next: Boolean, selectedMarkerId: Long?): Long {
        fun candidate(times: Iterable<Long>): Long? {
            val tier = TreeSet<Long>().apply { addAll(times.filter { it >= 0 }) }
            return if (next) tier.higher(time) else tier.lower(time)
        }
        val comp = project.composition
        val markers = selected?.markers ?: comp.markers
        val anchor = selected?.resolvedKeyframeAnchorUs ?: 0L
        val frame = comp.frameRate.nearestFrame(time)
        // Markers are reachable from any frame. A focused property still owns keyframe navigation.
        if (!inspector || property == null) {
            val times = markers.map { Math.addExact(anchor, it.timeUs) }.filter { it >= 0 }
                .filter { if (next) comp.frameRate.nearestFrame(it) > frame else comp.frameRate.nearestFrame(it) < frame }
            candidate(times)?.let { return it }
        }
        if (inspector && selected != null && property != null) {
            candidate(keyframeTimes(selected, property))?.let { return it }
        }
        if (selected != null) candidate(listOfNotNull(selected.timing.startUs, selected.timing.endUs))?.let { return it }
        val duration = project.composition.resolvedDurationUs
        val global = buildList {
            add(0L); add(duration)
            project.composition.layers.forEach { add(it.timing.startUs); it.timing.endUs?.let(::add) }
        }
        return candidate(global) ?: if (next) maxOf(time, duration) else 0L
    }

    internal fun keyframeTimes(layer: CompositionLayer, property: AnimPropertyType): List<Long> {
        val animation = layer.animTransform
        val local = when (property) {
            AnimPropertyType.POSITION -> animation.position.takeIf { it.isAnimated }?.keyframes?.map { it.timeUs }
            AnimPropertyType.SCALE -> animation.scale.takeIf { it.isAnimated }?.keyframes?.map { it.timeUs }
            AnimPropertyType.ROTATION -> animation.rotation.takeIf { it.isAnimated }?.keyframes?.map { it.timeUs }
            AnimPropertyType.OPACITY -> animation.opacity.takeIf { it.isAnimated }?.keyframes?.map { it.timeUs }
            AnimPropertyType.FONT_SIZE, AnimPropertyType.FILL_COLOUR, AnimPropertyType.SOURCE_TEXT -> layer.propertyKeyTimes(property)
        }.orEmpty()
        return local.mapNotNull {
            try { Math.addExact(layer.resolvedKeyframeAnchorUs, it).takeIf { time -> time >= 0 } }
            catch (_: ArithmeticException) { null }
        }
    }
}
