package com.fluxx.android.engine

import android.content.res.AssetManager

/** CPU-only E1 proof bridge. Create/use/close on a worker; never shape on the UI thread.
 * Sessions share a native catalog while live; no FreeType pointers or GPU resources cross JNI.
 * E2 will consume cached native layouts directly; this JSON API is for parity diagnostics.
 */
class FontSession(assets: AssetManager) : AutoCloseable {
    private var handle = TextNative.create(assets).also { check(it != 0L) }

    @Synchronized
    fun shape(fontId: String, text: String): String = withHandle {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 16384) { "Text exceeds the E1 input limit" }
        TextNative.shape(it, fontId, bytes)
    }

    @Synchronized
    internal fun <T> withHandle(block: (Long) -> T): T {
        check(handle != 0L) { "Font session is closed" }
        return block(handle)
    }
    @Synchronized
    fun metrics(fontId: String, text: String, ligatures: Boolean=true): DoubleArray = withHandle {
        TextNative.metrics(it, fontId, text.toByteArray(Charsets.UTF_8),ligatures)
    }
    @Synchronized
    fun styledMetrics(fontId: String,input: ByteArray,ligatures: Boolean): DoubleArray = withHandle {
        TextNative.styledMetrics(it,fontId,input,ligatures)
    }
    @Synchronized
    fun preparedPacket(fontId: String,input: ByteArray,ligatures: Boolean,nominal: Boolean=false): ByteArray = withHandle {
        require(input.size<=1024*1024)
        TextNative.preparedPacket(it,fontId,input,ligatures,nominal)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) TextNative.close(handle)
        handle = 0
    }
}

internal object TextNative {
    init { System.loadLibrary("fluxxengine") }
    external fun create(assets: AssetManager): Long
    external fun close(handle: Long)
    external fun shape(handle: Long, font: String, utf8: ByteArray): String
    external fun metrics(handle: Long, font: String, utf8: ByteArray,ligatures: Boolean): DoubleArray
    external fun styledMetrics(handle: Long,font: String,input: ByteArray,ligatures: Boolean): DoubleArray
    external fun preparedPacket(handle: Long,font: String,input: ByteArray,ligatures: Boolean,nominal: Boolean): ByteArray
    external fun preparedMetrics(input: ByteArray): DoubleArray
    external fun preparedPreview(input: ByteArray,width: Int,height: Int,colour: Int): IntArray
}

/** No imported font parsing: this validates an immutable prepared geometry/pixel packet. */
object PreparedTextData {
    fun preview(input: ByteArray,width: Int,height: Int,colour: Int): IntArray {
        require(input.size<=32*1024*1024 && width in 1..1024 && height in 1..256)
        return TextNative.preparedPreview(input,width,height,colour)
    }
    fun metrics(input: ByteArray): DoubleArray {
        require(input.size<=32*1024*1024)
        return TextNative.preparedMetrics(input)
    }
}
