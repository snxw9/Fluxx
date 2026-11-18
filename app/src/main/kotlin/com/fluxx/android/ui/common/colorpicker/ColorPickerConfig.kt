package com.fluxx.android.ui.common.colorpicker

import androidx.compose.ui.graphics.Color
import com.fluxx.android.model.FluxxColor

// UI conversion lives here to keep the domain value independent of Compose.
fun FluxxColor.toComposeColor() = Color(r, g, b, a)
