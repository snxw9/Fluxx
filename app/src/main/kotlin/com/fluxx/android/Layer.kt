package com.fluxx.android

/** Immutable single-video backend snapshot; editor truth lives in ProjectDocument. */
data class Layer(
    val id: Long = 0L,
    val positionX: Float = 0f,
    val positionY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotationDegrees: Float = 0f,
    val opacity: Float = 1f,
    val assetUri: android.net.Uri? = null
)

