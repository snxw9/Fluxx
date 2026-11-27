package com.fluxx.android.render

import com.fluxx.android.model.CompositionLayer
import com.fluxx.android.model.LayerType
import com.fluxx.android.model.ProjectDocument
import java.util.concurrent.CopyOnWriteArrayList

/**
 * MP1 B3: safe culling (pure, JVM tested). Decides, for the frame being rendered, whether a layer is DRAWN and whether its
 * video is DECODED. It never decides evaluation: transforms and keyframes evaluate for every active layer every frame.
 * - The bounds come from the renderer's OWN matrices (`LayerGeometry.matrix` / `textMatrixInto`, the ones it draws with):
 *   the layer's local rectangle, padded in local units, through that matrix to normalised device coordinates, tested
 *   against the frame [-1, 1]². Exactly touching the frame edge is not culled (conservative).
 * - Culled: opacity 0; zero area (a zero scale axis) when nothing adds area; bounds fully outside the frame when the
 *   bounds can be proven. A layer another layer needs ([RenderDependencies]) is never culled.
 * - Occlusion (covered by an opaque layer) is out of scope.
 */
object Culling {
    enum class Decision { DRAW, CULLED }
    /** A culled video layer keeps decoding while it is expected to become visible within this (B3 rule 5). */
    const val WARM_LOOKAHEAD_US = 500_000L
    /** Safety margin after projection, in output pixels (sampling filters, crop rounding). */
    const val EDGE_PAD_PX = 2f

    /** The rectangle [left, top, width, height] in [m]'s local units, padded by [pad] local units, then [padX]/[padY] NDC. */
    fun rectOutside(m: FloatArray, left: Float, top: Float, width: Float, height: Float, pad: Float, padX: Float, padY: Float): Boolean {
        val x0 = left - pad; val y0 = top - pad; val x1 = left + width + pad; val y1 = top + height + pad
        var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        for (c in 0 until 4) {
            val x = if (c and 1 == 0) x0 else x1; val y = if (c and 2 == 0) y0 else y1
            val nx = m[0] * x + m[4] * y + m[12]; val ny = m[1] * x + m[5] * y + m[13]
            if (!nx.isFinite() || !ny.isFinite()) return false
            if (nx < minX) minX = nx; if (nx > maxX) maxX = nx; if (ny < minY) minY = ny; if (ny > maxY) maxY = ny
        }
        return maxX < -1f - padX || minX > 1f + padX || maxY < -1f - padY || minY > 1f + padY
    }

    /** The video / image / solid quad ([-1, 1]² in its local units, as video.vert draws it) is fully outside the frame. */
    fun quadOutside(m: FloatArray, padX: Float, padY: Float): Boolean = rectOutside(m, -1f, -1f, 2f, 2f, 0f, padX, padY)

    /**
     * The matrix that maps the unit quad onto the rectangle [left, top, width, height] (padded by [pad]) of [m]'s local
     * space, written to [out]: text bounds then go through the same quad test as video.
     */
    fun rectQuad(m: FloatArray, left: Float, top: Float, width: Float, height: Float, pad: Float, out: FloatArray) {
        val hw = width / 2f + pad; val hh = height / 2f + pad
        val cx = left + width / 2f; val cy = top + height / 2f
        java.util.Arrays.fill(out, 0f); out[10] = 1f; out[15] = 1f
        out[0] = m[0] * hw; out[1] = m[1] * hw; out[4] = m[4] * hh; out[5] = m[5] * hh
        out[12] = m[0] * cx + m[4] * cy + m[12]; out[13] = m[1] * cx + m[5] * cy + m[13]
    }

    /** The matrix collapses an axis: the layer covers no area. */
    fun zeroArea(m: FloatArray): Boolean = m[0] * m[5] - m[1] * m[4] == 0f

    /**
     * The draw decision. [positionProvable]: the drawn bounds are known (false for text with animators); [areaProvable]:
     * nothing adds area beyond the matrix (false with a stroke). [needed]: another layer needs this one's pixels.
     */
    fun decide(opacity: Float, matrix: FloatArray, positionProvable: Boolean, areaProvable: Boolean, padX: Float, padY: Float, needed: Boolean): Decision = when {
        needed -> Decision.DRAW
        opacity == 0f -> Decision.CULLED
        areaProvable && zeroArea(matrix) -> Decision.CULLED
        positionProvable && quadOutside(matrix, padX, padY) -> Decision.CULLED
        else -> Decision.DRAW
    }

    /** Whether the layer becomes visible within [WARM_LOOKAHEAD_US] after [timeUs], sampled every [stepUs] (animation is deterministic). */
    fun visibleSoon(timeUs: Long, stepUs: Long, visibleAt: (Long) -> Boolean): Boolean {
        val step = stepUs.coerceAtLeast(1_000L)
        var t = timeUs + step
        while (t < timeUs + WARM_LOOKAHEAD_US) { if (visibleAt(t)) return true; t += step }
        return visibleAt(timeUs + WARM_LOOKAHEAD_US)
    }
}

/**
 * MP1 B3: what can enlarge a layer beyond its source rectangle, in the layer's LOCAL units (an extension point: future
 * effects and layer styles add a [Contributor]). Null means the bounds cannot be proven, so the layer is never culled on
 * position (opacity 0 and zero area still apply when [addsArea] is false).
 */
object CullPadding {
    fun interface Contributor { fun pad(layer: CompositionLayer, localUs: Long): Float? }
    /** Registered contributors (none ship today besides the built-in text rule). */
    val contributors = CopyOnWriteArrayList<Contributor>()

    /** Text: the stroke (drawn outside the glyph outline) plus the SDF edge (1 px); unprovable with any animator. */
    fun text(layer: CompositionLayer, localUs: Long): Float? =
        if (layer.text.animators.isNotEmpty()) null else layer.text.strokeWidthAt(localUs).coerceAtLeast(0f) + 1f

    /** The local padding of [layer], or null when its bounds are unprovable. */
    fun localPad(layer: CompositionLayer, localUs: Long): Float? {
        var pad = if (layer.type == LayerType.TEXT) text(layer, localUs) ?: return null else 0f
        // Indexed (no iterator): this runs per layer per frame, inside text's zero-allocation window.
        for (i in 0 until contributors.size) pad += contributors[i].pad(layer, localUs) ?: return null
        return pad
    }

    /** Something draws beyond the matrix-scaled rectangle even at zero scale (a stroke): no zero-area cull. */
    fun addsArea(layer: CompositionLayer, localUs: Long): Boolean =
        layer.type == LayerType.TEXT && layer.text.strokeWidthAt(localUs) > 0f || contributors.isNotEmpty()
}

/**
 * MP1 B3: the layers whose pixels ANOTHER layer needs (track mattes, effect references, precomps). None exist in the model
 * yet, so it is empty. A layer in this set is never culled, whatever its own visibility; when those features arrive they
 * return their sources here and culling stays correct without other changes.
 */
object RenderDependencies {
    fun needed(project: ProjectDocument): Set<Long> = emptySet()
}
