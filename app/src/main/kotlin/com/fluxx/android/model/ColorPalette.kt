package com.fluxx.android.model

data class ColorPalette(val id: String, val name: String? = null, val swatches: List<FluxxColor>) {
    init { require(id.isNotBlank() && swatches.size <= MAX_SWATCHES) }
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "Untitled"
    companion object { const val MAX_SWATCHES = 24 }
}

/** List and active ID are published together so collectors never observe a dangling selection. */
data class PaletteLibrary(val palettes: List<ColorPalette> = emptyList(), val activePaletteId: String? = null) {
    val activePalette get() = palettes.firstOrNull { it.id == activePaletteId }
}
