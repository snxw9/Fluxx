package com.fluxx.android.engine

/** Separate native devices/sessions for preview and export; each session has one owning worker. */
object RenderBridge {
    external fun upsertText(session: Long, font: String, text: String,ligatures: Boolean=true): Long
    external fun upsertStyledText(session: Long,font: String,input: ByteArray,ligatures: Boolean): Long
    /** Preparation only: validated geometry/SDF pixels, never imported font bytes. */
    external fun upsertPreparedText(session: Long,input: ByteArray): Long
    /** Preparation only; pins borrowed glyph-code/modifier storage without changing drawing arguments. */
    external fun clearTextCharacterBindings(session: Long)
    external fun bindTextCharacters(session: Long,handle: Long,catalogues: LongArray,codepoints: IntArray,fonts: IntArray,
        source: String,modifiers: java.nio.ByteBuffer,codes: java.nio.ByteBuffer)
    external fun releaseText(session: Long, handle: Long)
    external fun textMetrics(session: Long, handle: Long): DoubleArray
    external fun prepareText(session: Long, handles: LongArray, proofOnly: Boolean = false): Boolean
    /** Reuse the native handle set without JNI array-copy allocation on warmed text frames. */
    external fun reusePreparedText(session: Long): Boolean
    /** Atlas page cap shared by every SDF tier (4 MiB per 2048² R8 page). */
    external fun configureText(session: Long, pages: Int)
    /** SDF atlas tier (ppem 48/96/192/384) per prepared layout handle; synchronous for export. True when any tier changed. */
    external fun textTiers(session: Long, handles: LongArray, ppems: IntArray, synchronous: Boolean): Boolean
    /** Waits up to [timeoutMs] for off-thread tier glyphs; returns how many are still pending. */
    external fun awaitTextTiers(session: Long, timeoutMs: Int): Int
    /** TEXT FIX 9c: missing imported-font tier keys (marked in flight), as a tier request packet; empty when none. */
    external fun textTierRequests(session: Long): ByteArray
    /** TEXT FIX 9c: hands the isolated worker's tier reply to the renderer; returns accepted glyphs (-1 on a bad reply). */
    external fun supplyTextTierGlyphs(session: Long, reply: ByteArray): Int
    external fun textLayer(session: Long, handle: Long, matrix: FloatArray, size: Float,
        alignment: Int, argb: Int, opacity: Float, tracking: Float = 0f, leading: Float = 0f,
        baselineShift: Float = 0f, stroke: Int = 0xff000000.toInt(), strokeWidth: Float = 0f, paintOrder: Int = 0, softness: Float = 0f,
        modifiers: java.nio.ByteBuffer?=null): Boolean
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
