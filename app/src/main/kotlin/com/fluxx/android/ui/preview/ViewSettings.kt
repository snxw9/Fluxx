package com.fluxx.android.ui.preview

/** Editor-session view preferences; never persisted into the composition or undo history. */
data class ViewSettings(
    val showBoundingBoxes: Boolean = true,
    val showSelectionHandles: Boolean = true,
    val showAnchorPoints: Boolean = true,
    val showGrid: Boolean = false,
    // Null = fit; 1 = one composition pixel per physical viewport pixel (100%).
    val zoomScale: Float? = null,
    val panX: Float = 0f,
    val panY: Float = 0f
) {
    fun anchorVisible(editing: Boolean): Boolean = showAnchorPoints || editing

    fun viewportScale(fitScale: Float): Float =
        if (fitScale > 0f) (zoomScale ?: fitScale) / fitScale else 1f

    fun zoomBy(factor: Float, fitScale: Float) = copy(
        zoomScale = ((zoomScale ?: fitScale) * factor).coerceIn(1f / 64f, 32f))

    fun fit() = copy(zoomScale = null, panX = 0f, panY = 0f)
    fun actualSize() = copy(zoomScale = 1f, panX = 0f, panY = 0f)

    /** Inverse of the centred Compose graphics layer, for native eyedropper coordinates. */
    fun unzoom(x: Float, y: Float, width: Float, height: Float, fitScale: Float): Pair<Float, Float> {
        val scale = viewportScale(fitScale)
        return ((x - width / 2f - panX) / scale + width / 2f) to
            ((y - height / 2f - panY) / scale + height / 2f)
    }
}
