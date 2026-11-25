package com.fluxx.android.ui.inspector

import com.fluxx.android.editor.GraphTarget
import com.fluxx.android.editor.AnimPropertyType
import com.fluxx.android.model.CompositionLayer

/** How a page writes a shown number. Each entry is the page's own formatter, not a copy of it. */
internal enum class DisplayFormat {
    /** Transform Position (composition pixels) and Opacity (%): one decimal, as the Transform / Opacity pages. */
    ONE_DECIMAL,
    /** Transform Rotation: the dial's turns notation `n x remainder` ([formatRotation]). */
    TURNS,
    /** Transform Scale: the ruler read-out ([ScaleRulerMath.readout]) without its % sign. */
    SCALE_READOUT,
    /** Text and Animate pages: [PropertyLimits.format] with the property's limit. */
    LIMIT,
}

/**
 * GRAPH G2.1 §5: one property channel's stored → shown conversion, shared by the inspector page and the Value graph.
 * shown = [offset] + stored × [factor]; [limit] is the page's typed / hard limit in SHOWN units.
 */
internal data class PropertyUnits(val name: String, val unit: String, val factor: Float, val offset: Float, val format: DisplayFormat,
    val limit: PropertyLimit) {
    init { require(factor != 0f && factor.isFinite() && offset.isFinite()) }
    /** The same float arithmetic the pages used inline (offset first, then the product). */
    fun toDisplay(stored: Float): Float = if (offset == 0f) stored * factor else offset + stored * factor
    fun toStored(display: Float): Float = if (offset == 0f) display / factor else (display - offset) / factor
    /** Stored-unit bounds of the limit's hard range (the page's clamp, expressed in stored units). */
    val storedMin get() = minOf(toStored(limit.hardMin), toStored(limit.hardMax))
    val storedMax get() = maxOf(toStored(limit.hardMin), toStored(limit.hardMax))
    /** The page's text for a shown value (no unit). */
    fun text(display: Float): String = when (format) {
        DisplayFormat.ONE_DECIMAL -> PropertyDisplay.oneDecimal(display)
        DisplayFormat.TURNS -> formatRotation(display)
        DisplayFormat.SCALE_READOUT -> ScaleRulerMath.readout(display / factor).removeSuffix("%")
        DisplayFormat.LIMIT -> PropertyLimits.format(limit, display)
    }
    /** The page's text for a stored value (no unit); Scale reads the stored fraction directly, like the ruler. */
    fun storedText(stored: Float): String =
        if (format == DisplayFormat.SCALE_READOUT) ScaleRulerMath.readout(stored).removeSuffix("%") else text(toDisplay(stored))
}

/** The conversions behind every page that shows an animatable number, defined once. */
internal object PropertyDisplay {
    fun oneDecimal(v: Float): String = String.format(java.util.Locale.ROOT, "%.1f", v)

    /**
     * Transform Position in composition pixels, origin at the composition's TOP-LEFT, Y growing DOWNWARD on screen:
     * px = composition/2 + stored × reference/2, with the layer's reference size (or the composition's).
     */
    fun positionX(compositionWidth: Int, referenceWidth: Int) = PropertyUnits("Position X", "px", referenceWidth / 2f, compositionWidth / 2f,
        DisplayFormat.ONE_DECIMAL, PropertyLimits.position(compositionWidth))
    fun positionY(compositionHeight: Int, referenceHeight: Int) = PropertyUnits("Position Y", "px", referenceHeight / 2f, compositionHeight / 2f,
        DisplayFormat.ONE_DECIMAL, PropertyLimits.position(compositionHeight))
    fun referenceWidth(layer: CompositionLayer, compositionWidth: Int) = layer.referenceWidth.takeIf { it > 0 } ?: compositionWidth
    fun referenceHeight(layer: CompositionLayer, compositionHeight: Int) = layer.referenceHeight.takeIf { it > 0 } ?: compositionHeight
    fun position(layer: CompositionLayer, compositionWidth: Int, compositionHeight: Int) = listOf(
        positionX(compositionWidth, referenceWidth(layer, compositionWidth)), positionY(compositionHeight, referenceHeight(layer, compositionHeight)))
    /** Scale: stored 1 = 100%. */
    val SCALE_X = PropertyUnits("Scale X", "%", 100f, 0f, DisplayFormat.SCALE_READOUT, PropertyLimits.LAYER_SCALE)
    val SCALE_Y = SCALE_X.copy(name = "Scale Y")
    /** Rotation in degrees (stored = shown) in the dial's turns notation. */
    val ROTATION = PropertyUnits("Rotation", "°", 1f, 0f, DisplayFormat.TURNS, PropertyLimits.ROTATION)
    /** Opacity: stored 1 = 100%. */
    val OPACITY = PropertyUnits("Opacity", "%", 100f, 0f, DisplayFormat.ONE_DECIMAL, PropertyLimits.OPACITY)
    /** Text and Animate fields: stored = shown, written with [PropertyLimits.format]. */
    fun limit(l: PropertyLimit, name: String = l.name) = PropertyUnits(name, l.unit, 1f, 0f, DisplayFormat.LIMIT, l)

    /** The channels of [target] in its page's units; empty when the property shows no number (colour, discrete). */
    fun of(layer: CompositionLayer, target: GraphTarget, compositionWidth: Int, compositionHeight: Int, channels: Int): List<PropertyUnits> {
        target.animator?.let { a ->
            if (a.property != null) {
                val l = AnimateFields.property(a.property).limit ?: return emptyList()
                return if (channels == 2) listOf(limit(l, "${l.name} X"), limit(l, "${l.name} Y")) else listOf(limit(l))
            }
            val l = a.field?.let { AnimateFields.selector(it).limit } ?: return emptyList()
            return listOf(limit(l))
        }
        return when (target.property!!) {
            AnimPropertyType.POSITION -> position(layer, compositionWidth, compositionHeight)
            AnimPropertyType.SCALE -> listOf(SCALE_X, SCALE_Y)
            AnimPropertyType.ROTATION -> listOf(ROTATION)
            AnimPropertyType.OPACITY -> listOf(OPACITY)
            AnimPropertyType.FONT_SIZE -> listOf(limit(PropertyLimits.FONT_SIZE))
            AnimPropertyType.TRACKING -> listOf(limit(PropertyLimits.TRACKING))
            AnimPropertyType.LEADING -> listOf(limit(PropertyLimits.LEADING))
            AnimPropertyType.BASELINE_SHIFT -> listOf(limit(PropertyLimits.BASELINE))
            AnimPropertyType.STROKE_WIDTH -> listOf(limit(PropertyLimits.STROKE_WIDTH))
            else -> emptyList()
        }
    }
}
