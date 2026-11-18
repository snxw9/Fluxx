package com.fluxx.android.ui.timeline

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import com.fluxx.android.editor.AnimPropertyType
import com.fluxx.android.editor.aggregateKeyTimes
import com.fluxx.android.editor.propertyKeyTimes
import com.fluxx.android.model.CompositionLayer

/**
 * Keyframe markers on a timeline layer bar. The bar always shows that a layer has keys: markers for ALL keyed
 * properties (every transform property; for text every text property, Source Text style/paragraph keys and animator
 * tracks). Keys of the selected/focused property draw at full ink and normal size; the others are quiet (dimmed,
 * smaller). Keys at the same time collapse into one marker, full ink if any of them is the focused property's.
 * Times are aggregated once per layer change (the caller remembers the result), never per frame.
 */
internal object LayerKeyMarkers {
    const val FOCUSED_DP = 10f
    const val QUIET_DP = 7f
    const val QUIET_ALPHA = .45f
    /** Markers sit along the lower part of the bar, clear of the centred layer name. */
    const val BOTTOM_INSET_DP = 2f

    data class Marker(val clipTimeUs: Long, val focused: Boolean) {
        val sizeDp get() = if (focused) FOCUSED_DP else QUIET_DP
        val alpha get() = if (focused) 1f else QUIET_ALPHA
    }

    /** Every keyed property of the layer, anchor-relative, sorted and distinct (the text-property bug fix). */
    fun localTimes(layer: CompositionLayer): List<Long> = layer.aggregateKeyTimes()

    /** Clip-relative times of every key, for drawing/snapping. Stored keys are anchor-relative and may be negative. */
    fun clipTimes(layer: CompositionLayer, anchorOffsetUs: Long): List<Long> = localTimes(layer).map { Math.addExact(it, anchorOffsetUs) }

    /** Clip-relative times of the focused property's keys (empty when no property is focused). */
    fun focusedClipTimes(layer: CompositionLayer, property: AnimPropertyType?, anchorOffsetUs: Long): Set<Long> =
        if (property == null) emptySet() else layer.propertyKeyTimes(property).map { it + anchorOffsetUs }.toSet()

    /** One marker per distinct time; focused wins when several properties share a time. */
    fun markers(all: List<Long>, focused: Set<Long>): List<Marker> =
        (all + focused).distinct().sorted().map { Marker(it, it in focused) }

    /** Rebuilds [into] as a diamond filling [size]; reuses the caller's Path so drawing allocates nothing per frame. */
    fun diamond(into: Path, size: Size): Path {
        into.reset()
        into.moveTo(size.width / 2f, 0f); into.lineTo(size.width, size.height / 2f)
        into.lineTo(size.width / 2f, size.height); into.lineTo(0f, size.height / 2f); into.close()
        return into
    }
}
