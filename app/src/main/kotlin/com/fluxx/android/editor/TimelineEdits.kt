package com.fluxx.android.editor

import com.fluxx.android.model.*

/** Timeline gesture math independent of pixels, Compose, and rendering. */
object TimelineEdits {
    /** Nearest magnetic target wins; coincident targets are deterministic. */
    fun snapKeyframeTime(rawTimeUs: Long, playheadLocalUs: Long, durationUs: Long,
        frameRate: FrameRate, snapThresholdUs: Long, adjacentTimes: List<Long>, movingTime: Long,
        clipStartUs: Long = 0, markerTimes: List<Long> = emptyList()): Long {
        val raw = rawTimeUs.coerceIn(0L, durationUs)
        var closest = raw
        var distance = Long.MAX_VALUE
        fun consider(target: Long) {
            if (target !in 0L..durationUs) return
            val delta = kotlin.math.abs(raw - target)
            if (delta <= snapThresholdUs && delta < distance) { closest = target; distance = delta }
        }
        consider(playheadLocalUs)
        consider(0)
        consider(durationUs)
        for (time in adjacentTimes) if (time != movingTime) consider(time)
        for (time in markerTimes) consider(time)
        return if (distance != Long.MAX_VALUE) closest else
            (frameRate.snap(Math.addExact(clipStartUs, raw)) - clipStartUs).coerceIn(0L, durationUs)
    }

    fun move(project: ProjectDocument, layer: CompositionLayer, requestedStartUs: Long): EditorAction.Move =
        EditorAction.Move(layer.id, project.composition.frameRate.snap(requestedStartUs.coerceAtLeast(0)))

    fun trimStart(project: ProjectDocument,layer: CompositionLayer,requestedUs: Long): EditorAction.Trim {
        val end=requireNotNull(layer.timing.endUs) { "Resolve the clip duration first" }
        val minimum=if(layer.type==LayerType.VIDEO) (layer.timing.startUs-layer.timing.sourceInUs).coerceAtLeast(0) else 0L
        val maximum=(end-1).coerceAtLeast(minimum)
        val snapped=project.composition.frameRate.snap(requestedUs.coerceAtLeast(0)).coerceIn(minimum,maximum)
        return EditorAction.Trim(layer.id,snapped,end)
    }
    fun trimEnd(project: ProjectDocument,layer: CompositionLayer,requestedUs: Long): EditorAction.Trim {
        val minimum=layer.timing.startUs+1
        val maximum=if(layer.type==LayerType.VIDEO) layer.asset?.durationUs?.let {
            Math.addExact(layer.timing.startUs,it-layer.timing.sourceInUs)
        } ?: requireNotNull(layer.timing.endUs) else Long.MAX_VALUE
        if(maximum<minimum) return EditorAction.Trim(layer.id,layer.timing.startUs,layer.timing.startUs)
        val snapped=project.composition.frameRate.snap(requestedUs.coerceAtLeast(0)).coerceIn(minimum,maximum)
        return EditorAction.Trim(layer.id,layer.timing.startUs,snapped)
    }
}
