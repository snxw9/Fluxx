package com.fluxx.android.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * TLP.1 the on-preview selector box: while an animator selector is focused on the Animate tab, the layer box is
 * replaced by ONE box around every character that selector covers. Pure and JVM tested.
 */
object SelectorCoverageBox {
    /** A cluster counts as covered from this much of the selector's own amount (magnitude, so negative amounts count). */
    const val THRESHOLD = .05

    /**
     * The box (left, top, right, bottom in layout space) from the first to the last covered cluster, the union of
     * every cluster box in that index range (so a non-contiguous coverage gives one box spanning it). [boxes] holds
     * four values per cluster. Null when nothing reaches [threshold] (the preview then shows the normal layer box).
     */
    fun box(coverage: DoubleArray, boxes: FloatArray, threshold: Double = THRESHOLD): FloatArray? {
        require(boxes.size == coverage.size * 4)
        val first = coverage.indexOfFirst { abs(it) >= threshold }
        if (first < 0) return null
        val last = coverage.indexOfLast { abs(it) >= threshold }
        var left = Float.POSITIVE_INFINITY; var top = left; var right = Float.NEGATIVE_INFINITY; var bottom = right
        for (c in first..last) {
            val i = c * 4
            if (!(boxes[i].isFinite() && boxes[i + 1].isFinite() && boxes[i + 2].isFinite() && boxes[i + 3].isFinite())) continue
            left = min(left, boxes[i]); top = min(top, boxes[i + 1]); right = max(right, boxes[i + 2]); bottom = max(bottom, boxes[i + 3])
        }
        return if (left.isFinite() && right >= left && bottom >= top) floatArrayOf(left, top, right, bottom) else null
    }
}
