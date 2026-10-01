package com.fluxx.android.engine

import android.media.MediaFormat

/** Shared source orientation contract. Call before disabling decoder-side rotation. */
data class VideoGeometry(val rotation: Int, val pixelAspect: Float) {
    companion object {
        fun from(format: MediaFormat): VideoGeometry = VideoGeometry(
            if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0,
            if (format.containsKey("sar-width") && format.containsKey("sar-height"))
                format.getInteger("sar-width").toFloat() / format.getInteger("sar-height").coerceAtLeast(1) else 1f
        )
    }
}
