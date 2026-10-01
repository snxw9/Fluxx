package com.fluxx.android.engine

/** Separate native devices/sessions for preview and export; each session has one owning worker. */
object RenderBridge {
    external fun upsertText(session: Long, font: String, text: String): Long
    external fun releaseText(session: Long, handle: Long)
    external fun textMetrics(session: Long, handle: Long): DoubleArray
    external fun prepareText(session: Long, handles: LongArray, proofOnly: Boolean = false): Boolean
    external fun configureText(session: Long, pages: Int)
    external fun textLayer(session: Long, handle: Long, matrix: FloatArray, size: Float,
        alignment: Int, argb: Int, opacity: Float): Boolean
    external fun textStats(session: Long): String
    /** Unsigned ARGB in a Long; -1 is failure. Call only on the owning paused render worker. */
    external fun readPreviewPixel(session: Long, normalizedX: Float, normalizedY: Float): Long
    init { System.loadLibrary("fluxxengine") }
    external fun create(surface: android.view.Surface?, width: Int, height: Int, assets: android.content.res.AssetManager): Long
    external fun destroy(session: Long)
    external fun resize(session: Long, width: Int, height: Int)
    external fun begin(session: Long, width: Int, height: Int, presentationWidth: Int, presentationHeight: Int): Boolean
    external fun layer(session: Long, slot: Long, buffer: android.hardware.HardwareBuffer, matrix: FloatArray, opacity: Float): Boolean
    external fun flush(session: Long): Boolean
    external fun finish(session: Long): Boolean
    external fun copyYuv(session: Long, y: java.nio.ByteBuffer, u: java.nio.ByteBuffer, v: java.nio.ByteBuffer,
        yRow: Int, uRow: Int, vRow: Int, uPixel: Int, vPixel: Int): Boolean
}

/** A whole frame fails before draws when the E1 proof's required glyph set cannot fit. */
class TextCapacityException(message: String) : IllegalStateException(message)
