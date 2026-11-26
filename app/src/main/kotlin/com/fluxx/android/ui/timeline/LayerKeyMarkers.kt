package com.fluxx.android.ui.timeline

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import com.fluxx.android.editor.AnimPropertyType
import com.fluxx.android.editor.AnimatorTrackAddress
import com.fluxx.android.editor.TextAnimatorEdits
import com.fluxx.android.editor.aggregateKeyTimes
import com.fluxx.android.editor.propertyKeyTimes
import com.fluxx.android.model.CompositionLayer
import com.fluxx.android.model.LayerType

/**
 * Keyframe markers on a timeline layer bar. The bar always shows that a layer has keys: markers for ALL keyed
 * properties (every transform property; for text every text property, Source Text style/paragraph keys and animator
 * tracks). Keys of the selected/focused property draw at full ink and normal size; the others are quiet (dimmed,
 * smaller). Keys at the same time collapse into one marker, full ink if any of them is the focused property's.
 * Markers sit on the bar's vertical midline, drawn above the layer name with a knock-out ring in the bar colour.
 * Times are aggregated once per layer change (the caller remembers the result), never per frame.
 */
internal object LayerKeyMarkers {
    const val FOCUSED_DP = 10f
    const val QUIET_DP = 7f
    const val QUIET_ALPHA = .45f
    /** Knock-out ring in the bar colour around every marker, so a marker over the layer name stays readable. */
    const val KNOCKOUT_DP = 1.5f
    /** Horizontal hit target of a focused-property key (the vertical target is the whole row). */
    const val HIT_WIDTH_DP = 48f

    /** Top of a marker of [sizeDp] centred on the vertical midline of a bar [barHeightDp] tall starting at [barTopDp]. */
    fun markerTop(barTopDp: Float, barHeightDp: Float, sizeDp: Float) = barTopDp + (barHeightDp - sizeDp) / 2f
    /** Centre of the marker: always the bar centre, whatever the bar height or marker size. */
    fun markerCentreY(barTopDp: Float, barHeightDp: Float, sizeDp: Float) = markerTop(barTopDp, barHeightDp, sizeDp) + sizeDp / 2f
    /** Hit region of a key at [xDp]: [HIT_WIDTH_DP] wide, the full row tall, so it is centred on the marker. */
    fun hitRegion(xDp: Float, rowHeightDp: Float) = floatArrayOf(xDp - HIT_WIDTH_DP / 2f, 0f, xDp + HIT_WIDTH_DP / 2f, rowHeightDp)
    /** The knock-out is always drawn: checking glyph overlap per frame would cost more than the extra stroke. */
    const val KNOCKOUT_ALWAYS = true
    /** Stroke width that leaves a [KNOCKOUT_DP] ring outside the diamond (strokes are centred on the path). */
    const val KNOCKOUT_STROKE_DP = 2 * KNOCKOUT_DP

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

    /** Clip-relative times of the focused animator field's keys (Animate tab); empty when none is focused. */
    fun focusedAnimatorClipTimes(layer: CompositionLayer, address: AnimatorTrackAddress?, anchorOffsetUs: Long): Set<Long> =
        if (address == null || layer.type != LayerType.TEXT || !TextAnimatorEdits.exists(layer.text, address)) emptySet()
        else TextAnimatorEdits.keyTimes(layer, address).map { it + anchorOffsetUs }.toSet()

    /** One marker per distinct time; focused wins when several properties share a time. */
    fun markers(all: List<Long>, focused: Set<Long>): List<Marker> =
        (all + focused).distinct().sorted().map { Marker(it, it in focused) }

    /**
     * TL3.1 B3: THE key shape, everywhere keys are drawn (layer bars, twirl rows, sheet-open keys, drags). A diamond, or a
     * SQUARE for a hold key (departing easing Hold, or a hold-only track). The square has the diamond's optical size (its
     * side is [SQUARE_FRACTION] of the box); hit targets are unchanged.
     */
    fun keyShape(into: Path, size: Size, hold: Boolean): Path {
        if (!hold) return diamond(into, size)
        into.reset()
        val side = size.width * SQUARE_FRACTION; val l = (size.width - side) / 2f; val t = (size.height - side) / 2f
        into.addRect(androidx.compose.ui.geometry.Rect(l, t, l + side, t + side))
        return into
    }
    const val SQUARE_FRACTION = .72f

    /** A hold key: a hold-only track, or a key whose departing easing is Hold. */
    fun holdAt(layer: CompositionLayer, target: com.fluxx.android.editor.GraphTarget, local: Long): Boolean =
        com.fluxx.android.editor.GraphModel.discrete(target) || com.fluxx.android.editor.GraphModel.curveAt(layer, target, local)?.type == com.fluxx.android.model.EasingPreset.HOLD

    /** Clip-relative times where EVERY key of the layer is a hold key (the bar's aggregated marker is then a square). */
    fun holdClipTimes(layer: CompositionLayer, anchorOffsetUs: Long): Set<Long> {
        val all = HashMap<Long, Boolean>()
        for ((target, _, _) in com.fluxx.android.editor.TwirlRows.targets(layer)) for (t in com.fluxx.android.editor.TwirlRows.keyTimes(layer, target)) {
            val c = Math.addExact(t, anchorOffsetUs)
            all[c] = (all[c] ?: true) && holdAt(layer, target, t)
        }
        return all.filterValues { it }.keys
    }

    /** Rebuilds [into] as a diamond filling [size]; reuses the caller's Path so drawing allocates nothing per frame. */
    fun diamond(into: Path, size: Size): Path {
        into.reset()
        into.moveTo(size.width / 2f, 0f); into.lineTo(size.width, size.height / 2f)
        into.lineTo(size.width / 2f, size.height); into.lineTo(0f, size.height / 2f); into.close()
        return into
    }
}
