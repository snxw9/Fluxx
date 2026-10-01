package com.fluxx.android.ui.common.colorpicker

import androidx.compose.ui.graphics.Color
import com.fluxx.android.model.FluxxColor

data class ColorPickerConfig(
    val showColorControl: Boolean = true,
    val showRgbSliders: Boolean = true,
    val showEyedropper: Boolean = true,
    val showHexInput: Boolean = true,
    val showAlpha: Boolean = true,
    val showPalette: Boolean = true
)

// UI conversion lives here to keep the domain value independent of Compose.
fun FluxxColor.toComposeColor() = Color(r, g, b, a)
