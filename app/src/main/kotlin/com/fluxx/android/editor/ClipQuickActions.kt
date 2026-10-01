package com.fluxx.android.editor

import com.fluxx.android.model.CompositionLayer
import com.fluxx.android.model.LayerType

enum class ClipQuickAction(val label: String) {
    MOVE_IN("Move In"), EXTEND_IN("Extend In"), TRIM_IN("Trim In"), SPLIT("Split"),
    TRIM_OUT("Trim Out"), EXTEND_OUT("Extend Out"), MOVE_OUT("Move Out")
}

/** Shared by the inspector and JVM tests; every action is one reducer transition. */
object ClipQuickActions {
    fun available(layer: CompositionLayer, playheadUs: Long): List<ClipQuickAction> {
        val start = layer.timing.startUs
        val end = layer.timing.endUs ?: (start + minOf(5_000_000L, Long.MAX_VALUE - start))
        return when {
            playheadUs < start -> listOf(ClipQuickAction.MOVE_IN, ClipQuickAction.EXTEND_IN)
            playheadUs > end -> listOf(ClipQuickAction.EXTEND_OUT, ClipQuickAction.MOVE_OUT)
            playheadUs > start && playheadUs < end && layer.timing.durationUs != null ->
                listOf(ClipQuickAction.TRIM_IN, ClipQuickAction.SPLIT, ClipQuickAction.TRIM_OUT)
            else -> listOf(ClipQuickAction.TRIM_IN, ClipQuickAction.TRIM_OUT)
        }
    }

    /** Null means unavailable, unresolved source timing, or already at the source/composition limit. */
    fun action(kind: ClipQuickAction, layer: CompositionLayer, playheadUs: Long,
        compositionDurationUs: Long?): EditorAction? {
        if (playheadUs < 0 || kind !in available(layer, playheadUs)) return null
        val timing = layer.timing
        val start = timing.startUs
        if (kind == ClipQuickAction.MOVE_IN) return EditorAction.Move(layer.id, playheadUs)
        val duration = timing.durationUs ?: return null
        val end = requireNotNull(timing.endUs)
        fun trim(newStart: Long, newEnd: Long): EditorAction? =
            if (newStart == start && newEnd == end) null else EditorAction.Trim(layer.id, newStart, newEnd)
        return when (kind) {
            ClipQuickAction.MOVE_IN -> error("Handled above")
            ClipQuickAction.MOVE_OUT -> EditorAction.Move(layer.id, (playheadUs - duration).coerceAtLeast(0L))
            ClipQuickAction.TRIM_IN -> trim(playheadUs, end)
            ClipQuickAction.TRIM_OUT -> trim(start, playheadUs)
            ClipQuickAction.SPLIT -> EditorAction.Split(layer.id, playheadUs)
            ClipQuickAction.EXTEND_IN -> {
                val lower = if (layer.type == LayerType.VIDEO) (start - timing.sourceInUs).coerceAtLeast(0L) else 0L
                trim(maxOf(lower, playheadUs), end)
            }
            ClipQuickAction.EXTEND_OUT -> {
                val remaining = if (layer.type == LayerType.VIDEO) layer.asset?.durationUs?.minus(timing.sourceInUs) else null
                val upper = if (remaining != null) start + minOf(remaining, Long.MAX_VALUE - start)
                    else compositionDurationUs ?: Long.MAX_VALUE
                trim(start, maxOf(end, minOf(playheadUs, upper)))
            }
        }
    }
}
