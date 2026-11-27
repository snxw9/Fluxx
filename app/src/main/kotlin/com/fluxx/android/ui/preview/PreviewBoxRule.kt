package com.fluxx.android.ui.preview

/**
 * TLP.2 the ONE preview box. While a focused selector covers something, the layer's bounding box itself turns red
 * and wraps the covered characters: no white outline and no resize / rotate handles are drawn (they would read as a
 * second box). Otherwise the normal layer box (white, with handles when the view setting shows them). Display only.
 */
internal object PreviewBoxRule {
    fun handles(showHandlesSetting: Boolean, selectorBoxShown: Boolean) = showHandlesSetting && !selectorBoxShown
    /** The overlay's state description (TalkBack and tests). */
    fun state(selectorBoxShown: Boolean, handles: Boolean) = when {
        selectorBoxShown -> "Selector box"
        handles -> "Layer box with handles"
        else -> "Layer box"
    }
}
