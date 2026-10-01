package com.fluxx.android.render

import kotlin.math.roundToInt

/** Session-only preview quality. Never stored in a ProjectDocument or used by export. */
enum class PreviewResolution(val fraction: Float, val label: String, val badge: String) {
    FULL(1f, "Full", "1"), HALF(.5f, "Half (1/2)", "½"),
    THIRD(1f / 3f, "Third (1/3)", "⅓"), QUARTER(.25f, "Quarter (1/4)", "¼");

    fun calculateDimensions(compWidth: Int, compHeight: Int): Pair<Int, Int> {
        require(compWidth > 0 && compHeight > 0)
        fun scaled(value: Int) = ((value * fraction).roundToInt() and -2).coerceAtLeast(2)
        return scaled(compWidth) to scaled(compHeight)
    }
}
