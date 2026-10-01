package com.fluxx.android.render

/** Transient harness input; no project state, history or persisted Text payload. */
data class TextFixture(
    val id: Long, val source: String = "AV office\nFluxx gy", val font: String = "fluxx.sans",
    val size: Float = 72f, val scale: Float = 1f, val opacity: Float = 1f,
    val rotation: Float = 0f, val flip: Boolean = false, val beforeRaster: Int = 0,
    val x: Float = 0f, val y: Float = 0f, val colour: Int = -1,
)
